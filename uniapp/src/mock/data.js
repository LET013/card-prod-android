import { SLOT_STATUS } from '@/constants/app.js'

export const defaultSettings = {
  initialized: false,
  cabinetNumber: '8652566615555520',
  serialPort: '/dev/ttyS5',
  serialExtra: '',
  baudRate: '57600',
  baudExtra: '',
  singleGroupCount: 10,
  totalCount: 100,
  cardParseMode: '转可见符',
  singleGroupPollingEnabled: true,
  serialPollingEnabled: true,
  serialResponseTimeoutMs: 1500,
  serialCommandGapMs: 200,
  serialPollingIntervalMs: 1200,
  slotStatusReportIntervalMs: 10000,
  deviceId: '336633',
  deviceCode: '',
  machineId: '',
  activationCode: '123123',
  apiBaseUrl: 'http://card-test.quyohui.com',
  serverAddress: 'http://card-test.quyohui.com',
  backendTransport: 'MQTT',
  tcpPort: 9009,
  mqttPort: 48419,
  mqttBrokerUrl: 'tcp://119.146.88.108:48419',
  mqttUsername: '',
  mqttCommandTopic: '',
  mqttResponseTopic: '',
  mqttEventTopic: '',
  httpPort: 80,
  faceRecognitionThreshold: 0.7,
  faceSyncIncludeFlags: 3,
  startupDataSyncEnabled: true,
  cameraRotation: 90,
  codeValueType: '字符',
  cardSuccessResponseType: '短链接',
  toastDisplay: '显示',
  boardUpgradeIntervalMs: 800,
  ignoreTokenFetch: false,
  faceRegistrationResponseEnabled: false,
  tcpDoorCommandResponseEnabled: true,
  secondaryDoorEnabled: false,
  usbCardReaderEnabled: false,
  startCharacter: '',
  endCharacter: ''
}

export const defaultRuntime = {
  deviceAuthorization: { state: 'AUTHORIZED', message: '已授权' },
  recognitionEngine: { state: 'CODE_IN_USE', message: '该激活码已被其他设备使用' },
  serial: { state: 'DISCONNECTED', message: '原生串口待接入' },
  socket: { state: 'DISCONNECTED', message: '原生长连接待接入' },
  http: { state: 'DISABLED', message: '当前阶段未接入HTTP' }
}

const seededStatus = {
  1: SLOT_STATUS.OCCUPIED,
  11: SLOT_STATUS.CHARGING_FAULT,
  21: SLOT_STATUS.CHARGING,
  22: SLOT_STATUS.CHARGING,
  37: SLOT_STATUS.FULL,
  44: SLOT_STATUS.COMMUNICATION_FAULT,
  51: SLOT_STATUS.ILLEGAL_CARD
}

export function createSlots(total = 100, groupSize = 10) {
  return Array.from({ length: total }, (_, index) => {
    const slotNumber = index + 1
    const status = seededStatus[slotNumber] || SLOT_STATUS.EMPTY
    return {
      id: `slot-${slotNumber}`,
      deviceId: defaultSettings.deviceId,
      slotNumber,
      displayNumber: String(slotNumber).padStart(2, '0'),
      groupNumber: Math.ceil(slotNumber / groupSize),
      boardAddress: `BOARD-${String(Math.ceil(slotNumber / groupSize)).padStart(2, '0')}`,
      cardNumber: status === SLOT_STATUS.EMPTY ? '' : `CARD${String(100000 + slotNumber)}`,
      status,
      presenceStatus: status === SLOT_STATUS.EMPTY ? '无卡' : '有卡',
      workStatus: status === SLOT_STATUS.CHARGING ? '充电中' : status === SLOT_STATUS.FULL ? '充电结束' : '待机',
      chargingStatus: status,
      doorStatus: '已锁定',
      voltage: status === SLOT_STATUS.EMPTY ? null : Number((4.05 + (slotNumber % 7) * 0.03).toFixed(2)),
      current: status === SLOT_STATUS.CHARGING ? Number((0.72 + (slotNumber % 4) * 0.08).toFixed(2)) : 0,
      faultCode: [SLOT_STATUS.CHARGING_FAULT, SLOT_STATUS.COMMUNICATION_FAULT].includes(status) ? `E${slotNumber}` : '',
      faultMessage: status === SLOT_STATUS.CHARGING_FAULT ? '充电模块异常' : status === SLOT_STATUS.COMMUNICATION_FAULT ? '单板通信超时' : '',
      updatedAt: Date.now()
    }
  })
}

export const defaultEmployees = [
  { id: 'EMP-001', employeeId: 'LDOGIK_7855422222', employeeCode: 'logjf125', employeeName: 'ruotji', avatarUrl: '/static/avatars/employee-1.jpg', faceRegistered: true, fingerprintRegistered: true, enabled: true, deviceIds: ['336633'] },
  { id: 'EMP-002', employeeId: 'EMP_20260002', employeeCode: 'xy202602', employeeName: '林清', avatarUrl: '/static/avatars/employee-2.jpg', faceRegistered: true, fingerprintRegistered: false, enabled: true, deviceIds: ['336633'] },
  { id: 'EMP-003', employeeId: 'EMP_20260003', employeeCode: 'xy202603', employeeName: '周宁', avatarUrl: '/static/avatars/employee-3.jpg', faceRegistered: false, fingerprintRegistered: true, enabled: true, deviceIds: ['336633'] },
  { id: 'EMP-004', employeeId: 'EMP_20260004', employeeCode: 'xy202604', employeeName: '沈月', avatarUrl: '/static/avatars/employee-4.jpg', faceRegistered: true, fingerprintRegistered: true, enabled: true, deviceIds: ['336633'] }
]

export const defaultHistory = [
  { id: 'H-001', type: '取卡', employeeName: 'ruotji', slotNumber: 4, result: '成功', createdAt: '2026-07-01 16:20:15' },
  { id: 'H-002', type: '管理员开门', employeeName: '运维人员', slotNumber: 11, result: '成功', createdAt: '2026-07-01 15:43:09' },
  { id: 'H-003', type: '还卡', employeeName: '林清', slotNumber: 21, result: '成功', createdAt: '2026-07-01 14:18:42' },
  { id: 'H-004', type: '取卡', employeeName: '周宁', slotNumber: 37, result: '识别失败', createdAt: '2026-07-01 10:02:31' }
]

export const upgradeFiles = [
  { id: 'UP-1', fileName: '华星北斗小卡', versionName: 'HWDHFHJGH_15vjdkj', createdAt: '2026-06-19 10:42:23', fileType: 'FIRMWARE', fileSize: 2048576 },
  { id: 'UP-2', fileName: '华星北斗小卡', versionName: 'HWDHFHJGH_16stable', createdAt: '2026-06-28 15:21:10', fileType: 'FIRMWARE', fileSize: 2256896 }
]
