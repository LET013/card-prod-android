import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { createSelfCheckCommandWorkflow } from '../src/services/selfCheckCommandWorkflow.js'

const createHarness = ({ queued = false, checkResult } = {}) => {
  const responses = new Map()
  const reports = []
  const records = []
  const sends = []
  let runs = 0
  const workflow = createSelfCheckCommandWorkflow({
    findResponse: async (msgId) => responses.get(msgId) || null,
    markProcessing: async (msgId) => {
      responses.set(msgId, {
        state: 'PROCESSING',
        payload: { data: null }
      })
    },
    runSelfCheck: async () => {
      runs += 1
      return checkResult || {
        result: 'pass',
        details: [{ name: 'sqlite', status: 'pass', errorMsg: '' }],
        timestamp: 1_753_001_234_567
      }
    },
    reportSelfCheck: async (report) => {
      reports.push(report)
      return queued
        ? { sent: false, queued: true }
        : { sent: true, queued: false }
    },
    recordResult: async (record) => { records.push(record) },
    sendResponse: async (data, msgId) => {
      sends.push({ data, msgId })
      responses.set(msgId, {
        state: data.result === 'pass' ? 'COMPLETED' : 'FAILED',
        payload: { data }
      })
      return { sent: !queued, queued, data, msgId }
    }
  })
  return {
    workflow,
    reports,
    records,
    sends,
    getRuns: () => runs
  }
}

test('runs, reports and responds to deviceSelfCheck with the original msgId', async () => {
  const harness = createHarness()
  const result = await harness.workflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-1' })

  assert.equal(result.responded, true)
  assert.equal(result.msgId, 'msg-check-1')
  assert.equal(result.data.code, 0)
  assert.equal(result.data.result, 'pass')
  assert.equal(harness.getRuns(), 1)
  assert.equal(harness.reports.length, 1)
  assert.equal(harness.records[0].state, 'COMPLETED')
})

test('returns a successful command response while preserving a measured self-check failure', async () => {
  const harness = createHarness({
    checkResult: {
      result: 'fail',
      details: [{ name: 'serial', status: 'fail', errorMsg: 'serial unavailable' }],
      timestamp: 100
    }
  })
  const result = await harness.workflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-2' })

  assert.equal(result.data.code, 0)
  assert.equal(result.data.result, 'fail')
  assert.equal(harness.records[0].state, 'FAILED')
  assert.equal(harness.sends[0].msgId, 'msg-check-2')
})

test('acknowledges a completed check while its report remains queued for retry', async () => {
  const harness = createHarness({ queued: true })
  const result = await harness.workflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-3' })

  assert.equal(result.data.code, 0)
  assert.match(result.data.msg, /待补传/)
  assert.equal(result.queued, true)
})

test('coalesces duplicate in-flight commands and later reuses the durable response', async () => {
  const harness = createHarness()
  const originalWorkflow = harness.workflow
  const first = originalWorkflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-4' })
  const second = originalWorkflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-4' })
  const [firstResult, secondResult] = await Promise.all([first, second])

  assert.equal(harness.getRuns(), 1)
  assert.deepEqual(secondResult, firstResult)
  const reused = await originalWorkflow({ cmd: 'deviceSelfCheck', msgId: 'msg-check-4' })
  assert.equal(reused.reused, true)
  assert.equal(harness.getRuns(), 1)
})

test('wires the supported command, response and report path without the unsupported blocker', async () => {
  const [serviceSource, blockerSource] = await Promise.all([
    readFile(new URL('../src/services/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/services/unsupportedDeviceCommandWorkflow.js', import.meta.url), 'utf8')
  ])

  assert.match(serviceSource, /'deviceSelfCheck'/)
  assert.match(serviceSource, /'selfCheckReportResp'/)
  assert.match(serviceSource, /handleSelfCheckCommand\(message\)/)
  assert.match(serviceSource, /\/api\/v1\/device\/selfcheck/)
  assert.doesNotMatch(blockerSource, /deviceSelfCheck/)
})
