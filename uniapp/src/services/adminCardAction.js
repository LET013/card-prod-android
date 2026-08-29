export const ADMIN_TAKEABLE_SLOT_STATES = Object.freeze([
  'OCCUPIED',
  'CHARGING',
  'FULL',
  'ILLEGAL_CARD',
  'CHARGING_FAULT',
  'COMMUNICATION_FAULT'
])

const TAKEABLE_STATES = new Set(ADMIN_TAKEABLE_SLOT_STATES)

export const slotNumberOf = (slot = {}) => Number(
  slot?.slotNumber ?? slot?.slotId ?? slot?.address
)

export const cardNoOf = (slot = {}) => String(
  slot?.cardNo || slot?.cardNumber || slot?.cardId || ''
).trim()

const slotStatusOf = (slot = {}) => String(slot?.status || '').trim().toUpperCase()

export const isAdminDoorPhysicallyOpen = (slot, expectedSlotNumber) => (
  slotNumberOf(slot) === Number(expectedSlotNumber) && Number(slot?.doorCode) === 1
)

export const isAdminCardPhysicallyRemoved = (slot, expectedSlotNumber) => (
  slotNumberOf(slot) === Number(expectedSlotNumber) &&
  slotStatusOf(slot) === 'EMPTY'
)

export function validateAdminCardSlot(slot, expectedSlotNumber) {
  const slotNumber = Number(expectedSlotNumber)
  if (!slot || slotNumberOf(slot) !== slotNumber) {
    return {
      ok: false,
      code: 'SLOT_STATE_UNAVAILABLE',
      message: slotNumber + '号卡槽实时状态不可用，未执行开门'
    }
  }
  const status = slotStatusOf(slot)
  if (status === 'EMPTY') {
    return {
      ok: false,
      code: 'NO_CARD_PRESENT',
      message: slotNumber + '号卡槽为空，无卡可取'
    }
  }
  if (!TAKEABLE_STATES.has(status)) {
    return {
      ok: false,
      code: 'SLOT_STATE_ABNORMAL',
      message: slotNumber + '号卡槽状态异常（' + (status || 'UNKNOWN') + '），未执行开门'
    }
  }
  const cardNo = cardNoOf(slot)
  if (!cardNo) {
    return {
      ok: false,
      code: 'CARD_IDENTITY_MISSING',
      message: slotNumber + '号卡槽未读取到卡号，未执行开门'
    }
  }
  return {
    ok: true,
    slot: { ...slot, slotNumber, status },
    slotNumber,
    status,
    cardNo
  }
}

export function planAdminEjectAll(slots = [], totalSlots = 0) {
  const normalizedTotal = Number(totalSlots)
  const slotMap = new Map(
    (Array.isArray(slots) ? slots : [])
      .map((slot) => [slotNumberOf(slot), slot])
      .filter(([slotNumber]) => Number.isInteger(slotNumber) && slotNumber > 0)
  )
  const addresses = Number.isInteger(normalizedTotal) && normalizedTotal > 0
    ? Array.from({ length: normalizedTotal }, (_, index) => index + 1)
    : [...slotMap.keys()].sort((left, right) => left - right)
  const targets = []
  const failures = []
  let emptyCount = 0

  addresses.forEach((slotNumber) => {
    const slot = slotMap.get(slotNumber)
    if (!slot) {
      failures.push({
        slotNumber,
        code: 'SLOT_STATE_UNAVAILABLE',
        message: slotNumber + '号卡槽实时状态不可用，未执行开门'
      })
      return
    }
    if (Number(slot?.cardCode) === 0 || slotStatusOf(slot) === 'EMPTY') {
      emptyCount += 1
      return
    }
    // 一键弹卡只跳过明确空卡槽，其他状态照常尝试开门。
    targets.push({
      slot: { ...slot, slotNumber },
      slotNumber,
      status: slotStatusOf(slot),
      cardNo: cardNoOf(slot)
    })
  })

  return {
    requestedCount: addresses.length,
    targetCount: targets.length,
    emptyCount,
    targets,
    failures
  }
}
