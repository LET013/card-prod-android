import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  CARD_EVENT_OUTBOX_TYPE,
  buildReturnCardTransition,
  buildTakeOpenHex,
  createTakeCardWorkflow
} from '../src/services/takeCardWorkflow.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function crc16Modbus(bytes) {
  let crc = 0xFFFF
  for (const value of bytes) {
    crc ^= value & 0xFF
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc & 1) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1)
    }
  }
  return crc & 0xFFFF
}

function buildOpenAckHex(address, result = 0x11) {
  const data = [0x5A, 0xA5, 0x5A, 0xA5, result]
  const length = 3 + data.length
  const bytes = [0xDD, 0xCC, length >> 8, length & 0xFF, 0xF0, address, 0x51, ...data]
  const crc = crc16Modbus(bytes)
  bytes.push(crc >> 8, crc & 0xFF)
  return bytes.map((value) => value.toString(16).padStart(2, '0')).join('').toUpperCase()
}

function createHarness({
  canDispatch = { allowed: true, simulator: true, requiresBoardAck: false },
  reportFailures = 0,
  statusReportFailures = 0,
  ackResult = 0x11,
  emitAck = true,
  emitPresented = true,
  selectedSlot = { slotNumber: 7, status: 'OCCUPIED', cardId: 'CARD001', fresh: true },
  election = { condition: 'OCCUPIED', conditionTip: '待机卡提示', candidateCount: 1, voltage: 4.1 },
  saveOperationOverride = null
} = {}) {
  const listeners = new Map()
  const operations = new Map()
  const outbox = new Map()
  const serialFrames = []
  const targetSlotQueries = []
  const reports = []
  const statusReports = []
  const operationStates = []
  let remainingReportFailures = reportFailures
  let remainingStatusReportFailures = statusReportFailures
  let currentTime = 1_750_000_000_000

  const emit = (eventName, event) => {
    ;(listeners.get(eventName) || []).slice().forEach((callback) => callback(event))
  }
  const subscribe = (eventName, callback) => {
    const callbacks = listeners.get(eventName) || []
    callbacks.push(callback)
    listeners.set(eventName, callbacks)
    return () => {
      listeners.set(eventName, (listeners.get(eventName) || []).filter((item) => item !== callback))
    }
  }
  const mergeOperation = (operation) => {
    const saved = { ...(operations.get(operation.operationId) || {}), ...operation }
    operations.set(operation.operationId, saved)
    if (operation.state) operationStates.push(operation.state)
    return saved
  }

  const workflow = createTakeCardWorkflow({
    async selectTakeCardSlot(options) {
      assert.equal(options.requireFresh, true)
      return {
        ok: true,
        slot: selectedSlot,
        election
      }
    },
    async saveOperation(operation) {
      if (typeof saveOperationOverride === 'function') return saveOperationOverride(operation, mergeOperation)
      return mergeOperation(operation)
    },
    async getOperation(operationId) { return operations.get(operationId) || null },
    async listRecoverableOperations() {
      return [...operations.values()].filter((operation) => !['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(operation.state))
    },
    async saveOutbox(event) {
      outbox.set(event.eventId, { ...(outbox.get(event.eventId) || {}), ...event, attemptCount: 0 })
      return outbox.get(event.eventId)
    },
    async getOutbox(eventId) { return outbox.get(eventId) || null },
    async listDueOutbox(eventType) {
      return [...outbox.values()].filter((event) => event.eventType === eventType && ['PENDING', 'FAILED'].includes(event.state))
    },
    async markOutboxSent(eventId) {
      const event = outbox.get(eventId)
      outbox.set(eventId, { ...event, state: 'SENT' })
      return outbox.get(eventId)
    },
    async markOutboxFailed(eventId, error) {
      const event = outbox.get(eventId)
      outbox.set(eventId, {
        ...event,
        state: 'FAILED',
        attemptCount: Number(event?.attemptCount || 0) + 1,
        lastError: error.message
      })
      return outbox.get(eventId)
    },
    async sendOpenDoor(request) {
      const slotNumber = Number(request?.slotNumber ?? request)
      serialFrames.push(buildTakeOpenHex(slotNumber))
      queueMicrotask(() => {
        if (emitPresented && ackResult === 0x11) {
          emit('cabinet.slotsSnapshot', {
            slots: [{ slotNumber: selectedSlot.slotNumber, status: selectedSlot.status, cardId: selectedSlot.cardId, doorCode: 1, fresh: true }]
          })
        }
        if (emitAck) {
          emit('serial.dataReceived', {
            type: 'serialRxRaw',
            hex: buildOpenAckHex(selectedSlot.slotNumber, ackResult)
          })
        }
      })
      if (request?.requiresBoardAck) {
        if (!emitAck) {
          const error = new Error('开门板级应答超时，未确认取卡完成')
          error.code = 'SERIAL_ACK_TIMEOUT'
          throw error
        }
        return {
          sent: true,
          boardAck: {
            accepted: ackResult === 0x11,
            address: slotNumber,
            functionCode: 0x51,
            resultCode: ackResult
          }
        }
      }
      return { sent: true, boardAck: null }
    },
    async queryTargetSlot(slotNumber) {
      targetSlotQueries.push(slotNumber)
      return { success: true, slotNumber, queued: true }
    },
    subscribe,
    async canDispatch() { return canDispatch },
    async reportCardEvent(cardEvent) {
      reports.push(cardEvent)
      if (remainingReportFailures > 0) {
        remainingReportFailures -= 1
        throw new Error('network unavailable')
      }
      return { sent: true, transport: 'HTTP' }
    },
    async reportStatusAfterTake(context) {
      statusReports.push({
        ...context,
        operationState: operations.get(context.operationId)?.state || ''
      })
      if (remainingStatusReportFailures > 0) {
        remainingStatusReportFailures -= 1
        throw new Error('status report unavailable')
      }
      return { sent: true, transport: 'MQTT' }
    },
    getSettings: () => ({ serialResponseTimeout: 500, serialPollInterval: 1000 }),
    now: () => currentTime++,
    createOperationId: ({ employeeId, timestamp }) => `take:${employeeId}:${timestamp}`
  })

  return { workflow, operations, outbox, serialFrames, targetSlotQueries, reports, statusReports, operationStates, emit }
}

const faceIdentity = {
  faceId: '101_0',
  employee: { employeeId: '101', employeeName: 'Employee 101' }
}

test('completes FACE take after board ACK and target slot presentation, then reports the card event', async () => {
  const harness = createHarness()
  const progress = []
  const result = await harness.workflow.take(faceIdentity, (event) => progress.push(event))

  assert.equal(result.completed, true)
  assert.equal(result.reportPending, false)
  assert.equal(result.slotNumber, 7)
  assert.equal(harness.serialFrames.length, 1)
  assert.equal(harness.serialFrames[0], buildTakeOpenHex(7))
  assert.deepEqual(harness.reports[0], {
    cardNo: 'CARD001',
    action: 'TAKE',
    employeeId: 101,
    timestamp: 1_750_000_000_001
  })
  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
  assert.equal([...harness.outbox.values()][0].state, 'SENT')
  assert.equal(harness.statusReports.length, 1)
  assert.equal(harness.statusReports[0].operationState, 'PHYSICAL_CONFIRMED')
  assert.equal(harness.statusReports[0].observedSlot.doorCode, 1)
  assert.deepEqual(harness.operationStates, [
    'RECEIVED',
    'SELECTING',
    'VALIDATED',
    'QUEUED',
    'SERIAL_SENT',
    'PHYSICAL_PENDING',
    'PHYSICAL_CONFIRMED',
    'REPORT_PENDING',
    'COMPLETED'
  ])
  assert.deepEqual(progress.map((event) => event.state), [
    'SELECTING',
    'VALIDATED',
    'QUEUED',
    'SERIAL_SENT',
    'PHYSICAL_PENDING',
    'CARD_PRESENTED_ANNOUNCEMENT',
    'CARD_PRESENTED',
    'COMPLETED'
  ])
  assert.equal(progress.find((event) => event.state === 'CARD_PRESENTED').slotNumber, 7)
})

test('keeps a physically completed take successful when its immediate status report is queued or fails', async () => {
  const harness = createHarness({ statusReportFailures: 1 })

  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(result.reportPending, false)
  assert.equal(result.statusReport.sent, false)
  assert.match(result.statusReport.error, /unavailable/)
  assert.equal(harness.reports.length, 1)
  assert.equal(harness.statusReports.length, 1)
  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
})

test('returns the FACE screen on board ACK and defers physical confirmation and reporting', async () => {
  const harness = createHarness({
    canDispatch: { allowed: true, simulator: false, requiresBoardAck: true },
    emitPresented: false
  })
  const progress = []

  const result = await harness.workflow.take(faceIdentity, (event) => progress.push(event))

  assert.equal(result.accepted, true)
  assert.equal(result.completed, false)
  assert.equal(result.boardAcknowledged, true)
  assert.equal(harness.operations.get(result.operationId).state, 'BOARD_ACKED')
  assert.equal(harness.reports.length, 0)
  assert.equal(progress.some((event) => event.state === 'CARD_PRESENTED' && event.physicalConfirmed === false), true)

  harness.emit('slot.status', {
    slotNumber: 7,
    status: 'OCCUPIED',
    cardId: 'CARD001',
    doorCode: 1
  })
  await new Promise((resolve) => setImmediate(resolve))
  await new Promise((resolve) => setImmediate(resolve))

  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
  assert.equal(harness.reports.length, 1)
})

test('dispatches a real FACE take without waiting for SQLite operation history before the board ACK', async () => {
  let releasePersistence
  const persistenceGate = new Promise((resolve) => { releasePersistence = resolve })
  const deferredStates = new Set(['RECEIVED', 'SELECTING', 'VALIDATED', 'QUEUED', 'SERIAL_SENT', 'PHYSICAL_PENDING', 'BOARD_ACKED'])
  const harness = createHarness({
    canDispatch: { allowed: true, simulator: false, requiresBoardAck: true },
    emitPresented: false,
    saveOperationOverride: async (operation, mergeOperation) => {
      if (deferredStates.has(operation.state)) await persistenceGate
      return mergeOperation(operation)
    }
  })

  const result = await Promise.race([
    harness.workflow.take(faceIdentity),
    new Promise((_, reject) => setTimeout(() => reject(new Error('FACE take waited for SQLite before board ACK')), 100))
  ])

  assert.equal(result.accepted, true)
  assert.equal(result.boardAcknowledged, true)
  assert.equal(harness.serialFrames.length, 1)
  releasePersistence()
  harness.emit('slot.status', {
    slotNumber: 7,
    status: 'OCCUPIED',
    cardId: 'CARD001',
    doorCode: 1
  })
  await new Promise((resolve) => setImmediate(resolve))
})

test('completes the Android serial simulator path from the target presentation event without a raw board ACK', async () => {
  const harness = createHarness({
    canDispatch: { allowed: true, simulator: true, requiresBoardAck: false },
    emitAck: false
  })
  const progress = []

  const result = await harness.workflow.take(faceIdentity, (event) => progress.push(event))

  assert.equal(result.completed, true)
  assert.equal(result.slotNumber, 7)
  assert.equal(harness.serialFrames.length, 1)
  assert.deepEqual(harness.operationStates, [
    'RECEIVED',
    'SELECTING',
    'VALIDATED',
    'QUEUED',
    'SERIAL_SENT',
    'PHYSICAL_PENDING',
    'PHYSICAL_CONFIRMED',
    'REPORT_PENDING',
    'COMPLETED'
  ])
  assert.deepEqual(progress.map((event) => event.state), [
    'SELECTING',
    'VALIDATED',
    'QUEUED',
    'SERIAL_SENT',
    'PHYSICAL_PENDING',
    'CARD_PRESENTED_ANNOUNCEMENT',
    'CARD_PRESENTED',
    'COMPLETED'
  ])
  assert.equal(progress.some((event) => event.state === 'BOARD_ACKED'), false)
})

test('uses the observed presented slot as employee-take success when a physical card opens before any board ACK arrives', async () => {
  const harness = createHarness({ emitAck: false })

  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(result.slotNumber, 7)
  assert.equal(harness.operationStates.includes('BOARD_ACKED'), false)
})

test('does not poll the native slot snapshot after Android pushes the simulator state event', async () => {
  const harness = createHarness({
    canDispatch: { allowed: true, simulator: true, requiresBoardAck: false },
    emitAck: false
  })

  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(result.slotNumber, 7)
  assert.equal(harness.serialFrames.length, 1)
})

test('reports fifteen zeroes when a serial-confirmed card has no readable card number', async () => {
  const harness = createHarness({
    selectedSlot: {
      slotNumber: 7,
      status: 'ILLEGAL_CARD',
      workCode: 1,
      cardCode: 2,
      cardId: '',
      fresh: true
    },
    election: { condition: 'OCCUPIED', conditionTip: '', candidateCount: 1, voltage: 4.1 }
  })

  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(result.cardNo, '000000000000000')
  assert.equal(harness.reports[0].cardNo, '000000000000000')
  assert.equal(harness.operations.get(result.operationId).cardNo, '000000000000000')
})

test('queries the target slot immediately and completes once its door opens', async () => {
  const harness = createHarness({ emitPresented: false })
  const progress = []
  const takePromise = harness.workflow.take(faceIdentity, (event) => progress.push(event))

  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(harness.targetSlotQueries, [7])
  harness.emit('slot.status', {
    slotNumber: 7,
    status: 'OCCUPIED',
    cardId: 'CARD001',
    doorCode: 1
  })
  await new Promise((resolve) => setImmediate(resolve))

  const presented = progress.find((event) => event.state === 'CARD_PRESENTED')
  assert.equal(presented.slotNumber, 7)
  assert.match(presented.message, /已打开，请取走工卡/)
  const result = await takePromise
  assert.equal(result.completed, true)
  assert.equal(harness.operationStates.includes('PHYSICAL_CONFIRMED'), true)
  assert.equal(harness.reports.length, 1)
})

test('blocks the Release path before serial send when outgoing topology is unconfirmed', async () => {
  const harness = createHarness({
    canDispatch: {
      allowed: false,
      code: 'SERIAL_TOPOLOGY_UNCONFIRMED',
      message: 'topology unconfirmed'
    }
  })

  await assert.rejects(
    harness.workflow.take(faceIdentity),
    (error) => error.code === 'SERIAL_TOPOLOGY_UNCONFIRMED'
  )
  assert.equal(harness.serialFrames.length, 0)
  assert.equal([...harness.operations.values()][0].state, 'FAILED')
})

test('reports the automatically elected physical card without requiring an employee card number', async () => {
  const conditionTip = '工卡尚未充满，续航可能不足；请留意使用时长，并在使用后尽快归还充电。'
  const harness = createHarness({
    selectedSlot: { slotNumber: 9, status: 'CHARGING', cardId: 'CARD009', fresh: true },
    election: { condition: 'CHARGING', conditionTip, candidateCount: 3, voltage: 4.05 }
  })
  const progress = []

  const result = await harness.workflow.take(faceIdentity, (event) => progress.push(event))

  assert.equal(result.slotNumber, 9)
  assert.equal(result.cardNo, 'CARD009')
  assert.equal(result.cardCondition, 'CHARGING')
  assert.equal(harness.reports[0].cardNo, 'CARD009')
  assert.equal(Object.hasOwn(harness.operations.get(result.operationId), 'employeeCardNo'), false)
  assert.equal(result.conditionTip, conditionTip)
  assert.equal(progress[0].state, 'SELECTING')
})

test('continues the physical take flow for card-present fault and illegal display states', async () => {
  for (const status of ['ILLEGAL_CARD', 'CHARGING_FAULT', 'COMMUNICATION_FAULT']) {
    const harness = createHarness({
      selectedSlot: { slotNumber: 9, status, cardId: `CARD-${status}`, fresh: true },
      election: { condition: 'OCCUPIED', conditionTip: '状态仅作显示', candidateCount: 1, voltage: 4.0 }
    })

    const result = await harness.workflow.take(faceIdentity)

    assert.equal(result.completed, true)
    assert.equal(result.cardNo, `CARD-${status}`)
    assert.equal(harness.serialFrames.length, 1)
    assert.equal(harness.reports[0].cardNo, `CARD-${status}`)
  }
})

test('keeps a physically completed take in outbox and completes it after retry succeeds', async () => {
  const harness = createHarness({ reportFailures: 1 })
  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(result.reportPending, true)
  assert.equal(harness.operations.get(result.operationId).state, 'REPORT_PENDING')
  const pending = [...harness.outbox.values()][0]
  assert.equal(pending.eventType, CARD_EVENT_OUTBOX_TYPE)
  assert.equal(pending.state, 'FAILED')

  const flushed = await harness.workflow.flushPendingReports()
  assert.deepEqual(flushed, { total: 1, sent: 1, failed: 0 })
  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
  assert.equal(harness.outbox.get(pending.eventId).state, 'SENT')
})

test('does not treat an unrelated persisted non-terminal take as an unreturned-card blocker', async () => {
  const harness = createHarness()
  harness.operations.set('take-existing', {
    operationId: 'take-existing',
    operationType: 'TAKE_CARD',
    employeeId: 101,
    cardNo: 'CARD001',
    state: 'PHYSICAL_PENDING'
  })

  const result = await harness.workflow.take(faceIdentity)

  assert.equal(result.completed, true)
  assert.equal(harness.serialFrames.length, 1)
  assert.equal(harness.statusReports.length, 1)
})

test('integrates V4.2 cardEvent with correlated MQTT ACK and documented HTTP fallback', async () => {
  const source = await readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8')

  assert.match(source, /'statusReportResp',[\s\S]*'cardEventResp'/)
  assert.match(source, /sendMqttAndWaitForResponse\('cardEvent', cardEvent, 'cardEventResp', msgId\)/)
  assert.match(source, /assertBackendSuccess\(result\?\.response\?\.data, 'cardEvent', \{ requireCode: true \}\)/)
  assert.match(source, /httpPost\('\/api\/v1\/card\/event', cardEvent\)/)
  assert.doesNotMatch(source, /MQTT_CARD_EVENT_ACK_NOT_IMPLEMENTED/)
})

test('rebuilds a durable cardEvent after restart without resending the serial command', async () => {
  const harness = createHarness()
  harness.operations.set('take-restart', {
    operationId: 'take-restart',
    operationType: 'TAKE_CARD',
    employeeId: 101,
    employeeName: 'Employee 101',
    faceId: '101_0',
    cardNo: 'CARD001',
    authType: 'FACE',
    slotNumber: 7,
    state: 'PHYSICAL_CONFIRMED',
    createdAt: 1_750_000_000_000,
    updatedAt: 1_750_000_000_123
  })

  const flushed = await harness.workflow.flushPendingReports()

  assert.deepEqual(flushed, { total: 1, sent: 1, failed: 0 })
  assert.equal(harness.serialFrames.length, 0)
  assert.equal(harness.reports.length, 1)
  assert.equal(harness.reports[0].timestamp, 1_750_000_000_123)
  assert.equal(harness.operations.get('take-restart').state, 'COMPLETED')
  assert.equal(harness.outbox.get('card-event:take-restart').state, 'SENT')
})

test('converts a queued legacy cardEvent to the documented raw before sending', async () => {
  const harness = createHarness()
  harness.outbox.set('legacy-card-event', {
    eventId: 'legacy-card-event',
    eventType: CARD_EVENT_OUTBOX_TYPE,
    operationId: 'legacy-take',
    payload: {
      cmd: 'cardEvent',
      data: {
        cardNo: 'CARD001',
        eventType: 'TAKE',
        slotId: 7,
        authType: 'FACE',
        employeeId: 101,
        timestamp: 1_750_000_000_456
      }
    },
    state: 'PENDING'
  })

  const flushed = await harness.workflow.flushPendingReports()

  assert.deepEqual(flushed, { total: 1, sent: 1, failed: 0 })
  assert.deepEqual(harness.reports[0], {
    cardNo: 'CARD001',
    action: 'TAKE',
    employeeId: 101,
    timestamp: 1_750_000_000_456
  })
  assert.deepEqual(harness.outbox.get('legacy-card-event').payload.data, harness.reports[0])
})

test('completes a recovered operation from an already acknowledged outbox without duplicate reporting', async () => {
  const harness = createHarness()
  const cardEvent = {
    cardNo: 'CARD001',
    eventType: 'TAKE',
    slotId: 7,
    authType: 'FACE',
    employeeId: 101,
    timestamp: 1_750_000_000_456
  }
  harness.operations.set('take-acked', {
    operationId: 'take-acked',
    operationType: 'TAKE_CARD',
    employeeId: 101,
    employeeName: 'Employee 101',
    faceId: '101_0',
    cardNo: 'CARD001',
    authType: 'FACE',
    slotNumber: 7,
    state: 'REPORT_PENDING',
    cardEvent,
    outboxEventId: 'card-event:take-acked',
    createdAt: 1_750_000_000_000,
    updatedAt: 1_750_000_000_456
  })
  harness.outbox.set('card-event:take-acked', {
    eventId: 'card-event:take-acked',
    eventType: CARD_EVENT_OUTBOX_TYPE,
    operationId: 'take-acked',
    payload: { cmd: 'cardEvent', data: cardEvent },
    state: 'SENT'
  })

  const flushed = await harness.workflow.flushPendingReports()

  assert.deepEqual(flushed, { total: 0, sent: 0, failed: 0 })
  assert.equal(harness.serialFrames.length, 0)
  assert.equal(harness.reports.length, 0)
  assert.equal(harness.operations.get('take-acked').state, 'COMPLETED')
})

test('does not infer physical completion or resend serial for a pre-confirmation restart state', async () => {
  const harness = createHarness()
  harness.operations.set('take-physical-pending', {
    operationId: 'take-physical-pending',
    operationType: 'TAKE_CARD',
    employeeId: 101,
    faceId: '101_0',
    cardNo: 'CARD001',
    slotNumber: 7,
    state: 'PHYSICAL_PENDING',
    createdAt: 1_750_000_000_000,
    updatedAt: 1_750_000_000_789
  })

  const recovered = await harness.workflow.recoverPendingReports()

  assert.deepEqual(recovered, { total: 0, repaired: 0, completed: 0, failed: 0 })
  assert.equal(harness.serialFrames.length, 0)
  assert.equal(harness.reports.length, 0)
  assert.equal(harness.outbox.size, 0)
  assert.equal(harness.operations.get('take-physical-pending').state, 'PHYSICAL_PENDING')
})

test('reports target-slot failure after a rejected board response without emitting physical success', async () => {
  const harness = createHarness({
    ackResult: 0x12,
    canDispatch: { allowed: true, requiresBoardAck: true }
  })
  const progress = []

  await assert.rejects(
    harness.workflow.take(faceIdentity, (event) => progress.push(event)),
    (error) => error.code === 'SERIAL_COMMAND_REJECTED'
  )

  assert.equal(harness.serialFrames.length, 1)
  assert.deepEqual(progress.map((event) => event.state), [
    'SELECTING',
    'VALIDATED',
    'QUEUED',
    'SERIAL_SENT',
    'PHYSICAL_PENDING',
    'FAILED'
  ])
  assert.equal(progress.at(-1).slotNumber, 7)
  assert.equal(progress.some((event) => event.state === 'PHYSICAL_CONFIRMED'), false)
  assert.equal(harness.statusReports.length, 0)
})

test('recognizes only an established empty-to-present slot transition as a return', async () => {
  assert.equal(buildReturnCardTransition(null, {
    slotNumber: 7, status: 'OCCUPIED', cardId: 'CARD001'
  }), null)
  assert.deepEqual(buildReturnCardTransition(
    { slotNumber: 7, status: 'EMPTY', cardId: '' },
    { slotNumber: 7, status: 'OCCUPIED', cardId: 'CARD001' }
  ), {
    slotNumber: 7,
    cardNo: 'CARD001',
    previousStatus: 'EMPTY',
    currentStatus: 'OCCUPIED'
  })
})

test('persists and reports a physical return without inventing employee identity', async () => {
  const harness = createHarness()
  const baseline = await harness.workflow.observeReturn(null, {
    slotNumber: 7, status: 'EMPTY', cardId: '', fresh: true
  })
  const result = await harness.workflow.observeReturn(
    { slotNumber: 7, status: 'EMPTY', cardId: '', fresh: true },
    { slotNumber: 7, status: 'OCCUPIED', cardId: 'CARD001', fresh: true }
  )

  assert.deepEqual(baseline, { observed: false, reason: 'BASELINE_ESTABLISHED' })
  assert.equal(result.observed, true)
  assert.equal(result.completed, true)
  assert.equal(result.reportPending, false)
  assert.equal(harness.serialFrames.length, 0)
  assert.deepEqual(harness.reports[0], {
    cardNo: 'CARD001',
    action: 'RETURN',
    timestamp: 1_750_000_000_000
  })
  assert.equal(Object.hasOwn(harness.reports[0], 'employeeId'), false)
  assert.equal(harness.operations.get(result.operationId).operationType, 'RETURN_CARD')
  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
})

test('keeps a confirmed return in outbox until backend acknowledgement', async () => {
  const harness = createHarness({ reportFailures: 1 })
  await harness.workflow.observeReturn(null, {
    slotNumber: 7, status: 'EMPTY', cardId: '', fresh: true
  })
  const result = await harness.workflow.observeReturn(
    { slotNumber: 7, status: 'EMPTY', cardId: '', fresh: true },
    { slotNumber: 7, status: 'CHARGING', cardId: 'CARD001', fresh: true }
  )

  assert.equal(result.reportPending, true)
  assert.equal(harness.outbox.get(`card-event:${result.operationId}`).state, 'FAILED')
  const flushed = await harness.workflow.flushPendingReports()
  assert.deepEqual(flushed, { total: 1, sent: 1, failed: 0 })
  assert.equal(harness.operations.get(result.operationId).state, 'COMPLETED')
})

test('keeps raw return observation ahead of coalesced slot projection updates', async () => {
  const source = await readFile(path.join(projectRoot, 'src/main.js'), 'utf8')
  assert.match(source, /const observeSlotBusinessChange[\s\S]*observeReturnCard/)
  assert.match(source, /cabinet\.slotsSnapshot[\s\S]*observeSlotBusinessChange[\s\S]*enqueueSnapshot/)
  assert.match(source, /slot\.status[\s\S]*observeSlotBusinessChange[\s\S]*enqueueSlotUpdates/)
})

test('keeps face success distinct when no card slot is available', async () => {
  const [serviceSource, workflowSource, homeSource, modalSource] = await Promise.all([
    readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8'),
    readFile(path.join(projectRoot, 'src/services/takeCardWorkflow.js'), 'utf8'),
    readFile(path.join(projectRoot, 'src/pages/index/index.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/components/RecognitionModal.vue'), 'utf8')
  ])

  assert.match(serviceSource, /selectTakeCardCandidate\(\{ slots, maxAgeMs \}\)/)
  assert.match(workflowSource, /selectTakeCardSlot\(\{ requireFresh: true \}\)/)
  assert.doesNotMatch(serviceSource, /resolveSimulatorCardSlot|findSlotByCardNo|employeeCardNo/)
  assert.doesNotMatch(serviceSource, /请安装关闭串口模拟器的实机包/)
  assert.match(homeSource, /NO_CARD_ERROR_CODES\.has\(error\?\.code\)[\s\S]*TAKE_CARD_RESULT\.NO_CARD/)
  assert.match(homeSource, /recognition\.visible = false[\s\S]*services\.takeCard\(result, applyTakeProgress\)/)
  assert.match(modalSource, /人脸已通过，取卡未完成/)
})

test('disabled employees are rejected before the take-card workflow can send a serial command', async () => {
  const serviceSource = await readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8')
  const takeStart = serviceSource.indexOf('async function takeCard(identity, progressCallback)')
  const takeEnd = serviceSource.indexOf('async function observeReturnCard', takeStart)
  const takeSource = serviceSource.slice(takeStart, takeEnd)
  const disabledCheck = takeSource.indexOf('if (!employee.enabled)')
  const workflowStart = takeSource.indexOf('getTakeCardWorkflow().take')

  assert.ok(disabledCheck >= 0)
  assert.ok(workflowStart > disabledCheck)
  assert.match(takeSource, /EMPLOYEE_DISABLED/)
  assert.match(takeSource, /员工已停用，不能取卡/)
})

test('keeps raw Face ID out of Android user-facing recognition success text', async () => {
  const source = await readFile(path.join(
    projectRoot,
    '../app/src/main/java/com/xingyao/card/face/FaceEnrollmentController.java'
  ), 'utf8')

  assert.match(source, /setTextSafe\(tvStatus, "识别完成"\)/)
  assert.doesNotMatch(source, /Toast\.makeText\(activity, "人脸识别成功", Toast\.LENGTH_SHORT\)/)
  assert.doesNotMatch(source, /setTextSafe\(tvStatus, "识别成功: " \+ matchedFaceID/)
  assert.doesNotMatch(source, /Toast\.makeText\(activity, "识别: " \+ matchedFaceID/)
  assert.match(source, /resultCallback\.onFaceVerified\(faceId, Math\.max\(score, liveness\)\)/)
})
