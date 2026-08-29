/**
 * Mock JsBridgeV2 — 浏览器端 JsBridge 模拟实现
 *
 * 替换 nativeBridge.js，在浏览器中模拟所有 Android JsBridge 通信通道。
 * 通过 Vite resolveId 插件透明注入，不改动任何源码。
 */

import { on as busOn, off as busOff, emit, clear as busClear } from './events.js'
import { query as dbQuery, execute as dbExecute, destroy as dbDestroy } from './worker-adapter.js'
import { start as serialStart, stop as serialStop, setSlotState, updateTotalSlots, handleCommand, getMockLogs, getSerialTxBytes, getSerialRxBytes } from './serial-sim.js'
import { faceRecognitionStart, faceRecognitionCancel, faceEnrollmentStart, faceEnrollmentCancel, destroy as faceDestroy } from './face-service.js'
import { createMqttClient, destroyMqttClient, isConnected, sendEnvelope } from './mqtt-sim.js'
import { log, warn, error } from './config.js'

// ── State ──
let initialized = false
let totalSlots = 12 // 默认12格，设备配置加载后更新
const channelReady = new Map()
const channelWaiters = new Map()

// ── Credentials (bootstrap 后填充) ──
export let serverUrl = 'http://card-test.quyohui.com'
export let deviceToken = null
export let deviceCode = null
export let mqttPassword = null
export let signingKey = null

export function setCredentials({ serverUrl: url, deviceToken: token, deviceCode: code, mqttPassword: pw, signingKey: sk }) {
  if (url) serverUrl = url
  if (token) deviceToken = token
  if (code) deviceCode = code
  if (pw) mqttPassword = pw
  if (sk !== undefined) signingKey = sk
}

// ── Device Info (bootstrap 后由 service.js 填充) ──
let deviceInfo = null
let activated = false
export function setDeviceInfo(info) {
  deviceInfo = { ...deviceInfo, ...info }
  if (info.totalSlots !== undefined) totalSlots = info.totalSlots
}
export function setActivated(val) { activated = val }
export function emitDeviceInfo() {
  const data = {
    deviceCode: deviceCode || '',
    activated: activated,
    mqttConnected: isConnected(),
    timestamp: Date.now()
  }
  emit('device.info', data)
}

// ── Init / Destroy ──

export function init() {
  if (initialized) return
  initialized = true
  log('Bridge', 'init')

  setChannelReady('http')
  setChannelReady('storage')
  setChannelReady('eventBus')
  setTimeout(() => setChannelReady('face'), 100)

  // 启动串口模拟（定时随机推送卡槽状态）
  serialStart(totalSlots)
  setChannelReady('serial')

  // 推送串口 CONNECTED 状态
  setTimeout(() => {
    emit('serial.statusChanged', {
      state: 'CONNECTED',
      message: '串口已连接',
      port: '/dev/ttyS5',
      baudRate: 57600,
      sentBytes: 0,
      receivedBytes: 0,
      pollingEnabled: true,
      totalSlots: totalSlots,
      debugEventLimit: 300
    })
  }, 50)

  setTimeout(() => {
    emit('native.ready', { version: 'mock-dev', timestamp: Date.now() })
    log('Bridge', 'native.ready emitted')
  }, 200)
}

export function destroy() {
  if (!initialized) return
  initialized = false

  serialStop()
  faceDestroy()
  destroyMqttClient()
  dbDestroy()
  busClear()

  channelReady.clear()
  channelWaiters.clear()
  log('Bridge', 'destroyed')
}

// ── Channel Management ──

function setChannelReady(name) {
  channelReady.set(name, true)
  const waiters = channelWaiters.get(name)
  if (waiters) {
    waiters.forEach(w => w.resolve(true))
    channelWaiters.delete(name)
  }
}

/**
 * 检查通道是否就绪。
 * - 兼容原始签名: isChannelReady() → 整体 bridge 是否初始化
 * - 新签名: isChannelReady(name) → 特定通道是否就绪
 */
export function isChannelReady(name) {
  if (name === undefined) return initialized
  return channelReady.get(name) === true
}

