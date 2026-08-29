export const TAKE_CARD_RESULT = Object.freeze({
  SUCCESS: 'SUCCESS',
  FAILURE: 'FAILURE',
  NO_CARD: 'NO_CARD'
})

export const LOW_BATTERY_THRESHOLD = 30

const formatSlotNumber = (value) => {
  const slotNumber = Number(value)
  return Number.isInteger(slotNumber) && slotNumber > 0
    ? String(slotNumber).padStart(2, '0')
    : ''
}

export function normalizeBatteryPercent(value) {
  const percent = Number(value)
  return Number.isFinite(percent) && percent >= 0 && percent <= 100
    ? Math.round(percent)
    : null
}

export function createTakeCardResultPresentation({
  outcome,
  slotNumber,
  batteryPercent,
  opened = false
} = {}) {
  if (outcome === TAKE_CARD_RESULT.NO_CARD) {
    return {
      status: 'NO_CARD',
      slotNumber: null,
      effect: '',
      message: '当前暂无可用工作卡，请联系管理员'
    }
  }

  const formattedSlotNumber = formatSlotNumber(slotNumber)
  const cabinet = formattedSlotNumber ? `卡柜${formattedSlotNumber}` : '卡柜'
  if (outcome === TAKE_CARD_RESULT.FAILURE) {
    return {
      status: 'TAKE_ERROR',
      slotNumber: formattedSlotNumber ? Number(slotNumber) : null,
      effect: formattedSlotNumber ? 'failure' : '',
      message: formattedSlotNumber
        ? `${cabinet}出卡失败，请稍后重试或联系管理员`
        : '出卡失败，请稍后重试或联系管理员'
    }
  }

  const percent = normalizeBatteryPercent(batteryPercent)
  if (opened) {
    return {
      status: 'SUCCESS',
      slotNumber: formattedSlotNumber ? Number(slotNumber) : null,
      effect: formattedSlotNumber ? 'success' : '',
      message: `${cabinet}已打开，请取走工卡`
    }
  }
  return {
    status: 'SUCCESS',
    slotNumber: formattedSlotNumber ? Number(slotNumber) : null,
    effect: formattedSlotNumber ? 'success' : '',
    message: percent != null && percent < LOW_BATTERY_THRESHOLD
      ? `${cabinet}取卡成功，当前电量${percent}%，建议使用后及时归还充电`
      : `${cabinet}取卡成功，请及时取走`
  }
}
