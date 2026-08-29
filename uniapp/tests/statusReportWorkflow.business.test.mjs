import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  createStatusReportWorkflow,
  STATUS_REPORT_EVENT_ID,
  STATUS_REPORT_OUTBOX_TYPE
} from '../src/services/statusReportWorkflow.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')
const payload = Object.freeze({ slots: [{ slotId: 1, status: 'EMPTY', faultCode: 0 }] })

const createHarness = (overrides = {}) => {
  const calls = []
  let stored = null
  const workflow = createStatusReportWorkflow({
    saveEvent: async (event) => {
      calls.push(['save', event])
      stored = { ...event }
      return stored
    },
    listDueEvents: async () => stored ? [stored] : [],
    markSent: async (eventId) => { calls.push(['sent', eventId]) },
    markFailed: async (eventId, error, delay) => { calls.push(['failed', eventId, error.message, delay]) },
    getCommunicationMode: () => 'MQTT',
    isMqttConnected: async () => true,
    sendMqttAndWaitForAck: async (data, msgId) => {
      calls.push(['mqtt', data, msgId])
      return { response: { data: { code: 0, msg: 'success' } } }
    },
    sendHttp: async (data) => {
      calls.push(['http', data])
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
    createMessageId: () => 'status-msg-1',
    retryDelayMs: 10000,
    ...overrides
  })
  return { workflow, calls, getStored: () => stored, setStored: (event) => { stored = event } }
}

test('persists statusReport before MQTT send and marks it sent only after business ACK', async () => {
  const { workflow, calls, getStored } = createHarness()
  const result = await workflow.report(payload)

  assert.equal(result.sent, true)
  assert.equal(result.transport, 'MQTT')
  assert.equal(getStored().eventId, STATUS_REPORT_EVENT_ID)
  assert.equal(getStored().eventType, STATUS_REPORT_OUTBOX_TYPE)
  assert.equal(getStored().attemptCount, 0)
  assert.equal(getStored().ackedAt, 0)
  assert.deepEqual(calls.map((call) => call[0]), ['save', 'mqtt', 'mqtt-ack', 'sent'])
})

test('uses the documented HTTP alternative when MQTT is unavailable', async () => {
  const { workflow, calls } = createHarness({ isMqttConnected: async () => false })
  const result = await workflow.report(payload)

  assert.equal(result.sent, true)
  assert.equal(result.transport, 'HTTP')
  assert.deepEqual(calls.map((call) => call[0]), ['save', 'http', 'http-ack', 'sent'])
})

test('keeps a failed business ACK in outbox for retry', async () => {
  const { workflow, calls } = createHarness({
    validateMqttAck: () => { throw new Error('statusReportResp code 500') }
  })
  const result = await workflow.report(payload)

  assert.equal(result.sent, false)
  assert.equal(result.queued, true)
  assert.match(result.error, /500/)
  assert.deepEqual(calls.map((call) => call[0]), ['save', 'mqtt', 'failed'])
  assert.equal(calls.at(-1)[3], 10000)
})

test('flushes the durable status report after reconnect and handles an empty outbox', async () => {
  const { workflow, calls, setStored } = createHarness()
  setStored({
    eventId: STATUS_REPORT_EVENT_ID,
    eventType: STATUS_REPORT_OUTBOX_TYPE,
    payload: { data: payload, msgId: 'status-retry-1' }
  })

  const flushed = await workflow.flush('mqtt.connected')
  assert.equal(flushed.flushed, 1)
  assert.equal(flushed.failed, 0)
  assert.deepEqual(calls.map((call) => call[0]), ['mqtt', 'mqtt-ack', 'sent'])

  const empty = createHarness({ listDueEvents: async () => [] })
  assert.deepEqual(await empty.workflow.flush('manual'), {
    flushed: 0,
    failed: 0,
    reason: 'manual',
    empty: true
  })
})

test('releases the in-flight guard so a failed durable report can be retried again', async () => {
  const { workflow, calls, setStored } = createHarness({
    sendMqttAndWaitForAck: async () => {
      calls.push(['mqtt'])
      throw new Error('ack timeout')
    }
  })
  setStored({
    eventId: STATUS_REPORT_EVENT_ID,
    eventType: STATUS_REPORT_OUTBOX_TYPE,
    payload: { data: payload, msgId: 'status-retry-loop' }
  })

  const first = await workflow.flush('retry-1')
  const second = await workflow.flush('retry-2')

  assert.equal(first.failed, 1)
  assert.equal(second.failed, 1)
  assert.equal(calls.filter((call) => call[0] === 'mqtt').length, 2)
  assert.equal(calls.filter((call) => call[0] === 'failed').length, 2)
})

test('serializes a newer status snapshot behind an in-flight delivery instead of dropping it', async () => {
  const sentPayloads = []
  const messageIds = ['status-before-take', 'status-after-take']
  let releaseFirst
  const firstDeliveryBlocked = new Promise((resolve) => { releaseFirst = resolve })
  const { workflow } = createHarness({
    createMessageId: () => messageIds.shift(),
    sendMqttAndWaitForAck: async (data, msgId) => {
      sentPayloads.push({ data, msgId })
      if (msgId === 'status-before-take') await firstDeliveryBlocked
      return { response: { data: { code: 0, msg: 'success' } } }
    }
  })
  const beforeTake = { slots: [{ slotId: 1, status: 'OCCUPIED', cardNo: 'CARD001' }] }
  const afterTake = { slots: [{ slotId: 1, status: 'EMPTY' }] }

  const first = workflow.report(beforeTake)
  await new Promise((resolve) => setImmediate(resolve))
  const second = workflow.report(afterTake)
  await new Promise((resolve) => setImmediate(resolve))

  assert.deepEqual(sentPayloads, [{ data: beforeTake, msgId: 'status-before-take' }])
  releaseFirst()
  await Promise.all([first, second])
  assert.deepEqual(sentPayloads, [
    { data: beforeTake, msgId: 'status-before-take' },
    { data: afterTake, msgId: 'status-after-take' }
  ])
})

test('registers statusReportResp and reconnect-triggered outbox recovery in the integration layer', async () => {
  const [serviceSource, mainSource] = await Promise.all([
    readSource('src/services/index.js'),
    readSource('src/main.js')
  ])

  assert.match(serviceSource, /'statusReportResp',[\s\S]*'cardEventResp'/)
  assert.match(serviceSource, /await registerMqttBusinessHandlers\(\{ reason: 'status-report' \}\)/)
  assert.match(serviceSource, /sendMqttAndWaitForResponse\('statusReport', data, 'statusReportResp', msgId\)/)
  assert.match(serviceSource, /async function reportDeviceStatusImmediately[\s\S]*clearTimeout\(statusReportTimer\)/)
  assert.match(serviceSource, /const configuredSeconds = Number\(appState\.settings\.mqttStatusReportInterval \?\? 300\)[\s\S]*const interval = intervalSeconds \* 1000/)
  assert.doesNotMatch(serviceSource, /mqttStatusReportInterval \|\| appState\.settings\.slotStatusPushInterval/)
  assert.match(serviceSource, /if \(statusReportFlushTimer\) \{/)
  assert.doesNotMatch(serviceSource, /if \(statusReportFlushTimer \|\| statusReportFlushPromise\)/)
  assert.match(serviceSource, /ILLEGAL_CARD: 'FAULT'/)
  assert.match(mainSource, /services\.flushPendingStatusReports\(reason\)/)
})

test('integration layer treats backend duplicate-de-duplication responses as success for retries', async () => {
  const serviceSource = await readSource('src/services/index.js')
  assert.match(serviceSource, /isBackendDuplicateResponse/)
  assert.match(serviceSource, /消息去重拦截/)
  assert.match(serviceSource, /if \(isBackendDuplicateResponse\(payload\)\) return/)
})
