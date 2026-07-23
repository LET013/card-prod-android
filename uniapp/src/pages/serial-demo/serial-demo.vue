<template>
  <view class="page-root serial-demo-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="serial-demo-wrap">
        <view class="top-bar">
          <view>
            <text class="demo-title">串口单机调试台</text>
            <text class="demo-subtitle">{{ bridgeLabel }} · {{ serialStatus.port || '-' }} @ {{ serialStatus.baudRate || '-' }}</text>
          </view>
          <view class="status-pill" :class="statusClass">{{ serialStatus.state || 'UNKNOWN' }}</view>
        </view>

        <view class="status-line">
          <text>{{ serialStatus.message || '等待串口状态' }}</text>
          <text>TX {{ serialStatus.sentBytes || 0 }} bytes</text>
          <text>RX {{ serialStatus.receivedBytes || 0 }} bytes</text>
        </view>
        <view class="backend-line" :class="backendStatusClass">
          <view class="backend-copy">
            <text class="backend-title">后端通信状态</text>
            <text class="backend-subtitle">{{ backendMode }} · {{ backendTarget }}</text>
          </view>
          <view class="backend-state">
            <text>{{ backendStatus.state || 'UNKNOWN' }}</text>
            <text>{{ backendStatus.message || '等待后端通信状态' }}</text>
          </view>
        </view>
        <view v-if="serialStatus.permissionHint" class="hint-line">{{ serialStatus.permissionHint }}</view>

        <view class="console-layout">
          <view class="control-panel">
            <view class="section-title">串口配置</view>
            <view class="config-grid">
              <view class="config-field path">
                <text>端口</text>
                <input v-model="portInput" />
              </view>
              <view class="config-field">
                <text>波特率</text>
                <input v-model="baudInput" type="number" />
              </view>
            </view>
            <view class="tool-row">
              <button class="tiny-button blue" :disabled="scanning" @click="scanSerialPorts">{{ scanning ? '扫描中' : '扫描串口' }}</button>
              <button class="tiny-button blue" :disabled="reconnecting" @click="applySerialConfig">应用并重连</button>
              <button class="tiny-button" :disabled="reconnecting" @click="toggleSerialPolling">{{ serialStatus.pollingEnabled ? '关闭轮询' : '开启轮询' }}</button>
            </view>
            <view v-if="availablePorts.length" class="port-list">
              <view v-for="item in availablePorts" :key="item.path" class="port-item" @click="selectPort(item.path)">
                <text>{{ item.path }}</text>
                <b :class="{ok:item.readable && item.writable}">{{ item.readable && item.writable ? 'RW' : `R${item.readable ? 1 : 0}/W${item.writable ? 1 : 0}` }}</b>
              </view>
            </view>

            <view class="section-title">卡槽控制</view>
            <view class="slot-row">
              <text>卡槽号</text>
              <input v-model="slotNumber" type="number" />
            </view>
            <view class="action-grid">
              <button class="action-button primary" :disabled="busy" @click="takeCard">取指定卡</button>
              <button class="action-button" :disabled="busy" @click="returnCard">还卡开门</button>
              <button class="action-button" :disabled="busy" @click="readSlotStatus">读取卡状态</button>
              <button class="action-button" :disabled="busy" @click="readBoardVersion">读取版本</button>
              <button class="action-button danger wide" :disabled="busy" @click="confirmEjectAll">一键弹出所有卡</button>
            </view>

            <view class="section-title manual-title">手动命令</view>
            <view class="mode-row">
              <view class="mode-toggle">
                <view :class="{active: encoding==='HEX'}" @click="encoding='HEX'">HEX</view>
                <view :class="{active: encoding==='TEXT'}" @click="encoding='TEXT'">TEXT</view>
              </view>
              <button class="tiny-button" @click="clearCommand">清空</button>
            </view>
            <textarea class="command-input" v-model="command" :placeholder="encoding==='HEX' ? '输入或由上方按钮生成 HEX 命令' : '输入文本命令'"></textarea>
            <button class="send-button" :disabled="busy" @click="sendManualCommand">写入串口</button>

            <view class="tool-row">
              <button class="tiny-button blue" :disabled="reconnecting" @click="reconnectSerial">{{ reconnecting ? '重连中' : '重连串口' }}</button>
              <button class="tiny-button blue" @click="refreshStatus">刷新状态</button>
              <button class="tiny-button" @click="clearLogs">清空日志</button>
            </view>
          </view>

          <view class="terminal-panel">
            <view class="terminal-head">
              <text>串口收发日志</text>
              <text>{{ logs.length }} lines</text>
            </view>
            <scroll-view class="terminal" scroll-y :scroll-top="terminalScrollTop">
              <view v-if="logs.length===0" class="empty-log">等待操作或串口数据...</view>
              <view v-for="item in logs" :key="item.id" class="terminal-row" :class="item.kind.toLowerCase()">
                <text class="terminal-time">{{ item.time }}</text>
                <text class="terminal-kind">{{ item.kind }}</text>
                <text class="terminal-text">{{ item.text }}</text>
              </view>
            </scroll-view>
          </view>
        </view>
      </view>
      <view class="back-wrap"><BackButton @click="back" /></view>
    </scroll-view>
  </view>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import BackButton from '@/components/BackButton.vue'
