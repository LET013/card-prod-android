const TAKEABLE_STATUSES = new Set([
  'FULL',
  'OCCUPIED',
  'CHARGING',
  'ILLEGAL_CARD',
  'CHARGING_FAULT',
  'COMMUNICATION_FAULT'
])
const finiteVoltageOf = (slot = {}) => {
  const voltage = Number(slot.voltage)
  return Number.isFinite(voltage) ? voltage : Number.NEGATIVE_INFINITY
}

export const TAKE_CARD_CONDITION = Object.freeze({
  FULL: 'FULL',
  OCCUPIED: 'OCCUPIED',
  CHARGING: 'CHARGING'
})

const CONDITION_PRIORITY = Object.freeze({
  [TAKE_CARD_CONDITION.FULL]: 0,
  [TAKE_CARD_CONDITION.OCCUPIED]: 1,
  [TAKE_CARD_CONDITION.CHARGING]: 2,
  FAULT: 3
})

const slotNumberOf = (slot = {}) => Number(slot.slotNumber ?? slot.slotId ?? slot.address)
const cardNoOf = (slot = {}) => String(slot.cardNo || slot.cardNumber || slot.cardId || '').trim()

// 串口卡状态 1=有卡、2=读卡错误；读不到卡号不代表卡不存在。
const hasPhysicalCard = (slot = {}) => {
  const rawCardCode = slot.cardCode ?? slot.cardStatusCode
  if (rawCardCode != null && rawCardCode !== '') {
    const cardCode = Number(rawCardCode)
    return cardCode === 1 || cardCode === 2
  }
  // 兼容旧快照：没有串口卡状态时，只有已读取的卡号可证明卡在位。
  return Boolean(cardNoOf(slot))
}

const batteryPercentOf = (slot = {}) => {
  const percent = Number(slot.batteryPercent ?? slot.batteryPercentage)
  return Number.isFinite(percent) && percent >= 0 && percent <= 100
    ? Math.round(percent)
    : null
}

const compareAvailableCharge = (left, right) => {
  const leftPercent = batteryPercentOf(left)
  const rightPercent = batteryPercentOf(right)
  if (leftPercent != null || rightPercent != null) {
    if (leftPercent == null) return 1
    if (rightPercent == null) return -1
    if (leftPercent !== rightPercent) return rightPercent - leftPercent
  }
  const voltageDifference = finiteVoltageOf(right) - finiteVoltageOf(left)
  if (voltageDifference !== 0) return voltageDifference
  return slotNumberOf(left) - slotNumberOf(right)
}

const matchesProtocolWorkCode = (slot, status) => {
  const rawWorkCode = slot?.workCode ?? slot?.workStatusCode
  if (rawWorkCode == null || rawWorkCode === '') return true
  const workCode = Number(rawWorkCode)
  if (!Number.isInteger(workCode)) return false
  if (status === 'OCCUPIED') return workCode === 1
  if (status === 'CHARGING') return workCode === 2
  if (status === 'FULL') return workCode === 3
  // 状态只决定展示与优先级，不能阻止已确认在位的工卡取出。
  return true
}

export function computeTakeCardSlotMaxAgeMs({
  configuredPollIntervalMs = 5000,
  pollingIntervalMs = 0,
  totalSlots = 1,
  responseTimeoutMs = 0
} = {}) {
  const configuredWindow = Math.max(1000, Number(configuredPollIntervalMs) || 5000) * 3
  const nativePollInterval = Math.max(0, Number(pollingIntervalMs) || 0)
  const nativeSlotCount = Math.max(1, Number(totalSlots) || 1)
  const nativeResponseTimeout = Math.max(0, Number(responseTimeoutMs) || 0)
  const nativeSweepWindow = nativePollInterval > 0
    ? ((nativePollInterval * nativeSlotCount) + nativeResponseTimeout) * 2
    : 0
  return Math.min(60000, Math.max(3000, configuredWindow, nativeSweepWindow))
}

