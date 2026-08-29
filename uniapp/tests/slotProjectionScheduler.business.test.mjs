import assert from 'node:assert/strict'
import test from 'node:test'
import { createSlotProjectionScheduler } from '../src/services/slotProjectionScheduler.js'

function createHarness() {
  const scheduled = []
  const snapshots = []
  const updates = []
  const scheduler = createSlotProjectionScheduler({
    applySnapshot: (slots) => snapshots.push(slots),
    applySlotUpdates: (slots) => updates.push(slots),
    schedule: (callback) => scheduled.push(callback)
  })
  return {
    scheduler,
    snapshots,
    updates,
    flush() {
      assert.equal(scheduled.length, 1)
      scheduled.shift()()
    }
  }
}

test('slot projection scheduler merges rapid updates for the same slot into one frame', () => {
  const harness = createHarness()
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 3, status: 'CHARGING' })
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 3, status: 'FULL' })
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 4, status: 'EMPTY' })

  harness.flush()

  assert.deepEqual(harness.updates, [[
    { slotNumber: 3, status: 'FULL' },
    { slotNumber: 4, status: 'EMPTY' }
  ]])
  assert.deepEqual(harness.snapshots, [])
})

test('a status received after a snapshot overrides that slot before the frame renders', () => {
  const harness = createHarness()
  harness.scheduler.enqueueSnapshot([
    { slotNumber: 1, status: 'EMPTY' },
    { slotNumber: 2, status: 'EMPTY' }
  ])
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 2, status: 'OCCUPIED', cardNo: 'C2' })

  harness.flush()

  assert.deepEqual(harness.snapshots, [[
    { slotNumber: 1, status: 'EMPTY' },
    { slotNumber: 2, status: 'OCCUPIED', cardNo: 'C2' }
  ]])
  assert.deepEqual(harness.updates, [])
})

test('a newer snapshot replaces older queued incremental states', () => {
  const harness = createHarness()
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 1, status: 'CHARGING' })
  harness.scheduler.enqueueSnapshot([{ slotNumber: 1, status: 'FULL' }])

  harness.flush()

  assert.deepEqual(harness.snapshots, [[{ slotNumber: 1, status: 'FULL' }]])
  assert.deepEqual(harness.updates, [])
})

test('a later slot event is retained when a partial snapshot does not include that slot', () => {
  const harness = createHarness()
  harness.scheduler.enqueueSnapshot([{ slotNumber: 1, status: 'EMPTY' }])
  harness.scheduler.enqueueSlotUpdates({ slotNumber: 2, status: 'OCCUPIED', cardNo: 'C2' })

  harness.flush()

  assert.deepEqual(harness.snapshots, [[
    { slotNumber: 1, status: 'EMPTY' },
    { slotNumber: 2, status: 'OCCUPIED', cardNo: 'C2' }
  ]])
})

test('keeps one latest update per slot for every supported cabinet capacity', () => {
  for (const totalSlots of [50, 60, 100, 120]) {
    const harness = createHarness()
    const initial = Array.from({ length: totalSlots }, (_, index) => ({
      slotNumber: index + 1,
      status: 'CHARGING'
    }))
    const latest = initial.map((slot) => ({ ...slot, status: 'FULL' }))

    harness.scheduler.enqueueSlotUpdates(initial)
    harness.scheduler.enqueueSlotUpdates(latest)
    harness.flush()

    assert.equal(harness.updates.length, 1)
    assert.equal(harness.updates[0].length, totalSlots)
    assert.equal(harness.updates[0][0].status, 'FULL')
    assert.equal(harness.updates[0][totalSlots - 1].slotNumber, totalSlots)
  }
})
