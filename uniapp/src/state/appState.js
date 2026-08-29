import { reactive } from 'vue'
import { defaultSettings, defaultRuntime } from '@/constants/runtimeDefaults.js'
import { normalizeMqttTimingConfig } from '@/constants/config.js'
import { reconcileSlotsProjection } from './slotProjection.js'

const clone = (value) => JSON.parse(JSON.stringify(value))
const slotIndexByNumber = new Map()
const INITIAL_ADMIN_PASSWORD_KEYS = [
  'developerPassword',
  'superAdminPassword',
  'initialAdminPassword',
  'initAdminPassword',
  'adminInitialPassword',
  'adminPassword',
  'initialPassword'
]

const extractHostFromServerUrl = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    return new URL(raw.includes('://') ? raw : `http://${raw}`).hostname || ''
  } catch (error) {
    return ''
  }
}

/**
 * UI-layer memory projection only.
 *
 * Vue owns the V2 display projection. Persisted business cache lives in Vue SQLite via
 * services/localStore.js; Android only provides native storage and device capability channels.
 */
export const appState = reactive({
  settings: { ...defaultSettings },
  runtime: clone(defaultRuntime),
  slots: [],
  employees: [],
  history: [],
  session: null,
  bridgeReady: false,
  lastError: '',
  /** 本地 SQLite 初始化状态（初始化成功后才为 true） */
  localStoreReady: false,
  /** 全局通知横幅（用于异步/后台错误） */
  globalNotice: null,
  /** 卡柜动作的短时视觉反馈，按卡槽号投影 */
  cabinetOperationEffects: {},
  /** 服务器确认添加人脸后记录 ID 和照片哈希，退出管理模式时合并触发一次增量同步。 */
  faceSyncPending: [],
  deviceInfo: { deviceCode: '', channelId: '', activated: false, mqttConnected: false }
})

