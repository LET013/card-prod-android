/**
 * Serial Simulator — 串口模拟（增强版）
 *
 * - 定时器随机变更卡槽状态，模拟物理串口轮询（原有功能）
 * - 解析 HEX 命令帧，按协议生成合理的模拟应答（新增）
 * - 通过事件总线推送 cabinet.slotsSnapshot / serial.dataReceived 事件
 *
 * 协议依据：docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md
 * 帧格式对齐：serial-demo.vue 的 buildCommandHex / frame / crc16Modbus
 */

import { emit } from './events.js'
import { SERIAL_POLL_INTERVAL, SERIAL_RANDOM_CHANGE_CHANCE, log } from './config.js'

const TAG = 'SerialSim'

// ── 协议常量 ──
const FRAME_START_H = 0xDD
const FRAME_START_L = 0xCC
const HOST_ADDRESS = 0xF0
const FIXED_PREFIX = [0x5A, 0xA5, 0x5A, 0xA5]

const FUNC_QUERY = 0x01      // 查询卡位状态
const FUNC_OPEN_DOOR = 0x51  // 开门控制
const FUNC_LED = 0x52         // LED 亮度控制
const FUNC_VERSION = 0x53     // 读取版本号
const FUNC_UPGRADE_EN = 0x80  // 升级使能（广播，无应答）
const FUNC_UPGRADE_DATA = 0x81 // 升级数据传输（广播，无应答）

const RESULT_OK = 0x11       // 命令正常执行
const RESULT_FAIL = 0x12     // 命令未执行

// ── 卡槽状态 ──
let timerId = null
let totalSlots = 12
const slotStates = new Map() // Map<slotNumber, { slotNumber, status, cardId, employeeId, ... }>
const cardChangeFlags = new Map() // Map<slotNumber, counter> — 同 real 协议，变更后 3 轮清 0

// ── 收发字节计数 & 日志 ──
let serialTxBytes = 0
let serialRxBytes = 0
const mockLogs = []
const MAX_MOCK_LOGS = 300

// ── CRC16 (Modbus, 多项式 0xA001, 初始值 0xFFFF) ──
function crc16Modbus(bytes, offset, length) {
  let crc = 0xFFFF
  for (let i = offset; i < offset + length; i++) {
    crc ^= bytes[i] & 0xFF
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc & 1) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1)
    }
  }
  return crc & 0xFFFF
}

// ── HEX 转换 ──
function hexToBytes(hex) {
  const compact = hex.replace(/[^0-9a-f]/ig, '')
  if (compact.length % 2 !== 0) throw new Error('HEX 长度必须为偶数')
  const bytes = []
  for (let i = 0; i < compact.length; i += 2) {
    bytes.push(parseInt(compact.substring(i, i + 2), 16))
  }
  return bytes
}

function bytesToHex(bytes) {
  return bytes.map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(' ')
}

function buildFrame(slaveAddress, functionCode, data) {
  const length = 3 + data.length // F0 + slave + func + data
  const bytes = [
    FRAME_START_H, FRAME_START_L,
    (length >> 8) & 0xFF, length & 0xFF,
    HOST_ADDRESS,
    slaveAddress & 0xFF,
    functionCode & 0xFF,
    ...data
  ]
  const crc = crc16Modbus(bytes, 0, bytes.length)
  bytes.push((crc >> 8) & 0xFF, crc & 0xFF)
  return bytes
}

// ── 卡槽状态管理 ──

function randomSlotState() {
  const states = ['EMPTY', 'EMPTY', 'EMPTY', 'OCCUPIED', 'OCCUPIED', 'CHARGING', 'ERROR']
  return states[Math.floor(Math.random() * states.length)]
}

function initSlots(count) {
  totalSlots = count || totalSlots
  for (let i = 1; i <= totalSlots; i++) {
    if (!slotStates.has(i)) {
      slotStates.set(i, newSlotData(i, randomSlotState()))
      cardChangeFlags.set(i, 0)
    }
  }
}

function newSlotData(slotNumber, status) {
  const cardId = status === 'OCCUPIED' || status === 'CHARGING'
    ? `CARD_${String(slotNumber).padStart(4, '0')}`
    : ''
  const employeeId = status === 'OCCUPIED' || status === 'CHARGING'
    ? `E${String(slotNumber).padStart(3, '0')}`
    : ''
  return {
    slotNumber,
    status,
    cardId,
    employeeId,
    voltage: randomVoltage(),
    current: randomCurrent(),
    updatedAt: Date.now()
  }
}

