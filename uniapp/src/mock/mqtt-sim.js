/**
 * Mock MQTT Client — mqtt.js WebSocket 封装
 *
 * 连接真实 MQTT Broker，处理上下行消息。
 * 上行消息使用 MqttEnvelope 格式（含 HMAC-SHA256 签名）。
 * 仅 npm run dev:mock 时使用。
 */

import mqtt from 'mqtt'
import { MQTT_WS_URL, HEARTBEAT_INTERVAL, log, warn, error } from './config.js'

const TAG = 'MqttSim'

let client = null
let deviceCode = ''
let heartbeatTimer = null

// ── signingKey (由 service.js 在 activate/verify 后注入) ──
let _signingKey = ''
export function setSigningKey(key) { _signingKey = key || '' }
export function getSigningKey() { return _signingKey }

// ── msgId 生成 ──
function generateMsgId(prefix = 'mq') {
  const id = (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID().substring(0, 8)
    : Math.random().toString(36).substring(2, 10)
  return `${prefix}_${id}`
}

// ── HMAC-SHA256 签名 (Web Crypto API) ──
async function computeSign(msgId, cmd, timestamp, canonicalData, key) {
  if (!key) {
    warn(TAG, 'computeSign: signingKey is empty, sign will be omitted')
    return ''
  }
  try {
    const input = `${msgId}:${cmd}:${timestamp}:${canonicalData}`
    const enc = new TextEncoder()
    const keyBytes = enc.encode(key)
    const dataBytes = enc.encode(input)
    const cryptoKey = await crypto.subtle.importKey(
      'raw', keyBytes,
      { name: 'HMAC', hash: { name: 'SHA-256' } },
      false, ['sign']
    )
    const sig = await crypto.subtle.sign('HMAC', cryptoKey, dataBytes)
    // Base64 (standard, no-wrap)
    return btoa(String.fromCharCode(...new Uint8Array(sig)))
  } catch (e) {
    error(TAG, 'computeSign failed:', e.message)
    return ''
  }
}

// ── 构建并发送 MqttEnvelope 到 card/{dc}/up ──
export async function sendEnvelope(cmd, data, msgPrefix = 'mq', requestedMsgId = '') {
  if (!client || !client.connected) {
    warn(TAG, 'sendEnvelope: not connected')
    return false
  }
  const msgId = String(requestedMsgId || '').trim() || generateMsgId(msgPrefix)
  const timestamp = Date.now()
  const payload = JSON.stringify(data || {})
  const sign = await computeSign(msgId, cmd, timestamp, payload, _signingKey)

  const envelope = { msgId, cmd, timestamp, deviceCode, data: data || {} }
  if (sign) envelope.sign = sign

  const topic = `card/${deviceCode}/up`
  const json = JSON.stringify(envelope)
  client.publish(topic, json, { qos: 1 })
  log(TAG, `📤 Envelope ${topic} cmd=${cmd} msgId=${msgId} [${json.length}B]${sign ? '' : ' ⚠️ unsigned'}`)
  return true
}

/**
 * 创建 MQTT 连接
 * @param {string} dc - deviceCode (作为 username)
 * @param {string} password - mqttPassword
 * @param {string|null} cid - clientId（可选，自动生成）
 * @param {function} onDownstream - (message) => void 完整下行消息回调
 * @param {function} onReady - 连接就绪回调
 * @returns {object} mqtt client 或 null
 */
export function createMqttClient(dc, password, cid, onDownstream, onReady, onStatusChange) {
  if (client) {
    log(TAG, 'Client already exists, reusing')
    return client
  }

  deviceCode = dc

  const clientId = cid || `browser_${dc}`
  const connectionOptions = {
    username: `device_${dc}`,
    password: password,
    clientId: clientId,
    clean: true,
    connectTimeout: 10000,
    reconnectPeriod: 5000
  }

  log(TAG, '──────────────────────────────────')
  log(TAG, `Connecting to ${MQTT_WS_URL}`)
  log(TAG, `  deviceCode = "${dc}"`)
  log(TAG, `  username   = "device_${dc}"`)
  log(TAG, `  clientId   = "${clientId}"`)
  log(TAG, `  Subscribe  = card/${dc}/down, card/${dc}/down/response`)
  log(TAG, '──────────────────────────────────')

  try {
    client = mqtt.connect(MQTT_WS_URL, connectionOptions)

    client.on('connect', () => {
      log(TAG, '✅ Connected to MQTT Broker')
      if (onStatusChange) onStatusChange({ connected: true })

      const downTopic = `card/${dc}/down`
      const downRespTopic = `card/${dc}/down/response`

      client.subscribe([downTopic, downRespTopic], (err) => {
        if (err) {
          error(TAG, 'Subscribe failed:', err.message)
        } else {
          log(TAG, `✅ Subscribed: ${downTopic}, ${downRespTopic}`)
          // 订阅成功即启动心跳，不依赖外部
          startHeartbeat()
          onReady()
        }
      })
    })

    // 底层包级日志 —— 确认消息是否到达客户端
    client.on('packetreceive', (packet) => {
      if (packet.cmd === 'publish') {
        log(TAG, `📡 PUBLISH packet: topic="${packet.topic}", payloadLen=${(packet.payload || '').length}`)
      }
      if (packet.cmd === 'suback') {
        log(TAG, `📡 SUBACK: grantedQos=[${(packet.granted || []).join(',')}]`)
      }
    })

    let msgCount = 0
    client.on('message', (topic, raw) => {
      msgCount++
      const rawStr = raw.toString()
      log(TAG, `📥 MQTT RECV #${msgCount} on ${topic} [${rawStr.length}B]: ${rawStr.substring(0, 300)}`)

      try {
        const data = JSON.parse(rawStr)

        // 标准信封格式:
        // {"cmd":"...", "data":{...}, "msgId":"...", "timestamp":...}
        if (data && data.cmd) {
          log(TAG, `📥 Dispatch cmd="${data.cmd}", msgId="${data.msgId || ''}"`)
          onDownstream(data)
        } else {
          warn(TAG, `⚠️ No "cmd" field in envelope on ${topic}`, rawStr.substring(0, 200))
        }
      } catch (e) {
        warn(TAG, `⚠️ Non-JSON payload on ${topic}:`, rawStr.substring(0, 200))
      }
    })

    client.on('error', (err) => {
      error(TAG, '❌ Connection error:', err.message)
      if (onStatusChange) onStatusChange({ connected: false, error: err.message })
    })

    client.on('close', () => {
      log(TAG, '🔌 Connection closed')
      if (onStatusChange) onStatusChange({ connected: false })
      stopHeartbeat()
    })

    client.on('reconnect', () => {
      log(TAG, '🔄 Reconnecting...')
      if (onStatusChange) onStatusChange({ connected: false, reconnecting: true })
    })

    client.on('offline', () => {
      log(TAG, '📴 Offline')
      if (onStatusChange) onStatusChange({ connected: false })
      stopHeartbeat()
    })
  } catch (e) {
    error(TAG, 'Failed to create MQTT client:', e.message)
    return null
  }

  return client
}

/**
 * 发送原始上行消息（不带信封，直接 publish）。
 * 用于特殊场景；一般业务应使用 sendEnvelope()。
 */
export function publish(topic, payload) {
  if (!client || !client.connected) {
    warn(TAG, 'Not connected, cannot publish')
    return false
  }
  const jsonStr = JSON.stringify(payload)
  client.publish(topic, jsonStr, { qos: 1 })
  log(TAG, `📤 Raw SEND ${topic} [${jsonStr.length}B]: ${jsonStr.substring(0, 200)}`)
  return true
}

/**
 * 发送心跳（MqttEnvelope 格式，topic = card/{dc}/heartbeat, QoS 0）
 */
export async function sendHeartbeat() {
  if (!client || !client.connected) return false
  const msgId = generateMsgId('hb')
  const timestamp = Date.now()
  const sign = await computeSign(msgId, 'heartbeat', timestamp, '{}', _signingKey)

  const envelope = { msgId, cmd: 'heartbeat', timestamp, deviceCode, data: {} }
  if (sign) envelope.sign = sign

  const topic = `card/${deviceCode}/heartbeat`
  client.publish(topic, JSON.stringify(envelope), { qos: 0 })
  log(TAG, `📤 Heartbeat ${topic} msgId=${msgId}${sign ? '' : ' ⚠️ unsigned'}`)
  return true
}

/**
 * 启动心跳定时器
 */
export function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    sendHeartbeat().catch(e => error(TAG, 'Heartbeat send failed:', e.message))
  }, HEARTBEAT_INTERVAL)
  log(TAG, `Heartbeat started (every ${HEARTBEAT_INTERVAL}ms)`)
}

/**
 * 停止心跳定时器
 */
export function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

/**
 * 销毁 MQTT 客户端
 */
export function destroyMqttClient() {
  stopHeartbeat()
  if (client) {
    try { client.end(true) } catch (e) { /* ignore */ }
    client = null
    log(TAG, 'Destroyed')
  }
}

/**
 * 检查连接状态
 */
export function isConnected() {
  return client && client.connected
}
