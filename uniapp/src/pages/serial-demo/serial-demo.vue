<template>
  <view class="page-root serial-demo-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar title="串口调试台" hint="串口日志和人工 HEX 指令验证" @back="back" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="serial-demo-wrap">
        <view class="top-bar">
          <view>
            <text class="demo-title">串口单机调试台</text>
            <text class="demo-subtitle">{{ bridgeLabel }} · {{ serialStatus.port || '-' }} @ {{ serialStatus.baudRate || '-' }}</text>
          </view>
          <view class="top-bar-right">
            <view class="panel-toggle" @click="showPanels = !showPanels">
              <view class="toggle-check" :class="{checked: showPanels}" />
              <text>控制面板</text>
            </view>
            <view class="status-pill" :class="statusClass">{{ serialStatus.state || 'UNKNOWN' }}</view>
          </view>
        </view>

        <view class="status-line">
          <text>{{ serialStatus.message || '等待串口状态' }}</text>
          <text>TX {{ serialStatus.sentBytes || 0 }} bytes</text>
          <text>RX {{ serialStatus.receivedBytes || 0 }} bytes</text>
          <text v-if="serialStatus.polling">轮询中</text>
        </view>
        <view v-if="serialStatus.permissionHint" class="hint-line">{{ serialStatus.permissionHint }}</view>
        <view class="topology-warning">
          卡槽控制复用客户端现有串口能力；请输入已确认的卡槽号。额外调试指令仍待确认，不会自动生成协议帧。
        </view>

        <view class="console-layout" :class="{ 'single-column': !showPanels }">
          <view v-if="showPanels" class="control-panel">
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
                <button v-permission="'maintenance.serial.config'" class="tiny-button" :disabled="scanningPorts" @click="scanSerialPorts">{{ scanningPorts ? '扫描中' : '扫描端口' }}</button>
                <button v-if="canSaveSerialConfig" class="tiny-button blue" :disabled="savingConfig" @click="applySerialConfig">{{ savingConfig ? '保存中' : '保存配置' }}</button>
                <button v-permission="'maintenance.serial.config'" class="tiny-button" :class="{ danger: serialConnected }" :disabled="serialSwitching" @click="toggleSerial">
                  {{ serialSwitching ? '处理中' : (serialConnected ? '断开串口' : '连接串口') }}
                </button>
                <button class="tiny-button" @click="runPendingSerialAction('轮询控制')">轮询控制待确认</button>
              </view>
              <view v-if="serialPorts.length" class="port-list">
                <view v-for="port in serialPorts" :key="port.path" class="port-item" @click="selectSerialPort(port)">
                  <text>{{ port.path }}</text>
                  <b :class="{ok: port.readable && port.writable}">{{ port.readable && port.writable ? '可读写' : '权限不足' }}</b>
                </view>
              </view>

              <view class="section-title">卡槽控制</view>
              <view class="blocked-hint">点击操作时实时检查串口；未连接会明确提示“串口未连接”，不会通过置灰隐藏原因。</view>
              <view class="slot-row">
                <text>卡槽号</text>
                <input v-model="slotNumber" type="number" />
              </view>

              <view class="action-section">
                <view class="action-label">控制</view>
                <view class="action-grid">
                  <button v-permission="'maintenance.serial.admin-take'" class="action-button primary" :disabled="busy" @click="takeCardAdmin">管理员取卡</button>
                  <button v-permission="'maintenance.serial.normal-take'" class="action-button outline" :disabled="busy" @click="takeCardNormal">普通取卡</button>
                  <button v-permission="'maintenance.serial.led'" class="action-button info" :disabled="busy" @click="openLedDialog">LED亮度调整</button>
                  <button v-permission="'maintenance.serial.eject-all'" class="action-button danger" :disabled="busy" @click="confirmEjectAll">一键弹出所有卡</button>
                </view>
              </view>

              <view class="action-section">
                <view class="action-label">查询</view>
                <view class="action-grid">
                  <button v-permission="'maintenance.serial.read-status'" class="action-button" :disabled="busy" @click="readSlotStatus">读取卡状态</button>
                  <button v-permission="'maintenance.serial.read-version'" class="action-button" :disabled="busy" @click="readVersion">读取版本信息</button>
                </view>
              </view>

              <view class="action-section">
                <view class="action-label">OTA</view>
                <view class="action-grid">
                  <button class="action-button" @click="runPendingSerialAction('OTA发卡机')">OTA发卡机</button>
                  <button class="action-button" @click="runPendingSerialAction('OTA卡片')">OTA卡片</button>
                </view>
              </view>

              <view class="section-title manual-title">手动命令</view>
              <view class="mode-row">
                <view class="mode-toggle">
                  <view class="active">HEX</view>
                </view>
                <button v-permission="'maintenance.serial.manual-command'" class="tiny-button" @click="clearCommand">清空</button>
              </view>
              <textarea class="command-input" v-model="command" placeholder="输入已按协议核对的完整 HEX 帧"></textarea>
              <button v-permission="'maintenance.serial.manual-command'" class="send-button" :disabled="busy" @click="sendManualCommand">写入串口</button>

              <view class="tool-row">
                <button class="tiny-button blue" @click="refreshStatus">刷新日志状态</button>
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
    </scroll-view>

    <view v-if="showAdminTakeDialog" class="dialog-overlay" @click.self="closeAdminTakeDialog">
      <view class="dialog-card">
        <text class="dialog-title">管理员取卡</text>
        <view class="admin-take-hint">请输入需要取卡的卡槽号，确认后复用现有管理员取卡流程。</view>
        <view class="admin-take-input-row">
          <text>卡槽号</text>
          <input v-model="adminTakeSlotNumber" type="number" :placeholder="`1 - ${totalSlotCount()}`" />
        </view>
        <view class="dialog-actions">
          <button class="dialog-btn cancel" @click="closeAdminTakeDialog">取消</button>
          <button v-permission="'maintenance.serial.admin-take'" class="dialog-btn confirm" @click="confirmAdminTakeCard">确认取卡</button>
        </view>
      </view>
    </view>

    <view v-if="showLedDialog" class="dialog-overlay" @click.self="closeLedDialog">
      <view class="dialog-card">
        <text class="dialog-title">LED亮度调整</text>
        <view class="dialog-body">
          <view class="brightness-row">
            <text>占空比</text>
            <input v-model="ledDutyCycle" type="number" min="30" max="100" />
            <text class="brightness-unit">%</text>
          </view>
          <view class="brightness-hint">协议范围 30% ~ 100%，当前卡槽 {{ slotNumber || '-' }}</view>
        </view>
        <view class="dialog-actions">
          <button class="dialog-btn cancel" @click="closeLedDialog">取消</button>
          <button v-permission="'maintenance.serial.led'" class="dialog-btn confirm" @click="confirmLedDutyCycle">确认发送</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import { appState, hasPermission } from '@/state/appState.js'
