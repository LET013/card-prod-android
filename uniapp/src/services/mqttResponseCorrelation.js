const DUPLICATE_MSG_ID_PATTERN = /(?:^|[\s:：,，])msgId\s*=\s*([A-Za-z0-9._:-]+)/i

function duplicateRequestMsgId(message = {}) {
  const text = String(message?.data?.msg || message?.data?.message || '').trim()
  const matched = text.match(DUPLICATE_MSG_ID_PATTERN)
  return matched ? matched[1] : ''
}

/**
 * Selects one pending response waiter without weakening command correlation.
 *
 * V4.2 expects a response to reuse the uplink msgId. The current backend has
 * also been observed returning a new response-envelope UUID. For that server
 * behavior we first recover an explicitly quoted duplicate msgId, then fall
 * back only when exactly one waiter exists for the same response command.
 */
export function selectMqttResponseCorrelation(pendingKeys, message = {}) {
  const cmd = String(message?.cmd || '').trim()
  if (!cmd) return null

  const keys = Array.from(pendingKeys || [])
  const envelopeMsgId = String(message?.msgId || '').trim()
  const exactKey = envelopeMsgId ? `${cmd}:${envelopeMsgId}` : ''
  if (exactKey && keys.includes(exactKey)) {
    return { key: exactKey, requestMsgId: envelopeMsgId, mode: 'EXACT' }
  }

  const duplicateMsgId = duplicateRequestMsgId(message)
  const duplicateKey = duplicateMsgId ? `${cmd}:${duplicateMsgId}` : ''
  if (duplicateKey && keys.includes(duplicateKey)) {
    return { key: duplicateKey, requestMsgId: duplicateMsgId, mode: 'DUPLICATE_MSG' }
  }

  const commandPrefix = `${cmd}:`
  const commandKeys = keys.filter((key) => String(key).startsWith(commandPrefix))
  if (commandKeys.length !== 1) return null
  return {
    key: commandKeys[0],
    requestMsgId: commandKeys[0].slice(commandPrefix.length),
    mode: 'SOLE_PENDING_COMMAND'
  }
}
