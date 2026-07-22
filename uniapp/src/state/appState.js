import { reactive } from 'vue'
import { defaultSettings, defaultRuntime, defaultHistory } from '@/mock/data.js'

const clone = (value) => JSON.parse(JSON.stringify(value))
const MOCK_ENABLED = import.meta.env.DEV || import.meta.env.VITE_ENABLE_MOCK === 'true'

/**
 * UI-layer memory projection only.
 *
 * Android DeviceStateStore/Repositories own settings after commit, runtime, slot, employee and
 * operation truth. Nothing in this module is restored from or persisted to H5 storage.
 */
export const appState = reactive({
  settings: { ...defaultSettings },
  runtime: clone(defaultRuntime),
  slots: [],
  employees: [],
  history: MOCK_ENABLED ? clone(defaultHistory) : [],
  session: null,
  bridgeReady: false,
  lastError: ''
})

export const replaceSettingsProjection = (settings = {}) => {
  Object.assign(appState.settings, settings || {})
  return appState.settings
}

export const replaceRuntimeProjection = (runtime = {}) => {
  appState.runtime = clone(runtime || {})
  return appState.runtime
}

export const replaceSlotsProjection = (items = []) => {
  const slots = Array.isArray(items) ? clone(items) : []
  appState.slots.splice(0, appState.slots.length, ...slots)
  return appState.slots
}

export const replaceEmployeesProjection = (items = []) => {
  appState.employees = Array.isArray(items) ? clone(items) : []
  return appState.employees
}

export const replaceHistoryProjection = (items = []) => {
  appState.history = Array.isArray(items) ? clone(items) : []
  return appState.history
}

export const clearNativeProjection = () => {
  appState.runtime = clone(defaultRuntime)
  appState.slots.splice(0, appState.slots.length)
  appState.employees = []
  appState.bridgeReady = false
}

export const applySlotStatus = (data) => {
  if (!data) return null
  const slot = appState.slots.find((item) => Number(item.slotNumber) === Number(data.slotNumber))
  if (!slot) return null
  Object.assign(slot, clone(data))
  return slot
}