function randomVoltage() {
  return Math.floor((Math.random() * 6 + 94)) // 94-100 → 4.7V-5.0V
}

function randomCurrent() {
  return Math.floor((Math.random() * 30 + 5)) // 5-35 → 0.05A-0.35A
}

function ensureSlot(slotNumber) {
  if (!slotStates.has(slotNumber)) {
    slotStates.set(slotNumber, newSlotData(slotNumber, 'EMPTY'))
    cardChangeFlags.set(slotNumber, 0)
  }
  return slotStates.get(slotNumber)
}

// ── 帧解析 ──

/**
 * 解析 HEX 命令帧，返回 { slaveAddress, functionCode, data, crcValid }
 * 解析失败返回 null
 */
function parseFrame(hex) {
  try {
    const bytes = hexToBytes(hex)
    if (bytes.length < 8) return null // 最小帧: DD CC lenH lenL F0 slave func CRC(2)
    if (bytes[0] !== FRAME_START_H || bytes[1] !== FRAME_START_L) return null
    if (bytes[4] !== HOST_ADDRESS) return null

    const length = (bytes[2] << 8) | bytes[3]
    const expectedDataLen = length - 3 // 减去 host(1)+slave(1)+func(1)
    const frameEnd = 4 + length // 4(DD+CC+LenH+LenL) + length = 最后一个数据字节的位置+1
    const crcPos = frameEnd

    if (bytes.length < crcPos + 2) return null

    const frameCrc = (bytes[crcPos] << 8) | bytes[crcPos + 1]
    const calcCrc = crc16Modbus(bytes, 0, crcPos)

    const slaveAddress = bytes[5]
    const functionCode = bytes[6]
    const data = expectedDataLen > 0 ? bytes.slice(7, 7 + expectedDataLen) : []

    return {
      slaveAddress,
      functionCode,
      data,
      crcValid: frameCrc === calcCrc,
      length,
      raw: hex
    }
  } catch {
    return null
  }
}

// ── 协议应答构建 ──

/**
 * 功能码 0x01 — 查询卡槽状态
 * 数据域 22 字节: 工作状态(1) + 在位状态(1) + 卡状态(1) + 卡状态变更(1) + 卡号(15) + 故障码(1) + 电压(1) + 电流(1)
 */
function buildQueryResponse(slaveAddress) {
  const slot = ensureSlot(slaveAddress)

  let workState = 1  // 默认待机
  let cardState = 0  // 无卡
  const cardChangeFlag = Math.min(cardChangeFlags.get(slaveAddress) || 0, 1)
  let faultCode = 0

  switch (slot.status) {
    case 'EMPTY':
      workState = 1  // 待机
      cardState = 0  // 无卡
      break
    case 'OCCUPIED':
      workState = 1  // 待机
      cardState = 1  // 有卡
      break
    case 'CHARGING':
      workState = 2  // 充电
      cardState = 1  // 有卡
      break
    case 'ERROR':
      workState = 4  // 故障
      cardState = 1
      faultCode = 0x01 // 插卡错误
      break
    default:
      workState = 1
      cardState = 0
  }

  // 卡号 15 字节 ASCII，右对齐填充空格(0x20)
  const rawCardId = String(slot.cardId || '')
  const cardIdBytes = rawCardId.padStart(15, ' ').substring(0, 15).split('').map((c) => c.charCodeAt(0))
  while (cardIdBytes.length < 15) cardIdBytes.push(0x20)

  const inPositionState = 2   // 关门状态
  const voltage = slot.voltage || randomVoltage()
  const current = slot.current || randomCurrent()

  const data = [
    workState,          // 0: 工作状态
    inPositionState,    // 1: 在位状态
    cardState,          // 2: 卡状态
    cardChangeFlag,     // 3: 卡状态变更
    ...cardIdBytes,     // 4-18: 卡号 (15B)
    faultCode,          // 19: 故障码
    voltage,            // 20: 电压 (50mV/bit)
    current             // 21: 电流 (10mA/bit)
  ]

  const frame = buildFrame(slaveAddress, FUNC_QUERY, data)
  return {
    hex: bytesToHex(frame),
    parsed: { slaveAddress, functionCode: FUNC_QUERY, slotStatus: slot.status, cardId: slot.cardId }
  }
}

/**
 * 功能码 0x51 — 开门控制
 * 数据域: 5A A5 5A A5 controlFlag
 * controlFlag: 01=发卡开门, 02=管理员开门
 * 应答: 5A A5 5A A5 resultCode (11=成功, 12=失败)
 */
