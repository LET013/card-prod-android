export const DEVICE_CONFIG_FIELDS = Object.freeze([
  'cameraFacing',
  'cameraMirror',
  'cameraRotation',
  'cameraFrameWidth',
  'cameraFrameHeight',
  'faceThreshold',
  'fingerThreshold',
  'faceRecognitionTimeout',
  'searchTimeout',
  'searchIntervalTime',
  'needFaceLiveness',
  'captureTimeout',
  'fingerEnabled',
  'serialPollEnabled',
  'serialPollInterval',
  'serialResponseTimeout',
  'pollingMode',
  'slotStatusPushInterval',
  'mqttStatusReportInterval',
  'mqttHeartbeatInterval',
  'slotSortDirection',
  'serialPort',
  'baudRate',
  'groupSize',
  'totalSlots',
  'communicationMode',
  'httpHost',
  'httpPort',
  'mqttHost',
  'mqttPort',
  'mqttReconnectInitialInterval',
  'mqttReconnectMaxInterval'
])

export const NATIVE_RUNTIME_CONFIG_FIELDS = Object.freeze([
  'serialPort',
  'baudRate',
  'groupSize',
  'totalSlots',
  'serialPollEnabled',
  'serialPollInterval',
  'serialResponseTimeout',
  'pollingMode',
  'communicationMode',
  'httpHost',
  'httpPort',
  'mqttHost',
  'mqttPort',
  'mqttHeartbeatInterval',
  'mqttReconnectInitialInterval',
  'mqttReconnectMaxInterval'
])

export const SUPER_ADMIN_PASSWORD_FIELDS = Object.freeze([
  'superAdminPassword',
  'initialAdminPassword',
  'initAdminPassword',
  'adminInitialPassword',
  'adminPassword',
  'initialPassword'
])

export const DEVELOPER_PASSWORD_FIELD = 'developerPassword'

const BOOLEAN_FIELDS = Object.freeze([
  'cameraMirror',
  'needFaceLiveness',
  'serialPollEnabled'
])

const INTEGER_FIELDS = Object.freeze([
  'cameraRotation',
  'cameraFrameWidth',
  'cameraFrameHeight',
  'faceRecognitionTimeout',
  'searchTimeout',
  'searchIntervalTime',
  'captureTimeout',
  'serialPollInterval',
  'serialResponseTimeout',
  'slotStatusPushInterval',
  'mqttStatusReportInterval',
  'mqttHeartbeatInterval',
  'baudRate',
  'groupSize',
  'totalSlots',
  'httpPort',
  'mqttPort',
  'mqttReconnectInitialInterval',
  'mqttReconnectMaxInterval'
])

const NUMBER_FIELDS = Object.freeze(['faceThreshold', 'fingerThreshold'])
const STRING_FIELDS = Object.freeze([
  'cameraFacing',
  'fingerEnabled',
  'pollingMode',
  'slotSortDirection',
  'serialPort',
  'communicationMode',
  'httpHost',
  'mqttHost'
])

const ENUM_FIELDS = Object.freeze({
  cameraFacing: new Set(['front', 'back']),
  cameraRotation: new Set([0, 90, 180, 270]),
  fingerEnabled: new Set(['0', '1']),
  pollingMode: new Set(['GROUP', 'SINGLE']),
  slotSortDirection: new Set(['HORIZONTAL', 'VERTICAL']),
  communicationMode: new Set(['MQTT', 'HTTP', 'BOTH'])
})

export function extractSuperAdminPasswordConfig(payload = {}) {
  const data = payload?.body?.data || payload?.data || payload
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return { found: false, field: '', password: undefined }
  }
  for (const field of SUPER_ADMIN_PASSWORD_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(data, field)) {
      return { found: true, field, password: data[field] }
    }
  }
  return { found: false, field: '', password: undefined }
}

export function extractSystemCredentialPasswordConfig(payload = {}) {
  const data = payload?.body?.data || payload?.data || payload
  const developer = data && typeof data === 'object' && !Array.isArray(data)
    && Object.prototype.hasOwnProperty.call(data, DEVELOPER_PASSWORD_FIELD)
    ? { found: true, field: DEVELOPER_PASSWORD_FIELD, password: data[DEVELOPER_PASSWORD_FIELD] }
    : { found: false, field: '', password: undefined }
  return {
    developer,
    superAdmin: extractSuperAdminPasswordConfig(data)
  }
}

export function validateCompleteDeviceConfig(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('设备配置响应 data 格式错误')
  }

  const missingFields = DEVICE_CONFIG_FIELDS.filter((field) => (
    !Object.prototype.hasOwnProperty.call(payload, field) || payload[field] == null
  ))
  if (missingFields.length > 0) {
    throw new Error(`设备配置响应缺少字段: ${missingFields.join(', ')}`)
  }

  const invalidFields = []
  BOOLEAN_FIELDS.forEach((field) => {
    if (typeof payload[field] !== 'boolean') invalidFields.push(field)
  })
  INTEGER_FIELDS.forEach((field) => {
    if (!Number.isInteger(payload[field])) invalidFields.push(field)
  })
  NUMBER_FIELDS.forEach((field) => {
    if (typeof payload[field] !== 'number' || !Number.isFinite(payload[field])) invalidFields.push(field)
  })
  STRING_FIELDS.forEach((field) => {
    if (typeof payload[field] !== 'string') invalidFields.push(field)
  })
  Object.entries(ENUM_FIELDS).forEach(([field, allowed]) => {
    if (!allowed.has(payload[field])) invalidFields.push(field)
  })

  if (invalidFields.length > 0) {
    throw new Error(`设备配置响应字段类型或取值错误: ${[...new Set(invalidFields)].join(', ')}`)
  }
  return payload
}

export function findChangedNativeRuntimeConfigFields(currentConfig = {}, nextConfig = {}) {
  return NATIVE_RUNTIME_CONFIG_FIELDS.filter((field) => !Object.is(currentConfig[field], nextConfig[field]))
}

export function createSyncConfigWorkflow({
  findResponse,
  markProcessing,
  syncConfig,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof syncConfig !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('syncConfig workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (message, originalMsgId) => {
    const existing = await findResponse(originalMsgId)
    if (existing?.payload?.data != null) {
      const reused = await sendResponse(existing.payload.data, originalMsgId)
      return {
        responded: reused.sent,
        queued: reused.queued === true,
        reused: true,
        msgId: originalMsgId,
        data: existing.payload.data
      }
    }

    await markProcessing(originalMsgId)
    let responseData = { code: 0, msg: 'success' }
    try {
      await syncConfig({ source: 'MQTT_SYNC_CONFIG', message })
    } catch (error) {
      responseData = {
        code: 500,
        msg: error?.message || 'syncConfig failed'
      }
    }

    const sent = await sendResponse(responseData, originalMsgId)
    return {
      responded: sent.sent,
      queued: sent.queued === true,
      msgId: sent.msgId || originalMsgId,
      data: responseData
    }
  }

  return async function handleSyncConfigCommand(message = {}) {
    const originalMsgId = String(message.msgId || '').trim()
    if (!originalMsgId) {
      return { responded: false, reason: 'MISSING_MSG_ID' }
    }
    if (inFlight.has(originalMsgId)) return inFlight.get(originalMsgId)

    const promise = execute(message, originalMsgId)
    inFlight.set(originalMsgId, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(originalMsgId)
    }
  }
}
