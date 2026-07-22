import { nativeBridge } from './nativeBridge.js'
import { mockService } from './mockService.js'
import {
  appState,
  replaceSettingsProjection,
  replaceRuntimeProjection,
  replaceSlotsProjection,
  replaceEmployeesProjection
} from '@/state/appState.js'

const MOCK_ENABLED = import.meta.env.DEV || import.meta.env.VITE_ENABLE_MOCK === 'true'

const nativeOrMock = async (action, payload, fallback, timeout = 2500) => {
  if (nativeBridge.isAvailable()) {
    try {
      return await nativeBridge.request(action, payload, timeout)
    } catch (error) {
      const message = String(error?.message || '')
      const bridgeUnavailable = ['NATIVE_BRIDGE_UNAVAILABLE', 'NATIVE_BRIDGE_TIMEOUT'].includes(message) || message.includes('NOT_IMPLEMENTED')
      if (!bridgeUnavailable || !MOCK_ENABLED) throw error
    }
  } else if (!MOCK_ENABLED) {
    throw new Error(`NATIVE_BRIDGE_REQUIRED: ${action}`)
  }
  return fallback()
}

const mockOnly = (action, fallback) => {
  if (!MOCK_ENABLED) return Promise.reject(new Error(`NATIVE_NOT_IMPLEMENTED: ${action}`))
  return fallback()
}

const fingerprintProgressStatus = (status) => ({
  STARTED: 'PREPARING',
  WAITING_FOR_TOUCH: 'COLLECTING',
  FAILED_ATTEMPT: 'RETRY',
  ERROR: 'ERROR',
  SUCCESS: 'SUCCESS'
}[status] || 'COLLECTING')

const withFingerprintProgress = async (operation, request, onProgress) => {
  const off = nativeBridge.on('fingerprint.statusChanged', (event) => {
    if (!event || (event.operation && event.operation !== operation)) return
    onProgress?.({ status: fingerprintProgressStatus(event.status), message: event.message || '' })
  })
  try {
    return await request()
  } finally {
    off?.()
  }
}

const normalizeNativeEmployees = (items = []) => (Array.isArray(items) ? items : []).map((item, index) => ({
  id: String(item.employeeId || item.id || ''),
  employeeId: String(item.employeeId || item.id || ''),
  employeeCode: String(item.employeeCode || ''),
  employeeName: String(item.employeeName || item.name || ''),
  cardNo: item.cardNo || '',
  department: item.department || '',
  position: item.position || '',
  avatarUrl: item.avatarUrl || item.faceImageUrl || '',
  faceRegistered: item.faceRegistered === true || item.faceRegistered === '1',
  fingerprintRegistered: item.fingerprintRegistered === true || item.fingerRegistered === '1',
  enabled: item.enabled !== false && item.status !== '1',
  deviceIds: Array.isArray(item.deviceIds) ? item.deviceIds : []
}))

const applyNativeEmployees = (items = []) => replaceEmployeesProjection(normalizeNativeEmployees(items))
const applyNativeSlots = (items = []) => replaceSlotsProjection(Array.isArray(items) ? items : [])