import { ROLE_META } from '@/constants/app.js'
import { nativeBridge, services } from '@/services/index.js'

const roleLabel = computed(() => appState.session?.roleLabels?.join('、') || ROLE_META[appState.session?.role]?.label || '')
const bridgeLabel = computed(() => nativeBridge.isAvailable() ? 'Android Bridge 已连接' : '浏览器 Mock 模式')
const canSaveSerialConfig = computed(() => hasPermission('maintenance.serial.config') && hasPermission('system.settings.edit'))
const serialStatus = reactive({ ...appState.runtime.serial })
const serialConnected = computed(() => String(serialStatus.state || '').trim().toUpperCase() === 'CONNECTED')
const statusClass = computed(() => {
  if (serialStatus.state === 'CONNECTED') return 'connected'
  if (serialStatus.state === 'CONNECTING') return 'connecting'
  return 'disconnected'
})
const portInput = ref(appState.settings.serialPort || '')
const baudInput = ref(String(appState.settings.baudRate || '57600'))
const slotNumber = ref('1')
const ledDutyCycle = ref('60')
const command = ref('')
const logs = ref([])
const terminalScrollTop = ref(0)
const busy = ref(false)
const savingConfig = ref(false)
const scanningPorts = ref(false)
const serialSwitching = ref(false)
const serialPorts = ref([])
const showPanels = ref(false)
const showLedDialog = ref(false)
const showAdminTakeDialog = ref(false)
const adminTakeSlotNumber = ref('')
const unsubs = []

