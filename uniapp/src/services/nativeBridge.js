/**
 * NativeBridge V2 — 六大能力通道桥接层
 *
 * 统一的消息格式：
 *  - Vue → Java: { id, action, payload }
 *    通过 WebViewCompat.addWebMessageListener("android") 发送
 *  - Java → Vue: NativeBridge.receive({ type:"response"|"event", requestId?, success?, ... })
 *
 * 六大通道：
 *  1. bootstrap — 启动流程
 *  2. http     — HTTP GET/POST/DOWNLOAD（同步/异步）
 *  3. mqtt     — MQTT 发送/状态查询/消息监听
 *  4. serial   — 串口发送/日志/订阅
 *  5. storage  — SQLite 查询/执行
 *  6. face     — 人脸识别/录入
 */

const TAG = 'NativeBridge'

// ── 内部状态 ──

/** 自增 request id */
let nextId = 0

/** 等待响应的 Promise 缓存: Map<requestId, { resolve, reject, timer }> */
const pendingRequests = new Map()

/** 事件监听器: Map<eventName, Set<callback>> */
const eventListeners = new Map()

/** 是否已初始化 */
let initialized = false

/** 默认超时 (ms) */
const DEFAULT_TIMEOUT = 30000

// ── NativeBridge.receive ──

/**
 * 从 Java 层接收消息的入口。
 * 由 MainActivity.sendBridgeResponse 通过 evaluateJavascript 调用。
 */
function receive(json) {
  if (typeof json === 'string') {
    try { json = JSON.parse(json) } catch (e) { return }
  }
  if (!json || typeof json !== 'object') return

  const { type } = json

  if (type === 'response') {
    handleResponse(json)
  } else if (type === 'event') {
    handleEvent(json)
  }
}

function handleResponse(msg) {
  const { requestId, success, data, code, message } = msg
  if (!requestId) return

  const entry = pendingRequests.get(requestId)
  if (!entry) return

  clearTimeout(entry.timer)
  pendingRequests.delete(requestId)

  if (success) {
    entry.resolve(data)
  } else {
    entry.reject({ code: code || 'ERROR', message: message || 'Unknown error', data })
  }
}

function handleEvent(msg) {
  const { event, data } = msg
  if (!event) return

  const listeners = eventListeners.get(event)
  if (!listeners || listeners.size === 0) {
    if (event === 'slot.status') console.warn('[NativeBridge] slot.status received but NO listeners registered!')
    return
  }

  // 也通知通配符监听器
  const wildcardListeners = eventListeners.get('*')
  const all = new Set([...(listeners || []), ...(wildcardListeners || [])])

  all.forEach(cb => {
    try { cb(data, event) } catch (e) { console.error(`${TAG} event handler error for "${event}":`, e) }
  })
}

// ── 发送消息到 Java ──

/**
 * 通过 Android WebViewCompat.addWebMessageListener 向 Java 发送消息。
 */
function postToAndroid(jsonString) {
  try {
    // 使用 origin-scoped WebMessage API (需要 trust 127.0.0.1:8088)
    const MyWebView = window.android || window.chrome?.webview
    if (MyWebView && typeof MyWebView.postMessage === 'function') {
      MyWebView.postMessage(jsonString)
      return
    }
    // 回退: 标准接口
    // @ts-ignore
    if (typeof window.nativeBridge?.postMessage === 'function') {
      // @ts-ignore
      window.nativeBridge.postMessage(jsonString)
      return
    }
    // 通道不可用时抛出异常，让 request() 立即 reject 而非等 30s 超时
    throw new Error(`${TAG}: No message channel to Android (window.android=${!!window.android}, chrome.webview=${!!window.chrome?.webview}, window.nativeBridge=${!!window.nativeBridge})`)
  } catch (e) {
    console.error(`${TAG} postToAndroid failed:`, e)
    throw e
  }
}

// ── 公开 API ──