/** 设置全局通知（同时写入 lastError），用户可手动关闭 */
export const setGlobalNotice = (message, type = 'warn') => {
  appState.globalNotice = {
    id: `notice_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    message: String(message || '').slice(0, 500),
    type,
    timestamp: Date.now()
  }
  appState.lastError = String(message || '').slice(0, 500)
  console.warn(`[GlobalNotice:${type}]`, message)
}

/** 清除全局通知 */
export const clearGlobalNotice = () => {
  appState.globalNotice = null
}

export const setCabinetOperationEffect = (slotNumber, effect, details = {}) => {
  const normalizedSlotNumber = Number(slotNumber)
  if (!Number.isInteger(normalizedSlotNumber) || normalizedSlotNumber < 1) return null
  if (!['success', 'failure'].includes(effect)) return null
  const item = {
    id: String(details.id || ('cabinet-effect:' + normalizedSlotNumber + ':' + Date.now())),
    slotNumber: normalizedSlotNumber,
    effect,
    message: String(details.message || ''),
    operationId: String(details.operationId || ''),
    timestamp: Date.now()
  }
  appState.cabinetOperationEffects = {
    ...appState.cabinetOperationEffects,
    [normalizedSlotNumber]: item
  }
  return item
}

export const clearCabinetOperationEffect = (slotNumber, expectedId = '') => {
  const normalizedSlotNumber = Number(slotNumber)
  const current = appState.cabinetOperationEffects[normalizedSlotNumber]
  if (!current || (expectedId && current.id !== expectedId)) return false
  const next = { ...appState.cabinetOperationEffects }
  delete next[normalizedSlotNumber]
  appState.cabinetOperationEffects = next
  return true
}

/** 将 Error 或字符串转为全局通知（内部使用） */
export const noticeError = (error, context = '') => {
  const message = context
    ? `${context}: ${String(error?.message || error).slice(0, 400)}`
    : String(error?.message || error).slice(0, 500)
  setGlobalNotice(message, 'error')
}

/** 固件升级状态管理（服务端 MQTT firmwareUpgrade 下行驱动） */
const firmwareUpgradeState = reactive({ current: null })

export const setFirmwareUpgrade = (upgrade) => {
  firmwareUpgradeState.current = upgrade
    ? Object.freeze({
        operationId: String(upgrade.operationId || ''),
        requestMsgId: String(upgrade.requestMsgId || ''),
        firmwareVersion: String(upgrade.firmwareVersion || ''),
        downloadUrl: String(upgrade.downloadUrl || ''),
        receivedAt: Number(upgrade.receivedAt || Date.now()),
        status: String(upgrade.status || 'PENDING').toUpperCase(),
        progress: Math.max(0, Math.min(100, Number(upgrade.progress || 0))),
        hardwareVerified: false
      })
    : null
  return firmwareUpgradeState.current
}

export const getFirmwareUpgrade = () => firmwareUpgradeState.current

export const clearFirmwareUpgrade = () => {
  firmwareUpgradeState.current = null
}

export const updateFirmwareUpgradeStatus = (status, extra = {}) => {
  if (!firmwareUpgradeState.current) return null
  firmwareUpgradeState.current = Object.freeze({
    ...firmwareUpgradeState.current,
    ...extra,
    status: String(status || firmwareUpgradeState.current.status).toUpperCase(),
    progress: Math.max(0, Math.min(100, Number(extra.progress ?? firmwareUpgradeState.current.progress ?? 0))),
    hardwareVerified: false
  })
  return firmwareUpgradeState.current
}

export const normalizeSettingsProjection = (settings = {}) => {
  const next = { ...(settings || {}) }
  INITIAL_ADMIN_PASSWORD_KEYS.forEach((key) => { delete next[key] })
  const total = Number(next.totalSlots ?? next.totalCount)
  if (Number.isInteger(total) && total > 0) {
    next.totalSlots = total
    next.totalCount = total
  }
  const groupSize = Number(next.groupSize ?? next.singleGroupCount)
  if (Number.isInteger(groupSize) && groupSize > 0) {
    next.groupSize = groupSize
    next.singleGroupCount = groupSize
  }
  if (next.communicationMode && !next.backendTransport) next.backendTransport = next.communicationMode
  if (next.backendTransport && !next.communicationMode) next.communicationMode = next.backendTransport
  const communicationMode = String(next.communicationMode || 'MQTT').toUpperCase()
  next.communicationMode = ['MQTT', 'HTTP', 'BOTH'].includes(communicationMode) ? communicationMode : 'MQTT'
  next.backendTransport = next.communicationMode
  const direction = String(next.slotSortDirection || next.slotLayoutDirection || '').toUpperCase()
  next.slotSortDirection = direction === 'VERTICAL' ? 'VERTICAL' : 'HORIZONTAL'
  if (next.serverUrl && !next.httpServerAddress) next.httpServerAddress = next.serverUrl
  if (next.httpServerAddress && !next.serverUrl) next.serverUrl = next.httpServerAddress
  // 后端 getConfig 下发 httpHost+httpPort（非旧字段 serverUrl），需要回构 serverUrl
  // 后端强制 HTTPS；HTTP→HTTPS 301 重定向会导致 Android HttpClient 请求失败
  if (!next.serverUrl && next.httpHost) {
    const port = next.httpPort !== 80 ? (':' + next.httpPort) : ''
    next.serverUrl = `https://${next.httpHost}${port}`
    next.httpServerAddress = next.serverUrl
  }
  if (!next.httpHost && next.serverUrl) next.httpHost = extractHostFromServerUrl(next.serverUrl)
  if (next.mqttHost && !next.mqttServerAddress) next.mqttServerAddress = next.mqttHost
  if (next.mqttServerAddress && !next.mqttHost) next.mqttHost = next.mqttServerAddress
  Object.assign(next, normalizeMqttTimingConfig(next))
  if (next.deviceCode && !next.deviceId) next.deviceId = next.deviceCode
  if (next.deviceId && !next.deviceCode) next.deviceCode = next.deviceId
  // config 下发遗漏字段的双向别名（与 normalizeDeviceConfig 对齐）
  if (next.cabinetNumber && !next.cabinetNo) next.cabinetNo = next.cabinetNumber
  if (next.cabinetNo && !next.cabinetNumber) next.cabinetNumber = next.cabinetNo
  if (next.httpPort != null) next.httpPort = Number(next.httpPort)
  if (next.mqttPort != null) next.mqttPort = Number(next.mqttPort)
  if (next.serialPollEnabled == null) next.serialPollEnabled = next.serialPollingEnabled !== false
  next.serialPollingEnabled = next.serialPollEnabled !== false
  if (next.serialPollInterval == null && next.serialPollingIntervalMs != null) next.serialPollInterval = Number(next.serialPollingIntervalMs)
  if (next.serialPollInterval != null) next.serialPollInterval = Number(next.serialPollInterval)
  if (next.serialPollInterval != null) next.serialPollingIntervalMs = next.serialPollInterval
  if (next.serialResponseTimeout == null && next.serialResponseTimeoutMs != null) next.serialResponseTimeout = Number(next.serialResponseTimeoutMs)
  if (next.serialResponseTimeout != null) next.serialResponseTimeout = Number(next.serialResponseTimeout)
  if (next.serialResponseTimeout != null) next.serialResponseTimeoutMs = next.serialResponseTimeout
  // MQTT 状态上报间隔按协议保存秒值，卡槽推送间隔继续独立使用毫秒。
  const statusReportSeconds = Number(next.mqttStatusReportInterval ?? 300)
  next.mqttStatusReportInterval = Number.isInteger(statusReportSeconds) && statusReportSeconds > 0
    ? statusReportSeconds
    : 300
  if (next.slotStatusPushInterval == null && next.slotStatusReportIntervalMs != null) next.slotStatusPushInterval = Number(next.slotStatusReportIntervalMs)
  if (next.slotStatusPushInterval != null) next.slotStatusPushInterval = Number(next.slotStatusPushInterval)
  if (next.slotStatusPushInterval != null) next.slotStatusReportIntervalMs = next.slotStatusPushInterval
  const pollingMode = String(next.pollingMode || '').toUpperCase()
  next.pollingMode = pollingMode === 'SINGLE' ? 'SINGLE' : 'GROUP'
  // faceThreshold 是 API 文档字段名，faceRecognitionThreshold 是内部使用名
  if (next.faceThreshold != null && next.faceRecognitionThreshold == null) {
    next.faceRecognitionThreshold = Number(next.faceThreshold)
  }
  if (next.faceRecognitionThreshold != null) {
    next.faceRecognitionThreshold = Number(next.faceRecognitionThreshold)
    next.faceThreshold = next.faceRecognitionThreshold
  }
  if (next.fingerThreshold == null && next.fingerRecognitionThreshold != null) next.fingerThreshold = Number(next.fingerRecognitionThreshold)
  if (next.fingerThreshold != null) next.fingerThreshold = Number(next.fingerThreshold)
  if (next.fingerThreshold != null) next.fingerRecognitionThreshold = next.fingerThreshold
  next.fingerEnabled = next.fingerEnabled === '1' || next.fingerEnabled === 1 || next.fingerEnabled === true || next.fingerprintEnabled ? '1' : '0'
  next.fingerprintEnabled = next.fingerEnabled === '1'
  if (next.faceRecognitionTimeout != null) next.faceRecognitionTimeout = Number(next.faceRecognitionTimeout)
  if (next.searchTimeout != null) next.searchTimeout = Number(next.searchTimeout)
  if (next.searchIntervalTime != null) next.searchIntervalTime = Number(next.searchIntervalTime)
  if (next.captureTimeout != null) next.captureTimeout = Number(next.captureTimeout)
  if (next.needFaceLiveness != null) next.needFaceLiveness = Boolean(next.needFaceLiveness)
  const cameraFacing = String(next.cameraFacing || '').toLowerCase()
  next.cameraFacing = cameraFacing === 'back' ? 'back' : 'front'
  if (next.cameraMirror != null) next.cameraMirror = Boolean(next.cameraMirror)
  else next.cameraMirror = next.cameraFacing === 'front'
  const cameraRotation = Number(next.cameraRotation)
  next.cameraRotation = [0, 90, 180, 270].includes(cameraRotation) ? cameraRotation : 0
  // initialized 标志位
  if (next.initialized != null) next.initialized = Boolean(next.initialized)
  return next
}

