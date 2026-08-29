export const UNSUPPORTED_DEVICE_COMMANDS = Object.freeze({})

export const UNSUPPORTED_DEVICE_COMMAND_NAMES = Object.freeze(Object.keys(UNSUPPORTED_DEVICE_COMMANDS))

export function createUnsupportedDeviceCommandWorkflow({
  findResponse,
  markProcessing,
  recordFailure,
  sendResponse
} = {}) {
  if (typeof findResponse !== 'function' ||
      typeof markProcessing !== 'function' ||
      typeof recordFailure !== 'function' ||
      typeof sendResponse !== 'function') {
    throw new Error('unsupported device command workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (message, command, originalMsgId) => {
    const responseCmd = command.responseCmd
    const existing = await findResponse(responseCmd, originalMsgId)
    if (existing?.payload?.data != null) {
      const reused = await sendResponse(responseCmd, existing.payload.data, originalMsgId)
      return {
        responded: reused.sent,
        queued: reused.queued === true,
        reused: true,
        responseCmd,
        msgId: originalMsgId,
        data: existing.payload.data
      }
    }

    await markProcessing(responseCmd, originalMsgId)
    const responseData = { code: 500, msg: command.failureMessage }
    try {
      await recordFailure({
        cmd: message.cmd,
        msgId: originalMsgId,
        data: message.data || {},
        command,
        responseData
      })
    } catch (error) {
      console.warn(`[mqtt] ${message.cmd} failure history could not be recorded:`, error)
    }

    const sent = await sendResponse(responseCmd, responseData, originalMsgId)
    return {
      responded: sent.sent,
      queued: sent.queued === true,
      responseCmd,
      msgId: sent.msgId || originalMsgId,
      data: responseData
    }
  }

  return async function handleUnsupportedDeviceCommand(message = {}) {
    const cmd = String(message.cmd || '').trim()
    const command = Object.prototype.hasOwnProperty.call(UNSUPPORTED_DEVICE_COMMANDS, cmd)
      ? UNSUPPORTED_DEVICE_COMMANDS[cmd]
      : null
    if (!command) return { responded: false, reason: 'UNHANDLED_COMMAND' }

    const originalMsgId = String(message.msgId || '').trim()
    if (!originalMsgId) return { responded: false, reason: 'MISSING_MSG_ID' }

    const inFlightKey = `${cmd}:${originalMsgId}`
    if (inFlight.has(inFlightKey)) return inFlight.get(inFlightKey)

    const promise = execute(message, command, originalMsgId)
    inFlight.set(inFlightKey, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(inFlightKey)
    }
  }
}
