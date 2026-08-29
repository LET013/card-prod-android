/**
 * 业务服务层 V2 — 基于 JsBridgeV2 六大通道的业务封装。
 *
 * 所有业务逻辑在此层完成，不再依赖 Java 层的业务方法。
 * 只通过 nativeBridge 调用原始能力通道。
 *
 * 提供两种 API：
 *  - 新 6 通道 API（引导、HTTP、MQTT、串口、SQLite、人脸）
 *  - 旧业务方法兼容层（init、loadSettings 等）
 */

import nativeBridge from './nativeBridge.js'
import { createMockService } from './mockService.js'
import { createLocalStore, FACE_PHOTO_UPLOAD_STATE } from './localStore.js'
import {
  createMultiFaceEnrollmentWorkflow,
  createTemporaryFaceAiId,
  FACE_IMAGE_UPLOAD_PATH,
  FACE_RECORD_CREATE_PATH,
  normalizeFaceSyncItem
} from './faceEnrollmentWorkflow.js'
import {
  createTakeCardWorkflow,
  parseTakeOpenAck
} from './takeCardWorkflow.js'
import { createDoorOperationScheduler } from './doorOperationScheduler.js'
import {
  computeTakeCardSlotMaxAgeMs,
  selectTakeCardCandidate
} from './takeCardElection.js'
import { createEmployeeMutationWorkflow } from './employeeMutationWorkflow.js'
import {
  createSyncConfigWorkflow,
  DEVICE_CONFIG_FIELDS,
  extractSystemCredentialPasswordConfig,
  findChangedNativeRuntimeConfigFields,
  SUPER_ADMIN_PASSWORD_FIELDS,
  validateCompleteDeviceConfig
} from './syncConfigWorkflow.js'
import {
  createUnsupportedDeviceCommandWorkflow,
  UNSUPPORTED_DEVICE_COMMAND_NAMES,
  UNSUPPORTED_DEVICE_COMMANDS
} from './unsupportedDeviceCommandWorkflow.js'
import {
  createRestartAppCommandWorkflow,
  RESTART_APP_SUCCESS_RESPONSE
} from './restartAppCommandWorkflow.js'
import { createRemoteEjectAllCommandWorkflow } from './remoteEjectAllCommandWorkflow.js'
import { createRemoteOpenCommandWorkflow } from './remoteOpenCommandWorkflow.js'
import {
  cardNoOf,
  isAdminCardPhysicallyRemoved,
  planAdminEjectAll,
  slotNumberOf,
  validateAdminCardSlot
} from './adminCardAction.js'
import {
  createStatusReportWorkflow
} from './statusReportWorkflow.js'
import { selectMqttResponseCorrelation } from './mqttResponseCorrelation.js'
import { assertMqttDispatchAccepted } from './mqttDispatch.js'
import { createMqttSendQueue, MQTT_PRIORITY } from './mqttSendQueue.js'
import { createSyncUserWorkflow } from './syncUserWorkflow.js'
import { createCardEventRetryScheduler } from './cardEventRetryScheduler.js'
import {
  createLogUploadPolicyWorkflow,
  LOG_UPLOAD_COMMAND_NAMES,
  LOG_UPLOAD_COMMANDS
} from './logUploadPolicyWorkflow.js'
import {
  buildHardwareFaultTransition,
  createDiagnosticDeliveryWorkflow,
  DIAGNOSTIC_OUTBOX_TYPES
} from './diagnosticDeliveryWorkflow.js'
import { createSelfCheckCommandWorkflow } from './selfCheckCommandWorkflow.js'
import {
  extractAppVersionCheckData,
  normalizeAppVersionInfo
} from './appUpdateWorkflow.js'
import {
  buildAdminCardOpenedPrompt,
  buildAdminWelcomePrompt,
  buildTakeCardFailurePrompt,
  buildTakeCardSuccessPrompt
} from './ttsPrompts.js'
// 当前在线版启动只保存 HTTP 注册服务地址；完整运行配置由 GET /api/v1/device/config 下发。
import {
  MQTT_CONNECTION_CONFIG_FIELDS,
  normalizeBootstrapServerUrl,
  normalizeMqttTimingConfig,
  OFFLINE_ACTIVATION_RESERVED,
  parseBootstrapServerUrl,
  usesMqttConnection
} from '@/constants/config.js'
import { normalizeAppChannelId } from '@/constants/appChannel.js'
import { normalizeFaceRuntimeOptions } from './faceRuntimeConfig.js'
import { canonicalizeRemoteDeviceConfigLayout } from './deviceConfigLayout.js'
import {
  appState,
  replaceEmployeesProjection,
  replaceHistoryProjection,
  replaceDeviceInfoProjection,
  replaceSettingsProjection,
  replaceSessionProjection,
  clearSessionProjection,
  setGlobalNotice,
  noticeError,
  setFirmwareUpgrade,
  getFirmwareUpgrade,
  updateFirmwareUpgradeStatus,
  upsertSlotProjection,
  setCabinetOperationEffect,
  clearCabinetOperationEffect
} from '@/state/appState.js'

// ── 模式判断 ──

const isMockDev = typeof __CARD_MOCK_DEV__ !== 'undefined' && __CARD_MOCK_DEV__ === true
const isRelease = !isMockDev
const MOCK_APP_RESTART_STORAGE_KEY = 'card.mock.appRestart'
let mockAppRestartTimer = null
const cabinetOperationEffectTimers = new Map()

let useRelease = isRelease

export function setReleaseMode(enabled) {
  useRelease = enabled
}

const normalizePort = (value, fallback = 0) => {
  const port = Number(value)
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : fallback
}

const normalizeCameraFacing = (value) => {
  const facing = String(value || '').toLowerCase()
  return facing === 'back' ? 'back' : 'front'
}

const normalizeCameraRotation = (value, fallback = 0) => {
  const rotation = Number(value)
  return [0, 90, 180, 270].includes(rotation) ? rotation : fallback
}

const normalizeSlotSortDirection = (value) => {
  const direction = String(value || '').toUpperCase()
  return direction === 'VERTICAL' ? 'VERTICAL' : 'HORIZONTAL'
}

const normalizeCommunicationMode = (value) => {
  const mode = String(value || '').toUpperCase()
  return ['MQTT', 'HTTP', 'BOTH'].includes(mode) ? mode : 'MQTT'
}

const normalizePollingMode = (value) => {
  const mode = String(value || '').toUpperCase()
  return mode === 'SINGLE' ? 'SINGLE' : 'GROUP'
}

const normalizeFingerEnabled = (value) => {
  if (value === '1' || value === 1 || value === true) return '1'
  return '0'
}

const extractHostFromServerUrl = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    return new URL(raw.includes('://') ? raw : `http://${raw}`).hostname || ''
  } catch (error) {
    return ''
  }
}

const SYNC_DATASETS = {
  employees: {
    path: '/api/v1/employee/sync',
    scope: 'employees',
    pageSize: 50,
    maxPageSize: 100,
    listKey: 'employees'
  },
  faceBindings: {
    path: '/api/v1/employee/face/sync',
    scope: 'face_bindings',
    pageSize: 10,
    maxPageSize: 30,
    listKey: 'faceFeatures',
    // 需要特征、服务器路径和 Base64 照片，才能完成模板导入与私有照片落库。
    extraRequest: { includeFlags: 7 }
  },
  fingerBindings: {
    path: '/api/v1/employee/finger/sync',
    scope: 'finger_bindings',
    pageSize: 20,
    maxPageSize: 50,
    listKey: 'fingerFeatures'
  }
}

const MAX_SYNC_PAGES = 1000
const FACE_SYNC_INTERVAL_MS = 5 * 60 * 1000
const FACE_REGISTERED_EMPLOYEES_PATH = '/api/v1/employee/face/registered'
const MQTT_BUSINESS_COMMANDS = [
  'syncUser',
  'syncConfig',
  'deviceSelfCheck',
  'restartApp',
  'remoteOpen',
  'remoteEjectAll',
  'firmwareUpgrade',
  'cancelUpgrade',
  ...UNSUPPORTED_DEVICE_COMMAND_NAMES,
  ...LOG_UPLOAD_COMMAND_NAMES,
  'syncEmployeeDataResp',
  'syncFingerDataResp',
  'faceRegisterResp'
]
const MQTT_SYNC_RESPONSE_COMMANDS = new Set([
  'syncEmployeeDataResp',
  'syncFingerDataResp'
])
const MQTT_UPSTREAM_RESPONSE_COMMANDS = new Set([
  'statusReportResp',
  'cardEventResp',
  'logReportResp',
  'hardwareFaultResp',
  'selfCheckReportResp',
  'faceRegisterResp',
  'saveEmployeeResp',
  'getDepartmentResp',
  'deviceConfigResp'
])
const STATUS_REPORT_STATUS_MAP = {
  EMPTY: 'EMPTY',
  OCCUPIED: 'OCCUPIED',
  CHARGING: 'CHARGING',
  FULL: 'FULL',
  ILLEGAL_CARD: 'FAULT',
  CHARGING_FAULT: 'FAULT',
  COMMUNICATION_FAULT: 'FAULT'
}
const STATUS_REPORT_CHARGE_STATES = new Set(['CHARGING', 'FULL', 'DISCHARGING', 'IDLE'])
const MQTT_BUSINESS_REGISTER_RETRY_DELAYS = [1000, 3000, 5000, 10000]
const MQTT_RESPONSE_OUTBOX_TYPE = 'MQTT_RESPONSE'
const MQTT_COMMAND_STATE_TYPE = 'MQTT_COMMAND_STATE'
const MQTT_RESPONSE_RETRY_DELAY_MS = 10000
const MQTT_UPSTREAM_RESPONSE_TIMEOUT_MS = 10000
const STATUS_REPORT_RETRY_DELAY_MS = 10000
const DIAGNOSTIC_RETRY_DELAY_MS = 10000
const CARD_EVENT_RETRY_DELAY_MS = 10000
const FIRMWARE_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000
const FIRMWARE_TRANSFER_TIMEOUT_MS = 30 * 60 * 1000
let mqttBusinessHandlersRegistered = false
let mqttBusinessUnsubscribe = null
let mqttBusinessRegistrationPromise = null
let mqttBusinessRegistrationRetryTimer = null
let startupOutboxRecovered = false
let mqttBusinessRegistrationRetryCount = 0
let faceSyncIntervalTimer = null
let faceSyncPromise = null
let adminExitFaceSyncPromise = null
let mqttResponseFlushPromise = null
let mqttResponseFlushTimer = null
const pendingMqttBusinessResponses = new Map()
let activeFirmwareUpgrade = null
let statusReportTimer = null
let statusReportInFlight = null
let statusReportFlushTimer = null
let statusReportFlushPromise = null
let lastStatusReportAt = 0
let diagnosticFlushTimer = null
let diagnosticFlushPromise = null
let mqttSendQueueInstance = null

const pickDeviceConfigPayload = (settings = {}) => {
  const payload = {}
  const includeMqttConnection = usesMqttConnection(settings.communicationMode || settings.backendTransport)
  DEVICE_CONFIG_FIELDS.forEach((key) => {
    if (!includeMqttConnection && MQTT_CONNECTION_CONFIG_FIELDS.includes(key)) return
    if (settings[key] !== undefined && settings[key] !== null) payload[key] = settings[key]
  })
  return payload
}

const SLOT_LAYOUT_CONFIG_FIELDS = Object.freeze([
  'totalSlots',
  'groupSize',
  'slotSortDirection'
])

const assertSavedSlotLayout = (requested, remoteData) => {
  if (!remoteData || typeof remoteData !== 'object' || Array.isArray(remoteData)) {
    const error = new Error('保存设备配置响应缺少完整配置')
    error.code = 'CONFIG_RESPONSE_MISSING'
    throw error
  }
  const expected = normalizeDeviceConfig(requested)
  const confirmed = normalizeDeviceConfig(remoteData)
  const missing = SLOT_LAYOUT_CONFIG_FIELDS.filter((field) => {
    const alias = field === 'totalSlots'
      ? 'totalCount'
      : field === 'groupSize'
        ? 'singleGroupCount'
        : 'slotLayoutDirection'
    return !(field in remoteData) && !(alias in remoteData)
  })
  if (missing.length) {
    const error = new Error(`后台保存响应缺少卡槽布局字段: ${missing.join(', ')}`)
    error.code = 'CONFIG_LAYOUT_MISSING'
    throw error
  }
  const inconsistent = SLOT_LAYOUT_CONFIG_FIELDS.find((field) => expected[field] !== confirmed[field])
  if (inconsistent) {
    const error = new Error(`后台未确认${inconsistent}，本机未应用该卡槽布局`)
    error.code = 'CONFIG_LAYOUT_NOT_CONFIRMED'
    throw error
  }
  return confirmed
}

const unsupportedClientFeature = (code, message) => {
  const error = new Error(message)
  error.code = code
  error.supported = false
  throw error
}

const INITIAL_ADMIN_PASSWORD_KEYS = [
  'developerPassword',
  ...SUPER_ADMIN_PASSWORD_FIELDS
]

const stripInitialAdminPassword = (value = {}) => {
  if (!value || typeof value !== 'object') return value || {}
  const next = { ...value }
  INITIAL_ADMIN_PASSWORD_KEYS.forEach((key) => { delete next[key] })
  if (next.data && typeof next.data === 'object') {
    next.data = stripInitialAdminPassword(next.data)
  }
  if (next.body?.data && typeof next.body.data === 'object') {
    next.body = { ...next.body, data: stripInitialAdminPassword(next.body.data) }
  }
  return next
}

const unwrapResponsePayload = (response) => {
  const body = response?.body || response
  if (body?.data && typeof body.data === 'object') return body.data
  if (response?.data && typeof response.data === 'object') return response.data
  return body || {}
}

const isBackendDuplicateResponse = (payload) => {
  const message = String(payload?.msg || payload?.message || '').trim()
  return message.includes('消息去重拦截') || message.includes('去重')
}

const assertBackendSuccess = (payload, action, { requireCode = false } = {}) => {
  if (isBackendDuplicateResponse(payload)) return
  const code = payload?.code
  if (code == null) {
    if (!requireCode) return
    const error = new Error(`${action}响应缺少业务状态码`)
    error.code = 'BACKEND_CODE_MISSING'
    throw error
  }
  if (code === 0 || code === 200 || code === '0' || code === '200') return
  const error = new Error(payload?.msg || payload?.message || `${action}失败`)
  error.code = `BACKEND_${code}`
  throw error
}

const assertHttpSuccess = (response, action) => {
  const status = Number(response?.status || 0)
  if (status && (status < 200 || status >= 300)) {
    const body = response?.body
    const message = body?.msg || body?.message || response?.error || `${action}HTTP失败(${status})`
    const error = new Error(message)
    error.code = `HTTP_${status}`
    throw error
  }
  if (response?.error && status === 0) {
    const error = new Error(response.error)
    error.code = 'HTTP_ERROR'
    throw error
  }
}

const assertSyncPayloadShape = (payload, config) => {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error(`${config.scope} 同步响应格式错误`)
  }
  if (payload.code == null) {
    throw new Error(`${config.scope} 同步响应缺少 code`)
  }
  if (!Array.isArray(payload[config.listKey])) {
    throw new Error(`${config.scope} 同步响应缺少 ${config.listKey}`)
  }
}

const normalizeSyncVersion = (value, fallback = 0) => {
  const version = Number(value)
  return Number.isFinite(version) && version >= 0 ? version : fallback
}

const normalizeFaceCameraOptions = normalizeFaceRuntimeOptions

const normalizeDeviceConfig = (settings = {}) => {
  const source = stripInitialAdminPassword(settings)
  const mqttTiming = normalizeMqttTimingConfig(source)
  const totalSlots = Number(source.totalSlots ?? source.totalCount)
  const groupSize = Number(source.groupSize ?? source.singleGroupCount)
  const communicationMode = normalizeCommunicationMode(source.communicationMode || source.backendTransport)
  const slotSortDirection = normalizeSlotSortDirection(source.slotSortDirection || source.slotLayoutDirection)
  const parsedServerUrl = parseBootstrapServerUrl(source.serverUrl || source.httpServerAddress)
  const httpHost = String(source.httpHost || parsedServerUrl.host || extractHostFromServerUrl(source.serverUrl || source.httpServerAddress)).trim()
  // 后端 getConfig 下发 httpHost+httpPort（非旧字段 serverUrl），需要从新字段回构 serverUrl
  // 后端强制 HTTPS；HTTP→HTTPS 301 重定向会导致 Android HttpClient 请求失败
  const httpPort = source.httpHost
    ? normalizePort(source.httpPort, parsedServerUrl.port || 8082)
    : parsedServerUrl.port || normalizePort(source.httpPort, 8082)
  const mqttHost = String(source.mqttHost || source.mqttServerAddress || '').trim()
  const mqttPort = normalizePort(source.mqttPort, 0)
  const resolvedFromHost = httpHost
    ? `https://${httpHost}${httpPort && httpPort !== 80 ? ':' + httpPort : ''}`
    : ''
  const serverUrl = String(source.serverUrl || source.httpServerAddress || resolvedFromHost)
    .trim()
    .replace(/^http:\/\//, 'https://')
    .replace(/:80(?=\/|$)/, '')
  const serialPollEnabled = source.serialPollEnabled != null
    ? Boolean(source.serialPollEnabled)
    : source.serialPollingEnabled !== false
  const serialPollInterval = Number(source.serialPollInterval ?? source.serialPollingIntervalMs ?? 5000)
  const serialResponseTimeout = Number(source.serialResponseTimeout ?? source.serialResponseTimeoutMs ?? 700)
  const mqttStatusReportInterval = Number(source.mqttStatusReportInterval ?? 300)
  const slotStatusPushInterval = Number(source.slotStatusPushInterval ?? source.slotStatusReportIntervalMs ?? source.slotUiPushIntervalMs ?? 60000)
  const faceThreshold = Number(source.faceThreshold ?? source.faceRecognitionThreshold ?? 0.8)
  const fingerThreshold = Number(source.fingerThreshold ?? source.fingerRecognitionThreshold ?? 0.8)
  const cameraFacing = normalizeCameraFacing(source.cameraFacing)
  const initialized = source.initialized != null
    ? Boolean(source.initialized)
    : Boolean(serverUrl || mqttHost || totalSlots || groupSize)
  return {
    ...source,
    initialized,
    cabinetNumber: String(source.cabinetNumber || source.cabinetNo || '').trim(),
    deviceId: source.deviceId || source.deviceCode || '',
    deviceCode: source.deviceCode || source.deviceId || '',
    communicationMode,
    backendTransport: communicationMode,
    slotSortDirection,
    serverUrl,
    httpServerAddress: serverUrl,
    httpHost,
    httpPort,
    mqttHost,
    mqttServerAddress: mqttHost,
    mqttPort,
    ...mqttTiming,
    totalSlots: Number.isInteger(totalSlots) && totalSlots > 0 ? totalSlots : undefined,
    totalCount: Number.isInteger(totalSlots) && totalSlots > 0 ? totalSlots : source.totalCount,
    groupSize: Number.isInteger(groupSize) && groupSize > 0 ? groupSize : undefined,
    singleGroupCount: Number.isInteger(groupSize) && groupSize > 0 ? groupSize : source.singleGroupCount,
    serialPollEnabled,
    serialPollingEnabled: serialPollEnabled,
    serialPollInterval,
    serialPollingIntervalMs: serialPollInterval,
    serialResponseTimeout,
    serialResponseTimeoutMs: serialResponseTimeout,
    mqttStatusReportInterval: Number.isInteger(mqttStatusReportInterval) && mqttStatusReportInterval > 0
      ? mqttStatusReportInterval
      : 300,
    slotStatusPushInterval,
    slotStatusReportIntervalMs: slotStatusPushInterval,
    pollingMode: normalizePollingMode(source.pollingMode),
    faceRecognitionThreshold: faceThreshold,
    faceThreshold,
    fingerThreshold,
    fingerRecognitionThreshold: fingerThreshold,
    fingerEnabled: normalizeFingerEnabled(source.fingerEnabled ?? source.fingerprintEnabled),
    fingerprintEnabled: normalizeFingerEnabled(source.fingerEnabled ?? source.fingerprintEnabled) === '1',
    faceRecognitionTimeout: Number(source.faceRecognitionTimeout || 30000),
    searchTimeout: Number(source.searchTimeout || 15000),
    searchIntervalTime: Number(source.searchIntervalTime || 3000),
    needFaceLiveness: Boolean(source.needFaceLiveness),
    captureTimeout: Number(source.captureTimeout || 8000),
    cameraFacing,
    cameraMirror: source.cameraMirror != null ? Boolean(source.cameraMirror) : cameraFacing === 'front',
    cameraRotation: normalizeCameraRotation(source.cameraRotation, 0),
    cameraFrameWidth: Number(source.cameraFrameWidth || 640),
    cameraFrameHeight: Number(source.cameraFrameHeight || 480)
  }
}

/**
 * 从服务端下发的完整 config JSON 中提取 bootstrap 启动所需的连接参数。
 * 不再回退到本地硬编码默认值；config 缺失必填字段时由 Android 启动流程抛异常。
 * serverUrl 首次由用户输入；后续 getConfig 下发 httpHost + httpPort，经 normalizeDeviceConfig 回构为 serverUrl。
 */
const bootstrapConfigFromSettings = (settings = {}) => {
  const normalized = normalizeDeviceConfig(settings)
  return {
    serverUrl: normalizeBootstrapServerUrl(normalized.serverUrl),
    mqttHost: normalized.mqttHost || '',
    mqttPort: normalizePort(normalized.mqttPort, 0),
    httpPort: normalizePort(normalized.httpPort, 0)
  }
}

// ── 事件取消器 ──

const unsubscribers = []
const SLOT_CACHE_FLUSH_DELAY_MS = 250
const SLOT_CACHE_MAX_SILENCE_MS = 3000
const pendingSlotCache = new Map()
const persistedSlotCache = new Map()
let slotCacheFlushTimer = null
let slotCacheFlushPromise = null
let slotCacheWaiters = []

// ── 6 通道底层 API ──

// 启动流程
async function bootstrap(config) {
  return nativeBridge.request('bootstrap.start', config)
}
async function bootstrapActivate(code) {
  return nativeBridge.request('bootstrap.activate', { code })
}
async function bootstrapRetry() {
  return nativeBridge.request('bootstrap.retry')
}
async function bootstrapRefreshCode() {
  return nativeBridge.request('bootstrap.refreshCode')
}
async function bootstrapCancel() {
  return nativeBridge.request('bootstrap.cancel')
}
async function bootstrapDeviceInfo() {
  return nativeBridge.request('bootstrap.deviceInfo')
}

async function offlineActivationStatus() {
  try {
    const native = await nativeBridge.request('offlineActivation.status', {}, 3000)
    return { ...OFFLINE_ACTIVATION_RESERVED, ...(native || {}) }
  } catch (error) {
    // 当前在线版允许 Bridge 不存在或未实现时回落到本地预留状态。
  }
  try {
    const local = await localStore.loadOfflineActivationState()
    return { ...OFFLINE_ACTIVATION_RESERVED, ...(local || {}) }
  } catch (error) {
    return {
      ...OFFLINE_ACTIVATION_RESERVED,
      error: error?.message || String(error)
    }
  }
}

async function offlineActivationActivate() {
  return nativeBridge.request('offlineActivation.activate', {})
}

async function offlineActivationLoadConfig() {
  return nativeBridge.request('offlineActivation.loadConfig', {})
}

// HTTP
async function httpGet(path) {
  return nativeBridge.request('http.get', { path })
}
async function httpPost(path, body) {
  return nativeBridge.request('http.post', { path, body })
}
async function httpMultipart(path, fields, file) {
  return nativeBridge.request('http.multipart', { path, fields, file }, 60000)
}
async function httpDownload(path, targetDir, timeoutMs = 60000) {
  return nativeBridge.request('http.download', { path, targetDir }, timeoutMs)
}
async function httpGetAsync(path, requestId) {
  return nativeBridge.request('http.get', { path, mode: 'async', requestId })
}
async function httpPostAsync(path, body, requestId) {
  return nativeBridge.request('http.post', { path, body, mode: 'async', requestId })
}

// MQTT
const rawMqttSend = async (cmd, data, options = {}) => {
  return nativeBridge.request('mqtt.send', {
    cmd,
    data,
    ...(options?.msgId ? { msgId: options.msgId } : {})
  })
}

function getMqttSendQueue() {
  if (!mqttSendQueueInstance) {
    mqttSendQueueInstance = createMqttSendQueue({
      nativeSend: rawMqttSend,
      checkConnection: async () => {
        try {
          const status = await mqttLoginStatus()
          return isMqttBusinessReady(status)
        } catch (e) {
          return false
        }
      },
      onDrop: (entry, reason) => {
        console.warn('[mqtt-queue] dropped cmd=%s reason=%s', entry.cmd, reason)
      }
    })
  }
  return mqttSendQueueInstance
}

async function mqttSend(cmd, data, options = {}, priority = MQTT_PRIORITY.NORMAL) {
  const queue = getMqttSendQueue()
  if (!queue) return rawMqttSend(cmd, data, options)
  return queue.enqueue({ cmd, data, options, priority })
}
async function mqttLoginStatus() {
  return nativeBridge.request('mqtt.loginStatus')
}
// MQTT 业务请求必须同时满足传输连接和服务器登录成功。
function isMqttBusinessReady(status) {
  return status?.connected === true && status?.authenticated === true
}

/** 用原生当前登录状态刷新页面投影，避免错过早于 WebView 监听器的认证事件。 */
async function refreshMqttConnectionProjection() {
  const status = await mqttLoginStatus()
  const mqttConnected = isMqttBusinessReady(status)
  appState.deviceInfo.mqttConnected = mqttConnected
  appState.runtime.socket = {
    ...(appState.runtime.socket || {}),
    connected: status?.connected === true,
    authenticated: status?.authenticated === true,
    state: mqttConnected ? 'CONNECTED' : 'DISCONNECTED',
    message: mqttConnected ? '后端通信已连接' : '后端通信未连接'
  }
  return mqttConnected
}
async function mqttRegisterCmd(cmd) {
  return nativeBridge.request('mqtt.handleMessage', { cmd })
}

const mqttBusinessResponseKey = (cmd, msgId) => `${cmd}:${msgId}`

function waitForMqttBusinessResponse(cmd, msgId, timeoutMs = MQTT_UPSTREAM_RESPONSE_TIMEOUT_MS) {
  const key = mqttBusinessResponseKey(cmd, msgId)
  if (pendingMqttBusinessResponses.has(key)) {
    throw new Error(`MQTT response waiter already exists: ${key}`)
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingMqttBusinessResponses.delete(key)
      reject(new Error(`${cmd} timed out for msgId ${msgId}`))
    }, timeoutMs)
    pendingMqttBusinessResponses.set(key, { resolve, timer })
  })
}

function clearMqttBusinessResponseWaiter(cmd, msgId) {
  const key = mqttBusinessResponseKey(cmd, msgId)
  const pending = pendingMqttBusinessResponses.get(key)
  if (!pending) return false
  clearTimeout(pending.timer)
  pendingMqttBusinessResponses.delete(key)
  return true
}

function resolveMqttBusinessResponse(message = {}) {
  const correlation = selectMqttResponseCorrelation(
    pendingMqttBusinessResponses.keys(),
    message
  )
  if (!correlation) return false
  const pending = pendingMqttBusinessResponses.get(correlation.key)
  if (!pending) return false
  clearTimeout(pending.timer)
  pendingMqttBusinessResponses.delete(correlation.key)
  pending.resolve({
    ...message,
    requestMsgId: correlation.requestMsgId,
    correlationMode: correlation.mode
  })
  return true
}

