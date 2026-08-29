import assert from 'node:assert/strict'
import test from 'node:test'

import { createDoorOperationScheduler } from '../src/services/doorOperationScheduler.js'

function createHarness () {
  const listeners = []
  const sent = []
  const scheduler = createDoorOperationScheduler({
    sendOpenDoor: async (slotNumber, administrator) => {
      sent.push({ slotNumber, administrator })
      return { success: true, queued: true }
    },
    subscribe: (_eventName, callback) => {
      listeners.push(callback)
      return () => {
        const index = listeners.indexOf(callback)
        if (index >= 0) listeners.splice(index, 1)
      }
    },
    matchTransmit: (event, request) => event?.type === 'serialTx' && Number(event.slotNumber) === request.slotNumber,
    matchAck: (event, request) => {
      if (event?.type !== 'serialAck' || Number(event.slotNumber) !== request.slotNumber) return null
      return { accepted: event.accepted === true }
    }
  })
  const emit = (event) => listeners.slice().forEach((callback) => callback(event))
  return { scheduler, sent, emit }
}

test('serializes different slots and starts ACK timing only after actual serial TX', async () => {
  const harness = createHarness()
  const first = harness.scheduler.dispatch({ slotNumber: 3, ackTimeoutMs: 30, txTimeoutMs: 50 })
  const second = harness.scheduler.dispatch({ slotNumber: 4, ackTimeoutMs: 30, txTimeoutMs: 50 })

  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(harness.sent, [{ slotNumber: 3, administrator: false }])
  await new Promise((resolve) => setTimeout(resolve, 35))
  assert.equal(harness.sent.length, 1)

  harness.emit({ type: 'serialTx', slotNumber: 3 })
  harness.emit({ type: 'serialAck', slotNumber: 3, accepted: true })
  await first
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(harness.sent, [
    { slotNumber: 3, administrator: false },
    { slotNumber: 4, administrator: false }
  ])
  harness.emit({ type: 'serialTx', slotNumber: 4 })
  harness.emit({ type: 'serialAck', slotNumber: 4, accepted: true })
  await second
})

test('rejects another operation for the same slot instead of writing a duplicate command', async () => {
  const harness = createHarness()
  const first = harness.scheduler.dispatch({ slotNumber: 8, ackTimeoutMs: 50, txTimeoutMs: 50 })
  await assert.rejects(
    harness.scheduler.dispatch({ slotNumber: 8, ackTimeoutMs: 50, txTimeoutMs: 50 }),
    (error) => error.code === 'SLOT_OPERATION_IN_PROGRESS'
  )
  harness.emit({ type: 'serialTx', slotNumber: 8 })
  harness.emit({ type: 'serialAck', slotNumber: 8, accepted: true })
  await first
  assert.equal(harness.sent.length, 1)
})

test('reports board rejection after the matching TX event', async () => {
  const harness = createHarness()
  const pending = harness.scheduler.dispatch({ slotNumber: 6, ackTimeoutMs: 50, txTimeoutMs: 50 })
  await new Promise((resolve) => setImmediate(resolve))
  harness.emit({ type: 'serialTx', slotNumber: 6 })
  harness.emit({ type: 'serialAck', slotNumber: 6, accepted: false })
  await assert.rejects(pending, (error) => error.code === 'SERIAL_COMMAND_REJECTED')
})