import { appState, applySlotStatus } from '@/state/appState.js'
import { ROLE_META } from '@/constants/app.js'
import { nativeBridge, services } from '@/services/index.js'

const FIXED_PREFIX = [0x5A, 0xA5, 0x5A, 0xA5]
const MASTER_ADDRESS = 0xF0
const FUNCTION_QUERY = 0x01
const FUNCTION_OPEN_DOOR = 0x51
const FUNCTION_VERSION = 0x53

const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '')
const bridgeLabel = computed(() => nativeBridge.isAvailable() ? 'Android Bridge 已连接' : '浏览器 Mock 模式')
const serialStatus = reactive({ ...appState.runtime.serial })
const backendStatus = reactive({ ...appState.runtime.socket })
const statusClass = computed(() => {
  if (serialStatus.state === 'CONNECTED') return 'connected'
  if (serialStatus.state === 'CONNECTING') return 'connecting'
  return 'disconnected'
})
const backendStatusClass = computed(() => {
  if (backendStatus.state === 'AUTHENTICATED') return 'connected'
  if (['CONNECTING', 'TRANSPORT_CONNECTED', 'SUBSCRIBED', 'LOGIN_SENT'].includes(backendStatus.state)) return 'connecting'
  return 'disconnected'
})
const backendMode = computed(() => String(appState.settings.backendTransport || 'MQTT').toUpperCase())
const backendTarget = computed(() => {
  if (backendMode.value === 'TCP') return `${appState.settings.serverAddress || '-'}:${appState.settings.tcpPort || '-'}`
  return appState.settings.mqttBrokerUrl || `${appState.settings.serverAddress || '-'}:${appState.settings.mqttPort || '-'}`
})
const totalSlots = computed(() => Number(serialStatus.totalSlots || appState.settings.totalCount || 100))
const serialAddressLimit = computed(() => Number(serialStatus.pollingAddressLimit || serialStatus.singleGroupCount || appState.settings.singleGroupCount || totalSlots.value))

const slotNumber = ref('1')
const portInput = ref(appState.settings.serialPort || '')
const baudInput = ref(String(appState.settings.baudRate || '57600'))
const command = ref('')
const encoding = ref('HEX')
const logs = ref([])
const terminalScrollTop = ref(0)
const busy = ref(false)
const reconnecting = ref(false)
const scanning = ref(false)
const availablePorts = ref([])
const unsubs = []

