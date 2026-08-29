export const TTS_PROMPT_TEMPLATES = Object.freeze({
  takeCardSuccess: '{slotNumber}号卡槽已打开，请取走您的卡',
  takeCardFailure: '取卡失败，请联系工作人员处理',
  adminCardOpened: '{slotNumber}号卡槽已开卡',
  adminWelcome: '欢迎{adminName}进入管理员模式'
})

const normalizeSlotNumber = (value) => {
  const slotNumber = Number(value)
  return Number.isInteger(slotNumber) && slotNumber > 0 ? String(slotNumber) : ''
}

const normalizeAdminName = (value) => String(value || '').trim() || '管理员'

export const buildTakeCardSuccessPrompt = (slotNumber, templates = TTS_PROMPT_TEMPLATES) => {
  const normalizedSlotNumber = normalizeSlotNumber(slotNumber)
  if (!normalizedSlotNumber) return ''
  return String(templates.takeCardSuccess || TTS_PROMPT_TEMPLATES.takeCardSuccess)
    .replace('{slotNumber}', normalizedSlotNumber)
}

export const buildTakeCardFailurePrompt = (templates = TTS_PROMPT_TEMPLATES) => {
  return String(templates.takeCardFailure || TTS_PROMPT_TEMPLATES.takeCardFailure)
}

export const buildAdminCardOpenedPrompt = (slotNumber, templates = TTS_PROMPT_TEMPLATES) => {
  const normalizedSlotNumber = normalizeSlotNumber(slotNumber)
  if (!normalizedSlotNumber) return ''
  return String(templates.adminCardOpened || TTS_PROMPT_TEMPLATES.adminCardOpened)
    .replace('{slotNumber}', normalizedSlotNumber)
}

export const buildAdminWelcomePrompt = (adminName, templates = TTS_PROMPT_TEMPLATES) => {
  return String(templates.adminWelcome || TTS_PROMPT_TEMPLATES.adminWelcome)
    .replace('{adminName}', normalizeAdminName(adminName))
}
