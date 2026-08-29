import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  buildHardwareFaultTransition,
  createDiagnosticDeliveryWorkflow,
  DIAGNOSTIC_OUTBOX_TYPES
} from '../src/services/diagnosticDeliveryWorkflow.js'

const createHarness = (overrides = {}) => {
  const calls = []
  const stored = []
  const workflow = createDiagnosticDeliveryWorkflow({
    saveEvent: async (event) => {
      calls.push(['save', event])
      stored.push({ ...event })
      return stored.at(-1)
    },
    listDueEvents: async (eventType) => stored.filter((event) => event.eventType === eventType),
    markSent: async (eventId) => { calls.push(['sent', eventId]) },
    markFailed: async (eventId, error, delay) => { calls.push(['failed', eventId, error.message, delay]) },
    getCommunicationMode: () => 'MQTT',
    isMqttConnected: async () => true,
    sendMqttAndWaitForAck: async (eventType, data, msgId, definition) => {
      calls.push(['mqtt', eventType, data, msgId, definition])
      return { response: { data: { code: 0, msg: 'success' } } }
    },
    sendHttp: async (eventType, data) => {
      calls.push(['http', eventType, data])
      return { status: 200, body: { code: 0, msg: 'success' } }
    },
    validateMqttAck: (result) => {
      assert.equal(result.response.data.code, 0)
      calls.push(['mqtt-ack'])
    },
    validateHttpAck: (result) => {
      assert.equal(result.status, 200)
      assert.equal(result.body.code, 0)
      calls.push(['http-ack'])
    },
    createMessageId: (eventType) => `msg-${eventType.toLowerCase()}-${stored.length + 1}`,
    now: () => 1_753_001_234_567,
    retryDelayMs: 10000,
    ...overrides
  })
  return {
    workflow,
    calls,
    stored
  }
}

test('builds a report only when a documented numeric hardware fault appears or changes', () => {
  const normal = { slotNumber: 7, faultMask: 0, updatedAt: 100 }
  const fault = {
    slotNumber: 7,
    faultMask: 4,
    faultCode: '0x04',
    faultMessage: '充电异常',
    updatedAt: 200
  }
  assert.deepEqual(buildHardwareFaultTransition(normal, fault), {
    slotId: 7,
    faultCode: 4,
    faultMsg: '充电异常',
    timestamp: 200
  })
  assert.equal(buildHardwareFaultTransition(fault, { ...fault, updatedAt: 300 }), null)
  assert.equal(buildHardwareFaultTransition(normal, {
    slotNumber: 7,
    faultMask: 0,
    faultCode: 'COMM_TIMEOUT',
    faultMessage: '通信超时',
    updatedAt: 300
  }), null)
})

test('uses the documented HTTP fallback for a hardware fault', async () => {
  const harness = createHarness({ isMqttConnected: async () => false })
  const result = await harness.workflow.reportHardwareFault({
    slotId: 3,
    faultCode: 8,
    faultMsg: '电机驱动故障',
    timestamp: 20
  })
  assert.equal(result.sent, true)
  assert.equal(result.transport, 'HTTP')
  assert.deepEqual(harness.calls.map((call) => call[0]), ['save', 'http', 'http-ack', 'sent'])
  assert.equal(harness.calls[1][1], DIAGNOSTIC_OUTBOX_TYPES.HARDWARE_FAULT)
})

test('persists and delivers a self-check report through its documented command', async () => {
  const harness = createHarness()
  const result = await harness.workflow.reportSelfCheck({
    result: 'fail',
    details: [
      { name: 'mqtt', status: 'fail', errorMsg: 'MQTT disconnected' }
    ],
    timestamp: 40
  })

  assert.equal(result.sent, true)
  assert.equal(harness.stored[0].eventType, DIAGNOSTIC_OUTBOX_TYPES.SELF_CHECK_REPORT)
  const mqttCall = harness.calls.find((call) => call[0] === 'mqtt')
  assert.equal(mqttCall[4].mqttCmd, 'selfCheckReport')
  assert.equal(mqttCall[4].responseCmd, 'selfCheckReportResp')
  assert.equal(mqttCall[2].result, 'fail')
})

test('keeps a failed business ACK in outbox and flushes it after reconnect', async () => {
  let fail = true
  const harness = createHarness({
    validateMqttAck: () => {
      if (fail) throw new Error('hardwareFaultResp code 500')
      harness.calls.push(['mqtt-ack'])
    }
  })
  const first = await harness.workflow.reportHardwareFault({
    slotId: 9,
    faultCode: 2,
    faultMsg: '传感器故障',
    timestamp: 30
  })
  assert.equal(first.queued, true)
  assert.deepEqual(harness.calls.map((call) => call[0]), ['save', 'mqtt', 'failed'])

  fail = false
  const flushed = await harness.workflow.flush('mqtt.connected')
  assert.equal(flushed.flushed, 1)
  assert.equal(flushed.failed, 0)
  assert.deepEqual(harness.calls.slice(-3).map((call) => call[0]), ['mqtt', 'mqtt-ack', 'sent'])
})

test('wires real slot events, both response commands and documented single-event HTTP paths', async () => {
  const [serviceSource, mainSource] = await Promise.all([
    readFile(new URL('../src/services/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/main.js', import.meta.url), 'utf8')
  ])
  assert.match(serviceSource, /'hardwareFaultResp'/)
  assert.match(serviceSource, /'selfCheckReportResp'/)
  assert.match(serviceSource, /httpPost\('\/api\/v1\/fault\/report'/)
  assert.match(serviceSource, /httpPost\('\/api\/v1\/device\/selfcheck', data\)/)
  assert.doesNotMatch(serviceSource, /\/api\/v1\/logs\/batch/)
  assert.doesNotMatch(serviceSource, /diagnosticDeliveryWorkflow\.reportLog|reportClientLog/)
  assert.match(mainSource, /services\.reportSlotHardwareFault\(previous, slot\)/)
  assert.match(mainSource, /services\.flushPendingDiagnosticEvents\(reason\)/)
})

test('integration layer treats backend duplicate-de-duplication responses as success for retries', async () => {
  const serviceSource = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  assert.match(serviceSource, /isBackendDuplicateResponse/)
  assert.match(serviceSource, /消息去重拦截/)
  assert.match(serviceSource, /if \(isBackendDuplicateResponse\(payload\)\) return/)
})
