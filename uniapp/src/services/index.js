import { nativeBridge } from './nativeBridge.js'
import { mockService } from './mockService.js'
import {
  appState,
  persistSettings,
  persistEmployees,
  persistSession,
  persistRuntime,
  rebuildSlots
} from '@/state/appState.js'

const nativeOrMock = async (action, payload, fallback) => {
  if (nativeBridge.isAvailable()) {
    try {
      return await nativeBridge.request(action, payload)
    } catch (error) {
      const message = String(error?.message || '')
      if (!['NATIVE_BRIDGE_UNAVAILABLE', 'NATIVE_BRIDGE_TIMEOUT'].includes(message) && !message.includes('NOT_IMPLEMENTED')) {
        throw error
      }
    }
  }
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

export const services = {
  init() {
    nativeBridge.install()
    if (nativeBridge.isAvailable()) nativeBridge.request('app.ready', {}).catch(() => {})
  },

  async loadSettings() {
    const settings = await nativeOrMock('settings.load', {}, () => mockService.loadSettings())
    if (settings && Object.keys(settings).length) {
      Object.assign(appState.settings, settings)
      persistSettings()
      rebuildSlots()
    }
    return { ...appState.settings }
  },

  async saveSettings(settings) {
    const result = await nativeOrMock('settings.save', settings, () => mockService.saveSettings(settings))
    Object.assign(appState.settings, settings, result || {}, { initialized: true })
    persistSettings()
    rebuildSlots()
    return { ...appState.settings }
  },

  async getRuntime() {
    const runtime = await nativeOrMock('device.snapshot', {}, () => mockService.getRuntime())
    if (runtime) {
      Object.assign(appState.runtime, runtime)
      persistRuntime()
    }
    return JSON.parse(JSON.stringify(appState.runtime))
  },

  async getSerialStatus() {
    const serial = await nativeOrMock('serial.getStatus', {}, () => appState.runtime.serial)
    if (serial) {
      appState.runtime.serial = serial
      persistRuntime()
    }
    return { ...appState.runtime.serial }
  },

  async reconnectSerial() {
    const serial = await nativeOrMock('serial.reconnect', {}, () => ({ ...appState.runtime.serial }))
    if (serial) appState.runtime.serial = serial
    return serial
  },

  async setSerialPolling(enabled) {
    const serial = await nativeOrMock('serial.setPolling', { enabled: !!enabled }, () => ({
      ...appState.runtime.serial,
      polling: !!enabled,
      pollingEnabled: !!enabled,
      message: `浏览器模拟环境已${enabled ? '开启' : '关闭'}轮询`
    }))
    if (serial) appState.runtime.serial = serial
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
    persistSession()
    return session
  },

  async logout() {
    await nativeOrMock('auth.logout', {}, () => mockService.logout())
    appState.session = null
    persistSession()
    return true
  },

  getSlots: () => mockService.getSlots(),
  unlockDoor: (slotNumber) => nativeOrMock('cabinet.unlockDoor', { slotNumber }, () => mockService.unlockDoor(slotNumber)),
  unlockAllDoors: () => nativeOrMock('cabinet.unlockAll', {}, () => mockService.unlockAllDoors()),
  reactivateFaceEngine: () => nativeOrMock('face.reactivate', {}, async () => {
    appState.runtime.recognitionEngine = { state: 'ACTIVE', message: '模拟环境已刷新识别引擎', lastCode: null }
    persistRuntime()
    return { success: true }
  }),
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
    return mockService.runRecognition(type, onProgress)
  },
  cancelRecognition(type) {
    if (type === 'FINGERPRINT' && nativeBridge.isAvailable()) return nativeBridge.request('fingerprint.cancel', {}, 5000).catch(() => {})
    return Promise.resolve()
  },
  async registerBiometric(type, employee, onProgress) {
    if (type === 'FACE' && nativeBridge.isAvailable()) {
      onProgress?.('PREPARING')
      const result = await nativeBridge.request('face.enroll', employee, 60000)
      let target = appState.employees.find((item) => item.employeeId === employee.employeeId)
      if (!target) {
        target = { id: `EMP-${Date.now()}`, employeeId: employee.employeeId, employeeCode: employee.employeeId, employeeName: employee.employeeName, avatarUrl: '/static/avatars/employee-1.jpg', faceRegistered: false, fingerprintRegistered: false, enabled: true, deviceIds: [appState.settings.deviceId] }
        appState.employees.unshift(target)
      }
      target.employeeName = employee.employeeName
      target.faceRegistered = true
      persistEmployees()
      onProgress?.('SUCCESS')
      return result
    }
    if (type === 'FINGERPRINT' && nativeBridge.isAvailable()) {
      onProgress?.({ status: 'PREPARING', message: '正在打开系统指纹授权' })
      const result = await withFingerprintProgress('ENROLL', () => nativeBridge.request('fingerprint.enroll', employee, 60000), onProgress)
      let target = appState.employees.find((item) => item.employeeId === employee.employeeId)
      if (!target) {
        target = { id: `EMP-${Date.now()}`, employeeId: employee.employeeId, employeeCode: employee.employeeId, employeeName: employee.employeeName, avatarUrl: '/static/avatars/employee-1.jpg', faceRegistered: false, fingerprintRegistered: false, enabled: true, deviceIds: [appState.settings.deviceId] }
        appState.employees.unshift(target)
      }
      target.employeeName = employee.employeeName
      // This flag records system-authentication authorization only; Android never exposes a fingerprint template.
      target.fingerprintRegistered = true
      persistEmployees()
      onProgress?.('SUCCESS')
      return result
    }
    return mockService.registerBiometric(type, employee, onProgress)
  },
  searchEmployees: (query) => mockService.searchEmployees(query),
  deleteEmployee: (id) => mockService.deleteEmployee(id),
  getHistory: () => mockService.getHistory(),
  getUpgradeFiles: () => mockService.getUpgradeFiles(),
  startUpgrade: (fileId, onProgress) => mockService.startUpgrade(fileId, onProgress),
  savePassword: (role, password) => nativeOrMock('auth.changePassword', { role, password }, () => mockService.savePassword(role, password)),
  restartApp: () => nativeOrMock('app.restart', {}, async () => ({ success: true, message: '模拟环境不执行真实重启' }))
}

export { nativeBridge }
