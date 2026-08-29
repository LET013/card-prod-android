import assert from 'node:assert/strict'
import test from 'node:test'

import { createSyncUserWorkflow } from '../src/services/syncUserWorkflow.js'

const createWorkflow = (overrides = {}) => createSyncUserWorkflow({
  findResponse: async () => null,
  markProcessing: async () => {},
  syncUser: async () => {},
  sendResponse: async (data, msgId) => ({ sent: true, data, msgId }),
  ...overrides
})

test('persists processing before syncUser and responds with the original msgId', async () => {
  const calls = []
  const workflow = createWorkflow({
    findResponse: async (msgId) => { calls.push(['find', msgId]); return null },
    markProcessing: async (msgId) => { calls.push(['processing', msgId]) },
    syncUser: async (options) => { calls.push(['sync', options.source]) },
    sendResponse: async (data, msgId) => {
      calls.push(['send', data, msgId])
      return { sent: true, msgId }
    }
  })

  const result = await workflow({ cmd: 'syncUser', msgId: 'msg-user-1', data: {} })

  assert.deepEqual(result.data, { code: 0, msg: 'success' })
  assert.equal(result.msgId, 'msg-user-1')
  assert.deepEqual(calls, [
    ['find', 'msg-user-1'],
    ['processing', 'msg-user-1'],
    ['sync', 'MQTT_SYNC_USER'],
    ['send', { code: 0, msg: 'success' }, 'msg-user-1']
  ])
})

test('reuses a durable terminal response without synchronizing again', async () => {
  const cached = { code: 500, msg: 'cached failure' }
  let syncCount = 0
  const workflow = createWorkflow({
    findResponse: async () => ({ state: 'FAILED', payload: { data: cached } }),
    markProcessing: async () => { throw new Error('must not mark a terminal response') },
    syncUser: async () => { syncCount += 1 },
    sendResponse: async (data, msgId) => ({ sent: false, queued: true, data, msgId })
  })

  const result = await workflow({ msgId: 'msg-user-terminal' })

  assert.equal(syncCount, 0)
  assert.equal(result.reused, true)
  assert.equal(result.queued, true)
  assert.deepEqual(result.data, cached)
})

test('converts a synchronization failure into the documented code 500 response', async () => {
  let sent
  const workflow = createWorkflow({
    syncUser: async () => { throw new Error('employee sync failed') },
    sendResponse: async (data, msgId) => {
      sent = { data, msgId }
      return { sent: true, msgId }
    }
  })

  const result = await workflow({ msgId: 'msg-user-error' })

  assert.deepEqual(sent, {
    data: { code: 500, msg: 'employee sync failed' },
    msgId: 'msg-user-error'
  })
  assert.deepEqual(result.data, sent.data)
})

test('coalesces duplicate in-flight syncUser commands', async () => {
  let releaseSync
  let syncCount = 0
  const syncGate = new Promise((resolve) => { releaseSync = resolve })
  const workflow = createWorkflow({
    syncUser: async () => {
      syncCount += 1
      await syncGate
    }
  })

  const first = workflow({ msgId: 'msg-user-concurrent' })
  const second = workflow({ msgId: 'msg-user-concurrent' })
  await Promise.resolve()
  releaseSync()
  const [firstResult, secondResult] = await Promise.all([first, second])

  assert.equal(syncCount, 1)
  assert.deepEqual(secondResult, firstResult)
})

test('resumes a persisted PROCESSING response after process restart', async () => {
  let syncCount = 0
  const workflow = createWorkflow({
    findResponse: async () => ({ state: 'PROCESSING', payload: { data: null } }),
    syncUser: async () => { syncCount += 1 }
  })

  const result = await workflow({ msgId: 'msg-user-recovery' })

  assert.equal(syncCount, 1)
  assert.equal(result.responded, true)
})

test('ignores syncUser without msgId before any side effect', async () => {
  let touched = false
  const workflow = createWorkflow({
    findResponse: async () => { touched = true },
    markProcessing: async () => { touched = true },
    syncUser: async () => { touched = true },
    sendResponse: async () => { touched = true }
  })

  assert.deepEqual(await workflow({ cmd: 'syncUser' }), {
    responded: false,
    reason: 'MISSING_MSG_ID'
  })
  assert.equal(touched, false)
})
