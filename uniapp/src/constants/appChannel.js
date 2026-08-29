export function normalizeAppChannelId(value) {
  return String(value || '').trim()
}

export function resolveAppChannelLabel(value) {
  const channelId = normalizeAppChannelId(value)
  return channelId || '未读取'
}
