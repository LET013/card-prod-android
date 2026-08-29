import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeSlotsProjection, reconcileSlotsProjection, summarizeSlotStatuses } from '../src/state/slotProjection.js'

test('keeps one latest record per configured slot and fills missing slots', () => {
  const slots = normalizeSlotsProjection([
    { slotNumber: 1, status: 'EMPTY', updatedAt: 100 },
    { slotNumber: 1, status: 'OCCUPIED', updatedAt: 200 },
    { slotNumber: 3, status: 'CHARGING', updatedAt: 150 },
    { slotNumber: 6, status: 'EMPTY', updatedAt: 300 }
  ], 5)

  assert.equal(slots.length, 5)
  assert.deepEqual(slots.map((slot) => slot.slotNumber), [1, 2, 3, 4, 5])
  assert.equal(slots[0].status, 'OCCUPIED')
  assert.equal(slots[1].status, 'LOADING')
  assert.equal(slots[1].displayNumber, '02')
})

test('drops malformed records and uses the later duplicate when timestamps are equal', () => {
  const slots = normalizeSlotsProjection([
    { slotNumber: 2, status: 'EMPTY' },
    { slotId: 2, status: 'OCCUPIED' },
    { slotNumber: 0, status: 'EMPTY' },
    { slotNumber: 'invalid', status: 'EMPTY' }
  ])

  assert.equal(slots.length, 1)
  assert.equal(slots[0].slotNumber, 2)
  assert.equal(slots[0].status, 'OCCUPIED')
  assert.equal(slots[0].id, 'slot-2')
})

test('keeps the screenshot case at ten columns despite 67 duplicate cached rows', () => {
  const configuredSlots = Array.from({ length: 100 }, (_, index) => ({
    slotNumber: index + 1,
    status: 'EMPTY',
    fresh: false,
    updatedAt: 1000 + index
  }))
  const duplicateRows = Array.from({ length: 67 }, (_, index) => ({
    slotNumber: index + 1,
    status: 'OCCUPIED',
    fresh: false,
    updatedAt: 2000 + index
  }))

  const slots = normalizeSlotsProjection([...configuredSlots, ...duplicateRows], 100)

  assert.equal(slots.length, 100)
  assert.equal(new Set(slots.map((slot) => slot.slotNumber)).size, 100)
  assert.equal(Math.ceil(slots.length / 10), 10)
})

test('summarizes visible statuses in one pass and excludes loading placeholders', () => {
  const counts = summarizeSlotStatuses([
    { status: 'EMPTY' },
    { status: 'EMPTY' },
    { status: 'FULL' },
    { status: 'LOADING' },
    { status: '' }
  ])

  assert.equal(counts.EMPTY, 2)
  assert.equal(counts.FULL, 1)
  assert.equal(counts.LOADING, undefined)
})

test('builds one stable projection for each supported cabinet capacity', () => {
  for (const totalSlots of [50, 60, 100, 120]) {
    const slots = normalizeSlotsProjection([
      { slotNumber: 1, status: 'EMPTY', updatedAt: 1 },
      { slotNumber: totalSlots, status: 'FULL', updatedAt: 2 }
    ], totalSlots)

    assert.equal(slots.length, totalSlots)
    assert.equal(new Set(slots.map((slot) => slot.slotNumber)).size, totalSlots)
    assert.equal(slots[0].status, 'EMPTY')
    assert.equal(slots[totalSlots - 1].status, 'FULL')
  }
})

test('reconciles a full snapshot without replacing stable slot objects', () => {
  const current = [
    { slotNumber: 1, status: 'EMPTY', displayNumber: '01', staleField: 'remove-me' },
    { slotNumber: 2, status: 'FULL', displayNumber: '02' }
  ]
  const firstSlot = current[0]
  const result = reconcileSlotsProjection(current, [
    { slotNumber: 1, status: 'FULL', displayNumber: '01' },
    { slotNumber: 2, status: 'FULL', displayNumber: '02' }
  ], 2, { fresh: true })

  assert.equal(result.topologyChanged, false)
  assert.equal(result.slots, current)
  assert.equal(result.slots[0], firstSlot)
  assert.equal(result.slots[0].status, 'FULL')
  assert.equal(result.slots[0].fresh, true)
  assert.equal('staleField' in result.slots[0], false)
})
