import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

import {
  isAdminCardPhysicallyRemoved,
  isAdminDoorPhysicallyOpen,
  planAdminEjectAll,
  validateAdminCardSlot
} from '../src/services/adminCardAction.js'

const actionSource = fs.readFileSync(new URL('../src/services/adminCardAction.js', import.meta.url), 'utf8')
const serviceSource = fs.readFileSync(new URL('../src/services/index.js', import.meta.url), 'utf8')

test('single-slot card validation remains strict only for the employee take-card flow', () => {
  assert.equal(validateAdminCardSlot({
    slotNumber: 2,
    status: 'OCCUPIED',
    cardNumber: 'SIM000000000002'
  }, 2).ok, true)
  assert.equal(validateAdminCardSlot({ slotNumber: 1, status: 'EMPTY' }, 1).code, 'NO_CARD_PRESENT')
  assert.equal(validateAdminCardSlot({
    slotNumber: 5,
    status: 'ILLEGAL_CARD',
    cardNumber: 'SIM000000000005'
  }, 5).ok, true)
  assert.equal(validateAdminCardSlot({
    slotNumber: 6,
    status: 'CHARGING_FAULT',
    cardNumber: 'SIM000000000006'
  }, 6).ok, true)
  assert.equal(validateAdminCardSlot({
    slotNumber: 7,
    status: 'COMMUNICATION_FAULT',
    cardNumber: 'SIM000000000007'
  }, 7).ok, true)
  assert.equal(validateAdminCardSlot({ slotNumber: 3, status: 'CHARGING' }, 3).code, 'CARD_IDENTITY_MISSING')
})

test('eject-all skips only explicit empty slots and opens every other available slot', () => {
  const plan = planAdminEjectAll([
    { slotNumber: 1, status: 'EMPTY', cardCode: 0 },
    { slotNumber: 2, status: 'OCCUPIED', cardNumber: 'CARD002' },
    { slotNumber: 3, status: 'CHARGING', cardNumber: 'CARD003' },
    { slotNumber: 4, status: 'FULL', cardNumber: 'CARD004' },
    { slotNumber: 5, status: 'ILLEGAL_CARD' },
    { slotNumber: 6, status: 'CHARGING_FAULT' },
    { slotNumber: 7, status: 'COMMUNICATION_FAULT', cardCode: -1 }
  ], 7)

  assert.deepEqual(plan.targets.map((item) => item.slotNumber), [2, 3, 4, 5, 6, 7])
  assert.equal(plan.emptyCount, 1)
  assert.deepEqual(plan.failures, [])

  const explicitEmptyPlan = planAdminEjectAll([
    { slotNumber: 1, status: 'EMPTY', cardCode: 0 },
    { slotNumber: 2, status: 'COMMUNICATION_FAULT', cardCode: -1 }
  ], 2)
  assert.deepEqual(explicitEmptyPlan.targets.map((item) => item.slotNumber), [2])
  assert.equal(explicitEmptyPlan.emptyCount, 1)
  assert.deepEqual(explicitEmptyPlan.failures, [])
})

test('admin card actions delegate to the Android serial capability without rebuilding raw frames', () => {
  assert.doesNotMatch(actionSource, /buildAdminOpenHex|buildAdminTakeHex|buildSlotQueryHex/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.openDoor', \{ slotNumber, administrator \}\)/)
  assert.match(serviceSource, /serial\.querySlot/)
})

test('administrator single-slot and batch actions query for an empty slot before declaring a card ejected', () => {
  assert.equal(isAdminDoorPhysicallyOpen({ slotNumber: 2, doorCode: 1 }, 2), true)
  assert.equal(isAdminDoorPhysicallyOpen({ slotNumber: 2, doorCode: 2 }, 2), false)
  assert.equal(isAdminDoorPhysicallyOpen({ slotNumber: 3, doorCode: 1 }, 2), false)

  assert.equal(isAdminCardPhysicallyRemoved({
    slotNumber: 2,
    status: 'EMPTY',
    cardNumber: ''
  }, 2), true)
  assert.equal(isAdminCardPhysicallyRemoved({
    slotNumber: 2,
    status: 'OCCUPIED',
    cardNumber: 'CARD002'
  }, 2), false)
  assert.equal(isAdminCardPhysicallyRemoved({
    slotNumber: 2,
    status: 'EMPTY',
    cardNumber: 'CARD002'
  }, 2), true)

  assert.match(serviceSource, /openDoorOnly: true/)
  assert.match(serviceSource, /operationType: 'ADMIN_TAKE_CARD'[\s\S]*?openDoorOnly: true/)
  assert.match(serviceSource, /const immediateAdminTake = parent\.openDoorOnly === true && parent\.singleOperation === true && operation\.source === 'LOCAL_ADMIN'/)
  assert.match(serviceSource, /\? readProjectedSlotSnapshot\(slotNumber\)/)
  assert.match(serviceSource, /const deferCommandPersistence = immediateAdminTake \|\| parent\.deferPersistence === true/)
  assert.match(serviceSource, /if \(deferCommandPersistence\) receivedRecord\.catch/)
  assert.match(serviceSource, /if \(immediateAdminTake\) logAdminTakeTiming\(slotNumber, startedAt, 'board_acked'\)/)
  assert.match(serviceSource, /Number\(currentSlot\?\.cardCode\) === 0/)
  assert.match(serviceSource, /const result = await sendDoorCommandAndWaitAck\(slotNumber, false\)/)
  assert.match(serviceSource, /statusReportPromise = reportDeviceStatusImmediately\('admin-eject-all'\)/)
  assert.match(serviceSource, /deferPersistence: true/)
  assert.match(serviceSource, /startRemoteEjectAllDoors/)
  assert.match(serviceSource, /commandAccepted: true/)
  assert.match(serviceSource, /doorOpenedCount/)
})

test('logout only schedules face synchronization after a completed enrollment', () => {
  assert.match(serviceSource, /const hasPendingFaceSync = Array\.isArray\(appState\.faceSyncPending\) && appState\.faceSyncPending\.length > 0/)
  assert.match(serviceSource, /if \(!hasPendingFaceSync\) return \{ \.\.\.result, faceSyncScheduled: false \}/)
})