export const replaceSettingsProjection = (settings = {}) => {
  const oldTotal = Number(appState.settings.totalSlots || appState.settings.totalCount)
  Object.assign(appState.settings, normalizeSettingsProjection(settings))
  const newTotal = Number(appState.settings.totalSlots || appState.settings.totalCount)
  // 预填充卡槽占位（槽总数由 config.totalSlots/totalCount 决定，串口只推送实际存在的槽）
  if (Number.isInteger(newTotal) && newTotal > 0) {
    initSlotsFromTotal(newTotal)
  }
  // totalSlots 缩小时，通知外部清理超出范围的缓存卡槽数据
  if (Number.isInteger(oldTotal) && oldTotal > 0 && Number.isInteger(newTotal) && newTotal > 0 && newTotal < oldTotal) {
    if (typeof totalSlotsChangeListener === 'function') {
      totalSlotsChangeListener(newTotal)
    }
  }
  return appState.settings
}

let totalSlotsChangeListener = null

/** 注册 totalSlots 缩小时的清理回调（用于清理 SQLite slots_snapshot） */
export const onTotalSlotsChange = (fn) => {
  totalSlotsChangeListener = fn
}

function initSlotsFromTotal(total) {
  const existing = new Map()
  appState.slots.forEach((s) => { existing.set(Number(s.slotNumber), s) })
  const next = []
  for (let i = 1; i <= total; i++) {
    const prev = existing.get(i)
    if (prev) {
      next.push(prev)
    } else {
      next.push({ slotNumber: i, id: `slot-${i}`, displayNumber: String(i).padStart(2, '0'), status: 'LOADING' })
    }
  }
  const unchanged = next.length === appState.slots.length && next.every((slot, index) => slot === appState.slots[index])
  if (!unchanged) appState.slots.splice(0, appState.slots.length, ...next)
  rebuildSlotIndex()
  console.log('[initSlots] totalSlots=%d, initialized %d slots, rehydrated %d', total, appState.slots.length, existing.size)
}

