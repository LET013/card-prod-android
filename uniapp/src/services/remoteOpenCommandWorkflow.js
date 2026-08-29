export const REMOTE_OPEN_COMMAND = Object.freeze({
  requestCmd: 'remoteOpen',
  responseCmd: 'remoteOpenResp',
  operationType: 'REMOTE_OPEN'
})

const responseResult = (delivery, msgId, data, extra = {}) => ({
  responded: delivery.sent,
  queued: delivery.queued === true,
  responseCmd: REMOTE_OPEN_COMMAND.responseCmd,
  msgId: delivery.msgId || msgId,
  data,
  ...extra
})

export function createRemoteOpenCommandWorkflow({
  findResponse,
  markProcessing,
  executeOpen,
  recordFailure,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof executeOpen !== 'function' ||
      typeof recordFailure !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('remote open command workflow dependencies are incomplete')
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
        msg: '上次远程开门执行结果未确认，客户端未重复执行'
      }
      const delivery = await sendResponse(data, msgId)
      return responseResult(delivery, msgId, data, { recovered: true })
    }

    await markProcessing(msgId)
    const operationId = 'remoteOpen:' + msgId
    const commandData = message?.data && typeof message.data === 'object' ? message.data : {}
    const slotId = Number(commandData.slotId)
    const operatorId = String(commandData.operatorId || '').trim()
    const authType = String(commandData.authType || '').trim().toUpperCase()
    let data
    try {
      if (!Number.isInteger(slotId) || slotId < 1) throw new Error('slotId 必须为正整数')
      if (authType !== 'ADMIN') throw new Error('authType 必须为 ADMIN')
      if (!operatorId) throw new Error('operatorId 不能为空')
      const result = await executeOpen({ operationId, operatorId, msgId, slotId, authType })
      if (result?.accepted !== true || result?.confirmed !== true) {
        throw new Error(result?.message || '远程开门未取得实际开门确认')
      }
      data = { code: 0, msg: 'success' }
    } catch (error) {
      data = { code: 500, msg: error?.message || '远程开门执行失败' }
      try {
        await recordFailure({
          operationId,
          operatorId: operatorId || '后台',
          msgId,
          slotId: Number.isInteger(slotId) ? slotId : null,
          authType,
          data
        })
      } catch (recordError) {
        console.warn('[mqtt] remoteOpen failure history could not be recorded:', recordError)
      }
    }

    const delivery = await sendResponse(data, msgId)
    return responseResult(delivery, msgId, data)
  }

  return async function handleRemoteOpenCommand(message = {}) {
    if (String(message.cmd || '').trim() !== REMOTE_OPEN_COMMAND.requestCmd) {
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