export function takeCardConditionOf(slot = {}) {
  const status = String(slot.status || '').trim().toUpperCase()
  const chargeStatus = String(slot.chargeStatus || slot.chargingStatus || '').trim().toUpperCase()
  if (status === 'FULL' || chargeStatus === 'FULL') return TAKE_CARD_CONDITION.FULL
  if (status === 'OCCUPIED') return TAKE_CARD_CONDITION.OCCUPIED
  if (status === 'CHARGING') return TAKE_CARD_CONDITION.CHARGING
  if (TAKEABLE_STATUSES.has(status)) return TAKE_CARD_CONDITION.OCCUPIED
  return TAKE_CARD_CONDITION.OCCUPIED
}

function takeCardPriorityOf(slot = {}) {
  const status = String(slot.status || '').trim().toUpperCase()
  const chargeStatus = String(slot.chargeStatus || slot.chargingStatus || '').trim().toUpperCase()
  if (status === 'FULL' || chargeStatus === 'FULL') return TAKE_CARD_CONDITION.FULL
  if (status === 'OCCUPIED') return TAKE_CARD_CONDITION.OCCUPIED
  if (status === 'CHARGING') return TAKE_CARD_CONDITION.CHARGING
  return 'FAULT'
}

export function takeCardConditionTip(condition) {
  const tips = {
    [TAKE_CARD_CONDITION.FULL]: '工卡已充满，可正常使用；请按时归还。',
    [TAKE_CARD_CONDITION.OCCUPIED]: '工卡已就绪，可正常取用；请在使用后及时归还。',
    [TAKE_CARD_CONDITION.CHARGING]: '工卡尚未充满，续航可能不足；请留意使用时长，并在使用后尽快归还充电。'
  }
  return tips[condition] || '请留意工卡状态，并在使用后及时归还。'
}

function isCurrentSlot(slot, now, maxAgeMs) {
  if (slot?.fresh === false) return false
  const updatedAt = Number(slot?.updatedAt || slot?.updated_at || 0)
  const age = now - updatedAt
  return Number.isFinite(updatedAt) && updatedAt > 0 && age >= -1000 && age <= maxAgeMs
}

export function selectTakeCardCandidate({
  slots,
  now = Date.now(),
  maxAgeMs = 15000
} = {}) {
  const currentTime = Number(now)
  const allowedAge = Math.max(1000, Number(maxAgeMs) || 15000)
  if (!Number.isFinite(currentTime)) return { ok: false, reason: 'INVALID_SELECTION_TIME' }

  const eligible = (Array.isArray(slots) ? slots : []).filter((slot) => {
    const slotNumber = slotNumberOf(slot)
    const status = String(slot?.status || '').trim().toUpperCase()
    return Number.isInteger(slotNumber) && slotNumber >= 1 && slotNumber <= 255 &&
      matchesProtocolWorkCode(slot, status) && hasPhysicalCard(slot)
  })
  const candidates = eligible
    .filter((slot) => isCurrentSlot(slot, currentTime, allowedAge))
    .sort((left, right) => {
      const conditionDifference = CONDITION_PRIORITY[takeCardPriorityOf(left)] -
        CONDITION_PRIORITY[takeCardPriorityOf(right)]
      if (conditionDifference !== 0) return conditionDifference
      return compareAvailableCharge(left, right)
    })

  if (!candidates.length) {
    return {
      ok: false,
      reason: eligible.length ? 'SLOT_SNAPSHOT_STALE' : 'NO_TAKEABLE_CARD'
    }
  }

  const slot = candidates[0]
  const condition = takeCardConditionOf(slot)
  return {
    ok: true,
    slot,
    cardNo: cardNoOf(slot),
    condition,
    conditionTip: takeCardConditionTip(condition),
    candidateCount: candidates.length,
    voltage: Number.isFinite(finiteVoltageOf(slot)) ? finiteVoltageOf(slot) : null,
    batteryPercent: batteryPercentOf(slot)
  }
}