async function sendMqttAndWaitForResponse(cmd, data, responseCmd, msgId) {
  const responsePromise = waitForMqttBusinessResponse(responseCmd, msgId)
  try {
    const sent = assertMqttDispatchAccepted(await mqttSend(cmd, data, { msgId }, MQTT_PRIORITY.HIGH), cmd)
    if (String(sent?.msgId || msgId) !== msgId) {
      throw new Error(`${cmd} returned an unexpected msgId`)
    }
    const response = await responsePromise
    return { sent, response }
  } catch (error) {
    clearMqttBusinessResponseWaiter(responseCmd, msgId)
    throw error
  }
}

const mqttResponseEventId = (cmd, msgId) => `mqtt-response:${cmd}:${msgId}`

function schedulePendingMqttResponseFlush(reason = 'retry') {
  if (mqttResponseFlushTimer || mqttResponseFlushPromise) {
    return { scheduled: true, reason, reused: true }
  }
  mqttResponseFlushTimer = setTimeout(() => {
    mqttResponseFlushTimer = null
    flushPendingMqttResponses(reason).catch((error) => {
      console.warn('[mqtt] flush pending responses failed:', error)
    })
  }, MQTT_RESPONSE_RETRY_DELAY_MS)
  return { scheduled: true, reason, dueIn: MQTT_RESPONSE_RETRY_DELAY_MS }
}

async function saveMqttResponseOutbox(cmd, msgId, data, state = 'PENDING') {
  await initializeLocalStore()
  return localStore.upsertOutboxEvent({
    eventId: mqttResponseEventId(cmd, msgId),
    eventType: MQTT_RESPONSE_OUTBOX_TYPE,
    payload: {
      cmd,
      msgId,
      data,
      updatedAt: Date.now()
    },
    state
  })
}

async function sendMqttResponseWithOutbox(cmd, data, msgId) {
  const actualMsgId = String(msgId || '').trim()
  if (!actualMsgId) throw new Error(`${cmd} missing msgId`)
  const eventId = mqttResponseEventId(cmd, actualMsgId)
  await saveMqttResponseOutbox(cmd, actualMsgId, data, 'PENDING')
  try {
    const sent = assertMqttDispatchAccepted(
      await mqttSend(cmd, data, { msgId: actualMsgId }, MQTT_PRIORITY.NORMAL),
      cmd
    )
    await localStore.markOutboxEventSent(eventId)
    return { sent: true, msgId: sent?.msgId || actualMsgId, result: sent }
  } catch (error) {
    await localStore.markOutboxEventFailed(eventId, error, MQTT_RESPONSE_RETRY_DELAY_MS)
    schedulePendingMqttResponseFlush('send-failed')
    return {
      sent: false,
      queued: true,
      msgId: actualMsgId,
      error: error?.message || String(error)
    }
  }
}

const normalizeDeviceAuthorization = (response, fallback = {}) => {
  const payload = unwrapResponsePayload(response)
  const data = payload?.data && typeof payload.data === 'object' ? payload.data : payload
  if (typeof data?.authorized === 'boolean') {
    return {
      state: data.authorized ? 'AUTHORIZED' : 'UNAUTHORIZED',
      message: data.authorized ? '授权有效' : '未授权或授权已过期',
      authorized: data.authorized,
      authorizedUntil: Number(data.authorizedUntil || 0) || 0,
      daysRemaining: Number(data.daysRemaining || 0) || 0,
      features: Array.isArray(data.features) ? data.features : []
    }
  }
  if (fallback.state && fallback.state !== 'PENDING') {
    return {
      state: fallback.state,
      message: fallback.message || '授权状态待确认',
      authorized: fallback.authorized ?? null,
      authorizedUntil: Number(fallback.authorizedUntil || 0) || 0,
      daysRemaining: Number(fallback.daysRemaining || 0) || 0,
      features: Array.isArray(fallback.features) ? fallback.features : []
    }
  }
  if (fallback.activated === true) {
    return {
      state: 'ACTIVE_UNKNOWN',
      message: '设备已激活，授权状态未返回',
      authorized: null,
      authorizedUntil: 0,
      daysRemaining: 0,
      features: []
    }
  }
  return {
    state: fallback.state || 'PENDING',
    message: fallback.message || '授权状态待确认',
    authorized: null,
    authorizedUntil: 0,
    daysRemaining: 0,
    features: []
  }
}

const normalizeDeviceInfo = (info = {}, mqttConnected = false) => ({
  deviceCode: info.deviceCode || info.deviceId || appState.settings.deviceCode || appState.settings.deviceId || '',
  channelId: normalizeAppChannelId(info.channelId || appState.deviceInfo.channelId),
  activated: info.activated === true || info.isActivated === true || String(info.activationStatus || '').toUpperCase() === 'ACTIVATED',
  mqttConnected: Boolean(mqttConnected)
})

async function flushPendingMqttResponses(reason = 'manual') {
  if (mqttResponseFlushPromise) {
    return mqttResponseFlushPromise
  }
  mqttResponseFlushPromise = (async () => {
    await initializeLocalStore()
    const events = await localStore.listDueOutboxEvents(MQTT_RESPONSE_OUTBOX_TYPE, 20)
    if (!events.length) {
      return { flushed: 0, failed: 0, reason, empty: true }
    }
    const status = await mqttLoginStatus().catch(() => ({ connected: false }))
    if (!isMqttBusinessReady(status)) {
      schedulePendingMqttResponseFlush('mqtt-offline')
      return { flushed: 0, failed: 0, skipped: true, reason: 'MQTT_OFFLINE' }
    }
    let flushed = 0
    let failed = 0
    for (const event of events) {
      const payload = event.payload || {}
      const cmd = String(payload.cmd || '').trim()
      const msgId = String(payload.msgId || '').trim()
      if (!cmd || !msgId || payload.data == null) continue
      try {
        assertMqttDispatchAccepted(await mqttSend(cmd, payload.data, { msgId }, MQTT_PRIORITY.NORMAL), cmd)
        await localStore.markOutboxEventSent(event.eventId)
        flushed += 1
      } catch (error) {
        failed += 1
        await localStore.markOutboxEventFailed(event.eventId, error, MQTT_RESPONSE_RETRY_DELAY_MS)
      }
    }
    if (failed > 0) schedulePendingMqttResponseFlush('flush-failed')
    return { flushed, failed, reason }
  })()
  try {
    return await mqttResponseFlushPromise
  } finally {
    mqttResponseFlushPromise = null
  }
}

const handleSyncUserCommand = createSyncUserWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('syncUserResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('syncUserResp', msgId, null, 'PROCESSING'),
  syncUser: (options) => syncIdentityData(options),
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('syncUserResp', data, msgId)
})

async function requestDeviceConfig({ transport = '' } = {}) {
  const mode = normalizeCommunicationMode(
    appState.settings.communicationMode || appState.settings.backendTransport
  )
  if (transport !== 'HTTP' && mode !== 'HTTP') {
    const mqttStatus = await mqttLoginStatus().catch(() => ({ connected: false }))
    if (isMqttBusinessReady(mqttStatus)) {
      const msgId = `config_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`
      await registerMqttBusinessHandlers({ reason: 'device-config' })
      const result = await sendMqttAndWaitForResponse('deviceConfig', {}, 'deviceConfigResp', msgId)
      const envelope = result?.response?.data
      assertBackendSuccess(envelope, 'deviceConfigResp', { requireCode: true })
      return validateCompleteDeviceConfig(envelope?.data)
    }
  }

  const response = await httpGet('/api/v1/device/config')
  assertHttpSuccess(response, 'deviceConfig')
  const envelope = response?.body || response
  assertBackendSuccess(envelope, 'deviceConfig', { requireCode: true })
  return validateCompleteDeviceConfig(envelope?.data)
}

async function syncRuntimeConfigFromServer() {
  await initializeLocalStore()
  const [runtimeConfig, localDraft] = await Promise.all([
    localStore.loadRuntimeConfig().catch(() => null),
    localStore.loadConfigDraft().catch(() => null)
  ])
  // syncConfig 只负责通知设备拉取配置，完整配置以 HTTP 接口为准。
  const data = canonicalizeRemoteDeviceConfigLayout(await requestDeviceConfig({ transport: 'HTTP' }))
  if (!runtimeConfig || typeof runtimeConfig !== 'object') {
    // 首次同步：本地无基线，以远端数据作为初始基线
    const seed = normalizeDeviceConfig({ ...(localDraft || {}), ...data })
    await cacheInitialAdminPassword(data, 'DEVICE_CONFIG', { required: true })
    await localStore.saveRuntimeConfig(seed)
    await localStore.saveConfigDraft(seed)
    await localStore.saveBootstrapConfig(bootstrapConfigFromSettings(seed))
    replaceSettingsProjection(seed)
    return seed
  }
  const current = normalizeDeviceConfig(runtimeConfig)
  const normalized = normalizeDeviceConfig({
    ...(runtimeConfig || {}),
    ...(localDraft || {}),
    ...data
  })
  const nativeRuntimeChanges = findChangedNativeRuntimeConfigFields(current, normalized)
  if (nativeRuntimeChanges.length > 0) {
    throw new Error(`Android 运行参数不支持热更新: ${nativeRuntimeChanges.join(', ')}`)
  }
  await cacheInitialAdminPassword(data, 'DEVICE_CONFIG', { required: true })
  await localStore.saveRuntimeConfig(normalized)
  await localStore.saveConfigDraft(normalized)
  await localStore.saveBootstrapConfig(bootstrapConfigFromSettings(normalized))
  replaceSettingsProjection(normalized)
  return normalized
}

const handleSyncConfigCommand = createSyncConfigWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('syncConfigResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('syncConfigResp', msgId, null, 'PROCESSING'),
  syncConfig: syncRuntimeConfigFromServer,
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('syncConfigResp', data, msgId)
})

const handleUnsupportedDeviceCommand = createUnsupportedDeviceCommandWorkflow({
  findResponse: async (responseCmd, msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId(responseCmd, msgId))
  },
  markProcessing: (responseCmd, msgId) => saveMqttResponseOutbox(responseCmd, msgId, null, 'PROCESSING'),
  recordFailure: ({ cmd, msgId, data, command, responseData }) => recordOperationHistory({
    operationId: `${cmd}:${msgId}`,
    operationType: command.operationType,
    operatorName: data.operatorId || '后台',
    slotNumber: Number(data.slotId || data.slotNumber || 0) || null,
    state: 'FAILED',
    requestMsgId: msgId,
    authType: data.authType || '',
    rawError: responseData
  }),
  sendResponse: (responseCmd, data, msgId) => sendMqttResponseWithOutbox(responseCmd, data, msgId)
})

const handleRestartAppCommand = createRestartAppCommandWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('restartAppResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('restartAppResp', msgId, null, 'PROCESSING'),
  scheduleRestart: scheduleAppRestart,
  recordOperation: (operation) => recordOperationHistory(operation),
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('restartAppResp', data, msgId)
})

const handleRemoteEjectAllCommand = createRemoteEjectAllCommandWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('remoteEjectAllResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('remoteEjectAllResp', msgId, null, 'PROCESSING'),
  executeEjectAll: ({ operationId, operatorId, msgId }) => startRemoteEjectAllDoors({
    operationId,
    operationType: 'REMOTE_EJECT_ALL',
    operatorName: operatorId,
    requestMsgId: msgId
  }),
  recordFailure: ({ operationId, operatorId, msgId, data }) => recordOperationHistory({
    operationId,
    operationType: 'REMOTE_EJECT_ALL',
    operatorName: operatorId,
    state: 'FAILED',
    requestMsgId: msgId,
    rawError: data
  }),
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('remoteEjectAllResp', data, msgId)
})

const handleRemoteOpenCommand = createRemoteOpenCommandWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('remoteOpenResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('remoteOpenResp', msgId, null, 'PROCESSING'),
  executeOpen: ({ operationId, operatorId, msgId, slotId, authType }) => executeAdminOpenDoor(slotId, {
    operationId,
    operationType: 'REMOTE_OPEN',
    operatorName: operatorId,
    requestMsgId: msgId,
    source: 'REMOTE_ADMIN',
    authType
  }),
  recordFailure: ({ operationId, operatorId, msgId, slotId, authType, data }) => recordOperationHistory({
    operationId,
    operationType: 'REMOTE_OPEN',
    operatorName: operatorId,
    slotNumber: slotId,
    state: 'FAILED',
    requestMsgId: msgId,
    authType,
    rawError: data
  }),
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('remoteOpenResp', data, msgId)
})

// ── 固件升级 MQTT handler ──
const firmwareOperationId = (msgId) => `firmwareUpgrade:${String(msgId || '').trim()}`

const firmwareErrorData = (error, fallback = '固件传输失败') => ({
  code: 500,
  msg: String(error?.message || fallback).slice(0, 500)
})

const assertFirmwareNotCancelled = (control) => {
  if (!control?.cancelRequested) return
  const error = new Error('固件升级已取消')
  error.code = 'FIRMWARE_UPGRADE_CANCELLED'
  throw error
}

async function saveFirmwareUpgradeOperation(operation = {}) {
  await initializeLocalStore()
  return localStore.saveOperationRecord({
    ...operation,
    operationType: 'FIRMWARE_UPGRADE'
  })
}

/** 使用 V4.2 明确定义的 HTTP 替代接口上报升级状态。 */
async function reportFirmwareUpgradeStatus(status, extra = {}) {
  const progress = Number(extra.progress)
  const payload = {
    firmwareVersion: String(extra.firmwareVersion || ''),
    status: String(status || ''),
    progress: Number.isFinite(progress) ? Math.max(0, Math.min(100, Math.trunc(progress))) : 0,
    errorMsg: extra.errorMsg ? String(extra.errorMsg).slice(0, 500) : null
  }
  const response = await httpPost('/api/v1/upgrade/status', payload)
  assertHttpSuccess(response, 'upgradeStatus')
  assertBackendSuccess(response?.body || response, 'upgradeStatus', { requireCode: true })
  return payload
}

async function executeFirmwareUpgrade(pending, progressCallback = null, control = {}) {
  const operationId = String(pending?.operationId || '').trim()
  const firmwareVersion = String(pending?.firmwareVersion || '').trim()
  const downloadUrl = String(pending?.downloadUrl || '').trim()
  if (!operationId || !firmwareVersion || !downloadUrl) {
    throw Object.assign(new Error('固件升级任务参数不完整'), { code: 'INVALID_FIRMWARE_TASK' })
  }

  const persist = (state, extra = {}) => saveFirmwareUpgradeOperation({
    operationId,
    state,
    firmwareVersion,
    downloadUrl,
    ...extra
  })
  let unsubscribe = null
  let lastPersistedProgress = -5

  try {
    control.phase = 'VALIDATED'
    await persist('VALIDATED', { progress: 0, hardwareVerified: false })
    assertFirmwareNotCancelled(control)

    control.phase = 'DOWNLOADING'
    updateFirmwareUpgradeStatus('DOWNLOADING', { progress: 0, errorMsg: '', hardwareVerified: false })
    setGlobalNotice(`正在下载固件 v${firmwareVersion}…`, 'info')
    progressCallback?.({ operationId, phase: 'DOWNLOADING', progress: 0 })
    await persist('DOWNLOADING', { progress: 0, hardwareVerified: false })
    await reportFirmwareUpgradeStatus('DOWNLOADING', {
      firmwareVersion,
      progress: 0
    }).catch((error) => console.warn('[upgrade] 下载状态上报失败:', error))

    const downloadResult = await httpDownload(
      downloadUrl,
      'firmware-downloads',
      FIRMWARE_DOWNLOAD_TIMEOUT_MS
    )
    if (!downloadResult || downloadResult.success === false) {
      const message = downloadResult?.error || downloadResult?.message || '固件下载返回空响应'
      throw Object.assign(new Error(String(message).slice(0, 500)), { code: 'DOWNLOAD_FAILED' })
    }
    const filePath = String(downloadResult.filePath || downloadResult.path || '').trim()
    const fileSize = Number(downloadResult.size || 0)
    if (!filePath) {
      throw Object.assign(new Error('固件下载完成但未返回应用私有文件路径'), { code: 'DOWNLOAD_PATH_MISSING' })
    }
    if (!Number.isFinite(fileSize) || fileSize <= 0) {
      throw Object.assign(new Error('固件下载文件为空'), { code: 'DOWNLOAD_FILE_EMPTY' })
    }
    assertFirmwareNotCancelled(control)

    control.phase = 'DOWNLOADED'
    updateFirmwareUpgradeStatus('DOWNLOADED', { progress: 0, filePath, fileSize })
    await persist('DOWNLOADED', { progress: 0, filePath, fileSize, hardwareVerified: false })

    unsubscribe = nativeBridge.on('serial.firmwareProgress', (event = {}) => {
      if (String(event.operationId || '') !== operationId) return
      const progress = Math.max(0, Math.min(100, Number(event.progress || 0)))
      control.phase = String(event.phase || 'TRANSMITTING').toUpperCase()
      updateFirmwareUpgradeStatus(control.phase, {
        progress,
        bytes: Number(event.transmittedBytes || 0),
        frames: Number(event.frames || 0),
        hardwareVerified: false
      })
      progressCallback?.({ ...event, operationId, progress })
      if (progress < lastPersistedProgress + 5 && progress !== 100) return
      lastPersistedProgress = progress
      persist('TRANSMITTING', {
        progress,
        filePath,
        fileSize,
        bytes: Number(event.transmittedBytes || 0),
        frames: Number(event.frames || 0),
        hardwareVerified: false
      }).catch((error) => console.warn('[upgrade] 串口进度保存失败:', error))
    })

    assertFirmwareNotCancelled(control)
    control.phase = 'TRANSMITTING'
    updateFirmwareUpgradeStatus('TRANSMITTING', { progress: 0, filePath, fileSize })
    setGlobalNotice(`正在向单板传输固件 v${firmwareVersion}…`, 'info')
    await persist('TRANSMITTING', { progress: 0, filePath, fileSize, hardwareVerified: false })
    const nativeResult = await nativeBridge.request('serial.firmwareUpgrade', {
      operationId,
      firmwareVersion,
      filePath
    }, FIRMWARE_TRANSFER_TIMEOUT_MS)
    if (nativeResult?.success !== true || String(nativeResult?.status || '').toUpperCase() !== 'TRANSMITTED') {
      throw Object.assign(new Error('串口固件传输未返回有效完成状态'), { code: 'INVALID_TRANSFER_RESULT' })
    }
    const transmittedBytes = Number(nativeResult.bytes || 0)
    if (!Number.isFinite(transmittedBytes) || transmittedBytes <= 0) {
      throw Object.assign(new Error('串口固件传输字节数无效'), { code: 'INVALID_TRANSFER_BYTES' })
    }
    if (nativeResult.hardwareVerified !== false) {
      throw Object.assign(new Error('串口固件结果缺少真机未验证标记'), { code: 'HARDWARE_VERIFICATION_STATE_INVALID' })
    }

    control.phase = 'TRANSMITTED'
    const result = {
      operationId,
      firmwareVersion,
      status: 'TRANSMITTED',
      progress: 100,
      bytes: transmittedBytes,
      frames: Number(nativeResult.frames || 0),
      simulator: nativeResult.simulator === true,
      hardwareVerified: false
    }
    updateFirmwareUpgradeStatus('TRANSMITTED', result)
    await persist('TRANSMITTED', { ...result, filePath, fileSize, nativeResult })
    await reportFirmwareUpgradeStatus('TRANSMITTED', {
      firmwareVersion,
      progress: 100
    }).catch((error) => console.warn('[upgrade] 传输完成状态上报失败:', error))
    setGlobalNotice(`固件 v${firmwareVersion} 已传输，真机刷写结果待后台验证`, 'success')
    progressCallback?.({ ...result, phase: 'TRANSMITTED' })
    return result
  } catch (error) {
    const cancelled = error?.code === 'FIRMWARE_UPGRADE_CANCELLED'
    const state = cancelled ? 'CANCELLED' : 'FAILED'
    const errorMsg = String(error?.message || error).slice(0, 500)
    control.phase = state
    updateFirmwareUpgradeStatus(state, { errorMsg, hardwareVerified: false })
    await persist(state, {
      progress: Number(getFirmwareUpgrade()?.progress || 0),
      errorMsg,
      hardwareVerified: false,
      rawError: { code: error?.code || '', message: errorMsg }
    })
    await reportFirmwareUpgradeStatus(state, {
      firmwareVersion,
      progress: Number(getFirmwareUpgrade()?.progress || 0),
      errorMsg
    }).catch((reportError) => console.warn('[upgrade] 失败状态上报失败:', reportError))
    setGlobalNotice(cancelled ? `固件 v${firmwareVersion} 已取消` : `固件升级失败：${errorMsg}`, cancelled ? 'info' : 'error')
    throw error
  } finally {
    unsubscribe?.()
  }
}

async function handleFirmwareUpgradeCommand(message) {
  const { data = {}, msgId } = message
  const actualMsgId = String(msgId || '').trim()
  if (!actualMsgId) throw new Error('firmwareUpgrade missing msgId')

  await initializeLocalStore()
  const responseEventId = mqttResponseEventId('firmwareUpgradeResp', actualMsgId)
  const existing = await localStore.getOutboxEvent(responseEventId)
  if (existing?.payload?.data && existing.state !== 'PROCESSING') {
    return sendMqttResponseWithOutbox('firmwareUpgradeResp', existing.payload.data, actualMsgId)
  }
  const operationId = firmwareOperationId(actualMsgId)
  if (existing?.state === 'PROCESSING') {
    if (activeFirmwareUpgrade?.operationId === operationId) return activeFirmwareUpgrade.promise
    return sendMqttResponseWithOutbox('firmwareUpgradeResp', {
      code: 500,
      msg: '上次固件升级因客户端重启中断，未自动重放'
    }, actualMsgId)
  }

  const firmwareVersion = String(data.firmwareVersion || '').trim()
  const downloadUrl = String(data.downloadUrl || '').trim()
  if (!firmwareVersion || !downloadUrl) {
    return sendMqttResponseWithOutbox('firmwareUpgradeResp', {
      code: 400,
      msg: !firmwareVersion ? '缺少 firmwareVersion 参数' : '缺少 downloadUrl 参数'
    }, actualMsgId)
  }
  if (activeFirmwareUpgrade) {
    return sendMqttResponseWithOutbox('firmwareUpgradeResp', {
      code: 409,
      msg: '已有固件升级正在执行'
    }, actualMsgId)
  }

  const pending = setFirmwareUpgrade({
    operationId,
    requestMsgId: actualMsgId,
    firmwareVersion,
    downloadUrl,
    status: 'PENDING',
    progress: 0,
    hardwareVerified: false
  })
  await saveMqttResponseOutbox('firmwareUpgradeResp', actualMsgId, null, 'PROCESSING')
  setGlobalNotice(`收到固件升级指令：v${firmwareVersion}`, 'info')

  const control = { operationId, phase: 'PENDING', cancelRequested: false, promise: null }
  const promise = executeFirmwareUpgrade(pending, null, control)
  control.promise = promise
  activeFirmwareUpgrade = control
  try {
    await promise
    const response = await sendMqttResponseWithOutbox('firmwareUpgradeResp', {
      code: 0,
      msg: '固件已写入串口传输通道，真机刷写结果待验证'
    }, actualMsgId)
    // 传输完成后自动调度应用重启，5秒延迟确保 MQTT 响应已发送到服务端
    scheduleAppRestart({
      operationId: `firmwareRestart:${actualMsgId}`,
      delayMs: 5000
    }).catch((err) => {
      console.warn('[firmware] auto restart schedule failed:', err)
    })
    return response
  } catch (error) {
    return await sendMqttResponseWithOutbox('firmwareUpgradeResp', firmwareErrorData(error), actualMsgId)
  } finally {
    if (activeFirmwareUpgrade === control) activeFirmwareUpgrade = null
  }
}

async function handleCancelUpgradeCommand(message) {
  const actualMsgId = String(message?.msgId || '').trim()
  if (!actualMsgId) throw new Error('cancelUpgrade missing msgId')
  await initializeLocalStore()
  const existing = await localStore.getOutboxEvent(mqttResponseEventId('cancelUpgradeResp', actualMsgId))
  if (existing?.payload?.data && existing.state !== 'PROCESSING') {
    return sendMqttResponseWithOutbox('cancelUpgradeResp', existing.payload.data, actualMsgId)
  }
  await saveMqttResponseOutbox('cancelUpgradeResp', actualMsgId, null, 'PROCESSING')

  const control = activeFirmwareUpgrade
  if (!control) {
    return sendMqttResponseWithOutbox('cancelUpgradeResp', {
      code: 0,
      msg: '当前没有正在执行的固件升级'
    }, actualMsgId)
  }

  if (['TRANSMITTED', 'CANCELLED', 'FAILED'].includes(control.phase)) {
    return sendMqttResponseWithOutbox('cancelUpgradeResp', {
      code: 409,
      msg: '固件升级已结束，不能再取消'
    }, actualMsgId)
  }
  control.cancelRequested = true
  if (['ENABLING', 'TRANSMITTING'].includes(control.phase)) {
    const nativeResult = await nativeBridge.request('serial.cancelFirmwareUpgrade', {}, 10000)
    if (nativeResult?.accepted !== true) {
      control.cancelRequested = false
      return sendMqttResponseWithOutbox('cancelUpgradeResp', {
        code: 409,
        msg: '固件传输已进入完成阶段，取消未被接受'
      }, actualMsgId)
    }
  }
  setGlobalNotice('固件升级取消请求已接受', 'info')
  return sendMqttResponseWithOutbox('cancelUpgradeResp', {
    code: 0,
    msg: '取消请求已接受'
  }, actualMsgId)
}

const handleSelfCheckCommand = createSelfCheckCommandWorkflow({
  findResponse: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttResponseEventId('deviceSelfCheckResp', msgId))
  },
  markProcessing: (msgId) => saveMqttResponseOutbox('deviceSelfCheckResp', msgId, null, 'PROCESSING'),
  runSelfCheck: runClientSelfCheck,
  reportSelfCheck,
  recordResult: ({ msgId, report, delivery, state, error }) => recordOperationHistory({
    operationId: `deviceSelfCheck:${msgId}`,
    operationType: 'DEVICE_SELF_CHECK',
    operatorName: '后台',
    state,
    requestMsgId: msgId,
    reportResult: report?.result || '',
    reportPending: delivery?.queued === true,
    checkDetails: report?.details || [],
    rawError: error ? { message: error?.message || String(error) } : null
  }),
  sendResponse: (data, msgId) => sendMqttResponseWithOutbox('deviceSelfCheckResp', data, msgId)
})

const mqttCommandStateEventId = (msgId) => `mqtt-command:${msgId}`

const handleLogUploadPolicyCommand = createLogUploadPolicyWorkflow({
  findProcessed: async (msgId) => {
    await initializeLocalStore()
    return localStore.getOutboxEvent(mqttCommandStateEventId(msgId))
  },
  loadPolicy: async () => {
    await initializeLocalStore()
    return localStore.loadLogUploadPolicy()
  },
  savePolicy: async (policy) => {
    await initializeLocalStore()
    return localStore.saveLogUploadPolicy(policy)
  },
  markProcessed: async (policy) => {
    await initializeLocalStore()
    return localStore.upsertOutboxEvent({
      eventId: mqttCommandStateEventId(policy.msgId),
      eventType: MQTT_COMMAND_STATE_TYPE,
      payload: policy,
      state: 'SENT',
      ackedAt: policy.updatedAt
    })
  }
})

