import { createUniqueMessageId } from './messageId.js'

export const DIAGNOSTIC_OUTBOX_TYPES = Object.freeze({
  HARDWARE_FAULT: 'HARDWARE_FAULT',
  SELF_CHECK_REPORT: 'SELF_CHECK_REPORT'
})

const DEFINITIONS = Object.freeze({
  [DIAGNOSTIC_OUTBOX_TYPES.HARDWARE_FAULT]: {
    mqttCmd: 'hardwareFault',
    responseCmd: 'hardwareFaultResp'
  },
  [DIAGNOSTIC_OUTBOX_TYPES.SELF_CHECK_REPORT]: {
    mqttCmd: 'selfCheckReport',
    responseCmd: 'selfCheckReportResp'
  }
})

const normalizeCommunicationMode = (value) => {
  const mode = String(value || '').toUpperCase()
  return ['MQTT', 'HTTP', 'BOTH'].includes(mode) ? mode : 'MQTT'
}

const requiredText = (value, field) => {
  const text = String(value || '').trim()
  if (!text) throw new Error(`${field} is required`)
  return text
}

const normalizeTimestamp = (value, fallback) => {
  const timestamp = Number(value)
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : fallback
}

const normalizeHardwareFaultData = (value = {}, now) => {
  const slotId = Number(value.slotId)
  const faultCode = Number(value.faultCode)
  if (!Number.isInteger(slotId) || slotId < 1) throw new Error('hardware fault slotId is invalid')
  if (!Number.isInteger(faultCode) || faultCode < 1) throw new Error('hardware fault faultCode is invalid')
  return {
    slotId,
    faultCode,
    faultMsg: requiredText(value.faultMsg, 'hardware fault faultMsg'),
    timestamp: normalizeTimestamp(value.timestamp, now)
  }
}

const normalizeSelfCheckData = (value = {}, now) => {
  const result = String(value.result || '').trim().toLowerCase()
  if (!['pass', 'fail'].includes(result)) throw new Error('self check result is invalid')
  const details = Array.isArray(value.details) ? value.details.map((item = {}) => {
    const status = String(item.status || '').trim().toLowerCase()
    if (!['pass', 'fail'].includes(status)) throw new Error('self check detail status is invalid')
    return {
      name: requiredText(item.name, 'self check detail name'),
      status,
      ...(item.errorMsg ? { errorMsg: String(item.errorMsg) } : {})
    }
  }) : []
  return {
    result,
    details,
    timestamp: normalizeTimestamp(value.timestamp, now)
  }
}

const readFaultCode = (slot = {}) => {
  for (const candidate of [slot.faultMask, slot.faultCode]) {
    if (candidate === '' || candidate == null) continue
    const value = Number(candidate)
    if (Number.isInteger(value) && value >= 0) return value
  }
  return null
}

export function buildHardwareFaultTransition(previous = null, current = null, observedAt = Date.now()) {
  if (!current || typeof current !== 'object') return null
  const slotId = Number(current.slotNumber ?? current.slotId ?? current.address)
  const faultCode = readFaultCode(current)
  if (!Number.isInteger(slotId) || slotId < 1 || !Number.isInteger(faultCode) || faultCode < 1) return null
  if (readFaultCode(previous || {}) === faultCode) return null
  const faultMsg = String(current.faultMessage || current.faultMsg || '').trim()
  if (!faultMsg) return null
  return {
    slotId,
    faultCode,
    faultMsg,
    timestamp: normalizeTimestamp(current.updatedAt ?? current.timestamp, observedAt)
  }
}

const defaultMessageId = (eventType) => {
  const prefix = `diagnostic_${String(eventType || '').toLowerCase()}`
  return createUniqueMessageId(prefix)
}

