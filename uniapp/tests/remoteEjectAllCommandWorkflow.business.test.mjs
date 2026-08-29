import assert from 'node:assert/strict'
import test from 'node:test'

import { createRemoteEjectAllCommandWorkflow } from '../src/services/remoteEjectAllCommandWorkflow.js'

const createWorkflow = (overrides = {}) => createRemoteEjectAllCommandWorkflow({
  findResponse: async () => null,
  markProcessing: async () => {},
  executeEjectAll: async () => ({ accepted: true, failedCount: 0 }),
  recordFailure: async () => {},
  sendResponse: async (data, msgId) => ({ sent: true, data, msgId }),
  ...overrides
})

test('executes the shared eject-all operation and replies with the original msgId', async () => {
  const calls = []
  const workflow = createWorkflow({
    markProcessing: async (msgId) => { calls.push(['processing', msgId]) },
    executeEjectAll: async (request) => {
      calls.push(['execute', request])
      return { accepted: true, failedCount: 0 }
    },
    sendResponse: async (data, msgId) => {
      calls.push(['send', data, msgId])
      return { sent: true, msgId }
    }
  })

  const result = await workflow({
    cmd: 'remoteEjectAll',
    msgId: 'msg-eject-1',
    data: { operatorId: 'operator-1', confirm: true }
  })

  assert.equal(result.responded, true)
  assert.equal(result.msgId, 'msg-eject-1')
  assert.deepEqual(result.data, { code: 0, msg: 'success' })
  assert.deepEqual(calls.map((call) => call[0]), ['processing', 'execute', 'send'])
  assert.deepEqual(calls[1][1], {
    operationId: 'remoteEjectAll:msg-eject-1',
    operatorId: 'operator-1',
    msgId: 'msg-eject-1'
  })
})

test('replies immediately when the remote eject-all operation is accepted for background serial execution', async () => {
  const workflow = createWorkflow({
    executeEjectAll: async () => ({
      accepted: true,
      queued: true,
      message: '已受理，正在逐槽开门'
    })
  })

  const result = await workflow({
    cmd: 'remoteEjectAll',
    msgId: 'msg-eject-queued',
    data: { operatorId: 'operator-queued', confirm: true }
  })

  assert.deepEqual(result.data, { code: 0, msg: '已受理，正在逐槽开门' })
})

test('rejects incomplete command data without running the device operation', async () => {
  let executed = 0
  const failures = []
  const workflow = createWorkflow({
    executeEjectAll: async () => { executed += 1 },
    recordFailure: async (failure) => { failures.push(failure) }
  })

  const result = await workflow({
    cmd: 'remoteEjectAll',
    msgId: 'msg-eject-invalid',
    data: { operatorId: '', confirm: false }
  })

  assert.equal(executed, 0)
  assert.equal(result.data.code, 500)
  assert.match(result.data.msg, /confirm/)
  assert.equal(failures.length, 1)
})

test('does not repeat an uncertain side effect after a process restart', async () => {
  let executed = 0
  const workflow = createWorkflow({
    findResponse: async () => ({ state: 'PROCESSING', payload: { data: null } }),
    executeEjectAll: async () => { executed += 1 }
  })

  const result = await workflow({
    cmd: 'remoteEjectAll',
    msgId: 'msg-eject-recover',
    data: { operatorId: 'operator-2', confirm: true }
  })

  assert.equal(executed, 0)
  assert.equal(result.recovered, true)
  assert.equal(result.data.code, 500)
  assert.match(result.data.msg, /未重复执行/)
})

test('reuses a durable terminal response and coalesces an in-flight duplicate', async () => {
  const cached = { code: 0, msg: 'success' }
  const cachedWorkflow = createWorkflow({
    findResponse: async () => ({ state: 'SENT', payload: { data: cached } }),
    executeEjectAll: async () => { throw new Error('must not execute cached command') }
  })
  const cachedResult = await cachedWorkflow({ cmd: 'remoteEjectAll', msgId: 'msg-cached' })
  assert.equal(cachedResult.reused, true)
  assert.deepEqual(cachedResult.data, cached)

  let release
  let executed = 0
  const gate = new Promise((resolve) => { release = resolve })
  const inFlightWorkflow = createWorkflow({
    executeEjectAll: async () => {
      executed += 1
      await gate
      return { accepted: true, failedCount: 0 }
    }
  })
  const message = {
    cmd: 'remoteEjectAll',
    msgId: 'msg-in-flight',
    data: { operatorId: 'operator-3', confirm: true }
  }
  const first = inFlightWorkflow(message)
  const second = inFlightWorkflow(message)
  await Promise.resolve()
  release()
  const [firstResult, secondResult] = await Promise.all([first, second])
  assert.equal(executed, 1)
  assert.deepEqual(secondResult, firstResult)
})

test('maps topology protection or partial dispatch to code 500', async () => {
  const workflow = createWorkflow({
    executeEjectAll: async () => ({
      accepted: false,
      failedCount: 2,
      message: '已下发 98 个，失败 2 个'
    })
  })
  const result = await workflow({
    cmd: 'remoteEjectAll',
    msgId: 'msg-eject-partial',
    data: { operatorId: 'operator-4', confirm: true }
  })
  assert.equal(result.data.code, 500)
  assert.equal(result.data.msg, '已下发 98 个，失败 2 个')
})
