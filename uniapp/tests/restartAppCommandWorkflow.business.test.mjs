import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createRestartAppCommandWorkflow,
  normalizeRestartDelay
} from '../src/services/restartAppCommandWorkflow.js'

const createWorkflow = (overrides = {}) => createRestartAppCommandWorkflow({
  findResponse: async () => null,
  markProcessing: async () => {},
  scheduleRestart: async ({ operationId, delayMs }) => ({ operationId, status: 'SCHEDULED', effectiveDelayMs: delayMs }),
  recordOperation: async () => {},
  sendResponse: async (data, msgId) => ({ sent: true, data, msgId }),
  ...overrides
})

test('normalizes the documented delay without inventing a channel-side value', () => {
  assert.equal(normalizeRestartDelay(undefined), 3000)
  assert.equal(normalizeRestartDelay(0), 0)
  assert.equal(normalizeRestartDelay('4500'), 4500)
  assert.throws(() => normalizeRestartDelay(-1), /非负整数/)
  assert.throws(() => normalizeRestartDelay(1.5), /非负整数/)
})

test('persists receipt, schedules the native restart and responds with the original msgId', async () => {
  const calls = []
  const workflow = createWorkflow({
    markProcessing: async (msgId) => { calls.push(['processing', msgId]) },
    recordOperation: async (operation) => { calls.push(['record', operation.state, operation.operationId]) },
    scheduleRestart: async (payload) => {
      calls.push(['schedule', payload.operationId, payload.delayMs])
      return { status: 'SCHEDULED', effectiveDelayMs: 3000 }
    },
    sendResponse: async (data, msgId) => {
      calls.push(['send', data.code, msgId])
      return { sent: true, msgId }
    }
  })

  const result = await workflow({ cmd: 'restartApp', msgId: 'restart-1', data: { delayMs: 3000 } })
  assert.equal(result.responded, true)
  assert.equal(result.responseCmd, 'restartAppResp')
  assert.equal(result.msgId, 'restart-1')
  assert.equal(result.data.code, 0)
  assert.deepEqual(calls.map((call) => call[0]), ['processing', 'record', 'schedule', 'record', 'send'])
  assert.equal(calls[1][2], 'restartApp:remote:restart-1')
})

test('returns code 500 and records failure when native scheduling fails', async () => {
  const states = []
  const workflow = createWorkflow({
    scheduleRestart: async () => { throw new Error('alarm unavailable') },
    recordOperation: async (operation) => { states.push(operation.state) }
  })

  const result = await workflow({ cmd: 'restartApp', msgId: 'restart-failed' })
  assert.equal(result.data.code, 500)
  assert.match(result.data.msg, /alarm unavailable/)
  assert.deepEqual(states, ['RECEIVED', 'FAILED'])
})

test('returns code 500 for an invalid delay instead of dropping the backend response', async () => {
  let scheduled = false
  const workflow = createWorkflow({
    scheduleRestart: async () => { scheduled = true }
  })

  const result = await workflow({ cmd: 'restartApp', msgId: 'restart-invalid', data: { delayMs: -1 } })
  assert.equal(result.data.code, 500)
  assert.match(result.data.msg, /非负整数/)
  assert.equal(scheduled, false)
})

test('coalesces the same in-flight command and reuses a durable response', async () => {
  let scheduleCount = 0
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const workflow = createWorkflow({
    scheduleRestart: async () => {
      scheduleCount += 1
      await gate
      return { status: 'SCHEDULED' }
    }
  })
  const first = workflow({ cmd: 'restartApp', msgId: 'same-id' })
  const second = workflow({ cmd: 'restartApp', msgId: 'same-id' })
  release()
  assert.deepEqual(await second, await first)
  assert.equal(scheduleCount, 1)

  const cached = { code: 0, msg: 'cached' }
  const reused = await createWorkflow({
    findResponse: async () => ({ state: 'SENT', payload: { data: cached } }),
    scheduleRestart: async () => { throw new Error('must not schedule again') }
  })({ cmd: 'restartApp', msgId: 'cached-id' })
  assert.equal(reused.reused, true)
  assert.deepEqual(reused.data, cached)
})

test('ignores another command and a restart command without msgId', async () => {
  const workflow = createWorkflow()
  assert.deepEqual(await workflow({ cmd: 'syncConfig', msgId: 'x' }), {
    responded: false,
    reason: 'UNHANDLED_COMMAND'
  })
  assert.deepEqual(await workflow({ cmd: 'restartApp' }), {
    responded: false,
    reason: 'MISSING_MSG_ID'
  })
})
