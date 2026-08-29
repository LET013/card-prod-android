import assert from 'node:assert/strict'
import test from 'node:test'

import { createRemoteOpenCommandWorkflow } from '../src/services/remoteOpenCommandWorkflow.js'

const createWorkflow = (overrides = {}) => createRemoteOpenCommandWorkflow({
  findResponse: async () => null,
  markProcessing: async () => {},
  executeOpen: async () => ({ accepted: true, confirmed: true }),
  recordFailure: async () => {},
  sendResponse: async (data, msgId) => ({ sent: true, data, msgId }),
  ...overrides
})

test('replies success after the requested door command is accepted', async () => {
  const calls = []
  const workflow = createWorkflow({
    executeOpen: async (request) => {
      calls.push(request)
      return { accepted: true, confirmed: true }
    }
  })
  const result = await workflow({
    cmd: 'remoteOpen',
    msgId: 'msg-open-1',
    data: { slotId: 2, authType: 'ADMIN', operatorId: 'admin' }
  })

  assert.deepEqual(result.data, { code: 0, msg: 'success' })
  assert.equal(result.msgId, 'msg-open-1')
  assert.deepEqual(calls, [{
    operationId: 'remoteOpen:msg-open-1',
    operatorId: 'admin',
    msgId: 'msg-open-1',
    slotId: 2,
    authType: 'ADMIN'
  }])
})

test('rejects invalid fields and unaccepted device execution', async () => {
  let executed = 0
  const invalid = createWorkflow({
    executeOpen: async () => { executed += 1 }
  })
  const invalidResult = await invalid({
    cmd: 'remoteOpen',
    msgId: 'msg-open-invalid',
    data: { slotId: 0, authType: 'FACE', operatorId: '' }
  })
  assert.equal(executed, 0)
  assert.equal(invalidResult.data.code, 500)

  const unconfirmed = createWorkflow({
    executeOpen: async () => ({
      accepted: true,
      confirmed: false,
      message: '串口未接受开卡指令'
    })
  })
  const unconfirmedResult = await unconfirmed({
    cmd: 'remoteOpen',
    msgId: 'msg-open-unconfirmed',
    data: { slotId: 2, authType: 'ADMIN', operatorId: 'admin' }
  })
  assert.equal(unconfirmedResult.data.code, 500)
  assert.equal(unconfirmedResult.data.msg, '串口未接受开卡指令')
})

test('reuses terminal responses and does not repeat an uncertain side effect', async () => {
  const cached = { code: 0, msg: 'success' }
  const cachedWorkflow = createWorkflow({
    findResponse: async () => ({ state: 'SENT', payload: { data: cached } }),
    executeOpen: async () => { throw new Error('must not execute cached command') }
  })
  const cachedResult = await cachedWorkflow({ cmd: 'remoteOpen', msgId: 'msg-cached' })
  assert.equal(cachedResult.reused, true)
  assert.deepEqual(cachedResult.data, cached)

  let executed = 0
  const recoveringWorkflow = createWorkflow({
    findResponse: async () => ({ state: 'PROCESSING', payload: { data: null } }),
    executeOpen: async () => { executed += 1 }
  })
  const recovered = await recoveringWorkflow({ cmd: 'remoteOpen', msgId: 'msg-recover' })
  assert.equal(executed, 0)
  assert.equal(recovered.recovered, true)
  assert.equal(recovered.data.code, 500)
})
