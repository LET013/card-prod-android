const pending = new Map()
const listeners = new Map()
let installed = false

const makeId = () => `web-${Date.now()}-${Math.random().toString(16).slice(2)}`

function installReceiver() {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.NativeBridge = window.NativeBridge || {}
  window.NativeBridge.receive = (message) => {
    let data = message
    if (typeof message === 'string') {
      try { data = JSON.parse(message) } catch (error) { return }
    }
    if (!data) return
    if (data.type === 'response' && data.requestId) {
      const item = pending.get(data.requestId)
      if (!item) return
      clearTimeout(item.timer)
      pending.delete(data.requestId)
      if (data.success === false) {
        const error = new Error(data.message || data.code || 'Native request failed')
        error.code = data.code || 'NATIVE_REQUEST_FAILED'
        error.requestId = data.requestId
        item.reject(error)
      } else item.resolve(data.data)
      return
    }
    if (data.type === 'event' && data.event) {
      ;(listeners.get(data.event) || []).forEach((handler) => handler(data.data))
    }
  }
}

export const nativeBridge = {
  install: installReceiver,
  isAvailable() {
    return typeof window !== 'undefined' && window.android && typeof window.android.postMessage === 'function'
  },
  request(action, payload = {}, timeout = 2500) {
    installReceiver()
    if (!this.isAvailable()) return Promise.reject(new Error('NATIVE_BRIDGE_UNAVAILABLE'))
    const requestId = makeId()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId)
        reject(new Error('NATIVE_BRIDGE_TIMEOUT'))
      }, timeout)
      pending.set(requestId, { resolve, reject, timer })
      window.android.postMessage(JSON.stringify({ requestId, action, payload }))
    })
  },
  on(event, handler) {
    installReceiver()
    const list = listeners.get(event) || []
    list.push(handler)
    listeners.set(event, list)
    return () => listeners.set(event, (listeners.get(event) || []).filter((item) => item !== handler))
  }
}
