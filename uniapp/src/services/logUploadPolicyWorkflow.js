export const LOG_UPLOAD_COMMANDS = Object.freeze({
  enableLogUpload: true,
  disableLogUpload: false
})

export const LOG_UPLOAD_COMMAND_NAMES = Object.freeze(Object.keys(LOG_UPLOAD_COMMANDS))

const requiredDependency = (value, name) => {
  if (typeof value !== 'function') {
    throw new Error(`log upload policy dependency is missing: ${name}`)
  }
  return value
}

export function createLogUploadPolicyWorkflow({
  findProcessed,
  loadPolicy,
  savePolicy,
  markProcessed,
  now = Date.now
} = {}) {
  requiredDependency(findProcessed, 'findProcessed')
  requiredDependency(loadPolicy, 'loadPolicy')
  requiredDependency(savePolicy, 'savePolicy')
  requiredDependency(markProcessed, 'markProcessed')
  requiredDependency(now, 'now')

  const inFlight = new Map()

  const execute = async (message, command, msgId) => {
    const expectedEnabled = LOG_UPLOAD_COMMANDS[command]
    if (message.data?.enabled !== expectedEnabled) {
      return {
        handled: true,
        applied: false,
        reason: 'INVALID_ENABLED_VALUE',
        msgId
      }
    }

    const processed = await findProcessed(msgId)
    if (processed) {
      return {
        handled: true,
        applied: false,
        reused: true,
        enabled: processed.payload?.enabled === true,
        msgId
      }
    }

    const current = await loadPolicy()
    const policy = {
      enabled: expectedEnabled,
      operatorId: String(message.data?.operatorId || '').trim(),
      command,
      msgId,
      updatedAt: Number(now())
    }
    await savePolicy(policy)
    await markProcessed(policy)
    return {
      handled: true,
      applied: current?.enabled !== expectedEnabled,
      reused: false,
      enabled: expectedEnabled,
      msgId
    }
  }

  return async function handleLogUploadPolicyCommand(message = {}) {
    const command = String(message.cmd || '').trim()
    if (!Object.prototype.hasOwnProperty.call(LOG_UPLOAD_COMMANDS, command)) {
      return { handled: false, reason: 'UNHANDLED_COMMAND' }
    }

    const msgId = String(message.msgId || '').trim()
    if (!msgId) {
      return { handled: true, applied: false, reason: 'MISSING_MSG_ID' }
    }
    if (inFlight.has(msgId)) return inFlight.get(msgId)

    const promise = execute(message, command, msgId)
    inFlight.set(msgId, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(msgId)
    }
  }
}
