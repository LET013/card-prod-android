/**
 * 服务器地址输入提示默认值（仅在首次启动无缓存配置时用于输入框预填）。
 * 实际连接参数由启动流程最后一步 GET /api/v1/device/config 下发，不依赖本地硬编码。
 */
export const SERVER_INPUT_DEFAULTS = {
  host: 'card-test.quyohui.com',
  port: '443'
}

const LEGACY_TEST_SERVER_HOST = SERVER_INPUT_DEFAULTS.host.toLowerCase()

/**
 * 构造首次启动的注册服务地址。
 * 当前测试域名已验证只接受 HTTPS；其他地址继续遵循现有 HTTP 配置契约。
 */
export const buildBootstrapServerUrl = (hostValue, portValue) => {
  const rawHost = String(hostValue || '').trim()
  if (!rawHost) return ''

  const explicitScheme = rawHost.match(/^(https?):\/\/(.+)$/i)
  const host = String(explicitScheme?.[2] || rawHost).replace(/\/+$/, '')
  const protocol = explicitScheme?.[1]?.toLowerCase()
    || (host.toLowerCase() === LEGACY_TEST_SERVER_HOST ? 'https' : 'http')
  const port = Number(portValue)
  const omitPort = !Number.isInteger(port)
    || (protocol === 'http' && port === 80)
    || (protocol === 'https' && port === 443)

  return `${protocol}://${host}${omitPort ? '' : ':' + port}`
}

/**
 * 迁移旧版本写入 SQLite 的测试注册地址；不得改写其他明确配置的 HTTP 地址。
 */
export const normalizeBootstrapServerUrl = (value) => {
  const serverUrl = String(value || '').trim()
  if (!serverUrl) return ''

  try {
    const parsed = new URL(serverUrl)
    const isLegacyTestUrl = parsed.protocol === 'http:'
      && parsed.hostname.toLowerCase() === LEGACY_TEST_SERVER_HOST
      && (!parsed.port || parsed.port === '80')
    if (!isLegacyTestUrl) return serverUrl

    parsed.protocol = 'https:'
    parsed.port = ''
    return parsed.toString().replace(/\/$/, '')
  } catch (_) {
    return serverUrl
  }
}

/** 将首次启动保存的完整地址还原为管理页可直接展示的主机和有效端口。 */
export const parseBootstrapServerUrl = (value) => {
  const serverUrl = normalizeBootstrapServerUrl(value)
  if (!serverUrl) return { serverUrl: '', host: '', port: 0 }
  try {
    const parsed = new URL(serverUrl)
    const port = Number(parsed.port) || (parsed.protocol === 'https:' ? 443 : 80)
    return { serverUrl, host: parsed.hostname, port }
  } catch (_) {
    return { serverUrl, host: '', port: 0 }
  }
}

export const MQTT_TIMING_DEFAULTS = Object.freeze({
  mqttHeartbeatInterval: 60000,
  mqttReconnectInitialInterval: 1000,
  mqttReconnectMaxInterval: 60000
})

export const MQTT_CONNECTION_CONFIG_FIELDS = Object.freeze([
  'mqttHost',
  'mqttPort',
  'mqttReconnectInitialInterval',
  'mqttReconnectMaxInterval'
])

export const usesMqttConnection = (communicationMode) => {
  const mode = String(communicationMode || '').toUpperCase()
  return mode === 'MQTT' || mode === 'BOTH'
}

const positiveIntegerOrDefault = (value, fallback) => {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}

export const normalizeMqttTimingConfig = (settings = {}) => ({
  mqttHeartbeatInterval: positiveIntegerOrDefault(
    settings.mqttHeartbeatInterval,
    MQTT_TIMING_DEFAULTS.mqttHeartbeatInterval
  ),
  mqttReconnectInitialInterval: positiveIntegerOrDefault(
    settings.mqttReconnectInitialInterval,
    MQTT_TIMING_DEFAULTS.mqttReconnectInitialInterval
  ),
  mqttReconnectMaxInterval: positiveIntegerOrDefault(
    settings.mqttReconnectMaxInterval,
    MQTT_TIMING_DEFAULTS.mqttReconnectMaxInterval
  )
})

export const STARTUP_MODE = {
  ONLINE: 'ONLINE',
  OFFLINE: 'OFFLINE'
}

export const CONFIG_SOURCE = {
  ONLINE_SERVER: 'ONLINE_SERVER',
  OFFLINE_FILE: 'OFFLINE_FILE'
}

export const OFFLINE_ACTIVATION_STATUS = {
  NOT_AVAILABLE: 'NOT_AVAILABLE',
  PENDING: 'PENDING',
  ACTIVATED: 'ACTIVATED',
  FAILED: 'FAILED'
}

export const OFFLINE_ACTIVATION_RESERVED = {
  available: false,
  mode: STARTUP_MODE.ONLINE,
  status: OFFLINE_ACTIVATION_STATUS.NOT_AVAILABLE,
  configSource: CONFIG_SOURCE.ONLINE_SERVER,
  message: '当前在线版不支持离线激活'
}
