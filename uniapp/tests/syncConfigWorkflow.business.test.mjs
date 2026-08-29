import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createSyncConfigWorkflow,
  DEVELOPER_PASSWORD_FIELD,
  DEVICE_CONFIG_FIELDS,
  extractSuperAdminPasswordConfig,
  extractSystemCredentialPasswordConfig,
  findChangedNativeRuntimeConfigFields,
  NATIVE_RUNTIME_CONFIG_FIELDS,
  SUPER_ADMIN_PASSWORD_FIELDS,
  validateCompleteDeviceConfig
} from '../src/services/syncConfigWorkflow.js'

const completeConfig = Object.freeze({
  cameraFacing: 'front',
  cameraMirror: true,
  cameraRotation: 0,
  cameraFrameWidth: 640,
  cameraFrameHeight: 480,
  faceThreshold: 0.8,
  fingerThreshold: 0.8,
  faceRecognitionTimeout: 30000,
  searchTimeout: 15000,
  searchIntervalTime: 3000,
  needFaceLiveness: false,
  captureTimeout: 8000,
  fingerEnabled: '0',
  serialPollEnabled: true,
  serialPollInterval: 5000,
  serialResponseTimeout: 700,
  pollingMode: 'GROUP',
  slotStatusPushInterval: 60000,
  mqttStatusReportInterval: 300000,
  mqttHeartbeatInterval: 60000,
  slotSortDirection: 'HORIZONTAL',
  serialPort: '/dev/ttyS5',
  baudRate: 57600,
  groupSize: 16,
  totalSlots: 100,
  communicationMode: 'MQTT',
  httpHost: '127.0.0.1',
  httpPort: 8082,
  mqttHost: '127.0.0.1',
  mqttPort: 1883,
  mqttReconnectInitialInterval: 1000,
  mqttReconnectMaxInterval: 60000
})

test('config contract contains and validates all documented 32 fields', () => {
  assert.equal(DEVICE_CONFIG_FIELDS.length, 32)
  assert.deepEqual(validateCompleteDeviceConfig({ ...completeConfig }), completeConfig)
})

test('config contract rejects missing and invalid server fields', () => {
  const missing = { ...completeConfig }
  delete missing.mqttReconnectMaxInterval
  assert.throws(() => validateCompleteDeviceConfig(missing), /mqttReconnectMaxInterval/)
  const missingStatusReportInterval = { ...completeConfig }
  delete missingStatusReportInterval.mqttStatusReportInterval
  assert.throws(() => validateCompleteDeviceConfig(missingStatusReportInterval), /mqttStatusReportInterval/)
  assert.throws(
    () => validateCompleteDeviceConfig({ ...completeConfig, communicationMode: 'TCP' }),
    /communicationMode/
  )
  assert.throws(
    () => validateCompleteDeviceConfig({ ...completeConfig, mqttStatusReportInterval: 300000.5 }),
    /mqttStatusReportInterval/
  )
  assert.throws(
    () => validateCompleteDeviceConfig({ ...completeConfig, mqttPort: '1883' }),
    /mqttPort/
  )
})

test('extracts the backend-managed super-admin password without treating developer password as a fallback', () => {
  assert.equal(SUPER_ADMIN_PASSWORD_FIELDS[0], 'superAdminPassword')
  assert.deepEqual(
    extractSuperAdminPasswordConfig({ superAdminPassword: '234567', initialPassword: '345678' }),
    { found: true, field: 'superAdminPassword', password: '234567' }
  )
  assert.deepEqual(
    extractSuperAdminPasswordConfig({ body: { data: { initialAdminPassword: '345678' } } }),
    { found: true, field: 'initialAdminPassword', password: '345678' }
  )
  assert.deepEqual(
    extractSuperAdminPasswordConfig({ developerPassword: '654321' }),
    { found: false, field: '', password: undefined }
  )
})

test('extracts both backend-managed system credential passwords', () => {
  assert.equal(DEVELOPER_PASSWORD_FIELD, 'developerPassword')
  assert.deepEqual(
    extractSystemCredentialPasswordConfig({
      developerPassword: '654321',
      superAdminPassword: '234567'
    }),
    {
      developer: { found: true, field: 'developerPassword', password: '654321' },
      superAdmin: { found: true, field: 'superAdminPassword', password: '234567' }
    }
  )
  assert.deepEqual(
    extractSystemCredentialPasswordConfig({ body: { data: { developerPassword: '654321' } } }),
    {
      developer: { found: true, field: 'developerPassword', password: '654321' },
      superAdmin: { found: false, field: '', password: undefined }
    }
  )
})

