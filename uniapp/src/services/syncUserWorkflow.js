export function createSyncUserWorkflow({
  findResponse,
  markProcessing,
  syncUser,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof syncUser !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('syncUser workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (message, originalMsgId) => {
    const existing = await findResponse(originalMsgId)
    if (existing?.payload?.data != null) {
      const reused = await sendResponse(existing.payload.data, originalMsgId)
      return {
        responded: reused.sent,
        queued: reused.queued === true,
        reused: true,
        msgId: originalMsgId,
        data: existing.payload.data
      }
    }

    await markProcessing(originalMsgId)
    let responseData = { code: 0, msg: 'success' }
    try {
      await syncUser({ source: 'MQTT_SYNC_USER', message })
    } catch (error) {
      responseData = {
        code: 500,
        msg: error?.message || 'syncUser failed'
      }
    }

    const sent = await sendResponse(responseData, originalMsgId)
    return {
      responded: sent.sent,
      queued: sent.queued === true,
      msgId: sent.msgId || originalMsgId,
      data: responseData
    }
  }

  return async function handleSyncUserCommand(message = {}) {
    const originalMsgId = String(message.msgId || '').trim()
    if (!originalMsgId) {
      return { responded: false, reason: 'MISSING_MSG_ID' }
    }
    if (inFlight.has(originalMsgId)) return inFlight.get(originalMsgId)

    const promise = execute(message, originalMsgId)
    inFlight.set(originalMsgId, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(originalMsgId)
    }
  }
}