onMounted(async () => {
  addLog('INFO', 'serial debug ready')
  unsubs.push(nativeBridge.on('serial.statusChanged', (event) => {
    if (!event) return
    Object.assign(serialStatus, event)
    if (event.state !== 'CONNECTED') addLog('STATUS', `${event.state || ''} ${event.message || ''}`.trim())
    if (event.permissionHint) addLog('HINT', event.permissionHint)
  }))
  unsubs.push(nativeBridge.on('serial.dataReceived', (event) => addSerialEvent(event)))
  unsubs.push(nativeBridge.on('serial.log', (event) => addSerialEvent(event)))
  unsubs.push(nativeBridge.on('serial.frame', (event) => addSerialEvent({ ...event, type: 'serialFrame' })))
  await refreshStatus()
})

onBeforeUnmount(() => {
  unsubs.forEach((off) => off?.())
})

const applySerialConfig = async () => {
  const port = portInput.value.trim()
  const baudRate = baudInput.value.trim() || '57600'
  if (!port) {
    uni.showToast({ title: '请输入串口端口', icon: 'none' })
    return
  }
  savingConfig.value = true
  try {
    addLog('CMD', `save serial config port=${port} baud=${baudRate}`)
    const result = await services.saveSettings({ ...appState.settings, serialPort: port, baudRate })
    if (!result.remoteSaved) throw new Error(result.error || '串口配置未同步到后台')
    if (!result.localSaved) throw new Error(result.localError || '串口配置未写入本机缓存')
    Object.assign(serialStatus, await services.getSerialStatus())
    addLog('DONE', `串口配置已保存并同步；${result.restartRequired ? '重启应用后生效' : '当前配置已生效'}`)
    if (serialStatus.permissionHint) addLog('HINT', serialStatus.permissionHint)
  } catch (error) {
    addLog('ERROR', `保存串口配置失败 ${error.message || '未知错误'}`)
  } finally {
    savingConfig.value = false
  }
}

const scanSerialPorts = async () => {
  if (scanningPorts.value) return
  scanningPorts.value = true
  try {
    const result = await services.listSerialPorts()
    serialPorts.value = Array.isArray(result?.ports) ? result.ports : []
    addLog('STATUS', result?.message || `发现 ${serialPorts.value.length} 个候选串口`)
    if (!serialPorts.value.length) uni.showToast({ title: '未发现候选串口', icon: 'none' })
  } catch (error) {
    serialPorts.value = []
    addLog('ERROR', `扫描串口失败 ${error.message || '未知错误'}`)
  } finally {
    scanningPorts.value = false
  }
}

const selectSerialPort = (port) => {
  const path = String(port?.path || '').trim()
  if (!path) return
  portInput.value = path
  addLog('INFO', `已选择串口 ${path}`)
}

const toggleSerial = async () => {
  if (serialSwitching.value) return
  serialSwitching.value = true
  const disconnecting = serialConnected.value
  try {
    const result = disconnecting
      ? await services.disconnectSerial()
      : await services.reconnectSerial()
    Object.assign(serialStatus, result || {})
    const connected = String(result?.state || '').trim().toUpperCase() === 'CONNECTED'
    if (!disconnecting && !connected) throw new Error(result?.message || '串口未连接')
    addLog(connected ? 'DONE' : 'STATUS', `${result?.state || ''} ${result?.message || ''}`.trim())
    uni.showToast({ title: connected ? '串口已连接' : '串口已断开', icon: 'none' })
  } catch (error) {
    showActionError(error, disconnecting ? '断开串口失败' : '连接串口失败')
  } finally {
    serialSwitching.value = false
  }
}