export function isAvailable() {
  return false // 浏览器 Mock 模式，JsBridge 不可用
}

/**
 * 等待通道就绪。
 * - 兼容原始签名: waitForChannel(maxWaitMs) → 等 init() 完成 + native.ready 事件
 * - 新签名: waitForChannel(channelName, timeout) → 等特定通道就绪
 */
export function waitForChannel(nameOrTimeout, timeout = 10000) {
  // 兼容原始 API: waitForChannel(3000) — 第一个参数是数字，表示最大等待毫秒数
  if (typeof nameOrTimeout === 'number') {
    const maxWaitMs = nameOrTimeout
    if (initialized) return Promise.resolve(true)
    return new Promise((resolve) => {
      const start = Date.now()
      const check = () => {
        if (initialized) { resolve(true); return }
        if (Date.now() - start >= maxWaitMs) {
          warn('Bridge', `bridge not ready after ${maxWaitMs}ms`)
          resolve(false)
          return
        }
        setTimeout(check, 100)
      }
      check()
    })
  }

  const name = nameOrTimeout
  if (channelReady.get(name)) return Promise.resolve(true)
  return new Promise((resolve, reject) => {
    if (!channelWaiters.has(name)) channelWaiters.set(name, [])
    channelWaiters.get(name).push({ resolve, reject })
    setTimeout(() => reject(new Error(`Channel ${name} timeout`)), timeout)
  })
}

// ── Event API ──

export function on(eventName, callback) {
  return busOn(eventName, callback)
}

export function off(eventName, callback) {
  busOff(eventName, callback)
}

// ── Request Router ──
// 签名: request(action, payload, timeout) — 与 nativeBridge.js 一致
// action 格式: "service.method"，如 "http.get", "storage.query"

export function request(action, payload = {}, timeout = 30000) {
  const dotIdx = action.indexOf('.')
  const service = dotIdx >= 0 ? action.substring(0, dotIdx) : action
  const method = dotIdx >= 0 ? action.substring(dotIdx + 1) : ''

  const payloadStr = typeof payload === 'object' ? JSON.stringify(payload).substring(0, 80) : payload
  log('Bridge', action, payloadStr)

  switch (service) {
    case 'http': return handleHttp(method, payload)
    case 'mqtt': return handleMqtt(method, payload)
    case 'storage': return handleStorage(method, payload)
    case 'serial': return handleSerial(method, payload)
    case 'face': return handleFace(method, payload)
    case 'app': return handleApp(method, payload)
    case 'bootstrap': return handleBootstrap(method, payload)
    default:
      warn('Bridge', `Unknown service: ${action}`)
      return Promise.resolve(null)
  }
}

export function requestAsync(action, payload = {}) {
  return request(action, payload)
}

// ══════════════════════════════════════════
//  HTTP
// ══════════════════════════════════════════

