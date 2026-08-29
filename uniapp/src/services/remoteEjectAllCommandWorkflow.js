export const REMOTE_EJECT_ALL_COMMAND = Object.freeze({
  requestCmd: 'remoteEjectAll',
  responseCmd: 'remoteEjectAllResp',
  operationType: 'REMOTE_EJECT_ALL'
})

const responseResult = (delivery, msgId, data, extra = {}) => ({
  responded: delivery.sent,
  queued: delivery.queued === true,
  responseCmd: REMOTE_EJECT_ALL_COMMAND.responseCmd,
  msgId: delivery.msgId || msgId,
  data,
  ...extra
})

export function createRemoteEjectAllCommandWorkflow({
  findResponse,
  markProcessing,
  executeEjectAll,
  recordFailure,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof executeEjectAll !== 'function' ||
      typeof recordFailure !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('remote eject all command workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (message, msgId) => {
    const existing = await findResponse(msgId)
    if (existing?.payload?.data != null) {
      const delivery = await sendResponse(existing.payload.data, msgId)
      return responseResult(delivery, msgId, existing.payload.data, { reused: true })
    }
    if (existing?.state === 'PROCESSING') {
      const data = {
        code: 500,
        msg: '上次远程弹卡执行结果未确认，客户端未重复执行'
      }
      const delivery = await sendResponse(data, msgId)
      return responseResult(delivery, msgId, data, { recovered: true })
    }

    await markProcessing(msgId)
    const operationId = `remoteEjectAll:${msgId}`
    const commandData = message?.data && typeof message.data === 'object' ? message.data : {}
    const operatorId = String(commandData.operatorId || '').trim()
    let data
    try {
      if (commandData.confirm !== true) throw new Error('confirm 必须为 true')
      if (!operatorId) throw new Error('operatorId 不能为空')
      const result = await executeEjectAll({ operationId, operatorId, msgId })
      const failedCount = Number(result?.failedCount)
      if (result?.accepted !== true || (!result?.queued && (!Number.isFinite(failedCount) || failedCount !== 0))) {
        throw new Error(result?.message || '远程弹卡未被全部接受')
      }
      data = result?.queued === true
        ? { code: 0, msg: result.message || '已受理，正在逐槽开门' }
        : { code: 0, msg: 'success' }
    } catch (error) {
      data = { code: 500, msg: error?.message || '远程弹卡执行失败' }
      try {
        await recordFailure({ operationId, operatorId: operatorId || '后台', msgId, data })
      } catch (recordError) {
        console.warn('[mqtt] remoteEjectAll failure history could not be recorded:', recordError)
      }
    }

    const delivery = await sendResponse(data, msgId)
    return responseResult(delivery, msgId, data)
  }

  return async function handleRemoteEjectAllCommand(message = {}) {
    if (String(message.cmd || '').trim() !== REMOTE_EJECT_ALL_COMMAND.requestCmd) {
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
