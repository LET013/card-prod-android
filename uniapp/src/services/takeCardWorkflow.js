const OPEN_DOOR_FUNCTION = 0x51
const TAKE_OPEN_FLAG = 0x01
const MASTER_ADDRESS = 0xF0
const COMMAND_PREFIX = [0x5A, 0xA5, 0x5A, 0xA5]
const RESULT_OK = 0x11
const RESULT_FAILED = 0x12
const UNKNOWN_CARD_NO = '0'.repeat(15)
// 还卡事件必须识别为空槽到有卡的状态转换；它不参与取卡候选限制。
const CARD_PRESENT_STATES = new Set([
  'OCCUPIED',
  'CHARGING',
  'FULL',
  'ILLEGAL_CARD',
  'CHARGING_FAULT',
  'COMMUNICATION_FAULT'
])
const EMPTY_CARD_STATES = new Set(['EMPTY'])

export const CARD_EVENT_OUTBOX_TYPE = 'CARD_EVENT'

function createWorkflowError(code, message, data = null) {
  const error = new Error(message)
  error.code = code
  error.data = data
  return error
}

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

function buildFrame(address, functionCode, data) {
  const length = 3 + data.length
  const bytes = [
    0xDD,
    0xCC,
    length >> 8,
    length & 0xFF,
    MASTER_ADDRESS,
    address,
    functionCode,
    ...data
  ]
  const crc = crc16Modbus(bytes)
  bytes.push(crc >> 8, crc & 0xFF)
  return bytes.map((value) => value.toString(16).padStart(2, '0')).join('').toUpperCase()
}

export function buildTakeOpenHex(address) {
  const normalizedAddress = Number(address)
  if (!Number.isInteger(normalizedAddress) || normalizedAddress < 1 || normalizedAddress > 255) {
    throw createWorkflowError('INVALID_SLOT_ADDRESS', '卡槽地址无效，未发送开门指令')
  }
  return buildFrame(normalizedAddress, OPEN_DOOR_FUNCTION, [...COMMAND_PREFIX, TAKE_OPEN_FLAG])
}

function normalizeHex(value) {
  return String(value || '').replace(/[^0-9a-f]/gi, '').toUpperCase()
}

function parseHexBytes(hex) {
  const normalized = normalizeHex(hex)
  if (!normalized || normalized.length % 2 !== 0) return []
  return normalized.match(/.{2}/g).map((pair) => Number.parseInt(pair, 16))
}

function serialEventHex(event = {}) {
  if (typeof event === 'string') return event
  return event.hex || event.data?.hex || event.rawHex || ''
}

export function parseTakeOpenAck(event, expectedAddress) {
  const bytes = parseHexBytes(serialEventHex(event))
  if (bytes.length < 14 || bytes[0] !== 0xDD || bytes[1] !== 0xCC) return null
  if (bytes[5] !== Number(expectedAddress) || bytes[6] !== OPEN_DOOR_FUNCTION) return null
  const expectedCrc = crc16Modbus(bytes.slice(0, -2))
  if (bytes[bytes.length - 2] !== (expectedCrc >> 8) || bytes[bytes.length - 1] !== (expectedCrc & 0xFF)) {
    return null
  }
  if (!COMMAND_PREFIX.every((value, index) => bytes[7 + index] === value)) return null
  const resultCode = bytes[11]
  if (resultCode !== RESULT_OK && resultCode !== RESULT_FAILED) return null
  return {
    accepted: resultCode === RESULT_OK,
    address: bytes[5],
    functionCode: bytes[6],
    resultCode
  }
}

function slotItemsFromEvent(event) {
  if (Array.isArray(event)) return event
  if (Array.isArray(event?.slots)) return event.slots
  if (event?.slot) return [event.slot]
  if (event && (event.slotNumber != null || event.slotId != null || event.address != null)) return [event]
  return []
}

function slotNumberOf(slot = {}) {
  return Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address)
}

function cardNoOf(slot = {}) {
  return String(slot.cardNo || slot.cardNumber || slot.cardId || '').trim()
}