const takeCardAdmin = async () => {
  if (busy.value || !await ensureSerialConnected()) return
  adminTakeSlotNumber.value = slotNumber.value
  showAdminTakeDialog.value = true
}
const closeAdminTakeDialog = () => {
  showAdminTakeDialog.value = false
  adminTakeSlotNumber.value = ''
}
const confirmAdminTakeCard = async () => {
  let selectedSlotNumber
  try {
    selectedSlotNumber = parseSlotNumber(adminTakeSlotNumber.value)
  } catch (error) {
    uni.showToast({ title: error.message, icon: 'none' })
    return
  }
  closeAdminTakeDialog()
  await runSerialAction(
    '管理员取卡',
    () => services.unlockDoor(selectedSlotNumber),
    { slotNumber: selectedSlotNumber }
  )
}
const takeCardNormal = () => runSerialAction('普通取卡', () => services.openSerialDoor(currentSlot(), false))
const readSlotStatus = () => runSerialAction('读取卡状态', () => services.querySlot(currentSlot()))
const readVersion = () => runSerialAction('读取版本信息', () => services.readBoardVersion(currentSlot()))

const confirmEjectAll = async () => {
  if (!await ensureSerialConnected()) return
  uni.showModal({
    title: '一键弹出所有卡',
    content: '将复用现有一键弹卡流程，逐个执行并等待物理状态确认。是否继续？',
    success: (result) => {
      if (result.confirm) runSerialAction('一键弹出所有卡', () => services.unlockAllDoors(), { skipStatusCheck: true })
    }
  })
}

const openLedDialog = async () => {
  if (!await ensureSerialConnected()) return
  showLedDialog.value = true
}
const closeLedDialog = () => { showLedDialog.value = false }
const confirmLedDutyCycle = async () => {
  const dutyCycle = Number(ledDutyCycle.value)
  if (!Number.isInteger(dutyCycle) || dutyCycle < 30 || dutyCycle > 100) {
    uni.showToast({ title: '请输入 30 到 100', icon: 'none' })
    return
  }
  closeLedDialog()
  await runSerialAction('LED亮度调整', () => services.setSerialLedDutyCycle(currentSlot(), dutyCycle), { skipStatusCheck: true })
}

const runPendingSerialAction = async (label) => {
  if (!await ensureSerialConnected()) return
  uni.showToast({ title: `${label}调试方式待确认`, icon: 'none' })
  addLog('HINT', `${label}调试方式待确认，未下发串口指令`)
}

const sendManualCommand = async () => {
  const input = command.value.trim()
  if (!input) {
    uni.showToast({ title: '请输入命令', icon: 'none' })
    return
  }
  if (!await ensureSerialConnected()) return
  busy.value = true
  try {
    await writeHex('手动 HEX', input)
    await refreshStatus()
  } catch (error) {
    showActionError(error, '手动命令发送失败')
  } finally {
    busy.value = false
  }
}

const runSerialAction = async (label, action, options = {}) => {
  if (busy.value) return
  if (options.skipStatusCheck !== true && !await ensureSerialConnected()) return
  busy.value = true
  try {
    const targetSlot = label.includes('所有卡') ? 'ALL' : (options.slotNumber ?? currentSlot())
    addLog('CMD', `${label} slot=${targetSlot}`)
    const result = await action()
    const message = result?.message || `${label}已提交`
    addLog('DONE', message)
    uni.showToast({ title: message, icon: 'none' })
    await refreshStatus()
  } catch (error) {
    showActionError(error, `${label}失败`)
  } finally {
    busy.value = false
  }
}

const ensureSerialConnected = async () => {
  try {
    const status = await services.getSerialStatus()
    Object.assign(serialStatus, status || {})
    if (String(status?.state || '').trim().toUpperCase() === 'CONNECTED') return true
  } catch (error) {
    addLog('ERROR', `读取串口状态失败 ${error.message || '未知错误'}`)
  }
  uni.showToast({ title: '串口未连接', icon: 'none' })
  addLog('WARN', '串口未连接')
  return false
}

