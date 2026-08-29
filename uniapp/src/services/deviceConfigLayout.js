const hasOwn = (source, key) => Object.prototype.hasOwnProperty.call(source, key)

const positiveInteger = (value) => {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

/**
 * 后端的旧字段必须在与本机缓存合并前归一，避免缓存的 canonical 字段覆盖远端值。
 */
export const canonicalizeRemoteDeviceConfigLayout = (config = {}) => {
  const source = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  const next = { ...source }

  if (hasOwn(source, 'totalSlots') || hasOwn(source, 'totalCount')) {
    const totalSlots = positiveInteger(source.totalSlots ?? source.totalCount)
    if (totalSlots != null) {
      next.totalSlots = totalSlots
      next.totalCount = totalSlots
    }
  }

  if (hasOwn(source, 'groupSize') || hasOwn(source, 'singleGroupCount')) {
    const groupSize = positiveInteger(source.groupSize ?? source.singleGroupCount)
    if (groupSize != null) {
      next.groupSize = groupSize
      next.singleGroupCount = groupSize
    }
  }

  if (hasOwn(source, 'slotSortDirection') || hasOwn(source, 'slotLayoutDirection')) {
    const direction = String((source.slotSortDirection ?? source.slotLayoutDirection) || '').toUpperCase()
    next.slotSortDirection = direction === 'VERTICAL' ? 'VERTICAL' : 'HORIZONTAL'
  }

  return next
}
