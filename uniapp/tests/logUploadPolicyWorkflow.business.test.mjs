import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  createLogUploadPolicyWorkflow,
  LOG_UPLOAD_COMMAND_NAMES
} from '../src/services/logUploadPolicyWorkflow.js'

function createHarness() {
  let policy = { enabled: false }
  const processed = new Map()
  let saveCount = 0

  const workflow = createLogUploadPolicyWorkflow({
    findProcessed: async (msgId) => processed.get(msgId) || null,
    loadPolicy: async () => policy,
    savePolicy: async (next) => {
      saveCount += 1
      policy = { ...next }
      return policy
    },
    markProcessed: async (next) => {
      processed.set(next.msgId, { payload: { ...next }, state: 'SENT' })
    },
    now: () => 1_753_001_234_567
  })

  return {
    workflow,
    getPolicy: () => policy,
    getSaveCount: () => saveCount,
    processed
  }
}

test('exposes the two documented no-response log upload commands', () => {
  assert.deepEqual(LOG_UPLOAD_COMMAND_NAMES, ['enableLogUpload', 'disableLogUpload'])
})

test('persists enable and disable commands with the original msgId', async () => {
  const harness = createHarness()

  const enabled = await harness.workflow({
    cmd: 'enableLogUpload',
    msgId: 'msg-enable-1',
    data: { enabled: true, operatorId: 'operator-1' }
  })
  assert.equal(enabled.applied, true)
  assert.deepEqual(harness.getPolicy(), {
    enabled: true,
    operatorId: 'operator-1',
    command: 'enableLogUpload',
    msgId: 'msg-enable-1',
    updatedAt: 1_753_001_234_567
  })

  const disabled = await harness.workflow({
    cmd: 'disableLogUpload',
    msgId: 'msg-disable-1',
    data: { enabled: false, operatorId: 'operator-2' }
  })
  assert.equal(disabled.applied, true)
  assert.equal(harness.getPolicy().enabled, false)
  assert.equal(harness.processed.get('msg-disable-1').state, 'SENT')
})

test('reuses a processed msgId without applying an old command again', async () => {
  const harness = createHarness()
  const command = {
    cmd: 'enableLogUpload',
    msgId: 'msg-duplicate-1',
    data: { enabled: true }
  }

  await harness.workflow(command)
  const duplicate = await harness.workflow(command)

  assert.equal(duplicate.reused, true)
  assert.equal(duplicate.applied, false)
  assert.equal(harness.getSaveCount(), 1)
})

test('rejects missing msgId and contradictory enabled values without mutation', async () => {
  const harness = createHarness()

  assert.deepEqual(await harness.workflow({
    cmd: 'enableLogUpload',
    data: { enabled: true }
  }), {
    handled: true,
    applied: false,
    reason: 'MISSING_MSG_ID'
  })

  const invalid = await harness.workflow({
    cmd: 'disableLogUpload',
    msgId: 'msg-invalid-1',
    data: { enabled: true }
  })
  assert.equal(invalid.reason, 'INVALID_ENABLED_VALUE')
  assert.equal(harness.getPolicy().enabled, false)
  assert.equal(harness.getSaveCount(), 0)
})

test('serializes concurrent duplicate deliveries to one persistent change', async () => {
  const harness = createHarness()
  const command = {
    cmd: 'enableLogUpload',
    msgId: 'msg-concurrent-1',
    data: { enabled: true }
  }
  const [first, second] = await Promise.all([
    harness.workflow(command),
    harness.workflow(command)
  ])

  assert.deepEqual(second, first)
  assert.equal(harness.getSaveCount(), 1)
})

test('registers and handles both commands without publishing a response', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const mainSource = await readFile(new URL('../src/main.js', import.meta.url), 'utf8')
  assert.match(source, /\.\.\.LOG_UPLOAD_COMMAND_NAMES/)
  assert.match(source, /hasOwnProperty\.call\(LOG_UPLOAD_COMMANDS, cmd\)/)
  assert.match(source, /handleLogUploadPolicyCommand\(message\)/)
  assert.match(source, /typeof result\?\.enabled === 'boolean'/)
  assert.match(source, /nativeBridge\.waitForChannel\(5000\)/)
  assert.match(mainSource, /nativeBridge\.init\(\)[\s\S]{0,300}services\.restoreLogUploadPolicyOnStartup\(\)/)
  assert.doesNotMatch(source, /sendMqttResponseWithOutbox\(['"](?:enableLogUpload|disableLogUpload)/)
})