function buildOpenDoorResponse(slaveAddress, data) {
  if (data.length < 5) {
    return buildSimpleResponse(slaveAddress, FUNC_OPEN_DOOR, RESULT_FAIL)
  }

  const controlFlag = data[4]
  const validControls = [0x01, 0x02] // 01=发卡, 02=管理员
  const resultCode = validControls.includes(controlFlag) ? RESULT_OK : RESULT_FAIL

  if (resultCode === RESULT_OK) {
    const slot = ensureSlot(slaveAddress)
    if (slot.status === 'OCCUPIED' || slot.status === 'CHARGING') {
      // 开门 → 卡槽变空
      const oldStatus = slot.status
      slot.status = 'EMPTY'
      slot.cardId = ''
      slot.employeeId = ''
      slot.voltage = randomVoltage()
      slot.current = 5  // 空载小电流
      slot.updatedAt = Date.now()
      cardChangeFlags.set(slaveAddress, 3) // 变更标记，3 轮后清零

      log(TAG, `Slot ${slaveAddress}: openDoor → ${oldStatus} → ${slot.status}`)
      emit('cabinet.slotsSnapshot', getSnapshot())
    }
  }

  const responseData = [...FIXED_PREFIX, resultCode]
  const frame = buildFrame(slaveAddress, FUNC_OPEN_DOOR, responseData)
  return {
    hex: bytesToHex(frame),
    parsed: { slaveAddress, functionCode: FUNC_OPEN_DOOR, accepted: resultCode === RESULT_OK, controlFlag }
  }
}

/**
 * 功能码 0x52 — LED 亮度控制
 * 数据域: 5A A5 5A A5 dutyCycle (30-100)
 * 应答: 5A A5 5A A5 resultCode
 */
function buildLedResponse(slaveAddress, data) {
  if (data.length < 5) {
    return buildSimpleResponse(slaveAddress, FUNC_LED, RESULT_FAIL)
  }

  const dutyCycle = data[4]
  const resultCode = (dutyCycle >= 30 && dutyCycle <= 100) ? RESULT_OK : RESULT_FAIL

  if (resultCode === RESULT_OK) {
    log(TAG, `Slot ${slaveAddress}: LED brightness set to ${dutyCycle}%`)
  }

  const responseData = [...FIXED_PREFIX, resultCode]
  const frame = buildFrame(slaveAddress, FUNC_LED, responseData)
  return {
    hex: bytesToHex(frame),
    parsed: { slaveAddress, functionCode: FUNC_LED, accepted: resultCode === RESULT_OK, dutyCycle }
  }
}

/**
 * 功能码 0x53 — 读取版本号
 * 应答数据域: 5A A5 5A A5 + hwMajor + hwMinor + swMajor + swMinor
 */
function buildVersionResponse(slaveAddress) {
  const hwMajor = 1
  const hwMinor = 3
  const swMajor = 2
  const swMinor = 0

  const responseData = [...FIXED_PREFIX, hwMajor, hwMinor, swMajor, swMinor]
  const frame = buildFrame(slaveAddress, FUNC_VERSION, responseData)
  return {
    hex: bytesToHex(frame),
    parsed: { slaveAddress, functionCode: FUNC_VERSION, version: `HW${hwMajor}.${hwMinor} SW${swMajor}.${swMinor}` }
  }
}

/**
 * 构建通用简单应答 (5A A5 5A A5 resultCode)
 */
function buildSimpleResponse(slaveAddress, functionCode, resultCode) {
  const responseData = [...FIXED_PREFIX, resultCode]
  const frame = buildFrame(slaveAddress, functionCode, responseData)
  return {
    hex: bytesToHex(frame),
    parsed: { slaveAddress, functionCode, accepted: resultCode === RESULT_OK }
  }
}

// ── 命令处理入口 ──

/**
 * 处理收到的 HEX 命令帧，返回 { hex, parsed } 或 null（无需应答）
 */
