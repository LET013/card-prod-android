import { SLOT_STATUS } from '@/constants/app.js'

export const defaultSettings = {
  initialized: false,
  cabinetNumber: '',
  serialPort: '/dev/ttyS5',
  baudRate: '57600',
  serialDataBits: 8,
  serialStopBits: 1,
  serialParity: 'NONE',
  serialPollingEnabled: true,
  serialResponseTimeoutMs: 1500,
  serialCommandGapMs: 200,
  serialPollingIntervalMs: 5000,
  slotStatusReportIntervalMs: 10000,
  pollingMode: '',
  singleGroupCount: 16,
  totalCount: 100,
  cardNumberMode: 'VISIBLE',
  cardParseMode: '可视卡号',

  deviceId: '',
  deviceCode: '',
  activationCode: '',
  backendTransport: 'MQTT',

  httpScheme: 'http',
  httpServerAddress: 'card-test.quyohui.com',
  httpPort: 80,
  httpBasePath: '',

  mqttScheme: 'tcp',
  mqttServerAddress: '119.146.88.108',
  mqttPort: 48419,

  tcpServerAddress: '',
  tcpPort: 9009,

  faceRecognitionThreshold: 0.8,
  faceSyncIncludeFlags: 3,
  startupDataSyncEnabled: true,
  cameraRotation: 90,
  fingerprintEnabled: false,
  fingerRecognitionThreshold: '',
  systemBiometricEnabled: true,

  // Unconfirmed/deprecated fields stay blank until the user approves deletion.
  singleGroupPollingEnabled: false,
  ignoreTokenFetch: '',
  codeValueType: '',
  cardSuccessResponseType: '',
  toastDisplay: '',
  boardUpgradeIntervalMs: '',
  faceRegistrationResponseEnabled: '',
  tcpDoorCommandResponseEnabled: '',
  secondaryDoorEnabled: '',
  usbCardReaderEnabled: '',
  startCharacter: '',
  endCharacter: '',
  serialExtra: '',
  baudExtra: ''
}

export const defaultRuntime = {
  deviceAuthorization: { state: 'PENDING', message: '等待后端授权状态' },
  recognitionEngine: { state: 'STOPPED', message: '识别引擎尚未启动' },
  serial: { state: 'DISCONNECTED', message: '串口未连接' },
  socket: { state: 'DISCONNECTED', message: '后端通信未连接' },
  http: { state: 'PENDING', message: '等待HTTP注册/激活' }
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

export function createSlots(total = 100, groupSize = 16) {
  return Array.from({ length: total }, (_, index) => {
    const slotNumber = index + 1
    const status = seededStatus[slotNumber] || SLOT_STATUS.EMPTY
    return {
      id: `slot-${slotNumber}`,
      deviceId: defaultSettings.deviceId,
      slotNumber,
      displayNumber: String(slotNumber).padStart(2, '0'),
      groupNumber: Math.ceil(slotNumber / groupSize),
      boardAddress: slotNumber,
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
  { id: 'EMP-001', employeeId: 'LDOGIK_7855422222', employeeCode: 'logjf125', employeeName: 'ruotji', avatarUrl: '/static/avatars/employee-1.jpg', faceRegistered: true, fingerprintRegistered: false, enabled: true, deviceIds: ['DEV-DEMO'] },
  { id: 'EMP-002', employeeId: 'EMP_20260002', employeeCode: 'xy202602', employeeName: '林清', avatarUrl: '/static/avatars/employee-2.jpg', faceRegistered: true, fingerprintRegistered: false, enabled: true, deviceIds: ['DEV-DEMO'] }
]

export const defaultHistory = [
  { id: 'H-001', type: '取卡', employeeName: 'ruotji', slotNumber: 4, result: '成功', createdAt: '2026-07-01 16:20:15' },
  { id: 'H-002', type: '管理员开门', employeeName: '运维人员', slotNumber: 11, result: '成功', createdAt: '2026-07-01 15:43:09' }
]

export const upgradeFiles = [
  { id: 'UP-1', fileName: '华星北斗小卡', versionName: 'HWDHFHJGH_15vjdkj', createdAt: '2026-06-19 10:42:23', fileType: 'FIRMWARE', fileSize: 2048576 },
  { id: 'UP-2', fileName: '华星北斗小卡', versionName: 'HWDHFHJGH_16stable', createdAt: '2026-06-28 15:21:10', fileType: 'FIRMWARE', fileSize: 2256896 }
]
