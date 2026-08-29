export function createCardEventRetryScheduler({
  flush,
  retryDelayMs = 10000,
  setTimer = (callback, delay) => setTimeout(callback, delay),
  clearTimer = (timer) => clearTimeout(timer),
  onError = () => {}
} = {}) {
  if (typeof flush !== 'function') {
    throw new Error('card event retry scheduler flush dependency is missing')
  }

  const requestedDelayMs = Number(retryDelayMs)
  const delayMs = Number.isFinite(requestedDelayMs) && requestedDelayMs > 0
    ? Math.max(1000, requestedDelayMs)
    : 10000
  let timer = null
  let inFlight = null
  let stopped = false

  const schedule = (reason = 'retry') => {
    if (stopped) return { scheduled: false, reason: 'STOPPED' }
    if (timer !== null) return { scheduled: true, reason, reused: true }
    timer = setTimer(() => {
      timer = null
      flushNow(`scheduled:${reason}`).catch(onError)
    }, delayMs)
    return { scheduled: true, reason, dueIn: delayMs }
  }

  const flushNow = async (reason = 'manual', limit = 20) => {
    if (inFlight) return inFlight
    const task = Promise.resolve().then(() => flush(limit, reason))
    inFlight = task
    try {
      const result = await task
      if (Number(result?.failed || 0) > 0) schedule('flush-failed')
      return result
    } catch (error) {
      schedule('flush-error')
      throw error
    } finally {
      if (inFlight === task) inFlight = null
    }
  }

  const cancel = () => {
    stopped = true
    if (timer !== null) clearTimer(timer)
    timer = null
  }

  return {
    schedule,
    flushNow,
    cancel,
    isScheduled: () => timer !== null
  }
}