const rebuildSlotIndex = () => {
  slotIndexByNumber.clear()
  appState.slots.forEach((slot, index) => {
    const slotNumber = Number(slot?.slotNumber)
    if (Number.isInteger(slotNumber) && slotNumber > 0) slotIndexByNumber.set(slotNumber, index)
  })
}

export const getSlotProjection = (slotNumber) => {
  const normalized = Number(slotNumber)
  if (!Number.isInteger(normalized) || normalized < 1) return null
  const index = slotIndexByNumber.get(normalized)
  return Number.isInteger(index) ? (appState.slots[index] || null) : null
}

export const replaceRuntimeProjection = (runtime = {}) => {
  appState.runtime = clone(runtime || {})
  return appState.runtime
}

export const replaceSlotsProjection = (items = [], options = {}) => {
  const slots = Array.isArray(items) ? items : []
  const max = Number(appState.settings.totalSlots || appState.settings.totalCount)
  const result = reconcileSlotsProjection(appState.slots, slots, max, options)
  if (result.slots.length !== slots.length) {
    console.warn('[replaceSlots] normalized %d records to %d unique configured slots', slots.length, result.slots.length)
  }
  if (result.topologyChanged) rebuildSlotIndex()
  return appState.slots
}

/** 更新/插入单个卡槽状态（来自串口轮询的实时推送） */
export const upsertSlotProjection = (slot, { fresh } = {}) => {
  if (!slot || slot.slotNumber == null) return appState.slots
  const sn = Number(slot.slotNumber)
  if (!Number.isInteger(sn) || sn < 1) return appState.slots
  // 忽略超出 totalSlots/totalCount 范围的数据
  const max = Number(appState.settings.totalSlots || appState.settings.totalCount)
  if (Number.isInteger(max) && max > 0 && sn > max) {
    console.warn('[upsertSlot] slot', sn, 'exceeds totalSlots', max)
    return appState.slots
  }
  // 补全 Vue 组件依赖的展示字段（串口原始数据不包含）。
  // 不对高频串口事件做 JSON 深拷贝，避免每次更新都制造整槽位对象副本。
  const enriched = { ...slot }
  if (fresh === true) enriched.fresh = true
  if (!enriched.id) enriched.id = `slot-${sn}`
  if (!enriched.displayNumber) enriched.displayNumber = String(sn).padStart(2, '0')
  const idx = slotIndexByNumber.get(sn)
  if (Number.isInteger(idx)) {
    const current = appState.slots[idx]
    let changed = false
    Object.entries(enriched).forEach(([key, value]) => {
      // 轮询时间戳本身不改变展示或持久化签名，不触发整张卡片重新渲染。
      if ((key === 'updatedAt' || key === 'updated_at') && current.status === enriched.status) return
      if (Object.is(current[key], value)) return
      current[key] = value
      changed = true
    })
    if (!changed) return appState.slots
  } else {
    const insertAt = appState.slots.findIndex((item) => Number(item.slotNumber) > sn)
    if (insertAt < 0) appState.slots.push(enriched)
    else appState.slots.splice(insertAt, 0, enriched)
    rebuildSlotIndex()
  }
  return appState.slots
}