async function fetchJson(method, url, body = null) {
  const fullUrl = url.startsWith('http') ? url : `${serverUrl}${url}`
  const headers = { 'Content-Type': 'application/json' }
  if (deviceToken) headers['Authorization'] = `Bearer ${deviceToken}`

  const res = await fetch(fullUrl, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`)
  return res.json()
}

async function handleHttp(action, payload) {
  try {
    switch (action) {
      case 'get': return await fetchJson('GET', payload.path)
      case 'post': return await fetchJson('POST', payload.path, payload.body)
      case 'put': return await fetchJson('PUT', payload.path, payload.body)
      case 'delete': return await fetchJson('DELETE', payload.path)
      case 'download': return await fetchJson('GET', payload.path) // mock: download ≈ GET
      default: return null
    }
  } catch (e) {
    error('HttpHandler', `${action} failed:`, e.message)
    throw e
  }
}

// ══════════════════════════════════════════
//  MQTT
// ══════════════════════════════════════════

function handleMqtt(action, payload) {
  if (!mqttClient && action !== 'connect' && action !== 'init') {
    log('Bridge', `mqtt.${action} — client not connected, deferring`)
    return Promise.resolve(null)
  }
  switch (action) {
    case 'connect': return connectMqtt(payload)
    case 'publish': return publishMqtt(payload?.topic, payload?.message)
    case 'subscribe': return subscribeMqtt(payload?.topic)
    case 'send': return sendMqttEnvelope(payload)
    case 'disconnect': destroyMqttClient(); return Promise.resolve(true)
    case 'loginStatus': return Promise.resolve({ connected: isConnected() })
    case 'handleMessage': return Promise.resolve(null)
    default: return Promise.resolve(null)
  }
}

export function connectMqtt(options = {}) {
  if (!deviceCode || !mqttPassword) {
    warn('Bridge', 'MQTT connect: missing credentials')
    return Promise.resolve(false)
  }
  const clientId = options.clientId || null
  log('Bridge', `MQTT connecting as deviceCode="${deviceCode}" clientId=${clientId || 'auto'}...`)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      error('Bridge', 'MQTT connect timeout')
      reject(new Error('MQTT connect timeout'))
    }, 30000)
    mqttClient = createMqttClient(
      deviceCode,
      mqttPassword,
      clientId,
      (message = {}) => {
        const cmd = String(message?.cmd || '').trim()
        if (!cmd) {
          warn('Bridge', 'MQTT downstream message is missing cmd')
          return
        }
        log('Bridge', `🔔 MQTT event: cmd=${cmd} msgId=${message?.msgId || ''}`)
        emit('mqtt.message', message)
        emit(`mqtt.${cmd}`, message?.data ?? {})
      },
      () => {
        clearTimeout(timer)
        log('Bridge', 'MQTT channel ready')
        setChannelReady('mqtt')
        emit('socket.statusChanged', { connected: true, timestamp: Date.now() })
        emitDeviceInfo()
        resolve(true)
      },
      (status) => {
        log('Bridge', `MQTT status: ${JSON.stringify(status)}`)
        emit('socket.statusChanged', { ...status, timestamp: status.timestamp || Date.now() })
        emitDeviceInfo()
      }
    )
  })
}

async function sendMqttEnvelope(payload = {}) {
  const cmd = String(payload?.cmd || '').trim()
  if (!cmd) {
    warn('Bridge', 'mqtt.send rejected: missing cmd')
    return false
  }
  const responseData = payload?.data && typeof payload.data === 'object' ? payload.data : {}
  const msgId = String(payload?.msgId || '').trim()
  return sendEnvelope(cmd, responseData, 'mq', msgId)
}

async function publishMqtt(topic, payload) {
  if (mqttClient) {
    try {
      const json = JSON.stringify(payload)
      log('Bridge', `📤 MQTT SEND ${topic} [${json.length}B]: ${json.substring(0, 200)}`)
      await mqttClient.publish(topic, json)
      return true
    } catch (e) {
      error('Bridge', 'MQTT publish error:', e.message)
      return false
    }
  }
  return false
}

async function subscribeMqtt(topic) {
  if (mqttClient) {
    try {
      await mqttClient.subscribe(topic)
      return true
    } catch (e) {
      error('Bridge', 'MQTT subscribe error:', e.message)
      return false
    }
  }
  return false
}

let mqttClient = null

// ══════════════════════════════════════════
//  Storage
// ══════════════════════════════════════════

async function handleStorage(action, payload) {
  try {
    switch (action) {
      case 'query': {
        const { sql, params: sqlParams } = payload
        const result = await dbQuery(sql, sqlParams || [])
        return { rows: result.rows || [], count: result.count || 0 }
      }
      case 'execute': {
        const { sql, params: sqlParams } = payload
        const result = await dbExecute(sql, sqlParams || [])
        return { affectedRows: result.affectedRows || 0 }
      }
      case 'clear':
        return { success: true }
      default:
        warn('Bridge', `Unknown storage action: ${action}`)
        return null
    }
  } catch (e) {
    error('StorageHandler', `${action} failed:`, e.message)
    throw e
  }
}

// ══════════════════════════════════════════
//  Serial
// ══════════════════════════════════════════

function handleSerial(action, payload) {
  switch (action) {
    case 'start': {
      serialStart(payload?.count || 12)
      setChannelReady('serial')
      return Promise.resolve(true)
    }
    case 'stop':
      serialStop()
      return Promise.resolve(true)
    case 'setSlot': {
      setSlotState(payload?.slot, payload?.status, payload?.extra)
      return Promise.resolve(true)
    }
    case 'updateTotalSlots': {
      updateTotalSlots(payload?.count)
      return Promise.resolve(true)
    }
    case 'send': {
      log('Bridge', `serial.send: ${payload?.hex}`)
      const response = handleCommand(payload?.hex)
      if (response) {
        setTimeout(() => {
          emit('serial.dataReceived', { type: 'serialRxRaw', hex: response.hex })
        }, 60) // 模拟串口应答延迟
      }
      return Promise.resolve({ sent: true, hex: payload?.hex, bytes: (payload?.hex || '').length >> 1 })
    }
    case 'getLogs':
      return Promise.resolve(getMockLogs(payload?.count || 100))
    case 'status':
      return Promise.resolve({
        state: 'CONNECTED',
        message: '串口已连接',
        port: '/dev/ttyS5',
        baudRate: 57600,
        sentBytes: getSerialTxBytes(),
        receivedBytes: getSerialRxBytes(),
        pollingEnabled: true,
        totalSlots: totalSlots,
        debugEventLimit: 300
      })
    case 'listPorts':
      return Promise.resolve([
        { path: '/dev/ttyS5', readable: true, writable: true },
        { path: '/dev/ttyUSB0', readable: true, writable: false },
        { path: '/dev/ttyUSB1', readable: false, writable: false }
      ])
    case 'subscribe':
    case 'unsubscribe':
      return Promise.resolve(true)
    default:
      return Promise.resolve(null)
  }
}

// ══════════════════════════════════════════
//  Face
// ══════════════════════════════════════════

function handleFace(action, payload) {
  switch (action) {
    case 'recognition.start':
      faceRecognitionStart(payload)
      return Promise.resolve({ accepted: true })
    case 'recognition.cancel':
      faceRecognitionCancel()
      return Promise.resolve(true)
    case 'enrollment.start': {
      faceEnrollmentStart(payload?.faceId, payload)
      return Promise.resolve({ accepted: true })
    }
    case 'enrollment.cancel':
      faceEnrollmentCancel()
      return Promise.resolve(true)
    case 'count':
      return Promise.resolve({ count: 0 }) // mock 环境下无人脸库
    default:
      return Promise.resolve(null)
  }
}

// ══════════════════════════════════════════
//  Bootstrap
// ══════════════════════════════════════════

function handleBootstrap(action, payload) {
  switch (action) {
    case 'deviceInfo':
      log('Bridge', 'bootstrap.deviceInfo', deviceInfo)
      if (deviceInfo) {
        return Promise.resolve({
          ...deviceInfo,
          activated: activated,
          mqttConnected: isConnected()
        })
      }
      // 兜底：bootstrap 尚未完成
      return Promise.resolve({
        machineId: '',
        deviceCode: deviceCode || '',
        version: '',
        model: '',
        os: navigator.platform || 'web',
        totalSlots: totalSlots,
        activated: false,
        mqttConnected: false
      })
    default:
      warn('Bridge', `Unknown bootstrap action: ${action}`)
      return Promise.resolve(null)
  }
}

// ══════════════════════════════════════════
//  App
// ══════════════════════════════════════════

function handleApp(action, payload) {
  switch (action) {
    case 'getDeviceInfo':
      return Promise.resolve({
        model: deviceInfo?.model || '',
        version: deviceInfo?.version || '',
        os: deviceInfo?.os || navigator.platform || 'web',
        totalSlots: deviceInfo?.totalSlots || totalSlots
      })
    case 'exit':
      log('Bridge', 'app.exit called (no-op in browser)')
      return Promise.resolve(true)
    default:
      return Promise.resolve(null)
  }
}

// ── Expose totalSlots updater ──
export function _devSetTotalSlots(count) {
  totalSlots = count
}

// ── Default export (matches nativeBridge.js shape) ──
export default {
  init,
  destroy,
  request,
  requestAsync,
  on,
  off,
  waitForChannel,
  isChannelReady,
  isAvailable
}