export function handleCommand(hex) {
  // 记录 TX 字节数（HEX 字符串长度/2）
  serialTxBytes += (hex.length >> 1)

  const frame = parseFrame(hex)
  if (!frame) {
    pushMockLog('TX', `Raw: ${hex}`)
    pushMockLog('WARN', `Ignored unrecognized frame`)
    return null
  }

  if (!frame.crcValid) {
    pushMockLog('TX', `Fn=0x${frame.functionCode.toString(16)} Addr=${frame.slaveAddress} CRC=BAD`)
    return null
  }

  const { slaveAddress, functionCode, data } = frame
  pushMockLog('TX', `Fn=0x${functionCode.toString(16).toUpperCase()} Addr=${slaveAddress} Data=${bytesToHex(data)}`)

  let response = null

  switch (functionCode) {
    case FUNC_QUERY:
      response = buildQueryResponse(slaveAddress)
      break

    case FUNC_OPEN_DOOR:
      response = buildOpenDoorResponse(slaveAddress, data)
      break

    case FUNC_LED:
      response = buildLedResponse(slaveAddress, data)
      break

    case FUNC_VERSION:
      response = buildVersionResponse(slaveAddress)
      break

    case FUNC_UPGRADE_EN:
    case FUNC_UPGRADE_DATA:
      log(TAG, `Upgrade command ${functionCode.toString(16)} addr=${slaveAddress} (broadcast, no response)`)
      break

    default:
      pushMockLog('WARN', `Unknown function code: 0x${functionCode.toString(16)}`)
      break
  }

  if (response) {
    serialRxBytes += (response.hex.length >> 1)
    pushMockLog('RX', response.hex)
  }

  return response
}

// ── Mock 日志管理 ──

function pushMockLog(kind, text) {
  mockLogs.push({
    id: `log_${Date.now()}_${mockLogs.length}`,
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    kind,
    text
  })
  if (mockLogs.length > MAX_MOCK_LOGS) mockLogs.shift()
}

export function getMockLogs(count = 100) {
  const start = Math.max(0, mockLogs.length - count)
  return mockLogs.slice(start)
}

export function getSerialTxBytes() { return serialTxBytes }
export function getSerialRxBytes() { return serialRxBytes }

// ── 卡槽快照 ──

export function getSnapshot() {
  const slots = []
  for (let i = 1; i <= totalSlots; i++) {
    const state = slotStates.get(i)
    slots.push({
      slotNumber: i,
      displayNumber: String(i).padStart(2, '0'),
      status: state?.status || 'EMPTY',
      cardId: state?.cardId || '',
      employeeId: state?.employeeId || '',
      source: 'SERIAL_SIM',
      fresh: true,
      updatedAt: state?.updatedAt || Date.now()
    })
  }
  return { slots }
}

// ── 定时轮询 ──

function randomTick() {
  for (let i = 1; i <= totalSlots; i++) {
    // 递减卡状态变更计数器
    let flagCounter = cardChangeFlags.get(i) || 0
    if (flagCounter > 0) {
      cardChangeFlags.set(i, flagCounter - 1)
    }

    if (Math.random() < SERIAL_RANDOM_CHANGE_CHANCE) {
      const old = slotStates.get(i)
      const newStatus = randomSlotState()
      if (old && old.status !== newStatus) {
        slotStates.set(i, { ...old, status: newStatus, updatedAt: Date.now() })
        cardChangeFlags.set(i, 3) // 新变更，标记 3 轮
        log(TAG, `Slot ${i}: ${old.status} → ${newStatus}`)
      }
    }
  }
}

// ── 对外 API ──

export function start(count) {
  stop()
  initSlots(count)
  log(TAG, `Started with ${totalSlots} slots, interval ${SERIAL_POLL_INTERVAL}ms`)

  // 推送初始 STATUS 日志，确保 getSerialStatus() 识别为已连接
  pushMockLog('STATUS', `串口已连接 /dev/ttyS5 57600-8N1`)

  setTimeout(() => emit('cabinet.slotsSnapshot', getSnapshot()), 500)

  timerId = setInterval(() => {
    randomTick()
    emit('cabinet.slotsSnapshot', getSnapshot())
  }, SERIAL_POLL_INTERVAL)
}

export function stop() {
  if (timerId) {
    clearInterval(timerId)
    timerId = null
    log(TAG, 'Stopped')
  }
}

export function setSlotState(slotNumber, status, extra = {}) {
  const current = ensureSlot(slotNumber)
  const oldStatus = current.status
  const cardId = extra.cardId || (['OCCUPIED', 'CHARGING'].includes(status)
    ? `CARD_${String(slotNumber).padStart(4, '0')}` : '')

  slotStates.set(slotNumber, {
    ...current,
    status,
    cardId,
    employeeId: extra.employeeId || '',
    voltage: extra.voltage ?? randomVoltage(),
    current: extra.current ?? (status === 'EMPTY' ? 5 : randomCurrent()),
    updatedAt: Date.now()
  })

  if (oldStatus !== status) {
    cardChangeFlags.set(slotNumber, 3)
  }

  emit('cabinet.slotsSnapshot', getSnapshot())
}

export function updateTotalSlots(count) {
  totalSlots = count
  initSlots(count)
  emit('cabinet.slotsSnapshot', getSnapshot())
  log(TAG, `Total slots updated to ${count}`)
}
