/**
 * 高熵 MQTT msgId 生成器
 *
 * 旧实现仅使用 Date.now() + 8 位 Math.random() 十六进制，在大量消息、设备时间回拨
 * 或同一毫秒内并发时冲突概率高，导致服务器去重拦截。新实现：
 * 1. 保留原 prefix_timestamp_random 的可读性；
 * 2. 随机部分升级为 16 位十六进制（64 bit）；
 * 3. 附加进程内自增序列号，避免同一毫秒 + 同随机数冲突；
 * 4. 优先使用 Web Crypto API，不可用时回退到 Math.random。
 */

let seqCounter = 0

function randomHex(length = 16) {
  try {
    if (
      typeof crypto !== 'undefined' &&
      typeof crypto.getRandomValues === 'function'
    ) {
      const bytes = new Uint8Array(Math.ceil(length / 2))
      crypto.getRandomValues(bytes)
      return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('').slice(0, length)
    }
  } catch (_e) {
    // ignore
  }

  let hex = ''
  while (hex.length < length) {
    hex += Math.random().toString(16).slice(2)
  }
  return hex.slice(0, length)
}

function nextSeq() {
  seqCounter = (seqCounter + 1) % 0xffffff
  return seqCounter.toString(16).padStart(6, '0')
}

export function createUniqueMessageId(prefix = '') {
  const base = `${Date.now()}_${randomHex(16)}_${nextSeq()}`
  return prefix ? `${prefix}_${base}` : base
}
