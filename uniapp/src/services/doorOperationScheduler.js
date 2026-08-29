function schedulerError (code, message) {
  const error = new Error(message)
  error.code = code
  return error
}

function isSendAccepted (result) {
  return result?.success === true || result?.sent === true
}

/** Vue 层只统一开门时序，调用方继续拥有各自的业务完成和上报规则。 */
export function createDoorOperationScheduler ({
  sendOpenDoor,
  subscribe,
  matchTransmit,
  matchAck,
  matchTimeout,
  now = () => Date.now()
} = {}) {
  const required = { sendOpenDoor, subscribe, matchTransmit, matchAck }
  Object.entries(required).forEach(([name, value]) => {
    if (typeof value !== 'function') throw new Error(`door operation scheduler dependency missing: ${name}`)
  })

  const queue = []
  const reservedSlots = new Set()
  let active = null

  const dispatchNext = () => {
    if (active || queue.length === 0) return
    active = queue.shift()
    run(active)
      .then(active.resolve, active.reject)
      .finally(() => {
        reservedSlots.delete(active.request.slotNumber)
        active = null
        dispatchNext()
      })
  }

  const run = (job) => new Promise((resolve, reject) => {
    const request = job.request
    const waitForAck = request.requiresBoardAck !== false
    const expectTransmitEvent = request.expectTransmitEvent !== false
    let settled = false
    let sentAt = null
    let txTimer = null
    let ackTimer = null
    let unsubscribe = null

    const cleanup = () => {
      if (txTimer) clearTimeout(txTimer)
      if (ackTimer) clearTimeout(ackTimer)
      txTimer = null
      ackTimer = null
      if (unsubscribe) unsubscribe()
      unsubscribe = null
    }
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    const beginAckWait = (event) => {
      if (sentAt != null) return
      sentAt = Number(event?.timestamp) || now()
      if (txTimer) clearTimeout(txTimer)
      txTimer = null
      if (!waitForAck) {
        finish(resolve, { sent: true, queued: true, serialSentAt: sentAt, boardAck: null })
        return
      }
      ackTimer = setTimeout(() => {
        finish(reject, schedulerError(
          'SERIAL_ACK_TIMEOUT',
          `${request.slotNumber}号卡槽开门等待板级应答超时`
        ))
      }, request.ackTimeoutMs)
    }

    unsubscribe = subscribe('serial.dataReceived', (event) => {
      if (!settled && matchTransmit(event, request)) beginAckWait(event)
      if (settled || sentAt == null) return
      if (typeof matchTimeout === 'function' && matchTimeout(event, request)) {
        finish(reject, schedulerError(
          'SERIAL_ACK_TIMEOUT',
          `${request.slotNumber}号卡槽开门等待板级应答超时`
        ))
        return
      }
      const ack = matchAck(event, request)
      if (!ack) return
      if (ack.accepted) {
        finish(resolve, { sent: true, queued: true, serialSentAt: sentAt, boardAck: ack })
      } else {
        finish(reject, schedulerError(
          'SERIAL_COMMAND_REJECTED',
          `${request.slotNumber}号卡槽开门被设备板级拒绝`
        ))
      }
    })

    // 浏览器 Mock 不会回传原生 serialTx，须在模拟发送前就开始接收其同步 ACK。
    if (!expectTransmitEvent) beginAckWait({ timestamp: now() })

    Promise.resolve()
      .then(() => sendOpenDoor(request.slotNumber, request.administrator === true))
      .then((result) => {
        if (!isSendAccepted(result)) {
          finish(reject, schedulerError('SERIAL_SEND_NOT_ACCEPTED', '串口能力未确认开门指令已发送'))
          return
        }
        if (!expectTransmitEvent || sentAt != null) return
        txTimer = setTimeout(() => {
          finish(reject, schedulerError(
            'SERIAL_SEND_TIMEOUT',
            `${request.slotNumber}号卡槽开门指令未实际写入串口`
          ))
        }, request.txTimeoutMs)
      })
      .catch((error) => finish(reject, error))
  })

  return {
    dispatch (input = {}) {
      const slotNumber = Number(input.slotNumber)
      if (!Number.isInteger(slotNumber) || slotNumber < 1) {
        return Promise.reject(schedulerError('INVALID_SLOT', '卡槽号无效，无法执行开门'))
      }
      if (reservedSlots.has(slotNumber)) {
        return Promise.reject(schedulerError('SLOT_OPERATION_IN_PROGRESS', `${slotNumber}号卡槽正在执行开门操作`))
      }
      const request = {
        ...input,
        slotNumber,
        txTimeoutMs: Math.max(500, Number(input.txTimeoutMs) || 3000),
        ackTimeoutMs: Math.max(300, Number(input.ackTimeoutMs) || 1800)
      }
      reservedSlots.add(slotNumber)
      return new Promise((resolve, reject) => {
        queue.push({ request, resolve, reject })
        dispatchNext()
      })
    },
    pendingCount () {
      return queue.length + (active ? 1 : 0)
    }
  }
}