export function createDiagnosticDeliveryWorkflow({
  saveEvent,
  listDueEvents,
  markSent,
  markFailed,
  getCommunicationMode,
  isMqttConnected,
  sendMqttAndWaitForAck,
  sendHttp,
  validateMqttAck,
  validateHttpAck,
  createMessageId = defaultMessageId,
  now = () => Date.now(),
  retryDelayMs = 10000
} = {}) {
  const dependencies = [
    saveEvent,
    listDueEvents,
    markSent,
    markFailed,
    getCommunicationMode,
    isMqttConnected,
    sendMqttAndWaitForAck,
    sendHttp,
    validateMqttAck,
    validateHttpAck,
    createMessageId,
    now
  ]
  if (dependencies.some((dependency) => typeof dependency !== 'function')) {
    throw new Error('diagnostic delivery workflow dependencies are incomplete')
  }

  let sequence = Promise.resolve()
  const enqueue = (task) => {
    const next = sequence.then(task, task)
    sequence = next.catch(() => {})
    return next
  }

  const deliver = async (event) => {
    const eventType = String(event?.eventType || '').trim()
    const definition = DEFINITIONS[eventType]
    const msgId = String(event?.payload?.msgId || '').trim()
    try {
      if (!definition || !msgId || !event?.payload?.data) {
        throw new Error('diagnostic outbox payload is incomplete')
      }
      const mode = normalizeCommunicationMode(getCommunicationMode())
      const mqttConnected = mode !== 'HTTP' && await isMqttConnected()
      let result
      let transport
      if (mqttConnected) {
        result = await sendMqttAndWaitForAck(eventType, event.payload.data, msgId, definition)
        validateMqttAck(result, eventType)
        transport = 'MQTT'
      } else {
        result = await sendHttp(eventType, event.payload.data)
        validateHttpAck(result, eventType)
        transport = 'HTTP'
      }
      await markSent(event.eventId)
      return { sent: true, queued: false, transport, msgId, result, payload: event.payload.data }
    } catch (error) {
      await markFailed(event?.eventId || `diagnostic:invalid:${now()}`, error, retryDelayMs)
      return {
        sent: false,
        queued: true,
        msgId,
        error: error?.message || String(error),
        payload: event?.payload?.data || null
      }
    }
  }

  const persistAndDeliver = async (eventType, data) => {
    const msgId = requiredText(createMessageId(eventType, data), 'diagnostic msgId')
    const event = await saveEvent({
      eventId: `diagnostic:${msgId}`,
      eventType,
      payload: { data, msgId, createdAt: now() },
      state: 'PENDING',
      attemptCount: 0,
      nextAttemptAt: 0,
      lastError: '',
      ackedAt: 0
    })
    if (!event) throw new Error('diagnostic outbox could not be saved')
    return deliver(event)
  }

  const reportHardwareFault = (value) => enqueue(() => persistAndDeliver(
    DIAGNOSTIC_OUTBOX_TYPES.HARDWARE_FAULT,
    normalizeHardwareFaultData(value, now())
  ))

  const reportSelfCheck = (value) => enqueue(() => persistAndDeliver(
    DIAGNOSTIC_OUTBOX_TYPES.SELF_CHECK_REPORT,
    normalizeSelfCheckData(value, now())
  ))

  const flush = (reason = 'manual', limit = 20) => enqueue(async () => {
    const boundedLimit = Math.max(1, Number(limit || 20))
    const groups = await Promise.all(
      Object.values(DIAGNOSTIC_OUTBOX_TYPES).map((type) => listDueEvents(type, boundedLimit))
    )
    const events = groups
      .flat()
      .sort((left, right) => Number(left?.createdAt || 0) - Number(right?.createdAt || 0))
      .slice(0, boundedLimit)
    let flushed = 0
    let failed = 0
    for (const event of events) {
      const result = await deliver(event)
      if (result.sent) flushed += 1
      else failed += 1
    }
    return { flushed, failed, reason, empty: events.length === 0 }
  })

  return { reportHardwareFault, reportSelfCheck, flush }
}
