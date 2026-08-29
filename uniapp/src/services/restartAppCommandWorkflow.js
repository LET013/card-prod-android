export const RESTART_APP_COMMAND = Object.freeze({
  requestCmd: 'restartApp',
  responseCmd: 'restartAppResp',
  operationType: 'RESTART_APP'
})

export const RESTART_APP_SUCCESS_RESPONSE = Object.freeze({
  code: 0,
  msg: '重启指令已接受'
})

export function normalizeRestartDelay(value, fallback = 3000) {
  if (value == null || value === '') return fallback
  const delayMs = Number(value)
  if (!Number.isInteger(delayMs) || delayMs < 0) {
    throw new Error('delayMs 必须是非负整数')
  }
  return delayMs
}

export function createRestartAppCommandWorkflow({
  findResponse,
  markProcessing,
  scheduleRestart,
  recordOperation,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof scheduleRestart !== 'function' ||
      typeof recordOperation !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('restart app command workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (message, msgId) => {
    const existing = await findResponse(msgId)
    if (existing?.payload?.data != null) {
      const reused = await sendResponse(existing.payload.data, msgId)
      return {
        responded: reused.sent,
        queued: reused.queued === true,
        reused: true,
        responseCmd: RESTART_APP_COMMAND.responseCmd,
        msgId,
        data: existing.payload.data
      }
    }

    const operationId = `restartApp:remote:${msgId}`
    await markProcessing(msgId)
    let delayMs
    let responseData
    try {
      delayMs = normalizeRestartDelay(message?.data?.delayMs)
      await recordOperation({
        operationId,
        operationType: RESTART_APP_COMMAND.operationType,
        operatorName: '后台',
        state: 'RECEIVED',
        requestMsgId: msgId,
        delayMs
      })
      const nativeResult = await scheduleRestart({ operationId, delayMs })
      await recordOperation({
        operationId,
        operationType: RESTART_APP_COMMAND.operationType,
        operatorName: '后台',
        state: 'RESTART_SCHEDULED',
        requestMsgId: msgId,
        delayMs,
        nativeResult
      })
      responseData = { ...RESTART_APP_SUCCESS_RESPONSE }
    } catch (error) {
      responseData = { code: 500, msg: error?.message || '应用重启安排失败' }
      await recordOperation({
        operationId,
        operationType: RESTART_APP_COMMAND.operationType,
        operatorName: '后台',
        state: 'FAILED',
        requestMsgId: msgId,
        ...(delayMs == null ? {} : { delayMs }),
        rawError: { message: responseData.msg }
      })
    }

    const delivery = await sendResponse(responseData, msgId)
    return {
      responded: delivery.sent,
      queued: delivery.queued === true,
      responseCmd: RESTART_APP_COMMAND.responseCmd,
      msgId: delivery.msgId || msgId,
      data: responseData
    }
  }

  return async function handleRestartAppCommand(message = {}) {
    if (String(message.cmd || '').trim() !== RESTART_APP_COMMAND.requestCmd) {
      return { responded: false, reason: 'UNHANDLED_COMMAND' }
    }
    const msgId = String(message.msgId || '').trim()
    if (!msgId) return { responded: false, reason: 'MISSING_MSG_ID' }
    if (inFlight.has(msgId)) return inFlight.get(msgId)

    const promise = execute(message, msgId)
    inFlight.set(msgId, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(msgId)
    }
  }
}
