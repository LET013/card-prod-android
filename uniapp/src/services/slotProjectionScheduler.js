const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address)
const SLOT_PROJECTION_FLUSH_DELAY_MS = 100

const defaultSchedule = (callback) => setTimeout(callback, SLOT_PROJECTION_FLUSH_DELAY_MS)

/**
 * Limits normal Vue projection work to one latest-state update per short window.
 * Physical-operation listeners stay on nativeBridge and continue to receive raw events.
 */
export function createSlotProjectionScheduler({
  applySnapshot,
  applySlotUpdates,
  schedule = defaultSchedule
} = {}) {
  if (typeof applySnapshot !== 'function' || typeof applySlotUpdates !== 'function') {
    throw new Error('slot projection scheduler callbacks are required')
  }

  let pendingSnapshot = null
  const pendingSnapshotOverrides = new Map()
  const pendingSlotUpdates = new Map()
  let scheduled = false

  const flush = () => {
    scheduled = false
    if (pendingSnapshot) {
      const overrides = pendingSnapshotOverrides
      const snapshotNumbers = new Set()
      const snapshot = pendingSnapshot.map((slot) => {
        const slotNumber = slotNumberOf(slot)
        snapshotNumbers.add(slotNumber)
        return overrides.get(slotNumber) || slot
      })
      overrides.forEach((slot, slotNumber) => {
        if (!snapshotNumbers.has(slotNumber)) snapshot.push(slot)
      })
      pendingSnapshot = null
      pendingSnapshotOverrides.clear()
      applySnapshot(snapshot)
      return
    }

    if (!pendingSlotUpdates.size) return
    const updates = Array.from(pendingSlotUpdates.values())
    pendingSlotUpdates.clear()
    applySlotUpdates(updates)
  }

  const requestFlush = () => {
    if (scheduled) return
    scheduled = true
    schedule(flush)
  }

  return {
    enqueueSnapshot(slots) {
      if (!Array.isArray(slots)) return
      pendingSnapshot = slots
      pendingSnapshotOverrides.clear()
      pendingSlotUpdates.clear()
      requestFlush()
    },
    enqueueSlotUpdates(slots) {
      const items = Array.isArray(slots) ? slots : [slots]
      items.filter(Boolean).forEach((slot) => {
        const slotNumber = slotNumberOf(slot)
        if (!Number.isInteger(slotNumber) || slotNumber < 1) return
        if (pendingSnapshot) pendingSnapshotOverrides.set(slotNumber, slot)
        else pendingSlotUpdates.set(slotNumber, slot)
      })
      requestFlush()
    }
  }
}
