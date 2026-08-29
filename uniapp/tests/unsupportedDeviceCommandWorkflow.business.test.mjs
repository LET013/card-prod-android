import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createUnsupportedDeviceCommandWorkflow,
  UNSUPPORTED_DEVICE_COMMAND_NAMES,
  UNSUPPORTED_DEVICE_COMMANDS
} from '../src/services/unsupportedDeviceCommandWorkflow.js'

const createWorkflow = (overrides = {}) => createUnsupportedDeviceCommandWorkflow({
  findResponse: async () => null,
  markProcessing: async () => {},
  recordFailure: async () => {},
  sendResponse: async (responseCmd, data, msgId) => ({ sent: true, responseCmd, data, msgId }),
  ...overrides
})

test('maps every unavailable V4.2 command to its documented response command', () => {
  assert.deepEqual(UNSUPPORTED_DEVICE_COMMAND_NAMES, [])
  assert.deepEqual(
    Object.fromEntries(Object.entries(UNSUPPORTED_DEVICE_COMMANDS).map(([cmd, value]) => [cmd, value.responseCmd])),
    {}
  )
})

test('ignores missing msgId and commands outside the explicit map', async () => {
  const workflow = createWorkflow()
  assert.deepEqual(await workflow({ cmd: 'remoteOpen' }), {
    responded: false,
    reason: 'UNHANDLED_COMMAND'
  })
  assert.deepEqual(await workflow({ cmd: 'enableLogUpload', msgId: 'msg-log-1' }), {
    responded: false,
    reason: 'UNHANDLED_COMMAND'
  })
})
