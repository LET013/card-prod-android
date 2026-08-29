import assert from 'node:assert/strict'
import test from 'node:test'

import {
  selectTakeCardCandidate,
  TAKE_CARD_CONDITION,
  takeCardConditionOf,
  takeCardConditionTip
} from '../src/services/takeCardElection.js'

test('places fault and unrecognized states behind charged cards while retaining them as candidates', () => {
  const now = 100000
  const result = selectTakeCardCandidate({
    now,
    slots: [
      { slotNumber: 6, status: 'CHARGING', workCode: 2, cardCode: 1, batteryPercent: 80, updatedAt: now },
      { slotNumber: 2, status: 'ILLEGAL_CARD', cardCode: 2, updatedAt: now },
      { slotNumber: 4, status: 'UNRECOGNIZED_STATE', cardCode: 1, updatedAt: now }
    ]
  })

  assert.equal(result.ok, true)
  assert.equal(result.slot.slotNumber, 6)
  assert.equal(result.condition, TAKE_CARD_CONDITION.CHARGING)
  assert.equal(result.batteryPercent, 80)
})

test('orders full, ready, charging, then fault before comparing charge details', () => {
  const now = 100000
  const slots = [
    { slotNumber: 1, status: 'FULL', workCode: 3, cardCode: 1, batteryPercent: 1, voltage: 3.5, updatedAt: now },
    { slotNumber: 2, status: 'OCCUPIED', workCode: 1, cardCode: 1, batteryPercent: 100, voltage: 4.3, updatedAt: now },
    { slotNumber: 3, status: 'CHARGING', workCode: 2, cardCode: 1, batteryPercent: 100, voltage: 4.3, updatedAt: now },
    { slotNumber: 4, status: 'ILLEGAL_CARD', cardCode: 2, batteryPercent: 100, voltage: 4.3, updatedAt: now }
  ]

  assert.equal(selectTakeCardCandidate({ now, slots }).slot.slotNumber, 1)
  assert.equal(selectTakeCardCandidate({ now, slots: slots.slice(1) }).slot.slotNumber, 2)
  assert.equal(selectTakeCardCandidate({ now, slots: slots.slice(2) }).slot.slotNumber, 3)
  assert.equal(selectTakeCardCandidate({ now, slots: slots.slice(3) }).slot.slotNumber, 4)
})

test('uses higher protocol voltage inside the same condition and slot number as stable fallback', () => {
  const now = 100000
  const result = selectTakeCardCandidate({
    now,
    slots: [
      { slotNumber: 8, status: 'OCCUPIED', workCode: 1, cardNo: 'CARD008', voltage: 4.15, updatedAt: now },
      { slotNumber: 6, status: 'OCCUPIED', workCode: 1, cardNo: 'CARD006', voltage: 4.2, updatedAt: now },
      { slotNumber: 2, status: 'OCCUPIED', workCode: 1, cardNo: 'CARD002', voltage: 4.2, updatedAt: now }
    ]
  })

  assert.equal(result.slot.slotNumber, 2)
  assert.equal(result.voltage, 4.2)
})

test('passes through an explicit battery percentage without estimating it from voltage', () => {
  const now = 100000
  const result = selectTakeCardCandidate({
    now,
    slots: [{
      slotNumber: 9,
      status: 'CHARGING',
      cardNo: 'CARD009',
      voltage: 4.05,
      batteryPercent: 28,
      updatedAt: now
    }]
  })

  assert.equal(result.batteryPercent, 28)
})

test('requires card-in-place evidence and a current snapshot', () => {
  assert.deepEqual(selectTakeCardCandidate({
    now: 100000,
    slots: [
      { slotNumber: 1, status: 'EMPTY', cardCode: 0, updatedAt: 100000 },
      { slotNumber: 2, status: 'UNKNOWN', updatedAt: 100000 }
    ]
  }), { ok: false, reason: 'NO_TAKEABLE_CARD' })
  assert.deepEqual(selectTakeCardCandidate({
    now: 100000,
    maxAgeMs: 15000,
    slots: [{ slotNumber: 3, status: 'CHARGING', workCode: 2, cardCode: 1, updatedAt: 84999 }]
  }), { ok: false, reason: 'SLOT_SNAPSHOT_STALE' })
})

test('keeps a physical fault state in the occupied priority group', () => {
  const result = selectTakeCardCandidate({
    now: 100000,
    slots: [{ slotNumber: 1, status: 'COMMUNICATION_FAULT', cardCode: 1, updatedAt: 100000 }]
  })

  assert.equal(result.ok, true)
  assert.equal(result.condition, TAKE_CARD_CONDITION.OCCUPIED)
})

test('uses user-facing condition wording backed by the protocol states', () => {
  assert.equal(takeCardConditionOf({ status: 'FULL' }), TAKE_CARD_CONDITION.FULL)
  assert.equal(takeCardConditionOf({ status: 'OCCUPIED' }), TAKE_CARD_CONDITION.OCCUPIED)
  assert.equal(takeCardConditionOf({ status: 'CHARGING' }), TAKE_CARD_CONDITION.CHARGING)
  assert.match(takeCardConditionTip(TAKE_CARD_CONDITION.FULL), /已充满/)
  assert.match(takeCardConditionTip(TAKE_CARD_CONDITION.OCCUPIED), /已就绪.*正常取用/)
  assert.doesNotMatch(takeCardConditionTip(TAKE_CARD_CONDITION.OCCUPIED), /电量.*无法确认/)
  assert.match(takeCardConditionTip(TAKE_CARD_CONDITION.CHARGING), /尚未充满.*续航可能不足/)
})