function buildCardEventPayload({ cardNo, action, timestamp, employeeId } = {}) {
  const normalizedCardNo = String(cardNo || '').trim()
  const normalizedAction = String(action || '').trim().toUpperCase()
  const normalizedTimestamp = Number(timestamp)
  if (!normalizedCardNo || !['TAKE', 'RETURN'].includes(normalizedAction) ||
    !Number.isSafeInteger(normalizedTimestamp) || normalizedTimestamp < 1) {
    throw createWorkflowError('INVALID_CARD_EVENT', '取还卡事件字段无效')
  }
  const payload = {
    cardNo: normalizedCardNo,
    action: normalizedAction,
    timestamp: normalizedTimestamp
  }
  if (normalizedAction === 'TAKE') {
    const normalizedEmployeeId = Number(employeeId)
    if (!Number.isSafeInteger(normalizedEmployeeId) || normalizedEmployeeId < 1) {
      throw createWorkflowError('INVALID_CARD_EVENT', '取卡事件缺少员工ID')
    }
    payload.employeeId = normalizedEmployeeId
  }
  return payload
}

function normalizeCardEventPayload(payload = {}) {
  return buildCardEventPayload({
    cardNo: payload.cardNo,
    action: payload.action || payload.eventType,
    timestamp: payload.timestamp,
    employeeId: payload.employeeId
  })
}

export function buildReturnCardTransition(previousSlot, currentSlot) {
  const slotNumber = slotNumberOf(currentSlot)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return null
  if (slotNumberOf(previousSlot) !== slotNumber) return null
  const previousStatus = String(previousSlot?.status || '').trim().toUpperCase()
  const currentStatus = String(currentSlot?.status || '').trim().toUpperCase()
  const cardNo = cardNoOf(currentSlot)
  if (!EMPTY_CARD_STATES.has(previousStatus) || cardNoOf(previousSlot)) return null
  if (!CARD_PRESENT_STATES.has(currentStatus) || !cardNo) return null
  return { slotNumber, cardNo, previousStatus, currentStatus }
}

function observeOpenedSlot(event, expectedSlotNumber) {
  const slot = slotItemsFromEvent(event).find((item) => slotNumberOf(item) === expectedSlotNumber)
  if (!slot || Number(slot.doorCode) !== 1) return null
  return { ...slot, slotNumber: expectedSlotNumber }
}

function createEventWaiter({ subscribe, eventNames, match, timeoutMs, timeoutCode, timeoutMessage }) {
  let settled = false
  let timer = null
  const unsubscribers = []
  const cleanup = () => {
    if (timer) clearTimeout(timer)
    timer = null
    unsubscribers.splice(0).forEach((unsubscribe) => {
      try { unsubscribe?.() } catch (error) {}
    })
  }
  const promise = new Promise((resolve, reject) => {
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    eventNames.forEach((eventName) => {
      const unsubscribe = subscribe(eventName, (event) => {
        try {
          const matched = match(event)
          if (matched) finish(resolve, matched)
        } catch (error) {
          finish(reject, error)
        }
      })
      unsubscribers.push(unsubscribe)
    })
    timer = setTimeout(() => {
      finish(reject, createWorkflowError(timeoutCode, timeoutMessage))
    }, timeoutMs)
  })
  return {
    promise,
    cancel() {
      if (settled) return
      settled = true
      cleanup()
    }
  }
}

function normalizeTimeouts(settings = {}) {
  const responseTimeout = Number(settings.serialResponseTimeout)
  const pollInterval = Number(settings.serialPollInterval)
  // 先让可能正在等待的单次轮询收尾，再覆盖两次 300ms 的开门应答；
  // 0x11 一到即返回前台，不把这段上限作为正常操作等待时间。
  const ackTimeoutMs = Math.max(1800, Math.min(30000,
    Number.isFinite(responseTimeout) && responseTimeout > 0 ? responseTimeout : 3000
  ))
  const normalizedPollInterval = Number.isFinite(pollInterval) && pollInterval > 0 ? pollInterval : 1000
  const physicalTimeoutMs = Math.max(5000, Math.min(60000, ackTimeoutMs + normalizedPollInterval * 3))
  return { ackTimeoutMs, physicalTimeoutMs }
}

function normalizeIdentity(identity = {}) {
  const rawEmployeeId = identity.employee?.employeeId ?? identity.employeeId
  const employeeId = Number(rawEmployeeId)
  const faceId = String(identity.faceId || '').trim()
  if (!Number.isSafeInteger(employeeId) || employeeId < 1) {
    throw createWorkflowError('INVALID_EMPLOYEE_ID', '员工ID不符合 V4.2 Long 类型要求，未执行取卡')
  }
  if (!faceId) throw createWorkflowError('FACE_ID_REQUIRED', '缺少本机人脸绑定，未执行取卡')
  return {
    employeeId,
    employeeName: String(identity.employee?.employeeName || identity.employeeName || '').trim(),
    faceId,
    authType: 'FACE'
  }
}

