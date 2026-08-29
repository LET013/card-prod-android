const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address)

const updatedAtOf = (slot) => {
  const updatedAt = Number(slot?.updatedAt ?? slot?.updated_at)
  return Number.isFinite(updatedAt) ? updatedAt : 0
}

const normalizeSlot = (slot, slotNumber) => ({
  ...slot,
  slotNumber,
  id: slot.id || `slot-${slotNumber}`,
  displayNumber: slot.displayNumber || String(slotNumber).padStart(2, '0')
})

const placeholderSlot = (slotNumber) => ({
  slotNumber,
  id: `slot-${slotNumber}`,
  displayNumber: String(slotNumber).padStart(2, '0'),
  status: 'LOADING'
})

const hasOwn = (target, key) => Object.prototype.hasOwnProperty.call(target, key)

const syncSlotProjection = (target, source) => {
  Object.keys(target).forEach((key) => {
    if (!hasOwn(source, key)) delete target[key]
  })
  Object.entries(source).forEach(([key, value]) => {
    if (!Object.is(target[key], value)) target[key] = value
  })
  return target
}

export function summarizeSlotStatuses(items = []) {
  const counts = Object.create(null)
  for (const slot of Array.isArray(items) ? items : []) {
    const status = String(slot?.status || '').trim().toUpperCase()
    if (!status || status === 'LOADING') continue
    counts[status] = (counts[status] || 0) + 1
  }
  return counts
}

export function normalizeSlotsProjection(items = [], totalSlots = 0) {
  const configuredTotal = Number(totalSlots)
  const hasConfiguredTotal = Number.isInteger(configuredTotal) && configuredTotal > 0
  const slotsByNumber = new Map()

  for (const slot of Array.isArray(items) ? items : []) {
    const slotNumber = slotNumberOf(slot)
    if (!Number.isInteger(slotNumber) || slotNumber < 1) continue
    if (hasConfiguredTotal && slotNumber > configuredTotal) continue

    const current = slotsByNumber.get(slotNumber)
    if (!current || updatedAtOf(slot) >= updatedAtOf(current)) {
      slotsByNumber.set(slotNumber, normalizeSlot(slot, slotNumber))
    }
  }

  if (hasConfiguredTotal) {
    for (let slotNumber = 1; slotNumber <= configuredTotal; slotNumber += 1) {
      if (!slotsByNumber.has(slotNumber)) {
        slotsByNumber.set(slotNumber, placeholderSlot(slotNumber))
      }
    }
  }

  return [...slotsByNumber.values()].sort((left, right) => left.slotNumber - right.slotNumber)
}

/**
 * Preserve the stable array and slot objects whenever cabinet topology is unchanged.
 * This keeps a full serial snapshot from remounting every card in the Vue grid.
 */
export function reconcileSlotsProjection(currentSlots = [], items = [], totalSlots = 0, { fresh = false } = {}) {
  const projected = normalizeSlotsProjection(items, totalSlots)
  if (fresh) projected.forEach((slot) => { slot.fresh = true })

  const current = Array.isArray(currentSlots) ? currentSlots : []
  const currentByNumber = new Map(current.map((slot) => [slotNumberOf(slot), slot]))
  const next = projected.map((slot) => {
    const existing = currentByNumber.get(slot.slotNumber)
    return existing ? syncSlotProjection(existing, slot) : slot
  })
  const topologyChanged = next.length !== current.length || next.some((slot, index) => slot !== current[index])
  if (topologyChanged) current.splice(0, current.length, ...next)
  return { slots: current, topologyChanged }
}
