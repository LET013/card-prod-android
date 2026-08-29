import { createUniqueMessageId } from './messageId.js'

export const STATUS_REPORT_OUTBOX_TYPE = 'STATUS_REPORT'
export const STATUS_REPORT_EVENT_ID = 'status-report:latest'

const normalizeCommunicationMode = (value) => {
  const mode = String(value || '').toUpperCase()
  return ['MQTT', 'HTTP', 'BOTH'].includes(mode) ? mode : 'MQTT'
}

const defaultMessageId = () => createUniqueMessageId('status')

export function createStatusReportWorkflow({
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
    createMessageId
  ]
  if (dependencies.some((dependency) => typeof dependency !== 'function')) {
    throw new Error('status report workflow dependencies are incomplete')
  }

  let deliveryTail = Promise.resolve()

  const deliver = async (event) => {
    const data = event?.payload?.data
    const msgId = String(event?.payload?.msgId || '').trim()
    if (!data || !Array.isArray(data.slots) || !data.slots.length || !msgId) {
      const error = new Error('status report outbox payload is incomplete')
      await markFailed(event?.eventId || STATUS_REPORT_EVENT_ID, error, retryDelayMs)
      return { sent: false, queued: true, error: error.message }
    }

    try {
      const mode = normalizeCommunicationMode(getCommunicationMode())
      const mqttConnected = mode !== 'HTTP' && await isMqttConnected()
      let result
      let transport
      if (mqttConnected) {
        result = await sendMqttAndWaitForAck(data, msgId)
        validateMqttAck(result)
        transport = 'MQTT'
      } else {
        result = await sendHttp(data)
        validateHttpAck(result)
        transport = 'HTTP'
      }
      await markSent(event.eventId)
      return { sent: true, queued: false, transport, msgId, result, payload: data }
    } catch (error) {
      await markFailed(event.eventId, error, retryDelayMs)
      return {
        sent: false,
        queued: true,
        msgId,
        error: error?.message || String(error),
        payload: data
      }
    }
  }

  const runSerialized = (task) => {
    const delivery = deliveryTail
      .catch(() => {})
      .then(task)
    deliveryTail = delivery.catch(() => {})
    return delivery
  }

  const report = async (data) => runSerialized(async () => {
    if (!data || !Array.isArray(data.slots) || !data.slots.length) {
      return { sent: false, queued: false, reason: 'NO_REPORTABLE_SLOTS', payload: data || { slots: [] } }
    }
    const msgId = String(createMessageId() || '').trim()
    if (!msgId) throw new Error('status report msgId is required')
    const event = await saveEvent({
      eventId: STATUS_REPORT_EVENT_ID,
      eventType: STATUS_REPORT_OUTBOX_TYPE,
      payload: { data, msgId, updatedAt: Date.now() },
      state: 'PENDING',
      attemptCount: 0,
      nextAttemptAt: 0,
      lastError: '',
      ackedAt: 0
    })
    if (!event) throw new Error('status report outbox could not be saved')
    return deliver(event)
  })

  const flush = async (reason = 'manual') => runSerialized(async () => {
    const events = await listDueEvents(STATUS_REPORT_OUTBOX_TYPE, 1)
    if (!events.length) return { flushed: 0, failed: 0, reason, empty: true }
    const result = await deliver(events[0])
    return {
      flushed: result.sent ? 1 : 0,
      failed: result.sent ? 0 : 1,
      queued: result.queued === true,
      reason,
      result
    }
  })

  return { report, flush }
}