function slotSelectionError(result = {}) {
  const messages = {
    SERIAL_SNAPSHOT_UNAVAILABLE: '当前无法读取卡柜实时状态，请稍后重试',
    SLOT_SNAPSHOT_STALE: '没有可用的实时卡槽数据，请等待串口刷新后重试',
    NO_TAKEABLE_CARD: '当前卡柜暂无可用工卡，请联系管理员补充或检查卡柜状态',
    INVALID_SELECTION_TIME: '设备时间异常，暂无法选择工卡'
  }
  return createWorkflowError(
    result.reason || 'CARD_SLOT_SELECTION_FAILED',
    messages[result.reason] || '没有可用的卡槽，未执行取卡',
    result
  )
}

export function createTakeCardWorkflow(dependencies = {}) {
  const {
    selectTakeCardSlot,
    saveOperation,
    getOperation,
    listRecoverableOperations,
    saveOutbox,
    getOutbox,
    listDueOutbox,
    markOutboxSent,
    markOutboxFailed,
    sendOpenDoor,
    queryTargetSlot,
    subscribe,
    canDispatch,
    reportCardEvent,
    reportStatusAfterTake,
    getSettings = () => ({}),
    now = () => Date.now(),
    createOperationId = ({ employeeId, timestamp }) => `take:${employeeId}:${timestamp}:${Math.random().toString(36).slice(2, 8)}`,
    createReturnOperationId = ({ slotNumber, cardNo, timestamp }) => `return:${slotNumber}:${cardNo}:${timestamp}`
  } = dependencies

  const required = {
    selectTakeCardSlot,
    saveOperation,
    getOperation,
    listRecoverableOperations,
    saveOutbox,
    getOutbox,
    listDueOutbox,
    markOutboxSent,
    markOutboxFailed,
    sendOpenDoor,
    subscribe,
    canDispatch,
    reportCardEvent,
    reportStatusAfterTake
  }
  Object.entries(required).forEach(([name, value]) => {
    if (typeof value !== 'function') throw new Error(`take card workflow dependency missing: ${name}`)
  })

  const persistOperation = async (operationType, operationId, details, patch) => {
    const saved = await saveOperation({
      operationId,
      operationType,
      ...details,
      ...patch
    })
    if (!saved) throw createWorkflowError('OPERATION_PERSIST_FAILED', '卡事件操作状态保存失败')
    return saved
  }

  const persist = (operationId, identity, patch) => persistOperation('TAKE_CARD', operationId, {
    employeeId: identity.employeeId,
    employeeName: identity.employeeName,
    faceId: identity.faceId,
    cardNo: identity.cardNo || '',
    cardCondition: identity.cardCondition || '',
    authType: identity.authType
  }, patch)

  const persistReturn = (operationId, transition, patch) => persistOperation('RETURN_CARD', operationId, {
    cardNo: transition.cardNo,
    slotNumber: transition.slotNumber,
    authType: 'SYSTEM',
    operatorName: '系统自动识别'
  }, patch)

  const failOperation = async (operationId, identity, error, state = 'FAILED') => {
    try {
      await persist(operationId, identity, {
        state,
        rawError: {
          code: error?.code || 'TAKE_CARD_FAILED',
          message: error?.message || String(error)
        }
      })
    } catch (persistError) {
      console.warn('[takeCard] persist failure state failed:', persistError)
    }
  }

  const recoverPendingReports = async (limit = 200) => {
    const operations = await listRecoverableOperations(limit)
    const candidates = operations.filter((operation) => {
      return ['TAKE_CARD', 'RETURN_CARD'].includes(operation?.operationType) &&
        ['PHYSICAL_CONFIRMED', 'REPORT_PENDING'].includes(operation.state)
    })
    let repaired = 0
    let completed = 0
    let failed = 0

    for (const operation of candidates) {
      try {
        const slotNumber = Number(operation.slotNumber)
        const cardNo = String(operation.cardNo || operation.cardEvent?.cardNo || '').trim()
        const timestamp = Number(
          operation.cardEvent?.timestamp ||
          operation.physicalConfirmedAt ||
          operation.updatedAt ||
          operation.createdAt
        )
        if (!operation.operationId || !Number.isInteger(slotNumber) || slotNumber < 1 || !cardNo ||
          !Number.isSafeInteger(timestamp) || timestamp < 1) {
          throw createWorkflowError('INVALID_RECOVERABLE_OPERATION', '卡事件恢复记录缺少必要业务字段')
        }

        const isTake = operation.operationType === 'TAKE_CARD'
        const employeeId = Number(operation.employeeId)
        if (isTake && (!Number.isSafeInteger(employeeId) || employeeId < 1)) {
          throw createWorkflowError('INVALID_RECOVERABLE_OPERATION', '取卡恢复记录缺少员工ID')
        }
        const cardEvent = buildCardEventPayload({
          cardNo,
          action: isTake ? 'TAKE' : 'RETURN',
          timestamp,
          employeeId: isTake ? employeeId : undefined
        })
        const eventId = `card-event:${operation.operationId}`
        const existing = await getOutbox(eventId)
        if (existing?.state === 'SENT') {
          await persistOperation(operation.operationType, operation.operationId, operation, {
            state: 'COMPLETED',
            slotNumber,
            cardEvent,
            outboxEventId: eventId,
            recoveredFromOutbox: true
          })
          completed += 1
          continue
        }

        const savedOutbox = await saveOutbox({
          eventId,
          eventType: CARD_EVENT_OUTBOX_TYPE,
          operationId: operation.operationId,
          payload: { cmd: 'cardEvent', data: cardEvent },
          state: existing?.state === 'FAILED' ? 'FAILED' : 'PENDING'
        })
        if (!savedOutbox) {
          throw createWorkflowError('CARD_EVENT_PERSIST_FAILED', '卡事件恢复补传记录保存失败')
        }
        if (!existing || existing.state === 'PROCESSING') repaired += 1
        if (operation.state !== 'REPORT_PENDING' || operation.outboxEventId !== eventId) {
          await persistOperation(operation.operationType, operation.operationId, operation, {
            state: 'REPORT_PENDING',
            slotNumber,
            cardEvent,
            outboxEventId: eventId,
            recoveredAfterRestart: true
          })
        }
      } catch (error) {
        failed += 1
        console.warn('[takeCard] recover pending report failed:', error)
      }
    }
    return { total: candidates.length, repaired, completed, failed }
  }

  const reportOutboxEvent = async (event) => {
    const data = event?.payload?.data
    if (!data || event?.payload?.cmd !== 'cardEvent') {
      throw createWorkflowError('INVALID_CARD_EVENT_OUTBOX', '卡事件上报补传记录无效')
    }
    const cardEvent = normalizeCardEventPayload(data)
    const canonicalizedEvent = await saveOutbox({
      ...event,
      payload: { ...event.payload, data: cardEvent }
    })
    if (!canonicalizedEvent) throw createWorkflowError('CARD_EVENT_PERSIST_FAILED', '卡事件补传记录更新失败')
    const result = await reportCardEvent(cardEvent)
    const sentEvent = await markOutboxSent(event.eventId)
    if (!sentEvent) throw createWorkflowError('CARD_EVENT_ACK_SAVE_FAILED', '卡事件已上报，但本机确认状态保存失败')
    try {
      const existing = await getOperation(event.operationId)
      if (['PHYSICAL_CONFIRMED', 'REPORT_PENDING'].includes(existing?.state)) {
        await saveOperation({
          operationId: event.operationId,
          operationType: existing.operationType || (cardEvent.action === 'RETURN' ? 'RETURN_CARD' : 'TAKE_CARD'),
          state: 'COMPLETED',
          reportTransport: result?.transport || 'HTTP',
          reportResult: result || null
        })
      }
    } catch (error) {
      // 后端已经明确确认，不能把 outbox 降回 FAILED 并造成重复 cardEvent。
      console.warn('[takeCard] cardEvent acknowledged but operation history update failed:', error)
    }
    return result
  }

  const flushPendingReports = async (limit = 20) => {
    await recoverPendingReports(Math.max(200, limit))
    const events = await listDueOutbox(CARD_EVENT_OUTBOX_TYPE, limit)
    let sent = 0
    let failed = 0
    for (const event of events) {
      try {
        await reportOutboxEvent(event)
        sent += 1
      } catch (error) {
        failed += 1
        await markOutboxFailed(event.eventId, error, 10000)
      }
    }
    return { total: events.length, sent, failed }
  }

  const finalizePhysicalTake = async ({
    operationId,
    identity,
    operationIdentity,
    slotNumber,
    election,
    batteryPercent,
    observedSlot,
    progress
  }) => {
    const physicalConfirmedAt = now()
    const cardPresentedProgress = {
      state: 'CARD_PRESENTED',
      slotNumber,
      cardCondition: operationIdentity.cardCondition,
      batteryPercent,
      conditionTip: String(election.conditionTip || ''),
      observedSlot,
      physicalConfirmed: true,
      message: `${String(slotNumber).padStart(2, '0')}号卡槽已打开，请取走工卡`
    }
    progress({ ...cardPresentedProgress, state: 'CARD_PRESENTED_ANNOUNCEMENT' })
    await persist(operationId, operationIdentity, {
      state: 'PHYSICAL_CONFIRMED',
      slotNumber,
      observedSlot,
      physicalConfirmedAt,
      cardPresentedAt: physicalConfirmedAt
    })
    progress(cardPresentedProgress)

    const statusReportPromise = Promise.resolve()
      .then(() => reportStatusAfterTake({
        operationId,
        operationType: 'TAKE_CARD',
        slotNumber,
        observedSlot,
        physicalConfirmedAt
      }))
      .catch((error) => ({
        sent: false,
        queued: false,
        error: error?.message || String(error)
      }))
    const cardEvent = buildCardEventPayload({
      cardNo: operationIdentity.cardNo,
      action: 'TAKE',
      employeeId: identity.employeeId,
      timestamp: physicalConfirmedAt
    })
    const eventId = `card-event:${operationId}`
    const savedOutbox = await saveOutbox({
      eventId,
      eventType: CARD_EVENT_OUTBOX_TYPE,
      operationId,
      payload: { cmd: 'cardEvent', data: cardEvent },
      state: 'PENDING'
    })
    if (!savedOutbox) throw createWorkflowError('CARD_EVENT_PERSIST_FAILED', '卡已取出，但事件补传记录保存失败')
    await persist(operationId, operationIdentity, {
      state: 'REPORT_PENDING',
      slotNumber,
      cardEvent,
      outboxEventId: eventId
    })

    try {
      const reportResult = await reportOutboxEvent({
        eventId,
        eventType: CARD_EVENT_OUTBOX_TYPE,
        operationId,
        payload: { cmd: 'cardEvent', data: cardEvent }
      })
      const statusReport = await statusReportPromise
      progress({
        state: 'COMPLETED',
        slotNumber,
        cardCondition: operationIdentity.cardCondition,
        batteryPercent,
        conditionTip: String(election.conditionTip || ''),
        message: '取卡完成，事件已上报'
      })
      return {
        accepted: true,
        completed: true,
        operationId,
        slotNumber,
        cardNo: operationIdentity.cardNo,
        cardCondition: operationIdentity.cardCondition,
        batteryPercent,
        conditionTip: String(election.conditionTip || ''),
        reportPending: false,
        reportResult,
        statusReport
      }
    } catch (error) {
      await markOutboxFailed(eventId, error, 10000)
      const statusReport = await statusReportPromise
      progress({
        state: 'REPORT_PENDING',
        slotNumber,
        cardCondition: operationIdentity.cardCondition,
        batteryPercent,
        conditionTip: String(election.conditionTip || ''),
        message: '工卡已取出，上报已进入离线补传'
      })
      return {
        accepted: true,
        completed: true,
        operationId,
        slotNumber,
        cardNo: operationIdentity.cardNo,
        cardCondition: operationIdentity.cardCondition,
        conditionTip: String(election.conditionTip || ''),
        reportPending: true,
        reportError: error?.message || String(error),
        statusReport
      }
    }
  }

  const deferTakePersistence = (operationId, identity, patch, label) => {
    persist(operationId, identity, patch)
      .catch((error) => console.warn(`[takeCard] ${label} persistence deferred:`, error))
  }

  const deferTakeFailure = (operationId, identity, error, state) => {
    failOperation(operationId, identity, error, state)
      .catch((persistError) => console.warn('[takeCard] deferred failure persistence failed:', persistError))
  }

  const take = async (rawIdentity = {}, progress = () => {}) => {
    const identity = normalizeIdentity(rawIdentity)
    // Recovery scans SQLite and may send old reports; a new physical open must not wait for it.
    Promise.resolve()
      .then(() => recoverPendingReports())
      .catch((error) => console.warn('[takeCard] deferred report recovery failed:', error))
    const timestamp = now()
    const operationId = createOperationId({ ...identity, timestamp })
    const startedAt = Date.now()
    let waitForPresented = null
    let takeConfirmed = false
    let targetSlotNumber = null
    let operationIdentity = identity
    let backgroundPhysicalConfirmation = false
    let immediateBoardAckPath = false

    deferTakePersistence(operationId, identity, { state: 'RECEIVED', createdAt: timestamp }, 'received')
    try {
      // 不根据历史 TAKE_CARD / RETURN_CARD 记录阻止员工再次取卡。
      // operationId 每次包含时间戳+随机串，天然唯一；operation 记录仅用于物理证据、上报和非终态恢复。
      deferTakePersistence(operationId, identity, { state: 'SELECTING' }, 'selecting')
      progress({ state: 'SELECTING', message: '正在根据卡状态和电量选择工卡' })
      const selection = await selectTakeCardSlot({ requireFresh: true })
      if (!selection?.ok) throw slotSelectionError(selection)
      const slot = selection.slot || {}
      const slotNumber = slotNumberOf(slot)
      const slotStatus = String(slot.status || '').trim().toUpperCase()
      // 串口状态已确认有卡但卡号不可读时，按 15 字节卡号字段上报全 0。
      const selectedCardNo = cardNoOf(slot) || UNKNOWN_CARD_NO
      if (!Number.isInteger(slotNumber) || slotNumber < 1 || slotNumber > 255) {
        throw createWorkflowError('INVALID_SLOT_ADDRESS', '卡槽地址无效，未执行取卡')
      }
      targetSlotNumber = slotNumber
      const election = selection.election || selection
      const rawBatteryPercent = election.batteryPercent
      const normalizedBatteryPercent = Number(rawBatteryPercent)
      const batteryPercent = rawBatteryPercent != null && rawBatteryPercent !== '' &&
        Number.isFinite(normalizedBatteryPercent) && normalizedBatteryPercent >= 0 && normalizedBatteryPercent <= 100
        ? Math.round(normalizedBatteryPercent)
        : null
      operationIdentity = {
        ...identity,
        cardNo: selectedCardNo,
        cardCondition: String(election.condition || '').trim().toUpperCase()
      }

      const validatedPatch = {
        state: 'VALIDATED',
        slotNumber,
        initialSlotStatus: slotStatus,
        initialSlotCardNo: selectedCardNo,
        selectionCandidateCount: Number(election.candidateCount || 0),
        selectionVoltage: election.voltage ?? null,
        selectionBatteryPercent: batteryPercent,
        conditionTip: String(election.conditionTip || '')
      }
      deferTakePersistence(operationId, operationIdentity, validatedPatch, 'validated')
      progress({
        state: 'VALIDATED',
        slotNumber,
        cardCondition: operationIdentity.cardCondition,
        batteryPercent,
        conditionTip: String(election.conditionTip || ''),
        message: `已锁定${String(slotNumber).padStart(2, '0')}号卡槽，正在校验开门条件`
      })

      const dispatch = await canDispatch(slot)
      if (dispatch !== true && dispatch?.allowed !== true) {
        throw createWorkflowError(
          dispatch?.code || 'SERIAL_TOPOLOGY_UNCONFIRMED',
          dispatch?.message || '真实设备出站卡位拓扑尚未确认，未发送取卡指令'
        )
      }
      const requiresBoardAck = dispatch === true || dispatch?.requiresBoardAck !== false
      immediateBoardAckPath = requiresBoardAck

      const { ackTimeoutMs, physicalTimeoutMs } = normalizeTimeouts(getSettings())
      waitForPresented = createEventWaiter({
        subscribe,
        eventNames: ['slot.status', 'cabinet.slotsSnapshot'],
        match: (event) => observeOpenedSlot(event, slotNumber),
        timeoutMs: physicalTimeoutMs,
        timeoutCode: 'CARD_PRESENTED_TIMEOUT',
        timeoutMessage: '未收到目标卡槽开门状态'
      })

      const queuedPatch = { state: 'QUEUED', slotNumber }
      if (requiresBoardAck) {
        deferTakePersistence(operationId, operationIdentity, queuedPatch, 'queued')
      } else {
        await persist(operationId, operationIdentity, queuedPatch)
      }
      progress({ state: 'QUEUED', slotNumber, message: `正在打开${String(slotNumber).padStart(2, '0')}号卡槽` })
      const serialResult = await sendOpenDoor({
        operationId,
        slotNumber,
        requiresBoardAck,
        ackTimeoutMs
      })
      if (serialResult?.sent !== true) {
        throw createWorkflowError('SERIAL_SEND_NOT_ACCEPTED', '串口能力未确认取卡指令已发送')
      }
      const serialSentPatch = { state: 'SERIAL_SENT', slotNumber }
      if (requiresBoardAck) {
        deferTakePersistence(operationId, operationIdentity, serialSentPatch, 'serial-sent')
      } else {
        await persist(operationId, operationIdentity, serialSentPatch)
      }
      progress({
        state: 'SERIAL_SENT',
        slotNumber,
        message: requiresBoardAck
          ? '开门指令已发送，正在等待板级应答'
          : '模拟开门指令已发送，正在确认卡槽状态'
      })
      const physicalPendingPatch = { state: 'PHYSICAL_PENDING', slotNumber }
      if (requiresBoardAck) {
        deferTakePersistence(operationId, operationIdentity, physicalPendingPatch, 'physical-pending')
      } else {
        await persist(operationId, operationIdentity, physicalPendingPatch)
      }
      progress({
        state: 'PHYSICAL_PENDING',
        slotNumber,
        message: `${String(slotNumber).padStart(2, '0')}号卡槽正在等待开门状态`
      })
      if (requiresBoardAck) {
        const ack = serialResult?.boardAck
        if (!ack) {
          throw createWorkflowError('SERIAL_ACK_MISSING', '开门指令未返回板级应答')
        }
        if (!ack.accepted) {
          throw createWorkflowError('SERIAL_COMMAND_REJECTED', '开门板级应答为执行失败', ack)
        }
        takeConfirmed = true
        const commandAcceptedAt = now()
        console.info(`[face-take] slot=${slotNumber} stage=board-ack elapsedMs=${Date.now() - startedAt}`)
        const openingProgress = {
          state: 'CARD_PRESENTED',
          slotNumber,
          cardCondition: operationIdentity.cardCondition,
          batteryPercent,
          conditionTip: String(election.conditionTip || ''),
          boardAcknowledged: true,
          physicalConfirmed: false,
          message: `${String(slotNumber).padStart(2, '0')}号卡槽已打开，请取走工卡`
        }
        // 0x11 说明开门命令已被板卡接受：现场立即解除等待，卡槽确认和上报在后台完成。
        progress({ ...openingProgress, state: 'CARD_PRESENTED_ANNOUNCEMENT' })
        progress(openingProgress)
        persist(operationId, operationIdentity, {
          state: 'BOARD_ACKED',
          slotNumber,
          commandAcceptedAt,
          confirmationSource: 'SERIAL_BOARD_ACK'
        }).catch((error) => console.warn('[takeCard] board acknowledgement persistence failed:', error))
        Promise.resolve()
          .then(() => queryTargetSlot?.(slotNumber))
          .catch((error) => console.warn('[takeCard] target slot query failed:', error))
        backgroundPhysicalConfirmation = true
        waitForPresented.promise
          .then((observedSlot) => finalizePhysicalTake({
            operationId,
            identity,
            operationIdentity,
            slotNumber,
            election,
            batteryPercent,
            observedSlot,
            progress
          }))
          .catch(async (error) => {
            console.warn('[takeCard] physical confirmation deferred:', error)
            await persist(operationId, operationIdentity, {
              state: 'BOARD_ACKED',
              slotNumber,
              commandAcceptedAt,
              confirmationSource: 'SERIAL_BOARD_ACK',
              rawError: { code: error?.code || 'PHYSICAL_CONFIRM_DEFERRED', message: error?.message || String(error) }
            }).catch(() => {})
          })
        return {
          accepted: true,
          completed: false,
          boardAcknowledged: true,
          physicalConfirmed: false,
          operationId,
          slotNumber,
          cardNo: operationIdentity.cardNo,
          cardCondition: operationIdentity.cardCondition,
          batteryPercent,
          conditionTip: String(election.conditionTip || ''),
          reportPending: true,
          message: openingProgress.message
        }
      }

      // 模拟器没有 0x11 时，仍以目标卡槽状态作为完成依据。
      Promise.resolve()
        .then(() => queryTargetSlot?.(slotNumber))
        .catch((error) => console.warn('[takeCard] target slot query failed:', error))
      const observedSlot = await waitForPresented.promise
      takeConfirmed = true
      return finalizePhysicalTake({
        operationId,
        identity,
        operationIdentity,
        slotNumber,
        election,
        batteryPercent,
        observedSlot,
        progress
      })
    } catch (error) {
      const timedOut = String(error?.code || '').endsWith('_TIMEOUT')
      const failureState = timedOut ? 'TIMED_OUT' : 'FAILED'
      if (immediateBoardAckPath) {
        deferTakeFailure(operationId, operationIdentity, error, takeConfirmed ? 'REPORT_PENDING' : failureState)
      } else {
        await failOperation(operationId, operationIdentity, error, takeConfirmed ? 'REPORT_PENDING' : failureState)
      }
      if (Number.isInteger(targetSlotNumber) && targetSlotNumber > 0) {
        progress({
          state: takeConfirmed ? 'REPORT_PENDING' : failureState,
          slotNumber: targetSlotNumber,
          message: takeConfirmed
            ? '卡槽已打开，上报状态待恢复'
            : (error?.message || '卡槽执行失败')
        })
      }
      throw error
    } finally {
      if (!backgroundPhysicalConfirmation) waitForPresented?.cancel()
    }
  }

  const observedReturnSlots = new Set()
  const returnInFlight = new Set()

  const observeReturn = async (previousSlot, currentSlot) => {
    const slotNumber = slotNumberOf(currentSlot)
    if (!Number.isInteger(slotNumber) || slotNumber < 1) {
      return { observed: false, reason: 'INVALID_SLOT' }
    }
    if (!observedReturnSlots.has(slotNumber)) {
      observedReturnSlots.add(slotNumber)
      return { observed: false, reason: 'BASELINE_ESTABLISHED' }
    }
    const transition = buildReturnCardTransition(previousSlot, currentSlot)
    if (!transition) return { observed: false, reason: 'NO_RETURN_TRANSITION' }

    const inFlightKey = `${transition.slotNumber}:${transition.cardNo}`
    if (returnInFlight.has(inFlightKey)) return { observed: false, reason: 'RETURN_IN_PROGRESS' }
    returnInFlight.add(inFlightKey)
    try {
      const timestamp = now()
      const operationId = createReturnOperationId({ ...transition, timestamp })
      const cardEvent = buildCardEventPayload({
        cardNo: transition.cardNo,
        action: 'RETURN',
        timestamp
      })
      await persistReturn(operationId, transition, {
        state: 'PHYSICAL_CONFIRMED',
        createdAt: timestamp,
        physicalConfirmedAt: timestamp,
        previousSlot: { ...previousSlot },
        observedSlot: { ...currentSlot },
        cardEvent
      })
      const eventId = `card-event:${operationId}`
      const savedOutbox = await saveOutbox({
        eventId,
        eventType: CARD_EVENT_OUTBOX_TYPE,
        operationId,
        payload: { cmd: 'cardEvent', data: cardEvent },
        state: 'PENDING'
      })
      if (!savedOutbox) throw createWorkflowError('CARD_EVENT_PERSIST_FAILED', '还卡已确认，但事件补传记录保存失败')
      await persistReturn(operationId, transition, {
        state: 'REPORT_PENDING',
        cardEvent,
        outboxEventId: eventId
      })
      try {
        const reportResult = await reportOutboxEvent({
          eventId,
          eventType: CARD_EVENT_OUTBOX_TYPE,
          operationId,
          payload: { cmd: 'cardEvent', data: cardEvent }
        })
        return {
          observed: true,
          completed: true,
          operationId,
          slotNumber: transition.slotNumber,
          cardNo: transition.cardNo,
          reportPending: false,
          reportResult
        }
      } catch (error) {
        await markOutboxFailed(eventId, error, 10000)
        return {
          observed: true,
          completed: true,
          operationId,
          slotNumber: transition.slotNumber,
          cardNo: transition.cardNo,
          reportPending: true,
          reportError: error?.message || String(error)
        }
      }
    } finally {
      returnInFlight.delete(inFlightKey)
    }
  }

  return { take, observeReturn, flushPendingReports, recoverPendingReports }
}