export const replaceEmployeesProjection = (items = []) => {
  appState.employees = Array.isArray(items) ? clone(items) : []
  return appState.employees
}

const normalizePermissions = (permissions) => {
  if (permissions instanceof Set) return new Set(Array.from(permissions).filter(Boolean))
  if (Array.isArray(permissions)) return new Set(permissions.filter(Boolean))
  return new Set()
}

export const normalizeSessionProjection = (session = null) => {
  if (!session) return null
  const permissions = normalizePermissions(session.permissions)
  const roles = Array.isArray(session.roles) ? session.roles : []
  return {
    ...session,
    roles,
    roleIds: Array.isArray(session.roleIds) ? session.roleIds : roles.map((role) => role.roleId).filter(Boolean),
    roleLabels: roles.map((role) => role.roleName || role.roleId).filter(Boolean),
    role: session.role || roles[0]?.roleId || session.roleIds?.[0] || '',
    permissions
  }
}

export const replaceSessionProjection = (session = null) => {
  appState.session = normalizeSessionProjection(session)
  return appState.session
}

export const clearSessionProjection = () => {
  appState.session = null
  return appState.session
}

export const hasPermission = (permissionKey) => {
  if (!permissionKey || !appState.session) return false
  const granted = appState.session.permissions
  if (!(granted instanceof Set)) return false
  if (granted.has('*') || granted.has(permissionKey)) return true
  if (!String(permissionKey).endsWith('.*')) return false
  const prefix = String(permissionKey).slice(0, -1)
  return Array.from(granted).some((key) => String(key).startsWith(prefix))
}

export const hasAnyPermission = (...permissionKeys) => permissionKeys.some((permissionKey) => hasPermission(permissionKey))

export const replaceHistoryProjection = (items = []) => {
  appState.history = Array.isArray(items) ? clone(items) : []
  return appState.history
}

export const replaceDeviceInfoProjection = (info) => {
  if (!info) {
    appState.deviceInfo.deviceCode = ''
    appState.deviceInfo.activated = false
    appState.deviceInfo.mqttConnected = false
    return appState.deviceInfo
  }
  appState.deviceInfo.deviceCode = info.deviceCode || ''
  appState.deviceInfo.channelId = info.channelId || ''
  appState.deviceInfo.activated = Boolean(info.activated)
  appState.deviceInfo.mqttConnected = Boolean(info.mqttConnected)
  return appState.deviceInfo
}

export const clearNativeProjection = () => {
  appState.runtime = clone(defaultRuntime)
  appState.slots.splice(0, appState.slots.length)
  slotIndexByNumber.clear()
  appState.employees = []
  appState.cabinetOperationEffects = {}
  appState.bridgeReady = false
  appState.deviceInfo.deviceCode = ''
  appState.deviceInfo.channelId = ''
  appState.deviceInfo.activated = false
  appState.deviceInfo.mqttConnected = false
}