test('native runtime ownership guard detects only Android-managed changes', () => {
  assert.equal(NATIVE_RUNTIME_CONFIG_FIELDS.length, 16)
  assert.deepEqual(
    findChangedNativeRuntimeConfigFields(
      completeConfig,
      { ...completeConfig, faceThreshold: 0.9, slotSortDirection: 'VERTICAL' }
    ),
    []
  )
  assert.deepEqual(
    findChangedNativeRuntimeConfigFields(
      completeConfig,
      { ...completeConfig, baudRate: 115200, mqttHeartbeatInterval: 30000 }
    ),
    ['baudRate', 'mqttHeartbeatInterval']
  )
})

test('syncConfig persists before replying with the original msgId', async () => {
  const calls = []
  const workflow = createSyncConfigWorkflow({
    findResponse: async (msgId) => {
      calls.push(['find', msgId])
      return null
    },
    markProcessing: async (msgId) => { calls.push(['processing', msgId]) },
    syncConfig: async () => { calls.push(['sync']) },
    sendResponse: async (data, msgId) => {
      calls.push(['send', data, msgId])
      return { sent: true, msgId }
    }
  })

  const result = await workflow({ cmd: 'syncConfig', msgId: 'msg-config-1', data: {} })
  assert.deepEqual(result.data, { code: 0, msg: 'success' })
  assert.equal(result.msgId, 'msg-config-1')
  assert.deepEqual(calls, [
    ['find', 'msg-config-1'],
    ['processing', 'msg-config-1'],
    ['sync'],
    ['send', { code: 0, msg: 'success' }, 'msg-config-1']
  ])
})

test('syncConfig reports failure and reuses a durable response without repeating work', async () => {
  let syncCount = 0
  const cachedFailure = { code: 500, msg: '设备配置响应缺少字段: mqttHost' }
  const workflow = createSyncConfigWorkflow({
    findResponse: async () => ({ payload: { data: cachedFailure }, state: 'FAILED' }),
    markProcessing: async () => { throw new Error('must not mark cached response') },
    syncConfig: async () => { syncCount += 1 },
    sendResponse: async (data, msgId) => ({ sent: false, queued: true, data, msgId })
  })

  const result = await workflow({ msgId: 'msg-config-2' })
  assert.equal(syncCount, 0)
  assert.equal(result.reused, true)
  assert.equal(result.queued, true)
  assert.deepEqual(result.data, cachedFailure)
})

test('syncConfig converts a synchronization error into the documented failure response', async () => {
  let sent
  const workflow = createSyncConfigWorkflow({
    findResponse: async () => null,
    markProcessing: async () => {},
    syncConfig: async () => { throw new Error('设备配置响应缺少字段: mqttHost') },
    sendResponse: async (data, msgId) => {
      sent = { data, msgId }
      return { sent: true, msgId }
    }
  })

  const result = await workflow({ msgId: 'msg-config-error' })
  assert.deepEqual(sent, {
    data: { code: 500, msg: '设备配置响应缺少字段: mqttHost' },
    msgId: 'msg-config-error'
  })
  assert.deepEqual(result.data, sent.data)
})

test('duplicate in-flight syncConfig messages execute the synchronization once', async () => {
  let releaseSync
  let syncCount = 0
  const syncGate = new Promise((resolve) => { releaseSync = resolve })
  const workflow = createSyncConfigWorkflow({
    findResponse: async () => null,
    markProcessing: async () => {},
    syncConfig: async () => {
      syncCount += 1
      await syncGate
    },
    sendResponse: async (data, msgId) => ({ sent: true, data, msgId })
  })

  const first = workflow({ msgId: 'msg-config-concurrent' })
  const second = workflow({ msgId: 'msg-config-concurrent' })
  await Promise.resolve()
  releaseSync()
  const [firstResult, secondResult] = await Promise.all([first, second])

  assert.equal(syncCount, 1)
  assert.deepEqual(secondResult, firstResult)
})

test('syncConfig resumes a persisted PROCESSING record after process restart', async () => {
  let synced = 0
  const workflow = createSyncConfigWorkflow({
    findResponse: async () => ({ payload: { data: null }, state: 'PROCESSING' }),
    markProcessing: async () => {},
    syncConfig: async () => { synced += 1 },
    sendResponse: async (data, msgId) => ({ sent: true, data, msgId })
  })

  const result = await workflow({ msgId: 'msg-config-recovery' })
  assert.equal(synced, 1)
  assert.equal(result.responded, true)
})

test('syncConfig ignores a command without msgId', async () => {
  const workflow = createSyncConfigWorkflow({
    findResponse: async () => null,
    markProcessing: async () => {},
    syncConfig: async () => {},
    sendResponse: async () => ({ sent: true })
  })
  assert.deepEqual(await workflow({ cmd: 'syncConfig' }), {
    responded: false,
    reason: 'MISSING_MSG_ID'
  })
})
