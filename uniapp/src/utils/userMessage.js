const TIMEOUT_PATTERN = /timed?\s*out|timeout|超时/i
const TECHNICAL_PATTERN = /msgId|\b(?:Req|Resp)\b|SQLite|NativeBridge|Android Bridge|\bHTTP(?:_\d+|\s+\d+)?\b|\bMQTT\b|\bACK\b|BACKEND_|PROTOCOL_|TRANSPORT_|employeeId|data\.[A-Za-z]|\bcode\b/i
const MQTT_LOGIN_REQUIRED_PATTERN = /设备未登录.*(?:login|登录).*指令/i

const timeoutMessage = (fallback) => {
  const base = String(fallback || '操作失败').replace(/[，。！!：:]$/, '')
  return base.endsWith('失败')
    ? `${base.slice(0, -2)}超时，请稍后重试`
    : `${base}超时，请稍后重试`
}

export function toUserErrorMessage(error, fallback = '操作失败') {
  const message = String(error?.message || error || '').trim()
  if (!message) return fallback
  if (MQTT_LOGIN_REQUIRED_PATTERN.test(message)) return '设备正在重新连接，请稍后重试'
  if (TIMEOUT_PATTERN.test(message)) return timeoutMessage(fallback)
  if (message.length > 80 || TECHNICAL_PATTERN.test(message)) return fallback
  return message
}
