import { reactive } from 'vue'
import { defaultSettings, defaultRuntime, createSlots, defaultEmployees, defaultHistory } from '@/mock/data.js'

const STORAGE_KEYS = {
  settings: 'card.settings.v2',
  runtime: 'card.runtime.v2',
  slots: 'card.slots.v2',
  employees: 'card.employees.v2',
  history: 'card.history.v2',
  session: 'card.session.v2'
}

const safeRead = (key, fallback) => {
  try {
    return uni.getStorageSync(key) || fallback
  } catch (error) {
    return fallback
  }
}

const initialSettings = { ...defaultSettings, ...safeRead(STORAGE_KEYS.settings, {}) }

const buildSlots = (settings, previousSlots = []) => {
  const baseSlots = createSlots(Number(settings.totalCount) || 100, Number(settings.singleGroupCount) || 10)
  if (!Array.isArray(previousSlots) || previousSlots.length === 0) return baseSlots
  const previousByNumber = new Map(previousSlots.map((slot) => [Number(slot.slotNumber), slot]))
  return baseSlots.map((base) => {
    const previous = previousByNumber.get(Number(base.slotNumber))
    if (!previous) return base
    return {
      ...base,
      ...previous,
      id: base.id,
      deviceId: base.deviceId,
      slotNumber: base.slotNumber,
      displayNumber: base.displayNumber,
      groupNumber: base.groupNumber,
      boardAddress: base.boardAddress
    }
  })
}

export const appState = reactive({
  settings: initialSettings,
  runtime: { ...defaultRuntime, ...safeRead(STORAGE_KEYS.runtime, {}) },
  slots: buildSlots(initialSettings, safeRead(STORAGE_KEYS.slots, [])),
  employees: safeRead(STORAGE_KEYS.employees, defaultEmployees),
  history: safeRead(STORAGE_KEYS.history, defaultHistory),
  session: null,
  bridgeReady: false,
  lastError: ''
})

export const persistSettings = () => uni.setStorageSync(STORAGE_KEYS.settings, { ...appState.settings })
export const persistSlots = () => uni.setStorageSync(STORAGE_KEYS.slots, JSON.parse(JSON.stringify(appState.slots)))
export const persistEmployees = () => uni.setStorageSync(STORAGE_KEYS.employees, JSON.parse(JSON.stringify(appState.employees)))
export const persistHistory = () => uni.setStorageSync(STORAGE_KEYS.history, JSON.parse(JSON.stringify(appState.history)))
export const persistRuntime = () => uni.setStorageSync(STORAGE_KEYS.runtime, JSON.parse(JSON.stringify(appState.runtime)))
export const persistSession = () => {
  // 管理会话只保存在当前 WebView 内存中，应用重启后必须重新验证密码。
  uni.removeStorageSync(STORAGE_KEYS.session)
}

export const rebuildSlots = () => {
  appState.slots = buildSlots(appState.settings, appState.slots)
  persistSlots()
}

export const applySlotStatus = (data) => {
  if (!data) return null
  const slot = appState.slots.find((item) => Number(item.slotNumber) === Number(data.slotNumber))
  if (!slot) return null
  Object.assign(slot, data)
  persistSlots()
  return slot
}