function handleMqttBusinessMessage(message = {}) {
  const cmd = message?.cmd
  if (MQTT_UPSTREAM_RESPONSE_COMMANDS.has(cmd)) {
    resolveMqttBusinessResponse(message)
    return
  }
  if (cmd === 'syncUser') {
    handleSyncUserCommand(message).catch((error) => {
      console.warn('[mqtt] syncUser handler failed:', error)
      setGlobalNotice(`员工数据同步失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'syncConfig') {
    handleSyncConfigCommand(message).catch((error) => {
      console.warn('[mqtt] syncConfig handler failed:', error)
      setGlobalNotice(`配置同步失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (Object.prototype.hasOwnProperty.call(LOG_UPLOAD_COMMANDS, cmd)) {
    handleLogUploadPolicyCommand(message).then(async (result) => {
      // 原生开关属于进程内状态；重复下行或重启后也必须再次同步，不能只看 SQLite 是否变化。
      if (typeof result?.enabled === 'boolean') {
        await applyLogUploadNativePolicy(result.enabled)
      }
    }).catch((error) => {
      console.warn(`[mqtt] ${cmd} policy handler failed:`, error)
      setGlobalNotice(`日志上传策略处理失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'deviceSelfCheck') {
    handleSelfCheckCommand(message).catch((error) => {
      console.warn('[mqtt] deviceSelfCheck handler failed:', error)
      setGlobalNotice(`设备自检命令失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'restartApp') {
    handleRestartAppCommand(message).catch((error) => {
      console.warn('[mqtt] restartApp handler failed:', error)
      setGlobalNotice(`重启指令失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'remoteEjectAll') {
    handleRemoteEjectAllCommand(message)
      .then((result) => {
        const type = Number(result?.data?.code) === 0 ? 'success' : 'error'
        setGlobalNotice('远程一键弹卡：' + String(result?.data?.msg || '处理完成'), type)
      })
      .catch((error) => {
        console.warn('[mqtt] remoteEjectAll handler failed:', error)
        setGlobalNotice('远程一键弹卡失败: ' + String(error?.message || error).slice(0, 200), 'error')
      })
    return
  }
  if (cmd === 'faceChanged') {
    triggerFaceIncrementalSync('mqtt:faceChanged').catch((error) => {
      console.warn('[mqtt] faceChanged incremental sync failed:', error)
      setGlobalNotice(`人脸数据同步失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'remoteOpen') {
    handleRemoteOpenCommand(message)
      .catch((error) => {
        console.warn('[mqtt] remoteOpen handler failed:', error)
      })
    return
  }
  if (cmd === 'firmwareUpgrade') {
    handleFirmwareUpgradeCommand(message).catch((error) => {
      console.warn('[mqtt] firmwareUpgrade handler failed:', error)
      setGlobalNotice(`固件升级指令处理失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (cmd === 'cancelUpgrade') {
    handleCancelUpgradeCommand(message).catch((error) => {
      console.warn('[mqtt] cancelUpgrade handler failed:', error)
      setGlobalNotice(`取消固件升级处理失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (Object.prototype.hasOwnProperty.call(UNSUPPORTED_DEVICE_COMMANDS, cmd)) {
    handleUnsupportedDeviceCommand(message).catch((error) => {
      console.warn(`[mqtt] ${cmd} failure response handler failed:`, error)
      setGlobalNotice(`设备命令 ${cmd} 处理失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
    return
  }
  if (MQTT_SYNC_RESPONSE_COMMANDS.has(cmd)) {
    console.log('[mqtt] sync response received:', cmd, 'msgId:', message?.msgId || '')
    if (cmd === 'syncFaceDataResp') {
      handleMqttSyncFaceDataResp(message).catch((error) => {
        console.warn('[mqtt] syncFaceDataResp handler failed:', error)
        setGlobalNotice(`人脸同步数据处理失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
      })
    }
  }
}

function scheduleMqttBusinessHandlerRegistrationRetry(reason = 'retry') {
  if (mqttBusinessHandlersRegistered || mqttBusinessRegistrationPromise || mqttBusinessRegistrationRetryTimer) {
    return { scheduled: true, reason, reused: true }
  }
  const delayIndex = Math.min(mqttBusinessRegistrationRetryCount, MQTT_BUSINESS_REGISTER_RETRY_DELAYS.length - 1)
  const dueIn = MQTT_BUSINESS_REGISTER_RETRY_DELAYS[delayIndex]
  mqttBusinessRegistrationRetryCount += 1
  mqttBusinessRegistrationRetryTimer = setTimeout(() => {
    mqttBusinessRegistrationRetryTimer = null
    registerMqttBusinessHandlers({ reason: `retry:${reason}` }).catch((error) => {
      console.warn('[mqtt] register business handlers retry failed:', error)
      setGlobalNotice(`MQTT 业务注册失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
    })
  }, dueIn)
  return { scheduled: true, reason, dueIn }
}

async function registerMqttBusinessHandlersOnce(options = {}) {
  if (mqttBusinessHandlersRegistered) {
    return { registered: true, commands: MQTT_BUSINESS_COMMANDS, reused: true }
  }
  const channelReady = await nativeBridge.waitForChannel(5000)
  if (!channelReady) {
    throw new Error('Android MQTT bridge channel is not ready')
  }
  const commands = [...MQTT_BUSINESS_COMMANDS, ...MQTT_UPSTREAM_RESPONSE_COMMANDS]
  const results = await Promise.allSettled(commands.map((cmd) => mqttRegisterCmd(cmd)))
  const failed = results
    .map((result, index) => ({ result, cmd: commands[index] }))
    .filter((item) => item.result.status === 'rejected')
  if (failed.length) {
    const error = new Error(`MQTT 指令注册失败: ${failed.map((item) => item.cmd).join(', ')}`)
    error.failed = failed
    throw error
  }
  mqttBusinessUnsubscribe = on('mqtt.message', handleMqttBusinessMessage)
  mqttBusinessHandlersRegistered = true
  mqttBusinessRegistrationRetryCount = 0
  flushPendingMqttResponses('handlers-registered').catch((error) => {
    console.warn('[mqtt] flush pending responses after register failed:', error)
  })
  recoverPendingOutboxAfterConnect('handlers-registered').catch((error) => {
    console.warn('[outbox] startup recovery after register failed:', error)
  })
  return { registered: true, commands: MQTT_BUSINESS_COMMANDS, reason: options.reason || 'manual' }
}

async function registerMqttBusinessHandlers(options = {}) {
  if (mqttBusinessHandlersRegistered) {
    return { registered: true, commands: MQTT_BUSINESS_COMMANDS, reused: true }
  }
  if (mqttBusinessRegistrationPromise) {
    return mqttBusinessRegistrationPromise
  }
  if (mqttBusinessRegistrationRetryTimer) {
    clearTimeout(mqttBusinessRegistrationRetryTimer)
    mqttBusinessRegistrationRetryTimer = null
  }
  mqttBusinessRegistrationPromise = registerMqttBusinessHandlersOnce(options)
  try {
    return await mqttBusinessRegistrationPromise
  } catch (error) {
    mqttBusinessHandlersRegistered = false
    if (options.retry !== false) scheduleMqttBusinessHandlerRegistrationRetry(options.reason || 'register')
    throw error
  } finally {
    mqttBusinessRegistrationPromise = null
  }
}

/**
 * 启动/重连后统一冲刷积压的离线事件（状态报告/卡事件/诊断）
 * 设计为首次连接时执行一次，后续重连由各事件独立的调度器处理。
 */
async function recoverPendingOutboxAfterConnect(reason = 'startup') {
  if (startupOutboxRecovered) return { skipped: true, reason }
  startupOutboxRecovered = true
  const results = { reason }

  // 回调延迟，让 MQTT 连接稳定
  await new Promise((resolve) => setTimeout(resolve, 2000))

  // 状态报告（非阻塞）
  try {
    results.statusReport = await flushPendingStatusReports(reason)
  } catch (e) {
    results.statusReport = { error: String(e.message) }
  }

  // 卡事件（非阻塞）
  try {
    results.cardEvents = await flushPendingCardEvents(20, reason)
  } catch (e) {
    results.cardEvents = { error: String(e.message) }
  }

  // 诊断事件（非阻塞）
  try {
    results.diagnostics = await flushPendingDiagnosticEvents(reason, 20)
  } catch (e) {
    results.diagnostics = { error: String(e.message) }
  }

  return { recovered: true, ...results }
}

const toFiniteNumber = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

const toInteger = (value) => {
  const number = Number(value)
  return Number.isInteger(number) ? number : null
}

const normalizeStatusReportSlot = (slot = {}) => {
  const slotId = toInteger(slot.slotNumber ?? slot.slotId ?? slot.address)
  if (!slotId || slotId < 1) return null
  const rawStatus = String(slot.status || slot.chargingStatus || '').toUpperCase()
  const status = STATUS_REPORT_STATUS_MAP[rawStatus]
  if (!status) return null
  const item = { slotId, status }
  const cardNo = String(slot.cardNo || slot.cardNumber || slot.cardId || '').trim()
  if (status === 'OCCUPIED' && !cardNo) {
    console.warn('[statusReport] occupied slot skipped because cardNo is missing:', slotId)
    return null
  }
  if (cardNo) item.cardNo = cardNo
  const voltage = toFiniteNumber(slot.voltage)
  if (voltage != null) item.voltage = voltage
  const current = toFiniteNumber(slot.current)
  if (current != null) item.current = current
  const chargeStatus = String(slot.chargeStatus || '').toUpperCase()
  if (STATUS_REPORT_CHARGE_STATES.has(chargeStatus)) item.chargeStatus = chargeStatus
  const faultCode = toInteger(slot.faultCode)
  if (faultCode != null) item.faultCode = faultCode
  return item
}

async function buildStatusReportPayload(slotOverrides = []) {
  await initializeLocalStore()
  const projected = Array.isArray(appState.slots) && appState.slots.length > 0
    ? appState.slots
    : []
  const source = projected.length > 0
    ? projected
    : await localStore.loadSlotsSnapshot().catch(() => [])
  const slotsById = new Map()
  const mergeSlot = (slot) => {
    const slotId = toInteger(slot?.slotNumber ?? slot?.slotId ?? slot?.address)
    if (slotId && slotId > 0) slotsById.set(slotId, slot)
  }
  source.forEach(mergeSlot)
  ;(Array.isArray(slotOverrides) ? slotOverrides : []).forEach(mergeSlot)
  const slots = [...slotsById.values()]
    .map((slot) => normalizeStatusReportSlot(slot))
    .filter(Boolean)
  return { slots }
}

const statusReportWorkflow = createStatusReportWorkflow({
  saveEvent: (event) => localStore.upsertOutboxEvent(event),
  listDueEvents: (eventType, limit) => localStore.listDueOutboxEvents(eventType, limit),
  markSent: (eventId) => localStore.markOutboxEventSent(eventId),
  markFailed: (eventId, error, retryDelayMs) => localStore.markOutboxEventFailed(eventId, error, retryDelayMs),
  getCommunicationMode: () => appState.settings.communicationMode || appState.settings.backendTransport,
  isMqttConnected: async () => {
    const status = await mqttLoginStatus().catch(() => ({ connected: false }))
    return isMqttBusinessReady(status)
  },
  sendMqttAndWaitForAck: async (data, msgId) => {
    await registerMqttBusinessHandlers({ reason: 'status-report' })
    return sendMqttAndWaitForResponse('statusReport', data, 'statusReportResp', msgId)
  },
  sendHttp: (data) => httpPost('/api/v1/device/status', data),
  validateMqttAck: (result) => assertBackendSuccess(result?.response?.data, 'statusReport', { requireCode: true }),
  validateHttpAck: (result) => {
    assertHttpSuccess(result, 'statusReport')
    assertBackendSuccess(unwrapResponsePayload(result), 'statusReport', { requireCode: true })
  },
  retryDelayMs: STATUS_REPORT_RETRY_DELAY_MS
})

const diagnosticDeliveryWorkflow = createDiagnosticDeliveryWorkflow({
  saveEvent: (event) => localStore.upsertOutboxEvent(event),
  listDueEvents: (eventType, limit) => localStore.listDueOutboxEvents(eventType, limit),
  markSent: (eventId) => localStore.markOutboxEventSent(eventId),
  markFailed: (eventId, error, retryDelayMs) => localStore.markOutboxEventFailed(eventId, error, retryDelayMs),
  getCommunicationMode: () => appState.settings.communicationMode || appState.settings.backendTransport,
  isMqttConnected: async () => {
    const status = await mqttLoginStatus().catch(() => ({ connected: false }))
    return isMqttBusinessReady(status)
  },
  sendMqttAndWaitForAck: async (eventType, data, msgId, definition) => {
    await registerMqttBusinessHandlers({ reason: `diagnostic:${eventType}` })
    return sendMqttAndWaitForResponse(definition.mqttCmd, data, definition.responseCmd, msgId)
  },
  sendHttp: (eventType, data) => {
    if (eventType === DIAGNOSTIC_OUTBOX_TYPES.SELF_CHECK_REPORT) {
      return httpPost('/api/v1/device/selfcheck', data)
    }
    return httpPost('/api/v1/fault/report', data)
  },
  validateMqttAck: (result, eventType) => {
    assertBackendSuccess(result?.response?.data, eventType, { requireCode: true })
  },
  validateHttpAck: (result, eventType) => {
    assertHttpSuccess(result, eventType)
    assertBackendSuccess(unwrapResponsePayload(result), eventType, { requireCode: true })
  },
  retryDelayMs: DIAGNOSTIC_RETRY_DELAY_MS
})

function schedulePendingDiagnosticEventFlush(reason = 'retry') {
  if (diagnosticFlushTimer) return { scheduled: true, reason, reused: true }
  diagnosticFlushTimer = setTimeout(() => {
    diagnosticFlushTimer = null
    flushPendingDiagnosticEvents('scheduled').catch((error) => {
      console.warn('[diagnostic] flush failed:', error)
    })
  }, DIAGNOSTIC_RETRY_DELAY_MS)
  return { scheduled: true, reason, dueIn: DIAGNOSTIC_RETRY_DELAY_MS }
}

async function flushPendingDiagnosticEvents(reason = 'manual', limit = 20) {
  if (diagnosticFlushPromise) return diagnosticFlushPromise
  diagnosticFlushPromise = diagnosticDeliveryWorkflow.flush(reason, limit)
  try {
    const result = await diagnosticFlushPromise
    if (result.failed > 0) schedulePendingDiagnosticEventFlush('flush-failed')
    return result
  } finally {
    diagnosticFlushPromise = null
  }
}

/** 获取 MQTT 发送队列统计信息（调试用） */
function getMqttSendQueueStats() {
  const queue = getMqttSendQueue()
  return queue ? queue.getStats() : null
}

/** 通知 MQTT 发送队列连接已恢复 */
function notifyMqttConnected() {
  const queue = getMqttSendQueue()
  if (queue) queue.notifyConnected()
  // MQTT 恢复后确保通信日志收集已启动
  startMqttCommLogCapture()
}

// ══════════════════════════════════════════════
// MQTT 通信日志捕捉 + 内存缓冲
//  每条通信事件写入管理页内存缓冲，并通过原生 AppLog 按后台开关决定是否上传。
// ══════════════════════════════════════════════

const MQTT_COMM_LOG_MAX = 500
const mqttCommLogBuffer = [] // { seq, type, timestamp, detail }
let mqttCommLogSeq = 0
let _mqttCommLogCaptureActive = false
let _mqttCommLogCleanups = []
let _mqttNativeTrafficSeen = false

// 日志上报自身和高频心跳仅保留在管理页，避免产生回路或无效噪音。
const MQTT_COMM_LOG_NO_UPLOAD_CMDS = new Set([
  'logReport',      // 上行：我们自己发送的日志上报
  'logReportResp',  // 下行：服务端对 logReport 的 ACK（每条 logReport 都对应一条，形成 1:1 噪音）
  'heartbeatResp'   // 防御性：心跳类 ACK，避免类似回路
])

function _mqttCommLogWrite(type, detail, entryTimestamp, skipUpload = false) {
  const ts = entryTimestamp || Date.now()
  if (!skipUpload) {
    nativeBridge.request('diagnostics.log.write', {
      level: type === 'error' || type === 'disconnected' ? 'WARN' : 'INFO',
      tag: 'MqttComm',
      message: `[${type}] ${detail}`
    }).catch(() => { /* 本地管理页缓冲仍保留，不让日志影响业务通信 */ })
  }

  if (mqttCommLogBuffer.length >= MQTT_COMM_LOG_MAX) {
    mqttCommLogBuffer.shift()
  }
  mqttCommLogBuffer.push({
    seq: ++mqttCommLogSeq,
    type,
    timestamp: ts,
    detail
  })
}

function startMqttCommLogCapture() {
  if (_mqttCommLogCaptureActive) return
  _mqttCommLogCaptureActive = true

  _mqttCommLogCleanups.push(on('mqtt.connected', (data) => {
    _mqttCommLogWrite('connected',
      `通信服务已连接${data?.broker ? ' (' + data.broker + ')' : ''}`,
      data?.timestamp)
  }))

  _mqttCommLogCleanups.push(on('mqtt.transportConnected', (data) => {
    _mqttCommLogWrite('transport_connected', '通信通道已连接，正在等待服务认证', data?.timestamp)
  }))

  _mqttCommLogCleanups.push(on('mqtt.disconnected', (data) => {
    _mqttCommLogWrite('disconnected',
      `通信服务已断开${data?.broker ? ' (' + data.broker + ')' : ''}`,
      data?.timestamp)
  }))

  _mqttCommLogCleanups.push(on('mqtt.traffic', (data) => {
    _mqttNativeTrafficSeen = true
    const cmd = data?.cmd || 'unparsed'
    const msgId = data?.msgId || ''
    const payloadSize = Math.max(0, Number(data?.payloadSize || 0))
    const isSent = String(data?.direction || '').toUpperCase() === 'TX'
    _mqttCommLogWrite(
      isSent ? 'message_tx' : 'message_rx',
      `${isSent ? '发送' : '收到'}: 指令 ${cmd}${msgId ? ' · 编号 ' + msgId : ''}${payloadSize ? ' · ' + payloadSize + ' 字节' : ''}`,
      data?.timestamp,
      MQTT_COMM_LOG_NO_UPLOAD_CMDS.has(cmd)
    )
  }))

  _mqttCommLogCleanups.push(on('mqtt.message', (data) => {
    if (_mqttNativeTrafficSeen) return
    const cmd = data?.cmd || 'unknown'
    const msgId = data?.msgId || ''
    // 已知的“日志上报回路” cmd 跳过上报，但仍写入 UI 缓冲
    _mqttCommLogWrite('message_rx',
      `收到: cmd=${cmd}${msgId ? ' msgId=' + msgId : ''}`,
      data?.timestamp,
      MQTT_COMM_LOG_NO_UPLOAD_CMDS.has(cmd))
  }))
}

function stopMqttCommLogCapture() {
  _mqttCommLogCleanups.forEach(fn => { try { fn() } catch (e) {} })
  _mqttCommLogCleanups = []
  _mqttCommLogCaptureActive = false
}

function getMqttCommLogs(limit = 200) {
  const start = Math.max(0, mqttCommLogBuffer.length - limit)
  return mqttCommLogBuffer.slice(start).reverse()
}

function clearMqttCommLogs() {
  assertLocalPermission('realtime.communication.clear-log')
  mqttCommLogBuffer.length = 0
}

async function applyLogUploadNativePolicy(enabled) {
  try {
    await nativeBridge.request('diagnostics.log.setUploadEnabled', { enabled: enabled === true })
    console.log('[app-log] MQTT upload', enabled ? 'enabled' : 'disabled')
  } catch (error) {
    console.warn('[app-log] native policy apply failed:', error?.message ?? error)
  }
}

async function restoreLogUploadPolicyOnStartup() {
  try {
    const channelReady = await nativeBridge.waitForChannel(5000)
    if (!channelReady) throw new Error('Android diagnostics bridge channel is not ready')
    await initializeLocalStore()
    const policy = await localStore.loadLogUploadPolicy()
    await applyLogUploadNativePolicy(policy?.enabled === true)
  } catch (error) {
    console.warn('[app-log] startup policy restore failed:', error?.message ?? error)
  }
}

async function reportHardwareFault(fault) {
  const result = await diagnosticDeliveryWorkflow.reportHardwareFault(fault)
  if (result.queued) schedulePendingDiagnosticEventFlush('hardware-fault-report-failed')
  return result
}

async function reportSelfCheck(report) {
  const result = await diagnosticDeliveryWorkflow.reportSelfCheck(report)
  if (result.queued) schedulePendingDiagnosticEventFlush('self-check-report-failed')
  return result
}

const selfCheckDetail = (name, passed, errorMsg = '') => ({
  name,
  status: passed ? 'pass' : 'fail',
  ...(!passed && errorMsg ? { errorMsg } : {})
})

async function runClientSelfCheck() {
  const checks = await Promise.all([
    (async () => {
      try {
        await initializeLocalStore()
        await localStore.loadSlotsSnapshot()
        return selfCheckDetail('本机 SQLite', true)
      } catch (error) {
        return selfCheckDetail('本机 SQLite', false, error?.message || 'SQLite 访问失败')
      }
    })(),
    (async () => {
      try {
        const info = unwrapResponsePayload(await bootstrapDeviceInfo()) || {}
        const passed = info.activated === true && Boolean(String(info.deviceCode || '').trim())
        return selfCheckDetail('设备激活', passed, passed ? '' : '设备未激活或缺少设备编码')
      } catch (error) {
        return selfCheckDetail('设备激活', false, error?.message || '设备状态读取失败')
      }
    })(),
    (async () => {
      try {
        const status = await mqttLoginStatus()
        return selfCheckDetail('MQTT 通信', isMqttBusinessReady(status), 'MQTT 尚未完成服务器登录')
      } catch (error) {
        return selfCheckDetail('MQTT 通信', false, error?.message || 'MQTT 状态读取失败')
      }
    })(),
    (async () => {
      try {
        const status = await getSerialStatus()
        const state = String(status?.state || '').trim().toUpperCase()
        const passed = ['CONNECTED', 'OPEN', 'READY'].includes(state)
        return selfCheckDetail('串口连接', passed, passed ? '' : (status?.message || '原生未返回串口已连接状态'))
      } catch (error) {
        return selfCheckDetail('串口连接', false, error?.message || '串口状态读取失败')
      }
    })(),
    (async () => {
      try {
        const result = await faceCount()
        const count = Number(result?.count ?? result)
        return selfCheckDetail('人脸引擎', Number.isInteger(count) && count >= 0, '人脸引擎状态不可用')
      } catch (error) {
        return selfCheckDetail('人脸引擎', false, error?.message || '人脸引擎状态读取失败')
      }
    })()
  ])
  return {
    result: checks.every((item) => item.status === 'pass') ? 'pass' : 'fail',
    details: checks,
    timestamp: Date.now()
  }
}

async function reportSlotHardwareFault(previous, current) {
  const fault = buildHardwareFaultTransition(previous, current)
  if (!fault) return { reported: false, reason: 'NO_NEW_DOCUMENTED_HARDWARE_FAULT' }
  const [faultResult] = await Promise.all([
    reportHardwareFault(fault),
    nativeBridge.request('diagnostics.log.write', {
      level: 'ERROR',
      tag: 'HARDWARE_FAULT',
      message: `卡位${fault.slotId}发生硬件故障，故障码${fault.faultCode}`
    }).catch(() => null)
  ])
  return { reported: true, fault, faultResult }
}

function schedulePendingStatusReportFlush(reason = 'retry') {
  if (statusReportFlushTimer) {
    return { scheduled: true, reason, reused: true }
  }
  statusReportFlushTimer = setTimeout(() => {
    statusReportFlushTimer = null
    flushPendingStatusReports(reason).catch((error) => {
      console.warn('[statusReport] flush failed:', error)
    })
  }, STATUS_REPORT_RETRY_DELAY_MS)
  return { scheduled: true, reason, dueIn: STATUS_REPORT_RETRY_DELAY_MS }
}

async function flushPendingStatusReports(reason = 'manual') {
  if (statusReportFlushPromise) return statusReportFlushPromise
  statusReportFlushPromise = statusReportWorkflow.flush(reason)
  try {
    const result = await statusReportFlushPromise
    if (result.failed > 0) schedulePendingStatusReportFlush('flush-failed')
    return result
  } catch (error) {
    schedulePendingStatusReportFlush('flush-error')
    throw error
  } finally {
    statusReportFlushPromise = null
  }
}

async function reportDeviceStatus(options = {}) {
  const payload = await buildStatusReportPayload(options.slotOverrides)
  if (!payload.slots.length) {
    return { sent: false, reason: 'NO_REPORTABLE_SLOTS', payload }
  }
  const result = await statusReportWorkflow.report(payload)
  lastStatusReportAt = Date.now()
  if (result.queued) schedulePendingStatusReportFlush('report-failed')
  return result
}

async function reportDeviceStatusImmediately(reason = 'take-card', observedSlot = null) {
  if (statusReportTimer) {
    clearTimeout(statusReportTimer)
    statusReportTimer = null
  }
  try {
    const result = await reportDeviceStatus({
      slotOverrides: observedSlot ? [observedSlot] : []
    })
    return { ...result, trigger: reason }
  } catch (error) {
    console.warn(`[statusReport] immediate report failed after ${reason}:`, error)
    return {
      sent: false,
      queued: false,
      trigger: reason,
      error: error?.message || String(error)
    }
  }
}

function scheduleStatusReport(reason = 'slot.status') {
  if (statusReportTimer) return { scheduled: true, reason, reused: true }
  const configuredSeconds = Number(appState.settings.mqttStatusReportInterval ?? 300)
  const intervalSeconds = Number.isFinite(configuredSeconds) && configuredSeconds > 0 ? configuredSeconds : 300
  // 配置保存秒值，仅在调度边界换算为毫秒。
  const interval = intervalSeconds * 1000
  const dueIn = Math.max(0, interval - (Date.now() - lastStatusReportAt))
  statusReportTimer = setTimeout(() => {
    statusReportTimer = null
    if (!statusReportInFlight) {
      statusReportInFlight = reportDeviceStatus()
        .catch((error) => {
          console.warn('[statusReport] failed:', error)
          return { sent: false, error: error?.message || String(error) }
        })
        .finally(() => { statusReportInFlight = null })
    }
  }, dueIn)
  return { scheduled: true, reason, dueIn }
}

// 串口
async function serialSend(hex) {
  return nativeBridge.request('serial.send', { hex })
}
function speakTts(text, { flush = true } = {}) {
  const normalizedText = String(text || '').trim()
  if (!normalizedText) return false
  try {
    nativeBridge.requestAsync('tts.speak', { text: normalizedText, flush: flush !== false })
    return true
  } catch (error) {
    console.warn('[tts] native speech request failed:', error?.message || error)
    return false
  }
}
function announceTakeCardSuccess(slotNumber) {
  return speakTts(buildTakeCardSuccessPrompt(slotNumber))
}
function announceTakeCardFailure() {
  return speakTts(buildTakeCardFailurePrompt())
}
function announceAdminCardOpened(slotNumber, { flush = true } = {}) {
  return speakTts(buildAdminCardOpenedPrompt(slotNumber), { flush })
}
function announceAdminWelcome(adminName) {
  return speakTts(buildAdminWelcomePrompt(adminName))
}
async function serialOpenDoor(slotNumber, administrator = true) {
  return nativeBridge.request('serial.openDoor', { slotNumber, administrator })
}
async function serialQuerySlot(slotNumber) {
  return nativeBridge.request('serial.querySlot', { slotNumber })
}
async function serialStatus() {
  return nativeBridge.request('serial.status')
}
async function serialSlotsSnapshot() {
  return nativeBridge.request('serial.slotsSnapshot')
}
function slotsFromSerialSnapshot(snapshot) {
  const slots = Array.isArray(snapshot) ? snapshot : snapshot?.slots
  if (Array.isArray(slots)) return slots
  throw operationError('SERIAL_SNAPSHOT_UNAVAILABLE', '当前无法读取卡柜实时状态，请稍后重试')
}
async function serialGetLogs(count = 100) {
  return nativeBridge.request('serial.getLogs', { count })
}
async function serialSubscribe(cmd) {
  return nativeBridge.request('serial.subscribe', { cmd })
}
async function serialUnsubscribe(cmd) {
  return nativeBridge.request('serial.unsubscribe', { cmd })
}

// SQLite
async function storageQuery(sql, params = []) {
  return nativeBridge.request('storage.query', { sql, params })
}
async function storageExecute(sql, params = []) {
  return nativeBridge.request('storage.execute', { sql, params })
}

const localStore = createLocalStore({
  query: storageQuery,
  execute: storageExecute
})

let startupSessionCleared = false
let startupSessionClearPromise = null

// 人脸
/** 冷启动后主动推送摄像头配置到原生层，避免首次人脸识别时 CameraX Surface 未就绪导致预览黑屏 */
async function syncFaceCameraConfig() {
  try {
    const options = normalizeFaceCameraOptions(appState.settings)
    await nativeBridge.request('face.camera.config', options)
  } catch (e) {
    console.warn('[face] syncFaceCameraConfig failed:', e?.message || e)
  }
}
async function faceRecognitionStart(options = {}) {
  return nativeBridge.request('face.recognition.start', normalizeFaceRuntimeOptions(options))
}
async function faceRecognitionCancel() {
  return nativeBridge.request('face.recognition.cancel')
}
async function faceEnrollmentStart(faceId) {
  const options = normalizeFaceCameraOptions(appState.settings)
  return nativeBridge.request('face.enrollment.start', { faceId, ...options })
}
async function faceEnrollmentCancel() {
  return nativeBridge.request('face.enrollment.cancel')
}
async function faceCount() {
  return nativeBridge.request('face.count')
}
async function faceTemplateImport(payload = {}) {
  return nativeBridge.request('face.template.import', payload, 30000)
}
async function faceTemplateRemove(faceId) {
  return nativeBridge.request('face.template.remove', { faceId }, 10000)
}

// 事件
function on(eventName, callback) {
  const unsub = nativeBridge.on(eventName, callback)
  unsubscribers.push(unsub)
  return unsub
}
function off(eventName, callback) {
  nativeBridge.off(eventName, callback)
}

// ══════════════════════════════════════════════
//  旧业务方法兼容层
// ══════════════════════════════════════════════

/** 启动 bootstrap 流程（仅传 serverUrl，MQTT 等参数由 getConfig 步骤下发） */
function init(bootstrapConfig) {
  nativeBridge.init()
  clearLocalSessionOnStartup().catch((error) => {
    console.warn('clear local session on startup failed:', error)
    setGlobalNotice(`启动清理会话失败: ${String(error?.message || error).slice(0, 200)}`, 'error')
  })
  if (bootstrapConfig) {
    return bootstrap({ serverUrl: bootstrapConfig.serverUrl || '' })
  }
  return Promise.resolve()
}

async function initializeLocalStore() {
  if (appState.localStoreReady) {
    return { schemaVersion: localStore.schemaVersion || 1, reused: true }
  }
  try {
    const result = await localStore.initializeSchema()
    appState.localStoreReady = true
    return result
  } catch (error) {
    const msg = String(error?.message || error).slice(0, 200)
    console.error('[localStore] initializeSchema failed:', msg)
    setGlobalNotice(`本地数据库初始化失败: ${msg}`, 'error')
    throw new Error(`本地数据库初始化失败: ${msg}`)
  }
}

async function clearLocalSessionOnStartup() {
  if (startupSessionCleared) return { success: true, skipped: true }
  if (startupSessionClearPromise) return startupSessionClearPromise
  startupSessionClearPromise = (async () => {
    await initializeLocalStore()
    await localStore.clearLocalSession()
    clearSessionProjection()
    startupSessionCleared = true
    return { success: true }
  })()
  try {
    return await startupSessionClearPromise
  } finally {
    startupSessionClearPromise = null
  }
}

async function cacheInitialAdminPassword(payload = {}, source = 'BOOTSTRAP', { required = false } = {}) {
  const data = payload?.data || payload?.body?.data || payload
  const passwordConfig = extractSystemCredentialPasswordConfig(data)
  const missingLabels = []
  if (!passwordConfig.developer.found) missingLabels.push('开发人员密码')
  if (!passwordConfig.superAdmin.found) missingLabels.push('超级管理员密码')
  if (missingLabels.length > 0) {
    if (!required) return { saved: false, reason: 'SYSTEM_CREDENTIAL_PASSWORD_MISSING' }
    const error = new Error(`后台配置未返回${missingLabels.join('和')}`)
    error.code = 'SYSTEM_CREDENTIAL_PASSWORD_MISSING'
    throw error
  }
  const deviceCode = data?.deviceCode || data?.deviceId || appState.deviceInfo.deviceCode
    || appState.settings.deviceCode || appState.settings.deviceId || ''
  try {
    return await localStore.syncSystemCredentialsFromConfig({
      developerPassword: passwordConfig.developer.password,
      superAdminPassword: passwordConfig.superAdmin.password,
      deviceCode,
      source
    })
  } catch (error) {
    if (!error?.code) error.code = 'SYSTEM_CREDENTIAL_PASSWORD_INVALID'
    throw error
  }
}

async function loadInitialAdminState() {
  await initializeLocalStore()
  return localStore.loadInitialAdminState()
}

const faceWorkflowError = (code, message, data = null) => {
  const error = new Error(message)
  error.code = code
  error.data = data
  return error
}

const faceErrorMessage = (error) => String(error?.message || error || '本机人脸恢复失败').slice(0, 500)
const ORGANIZATION_UNAUTHORIZED_MESSAGE = '设备未授权组织，请联系管理员在后台授权组织。'
const FACE_ENROLLMENT_ORGANIZATION_UNAUTHORIZED_MESSAGE = '未授权组织，暂时无法录入人脸，请联系管理员在后台授权组织。'

async function applyFaceSyncItems(items = [], syncVersion = 0) {
  let saved = 0
  let skipped = 0
  for (const rawItem of items) {
    const item = normalizeFaceSyncItem(rawItem)
    // status='1'（待处理）静默跳过，不进入本地底库
    if (!item) { skipped += 1; continue }
    const { faceId, faceAiId, employeeId } = item
    const existingPhoto = await localStore.getFacePhotoByFaceId(faceId)
    if (!item.enabled) {
      // syncAction=DELETE：只处理本设备已有记录，不存在则静默跳过（不创建禁用记录）
      try {
        await faceTemplateRemove(faceAiId)
      } catch (removeErr) {
        console.warn(`[faceSync] 删除人脸模板失败（可能本机不存在）: faceAiId=${faceAiId}`, removeErr.message || String(removeErr))
      }
      const existingBinding = await localStore.getFaceBindingById(faceId)
      if (existingBinding) {
        const bindingResult = await localStore.upsertFaceBindings([item], syncVersion)
        if (!bindingResult.saved.length) {
          console.warn(`[faceSync] 更新人脸绑定为禁用失败: faceId=${faceId}`)
        }
      }
      if (existingPhoto) {
        await localStore.updateFacePhotoUploadState(faceId, {
          uploadState: FACE_PHOTO_UPLOAD_STATE.DISABLED,
          lastError: ''
        })
      }
      if (existingBinding || existingPhoto) {
        saved += 1
      } else {
        skipped += 1
      }
      continue
    }

    const employee = await localStore.getEmployeeById(employeeId)
    if (!employee) {
      throw faceWorkflowError('FACE_SYNC_EMPLOYEE_MISSING', `服务器人脸数据 ${faceId} 对应员工不存在`)
    }
    if (!employee.enabled) {
      // 停用员工不得新增本机人脸照片或模板；如旧模板尚在则清理，避免被误识别。
      await faceTemplateRemove(faceAiId).catch((removeErr) => {
        console.warn(`[faceSync] 清理停用员工人脸模板失败: faceAiId=${faceAiId}`, removeErr.message || String(removeErr))
      })
      skipped += 1
      continue
    }

    const photoBase64 = String(item.faceImageBase64 || '').trim()
    if (!photoBase64) {
      throw faceWorkflowError('FACE_SYNC_PHOTO_MISSING', `服务器人脸数据 ${faceId} 未返回 faceImageBase64`)
    }
    await faceTemplateImport({
      faceId: faceAiId,
      faceFeature: String(item.faceFeature || '').trim(),
      imageBase64: photoBase64,
      sourceUrl: String(item.faceImage || '').trim()
    })
    const photoResult = await localStore.saveFacePhoto({
      faceId,
      faceAiId,
      employeeId,
      photoBase64,
      mimeType: 'application/octet-stream',
      source: 'SERVER_SYNC',
      serverPath: String(item.faceImage || '').trim(),
      serverUrl: String(item.faceImage || '').trim(),
      fileHash: String(item.fileHash || '').trim().toLowerCase(),
      uploadState: FACE_PHOTO_UPLOAD_STATE.SYNCED,
      syncedAt: Date.now(),
      updatedAt: Number(item.updatedAt || item.updateTime || syncVersion || Date.now())
    })
    if (!photoResult.saved) {
      // 只有服务端明确返回未授权组织时才替换保存失败提示，传输错误仍保留实际失败原因。
      const organization = await getDeviceOrganizationAuthorization().catch(() => null)
      if (organization?.authorized === false) {
        throw faceWorkflowError(
          'ORGANIZATION_NOT_AUTHORIZED',
          FACE_ENROLLMENT_ORGANIZATION_UNAUTHORIZED_MESSAGE
        )
      }
      throw faceWorkflowError(photoResult.reason || 'FACE_PHOTO_SAVE_FAILED', `服务器人脸照片 ${faceId} 保存失败`, photoResult)
    }
    const bindingResult = await localStore.upsertFaceBindings([item], syncVersion)
    if (!bindingResult.saved.length) {
      throw faceWorkflowError('FACE_BINDING_SAVE_FAILED', `人脸绑定 ${faceId} 保存失败`)
    }
    saved += 1
  }
  return { saved, skipped }
}

async function recoverInterruptedFaceEnrollments() {
  await initializeLocalStore()
  const operations = await localStore.listRecoverableOperations(100)
  const interrupted = operations.filter((operation) => operation.operationType === 'FACE_ENROLLMENT')
  for (const operation of interrupted) {
    const temporaryFaceAiId = String(operation.temporaryFaceAiId || '').trim()
    if (temporaryFaceAiId) await faceTemplateRemove(temporaryFaceAiId).catch(() => {})

    if (operation.state === 'SERVER_CREATED') {
      // 后台已建档成功，仅清理可能残留的临时模板
      // 不标记失败，等待 faceBindings 增量同步后由 completeServerCreatedEnrollments 校验恢复
      await localStore.saveOperationRecord({
        operationId: operation.operationId,
        operationType: 'FACE_ENROLLMENT',
        state: 'SERVER_CREATED',
        employeeId: operation.employeeId,
        recoveryPhase: 'PENDING_SYNC',
        recoveryCheckedAt: Date.now()
      })
      continue
    }

    await localStore.saveOperationRecord({
      operationId: operation.operationId,
      operationType: 'FACE_ENROLLMENT',
      state: 'FAILED',
      errorCode: 'FACE_ENROLLMENT_INTERRUPTED',
      errorMessage: '人脸录入被应用重启中断，请重新录入',
      finishedAt: Date.now()
    })
  }
  return { recovered: interrupted.length }
}

/**
 * 处理 MQTT syncFaceDataResp 下行人脸同步数据
 * 文档4.2/4.6节：服务端主动推送完整人脸数据，终端直接落库
 */
async function handleMqttSyncFaceDataResp(message) {
  const data = message?.data
  if (!data || !Array.isArray(data.faceFeatures)) {
    console.warn('[mqtt] syncFaceDataResp missing faceFeatures, msgId:', message?.msgId || '')
    return { processed: 0, skipped: 0 }
  }
  const syncVersion = Number(data.syncVersion || Date.now())
  const result = await applyFaceSyncItems(data.faceFeatures, syncVersion)
  const hasMore = data.hasMore === true
  if (hasMore) {
    // 服务端指示还有更多分页，触发 HTTP 增量拉取补齐
    console.log('[mqtt] syncFaceDataResp hasMore=true, triggering incremental pull')
    triggerFaceIncrementalSync('mqtt:syncFaceDataResp:hasMore').catch((err) => {
      console.warn('[mqtt] incremental pull after syncFaceDataResp failed:', err)
    })
  }
  return { processed: result.saved, skipped: result.skipped }
}

async function triggerFaceIncrementalSync(reason = 'manual') {
  if (faceSyncPromise) return faceSyncPromise
  faceSyncPromise = (async () => ({
    reason,
    result: await syncPagedDataset('faceBindings')
  }))()
  try {
    return await faceSyncPromise
  } finally {
    faceSyncPromise = null
  }
}

async function getRegisteredFaceEmployeeIds() {
  const response = await httpGet(FACE_REGISTERED_EMPLOYEES_PATH)
  assertHttpSuccess(response, '获取已注册人脸员工')
  assertBackendSuccess(response?.body || response, '获取已注册人脸员工', { requireCode: true })
  const data = unwrapResponsePayload(response)
  if (!data || typeof data !== 'object' || Array.isArray(data) || !Array.isArray(data.employeeIds)) {
    throw faceWorkflowError('FACE_REGISTERED_LIST_INVALID', '已注册人脸员工列表响应缺少 employeeIds')
  }
  return [...new Set(data.employeeIds
    .map((employeeId) => String(employeeId || '').trim())
    .filter(Boolean))]
}

async function findMissingRegisteredFaceEmployeeIds(registeredEmployeeIds) {
  const [faceCounts, employees] = await Promise.all([
    localStore.getEmployeeFaceCounts(),
    localStore.loadEmployees({ includeDisabled: true })
  ])
  const employeeById = new Map(employees.map((employee) => [String(employee.employeeId), employee]))
  return registeredEmployeeIds.filter((employeeId) => {
    const employee = employeeById.get(employeeId)
    // 已停用员工不应下载或保存人脸；未知员工仍保留为缺失，以暴露服务端范围不一致。
    if (employee && !employee.enabled) return false
    return Number(faceCounts[employeeId] || 0) <= 0
  })
}

async function reconcileFaceSyncAfterAdminExit() {
  const registeredEmployeeIds = await getRegisteredFaceEmployeeIds()
  const missingBefore = await findMissingRegisteredFaceEmployeeIds(registeredEmployeeIds)
  if (!missingBefore.length) {
    return { registeredEmployeeIds, missingBefore, missingAfter: [], repaired: false, fullSync: null }
  }

  if (faceSyncPromise) await faceSyncPromise
  const fullSyncPromise = syncPagedDataset('faceBindings', { full: true })
  faceSyncPromise = fullSyncPromise
  let fullSync
  try {
    fullSync = await fullSyncPromise
  } finally {
    if (faceSyncPromise === fullSyncPromise) faceSyncPromise = null
  }
  const missingAfter = await findMissingRegisteredFaceEmployeeIds(registeredEmployeeIds)
  if (missingAfter.length) {
    throw faceWorkflowError(
      'FACE_SYNC_RECONCILIATION_INCOMPLETE',
      '后台已登记人脸，但未返回可同步的人脸数据或本机导入未完成',
      { missingEmployeeIds: missingAfter }
    )
  }
  return { registeredEmployeeIds, missingBefore, missingAfter, repaired: true, fullSync }
}

async function triggerAdminExitFaceSync() {
  if (adminExitFaceSyncPromise) return adminExitFaceSyncPromise
  adminExitFaceSyncPromise = (async () => {
    const incremental = await triggerFaceIncrementalSync('ADMIN_EXIT_FACE_SYNC')
    const reconciliation = await reconcileFaceSyncAfterAdminExit()
    return { incremental, reconciliation }
  })()
  try {
    return await adminExitFaceSyncPromise
  } finally {
    adminExitFaceSyncPromise = null
  }
}

function startFaceSyncScheduler() {
  if (faceSyncIntervalTimer) return { started: true, reused: true, intervalMs: FACE_SYNC_INTERVAL_MS }
  faceSyncIntervalTimer = setInterval(() => {
    triggerFaceIncrementalSync('periodic').catch((error) => {
      console.warn('[face] periodic incremental sync failed:', error)
    })
  }, FACE_SYNC_INTERVAL_MS)
  return { started: true, intervalMs: FACE_SYNC_INTERVAL_MS }
}

async function syncPagedDataset(datasetKey, options = {}) {
  const config = SYNC_DATASETS[datasetKey]
  if (!config) throw new Error(`未知同步数据集: ${datasetKey}`)
  await initializeLocalStore()
  const cursor = await localStore.getSyncCursor(config.scope)
  const pageSize = Math.min(
    Number(options.pageSize || config.pageSize),
    config.maxPageSize
  )
  const lastSyncTime = Number(options.full ? 0 : cursor.appliedVersion || 0)
  let page = 0 // 文档4.1/4.6节：页码从0开始（0-based）
  let hasMore = true
  let total = 0
  let saved = 0
  let skipped = 0
  let disabled = 0
  let received = 0
  let lastVersion = lastSyncTime

  while (hasMore) {
    if (page > MAX_SYNC_PAGES) {
      throw new Error(`${config.scope} 同步超过最大页数，已停止以避免循环`)
    }
    const request = {
      lastSyncTime,
      page,
      pageSize,
      ...(config.extraRequest || {})
    }
    const response = await httpPost(config.path, request)
    assertHttpSuccess(response, `${config.scope}同步`)
    const payload = unwrapResponsePayload(response)
    assertBackendSuccess(payload, `${config.scope}同步`)
    assertSyncPayloadShape(payload, config)
    const syncVersion = normalizeSyncVersion(payload.syncVersion, lastVersion)
    const items = Array.isArray(payload[config.listKey]) ? payload[config.listKey] : []
    let pageSaved = 0
    let pageSkipped = 0

    if (datasetKey === 'employees') {
      const result = await localStore.upsertEmployees(items, syncVersion)
      const disabledResult = await localStore.disableEmployees(payload.deletedEmployeeIds || [], syncVersion)
      pageSaved = result.saved.length
      pageSkipped = result.skipped
      disabled += Number(disabledResult.disabled || 0)
    } else if (datasetKey === 'faceBindings') {
      const result = await applyFaceSyncItems(items, syncVersion)
      pageSaved = result.saved
      pageSkipped = result.skipped
    } else if (datasetKey === 'fingerBindings') {
      const result = await localStore.upsertFingerBindings(items, syncVersion)
      pageSaved = result.saved.length
      pageSkipped = result.skipped
    }

    saved += pageSaved
    skipped += pageSkipped
    received += items.length
    total = Number(payload.total || total || 0)
    lastVersion = syncVersion
    const deletedCount = Array.isArray(payload.deletedEmployeeIds) ? payload.deletedEmployeeIds.length : 0
    const pageHasChanges = pageSaved + pageSkipped + deletedCount > 0
    hasMore = payload.hasMore === true
    if (hasMore && !pageHasChanges) {
      throw new Error(`${config.scope} 同步返回空页但仍要求继续，已停止以避免循环`)
    }
    page += 1
  }

  if (total > 0 && received === 0) {
    throw new Error(`${config.scope} 同步返回 total=${total}，但未返回可落库列表数据`)
  }

  await localStore.advanceSyncCursor(config.scope, lastVersion, {
    total,
    received,
    saved,
    skipped,
    disabled,
    pageSize,
    lastSyncTime
  })
  return {
    scope: config.scope,
    total,
    saved,
    skipped,
    disabled,
    syncVersion: lastVersion
  }
}

/**
 * 人脸录入 SERVER_CREATED 状态回收
 * 录入在后台建档成功后、最终模板导入前崩溃时：
 * 后台已有 faceId 和 binding，本机 operation 停留在 SERVER_CREATED。
 * faceBindings 增量同步会重新下载 binding 并导入模板；
 * 本函数在同步完成后校验绑定是否已成功到达本机。
 */
async function completeServerCreatedEnrollments() {
  await initializeLocalStore()
  const operations = await localStore.listRecoverableOperations(100)
  const serverCreated = (operations || []).filter(
    (op) => op.operationType === 'FACE_ENROLLMENT' && op.state === 'SERVER_CREATED'
  )
  if (!serverCreated.length) return { completed: 0, failed: 0 }

  let completed = 0
  let failed = 0

  for (const op of serverCreated) {
    const faceAiId = String(op.faceAiId || op.serverFaceId || '').trim()
    if (!faceAiId) {
      await localStore.saveOperationRecord({
        operationId: op.operationId,
        operationType: 'FACE_ENROLLMENT',
        state: 'FAILED',
        employeeId: op.employeeId,
        errorMessage: '后台建档记录缺少 faceAiId，无法校验同步恢复',
        recoveryPhase: 'SYNC_MISSING',
        recoveredAt: Date.now()
      })
      failed += 1
      continue
    }

    const binding = await localStore.getFaceBindingById(faceAiId).catch(() => null)
    if (binding && binding.enabled !== false) {
      await localStore.saveOperationRecord({
        operationId: op.operationId,
        operationType: 'FACE_ENROLLMENT',
        state: 'COMPLETED',
        employeeId: op.employeeId,
        faceAiId,
        serverFaceId: binding.faceId,
        recoveryPhase: 'SYNCED',
        recoveredAt: Date.now()
      })
      completed += 1
    } else {
      await localStore.saveOperationRecord({
        operationId: op.operationId,
        operationType: 'FACE_ENROLLMENT',
        state: 'FAILED',
        employeeId: op.employeeId,
        faceAiId,
        errorMessage: '增量同步未获取到后台已建档的绑定数据，请稍后重试或重新录入',
        recoveryPhase: 'SYNC_MISSING',
        recoveredAt: Date.now()
      })
      failed += 1
    }
  }

  return { completed, failed }
}

async function syncIdentityData(options = {}) {
  const employeeResult = await syncPagedDataset('employees', options)
  const employeeOnly = options.employeeOnly === true || options.includeBindings === false
  const faceResult = employeeOnly ? null : await syncPagedDataset('faceBindings', options)
  const interruptedEnrollments = employeeOnly ? null : await recoverInterruptedFaceEnrollments().catch((error) => ({
    total: 0,
    failed: [{ code: error?.code || 'FACE_ENROLLMENT_RECOVERY_FAILED', message: faceErrorMessage(error) }]
  }))
  const fingerResult = employeeOnly ? null : await syncPagedDataset('fingerBindings', options)
  const employees = await localStore.loadEmployees()
  replaceEmployeesProjection(employees)
  return {
    employees,
    summary: {
      employees: employeeResult,
      ...(employeeOnly ? {} : { faceBindings: faceResult, fingerBindings: fingerResult, interruptedEnrollments })
    }
  }
}

/** 加载设置（解包后返回 data 对象） */
async function loadSettings(options = {}) {
  await initializeLocalStore()
  const [bootstrapConfig, runtimeConfig, localDraft] = await Promise.all([
    localStore.loadBootstrapConfig().catch(() => null),
    localStore.loadRuntimeConfig().catch(() => null),
    localStore.loadConfigDraft().catch(() => null)
  ])
  // 首次启动下发的当前配置优先保留，再由已持久化运行配置和用户草稿覆盖同名字段。
  const cachedSettings = normalizeDeviceConfig({
    ...(bootstrapConfig || {}),
    ...(appState.settings || {}),
    ...(runtimeConfig || {}),
    ...(localDraft || {})
  })
  if (options.remote === false) {
    replaceSettingsProjection(cachedSettings)
    return cachedSettings
  }
  const fallbackToCache = (reason) => {
    console.warn('loadSettings fallback: using cache, cause:', reason?.code || reason?.message || reason)
    replaceSettingsProjection(cachedSettings)
    const detail = reason?.code === 'TIMEOUT'
      ? '获取配置超时，正在使用本地缓存配置'
      : reason?.code?.startsWith?.('HTTP_')
        ? `配置接口返回错误(${reason.code})，正在使用本地缓存配置`
        : ['SYSTEM_CREDENTIAL_PASSWORD_MISSING', 'SYSTEM_CREDENTIAL_PASSWORD_INVALID'].includes(reason?.code)
          ? '后台管理员或开发人员密码缺失或无效，已保留本机原密码和缓存配置'
        : reason?.code === 'BACKEND_CODE_MISSING'
          ? '配置接口响应格式异常，正在使用本地缓存配置'
          : '无法获取最新配置，正在使用本地缓存配置'
    setGlobalNotice(detail, 'warn')
    return cachedSettings
  }
  try {
    const data = canonicalizeRemoteDeviceConfigLayout(await requestDeviceConfig({ transport: options.transport }))
    await cacheInitialAdminPassword(data, 'CONFIG', { required: true })
    const normalized = normalizeDeviceConfig({
      ...(bootstrapConfig || {}),
      ...(appState.settings || {}),
      ...(runtimeConfig || {}),
      ...(localDraft || {}),
      ...data
    })
    await localStore.saveRuntimeConfig(normalized)
    await localStore.saveConfigDraft(normalized)
    await localStore.saveBootstrapConfig(bootstrapConfigFromSettings(normalized))
    replaceSettingsProjection(normalized)
    return normalized
  } catch (e) {
    return fallbackToCache(e)
  }
}

/** 登录 */
async function login(password) {
  return loginLocal(password)
}

/**
 * 构建审计事件基础字段，从当前 session 读取 actor 信息。
 * 调用方补充 event_type、feature_code、action_code 等业务字段。
 */
function buildAuditEvent(overrides = {}) {
  const session = appState.session
  return {
    session_ref: session ? `${session.credentialId}_${session.loginAt}` : '',
    actor_credential_id: String(session?.credentialId ?? ''),
    actor_label: String(session?.credentialLabel ?? ''),
    role_ids_json: session?.roleIds?.length ? JSON.stringify(session.roleIds) : null,
    source: 'LOCAL_UI',
    ...overrides
  }
}

/** 人员操作无论成功或失败都写入历史，便于追溯服务端与本机结果。 */
async function recordEmployeeHistory(operationType, employee = {}, outcome = {}) {
  const employeeId = String(employee?.employeeId || '').trim()
  const timestamp = Date.now()
  const state = String(outcome?.state || 'COMPLETED').toUpperCase()
  try {
    return await localStore.saveOperationRecord({
      operationId: `employee-history:${operationType}:${employeeId || 'pending'}:${timestamp}:${Math.random().toString(36).slice(2, 8)}`,
      operationType,
      employeeId,
      employeeName: String(employee?.employeeName || '').trim(),
      operatorName: appState.session?.username || appState.session?.credentialLabel || '本机管理员',
      state,
      rawError: outcome?.error ? {
        code: outcome.error?.code || 'EMPLOYEE_OPERATION_FAILED',
        message: outcome.error?.message || String(outcome.error)
      } : undefined,
      createdAt: timestamp,
      updatedAt: timestamp,
      finishedAt: timestamp
    })
  } catch (error) {
    console.warn('[history] 人员操作历史写入失败:', error?.message || error)
    return null
  }
}

/**
 * 记录一条审计事件。自动从 session 填充 actor 信息。
 * 调用方只需传入 event_type、action_code 等业务字段。
 * 写入失败不阻止当前操作，但打印明确警告。
 */
async function recordAuditEvent(event) {
  try {
    await localStore.insertAuditEvent(buildAuditEvent(event))
  } catch (error) {
    console.warn('[audit] 审计写入失败:', error?.message ?? error)
  }
}

/** 退出管理模式后先增量同步；后台登记但本机缺失时，再受控执行一次全量补齐。 */
async function logout() {
  const hasPendingFaceSync = Array.isArray(appState.faceSyncPending) && appState.faceSyncPending.length > 0
  const result = await logoutLocal()
  appState.faceSyncPending = []
  if (!hasPendingFaceSync) return { ...result, faceSyncScheduled: false }
  uni.showToast({ title: '正在后台同步人脸数据', icon: 'none', duration: 2000 })
  triggerAdminExitFaceSync()
    .then(() => uni.showToast({ title: '人脸数据同步完成', icon: 'success', duration: 2000 }))
    .catch((error) => {
      console.warn('[logout] face sync failed:', error)
      const title = error?.code === 'FACE_SYNC_RECONCILIATION_INCOMPLETE'
        ? '后台登记与人脸同步数据不一致'
        : '人脸数据同步失败，请稍后重试'
      uni.showToast({ title, icon: 'none', duration: 3000 })
    })
  return { ...result, faceSyncScheduled: true }
}

async function loginLocal(password) {
  await clearLocalSessionOnStartup()
  await initializeLocalStore()
  const session = await localStore.loginLocal(password)
  const result = replaceSessionProjection(session)
  try {
    recordAuditEvent({
      event_type: 'LOGIN',
      occurred_at: session.loginAt
    })
  } catch (_) {}
  return result
}

async function logoutLocal() {
  await initializeLocalStore()
  try {
    recordAuditEvent({ event_type: 'LOGOUT', occurred_at: Date.now() })
  } catch (_) {}
  await localStore.logoutLocal()
  clearSessionProjection()
  return { success: true }
}

async function refreshLocalSession() {
  const session = appState.session
  if (!session) return { refreshed: false, reason: 'NO_SESSION' }
  const ttlSeconds = Math.max(60, Number(session.ttlSeconds || 3600))
  const expiresAt = Date.now() + ttlSeconds * 1000
  session.expiresAt = expiresAt
  await initializeLocalStore()
  return localStore.refreshLocalSession({ expiresAt, session })
}

function assertLocalPermission(permissionKey) {
  if (!appState.session?.permissions?.has('*') && !appState.session?.permissions?.has(permissionKey)) {
    throw new Error(`权限不足：${permissionKey}`)
  }
}

function assertAnyLocalPermission(permissionKeys = []) {
  const keys = Array.isArray(permissionKeys) ? permissionKeys : [permissionKeys]
  if (!appState.session?.permissions?.has('*') && !keys.some((permissionKey) => appState.session?.permissions?.has(permissionKey))) {
    throw new Error(`权限不足：${keys.filter(Boolean).join(' 或 ')}`)
  }
}

function hasPrivilegedAdminRole() {
  const roleIds = Array.isArray(appState.session?.roleIds)
    ? appState.session.roleIds
    : []
  return roleIds.includes('SUPER_ADMIN') || roleIds.includes('DEVELOPER')
}

function markAdminManageSecondaryVerified() {
  if (appState.session) appState.session.adminManageVerifiedAt = Date.now()
}

function hasAdminManageSecondaryAccess() {
  if (!appState.session) return false
  if (hasPrivilegedAdminRole()) return true
  return Number(appState.session.adminManageVerifiedAt || 0) > 0
}

async function verifyAdminManageAccess(password = '') {
  assertAnyLocalPermission(['account.role.view', 'account.role.create', 'account.role.update', 'account.role.enable', 'account.role.delete', 'account.user.view', 'account.user.create', 'account.user.update', 'account.user.unlock', 'account.user.delete'])
  if (hasPrivilegedAdminRole()) return { verified: true, bypassed: true }
  await initializeLocalStore()
  const result = await localStore.verifySecondaryPassword(password)
  markAdminManageSecondaryVerified()
  return result
}

async function changeSecondaryPassword(oldPassword, newPassword) {
  assertLocalPermission('account.secondary-password.change')
  if (!hasAdminManageSecondaryAccess()) throw new Error('请先完成管理二级密码验证')
  await initializeLocalStore()
  return localStore.changeSecondaryPassword(oldPassword, newPassword)
}

async function changeLocalPassword(oldPassword, newPassword) {
  const session = appState.session
  if (!session) throw new Error('请先登录')
  if (!session.needsPasswordChange && !session.permissions?.has('*') && !session.permissions?.has('account.password.change')) {
    throw new Error('权限不足：account.password.change')
  }
  await initializeLocalStore()
  const result = await localStore.changeLocalCredentialPassword({
    credentialId: session.credentialId,
    oldPassword,
    newPassword
  })
  replaceSessionProjection({
    ...session,
    needsPasswordChange: false
  })
  const refreshed = await refreshLocalSession()
  return { ...result, session: appState.session, expiresAt: refreshed.expiresAt }
}

async function listLocalPermissions() {
  assertLocalPermission('account.role.view')
  await initializeLocalStore()
  return localStore.listLocalPermissions()
}

async function saveLocalPermission(permission) {
  assertLocalPermission('account.role.update')
  await initializeLocalStore()
  return localStore.saveLocalPermission(permission)
}

async function deleteLocalPermission(permissionKey) {
  assertLocalPermission('account.role.update')
  await initializeLocalStore()
  return localStore.deleteLocalPermission(permissionKey)
}

async function listLocalRoles() {
  assertLocalPermission('account.role.view')
  await initializeLocalStore()
  return localStore.listLocalRoles()
}

async function saveLocalRole(role) {
  await initializeLocalStore()
  const exists = (await localStore.listLocalRoles()).some((item) => item.roleId === String(role?.roleId || '').trim())
  assertLocalPermission(exists ? 'account.role.update' : 'account.role.create')
  return localStore.saveLocalRole(role)
}

async function setLocalRoleEnabled(roleId, enabled) {
  assertLocalPermission('account.role.enable')
  await initializeLocalStore()
  return localStore.setLocalRoleEnabled(roleId, enabled)
}

async function deleteLocalRole(roleId) {
  assertLocalPermission('account.role.delete')
  await initializeLocalStore()
  return localStore.deleteLocalRole(roleId)
}

async function listLocalCredentials() {
  assertLocalPermission('account.user.view')
  await initializeLocalStore()
  return localStore.listLocalCredentials()
}

async function saveLocalCredential(credential) {
  await initializeLocalStore()
  const exists = (await localStore.listLocalCredentials()).some((item) => item.credentialId === String(credential?.credentialId || '').trim())
  assertLocalPermission(exists ? 'account.user.update' : 'account.user.create')
  return localStore.saveLocalCredential(credential)
}

async function deleteLocalCredential(credentialId) {
  assertLocalPermission('account.user.delete')
  await initializeLocalStore()
  return localStore.deleteLocalCredential(credentialId)
}

async function unlockLocalCredential(credentialId) {
  assertLocalPermission('account.user.unlock')
  await initializeLocalStore()
  return localStore.unlockLocalCredential(credentialId)
}

function faceError(code, message, data = null) {
  return { code, message, data }
}

function fingerprintError(code, message, data = null) {
  return { code, message, data }
}

const mapFingerprintProgress = (event = {}) => {
  const status = String(event.status || '').toUpperCase()
  const message = event.message || ''
  if (status === 'FAILED_ATTEMPT' || status === 'HELP') {
    return { status: 'RETRY', message }
  }
  if (status === 'WAITING_FOR_TOUCH') {
    return { status: 'COLLECTING', message }
  }
  if (status === 'SUCCESS') {
    return { status: 'MATCHING', message }
  }
  if (status === 'ERROR') {
    return { status: 'ERROR', message }
  }
  return { status: 'PREPARING', message }
}

function waitForFaceEvent({
  successEvent,
  cancelEvent,
  timeoutEvent,
  failureEvent,
  timeoutMs,
  timeoutMessage
}) {
  let settled = false
  let timer = null
  const unsubs = []
  const cleanup = () => {
    if (timer) clearTimeout(timer)
    unsubs.splice(0).forEach((unsub) => {
      try { unsub?.() } catch (error) {}
    })
  }
  const finish = (fn, value) => {
    if (settled) return
    settled = true
    cleanup()
    fn(value)
  }
  const promise = new Promise((resolve, reject) => {
    unsubs.push(on(successEvent, (data) => finish(resolve, data || {})))
    unsubs.push(on(cancelEvent, (data) => finish(reject, faceError('FACE_CANCELLED', '已取消人脸操作', data))))
    if (timeoutEvent) {
      unsubs.push(on(timeoutEvent, (data) => finish(reject, faceError('FACE_TIMEOUT', timeoutMessage, data))))
    }
    if (failureEvent) {
      unsubs.push(on(failureEvent, (data) => finish(
        reject,
        faceError(data?.code || 'FACE_OPERATION_FAILED', data?.message || '人脸操作失败', data)
      )))
    }
    timer = setTimeout(() => {
      finish(reject, faceError('FACE_TIMEOUT', timeoutMessage))
    }, timeoutMs)
  })
  return {
    promise,
    cancel: () => {
      settled = true
      cleanup()
    }
  }
}

async function runFingerprintRecognition(progressCallback) {
  let unsubscribe = null
  const timeoutMs = Math.max(15000, Number(appState.settings.faceRecognitionTimeout || 30000))
  try {
    const status = await nativeBridge.request('fingerprint.getStatus', {}, 5000)
    if (!status?.available) {
      throw fingerprintError('FINGERPRINT_UNAVAILABLE', status?.message || '系统指纹不可用', status)
    }
    if (progressCallback) {
      progressCallback({ status: 'COLLECTING', message: status.message || '请按系统提示验证指纹' })
      unsubscribe = on('fingerprint.statusChanged', (event) => {
        progressCallback(mapFingerprintProgress(event))
      })
    }
    const result = await nativeBridge.request('fingerprint.verify', { operation: 'VERIFY' }, timeoutMs)
    if (!result?.success && result?.status !== 'SYSTEM_AUTHENTICATED') {
      throw fingerprintError('FINGERPRINT_AUTH_FAILED', result?.message || '系统指纹验证未完成', result)
    }
    return {
      ...result,
      accepted: true,
      systemAuthenticated: true,
      canCompleteTake: false,
      closeLoopMessage: '系统指纹已通过，但当前能力只能确认本机用户，不能识别员工身份，取卡闭环未完成。',
      reason: 'EMPLOYEE_FINGERPRINT_NOT_AVAILABLE',
      message: result.message || '系统指纹验证成功'
    }
  } catch (error) {
    throw fingerprintError(error?.code || 'FINGERPRINT_AUTH_FAILED', error?.message || '系统指纹验证未完成', error?.data || error)
  } finally {
    try { unsubscribe?.() } catch (error) {}
  }
}

async function registerFingerprintAuthorization(data = {}, progressCallback) {
  let unsubscribe = null
  const timeoutMs = Math.max(15000, Number(appState.settings.faceRecognitionTimeout || 30000))
  try {
    const status = await nativeBridge.request('fingerprint.getStatus', {}, 5000)
    if (!status?.available) {
      throw fingerprintError('FINGERPRINT_UNAVAILABLE', status?.message || '系统指纹不可用', status)
    }
    if (progressCallback) {
      progressCallback({ status: 'COLLECTING', message: status.message || '请按系统提示确认本机指纹' })
      unsubscribe = on('fingerprint.statusChanged', (event) => {
        progressCallback(mapFingerprintProgress(event))
      })
    }
    const result = await nativeBridge.request('fingerprint.enroll', {
      operation: 'ENROLL',
      employeeId: String(data.employeeId || '').trim(),
      employeeName: String(data.employeeName || '').trim()
    }, timeoutMs)
    if (!result?.success && result?.status !== 'SYSTEM_AUTHENTICATED') {
      throw fingerprintError('FINGERPRINT_AUTH_FAILED', result?.message || '本机系统指纹授权未完成', result)
    }
    return {
      ...result,
      accepted: true,
      systemAuthenticated: true,
      canCompleteEmployeeBinding: false,
      closeLoopMessage: '系统不会向应用提供指纹模板或编号，不能作为员工级指纹绑定闭环。',
      reason: 'EMPLOYEE_FINGERPRINT_NOT_AVAILABLE',
      message: result.message || '本机系统指纹授权成功'
    }
  } catch (error) {
    throw fingerprintError(error?.code || 'FINGERPRINT_AUTH_FAILED', error?.message || '本机系统指纹授权未完成', error?.data || error)
  } finally {
    try { unsubscribe?.() } catch (error) {}
  }
}

/** 人脸/指纹识别 */
async function runRecognition(type, progressCallback) {
  if (type === 'FACE') {
    const options = normalizeFaceCameraOptions(appState.settings)
    if (progressCallback) { progressCallback({ status: 'DETECTING', message: '请正对摄像头' }) }
    let waitResult
    try {
      waitResult = waitForFaceEvent({
        successEvent: 'face.recognized',
        cancelEvent: 'face.recognition.cancelled',
        timeoutEvent: 'face.recognition.timeout',
        timeoutMs: Math.max(5000, Number(options.faceRecognitionTimeout || 30000) + 3000),
        timeoutMessage: '人脸识别超时'
      })
      const res = await faceRecognitionStart(options)
      // 同时处理 { accepted: false }（显式拒绝）和 { error: 'XXX' }（桥返回错误，accepted 缺失）两种失败
      if (!res || !res.accepted) {
        waitResult.cancel()
        return { accepted: false, error: res?.error || 'NOT_ACCEPTED', message: res?.message || '人脸识别启动失败' }
      }
      const data = await waitResult.promise
      const resolved = await localStore.resolveEmployeeByFaceId(data?.faceId)
      if (!resolved.ok) {
        const unregistered = resolved.reason === 'FACE_BINDING_NOT_FOUND'
        if (unregistered) {
          const organization = await getDeviceOrganizationAuthorization()
          if (!organization.authorized) {
            return {
              faceId: data?.faceId || '',
              score: data?.score,
              status: 'ORGANIZATION_UNAUTHORIZED',
              accepted: false,
              reason: 'ORGANIZATION_NOT_AUTHORIZED',
              message: ORGANIZATION_UNAUTHORIZED_MESSAGE
            }
          }
        }
        return {
          faceId: data?.faceId || '',
          score: data?.score,
          status: unregistered ? 'UNREGISTERED' : 'REJECTED',
          accepted: false,
          reason: resolved.reason,
          message: unregistered
            ? '本机未找到该人脸对应的员工'
            : resolved.reason === 'EMPLOYEE_DISABLED'
              ? '员工已停用，不能取卡'
              : '人脸已识别，但本机员工绑定不可用'
        }
      }
      return {
        faceId: resolved.faceId,
        score: data?.score,
        status: 'SUCCESS',
        accepted: true,
        canCompleteTake: true,
        employee: resolved.employee,
        cardNo: resolved.cardNo,
        faceBinding: resolved.faceBinding
      }
    } catch (error) {
      if (error?.code === 'FACE_TIMEOUT') {
        await faceRecognitionCancel().catch(() => {})
      }
      throw error
    }
  }
  if (type === 'FINGERPRINT') {
    return runFingerprintRecognition(progressCallback)
  }
  return { accepted: false, error: 'UNSUPPORTED_RECOGNITION_TYPE', message: '不支持的识别方式' }
}

/** 取消识别 */
async function cancelRecognition(type) {
  if (type === 'FACE') {
    return faceRecognitionCancel()
  }
  if (type === 'FINGERPRINT') {
    return nativeBridge.request('fingerprint.cancel', {}, 5000).catch(() => ({ cancelled: true }))
  }
  return { cancelled: true }
}

/** 注册生物特征（人脸/指纹） */
async function registerBiometric(type, data, progressCallback) {
  assertAnyLocalPermission(['system.face.register', 'system.employee.face-register'])
  if (type === 'FACE') {
    await initializeLocalStore()
    const employeeId = String(data?.employeeId || '').trim()
    if (!employeeId) throw faceWorkflowError('EMPLOYEE_ID_REQUIRED', '请选择需要录入人脸的员工')
    const employee = await localStore.getEmployeeById(employeeId)
    if (!employee) throw faceWorkflowError('EMPLOYEE_NOT_FOUND', '员工不存在，请先同步员工数据')
    if (!employee.enabled) throw faceWorkflowError('EMPLOYEE_DISABLED', '员工已停用，不能录入人脸')
    if (Number(employee.expiresAt || 0) > 0 && Number(employee.expiresAt) <= Date.now()) {
      throw faceWorkflowError('EMPLOYEE_EXPIRED', '员工授权已过期，不能录入人脸')
    }
    if (!/^\d+$/.test(employeeId)) {
      throw faceWorkflowError('INVALID_EMPLOYEE_ID', '员工编号不是后台数字 ID，不能录入人脸')
    }
    await assertFaceOrganizationAuthorized()
    const operationId = `faceEnrollment:${employeeId}:${Date.now()}`
    const temporaryFaceAiId = createTemporaryFaceAiId(employeeId, operationId)
    await localStore.saveOperationRecord({
      operationId,
      operationType: 'FACE_ENROLLMENT',
      employeeId,
      employeeName: employee.employeeName || data?.employeeName || '',
      temporaryFaceAiId,
      state: 'VALIDATED'
    })
    if (progressCallback) progressCallback('DETECTING')
    let waitResult
    try {
      waitResult = waitForFaceEvent({
        successEvent: 'face.enrolled',
        cancelEvent: 'face.enrollment.cancelled',
        timeoutEvent: 'face.enrollment.timeout',
        failureEvent: 'face.enrollment.failed',
        timeoutMs: 65000,
        timeoutMessage: '人脸录入超时'
      })
      const res = await faceEnrollmentStart(temporaryFaceAiId)
      if (!res || res.accepted === false) {
        waitResult.cancel()
        await localStore.saveOperationRecord({
          operationId,
          operationType: 'FACE_ENROLLMENT',
          state: 'FAILED',
          errorCode: 'NOT_ACCEPTED',
          errorMessage: '原生人脸录入未被接受'
        })
        return { accepted: false, error: 'NOT_ACCEPTED' }
      }
      const enrolled = await waitResult.promise
      if (String(enrolled?.faceId || '').trim() !== temporaryFaceAiId) {
        await faceTemplateRemove(String(enrolled?.faceId || temporaryFaceAiId)).catch(() => {})
        throw faceWorkflowError('FACE_ID_MISMATCH', 'FaceAISDK 返回的人脸编号与本次录入不一致')
      }
      if (progressCallback) progressCallback('UPLOADING')
      const finalizeEnrollment = createMultiFaceEnrollmentWorkflow({
        uploadFaceImage: sendFaceImage,
        sendFaceRecord,
        findPhotoByFileHash: async (fileHash) => (
          (appState.faceSyncPending || []).find((item) => item?.fileHash === fileHash) ||
          localStore.getFacePhotoByFileHash(fileHash)
        ),
        removeTemplate: faceTemplateRemove,
        recordStage: (state, details = {}) => localStore.saveOperationRecord({
          operationId,
          operationType: 'FACE_ENROLLMENT',
          employeeId,
          employeeName: employee.employeeName || data?.employeeName || '',
          temporaryFaceAiId,
          faceId: details.faceId || '',
          ...details,
          state
        })
      })
      const result = await finalizeEnrollment({
        operationId,
        temporaryFaceAiId,
        employeeId,
        employeeName: employee.employeeName || data?.employeeName || '',
        enrolled
      })
      appState.faceSyncPending.push({
        operationId,
        employeeId,
        fileHash: result.fileHash,
        updatedAt: Date.now()
      })
      return { ...result, score: enrolled.score }
    } catch (error) {
      if (error?.code === 'FACE_TIMEOUT') {
        await faceEnrollmentCancel().catch(() => {})
      }
      await localStore.saveOperationRecord({
        operationId,
        operationType: 'FACE_ENROLLMENT',
        state: error?.code === 'FACE_TIMEOUT' ? 'TIMED_OUT' : 'FAILED',
        errorCode: error?.code || 'FACE_ENROLLMENT_FAILED',
        errorMessage: faceErrorMessage(error),
        serverCreated: error?.data?.serverCreated === true,
        faceId: error?.data?.serverFaceId || '',
        faceAiId: error?.data?.faceAiId || ''
      }).catch(() => {})
      throw error
    }
  }
  if (type === 'FINGERPRINT') {
    return registerFingerprintAuthorization(data, progressCallback)
  }
  return { accepted: false, error: 'UNSUPPORTED_BIOMETRIC_TYPE', message: '不支持的生物特征类型' }
}

/** 搜索员工 */
async function searchEmployees(query = '', { includeDisabled = false } = {}) {
  assertAnyLocalPermission(['system.employee.view', 'system.face.search'])
  const keyword = String(query || '').trim().toLowerCase()
  let employees
  if (includeDisabled) {
    employees = await localStore.loadEmployees({ includeDisabled: true }).catch((error) => {
      console.warn('load employees cache failed:', error)
      return []
    })
  } else {
    employees = Array.isArray(appState.employees) ? appState.employees : []
    if (!employees.length) {
      employees = await localStore.loadEmployees().catch((error) => {
        console.warn('load employees cache failed:', error)
        return []
      })
      if (employees.length) replaceEmployeesProjection(employees)
    }
  }
  // BUG-026: 批量加载人脸绑定数量，修正 top stats 人脸统计口径
  try {
    const faceCounts = await localStore.getEmployeeFaceCounts()
    for (const emp of employees) {
      const count = faceCounts[String(emp.employeeId)] || 0
      emp.faceRegistered = count > 0
      emp.faceCount = count
    }
  } catch (e) {
    console.warn('load face counts for employees failed:', e)
  }
  return filterEmployeesByKeyword(employees, keyword)
}

function filterEmployeesByKeyword(employees, keyword) {
  if (!keyword) return employees
  return employees.filter((employee) => {
    const values = [
      employee.employeeId,
      employee.id,
      employee.employeeCode,
      employee.employeeName,
      employee.departmentName
    ]
    return values.some((value) => String(value || '').toLowerCase().includes(keyword))
  })
}

/** 人员启停必须等待服务器业务成功后再更新本机员工投影。 */
async function setEmployeeAuthorization(employeeId, authorized) {
  const id = Number(employeeId)
  if (!Number.isInteger(id) || id <= 0) throw new Error('无效的员工ID')
  const result = await saveEmployee({
    employeeId: id,
    action: authorized ? 'enable' : 'disable'
  })
  return { ...result, changed: true, employeeId: id, authorized }
}

async function listEmployeeFaces(employeeId) {
  assertAnyLocalPermission(['system.employee.view', 'system.employee.face-register', 'system.face.search'])
  await initializeLocalStore()
  const bindings = await localStore.listFaceBindingsByEmployee(employeeId)
  const photos = await Promise.all(bindings.map(async (binding) => {
    const photo = await localStore.getFacePhotoByFaceId(binding.faceId)
    return photo ? { ...binding, ...photo } : null
  }))
  return photos.filter(Boolean)
}

async function getDeviceOrganizationAuthorization() {
  const mode = normalizeCommunicationMode(
    appState.settings.communicationMode || appState.settings.backendTransport
  )
  const mqttStatus = mode === 'HTTP'
    ? { connected: false }
    : await mqttLoginStatus().catch(() => ({ connected: false }))
  let payload
  if (isMqttBusinessReady(mqttStatus)) {
    await registerMqttBusinessHandlers({ reason: 'get-department' })
    const msgId = `department_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`
    const result = await sendMqttAndWaitForResponse('getDepartment', {}, 'getDepartmentResp', msgId)
    payload = result?.response?.data
    assertBackendSuccess(payload, 'getDepartmentResp', { requireCode: true })
  } else {
    const response = await httpGet('/api/v1/department/tree')
    assertHttpSuccess(response, 'getDepartment')
    assertBackendSuccess(response?.body || response, 'getDepartment', { requireCode: true })
    payload = unwrapResponsePayload(response)
  }
  const department = payload?.dept
  const authorized = Boolean(
    department &&
    typeof department === 'object' &&
    !Array.isArray(department) &&
    String(department.deptId || '').trim()
  )
  return { authorized, department: authorized ? department : null }
}

async function assertFaceOrganizationAuthorized() {
  const organization = await getDeviceOrganizationAuthorization()
  if (!organization.authorized) {
    throw faceWorkflowError(
      'ORGANIZATION_NOT_AUTHORIZED',
      FACE_ENROLLMENT_ORGANIZATION_UNAUTHORIZED_MESSAGE
    )
  }
  return organization.department
}

/** 部门树始终来自服务端当前设备授权范围。 */
async function getDepartmentTree() {
  assertAnyLocalPermission(['system.employee.create', 'system.employee.update'])
  const organization = await getDeviceOrganizationAuthorization()
  if (!organization.authorized) throw new Error(ORGANIZATION_UNAUTHORIZED_MESSAGE)
  return organization.department
}

/** 按统一 MQTT 协议新增或更新员工，服务端确认后再更新 Vue SQLite 投影。 */
async function saveEmployee(input = {}) {
  const action = String(input?.action || 'add').toLowerCase()
  const permissionKey = { add: 'system.employee.create', update: 'system.employee.update', enable: 'system.employee.enable', disable: 'system.employee.enable' }[action] || 'system.employee.create'
  assertLocalPermission(permissionKey)
  await initializeLocalStore()

  const postEmployee = async (request) => {
    const mqttStatus = await mqttLoginStatus().catch(() => ({ connected: false }))
    if (!isMqttBusinessReady(mqttStatus)) {
      const response = await httpPost('/api/v1/employee', request)
      assertHttpSuccess(response, 'saveEmployee')
      const payload = response?.body || response
      assertBackendSuccess(payload, 'saveEmployee', { requireCode: true })
      return payload
    }
    await registerMqttBusinessHandlers({ reason: 'save-employee', retry: false })
    const msgId = `employee_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`
    const result = await sendMqttAndWaitForResponse('saveEmployee', request, 'saveEmployeeResp', msgId)
    const response = result?.response?.data
    assertBackendSuccess(response, 'saveEmployeeResp', { requireCode: true })
    return response
  }

  const mutateEmployee = createEmployeeMutationWorkflow({
    postEmployee,
    getEmployeeById: (employeeId) => localStore.getEmployeeById(employeeId),
    upsertEmployees: (items, syncVersion) => localStore.upsertEmployees(items, syncVersion),
    loadEmployees: (options) => localStore.loadEmployees(options),
    replaceEmployees: (employees) => replaceEmployeesProjection(employees)
  })
  const operationType = {
    add: 'EMPLOYEE_ADD',
    update: 'EMPLOYEE_UPDATE',
    enable: 'EMPLOYEE_ENABLE',
    disable: 'EMPLOYEE_DISABLE'
  }[action] || 'EMPLOYEE_ADD'
  try {
    const result = await mutateEmployee(input)
    await recordEmployeeHistory(operationType, result.employee || {
      ...input,
      employeeId: result.employeeId
    }, result.cacheUpdated ? {} : {
      state: 'PARTIAL',
      error: operationError('EMPLOYEE_CACHE_NOT_WRITTEN', result.cacheError || '服务端已保存，但本机员工缓存未更新')
    })
    return result
  } catch (error) {
    await recordEmployeeHistory(operationType, input, { state: 'FAILED', error })
    throw error
  }
}

/** 删除员工：V4.2 POST /api/v1/employee action=update status=1 停用 */
async function deleteEmployee(id) {
  assertLocalPermission('system.employee.enable')
  await initializeLocalStore()
  const employeeId = Number(id)
  if (!Number.isInteger(employeeId) || employeeId <= 0) {
    throw new Error('无效的员工ID')
  }
  const employee = await localStore.getEmployeeById(String(employeeId))
  const faceBindings = await localStore.listFaceBindingsByEmployee(String(employeeId), { includeDisabled: true })

  let serverChanged = false
  try {
    // 1. 服务端停用
    const response = await httpPost('/api/v1/employee', {
      action: 'update',
      employeeId,
      status: '1'
    })
    assertHttpSuccess(response, '员工删除')
    assertBackendSuccess(response?.body || response, '员工删除', { requireCode: true })
    serverChanged = true

    // 2. 本地清理（员工、人脸绑定、人脸照片、指纹绑定全部停用）
    await localStore.disableEmployees([employeeId], Date.now())

    // 3. 从 Android FaceAISDK 移除该员工的全部人脸模板
    for (const binding of faceBindings) {
      try {
        await faceTemplateRemove(binding.faceAiId || binding.faceId)
      } catch (error) {
        console.warn('[deleteEmployee] faceTemplateRemove failed:', error)
      }
    }

    // 4. 刷新 UI 投影
    const employees = await localStore.loadEmployees()
    replaceEmployeesProjection(employees)
    await recordEmployeeHistory('EMPLOYEE_DISABLE', employee || { employeeId })

    return { deleted: true, employeeId }
  } catch (error) {
    await recordEmployeeHistory('EMPLOYEE_DISABLE', employee || { employeeId }, {
      state: serverChanged ? 'PARTIAL' : 'FAILED',
      error
    })
    throw error
  }
}

/** 同步员工 */
async function syncEmployees(options = {}) {
  assertAnyLocalPermission(['system.employee.sync', 'system.face.sync'])
  const result = await syncIdentityData(options)
  return result.employees
}

/** 获取可升级的固件文件列表（服务端 MQTT firmwareUpgrade 下行驱动） */
async function getUpgradeFiles() {
  assertAnyLocalPermission([
    'maintenance.app.upgrade',
    'maintenance.firmware.board',
    'maintenance.firmware.work-card',
    'maintenance.firmware.main-board'
  ])
  const pending = getFirmwareUpgrade()
  if (!pending) return []
  return [{
    id: pending.operationId || pending.firmwareVersion,
    operationId: pending.operationId,
    firmwareVersion: pending.firmwareVersion,
    downloadUrl: pending.downloadUrl,
    receivedAt: pending.receivedAt,
    status: pending.status,
    type: 'firmware'
  }]
}

const APP_UPDATE_TIMEOUT_MS = 10 * 60 * 1000

const assertAppUpdateAccess = (source) => {
  if (source !== 'BOOTSTRAP_FORCE') assertLocalPermission('maintenance.app.upgrade')
}

const saveAppUpdateOperation = async (operation = {}) => {
  await initializeLocalStore()
  return localStore.saveOperationRecord({
    operationType: 'APP_UPDATE',
    operatorName: appState.session?.username || '设备启动流程',
    ...operation
  })
}

async function getAppUpdateStatus(options = {}) {
  return nativeBridge.request('app.updateStatus', {
    clearCompleted: options.clearCompleted === true
  })
}

async function checkAppUpdate(options = {}) {
  assertAppUpdateAccess(options.source)
  const isManualCheck = options.source === 'MANUAL'
  const operationId = isManualCheck ? `appUpdateCheck:${Date.now()}` : ''
  let request = null
  let nativeInfo = null
  try {
    nativeInfo = await nativeBridge.request('app.updateInfo')
    request = {
      currentVersionCode: Number(nativeInfo?.currentVersionCode || 0),
      channelId: normalizeAppChannelId(nativeInfo?.channelId)
    }
    if (!request.channelId) throw new Error('APP 渠道号未由 Android 打包配置提供')
    const currentDeviceCode = String(appState.deviceInfo?.deviceCode || '').trim()
    if (currentDeviceCode) request.deviceCode = currentDeviceCode
    const response = await httpPost('/api/v1/app-version/check', request)
    const rawVersionInfo = extractAppVersionCheckData(response)
    const versionInfo = normalizeAppVersionInfo(rawVersionInfo, {
      currentVersionCode: request.currentVersionCode
    })
    if (isManualCheck) {
      await saveAppUpdateOperation({
        operationId,
        operationType: 'APP_UPDATE_CHECK',
        state: 'COMPLETED',
        checkOutcome: versionInfo ? 'UPDATE_AVAILABLE' : 'NO_UPDATE',
        currentVersionCode: request.currentVersionCode,
        currentVersionName: String(nativeInfo?.currentVersionName || '').trim(),
        channelId: request.channelId,
        versionInfo: versionInfo || null
      }).catch((error) => console.warn('[app-update] check persistence failed:', error))
    }
    return {
      ...nativeInfo,
      channelId: request.channelId,
      hasUpdate: Boolean(versionInfo),
      versionInfo
    }
  } catch (error) {
    if (isManualCheck) {
      await saveAppUpdateOperation({
        operationId,
        operationType: 'APP_UPDATE_CHECK',
        state: 'FAILED',
        currentVersionCode: Number(request?.currentVersionCode || nativeInfo?.currentVersionCode || 0),
        currentVersionName: String(nativeInfo?.currentVersionName || '').trim(),
        channelId: String(request?.channelId || '').trim(),
        rawError: { code: String(error?.code || ''), message: String(error?.message || error) }
      }).catch((persistError) => console.warn('[app-update] failed check persistence failed:', persistError))
    }
    throw error
  }
}

async function downloadAppUpdate(versionInfo, options = {}) {
  assertAppUpdateAccess(options.source)
  const current = await getAppUpdateStatus()
  const normalized = normalizeAppVersionInfo(versionInfo, {
    currentVersionCode: Number(current?.currentVersionCode || 0)
  })
  if (!normalized) throw new Error('当前没有可下载的 APP 更新')
  const operationId = String(options.operationId || `appUpdate:${Date.now()}`).trim()
  await saveAppUpdateOperation({
    operationId,
    state: 'VALIDATED',
    versionInfo: normalized
  })
  const unsubscribe = nativeBridge.on('app.updateProgress', (progress) => {
    if (String(progress?.operationId || '') !== operationId) return
    options.onProgress?.(progress)
    saveAppUpdateOperation({
      operationId,
      state: 'DOWNLOADING',
      progress: Number(progress?.progress || 0),
      versionInfo: normalized
    }).catch((error) => console.warn('[app-update] progress persistence failed:', error))
  })
  try {
    const nativeResult = await nativeBridge.request('app.downloadUpdate', {
      operationId,
      ...normalized
    }, APP_UPDATE_TIMEOUT_MS)
    await saveAppUpdateOperation({
      operationId,
      state: 'VERIFIED',
      progress: 100,
      versionInfo: normalized,
      nativeResult
    })
    return { operationId, versionInfo: normalized, nativeResult }
  } catch (error) {
    await saveAppUpdateOperation({
      operationId,
      state: 'FAILED',
      versionInfo: normalized,
      rawError: { code: error?.code || '', message: error?.message || String(error) }
    })
    throw error
  } finally {
    unsubscribe?.()
  }
}

async function installAppUpdate(operationId, options = {}) {
  assertAppUpdateAccess(options.source)
  const normalizedOperationId = String(operationId || '').trim()
  if (!normalizedOperationId) throw new Error('缺少 APP 升级操作编号')
  await saveAppUpdateOperation({ operationId: normalizedOperationId, state: 'INSTALL_REQUESTED' })
  try {
    const nativeResult = await nativeBridge.request('app.installUpdate', {
      operationId: normalizedOperationId
    }, 60000)
    const state = String(nativeResult?.status || '').toUpperCase()
    if (!['PERMISSION_REQUIRED', 'INSTALLER_OPENED'].includes(state)) {
      throw new Error('原生安装能力未返回有效状态')
    }
    await saveAppUpdateOperation({
      operationId: normalizedOperationId,
      state,
      nativeResult
    })
    return nativeResult
  } catch (error) {
    await saveAppUpdateOperation({
      operationId: normalizedOperationId,
      state: 'FAILED',
      rawError: { code: error?.code || '', message: error?.message || String(error) }
    })
    throw error
  }
}

let appUpdateRecoveryPromise = null

async function recoverPendingAppUpdate() {
  if (!nativeBridge.isChannelReady()) return { recovered: false, reason: 'CHANNEL_NOT_READY' }
  if (appUpdateRecoveryPromise) return appUpdateRecoveryPromise
  appUpdateRecoveryPromise = (async () => {
    const status = await getAppUpdateStatus()
    const operationId = String(status?.operationId || '').trim()
    const state = String(status?.status || 'NONE').toUpperCase()
    if (!operationId || state === 'NONE') return { recovered: false, status: state }
    await saveAppUpdateOperation({
      operationId,
      state,
      progress: Number(status?.progress || 0),
      nativeResult: status
    })
    if (state === 'COMPLETED') {
      await getAppUpdateStatus({ clearCompleted: true })
    }
    return { recovered: true, operationId, status: state }
  })()
  try {
    return await appUpdateRecoveryPromise
  } finally {
    appUpdateRecoveryPromise = null
  }
}

/** 手动执行当前会话内的固件任务；MQTT 下行默认会自动执行。 */
async function startUpgrade(fileId, progressCallback) {
  assertLocalPermission('maintenance.firmware.board')
  if (typeof progressCallback !== 'function') progressCallback = null

  const pending = getFirmwareUpgrade()
  if (!pending) {
    throw Object.assign(new Error('没有待处理的固件升级任务'), { code: 'NO_PENDING_FIRMWARE' })
  }
  if (pending.operationId !== fileId && pending.firmwareVersion !== fileId) {
    throw Object.assign(new Error('固件升级任务编号不匹配'), { code: 'FIRMWARE_OPERATION_MISMATCH' })
  }
  if (activeFirmwareUpgrade) {
    if (activeFirmwareUpgrade.operationId === pending.operationId) return activeFirmwareUpgrade.promise
    throw Object.assign(new Error('已有固件升级正在执行'), { code: 'FIRMWARE_UPGRADE_BUSY' })
  }

  const control = {
    operationId: pending.operationId,
    phase: 'PENDING',
    cancelRequested: false,
    promise: null
  }
  const promise = executeFirmwareUpgrade(pending, progressCallback, control)
  control.promise = promise
  activeFirmwareUpgrade = control
  try {
    return await promise
  } finally {
    if (activeFirmwareUpgrade === control) activeFirmwareUpgrade = null
  }
}

/**
 * 获取串口操作能力对象（同时支持真实设备与模拟串口）。
 */
async function getSerialSimulatorCapability() {
  if (!nativeBridge.isAvailable()) return null
  let status
  try {
    status = await serialStatus()
  } catch (_) {
    return null
  }
  if (status?.simulator === true) {
    const totalSlots = Number(status.totalSlots)
    return {
      ...status,
      simulator: true,
      totalSlots: Number.isInteger(totalSlots) && totalSlots > 0 ? totalSlots : 10
    }
  }
  // 真实设备路径：串口已连接即可执行操作
  const serialState = String(status?.state || '').trim().toUpperCase()
  const connected = serialState === 'CONNECTED' || status?.connected || status?.open
  const totalSlots = Number(status?.totalSlots)
  return {
    state: connected ? 'CONNECTED' : serialState || 'UNKNOWN',
    simulator: false,
    connected,
    totalSlots: Number.isInteger(totalSlots) && totalSlots > 0 ? totalSlots : 10
  }
}

function operationError(code, message) {
  const error = new Error(message)
  error.code = code
  return error
}

function publishCabinetOperationEffect(slotNumber, effect, operationId, message) {
  const normalizedSlotNumber = Number(slotNumber)
  if (!Number.isInteger(normalizedSlotNumber) || normalizedSlotNumber < 1) return null
  const previousTimer = cabinetOperationEffectTimers.get(normalizedSlotNumber)
  if (previousTimer) clearTimeout(previousTimer)
  const item = setCabinetOperationEffect(normalizedSlotNumber, effect, {
    operationId,
    message
  })
  if (!item) return null
  const timer = setTimeout(() => {
    cabinetOperationEffectTimers.delete(normalizedSlotNumber)
    clearCabinetOperationEffect(normalizedSlotNumber, item.id)
  }, 3000)
  cabinetOperationEffectTimers.set(normalizedSlotNumber, timer)
  return item
}

function adminSerialTimeoutMs() {
  const configured = Number(appState.settings.serialResponseTimeout)
  return Math.max(500, Math.min(10000,
    Number.isFinite(configured) && configured > 0 ? configured : 3000
  ))
}

// 单板开门每次最多等 300ms，原生会重发一次；Vue 还需允许一条既有轮询收尾，
// 以免开门任务刚进入专用队列就被前端误判为超时。
const DOOR_ACK_WAIT_TIMEOUT_MS = 1800
const DOOR_COMMAND_MODE = Object.freeze({
  ADMIN_TAKE: 'ADMIN_TAKE',
  EMPLOYEE_ISSUE: 'EMPLOYEE_ISSUE'
})

function isAdministratorDoorCommand(commandMode) {
  return commandMode === DOOR_COMMAND_MODE.ADMIN_TAKE
}

function serialEventHex(event = {}) {
  return String(event?.hex || event?.data?.hex || event?.rawHex || '').replace(/[^0-9a-f]/ig, '').toUpperCase()
}

function isDoorTransmitEvent(event, request = {}) {
  if (event?.type !== 'serialTx' || event?.source !== 'manual') return false
  const expectedCategory = request.administrator === true ? 'door.admin' : 'door.issue'
  if (event?.data?.category !== expectedCategory) return false
  const hex = serialEventHex(event)
  const address = Number.parseInt(hex.slice(10, 12), 16)
  const functionCode = Number.parseInt(hex.slice(12, 14), 16)
  return address === Number(request.slotNumber) && functionCode === 0x51
}

function isDoorTimeoutEvent(event, request = {}) {
  if (event?.type !== 'serialCommandTimeout' || Number(event?.slotNumber) !== Number(request.slotNumber)) return false
  return event?.category === (request.administrator === true ? 'door.admin' : 'door.issue')
}

let doorOperationScheduler = null

function getDoorOperationScheduler() {
  if (doorOperationScheduler) return doorOperationScheduler
  doorOperationScheduler = createDoorOperationScheduler({
    sendOpenDoor: serialOpenDoor,
    subscribe: (eventName, callback) => nativeBridge.on(eventName, callback),
    matchTransmit: isDoorTransmitEvent,
    matchAck: (event, request) => parseTakeOpenAck(event, request.slotNumber),
    matchTimeout: isDoorTimeoutEvent
  })
  return doorOperationScheduler
}

async function dispatchDoorCommand ({
  operationId = '',
  slotNumber,
  commandMode = DOOR_COMMAND_MODE.EMPLOYEE_ISSUE,
  requiresBoardAck = true,
  txTimeoutMs = DOOR_ACK_WAIT_TIMEOUT_MS,
  ackTimeoutMs = DOOR_ACK_WAIT_TIMEOUT_MS
} = {}) {
  return getDoorOperationScheduler().dispatch({
    operationId,
    slotNumber,
    administrator: isAdministratorDoorCommand(commandMode),
    requiresBoardAck,
    // 原生实机必定回传 serialTx；浏览器 Mock 没有该事件时保留既有模拟流程。
    expectTransmitEvent: nativeBridge.isAvailable(),
    txTimeoutMs,
    ackTimeoutMs
  })
}

async function sendDoorCommandAndWaitAck(slotNumber, commandMode, operationId = '') {
  return dispatchDoorCommand({
    operationId,
    slotNumber,
    commandMode,
    requiresBoardAck: true,
    txTimeoutMs: DOOR_ACK_WAIT_TIMEOUT_MS,
    ackTimeoutMs: DOOR_ACK_WAIT_TIMEOUT_MS
  })
}

function wasCardPresentBeforeDoorCommand(slot, slotNumber) {
  const status = String(slot?.status || '').trim().toUpperCase()
  return slotNumberOf(slot) === Number(slotNumber) &&
    status !== '' &&
    status !== 'UNKNOWN' &&
    !isAdminCardPhysicallyRemoved(slot, slotNumber)
}

function confirmedEmptySlotAfterAckTimeout(slotNumber, initialSlot) {
  if (!wasCardPresentBeforeDoorCommand(initialSlot, slotNumber)) return null
  try {
    const projectedSlot = readProjectedSlotSnapshot(slotNumber)
    return isAdminCardPhysicallyRemoved(projectedSlot, slotNumber) ? projectedSlot : null
  } catch (_) {
    return null
  }
}

async function sendAdministratorDoorCommandWithPhysicalFallback(slotNumber, operationId, initialSlot) {
  try {
    return await sendDoorCommandAndWaitAck(slotNumber, DOOR_COMMAND_MODE.ADMIN_TAKE, operationId)
  } catch (error) {
    if (error?.code !== 'SERIAL_ACK_TIMEOUT') throw error
    const confirmedSlot = confirmedEmptySlotAfterAckTimeout(slotNumber, initialSlot)
    if (!confirmedSlot) throw error
    return {
      sent: true,
      queued: true,
      boardAck: null,
      physicalConfirmed: true,
      ackMissing: true,
      confirmedSlot,
      confirmationSource: 'SERIAL_SLOT_EMPTY_AFTER_ACK_TIMEOUT'
    }
  }
}

async function requireConnectedSerialSimulator() {
  if (!nativeBridge.isAvailable()) {
    throw operationError('SERIAL_CAPABILITY_UNAVAILABLE', '当前环境没有 Android 串口能力，未执行卡柜操作')
  }
  const capability = await getSerialSimulatorCapability()
  if (!capability) {
    throw operationError(
      'SERIAL_TOPOLOGY_UNCONFIRMED',
      '串口通道未就绪，无法执行卡柜操作'
    )
  }
  const deviceLabel = capability.simulator ? '模拟串口' : '串口'
  if (String(capability.state || '').trim().toUpperCase() !== 'CONNECTED') {
    throw operationError('SERIAL_NOT_CONNECTED', `${deviceLabel}尚未连接`)
  }
  return capability
}

async function readSimulatorSlotsSnapshot() {
  const slots = slotsFromSerialSnapshot(await serialSlotsSnapshot())
  slots.forEach((slot) => upsertSlotProjection(slot))
  return slots
}

async function readSimulatorSlotSnapshot(slotNumber) {
  const slots = await readSimulatorSlotsSnapshot()
  const slot = slots.find((item) => slotNumberOf(item) === slotNumber) || null
  if (!slot) {
    throw operationError('SLOT_STATE_UNAVAILABLE', slotNumber + '号卡槽尚无 Android 自动轮询状态')
  }
  const targetSlot = { ...slot, slotNumber }
  // 管理员已指定目标卡槽，不能因校验该卡槽而把全柜快照同步写入 SQLite。
  queueSlotSnapshot(targetSlot, 'SERIAL_NATIVE_SNAPSHOT', true)
  return targetSlot
}

function readProjectedSlotSnapshot(slotNumber) {
  const normalizedSlotNumber = Number(slotNumber)
  const slot = appState.slots.find((item) => slotNumberOf(item) === normalizedSlotNumber) || null
  if (!slot) {
    throw operationError('SLOT_STATE_UNAVAILABLE', normalizedSlotNumber + '号卡槽实时状态尚未准备好')
  }
  return { ...slot, slotNumber: normalizedSlotNumber }
}

function logAdminTakeTiming(slotNumber, startedAt, stage) {
  console.info('[admin-take] slot=' + slotNumber + ' stage=' + stage + ' elapsedMs=' + (Date.now() - startedAt))
}

function adminPhysicalConfirmTimeoutMs() {
  return 3000
}

function serialSlotsFromEvent(event = {}) {
  if (Array.isArray(event?.slots)) return event.slots
  if (Array.isArray(event)) return event
  return event ? [event] : []
}

function createSimulatorSlotEventWaiter(slotNumber, isConfirmed, timeoutCode, timeoutMessage) {
  let settled = false
  let timeoutTimer = null
  const unsubscribers = []
  const cleanup = () => {
    if (timeoutTimer) clearTimeout(timeoutTimer)
    timeoutTimer = null
    unsubscribers.splice(0).forEach((unsubscribe) => {
      try { unsubscribe?.() } catch (_) {}
    })
  }
  const promise = new Promise((resolve, reject) => {
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    const handleEvent = (event) => {
      try {
        const slot = serialSlotsFromEvent(event)
          .find((item) => slotNumberOf(item) === slotNumber)
        if (slot && isConfirmed(slot, slotNumber)) finish(resolve, slot)
      } catch (error) {
        finish(reject, error)
      }
    }
    unsubscribers.push(
      nativeBridge.on('slot.status', handleEvent),
      nativeBridge.on('cabinet.slotsSnapshot', handleEvent)
    )
    timeoutTimer = setTimeout(() => {
      finish(reject, operationError(timeoutCode, timeoutMessage))
    }, adminPhysicalConfirmTimeoutMs())
  })
  return {
    promise,
    cancel() {
      if (settled) return
      settled = true
      cleanup()
    }
  }
}

/**
 * 真实设备通过事件等待门打开状态确认。
 * 使用 slot.status / cabinet.slotsSnapshot 事件代替轮询，
 * 避免串口应答时延导致 serialSlotsSnapshot 轮询超时。
 */
async function waitForDoorOpenEvent(slotNumber, timeoutMs, previousDoorCode) {
  return new Promise((resolve, reject) => {
    let settled = false
    let doorConfirmation = null
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    const timer = setTimeout(() => {
      if (doorConfirmation) {
        finish(resolve, { ...doorConfirmation, slotNumber, physicalConfirmed: false })
      } else {
        finish(reject, operationError(
          'DOOR_STATE_NOT_OPEN',
          slotNumber + '号卡门未在确认时限内返回已打开状态'
        ))
      }
    }, timeoutMs)

    const previousDoor = Number(previousDoorCode)

    // 板级 0x51 应答监听：最可靠的开门确认
    const onDataReceived = (event) => {
      if (settled) return
      const ack = parseTakeOpenAck(event, slotNumber)
      if (!ack) return
      if (ack.accepted) {
        doorConfirmation = { ...ack, slotNumber, confirmationFrom: 'BOARD_ACK' }
      } else {
        finish(reject, operationError('SERIAL_COMMAND_REJECTED',
          slotNumber + '号卡开门被设备板级拒绝'))
      }
    }

    // 轮询门状态变化监听（仅当开门前 doorCode ≠ 1 时可靠）
    const onSlotEvent = (data) => {
      if (settled) return
      const slots = Array.isArray(data) ? data
        : (Array.isArray(data?.slots) ? data.slots
        : (data?.slot ? [data.slot]
        : (data && (data.slotNumber != null || data.slotId != null) ? [data] : [])))
      const slot = slots.find((s) => slotNumberOf(s) === slotNumber)
      if (!slot) return
      const currentDoor = Number(slot?.doorCode)
      if (isAdminCardPhysicallyRemoved(slot, slotNumber)) {
        const projection = {
          ...slot,
          slotNumber,
          source: 'SERIAL_SLOT_STATUS_EVENT',
          fresh: true,
          updatedAt: Number(slot.updatedAt || Date.now()),
          physicalConfirmed: true,
          confirmationFrom: 'SERIAL_SLOT_EMPTY'
        }
        upsertSlotProjection(projection)
        queueSlotSnapshot(projection, 'SERIAL_SLOT_STATUS_EVENT', true)
        finish(resolve, projection)
        return
      }
      // 如果开门前 doorCode 已经是 1，不能用 doorCode 变化来确认
      if (previousDoor === 1) return
      // 门状态变化到 1 才算有效信号
      if (currentDoor === 1) {
        doorConfirmation = {
          ...slot,
          slotNumber,
          source: 'SERIAL_SLOT_STATUS_EVENT',
          fresh: true,
          updatedAt: Number(slot.updatedAt || Date.now()),
          confirmationFrom: 'SERIAL_DOOR_OPEN'
        }
      }
    }

    const unsub1 = nativeBridge.on('slot.status', onSlotEvent)
    const unsub2 = nativeBridge.on('cabinet.slotsSnapshot', onSlotEvent)
    const unsub3 = nativeBridge.on('serial.dataReceived', onDataReceived)

    function cleanup () {
      clearTimeout(timer)
      unsub1()
      unsub2()
      unsub3()
    }
  })
}

async function executeAdminOpenDoor(slotNumber, options = {}) {
  if (options.requireLocalPermission === true) assertLocalPermission('realtime.slot.open')
  const address = Number(slotNumber)
  const operationId = options.operationId || ('adminUnlock:' + address + ':' + Date.now())
  const operationType = options.operationType || 'ADMIN_UNLOCK'
  const operatorName = options.operatorName ||
    appState.session?.credentialLabel ||
    appState.session?.roleLabels?.join('、') ||
    '本机管理员'
  const operation = {
    operationId,
    operationType,
    operatorName,
    slotNumber: address,
    boardAddress: address,
    source: options.source || 'LOCAL_ADMIN',
    requestMsgId: options.requestMsgId || '',
    authType: options.authType || 'ADMIN'
  }
  const immediateRemoteOpen = operation.source === 'REMOTE_ADMIN'
  const deferRemoteHistory = (patch, label) => {
    recordOperationHistory({ ...operation, ...patch })
      .catch((error) => console.warn(`[remote-open] ${label} history deferred:`, error))
  }
  if (immediateRemoteOpen) deferRemoteHistory({ state: 'RECEIVED' }, 'received')
  else await recordOperationHistory({ ...operation, state: 'RECEIVED' })
  try {
    const simulator = await requireConnectedSerialSimulator()
    // 真实设备可能未确认 totalSlots，范围检查仅对模拟器或已知边界强制
    if (!Number.isInteger(address) || address < 1) {
      throw operationError('INVALID_SLOT', '卡槽号无效，无法执行开门')
    }
    if ((simulator.simulator || Number.isFinite(simulator.totalSlots)) && address > simulator.totalSlots) {
      throw operationError('INVALID_SLOT', '卡槽号超出设备范围，无法执行开门')
    }
    const isRemoteAdmin = options.source === 'REMOTE_ADMIN'
    let initialSlot
    let validation
    initialSlot = isRemoteAdmin
      ? (appState.slots.find((slot) => slotNumberOf(slot) === address) || { slotNumber: address, status: 'UNKNOWN' })
      : await readSimulatorSlotSnapshot(address)
    if (isRemoteAdmin) {
      const slotStatus = String(initialSlot?.status || '').trim().toUpperCase()
      validation = {
        ok: true,
        slot: { ...initialSlot, slotNumber: address, status: slotStatus || 'UNKNOWN' },
        slotNumber: address,
        status: slotStatus || 'UNKNOWN',
        cardNo: cardNoOf(initialSlot) || ''
      }
    } else {
      validation = validateAdminCardSlot(initialSlot, address)
    }
    if (!validation.ok) throw operationError(validation.code, validation.message)
    const validated = recordOperationHistory({
      ...operation,
      state: 'VALIDATED',
      cardNo: validation.cardNo,
      initialSlot
    })
    if (immediateRemoteOpen) validated.catch((error) => console.warn('[remote-open] validated history deferred:', error))
    else if (!await validated) {
      throw operationError('OPERATION_PERSIST_FAILED', '操作记录初始化失败，未发送开门指令')
    }

    const result = await sendAdministratorDoorCommandWithPhysicalFallback(address, operationId, initialSlot)
    const confirmationSource = result.confirmationSource || 'SERIAL_COMMAND_ACCEPTED'
    const serialSent = recordOperationHistory({ ...operation, state: 'SERIAL_SENT', cardNo: validation.cardNo })
    if (immediateRemoteOpen) serialSent.catch((error) => console.warn('[remote-open] serial history deferred:', error))
    else await serialSent
    const commandAcceptedAt = Date.now()
    announceAdminCardOpened(address)
    const commandAccepted = recordOperationHistory({
      ...operation,
      state: 'COMMAND_ACCEPTED',
      cardNo: validation.cardNo,
      commandAcceptedAt,
      confirmationSource
    })
    const statusReportPromise = reportDeviceStatusImmediately('remote-open')
    publishCabinetOperationEffect(
      address,
      'success',
      operationId,
      String(address).padStart(2, '0') + '号卡槽已开卡'
    )
    // 后台指令的成功语义是板卡 0x11；不能把 MQTT 状态上报耗时带进 remoteOpenResp。
    // 开门确认后才补发目标槽查询，避免在板卡等待应答期间发送其它串口命令。
    Promise.resolve()
      .then(() => serialQuerySlot(address))
      .catch((error) => console.warn('[remote-open] target slot query failed:', error))
    completeAdminCommandReport({
      ...operation,
      cardNo: validation.cardNo,
      initialSlot
    }, statusReportPromise, {
      commandAcceptedAt,
      confirmationSource
    })
    if (immediateRemoteOpen) commandAccepted.catch((error) => console.warn('[remote-open] accepted history deferred:', error))
    else await commandAccepted
    return {
      sent: true,
      accepted: true,
      confirmed: true,
      stage: 'COMMAND_ACCEPTED',
      slotNumber: address,
      cardNo: validation.cardNo,
      commandAcceptedAt,
      physicalConfirmed: result.physicalConfirmed === true,
      ackMissing: result.ackMissing === true,
      historyRecorded: true,
      statusReportPending: true,
      message: String(address).padStart(2, '0') + '号卡槽已开卡'
    }
  } catch (error) {
    const state = String(error?.code || '').endsWith('TIMEOUT') ? 'TIMED_OUT' : 'FAILED'
    await recordOperationHistory({
      ...operation,
      state,
      rawError: {
        code: error?.code || 'ADMIN_OPEN_FAILED',
        message: error?.message || String(error)
      }
    })
    publishCabinetOperationEffect(address, 'failure', operationId, error?.message || String(error))
    throw error
  }
}

function completeAdminCommandReport(operation, statusReportPromise, details = {}) {
  Promise.resolve(statusReportPromise).then(async (statusReport) => {
    const reportPending = statusReport?.sent !== true
    const completionRecord = recordOperationHistory({
      ...operation,
      ...details,
      state: reportPending ? 'REPORT_PENDING' : 'COMPLETED',
      finishedAt: reportPending ? null : Date.now(),
      statusReport,
      ...(reportPending ? {
        rawError: {
          code: 'STATUS_REPORT_PENDING',
          message: statusReport?.error || statusReport?.reason || '卡柜状态等待后台同步'
        }
      } : {})
    })
    await completionRecord
  }).catch((error) => {
    console.warn('[admin-eject] background status report failed:', error)
  })
}

async function executeAdminEjectSlot(target, parent = {}) {
  const slotNumber = target.slotNumber
  const startedAt = Date.now()
  const operationId = parent.singleOperation === true
    ? parent.operationId
    : parent.operationId + ':slot:' + slotNumber
  const operation = {
    operationId,
    operationType: parent.slotOperationType || 'ADMIN_EJECT_SLOT',
    operatorName: parent.operatorName,
    requestMsgId: parent.requestMsgId || '',
    source: parent.source || 'LOCAL_ADMIN',
    authType: 'ADMIN',
    slotNumber,
    boardAddress: slotNumber,
    cardNo: target.cardNo
  }
  let physicalConfirmed = false
  let doorOpened = false
  let statusReportPromise = null
  // 指定单槽管理员取卡以板级 ACK 为界面完成条件；SQLite 队列不能阻塞发门。
  const immediateAdminTake = parent.openDoorOnly === true && parent.singleOperation === true && operation.source === 'LOCAL_ADMIN'
  const deferCommandPersistence = immediateAdminTake || parent.deferPersistence === true
  const receivedRecord = recordOperationHistory({ ...operation, state: 'RECEIVED' })
  if (deferCommandPersistence) receivedRecord.catch((error) => console.warn('[admin-eject] received history deferred:', error))
  else await receivedRecord
  try {
    const currentSlot = deferCommandPersistence && target.slot
      ? { ...target.slot, slotNumber }
      : (immediateAdminTake ? readProjectedSlotSnapshot(slotNumber) : await readSimulatorSlotSnapshot(slotNumber))
    if (parent.openDoorOnly === true) {
      if (Number(currentSlot?.cardCode) === 0 || String(currentSlot?.status || '').trim().toUpperCase() === 'EMPTY') {
        throw operationError('NO_CARD_PRESENT', slotNumber + '号卡槽已为空，跳过开门')
      }
      const validatedRecord = recordOperationHistory({
        ...operation,
        state: 'VALIDATED',
        initialSlot: currentSlot
      })
      if (deferCommandPersistence) validatedRecord.catch((error) => console.warn('[admin-eject] validated history deferred:', error))
      const validated = deferCommandPersistence ? true : await validatedRecord
      if (!validated) {
        throw operationError('OPERATION_PERSIST_FAILED', '操作记录初始化失败，未发送弹卡指令')
      }
      if (immediateAdminTake) logAdminTakeTiming(slotNumber, startedAt, 'dispatching')
      const result = await sendAdministratorDoorCommandWithPhysicalFallback(slotNumber, operationId, currentSlot)
      const confirmationSource = result.confirmationSource || 'SERIAL_COMMAND_ACCEPTED'
      const serialSentRecord = recordOperationHistory({ ...operation, state: 'SERIAL_SENT', initialSlot: currentSlot })
      if (deferCommandPersistence) serialSentRecord.catch((error) => console.warn('[admin-eject] serial history deferred:', error))
      else await serialSentRecord
      doorOpened = true
      physicalConfirmed = result.physicalConfirmed === true
      const commandAcceptedAt = Date.now()
      if (immediateAdminTake) logAdminTakeTiming(slotNumber, startedAt, 'board_acked')
      announceAdminCardOpened(slotNumber, { flush: parent.ttsFlush !== false })
      const commandAcceptedRecord = recordOperationHistory({
        ...operation,
        state: 'COMMAND_ACCEPTED',
        commandAcceptedAt,
        confirmationSource,
        initialSlot: currentSlot
      })
      if (deferCommandPersistence) commandAcceptedRecord.catch((error) => console.warn('[admin-eject] accepted history deferred:', error))
      else await commandAcceptedRecord
      statusReportPromise = reportDeviceStatusImmediately('admin-eject-all')
      completeAdminCommandReport(operation, statusReportPromise, {
        commandAcceptedAt,
        confirmationSource,
        initialSlot: currentSlot
      })
      if (parent.deferOperationEffect !== true) {
        publishCabinetOperationEffect(
          slotNumber,
          'success',
          operationId,
          slotNumber + '号卡槽已开卡'
        )
      }
      if (immediateAdminTake) logAdminTakeTiming(slotNumber, startedAt, 'returned')
      return {
        accepted: true,
        doorOpened: true,
        physicalConfirmed,
        ackMissing: result.ackMissing === true,
        commandAccepted: true,
        slotNumber,
        commandAcceptedAt,
        reportPending: false,
        statusReportPending: true,
        message: slotNumber + '号卡槽已开卡'
      }
    }
    const validation = validateAdminCardSlot(currentSlot, slotNumber)
    if (!validation.ok) throw operationError(validation.code, validation.message)
    if (target.cardNo && validation.cardNo !== target.cardNo) {
      throw operationError('CARD_CHANGED', slotNumber + '号卡槽卡号已变化，未执行弹卡')
    }
    operation.cardNo = validation.cardNo
    const validated = await recordOperationHistory({
      ...operation,
      state: 'VALIDATED',
      initialSlot: currentSlot
    })
    if (!validated) {
      throw operationError('OPERATION_PERSIST_FAILED', '操作记录初始化失败，未发送弹卡指令')
    }

    const result = await sendAdministratorDoorCommandWithPhysicalFallback(slotNumber, operationId, currentSlot)
    const confirmationSource = result.confirmationSource || 'SERIAL_COMMAND_ACCEPTED'
    await recordOperationHistory({ ...operation, state: 'SERIAL_SENT' })
    const commandAcceptedAt = Date.now()
    doorOpened = true
    physicalConfirmed = result.physicalConfirmed === true
    announceAdminCardOpened(slotNumber, { flush: parent.ttsFlush !== false })
    await recordOperationHistory({
      ...operation,
      state: 'COMMAND_ACCEPTED',
      commandAcceptedAt,
      confirmationSource
    })
    statusReportPromise = reportDeviceStatusImmediately('admin-take')
    const statusReport = await statusReportPromise
    const reportPending = statusReport?.sent !== true
    await recordOperationHistory({
      ...operation,
      state: reportPending ? 'REPORT_PENDING' : 'COMPLETED',
      finishedAt: reportPending ? null : Date.now(),
      commandAcceptedAt,
        confirmationSource,
      statusReport,
      ...(reportPending ? {
        rawError: {
          code: 'STATUS_REPORT_PENDING',
          message: statusReport?.error || statusReport?.reason || '卡槽状态等待后台同步'
        }
      } : {})
    })
    if (parent.deferOperationEffect !== true) {
      publishCabinetOperationEffect(
        slotNumber,
        'success',
        operationId,
        slotNumber + '号卡槽已开卡'
      )
    }
    return {
      accepted: true,
      doorOpened: true,
        physicalConfirmed,
        ackMissing: result.ackMissing === true,
      commandAccepted: true,
      reportPending,
      reportError: reportPending ? (statusReport?.error || statusReport?.reason || '') : '',
      statusReport,
      slotNumber,
      cardNo: validation.cardNo,
      commandAcceptedAt,
      message: reportPending
        ? slotNumber + '号卡槽已开卡，卡槽状态等待后台同步'
        : slotNumber + '号卡槽已开卡'
    }
  } catch (error) {
    if (statusReportPromise) error.statusReport = await statusReportPromise
    error.physicalConfirmed = physicalConfirmed
    error.doorOpened = doorOpened
    const state = physicalConfirmed
      ? 'REPORT_PENDING'
      : (String(error?.code || '').endsWith('TIMEOUT') ? 'TIMED_OUT' : 'FAILED')
    const failedRecord = recordOperationHistory({
      ...operation,
      state,
      rawError: {
        code: error?.code || 'ADMIN_EJECT_FAILED',
        message: error?.message || String(error)
      }
    })
    if (deferCommandPersistence) failedRecord.catch((historyError) => console.warn('[admin-eject] failure history deferred:', historyError))
    else await failedRecord
    if (immediateAdminTake) logAdminTakeTiming(slotNumber, startedAt, 'failed:' + (error?.code || 'UNKNOWN'))
    if (parent.deferOperationEffect !== true) {
      publishCabinetOperationEffect(slotNumber, 'failure', operationId, error?.message || String(error))
    }
    throw error
  }
}

async function executeLocalAdminTakeCard(slotNumber, permissionKey = 'maintenance.serial.admin-take') {
  assertLocalPermission(permissionKey)
  const address = Number(slotNumber)
  const operationId = 'adminTake:' + address + ':' + Date.now()
  const operatorName = appState.session?.credentialLabel ||
    appState.session?.roleLabels?.join('、') ||
    '本机管理员'
  const parent = {
    operationId,
    operationType: 'ADMIN_TAKE_CARD',
    slotOperationType: 'ADMIN_TAKE_CARD',
    operatorName,
    source: 'LOCAL_ADMIN',
    singleOperation: true,
    deferOperationEffect: true,
    openDoorOnly: true
  }

  let result
  try {
    const simulator = await requireConnectedSerialSimulator()
    if (!Number.isInteger(address) || address < 1 || address > simulator.totalSlots) {
      throw operationError('INVALID_SLOT', '卡槽号无效，未执行管理员取卡')
    }
    result = await executeAdminEjectSlot({ slotNumber: address }, parent)
  } catch (error) {
    let statusReport = null
    if (error?.doorOpened === true) {
      statusReport = error.statusReport || await reportDeviceStatusImmediately('admin-eject-recovery')
      error.statusReport = statusReport
    }
    publishCabinetOperationEffect(address, 'failure', operationId, error?.message || String(error))
    throw error
  }

  const message = result.reportPending
    ? address + '号卡槽已开卡，卡柜状态等待后台同步'
    : address + '号卡槽已开卡'
  publishCabinetOperationEffect(address, 'success', operationId, message)
  return {
    ...result,
    stage: result.reportPending ? 'REPORT_PENDING' : 'COMPLETED',
    message
  }
}

async function executeAdminEjectAllDoors(options = {}) {
  if (options.requireLocalPermission === true) assertLocalPermission('maintenance.cabinet.eject-all')
  const simulator = await requireConnectedSerialSimulator()
  const operationId = options.operationId || ('unlockAll:' + Date.now())
  const operationType = options.operationType || 'UNLOCK_ALL'
  const operatorName = options.operatorName ||
    appState.session?.credentialLabel ||
    appState.session?.roleLabels?.join('、') ||
    '本机管理员'
  const parent = {
    operationId,
    operationType,
    operatorName,
    requestMsgId: options.requestMsgId || '',
    source: options.source || (options.requestMsgId ? 'REMOTE_ADMIN' : 'LOCAL_ADMIN'),
    openDoorOnly: true,
    deferPersistence: options.deferPersistence === true,
    ttsFlush: false
  }
  const received = recordOperationHistory({
    ...parent,
    state: 'RECEIVED',
    requestedCount: 0,
    inspectedCount: simulator.totalSlots
  })
  if (parent.deferPersistence) {
    received.catch((error) => console.warn('[admin-eject] batch received history deferred:', error))
  } else if (!await received) {
    throw operationError('OPERATION_PERSIST_FAILED', '操作记录初始化失败，未执行一键弹卡')
  }

  let freshSlots = []
  try {
    freshSlots = await readSimulatorSlotsSnapshot()
  } catch (error) {
    console.warn('[admin-eject] Android 自动轮询快照读取失败:', error)
  }
  const plan = planAdminEjectAll(freshSlots, simulator.totalSlots)
  const failures = [...plan.failures]
  const requestedCount = plan.targetCount + plan.failures.length
  plan.failures.forEach((failure) => {
    publishCabinetOperationEffect(
      failure.slotNumber,
      'failure',
      operationId,
      failure.message
    )
  })
  if (plan.targetCount === 0 && failures.length === 0) {
    failures.push({
      slotNumber: 0,
      code: 'NO_TAKEABLE_CARD',
      message: '当前没有可弹出的工卡'
    })
  }
  const validatedParent = recordOperationHistory({
    ...parent,
    state: 'VALIDATED',
    requestedCount,
    inspectedCount: simulator.totalSlots,
    targetCount: plan.targetCount,
    emptyCount: plan.emptyCount,
    targetAddresses: plan.targets.map((target) => target.slotNumber),
    validationFailures: plan.failures
  })
  if (parent.deferPersistence) {
    validatedParent.catch((error) => console.warn('[admin-eject] batch validation history deferred:', error))
  } else {
    await validatedParent
  }

  let successCount = 0
  let doorOpenedCount = 0
  let physicalConfirmedCount = 0
  let pendingTakeCount = 0
  let statusReportSentCount = 0
  let statusReportPendingCount = 0
  for (const target of plan.targets) {
    try {
      const result = await executeAdminEjectSlot(target, parent)
      if (result.accepted) {
        doorOpenedCount += 1
        successCount += 1
        if (result.reportPending) statusReportPendingCount += 1
        else statusReportSentCount += 1
      }
    } catch (error) {
      if (error?.doorOpened === true) {
        doorOpenedCount += 1
        if (error?.physicalConfirmed === true) {
          physicalConfirmedCount += 1
          successCount += 1
          if (error?.statusReport?.sent === true) statusReportSentCount += 1
          else statusReportPendingCount += 1
        } else {
          pendingTakeCount += 1
        }
        continue
      }
      failures.push({
        slotNumber: target.slotNumber,
        code: error?.code || 'ADMIN_EJECT_FAILED',
        message: error?.message || String(error)
      })
    }
  }

  const failedCount = failures.length
  const accepted = plan.targetCount > 0 && successCount === plan.targetCount && failedCount === 0
  const state = failedCount > 0
    ? (successCount > 0 ? 'PARTIAL' : 'FAILED')
    : (accepted ? 'COMPLETED' : 'FAILED')
  const message = '已开卡' + successCount + '张工卡'
    + (failedCount > 0 ? '，' + failedCount + '项未完成' : '')
  const completedParent = recordOperationHistory({
    ...parent,
    state,
    requestedCount,
    inspectedCount: simulator.totalSlots,
    targetCount: plan.targetCount,
    emptyCount: plan.emptyCount,
    doorOpenedCount,
    physicalConfirmedCount,
    pendingTakeCount,
    successCount,
    statusReportSentCount,
    statusReportPendingCount,
    failedCount,
    failures
  })
  if (parent.deferPersistence) {
    completedParent.catch((error) => console.warn('[admin-eject] batch completion history deferred:', error))
  } else {
    await completedParent
  }
  return {
    sent: doorOpenedCount > 0,
    accepted,
    stage: state,
    requestedCount,
    inspectedCount: simulator.totalSlots,
    targetCount: plan.targetCount,
    emptyCount: plan.emptyCount,
    doorOpenedCount,
    physicalConfirmedCount,
    pendingTakeCount,
    successCount,
    statusReportSentCount,
    statusReportPendingCount,
    failedCount,
    failures,
    message
  }
}

function startRemoteEjectAllDoors(options = {}) {
  Promise.resolve()
    .then(() => executeAdminEjectAllDoors({
      ...options,
      source: 'REMOTE_ADMIN',
      deferPersistence: true
    }))
    .catch((error) => {
      console.warn('[remote-eject-all] background execution failed:', error)
      recordOperationHistory({
        operationId: options.operationId,
        operationType: options.operationType || 'REMOTE_EJECT_ALL',
        operatorName: options.operatorName || '后台',
        requestMsgId: options.requestMsgId || '',
        source: 'REMOTE_ADMIN',
        state: String(error?.code || '').endsWith('TIMEOUT') ? 'TIMED_OUT' : 'FAILED',
        rawError: { code: error?.code || 'REMOTE_EJECT_ALL_FAILED', message: error?.message || String(error) }
      }).catch((historyError) => console.warn('[remote-eject-all] failure history deferred:', historyError))
      reportDeviceStatusImmediately('remote-eject-all-failed')
        .catch((reportError) => console.warn('[remote-eject-all] failure status report deferred:', reportError))
    })
  return {
    accepted: true,
    queued: true,
    message: '已受理，正在逐槽开门'
  }
}

async function unlockDoor(slotNumber) {
  return executeLocalAdminTakeCard(slotNumber, 'realtime.slot.open')
}

/** 获取历史 */
async function getHistory(limit = 50) {
  assertLocalPermission('system.history.view')
  try {
    await initializeLocalStore()
    const history = await localStore.listOperationHistory(limit)
    return replaceHistoryProjection(history)
  } catch (error) {
    console.warn('getHistory failed:', error)
    const historyError = new Error('历史记录读取失败，请重试')
    historyError.code = 'HISTORY_READ_FAILED'
    throw historyError
  }
}

/** 获取运行时信息 */
async function getRuntime(options = {}) {
  if (options.requireCommunicationPermission === true) {
    assertLocalPermission('realtime.communication.refresh')
  }
  const [mqttResult, deviceInfoResult, authResult] = await Promise.allSettled([
    mqttLoginStatus(),
    bootstrapDeviceInfo(),
    httpGet('/api/v1/device/auth/status')
  ])
  const mqttConnected = mqttResult.status === 'fulfilled' && isMqttBusinessReady(mqttResult.value)
  const nativeInfo = deviceInfoResult.status === 'fulfilled' ? unwrapResponsePayload(deviceInfoResult.value) : {}
  const deviceInfo = normalizeDeviceInfo(nativeInfo, mqttConnected)
  const authorizationPayload = authResult.status === 'fulfilled'
    ? unwrapResponsePayload(authResult.value)
    : null
  const authorizationData = authorizationPayload?.data && typeof authorizationPayload.data === 'object'
    ? authorizationPayload.data
    : authorizationPayload
  const authorizationChecked = typeof authorizationData?.authorized === 'boolean'
  const authorizationError = authResult.status === 'rejected'
    ? (authResult.reason?.message || String(authResult.reason || '授权状态读取失败'))
    : (authorizationChecked ? '' : '授权接口未返回有效状态')
  const authorization = normalizeDeviceAuthorization(
    authResult.status === 'fulfilled' ? authResult.value : null,
    {
      activated: deviceInfo.activated,
      state: appState.runtime?.deviceAuthorization?.state,
      message: appState.runtime?.deviceAuthorization?.message,
      authorized: appState.runtime?.deviceAuthorization?.authorized,
      authorizedUntil: appState.runtime?.deviceAuthorization?.authorizedUntil,
      daysRemaining: appState.runtime?.deviceAuthorization?.daysRemaining,
      features: appState.runtime?.deviceAuthorization?.features
    }
  )
  replaceDeviceInfoProjection(deviceInfo)
  appState.runtime.socket = {
    ...(appState.runtime.socket || {}),
    state: mqttConnected ? 'CONNECTED' : 'DISCONNECTED',
    message: mqttConnected ? '后端通信已连接' : '后端通信未连接'
  }
  appState.runtime.deviceAuthorization = authorization
  return {
    mqttConnected,
    deviceInfo,
    deviceAuthorization: authorization,
    authorizationChecked,
    authorizationError,
    timestamp: Date.now()
  }
}

/** 获取串口状态 */
async function getSerialStatus() {
  const [statusResult, logsResult] = await Promise.allSettled([
    serialStatus(),
    serialGetLogs(1)
  ])
  const status = statusResult.status === 'fulfilled' ? statusResult.value : null
  const result = logsResult.status === 'fulfilled' ? logsResult.value : null
  const logs = Array.isArray(result) ? result : (Array.isArray(result?.logs) ? result.logs : [])
  const projected = appState.runtime.serial || {}
  return {
    ...projected,
    ...(status || {}),
    state: status?.state || projected.state || 'UNKNOWN',
    message: status?.message || projected.message || '当前原生桥未返回串口连接状态',
    logAvailable: true,
    lastLog: logs.length > 0 ? logs[logs.length - 1] : null
  }
}

/** 保存设置 */
async function saveSettings(settings) {
  if (appState.session) assertLocalPermission('system.settings.edit')
  await cacheInitialAdminPassword(settings || {}, 'CONFIG_SAVE').catch((error) => {
    console.warn('cache initial admin password from saveSettings failed:', error)
  })
  const normalized = normalizeDeviceConfig(stripInitialAdminPassword(settings))
  try {
    const remote = await httpPost('/api/v1/device/config', pickDeviceConfigPayload(normalized))
    assertHttpSuccess(remote, '保存设备配置')
    const envelope = remote?.body || remote
    assertBackendSuccess(envelope, '保存设备配置', { requireCode: true })
    const remoteData = envelope?.data && typeof envelope.data === 'object' ? envelope.data : null
    const confirmed = assertSavedSlotLayout(normalized, remoteData)
    const saved = normalizeDeviceConfig({ ...normalized, ...confirmed })
    const currentRuntime = await localStore.loadRuntimeConfig().catch(() => null)
    const nativeRuntimeChanges = currentRuntime && typeof currentRuntime === 'object'
      ? findChangedNativeRuntimeConfigFields(normalizeDeviceConfig(currentRuntime), saved)
      : []
    await initializeLocalStore()
    await Promise.all([
      localStore.saveRuntimeConfig(saved),
      localStore.saveConfigDraft(saved),
      localStore.saveBootstrapConfig(bootstrapConfigFromSettings(saved))
    ])
    replaceSettingsProjection(saved)
    // 摄像头参数变更时实时推送到 Android 原生层
    if ('cameraFacing' in saved || 'cameraMirror' in saved || 'cameraRotation' in saved
          || 'cameraFrameWidth' in saved || 'cameraFrameHeight' in saved) {
      nativeBridge.isAvailable() && nativeBridge.request('face.camera.config', {
        cameraFacing: saved.cameraFacing || 'front',
        cameraMirror: Boolean(saved.cameraMirror),
        cameraRotation: Number(saved.cameraRotation || 0),
        cameraFrameWidth: Number(saved.cameraFrameWidth || 640),
        cameraFrameHeight: Number(saved.cameraFrameHeight || 480)
      }).catch(() => {})
    }
    return {
      localSaved: true,
      remoteSaved: true,
      remote,
      data: saved,
      restartRequired: nativeRuntimeChanges.length > 0,
      nativeRuntimeChanges
    }
  } catch (error) {
    console.warn('saveSettings remote failed, local saved:', error)
    return {
      localSaved: false,
      remoteSaved: false,
      error: error?.message || String(error),
      errorCode: error?.code || '',
      data: normalized
    }
  }
}

/**
 * 保存用户输入的启动配置（仅 serverUrl）。首次启动时由 splash.vue 调用。
 */
async function saveBootstrapConfig(value) {
  const config = value && typeof value === 'object'
    ? value
    : { serverUrl: value }
  await localStore.saveBootstrapConfig({
    serverUrl: normalizeBootstrapServerUrl(config.serverUrl)
  })
}

/**
 * 加载缓存的启动配置（仅 serverUrl，不含 MQTT 等参数）。
 * 首次启动无缓存时返回 null，由调用方（splash.vue）弹出服务器地址输入 UI。
 */
async function loadBootstrapConfig() {
  const local = await localStore.loadBootstrapConfig().catch((error) => {
    console.warn('loadBootstrapConfig failed, no cached config:', error)
    return null
  })
  if (!local) return null
  const normalized = bootstrapConfigFromSettings(local)
  if (normalized.serverUrl !== String(local.serverUrl || '').trim()) {
    await localStore.saveBootstrapConfig(normalized)
  }
  return normalized
}

async function loadCachedSlots() {
  return localStore.loadSlotsSnapshot().catch((error) => {
    console.warn('loadCachedSlots failed:', error)
    return []
  })
}

function queueSlotSnapshot(slot, source = 'SERIAL', fresh = true) {
  const slotNumber = slotNumberOf(slot)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return false
  const signature = slotCacheSignature(slot, source, fresh)
  const persisted = persistedSlotCache.get(slotNumber)
  if (persisted?.signature === signature && Date.now() - persisted.savedAt < SLOT_CACHE_MAX_SILENCE_MS) {
    return false
  }
  pendingSlotCache.set(slotNumber, { slot, source, fresh, signature })
  if (!slotCacheFlushTimer && !slotCacheFlushPromise) {
    slotCacheFlushTimer = setTimeout(flushPendingSlotCache, SLOT_CACHE_FLUSH_DELAY_MS)
  }
  return true
}

function cacheSlotSnapshot(slot, source = 'SERIAL', fresh = true) {
  if (!queueSlotSnapshot(slot, source, fresh)) return Promise.resolve(null)
  return new Promise((resolve) => slotCacheWaiters.push(resolve))
}

function slotCacheSignature(slot = {}, source, fresh) {
  return JSON.stringify({
    source,
    fresh: Boolean(fresh),
    status: slot.status,
    cardNo: slot.cardNo ?? slot.cardNumber ?? slot.cardId,
    cardCode: slot.cardCode,
    workCode: slot.workCode,
    doorCode: slot.doorCode,
    faultCode: slot.faultCode
  })
}

async function flushPendingSlotCache() {
  slotCacheFlushTimer = null
  if (slotCacheFlushPromise || !pendingSlotCache.size) return
  const pending = Array.from(pendingSlotCache.values())
  const waiters = slotCacheWaiters
  pendingSlotCache.clear()
  slotCacheWaiters = []
  slotCacheFlushPromise = (async () => {
    const batches = new Map()
    pending.forEach((item) => {
      const key = `${item.source}:${item.fresh ? 1 : 0}`
      const batch = batches.get(key) || { source: item.source, fresh: item.fresh, slots: [], items: [] }
      batch.slots.push(item.slot)
      batch.items.push(item)
      batches.set(key, batch)
    })
    for (const batch of batches.values()) {
      await localStore.saveSlotsSnapshot(batch.slots, batch.source, batch.fresh)
      const savedAt = Date.now()
      batch.items.forEach((item) => {
        persistedSlotCache.set(slotNumberOf(item.slot), { signature: item.signature, savedAt })
      })
    }
  })().catch((error) => {
    console.warn('cacheSlotSnapshot failed:', error)
    return null
  }).finally(() => {
    slotCacheFlushPromise = null
    waiters.forEach((resolve) => resolve(null))
    if (pendingSlotCache.size && !slotCacheFlushTimer) {
      slotCacheFlushTimer = setTimeout(flushPendingSlotCache, SLOT_CACHE_FLUSH_DELAY_MS)
    }
  })
  await slotCacheFlushPromise
}

async function cacheSlotsSnapshot(slots, source = 'LOCAL_OPERATION', fresh = false) {
  queueSlotsSnapshot(slots, source, fresh)
}

function queueSlotsSnapshot(slots, source = 'LOCAL_OPERATION', fresh = false) {
  const items = Array.isArray(slots) ? slots : []
  items.forEach((slot) => queueSlotSnapshot(slot, source, fresh))
}

async function recordOperationHistory(operation = {}) {
  try {
    await initializeLocalStore()
    return await localStore.saveOperationRecord(operation)
  } catch (error) {
    console.warn('recordOperationHistory failed:', error)
    return null
  }
}

/** totalSlots 缩小后清理超出范围的 slots_snapshot 缓存 */
async function trimStaleSlots(maxSlot) {
  console.log('[trimStaleSlots] deleting slots_snapshot rows above slot', maxSlot)
  return localStore.deleteSlotsSnapshotAbove(maxSlot).catch((error) => {
    console.warn('trimStaleSlots failed:', error)
    return 0
  })
}

/** 保存密码（Web Crypto API 加密后写入 SQLite） */
async function savePassword(role, password) {
  assertLocalPermission('account.secondary-password.change')
  if (!role || !password) {
    throw new Error('角色和密码不能为空')
  }
  try {
    const enc = new TextEncoder()
    // 从设备标识派生加密密钥
    const deviceTag = appState.deviceInfo?.deviceId
      || (typeof appState.getDeviceId === 'function' ? appState.getDeviceId() : '')
      || 'default-device'
    const keyMaterial = await crypto.subtle.importKey(
      'raw', enc.encode(deviceTag),
      { name: 'PBKDF2' }, false, ['deriveKey']
    )
    // 生成随机盐和 IV
    const salt = crypto.getRandomValues(new Uint8Array(16))
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const key = await crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt, iterations: 100000, hash: 'SHA-256' },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false, ['encrypt']
    )
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      key,
      enc.encode(password)
    )
    const payload = {
      role,
      salt: btoa(String.fromCharCode(...salt)),
      iv: btoa(String.fromCharCode(...iv)),
      data: btoa(String.fromCharCode(...new Uint8Array(ciphertext)))
    }
    await storageExecute(
      'INSERT OR REPLACE INTO device_settings (key, value) VALUES (?, ?)',
      [`auth.password.${role}`, JSON.stringify(payload)]
    )
    return { saved: true, role }
  } catch (error) {
    if (error.code && error.supported === false) throw error
    throw new Error(`密码加密保存失败：${error.message}`)
  }
}

/** 重启 App */
function validateRestartScheduleResult(result, operationId) {
  if (result?.status !== 'SCHEDULED') {
    throw new Error('原生未确认应用重启已安排')
  }
  if (String(result.operationId || '') !== operationId) {
    throw new Error('应用重启操作编号不匹配')
  }
  return result
}

function writeMockRestartStatus(status) {
  if (typeof window === 'undefined' || !window.sessionStorage) {
    throw new Error('浏览器 Mock 无法保存重启状态')
  }
  window.sessionStorage.setItem(MOCK_APP_RESTART_STORAGE_KEY, JSON.stringify(status))
}

function readMockRestartStatus() {
  if (typeof window === 'undefined' || !window.sessionStorage) return null
  const raw = window.sessionStorage.getItem(MOCK_APP_RESTART_STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (error) {
    window.sessionStorage.removeItem(MOCK_APP_RESTART_STORAGE_KEY)
    return null
  }
}

function scheduleMockAppRestart({ operationId, delayMs }) {
  if (typeof window === 'undefined' || typeof window.location?.reload !== 'function') {
    throw new Error('浏览器 Mock 无法执行页面重启')
  }
  const active = readMockRestartStatus()
  if (active?.status === 'SCHEDULED' && active.operationId !== operationId) {
    throw new Error('另一个应用重启任务正在等待执行')
  }
  const scheduled = {
    operationId,
    status: 'SCHEDULED',
    requestedAt: Date.now(),
    executedAt: 0,
    effectiveDelayMs: delayMs,
    mock: true
  }
  writeMockRestartStatus(scheduled)
  armMockAppRestart(scheduled, delayMs)
  return scheduled
}

function armMockAppRestart(scheduled, delayMs) {
  if (mockAppRestartTimer) clearTimeout(mockAppRestartTimer)
  mockAppRestartTimer = setTimeout(() => {
    mockAppRestartTimer = null
    writeMockRestartStatus({ ...scheduled, status: 'EXECUTED', executedAt: Date.now() })
    window.location.reload()
  }, delayMs)
}

async function scheduleAppRestart({ operationId, delayMs }) {
  const result = isMockDev
    ? scheduleMockAppRestart({ operationId, delayMs })
    : await nativeBridge.request('app.restart', { operationId, delayMs })
  return validateRestartScheduleResult(result, operationId)
}

async function restartApp(options = {}) {
  assertLocalPermission('system.restart')
  const delayMs = Number.isInteger(Number(options.delayMs)) && Number(options.delayMs) >= 0
    ? Number(options.delayMs)
    : 3000
  const operationId = `restartApp:local:${Date.now()}`
  await initializeLocalStore()
  await localStore.saveOperationRecord({
    operationId,
    operationType: 'RESTART_APP',
    operatorName: appState.session?.username || '本机管理员',
    state: 'RECEIVED',
    delayMs
  })
  try {
    const nativeResult = await scheduleAppRestart({ operationId, delayMs })
    await localStore.saveOperationRecord({
      operationId,
      operationType: 'RESTART_APP',
      operatorName: appState.session?.username || '本机管理员',
      state: 'RESTART_SCHEDULED',
      delayMs,
      nativeResult
    })
    return { operationId, delayMs, nativeResult }
  } catch (error) {
    await localStore.saveOperationRecord({
      operationId,
      operationType: 'RESTART_APP',
      operatorName: appState.session?.username || '本机管理员',
      state: 'FAILED',
      delayMs,
      rawError: { message: error?.message || String(error) }
    })
    throw error
  }
}

/** 本机管理员一键弹卡 */
async function unlockAllDoors() {
  return executeAdminEjectAllDoors({ requireLocalPermission: true, deferPersistence: true })
}

let appRestartRecoveryPromise = null

async function completeRecoveredAppRestart(status) {
  const operationId = String(status.operationId)
  await initializeLocalStore()
  await localStore.saveOperationRecord({
    operationId,
    operationType: 'RESTART_APP',
    state: 'COMPLETED',
    executedAt: Number(status.executedAt || Date.now()),
    finishedAt: Date.now()
  })

  const remotePrefix = 'restartApp:remote:'
  if (operationId.startsWith(remotePrefix)) {
    const msgId = operationId.slice(remotePrefix.length)
    const existing = await localStore.getOutboxEvent(mqttResponseEventId('restartAppResp', msgId))
    if (existing?.state !== 'SENT') {
      await sendMqttResponseWithOutbox('restartAppResp', { ...RESTART_APP_SUCCESS_RESPONSE }, msgId)
    }
  }
  return { recovered: true, operationId }
}

async function recoverPendingAppRestart() {
  if (appRestartRecoveryPromise) return appRestartRecoveryPromise
  appRestartRecoveryPromise = (async () => {
    if (isMockDev) {
      const status = readMockRestartStatus()
      if (status?.status === 'SCHEDULED' && status?.operationId) {
        const dueAt = Number(status.requestedAt || Date.now()) + Number(status.effectiveDelayMs || 0)
        armMockAppRestart(status, Math.max(0, dueAt - Date.now()))
        return { recovered: false, status: 'SCHEDULED' }
      }
      if (status?.status !== 'EXECUTED' || !status?.operationId) {
        return { recovered: false, status: status?.status || 'NONE' }
      }
      const result = await completeRecoveredAppRestart(status)
      window.sessionStorage.removeItem(MOCK_APP_RESTART_STORAGE_KEY)
      return result
    }
    if (!nativeBridge.isChannelReady()) return { recovered: false, reason: 'CHANNEL_NOT_READY' }
    const status = await nativeBridge.request('app.restartStatus', { clearExecuted: false })
    if (status?.status !== 'EXECUTED' || !status?.operationId) {
      return { recovered: false, status: status?.status || 'NONE' }
    }
    const result = await completeRecoveredAppRestart(status)
    await nativeBridge.request('app.restartStatus', { clearExecuted: true })
    return result
  })()
  try {
    return await appRestartRecoveryPromise
  } finally {
    appRestartRecoveryPromise = null
  }
}

/** 重连串口 */
async function reconnectSerial() {
  assertLocalPermission('maintenance.serial.config')
  return nativeBridge.request('serial.reconnect', {})
}

/** 断开串口但保留原生串口服务，后续仍可重新连接。 */
async function disconnectSerial() {
  assertLocalPermission('maintenance.serial.config')
  return nativeBridge.request('serial.disconnect', {})
}

/** 串口调试台按明确卡槽号调用已有开门能力，不在页面重建协议帧。 */
async function openSerialDoor(slotNumber, administrator = false) {
  assertLocalPermission(administrator ? 'maintenance.serial.admin-take' : 'maintenance.serial.normal-take')
  const capability = await requireConnectedSerialSimulator()
  const address = Number(slotNumber)
  if (!Number.isInteger(address) || address < 1 || address > capability.totalSlots) {
    throw operationError('INVALID_SLOT', '卡槽号无效，无法执行开门')
  }
  return dispatchDoorCommand({
    operationId: `serialDebug:${address}:${Date.now()}`,
    slotNumber: address,
    commandMode: administrator === true
      ? DOOR_COMMAND_MODE.ADMIN_TAKE
      : DOOR_COMMAND_MODE.EMPLOYEE_ISSUE,
    requiresBoardAck: true
  })
}

/** 串口调试台按 V1.5 已有 LED 能力调整占空比。 */
async function setSerialLedDutyCycle(slotNumber, dutyCycle) {
  assertLocalPermission('maintenance.serial.led')
  const capability = await requireConnectedSerialSimulator()
  const address = Number(slotNumber)
  const normalizedDutyCycle = Number(dutyCycle)
  if (!Number.isInteger(address) || address < 1 || address > capability.totalSlots) {
    throw operationError('INVALID_SLOT', '卡槽号无效，无法调整 LED')
  }
  if (!Number.isInteger(normalizedDutyCycle) || normalizedDutyCycle < 30 || normalizedDutyCycle > 100) {
    throw operationError('INVALID_LED_DUTY', 'LED 占空比必须在 30 到 100 之间')
  }
  return nativeBridge.request('serial.setLedDutyCycle', {
    slotNumber: address,
    dutyCycle: normalizedDutyCycle
  })
}

/** 发送串口指令 */
async function sendSerial(hexOrData, mode = 'HEX') {
  assertLocalPermission('maintenance.serial.manual-command')
  if (String(mode || '').toUpperCase() !== 'HEX') {
    return unsupportedClientFeature('SERIAL_TEXT_MODE_NOT_EXPOSED', 'JsBridgeV2 串口发送仅支持 HEX')
  }
  return serialSend(hexOrData)
}

/** 设置串口调试日志 */
async function setSerialDebugLogging(enabled) {
  assertLocalPermission('maintenance.serial.manual-command')
  return unsupportedClientFeature('SERIAL_DEBUG_TOGGLE_NOT_EXPOSED', 'JsBridgeV2 未提供串口调试日志开关')
}

/** 设置串口轮询 */
async function setSerialPolling(enabled) {
  assertLocalPermission('maintenance.serial.config')
  const simulator = await getSerialSimulatorCapability()
  if (!simulator && nativeBridge.isAvailable()) {
    return unsupportedClientFeature(
      'SERIAL_TOPOLOGY_UNCONFIRMED',
      '真实串口卡槽地址拓扑尚未确认，已阻止自动轮询变更'
    )
  }
  return nativeBridge.request('serial.setPolling', { enabled })
}

/** 列出串口 */
async function listSerialPorts() {
  assertLocalPermission('maintenance.serial.config')
  return nativeBridge.request('serial.listPorts', {})
}

let takeCardWorkflow = null
let cardEventRetryScheduler = null

async function sendFaceRecord(recordRequest) {
  const mode = normalizeCommunicationMode(
    appState.settings.communicationMode || appState.settings.backendTransport
  )
  if (mode !== 'HTTP') {
    const status = await mqttLoginStatus().catch(() => ({ connected: false }))
    if (isMqttBusinessReady(status)) {
      const msgId = [
        'face',
        Number(recordRequest.employeeId),
        Date.now()
      ].join('_')
      await registerMqttBusinessHandlers({ reason: 'face-register' })
      const result = await sendMqttAndWaitForResponse('faceRegister', recordRequest, 'faceRegisterResp', msgId)
      assertBackendSuccess(result?.response?.data, 'faceRegister', { requireCode: true })
      return result?.response?.data
    }
  }
  return httpPost(FACE_RECORD_CREATE_PATH, recordRequest)
}

async function sendCardEventToBackend(cardEvent) {
  const mode = normalizeCommunicationMode(
    appState.settings.communicationMode || appState.settings.backendTransport
  )
  if (mode !== 'HTTP') {
    const status = await mqttLoginStatus().catch(() => ({ connected: false }))
    if (isMqttBusinessReady(status)) {
      const msgId = [
        'card',
        Number(cardEvent.timestamp),
        Number(cardEvent.employeeId || 0),
        String(cardEvent.cardNo || '')
      ].join('_')
      await registerMqttBusinessHandlers({ reason: 'card-event' })
      const result = await sendMqttAndWaitForResponse('cardEvent', cardEvent, 'cardEventResp', msgId)
      assertBackendSuccess(result?.response?.data, 'cardEvent', { requireCode: true })
      return { sent: true, transport: 'MQTT', msgId, response: result.response }
    }
  }
  const response = await httpPost('/api/v1/card/event', cardEvent)
  assertHttpSuccess(response, 'cardEvent')
  assertBackendSuccess(response?.body || response, 'cardEvent', { requireCode: true })
  return { sent: true, transport: 'HTTP', response }
}

function getTakeCardWorkflow() {
  if (takeCardWorkflow) return takeCardWorkflow
  takeCardWorkflow = createTakeCardWorkflow({
    selectTakeCardSlot: async (options = {}) => {
      const simulator = await getSerialSimulatorCapability()
      let slots
      if (simulator) {
        try {
          slots = slotsFromSerialSnapshot(await serialSlotsSnapshot())
        } catch (error) {
          console.error('[takeCard] 实时卡槽快照读取失败', error)
          return {
            ok: false,
            reason: 'SERIAL_SNAPSHOT_UNAVAILABLE',
            causeCode: error?.code || 'SERIAL_SNAPSHOT_ERROR'
          }
        }
        slots.forEach((slot) => upsertSlotProjection(slot))
      } else {
        slots = options?.requireFresh === false
          ? await localStore.loadSlotsSnapshot()
          : await localStore.listFreshSlotsSnapshot()
      }
      const pollInterval = Math.max(1000, Number(appState.settings.serialPollInterval || 5000))
      const maxAgeMs = computeTakeCardSlotMaxAgeMs({
        configuredPollIntervalMs: pollInterval,
        pollingIntervalMs: simulator?.pollingIntervalMs,
        totalSlots: simulator?.totalSlots,
        responseTimeoutMs: simulator?.responseTimeoutMs
      })
      const election = selectTakeCardCandidate({ slots, maxAgeMs })
      if (!election.ok) return election
      return {
        ...election,
        election: {
          condition: election.condition,
          conditionTip: election.conditionTip,
          candidateCount: election.candidateCount,
          voltage: election.voltage,
          batteryPercent: election.batteryPercent
        }
      }
    },
    saveOperation: (...args) => localStore.saveOperationRecord(...args),
    getOperation: (...args) => localStore.getOperationRecord(...args),
    listRecoverableOperations: (...args) => localStore.listRecoverableOperations(...args),
    saveOutbox: (...args) => localStore.upsertOutboxEvent(...args),
    getOutbox: (...args) => localStore.getOutboxEvent(...args),
    listDueOutbox: (...args) => localStore.listDueOutboxEvents(...args),
    markOutboxSent: (...args) => localStore.markOutboxEventSent(...args),
    markOutboxFailed: (...args) => localStore.markOutboxEventFailed(...args),
    // 人脸仅保留自身物理确认；开门写入、实际 TX 与 0x11/0x12 由统一调度器处理。
    sendOpenDoor: ({ operationId, slotNumber, requiresBoardAck, ackTimeoutMs }) => dispatchDoorCommand({
      operationId,
      slotNumber,
      commandMode: DOOR_COMMAND_MODE.EMPLOYEE_ISSUE,
      requiresBoardAck,
      txTimeoutMs: ackTimeoutMs,
      ackTimeoutMs
    }),
    queryTargetSlot: (slotNumber) => serialQuerySlot(slotNumber),
    subscribe: (eventName, callback) => on(eventName, callback),
    getSettings: () => appState.settings,
    canDispatch: async () => {
      if (!nativeBridge.isAvailable()) return true

      let serialCapability
      try {
        serialCapability = await serialStatus()
      } catch (error) {
        console.error('[takeCard] serial.status 查询失败', error)
        return {
          allowed: false,
          code: 'SERIAL_STATUS_ERROR',
          message: '无法获取串口状态，暂无法发送取卡指令'
        }
      }

      const state = String(serialCapability?.state || '').trim().toUpperCase()
      if (state !== 'CONNECTED') {
        return {
          allowed: false,
          code: 'SERIAL_CHANNEL_NOT_READY',
          message: `串口状态为 ${state || 'UNKNOWN'}，暂无法发送取卡指令。请确认打卡机已连接并上电。`
        }
      }

      if (serialCapability?.simulator === true) {
        return {
          allowed: true,
          simulator: true,
          requiresBoardAck: false
        }
      }

      // 真实串口设备：必须等待开门板级 0x11/0x12，物理取卡确认由后续 slot.status 完成。
      return {
        allowed: true,
        simulator: false,
        requiresBoardAck: true
      }
    },
    reportCardEvent: sendCardEventToBackend,
    reportStatusAfterTake: ({ observedSlot }) => {
      return reportDeviceStatusImmediately('employee-take', observedSlot)
    }
  })
  return takeCardWorkflow
}

function getCardEventRetryScheduler() {
  if (cardEventRetryScheduler) return cardEventRetryScheduler
  cardEventRetryScheduler = createCardEventRetryScheduler({
    retryDelayMs: CARD_EVENT_RETRY_DELAY_MS,
    flush: async (limit) => {
      await initializeLocalStore()
      return getTakeCardWorkflow().flushPendingReports(limit)
    },
    onError: (error) => {
      console.warn('[cardEvent] scheduled outbox flush failed:', error)
    }
  })
  return cardEventRetryScheduler
}

/** 员工人脸取卡；真实设备在出站拓扑确认前保持阻塞，正式 Mock 使用文档化串口帧。 */
async function takeCard(identity, progressCallback) {
  await initializeLocalStore()
  const employeeId = String(identity?.employee?.employeeId ?? identity?.employeeId ?? '').trim()
  const employee = employeeId ? await localStore.getEmployeeById(employeeId) : null
  if (!employee) throw faceWorkflowError('EMPLOYEE_NOT_FOUND', '员工信息不存在，不能取卡')
  if (!employee.enabled) throw faceWorkflowError('EMPLOYEE_DISABLED', '员工已停用，不能取卡')
  const result = await getTakeCardWorkflow().take(identity, progressCallback)
  if (result?.reportPending === true) schedulePendingCardEventFlush('report-pending')
  return result
}

async function observeReturnCard(previousSlot, currentSlot) {
  await initializeLocalStore()
  const result = await getTakeCardWorkflow().observeReturn(previousSlot, currentSlot)
  if (result?.reportPending === true) schedulePendingCardEventFlush('return-report-pending')
  return result
}

async function flushPendingCardEvents(limit = 20, reason = 'manual') {
  return getCardEventRetryScheduler().flushNow(reason, limit)
}

async function loadLogUploadPolicy() {
  await initializeLocalStore()
  return localStore.loadLogUploadPolicy()
}

function schedulePendingCardEventFlush(reason = 'retry') {
  return getCardEventRetryScheduler().schedule(reason)
}

/** 还卡 */
async function returnCard(address) {
  assertLocalPermission('maintenance.serial.normal-take')
  return unsupportedClientFeature('SERIAL_COMMAND_UNCONFIRMED', '还卡串口命令未在 V2 客户端闭环中确认')
}

/** 查询卡槽 */
async function querySlot(address) {
  assertLocalPermission('maintenance.serial.read-status')
  const capability = await requireConnectedSerialSimulator()
  const slotNumber = Number(address)
  if (!Number.isInteger(slotNumber) || slotNumber < 1 || slotNumber > capability.totalSlots) {
    throw operationError('INVALID_SLOT', '卡槽号无效，无法查询')
  }
  return nativeBridge.request('serial.querySlot', { slotNumber })
}

async function sendFaceImage(uploadRequest) {
  return httpMultipart(FACE_IMAGE_UPLOAD_PATH, uploadRequest.fields, uploadRequest.file)
}

/** 读板版本 */
async function readBoardVersion(address) {
  assertLocalPermission('maintenance.serial.read-version')
  const capability = await requireConnectedSerialSimulator()
  const slotNumber = Number(address)
  if (!Number.isInteger(slotNumber) || slotNumber < 1 || slotNumber > capability.totalSlots) {
    throw operationError('INVALID_SLOT', '卡槽号无效，无法读取版本')
  }
  return nativeBridge.request('serial.readVersion', { slotNumber })
}

/** 设备激活 */
async function activateDevice(code) {
  return bootstrapActivate(code)
}

/** 设置变更监听 */
function onSettingsChanged(callback) {
  return on('mqtt.message', (data) => {
    if (data && data.cmd === 'syncConfig') {
      callback(data.data || data)
    }
  })
}

/** 后端状态变更监听 */
function onBackendStatusChanged(callback) {
  return on('mqtt.connected', (data) => {
    callback({ connected: true, data })
  })
}

// ══════════════════════════════════════════════
//  构建 service 对象 - 合并新旧 API
// ══════════════════════════════════════════════

function buildService(env) {
  return {
    name: env,
    destroy,
    env,

    // 新 6 通道 API
    bootstrap, bootstrapActivate, bootstrapRetry, bootstrapRefreshCode, bootstrapCancel, bootstrapDeviceInfo,
    offlineActivationStatus, offlineActivationActivate, offlineActivationLoadConfig,
    initializeLocalStore, clearLocalSessionOnStartup, loadBootstrapConfig, saveBootstrapConfig,
    cacheInitialAdminPassword, loadInitialAdminState,
    httpGet, httpPost, httpMultipart, httpDownload, httpGetAsync, httpPostAsync,
    mqttSend, mqttLoginStatus, refreshMqttConnectionProjection, mqttRegisterCmd, registerMqttBusinessHandlers, flushPendingMqttResponses,
    recoverPendingAppRestart, recoverPendingAppUpdate,
    getAppUpdateStatus, checkAppUpdate, downloadAppUpdate, installAppUpdate,
    loadLogUploadPolicy, reportHardwareFault, reportSlotHardwareFault, reportSelfCheck,
    getMqttSendQueueStats, notifyMqttConnected,
    startMqttCommLogCapture, getMqttCommLogs, clearMqttCommLogs,
    flushPendingDiagnosticEvents,
    flushPendingCardEvents, schedulePendingCardEventFlush, observeReturnCard,
    serialSend: serialSendFn, serialGetLogs, serialSubscribe, serialUnsubscribe,
    speakTts, announceTakeCardSuccess, announceTakeCardFailure, announceAdminCardOpened, announceAdminWelcome,
    storageQuery, storageExecute,
    loadCachedSlots, cacheSlotSnapshot, cacheSlotsSnapshot, queueSlotSnapshot, queueSlotsSnapshot, trimStaleSlots,
    buildStatusReportPayload, reportDeviceStatus, scheduleStatusReport, flushPendingStatusReports,
    faceRecognitionStart, faceRecognitionCancel, faceEnrollmentStart, faceEnrollmentCancel,
    faceTemplateImport, faceTemplateRemove, faceCount, syncFaceCameraConfig,
    on, off,
    waitForChannel: nativeBridge.waitForChannel,
    isChannelReady: nativeBridge.isChannelReady,

    // 旧业务兼容层
    init, loadSettings,
    login, logout, loginLocal, logoutLocal, refreshLocalSession,
    verifyAdminManageAccess, hasAdminManageSecondaryAccess, changeLocalPassword, changeSecondaryPassword,
    listLocalPermissions, saveLocalPermission, deleteLocalPermission,
    listLocalRoles, saveLocalRole, setLocalRoleEnabled, deleteLocalRole,
    listLocalCredentials, saveLocalCredential, deleteLocalCredential, unlockLocalCredential,
    runRecognition, cancelRecognition, registerBiometric, listEmployeeFaces,
    searchEmployees, getDepartmentTree, saveEmployee, setEmployeeAuthorization, deleteEmployee, syncEmployees, syncIdentityData,
    getUpgradeFiles, startUpgrade,
    unlockAllDoors, unlockDoor,
    getHistory, getRuntime,
    getSerialStatus, saveSettings, savePassword,
    restartApp,
    reconnectSerial, disconnectSerial, sendSerial,
    openSerialDoor, setSerialLedDutyCycle,
    setSerialDebugLogging, setSerialPolling,
    listSerialPorts,
    takeCard, returnCard, querySlot, readBoardVersion,
    activateDevice,
    onSettingsChanged, onBackendStatusChanged,
    // nativeBridge 直通
    nativeBridge: nativeBridge
  }
}

// 由于底部函数提升和模块导出要求，serialSend 在对象中作为别名
function serialSendFn(hex) { return serialSend(hex) }

// ── 导出 services 单例 ──

/** 构建 service 对象 */
const service = buildService(isRelease ? 'release' : 'mock')

// 无条件启动 MQTT 通信日志捕捉（幂等调用，不依赖 mqtt.connected 事件时序）
// 必须在 mqtt.connected 之前注册监听器，否则初始连接日志会被漏掉
service.startMqttCommLogCapture()

// SQLite 适配器：localStore 通过 JsBridge storage 通道 (storage.query / storage.execute) 读写 Android 端 SQLite
localStore.setAdapter({
  query: service.storageQuery,
  execute: service.storageExecute
})

// 如果是 mock 模式，覆盖为 mock 实现
if (isMockDev) {
  try {
    const mock = createMockService()
    // Mock 覆盖旧业务方法（mock 已实现这些方法）
    Object.keys(mock).forEach(key => {
      if (key !== 'name' && key !== 'env') {
        service[key] = mock[key]
      }
    })
  } catch (e) {
    console.warn('Mock service creation failed, using stubs', e)
  }
}

export const services = service
services.recordAuditEvent = recordAuditEvent
services.restoreLogUploadPolicyOnStartup = restoreLogUploadPolicyOnStartup
export { nativeBridge }

/** 销毁 */
function destroy() {
  unsubscribers.forEach(fn => { try { fn() } catch (e) {} })
  unsubscribers.length = 0
  if (mqttBusinessUnsubscribe) {
    try { mqttBusinessUnsubscribe() } catch (e) {}
    mqttBusinessUnsubscribe = null
  }
  mqttBusinessHandlersRegistered = false
  mqttBusinessRegistrationPromise = null
  if (mqttBusinessRegistrationRetryTimer) {
    clearTimeout(mqttBusinessRegistrationRetryTimer)
    mqttBusinessRegistrationRetryTimer = null
  }
  mqttBusinessRegistrationRetryCount = 0
  if (faceSyncIntervalTimer) {
    clearInterval(faceSyncIntervalTimer)
    faceSyncIntervalTimer = null
  }
  faceSyncPromise = null
  adminExitFaceSyncPromise = null
  mqttResponseFlushPromise = null
  if (mqttResponseFlushTimer) {
    clearTimeout(mqttResponseFlushTimer)
    mqttResponseFlushTimer = null
  }
  if (statusReportTimer) {
    clearTimeout(statusReportTimer)
    statusReportTimer = null
  }
  if (statusReportFlushTimer) {
    clearTimeout(statusReportFlushTimer)
    statusReportFlushTimer = null
  }
  if (diagnosticFlushTimer) {
    clearTimeout(diagnosticFlushTimer)
    diagnosticFlushTimer = null
  }
  diagnosticFlushPromise = null
  if (mqttSendQueueInstance) {
    mqttSendQueueInstance.destroy()
    mqttSendQueueInstance = null
  }
  cardEventRetryScheduler?.cancel()
  cardEventRetryScheduler = null
  pendingMqttBusinessResponses.forEach((pending) => clearTimeout(pending.timer))
  pendingMqttBusinessResponses.clear()
}

export function getService() {
  return services
}
