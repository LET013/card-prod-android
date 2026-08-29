/**
 * 统一事件总线
 *
 * 同时服务 nativeBridge.on/off 和 mockService 内部事件派发。
 * 支持通配符 "*" 监听所有事件。
 */

const listeners = new Map()

/**
 * 注册事件监听
 * @param {string} eventName
 * @param {function} callback - (data, eventName) => void
 * @returns {function} 取消监听函数
 */
export function on(eventName, callback) {
  if (!listeners.has(eventName)) {
    listeners.set(eventName, new Set())
  }
  listeners.get(eventName).add(callback)
  return () => off(eventName, callback)
}

/**
 * 取消事件监听
 * @param {string} eventName
 * @param {function} callback
 */
export function off(eventName, callback) {
  const set = listeners.get(eventName)
  if (set) {
    set.delete(callback)
    if (set.size === 0) listeners.delete(eventName)
  }
}

/**
 * 触发事件
 * @param {string} eventName
 * @param {*} data
 */
export function emit(eventName, data) {
  const targets = listeners.get(eventName)
  const wildcards = listeners.get('*')

  const all = new Set()
  if (targets) targets.forEach(cb => all.add(cb))
  if (wildcards) wildcards.forEach(cb => all.add(cb))

  all.forEach(cb => {
    try {
      cb(data, eventName)
    } catch (e) {
      console.error(`[MockEvents] handler error for "${eventName}":`, e)
    }
  })
}

/**
 * 清除所有监听器
 */
export function clear() {
  listeners.clear()
}