onMounted(async () => {
  addLog('INFO', 'serial demo ready')
  await refreshStatus()
  unsubs.push(nativeBridge.on('serial.statusChanged', (event) => {
    if (!event) return
    Object.assign(serialStatus, event)
    addLog('STATUS', `${event.state || ''} ${event.message || ''}`.trim())
    if (event.permissionHint) addLog('HINT', event.permissionHint)
  }))
  unsubs.push(nativeBridge.on('socket.statusChanged', (event) => {
    if (!event) return
    Object.assign(backendStatus, event)
    addLog('BACKEND', `${event.state || ''} ${event.message || ''}`.trim())
  }))
  unsubs.push(nativeBridge.on('sync.statusChanged', (event) => {
    if (!event) return
    addLog('SYNC', `${event.state || ''} ${event.message || ''}`.trim())
  }))
  unsubs.push(nativeBridge.on('sync.completed', (event) => {
    if (!event) return
    addLog('SYNC', `completed employees=${event.employeeCount || 0} faces=${event.faceCount || 0} fingers=${event.fingerCount || 0}`)
  }))
  unsubs.push(nativeBridge.on('serial.dataReceived', (event) => addSerialEvent(event)))
  unsubs.push(nativeBridge.on('cabinet.slotStatus', (event) => {
    if (!event) return
    applySlotStatus(event)
    const detail = [
      `slot=${event.slotNumber}`,
      `status=${event.status || '-'}`,
      `work=${event.workCode ?? '-'}(${event.workStatus || '-'})`,
      `card=${event.cardCode ?? '-'}(${event.presenceStatus || '-'})`,
      `door=${event.doorCode ?? '-'}(${event.doorStatus || '-'})`,
      `fault=${toHexByte(event.faultMask || 0)}`,
      event.cardNumber ? `cardNo=${event.cardNumber}` : '',
      event.faultMessage || ''
    ].filter(Boolean).join(' ')
    addLog('SLOT', detail)
  }))
})

onBeforeUnmount(() => unsubs.forEach((off) => off?.()))

const takeCard = () => runAction(() => openSlotWithNative('取指定卡', currentSlot(), services.takeCard))
const returnCard = () => runAction(() => openSlotWithNative('还卡开门', currentSlot(), services.returnCard))
const readSlotStatus = () => runAction(() => protocolCommandWithNative('读取卡状态', currentSlot(), FUNCTION_QUERY, [0x01], services.querySlot))
const readBoardVersion = () => runAction(() => protocolCommandWithNative('读取版本', currentSlot(), FUNCTION_VERSION, [0x01], services.readBoardVersion))

const scanSerialPorts = async () => {
  scanning.value = true
  try {
    addLog('CMD', 'scan /dev serial ports')
    const result = await services.listSerialPorts()
    availablePorts.value = Array.isArray(result?.ports) ? result.ports : []
    addLog('INFO', result?.message || `found ${availablePorts.value.length} ports`)
    if (availablePorts.value.length === 1) selectPort(availablePorts.value[0].path)
  } catch (error) {
    addLog('ERROR', `扫描串口失败 ${error.message || '未知错误'}`)
  } finally {
    scanning.value = false
  }
}

const selectPort = (path) => {
  portInput.value = path
  addLog('INFO', `select port ${path}`)
}

const applySerialConfig = async () => {
  const port = portInput.value.trim()
  const baudRate = baudInput.value.trim() || '57600'
  if (!port) {
    uni.showToast({ title: '请输入串口端口', icon: 'none' })
    return
  }
  reconnecting.value = true
  try {
    addLog('CMD', `apply serial config port=${port} baud=${baudRate} polling=off`)
    await services.saveSettings({ ...appState.settings, serialPort: port, baudRate, serialPollingEnabled: false })
    Object.assign(serialStatus, await services.getSerialStatus())
    addLog('STATUS', `${serialStatus.state || ''} ${serialStatus.message || ''}`.trim())
    if (serialStatus.permissionHint) addLog('HINT', serialStatus.permissionHint)
  } catch (error) {
    addLog('ERROR', `应用串口配置失败 ${error.message || '未知错误'}`)
  } finally {
    reconnecting.value = false
  }
}

const toggleSerialPolling = async () => {
  reconnecting.value = true
  try {
    const enabled = !serialStatus.pollingEnabled
    addLog('CMD', `${enabled ? 'enable' : 'disable'} serial polling`)
    await services.saveSettings({ ...appState.settings, serialPollingEnabled: enabled })
    Object.assign(serialStatus, await services.setSerialPolling(enabled))
    addLog('STATUS', `${serialStatus.state || ''} ${serialStatus.message || ''}`.trim())
  } catch (error) {
    addLog('ERROR', `切换轮询失败 ${error.message || '未知错误'}`)
  } finally {
    reconnecting.value = false
  }
}

const confirmEjectAll = () => {
  uni.showModal({
    title: '一键弹出所有卡',
    content: `将按单组数量向 1-${serialAddressLimit.value} 号单板地址连续写入管理员开门命令，配置总卡槽 ${totalSlots.value}，确认执行？`,
    success: (result) => { if (result.confirm) ejectAllCards() }
  })
}

