/**
 * MQTT 优先级发送队列模块
 *
 * 职责：
 * - 串行化所有 MQTT 发送，保证同一时刻只有一个 mqtt.send 在飞
 * - 按优先级排序：HIGH（业务指令请求-响应）> NORMAL（响应回包/状态上报）> LOW（日志流）
 * - 连接断开时暂停排空，恢复后自动继续
 * - LOW 优先级超量时丢弃最早条目，上报丢弃统计
 * - 所有日志流（logcat）绕过 SQLite，直接 LOW 优先级 fire-and-forget
 *
 * 用法：
 *   import { createMqttSendQueue, MQTT_PRIORITY } from './mqttSendQueue.js'
 *   const queue = createMqttSendQueue({ nativeSend: rawBridgeSend, checkConnection: () => ... })
 *   await queue.enqueue({ cmd: 'logReport', data: {...}, priority: MQTT_PRIORITY.LOW })
 */

export const MQTT_PRIORITY = Object.freeze({
  HIGH: 0,
  NORMAL: 1,
  LOW: 2
})

const PRIORITY_LABELS = ['HIGH', 'NORMAL', 'LOW']

/**
 * @param {Object} options
 * @param {Function} options.nativeSend - (cmd, data, options) => Promise — 底层桥接发送
 * @param {Function} options.checkConnection - () => Promise<boolean> — MQTT 已连接？
 * @param {Function} [options.now] - 时间戳工厂（可注入用于测试）
 * @param {number} [options.maxQueueSize=500] - 队列最大条目数
 * @param {number} [options.maxLowItems=300] - LOW 优先级最大条目数（超量丢弃最早）
 * @param {Function} [options.onDrop] - (entry, reason) => void — 丢弃回调
 * @param {number} [options.reconnectCheckIntervalMs=10000] - 连接断开后排空重试间隔
 */
