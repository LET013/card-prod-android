import { appState, persistSettings, persistEmployees, persistHistory, persistSession, rebuildSlots } from '@/state/appState.js'
import { PERMISSIONS, ROLE, SLOT_STATUS } from '@/constants/app.js'
import { upgradeFiles } from '@/mock/data.js'

const wait = (ms = 420) => new Promise((resolve) => setTimeout(resolve, ms))

const passwordRoleMap = {
  '111111': ROLE.SYSTEM_ADMIN,
  '222222': ROLE.OPS,
  '333333': ROLE.DEVELOPER
}

export const mockService = {
  wait,
  async loadSettings() {
    await wait(120)
    return { ...appState.settings }
  },
  async saveSettings(settings) {
    await wait()
    Object.assign(appState.settings, settings, { initialized: true })
    persistSettings()
    rebuildSlots()
    return { ...appState.settings }
  },
  async getRuntime() {
    await wait(180)
    return JSON.parse(JSON.stringify(appState.runtime))
  },
  async login(password) {
    await wait(300)
    const role = passwordRoleMap[String(password)]
    if (!role) throw new Error('密码错误')
    const session = { role, permissions: [...PERMISSIONS[role]], loginAt: Date.now() }
    appState.session = session
    persistSession()
    return session
  },
  async logout() {
    appState.session = null
    persistSession()
    return true
  },
  async getSlots() {
    await wait(160)
    return JSON.parse(JSON.stringify(appState.slots))
  },
  async unlockDoor(slotNumber) {
    await wait(520)
    const slot = appState.slots.find((item) => item.slotNumber === Number(slotNumber))
    if (!slot) throw new Error('卡门不存在')
    slot.doorStatus = '已开锁'
    slot.updatedAt = Date.now()
    appState.history.unshift({ id: `H-${Date.now()}`, type: '管理员开门', employeeName: appState.session?.role || '模拟用户', slotNumber: slot.slotNumber, result: '成功', createdAt: new Date().toLocaleString() })
    persistHistory()
    return { success: true, slotNumber: slot.slotNumber, message: `${slot.slotNumber}号卡门已开锁` }
  },
  async unlockAllDoors() {
    await wait(680)
    return { success: true, successCount: appState.slots.length, failedCount: 0, message: '全部卡门模拟开锁完成' }
  },
  async runRecognition(type, onProgress) {
    const stages = type === 'FACE'
      ? ['PREPARING', 'DETECTING', 'MATCHING']
      : ['PREPARING', 'COLLECTING', 'MATCHING']
    for (const stage of stages) {
      onProgress?.(stage)
      await wait(stage === 'MATCHING' ? 650 : 520)
    }
    const target = appState.slots.find((item) => [SLOT_STATUS.OCCUPIED, SLOT_STATUS.FULL, SLOT_STATUS.CHARGING].includes(item.status)) || appState.slots[3]
    await this.unlockDoor(target.slotNumber)
    onProgress?.('SUCCESS')
    return { success: true, recognitionType: type, employeeId: 'EMP-001', employeeName: 'ruotji', score: type === 'FACE' ? 0.92 : 1, slotNumber: target.slotNumber, message: '识别成功' }
  },
  async registerBiometric(type, employee, onProgress) {
    for (const stage of ['PREPARING', 'COLLECTING']) {
      onProgress?.(stage)
      await wait(620)
    }
    let target = appState.employees.find((item) => item.employeeId === employee.employeeId)
    if (!target) {
      target = { id: `EMP-${Date.now()}`, employeeId: employee.employeeId, employeeCode: employee.employeeId, employeeName: employee.employeeName, avatarUrl: '/static/avatars/employee-1.jpg', faceRegistered: false, fingerprintRegistered: false, enabled: true, deviceIds: [appState.settings.deviceId] }
      appState.employees.unshift(target)
    }
    if (type === 'FACE') target.faceRegistered = true
    else target.fingerprintRegistered = true
    persistEmployees()
    onProgress?.('SUCCESS')
    return { success: true, registrationType: type, employeeId: employee.employeeId, templateId: `TPL-${Date.now()}`, message: type === 'FACE' ? '人脸录入成功' : '指纹录入成功' }
  },
  async searchEmployees(query = '') {
    await wait(180)
    const value = String(query).trim().toLowerCase()
    if (!value) return JSON.parse(JSON.stringify(appState.employees))
    return JSON.parse(JSON.stringify(appState.employees.filter((item) => `${item.employeeName} ${item.employeeId} ${item.employeeCode}`.toLowerCase().includes(value))))
  },
  async deleteEmployee(id) {
    await wait(300)
    const index = appState.employees.findIndex((item) => item.id === id)
    if (index < 0) throw new Error('人员不存在')
    appState.employees.splice(index, 1)
    persistEmployees()
    return true
  },
  async getHistory() {
    await wait(180)
    return JSON.parse(JSON.stringify(appState.history))
  },
  async getUpgradeFiles() {
    await wait(220)
    return JSON.parse(JSON.stringify(upgradeFiles))
  },
  async startUpgrade(fileId, onProgress) {
    for (const value of [8, 24, 48, 73, 100]) {
      await wait(380)
      onProgress?.(value)
    }
    return { success: true, fileId, message: '模拟升级完成' }
  },
  async savePassword(role, newPassword) {
    await wait(260)
    if (appState.session?.role !== ROLE.SYSTEM_ADMIN) throw new Error('仅系统管理员可修改角色密码')
    if (!role || String(newPassword).length !== 6) throw new Error('请输入6位新密码')
    return { success: true, message: '密码已在模拟环境中更新' }
  }
}