const ejectAllCards = async () => {
  const count = serialAddressLimit.value
  busy.value = true
  addLog('CMD', `一键弹出所有卡 start boardAddresses=${count} totalSlots=${totalSlots.value}`)
  try {
    const result = await services.unlockAllDoors()
    addLog('DONE', `一键弹出所有卡 done success=${result.successCount || 0} failed=${result.failedCount || 0}`)
    await refreshStatus()
  } catch (error) {
    addLog('ERROR', `一键弹出中断 ${error.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

const openSlotWithNative = async (label, address, action) => {
  const control = label.includes('取') ? 0x01 : 0x02
  const boardAddress = boardAddressForSlot(address)
  const hex = buildCommandHex(boardAddress, FUNCTION_OPEN_DOOR, [control])
  encoding.value = 'HEX'
  command.value = hex
  addLog('CMD', `${label} slot=${address} board=${boardAddress} -> ${formatHex(hex)}`)
  const result = await action(address)
  addLog('WRITE', `${label} slot=${address} ${result.ack ? 'ack' : 'sent'} bytes=${result.bytes || 0} hex=${result.hex || normalizeHex(hex)}`)
  await refreshStatus()
}

const protocolCommandWithNative = async (label, address, functionCode, data, action) => {
  const boardAddress = boardAddressForSlot(address)
  const hex = buildCommandHex(boardAddress, functionCode, data)
  encoding.value = 'HEX'
  command.value = hex
  addLog('CMD', `${label} slot=${address} board=${boardAddress} -> ${formatHex(hex)}`)
  const result = await action(address)
  addLog('WRITE', `${label} slot=${address} ${result.ack ? 'ack' : 'sent'} bytes=${result.bytes || 0} hex=${result.hex || normalizeHex(hex)}`)
  await refreshStatus()
}

const sendProtocolCommand = async (label, address, functionCode, data) => {
  const hex = buildCommandHex(address, functionCode, data)
  encoding.value = 'HEX'
  command.value = hex
  const beforeRx = Number(serialStatus.receivedBytes || 0)
  await writeHex(`${label} slot=${address}`, hex)
  await waitForSerialResponse(`${label} slot=${address}`, beforeRx)
  await refreshStatus()
}

const sendManualCommand = async () => {
  const input = command.value.trim()
  if (!input) {
    uni.showToast({ title: '请输入命令', icon: 'none' })
    return
  }
  busy.value = true
  try {
    if (encoding.value === 'HEX') await writeHex('手动 HEX', input)
    else await writeText('手动 TEXT', input)
    await refreshStatus()
  } catch (error) {
    // writeHex/writeText already records the concrete failure in the terminal.
  } finally {
    busy.value = false
  }
}

const runAction = async (action) => {
  if (busy.value) return
  busy.value = true
  try {
    await action()
  } catch (error) {
    addLog('ERROR', error.message || '操作失败')
  } finally {
    busy.value = false
  }
}

const writeHex = async (label, value) => {
  const payload = normalizeHex(value)
  addLog('CMD', `${label} -> ${formatHex(payload)}`)
  try {
    const result = await services.sendSerial(payload, 'HEX')
    addLog('WRITE', `${label} ok bytes=${result.bytes || 0} hex=${result.hex || payload}`)
    if (!nativeBridge.isAvailable()) addLog('TX', payload)
    return result
  } catch (error) {
    addLog('ERROR', `${label} failed ${error.message || '未知错误'}`)
    throw error
  }
}

const writeText = async (label, value) => {
  addLog('CMD', `${label} -> ${value}`)
  try {
    const result = await services.sendSerial(value, 'TEXT')
    addLog('WRITE', `${label} ok bytes=${result.bytes || 0}`)
    if (!nativeBridge.isAvailable()) addLog('TX', value)
    return result
  } catch (error) {
    addLog('ERROR', `${label} failed ${error.message || '未知错误'}`)
    throw error
  }
}

const refreshStatus = async () => {
  try {
    const runtime = await services.getRuntime()
    Object.assign(serialStatus, runtime?.serial || await services.getSerialStatus())
    Object.assign(backendStatus, runtime?.socket || {})
    if (!portInput.value) portInput.value = serialStatus.port || appState.settings.serialPort || ''
    if (!baudInput.value) baudInput.value = String(serialStatus.baudRate || appState.settings.baudRate || '57600')
    addLog('STATUS', `${serialStatus.state || ''} ${serialStatus.message || ''}`.trim())
    addLog('BACKEND', `${backendStatus.state || ''} ${backendStatus.message || ''}`.trim())
    if (serialStatus.permissionHint) addLog('HINT', serialStatus.permissionHint)
  } catch (error) {
    addLog('ERROR', `读取状态失败 ${error.message || '未知错误'}`)
  }
}

const reconnectSerial = async () => {
  reconnecting.value = true
  try {
    addLog('CMD', 'reconnect serial')
    Object.assign(serialStatus, await services.reconnectSerial())
    addLog('STATUS', `${serialStatus.state || ''} ${serialStatus.message || ''}`.trim())
  } catch (error) {
    addLog('ERROR', `重连失败 ${error.message || '未知错误'}`)
  } finally {
    reconnecting.value = false
  }
}

const addSerialEvent = (event) => {
  if (!event) return
  if (event.type === 'serialTx') {
    addLog('TX', event.data?.hex || JSON.stringify(event.data || {}))
  } else if (event.type === 'serialRxRaw') {
    addLog('RX', event.hex || event.text || '')
  } else if (event.type === 'serialFrame') {
    const detail = [`addr=${event.address ?? '-'}`, `fn=${event.function || '-'}`, event.command || '', event.version || '', event.accepted === undefined ? '' : `accepted=${event.accepted}`, event.hex || ''].filter(Boolean).join(' ')
    addLog('FRAME', detail)
  } else {
    addLog('EVENT', `${event.type || 'serial'} ${JSON.stringify(event)}`)
  }
}

const addLog = (kind, text) => {
  logs.value.push({
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    kind,
    text: String(text || ''),
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false })
  })
  if (logs.value.length > 300) logs.value.splice(0, logs.value.length - 300)
  nextTick(() => { terminalScrollTop.value = logs.value.length * 34 })
}

const clearLogs = () => { logs.value = [] }
const clearCommand = () => { command.value = '' }
const back = () => uni.navigateBack()
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }

function currentSlot() {
  const value = Number(slotNumber.value)
  if (!Number.isInteger(value) || value < 1 || value > totalSlots.value) {
    throw new Error(`卡槽号必须在 1-${totalSlots.value} 之间`)
  }
  return value
}

function boardAddressForSlot(slot) {
  const groupSize = Number(serialAddressLimit.value) || Number(totalSlots.value) || 1
  if (groupSize >= totalSlots.value) return slot
  return ((slot - 1) % groupSize) + 1
}

function buildCommandHex(address, functionCode, data) {
  return toHex(frame(address, functionCode, [...FIXED_PREFIX, ...data]))
}

function normalizeHex(value) {
  const compact = value.replace(/[^0-9a-f]/ig, '')
  if (!compact) throw new Error('HEX 内容不能为空')
  if (compact.length % 2 !== 0) throw new Error('HEX 必须是偶数字符')
  return compact.toUpperCase()
}

function formatHex(value) {
  return normalizeHex(value).replace(/(.{2})/g, '$1 ').trim()
}

function frame(slaveAddress, functionCode, data) {
  const length = 3 + data.length
  const bytes = [0xDD, 0xCC, length >> 8, length & 0xFF, MASTER_ADDRESS, slaveAddress & 0xFF, functionCode & 0xFF, ...data]
  const crc = crc16Modbus(bytes, 0, bytes.length)
  bytes.push(crc >> 8, crc & 0xFF)
  return bytes
}

function crc16Modbus(bytes, offset, length) {
  let crc = 0xFFFF
  for (let index = offset; index < offset + length; index++) {
    crc ^= bytes[index] & 0xFF
    for (let bit = 0; bit < 8; bit++) crc = (crc & 1) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1)
  }
  return crc & 0xFFFF
}

function toHex(bytes) {
  return bytes.map((value) => value.toString(16).padStart(2, '0').toUpperCase()).join(' ')
}

function waitMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function waitForSerialResponse(label, beforeRx) {
  await waitMs(2500)
  const afterRx = Number(serialStatus.receivedBytes || 0)
  if (afterRx <= beforeRx) {
    addLog('WARN', `${label} 已写入，但2500ms内未收到RX；请检查单板地址/串口线/单板供电/波特率/设备是否会应答`)
  }
}

function toHexByte(value) {
  const number = Number(value || 0) & 0xFF
  return `0x${number.toString(16).padStart(2, '0').toUpperCase()}`
}
</script>

<style scoped>
.serial-demo-page { background: #eef3f7; }
.serial-demo-wrap { width: min(96%, 1180px); margin: clamp(12px, 2vh, 24px) auto 0; }
.top-bar { min-height: 68px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.demo-title { display: block; color: #172337; font-size: clamp(22px, 3vw, 32px); font-weight: 700; line-height: 1.2; }
.demo-subtitle { display: block; margin-top: 6px; color: #627086; font-size: clamp(12px, 1.5vw, 15px); overflow-wrap: anywhere; }
.status-pill { flex: 0 0 auto; min-width: 118px; height: 38px; border-radius: 999px; color: #fff; display: flex; align-items: center; justify-content: center; padding: 0 16px; font-size: 13px; font-weight: 700; }
.status-pill.connected { background: #0f9f5b; }
.status-pill.connecting { background: #d18413; }
.status-pill.disconnected { background: #d53d4b; }
.status-line { min-height: 42px; margin-top: 8px; border: 1px solid #d9e2ec; background: #fff; border-radius: 8px; padding: 8px 12px; display: flex; align-items: center; gap: 14px; color: #36475c; font-size: 13px; overflow-wrap: anywhere; box-sizing: border-box; }
.status-line text:first-child { flex: 1; min-width: 0; }
.backend-line { min-height: 58px; margin-top: 8px; border: 1px solid #d9e2ec; background: #fff; border-left: 5px solid #d53d4b; border-radius: 8px; padding: 9px 12px; display: flex; align-items: center; justify-content: space-between; gap: 14px; box-sizing: border-box; }
.backend-line.connected { border-left-color: #0f9f5b; }
.backend-line.connecting { border-left-color: #d18413; }
.backend-copy, .backend-state { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.backend-title { color: #172337; font-size: 14px; font-weight: 700; }
.backend-subtitle { color: #627086; font-size: 12px; font-family: monospace; overflow-wrap: anywhere; }
.backend-state { align-items: flex-end; text-align: right; max-width: 58%; }
.backend-state text:first-child { color: #172337; font-size: 13px; font-weight: 800; }
.backend-state text:last-child { color: #53657c; font-size: 12px; line-height: 1.35; overflow-wrap: anywhere; }
.hint-line { margin-top: 8px; border: 1px solid #f2c5cb; background: #fff4f5; color: #9a2634; border-radius: 8px; padding: 10px 12px; box-sizing: border-box; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.console-layout { display: grid; grid-template-columns: minmax(300px, 380px) minmax(420px, 1fr); gap: 12px; margin-top: 12px; }
.control-panel, .terminal-panel { min-width: 0; background: #fff; border: 1px solid #d9e2ec; border-radius: 8px; padding: 14px; box-sizing: border-box; }
.section-title { color: #172337; font-size: 16px; font-weight: 700; }
.config-grid { display: grid; grid-template-columns: 1fr 112px; gap: 8px; margin-top: 10px; }
.config-field { min-width: 0; height: 46px; border-radius: 8px; background: #f5f8fb; padding: 5px 10px; box-sizing: border-box; display: flex; flex-direction: column; justify-content: center; gap: 3px; }
.config-field text { color: #68758a; font-size: 12px; }
.config-field input { color: #172337; font-size: 14px; font-weight: 700; min-width: 0; }
.config-field.path input { font-family: monospace; font-size: 13px; }
.port-list { max-height: 132px; overflow: hidden; margin-top: 8px; border: 1px solid #d9e2ec; border-radius: 8px; background: #fbfdff; }
.port-item { min-height: 32px; border-bottom: 1px solid #edf2f7; padding: 6px 9px; display: flex; align-items: center; justify-content: space-between; gap: 8px; box-sizing: border-box; }
.port-item text { min-width: 0; color: #172337; font-family: monospace; font-size: 12px; overflow-wrap: anywhere; }
.port-item b { flex: 0 0 auto; color: #d53d4b; font-size: 12px; }
.port-item b.ok { color: #0f9f5b; }
.manual-title { margin-top: 18px; }
.control-panel > .section-title:not(:first-child) { margin-top: 18px; }
.slot-row { height: 46px; margin-top: 10px; border-radius: 8px; background: #f5f8fb; padding: 0 12px; display: flex; align-items: center; gap: 12px; box-sizing: border-box; }
.slot-row text { color: #59697e; font-size: 14px; white-space: nowrap; }
.slot-row input { flex: 1; min-width: 0; color: #172337; font-size: 18px; font-weight: 700; }
.action-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.action-button { height: 44px; border-radius: 8px; background: #edf2f7; color: #223247; font-size: 14px; display: flex; align-items: center; justify-content: center; padding: 0 10px; }
.action-button.primary { background: #0f9f5b; color: #fff; }
.action-button.danger { background: #d53d4b; color: #fff; }
.action-button.wide { grid-column: span 2; }
.mode-row, .tool-row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.mode-toggle { width: 146px; height: 34px; border-radius: 8px; background: #edf2f7; padding: 3px; display: grid; grid-template-columns: 1fr 1fr; box-sizing: border-box; }
.mode-toggle view { display: flex; align-items: center; justify-content: center; border-radius: 6px; color: #607188; font-size: 12px; font-weight: 700; }
.mode-toggle .active { background: #1e6ad8; color: #fff; }
.tiny-button { width: auto; min-width: 76px; height: 34px; border-radius: 8px; background: #fff; color: #1e6ad8; border: 1px solid #bfd1ea; font-size: 13px; display: flex; align-items: center; justify-content: center; padding: 0 12px; }
.tiny-button.blue { background: #1e6ad8; color: #fff; border-color: #1e6ad8; }
.command-input { width: 100%; height: 118px; margin-top: 10px; border-radius: 8px; background: #f5f8fb; color: #172337; padding: 11px; box-sizing: border-box; font-family: monospace; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.send-button { width: 100%; height: 44px; margin-top: 10px; border-radius: 8px; background: #172337; color: #fff; font-size: 15px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.tool-row { flex-wrap: wrap; }
.terminal-panel { padding: 0; overflow: hidden; background: #101927; border-color: #101927; }
.terminal-head { height: 44px; border-bottom: 1px solid rgba(255,255,255,.1); padding: 0 14px; display: flex; align-items: center; justify-content: space-between; color: #dbe8f6; font-size: 14px; font-weight: 700; box-sizing: border-box; }
.terminal-head text:last-child { color: #8fa4bd; font-size: 12px; font-weight: 500; }
.terminal { height: 560px; background: #101927; }
.empty-log { height: 100%; color: #8fa4bd; display: flex; align-items: center; justify-content: center; font-size: 14px; }
.terminal-row { min-height: 28px; display: grid; grid-template-columns: 74px 58px minmax(0, 1fr); gap: 8px; align-items: flex-start; padding: 6px 12px; border-bottom: 1px solid rgba(255,255,255,.06); box-sizing: border-box; font-family: monospace; font-size: 12px; line-height: 1.45; }
.terminal-time { color: #798da6; }
.terminal-kind { color: #dbe8f6; font-weight: 700; }
.terminal-text { color: #bdd0e5; overflow-wrap: anywhere; }
.terminal-row.cmd .terminal-kind { color: #ffd47c; }
.terminal-row.write .terminal-kind, .terminal-row.tx .terminal-kind { color: #72e8aa; }
.terminal-row.rx .terminal-kind, .terminal-row.frame .terminal-kind, .terminal-row.slot .terminal-kind { color: #7cb7ff; }
.terminal-row.error .terminal-kind { color: #ff7986; }
.terminal-row.warn .terminal-kind { color: #facc15; }
.terminal-row.hint .terminal-kind { color: #ffb1ba; }
.terminal-row.done .terminal-kind { color: #72e8aa; }
.back-wrap { padding: clamp(18px, 3.4vh, 40px) 0 max(22px, env(safe-area-inset-bottom)); }
button[disabled] { opacity: .62; }
@media (max-width: 820px) {
  .top-bar { align-items: flex-start; flex-direction: column; }
  .status-line { align-items: flex-start; flex-direction: column; }
  .backend-line { align-items: flex-start; flex-direction: column; }
  .backend-state { align-items: flex-start; text-align: left; max-width: none; }
  .console-layout { grid-template-columns: 1fr; }
  .terminal { height: 420px; }
  .terminal-row { grid-template-columns: 70px 54px minmax(0, 1fr); }
}
</style>