const showActionError = (error, fallback) => {
  const raw = `${error?.code || ''} ${error?.message || ''}`
  const message = /SERIAL_NOT_CONNECTED|串口.*未连接|not connected/i.test(raw)
    ? '串口未连接'
    : (error?.message || fallback)
  uni.showToast({ title: message, icon: 'none' })
  addLog('ERROR', `${fallback} ${message}`)
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

const refreshStatus = async () => {
  try {
    Object.assign(serialStatus, await services.getSerialStatus())
    if (!portInput.value) portInput.value = serialStatus.port || appState.settings.serialPort || ''
    if (!baudInput.value) baudInput.value = String(serialStatus.baudRate || appState.settings.baudRate || '57600')
    addLog('STATUS', `${serialStatus.state || ''} ${serialStatus.message || ''}`.trim())
    if (serialStatus.permissionHint) addLog('HINT', serialStatus.permissionHint)
  } catch (error) {
    addLog('ERROR', `读取状态失败 ${error.message || '未知错误'}`)
  }
}

const addSerialEvent = (event) => {
  if (!event) return
  if (event.source !== 'manual') return
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
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/engineering/engineering' }) })
const exitAdmin = async () => {
  await services.logout()
  uni.reLaunch({ url: '/pages/index/index' })
}

function currentSlot() {
  return parseSlotNumber(slotNumber.value)
}

function totalSlotCount() {
  return Number(serialStatus.totalSlots || appState.settings.totalSlots || appState.settings.totalCount || 100)
}

function parseSlotNumber(input) {
  const value = Number(input)
  const totalSlots = totalSlotCount()
  if (!Number.isInteger(value) || value < 1 || value > totalSlots) {
    throw new Error(`卡槽号必须在 1 到 ${totalSlots} 之间`)
  }
  return value
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

</script>

<style scoped>
.serial-demo-page { background: #eef3f7; }
.serial-demo-wrap { width: min(96%, 1180px); margin: clamp(10px, 1.8vh, 20px) auto 0; }
.top-bar { min-height: 68px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.top-bar-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.demo-title { display: block; color: #172337; font-size: clamp(22px, 3vw, 32px); font-weight: 700; line-height: 1.2; }
.demo-subtitle { display: block; margin-top: 6px; color: #627086; font-size: clamp(12px, 1.5vw, 15px); overflow-wrap: anywhere; }
.panel-toggle { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 8px; background: #edf2f7; cursor: pointer; }
.panel-toggle text { color: #4f617d; font-size: 13px; font-weight: 600; white-space: nowrap; }
.toggle-check { width: 32px; height: 20px; border-radius: 10px; background: #c5d0df; transition: background .2s; position: relative; }
.toggle-check::after { content: ''; position: absolute; width: 16px; height: 16px; border-radius: 50%; background: #fff; top: 2px; left: 2px; transition: left .2s; }
.toggle-check.checked { background: #0f9f5b; }
.toggle-check.checked::after { left: 14px; }
.status-pill { flex: 0 0 auto; min-width: 118px; height: 38px; border-radius: 999px; color: #fff; display: flex; align-items: center; justify-content: center; padding: 0 16px; font-size: 13px; font-weight: 700; }
.status-pill.connected { background: #0f9f5b; }
.status-pill.connecting { background: #d18413; }
.status-pill.disconnected { background: #d53d4b; }
.status-line { min-height: 42px; margin-top: 8px; border: 1px solid #d9e2ec; background: #fff; border-radius: 8px; padding: 8px 12px; display: flex; align-items: center; gap: 14px; color: #36475c; font-size: 13px; overflow-wrap: anywhere; box-sizing: border-box; }
.status-line text:first-child { flex: 1; min-width: 0; }

.hint-line { margin-top: 8px; border: 1px solid #f2c5cb; background: #fff4f5; color: #9a2634; border-radius: 8px; padding: 10px 12px; box-sizing: border-box; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.topology-warning { margin-top: 8px; border: 1px solid #f0c36b; background: #fff8e7; color: #805b13; border-radius: 8px; padding: 10px 12px; box-sizing: border-box; font-size: 13px; line-height: 1.5; }
.blocked-hint { margin-top: 10px; border-radius: 8px; background: #f5f8fb; color: #69788e; padding: 10px 12px; font-size: 12px; line-height: 1.45; }
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
.action-section { margin-top: 12px; }
.action-label { color: #68758a; font-size: 12px; font-weight: 600; margin-bottom: 6px; padding-left: 2px; }
.action-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.action-button { height: 44px; border-radius: 8px; background: #edf2f7; color: #223247; font-size: 14px; display: flex; align-items: center; justify-content: center; padding: 0 10px; min-width: 0; }
.console-layout.single-column { grid-template-columns: 1fr; }
.action-button.primary { background: #0f9f5b; color: #fff; }
.action-button.outline { background: #e8f5ee; color: #0f9f5b; }
.action-button.danger { background: #d53d4b; color: #fff; }
.action-button.info { background: #e3edfb; color: #1e6ad8; }
.action-button.muted { background: #eef1f5; color: #95a3b8; }
.mode-row, .tool-row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.mode-toggle { width: 73px; height: 34px; border-radius: 8px; background: #edf2f7; padding: 3px; display: grid; grid-template-columns: 1fr; box-sizing: border-box; }
.mode-toggle view { display: flex; align-items: center; justify-content: center; border-radius: 6px; color: #607188; font-size: 12px; font-weight: 700; }
.mode-toggle .active { background: #1e6ad8; color: #fff; }
.tiny-button { width: auto; min-width: 76px; height: 34px; border-radius: 8px; background: #fff; color: #1e6ad8; border: 1px solid #bfd1ea; font-size: 13px; display: flex; align-items: center; justify-content: center; padding: 0 12px; }
.tiny-button.blue { background: #1e6ad8; color: #fff; border-color: #1e6ad8; }
.tiny-button.danger { background: #d53d4b; color: #fff; border-color: #d53d4b; }
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
button[disabled] { opacity: .62; }

.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 200; }
.dialog-card { width: min(88%, 340px); background: #fff; border-radius: 14px; padding: 24px; box-sizing: border-box; }
.dialog-title { color: #172337; font-size: 18px; font-weight: 700; display: block; margin-bottom: 20px; }
.admin-take-hint { margin: -8px 0 14px; color: #68758a; font-size: 13px; line-height: 1.5; }
.admin-take-input-row { height: 52px; margin-bottom: 22px; border-radius: 8px; background: #f5f8fb; padding: 0 12px; display: flex; align-items: center; gap: 12px; box-sizing: border-box; }
.admin-take-input-row text { color: #59697e; font-size: 14px; white-space: nowrap; }
.admin-take-input-row input { flex: 1; min-width: 0; color: #172337; font-size: 22px; font-weight: 700; }
.dialog-body { margin-bottom: 22px; }
.brightness-row { display: flex; align-items: center; gap: 10px; }
.brightness-row text { color: #59697e; font-size: 14px; white-space: nowrap; }
.brightness-row input { width: 72px; height: 48px; border-radius: 8px; background: #f5f8fb; color: #172337; font-size: 26px; font-weight: 800; text-align: center; box-sizing: border-box; padding: 0 6px; }
.brightness-unit { color: #96a3b5 !important; font-size: 13px !important; }
.brightness-hint { margin-top: 10px; color: #8b9cb5; font-size: 12px; }
.dialog-actions { display: flex; gap: 10px; justify-content: flex-end; }
.dialog-btn { height: 40px; border-radius: 8px; padding: 0 20px; font-size: 14px; font-weight: 600; display: flex; align-items: center; justify-content: center; }
.dialog-btn.cancel { background: #edf2f7; color: #4f617d; }
.dialog-btn.confirm { background: #1e6ad8; color: #fff; }

@media (max-width: 820px) {
  .top-bar { align-items: flex-start; flex-direction: column; }
  .status-line { align-items: flex-start; flex-direction: column; }
  .console-layout { grid-template-columns: 1fr; }
  .terminal { height: 420px; }
  .terminal-row { grid-template-columns: 70px 54px minmax(0, 1fr); }
}
</style>