export function createMqttSendQueue({
  nativeSend,
  checkConnection,
  now = Date.now,
  maxQueueSize = 500,
  maxLowItems = 300,
  onDrop = null,
  reconnectCheckIntervalMs = 10000
} = {}) {
  if (typeof nativeSend !== 'function') {
    throw new Error('mqttSendQueue: nativeSend is required')
  }
  if (typeof checkConnection !== 'function') {
    throw new Error('mqttSendQueue: checkConnection is required')
  }

  // ── 内部状态 ──
  const queue = [] // 按 priority 升序、createdAt 升序排列
  let drainPromise = null
  let paused = false
  let pauseTimer = null
  let dropStats = { HIGH: 0, NORMAL: 0, LOW: 0, overflow: 0 }
  let sentStats = { HIGH: 0, NORMAL: 0, LOW: 0 }
  let sendErrorCount = 0
  let destroyed = false

  // ── 辅助 ──

  const label = (priority) => PRIORITY_LABELS[priority] || 'UNKNOWN'

  /**
   * 二分查找插入位置：按 priority 升序，同 priority 按 createdAt 升序
   */
  const findInsertIndex = (priority, createdAt) => {
    let lo = 0
    let hi = queue.length
    while (lo < hi) {
      const mid = (lo + hi) >>> 1
      const entry = queue[mid]
      if (entry.priority < priority || (entry.priority === priority && entry.createdAt <= createdAt)) {
        lo = mid + 1
      } else {
        hi = mid
      }
    }
    return lo
  }

  /**
   * 丢弃最早的一条 LOW 优先级条目
   */
  const dropOldestLow = () => {
    for (let i = queue.length - 1; i >= 0; i--) {
      if (queue[i].priority === MQTT_PRIORITY.LOW) {
        const entry = queue.splice(i, 1)[0]
        dropStats.LOW++
        if (onDrop) {
          try { onDrop(entry, 'LOW_OVERFLOW') } catch (e) { /* silent */ }
        }
        entry.reject(new Error('MQTT send queue overflow: LOW priority dropped'))
        return true
      }
    }
    return false
  }

  /**
   * 拒绝新条目（队列满且无法丢弃 LOW）
   */
  const rejectWithOverflow = (entry) => {
    dropStats.overflow++
    if (onDrop) {
      try { onDrop(entry, 'QUEUE_FULL') } catch (e) { /* silent */ }
    }
    entry.reject(new Error('MQTT send queue overflow: queue full'))
  }

  // ── 公共接口 ──

  /**
   * 入队
   * @param {{ cmd: string, data: any, options?: object, priority?: number, fireAndForget?: boolean }} opts
   * @returns {Promise<any>} — nativeSend 的结果
   */
  const enqueue = ({ cmd, data, options = {}, priority = MQTT_PRIORITY.NORMAL, fireAndForget = false }) => {
    if (destroyed) {
      return Promise.reject(new Error('MQTT send queue is destroyed'))
    }

    const p = typeof priority === 'number' ? priority : MQTT_PRIORITY.NORMAL
    const createdAt = now()

    // 容量检查
    if (queue.length >= maxQueueSize) {
      if (p === MQTT_PRIORITY.LOW) {
        if (!dropOldestLow()) {
          return Promise.reject(new Error('MQTT send queue overflow: no LOW item to drop'))
        }
      } else {
        return Promise.reject(new Error('MQTT send queue overflow: queue full, priority=' + label(p)))
      }
    }

    // LOW 优先级专属上限检查
    if (p === MQTT_PRIORITY.LOW) {
      const lowCount = queue.filter(e => e.priority === MQTT_PRIORITY.LOW).length
      if (lowCount >= maxLowItems) {
        if (!dropOldestLow()) {
          return Promise.reject(new Error('MQTT send queue: LOW priority limit exceeded'))
        }
      }
    }

    const entry = { cmd, data, options, priority: p, fireAndForget, createdAt, resolve: null, reject: null }

    const promise = new Promise((resolve, reject) => {
      entry.resolve = (value) => {
        sentStats[label(p)]++
        resolve(value)
      }
      entry.reject = reject
    })

    const insertIdx = findInsertIndex(p, createdAt)
    queue.splice(insertIdx, 0, entry)
    startDrain()
    return promise
  }

  /**
   * 通知队列连接已恢复，立即尝试排空
   */
  const notifyConnected = () => {
    paused = false
    if (pauseTimer) {
      clearTimeout(pauseTimer)
      pauseTimer = null
    }
    startDrain()
  }

  /**
   * 获取统计快照
   */
  const getStats = () => ({
    queued: queue.length,
    paused,
    drops: { ...dropStats },
    sent: { ...sentStats },
    errors: sendErrorCount
  })

  /**
   * 销毁队列：清理定时器、拒绝所有等待条目
   */
  const destroy = () => {
    destroyed = true
    if (pauseTimer) {
      clearTimeout(pauseTimer)
      pauseTimer = null
    }
    while (queue.length > 0) {
      const entry = queue.shift()
      entry.reject(new Error('MQTT send queue destroyed'))
    }
    drainPromise = null
  }

  // ── 排空循环 ──

  const startDrain = () => {
    if (drainPromise) return
    if (destroyed) return
    drainPromise = drainLoop().finally(() => {
      drainPromise = null
    })
  }

  const drainLoop = async () => {
    while (!destroyed && queue.length > 0 && !paused) {
      const entry = queue[0] // 不移除，发送成功再移除

      try {
        // 连接检查
        if (!entry._connChecked) {
          try {
            const connected = await checkConnection()
            if (!connected) {
              pauseForReconnect('MQTT_DISCONNECTED')
              return
            }
            entry._connChecked = true
          } catch (connError) {
            // 连接检查失败，也视为断连
            pauseForReconnect('CONNECTION_CHECK_FAILED')
            console.warn('[mqtt-queue] connection check failed:', connError?.message || connError)
            return
          }
        }

        const result = await nativeSend(entry.cmd, entry.data, entry.options)
        queue.shift() // 发送成功，移除
        sendErrorCount = 0
        entry.resolve(entry.fireAndForget ? result : result)
      } catch (sendError) {
        sendErrorCount++
        const msg = sendError?.message || String(sendError)

        if (msg.includes('not connected') || msg.includes('Not connected') ||
            msg.includes('NOT_CONNECTED') || msg.includes('disconnected')) {
          // 连接错误：不消耗条目，暂停排空
          pauseForReconnect(msg)
          return
        }

        // 非连接错误：LOW 优先级 fire-and-forget 直接丢弃
        if (entry.fireAndForget && entry.priority === MQTT_PRIORITY.LOW) {
          queue.shift()
          dropStats.LOW++
          if (onDrop) {
            try { onDrop(entry, 'SEND_FAILED') } catch (e) { /* silent */ }
          }
          entry.resolve({ dropped: true, reason: 'SEND_FAILED', error: msg })
          continue
        }

        // HIGH/NORMAL 连接错误外的发送失败：累计到一定次数后暂停
        if (sendErrorCount >= 5) {
          pauseForReconnect('TOO_MANY_SEND_ERRORS')
          return
        }

        queue.shift()
        entry.reject(sendError)
      }
    }
  }

  const pauseForReconnect = (reason) => {
    if (paused || destroyed) return
    paused = true
    sendErrorCount = 0
    console.warn('[mqtt-queue] paused, reason:', reason, 'pending:', queue.length)
    scheduleReconnectCheck()
  }

  const scheduleReconnectCheck = () => {
    if (pauseTimer || destroyed) return
    pauseTimer = setTimeout(() => {
      pauseTimer = null
      if (!paused || destroyed) return
      // 尝试恢复：先检查连接
      checkConnection()
        .then((connected) => {
          if (connected && paused && !destroyed) {
            paused = false
            console.log('[mqtt-queue] connection restored, resuming drain')
            startDrain()
          } else if (!connected && !destroyed) {
            scheduleReconnectCheck()
          }
        })
        .catch(() => {
          if (!destroyed) scheduleReconnectCheck()
        })
    }, reconnectCheckIntervalMs)
  }

  return {
    enqueue,
    notifyConnected,
    getStats,
    destroy,
    /** 队列中等待条目数 */
    get pending() { return queue.length },
    /** 是否暂停排空 */
    get isPaused() { return paused }
  }
}