/**
 * 向 Java 发送请求并等待响应。
 * @param {string} action - 动作名，如 "http.get", "bootstrap.start"
 * @param {object} [payload={}] - 请求载荷
 * @param {number} [timeout=30000] - 超时毫秒数
 * @returns {Promise<object>} 响应数据
 */
function request(action, payload = {}, timeout = DEFAULT_TIMEOUT) {
  return new Promise((resolve, reject) => {
    const id = `req_${++nextId}_${Date.now()}`
    const timer = setTimeout(() => {
      pendingRequests.delete(id)
      reject({ code: 'TIMEOUT', message: `Request "${action}" timed out after ${timeout}ms` })
    }, timeout)

    pendingRequests.set(id, { resolve, reject, timer })

    try {
      postToAndroid(JSON.stringify({ id, action, payload }))
    } catch (e) {
      // 消息通道不可用 → 立即 reject，不等超时
      clearTimeout(timer)
      pendingRequests.delete(id)
      reject({ code: 'CHANNEL_UNAVAILABLE', message: e.message })
    }
  })
}

/**
 * 向 Java 发送请求但不等待响应（fire-and-forget）。
 * @param {string} action
 * @param {object} [payload={}]
 */
function requestAsync(action, payload = {}) {
  postToAndroid(JSON.stringify({ id: '', action, payload }))
}

/**
 * 注册事件监听器。
 * @param {string} eventName - 事件名，如 "face.recognized", "bootstrap.progress"
 * @param {function} callback - 回调 (data, eventName) => void
 * @returns {function} 取消监听的函数
 */
function on(eventName, callback) {
  if (!eventListeners.has(eventName)) {
    eventListeners.set(eventName, new Set())
  }
  eventListeners.get(eventName).add(callback)
  return () => off(eventName, callback)
}

/**
 * 取消事件监听器。
 * @param {string} eventName
 * @param {function} callback
 */
function off(eventName, callback) {
  const set = eventListeners.get(eventName)
  if (set) {
    set.delete(callback)
    if (set.size === 0) eventListeners.delete(eventName)
  }
}

/**
 * 初始化 NativeBridge（注册 receive 入口）。
 * Must be called before any request.
 */
function init() {
  if (initialized) return
  window.NativeBridge = { receive }
  initialized = true
  console.log(`${TAG} initialized`)
}

/**
 * 清理所有未完成的请求和监听器。
 */
function destroy() {
  pendingRequests.forEach((entry, id) => {
    clearTimeout(entry.timer)
    entry.reject({ code: 'DESTROYED', message: 'Bridge destroyed' })
  })
  pendingRequests.clear()
  eventListeners.clear()
  initialized = false
  delete window.NativeBridge
}

// ── 通道就绪检测 ──

/**
 * 检查与 Android 的消息通道是否就绪。
 */
function isChannelReady() {
  const MyWebView = window.android || window.chrome?.webview
  return !!(MyWebView && typeof MyWebView.postMessage === 'function')
}

/**
 * 等待 Android 消息通道就绪（最多等待 maxWaitMs）。
 * @param {number} [maxWaitMs=5000] 最大等待毫秒数
 * @returns {Promise<boolean>} 通道是否就绪
 */
function waitForChannel(maxWaitMs = 5000) {
  if (isChannelReady()) return Promise.resolve(true)
  return new Promise((resolve) => {
    const start = Date.now()
    const check = () => {
      if (isChannelReady()) {
        resolve(true)
        return
      }
      if (Date.now() - start >= maxWaitMs) {
        console.error(`${TAG} Channel not ready after ${maxWaitMs}ms`)
        resolve(false)
        return
      }
      setTimeout(check, 100)
    }
    check()
  })
}

/**
 * 检查是否运行在 Android WebView 环境中（JsBridge 是否可用）
 */
function isAvailable() {
  return typeof window !== 'undefined' && window.NativeBridge !== undefined
}

// ── 导出 ──

export default {
  init,
  destroy,
  request,
  requestAsync,
  on,
  off,
  waitForChannel,
  isChannelReady,
  isAvailable,
  TAG
}