export const services = {
  init() {
    nativeBridge.install()
    nativeBridge.on('sync.completed', (event) => {
      const employees = event?.snapshot?.employees
      if (Array.isArray(employees)) applyNativeEmployees(employees)
      else services.searchEmployees('').catch(() => {})
    })
    nativeBridge.on('sync.employeeChanged', () => {
      services.searchEmployees('').catch(() => {})
    })
    if (nativeBridge.isAvailable()) nativeBridge.request('app.ready', {}).catch(() => {})
  },

  async loadSettings() {
    const settings = await nativeOrMock('settings.load', {}, () => mockService.loadSettings())
    return { ...replaceSettingsProjection(settings || {}) }
  },

  async saveSettings(settings) {
    const result = await nativeOrMock('settings.save', settings, () => mockService.saveSettings(settings))
    return { ...replaceSettingsProjection({ ...settings, ...(result || {}), initialized: true }) }
  },

  async getRuntime() {
    const runtime = await nativeOrMock('device.snapshot', {}, () => mockService.getRuntime())
    if (runtime && Array.isArray(runtime.slots)) applyNativeSlots(runtime.slots)
    return JSON.parse(JSON.stringify(replaceRuntimeProjection(runtime || {})))
  },

  async getSerialStatus() {
    const serial = await nativeOrMock('serial.getStatus', {}, () => appState.runtime.serial)
    appState.runtime.serial = serial || {}
    return { ...appState.runtime.serial }
  },

  async reconnectSerial() {
    const serial = await nativeOrMock('serial.reconnect', {}, () => ({ ...appState.runtime.serial }))
    appState.runtime.serial = serial || {}
    return serial
  },

  async setSerialPolling(enabled) {
    const serial = await nativeOrMock('serial.setPolling', { enabled: !!enabled }, () => ({
      ...appState.runtime.serial,
      polling: !!enabled,
      pollingEnabled: !!enabled,
      message: `浏览器模拟环境已${enabled ? '开启' : '关闭'}轮询`
    }))
    appState.runtime.serial = serial || {}
    return serial
  },

  listSerialPorts() {
    return nativeOrMock('serial.listPorts', {}, async () => ({ ports: [], count: 0, message: '浏览器模拟环境无法扫描 /dev 串口' }))
  },

  sendSerial(data, encoding = 'TEXT') {
    return nativeOrMock('serial.send', { data, encoding }, async () => ({
      success: true,
      bytes: String(data || '').length,
      message: '浏览器模拟环境未发送真实串口数据'
    }))
  },

  async login(password) {
    const session = await nativeOrMock('auth.login', { password }, () => mockService.login(password))
    appState.session = session
    return session
  },

  async logout() {
    await nativeOrMock('auth.logout', {}, () => mockService.logout())
    appState.session = null
    return true
  },

  async getSlots() {
    const result = await nativeOrMock('cabinet.getSlots', {}, () => mockService.getSlots().then((slots) => ({ slots })))
    const slots = Array.isArray(result) ? result : result?.slots
    if (!Array.isArray(slots)) throw new Error('INVALID_NATIVE_SLOT_RESPONSE')
    return JSON.parse(JSON.stringify(applyNativeSlots(slots)))
  },

  unlockDoor: (slotNumber) => nativeOrMock('cabinet.unlockDoor', { slotNumber }, () => mockService.unlockDoor(slotNumber), 5000),
  takeCard: (slotNumber) => nativeOrMock('cabinet.takeCard', { slotNumber }, () => mockService.unlockDoor(slotNumber), 5000),
  returnCard: (slotNumber) => nativeOrMock('cabinet.returnCard', { slotNumber }, () => mockService.unlockDoor(slotNumber), 5000),
  querySlot: (slotNumber) => nativeOrMock('cabinet.querySlot', { slotNumber }, async () => ({
    success: true, ack: true, slotNumber, message: '浏览器模拟环境未读取真实卡槽状态'
  }), 5000),
  readBoardVersion: (slotNumber) => nativeOrMock('cabinet.readVersion', { slotNumber }, async () => ({
    success: true, ack: true, slotNumber, message: '浏览器模拟环境未读取真实单板版本'
  }), 5000),
  unlockAllDoors: () => nativeOrMock('cabinet.unlockAll', {}, () => mockService.unlockAllDoors(), 70000),

  async reactivateFaceEngine() {
    const result = await nativeOrMock('face.reactivate', {}, async () => ({ success: true }))
    if (nativeBridge.isAvailable()) await services.getRuntime()
    return result
  },

  async runRecognition(type, onProgress) {
    if (type === 'FACE' && nativeBridge.isAvailable()) {
      onProgress?.('PREPARING')
      const result = await nativeBridge.request('face.verify', {}, 60000)
      onProgress?.('SUCCESS')
      return result
    }
    if (type === 'FINGERPRINT' && nativeBridge.isAvailable()) {
      const availability = await nativeBridge.request('fingerprint.getStatus', {}, 5000)
      if (!availability?.available) throw new Error(availability?.message || '本机指纹认证不可用')
      onProgress?.({ status: 'COLLECTING', message: '正在打开系统指纹验证' })
      const result = await withFingerprintProgress('VERIFY', () => nativeBridge.request('fingerprint.verify', {}, 60000), onProgress)
      onProgress?.('SUCCESS')
      return result
    }
    if (!MOCK_ENABLED) throw new Error(`NATIVE_BIOMETRIC_REQUIRED: ${type}`)
    return mockService.runRecognition(type, onProgress)
  },

  cancelRecognition(type) {
    if (type === 'FINGERPRINT' && nativeBridge.isAvailable()) {
      return nativeBridge.request('fingerprint.cancel', {}, 5000).catch(() => {})
    }
    return Promise.resolve()
  },

  async registerBiometric(type, employee, onProgress) {
    let result
    if (type === 'FACE' && nativeBridge.isAvailable()) {
      onProgress?.('PREPARING')
      result = await nativeBridge.request('face.enroll', employee, 60000)
      onProgress?.('SUCCESS')
    } else if (type === 'FINGERPRINT' && nativeBridge.isAvailable()) {
      onProgress?.({ status: 'PREPARING', message: '正在打开系统指纹授权' })
      result = await withFingerprintProgress('ENROLL', () => nativeBridge.request('fingerprint.enroll', employee, 60000), onProgress)
      onProgress?.('SUCCESS')
    } else {
      if (!MOCK_ENABLED) throw new Error(`NATIVE_BIOMETRIC_REQUIRED: ${type}`)
      result = await mockService.registerBiometric(type, employee, onProgress)
    }
    await services.searchEmployees('')
    return result
  },

  async searchEmployees(query) {
    const result = await nativeOrMock('employee.search', { query }, () => mockService.searchEmployees(query).then((employees) => ({ employees })))
    const employees = Array.isArray(result) ? result : result?.employees
    if (!Array.isArray(employees)) throw new Error('INVALID_NATIVE_EMPLOYEE_RESPONSE')
    return applyNativeEmployees(employees)
  },

  async deleteEmployee(id) {
    const result = await nativeOrMock('employee.delete', { id }, () => mockService.deleteEmployee(id))
    await services.searchEmployees('')
    return result
  },

  getHistory: () => mockOnly('history.get', () => mockService.getHistory()),
  getUpgradeFiles: () => mockOnly('upgrade.files', () => mockService.getUpgradeFiles()),
  startUpgrade: (fileId, onProgress) => mockOnly('upgrade.start', () => mockService.startUpgrade(fileId, onProgress)),
  savePassword: (role, password) => nativeOrMock('auth.changePassword', { role, password }, () => mockService.savePassword(role, password)),
  restartApp: () => nativeOrMock('app.restart', {}, async () => ({ success: true, message: '模拟环境不执行真实重启' }))
}

export { nativeBridge }
