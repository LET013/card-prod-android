import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const pageSource = fs.readFileSync(new URL('../src/pages/serial-demo/serial-demo.vue', import.meta.url), 'utf8')
const serviceSource = fs.readFileSync(new URL('../src/services/index.js', import.meta.url), 'utf8')
const doorSchedulerSource = fs.readFileSync(new URL('../src/services/doorOperationScheduler.js', import.meta.url), 'utf8')
const adminSource = fs.readFileSync(new URL('../src/pages/admin/admin.vue', import.meta.url), 'utf8')
const engineeringSource = fs.readFileSync(new URL('../src/pages/engineering/engineering.vue', import.meta.url), 'utf8')
const serialConnectionSource = fs.readFileSync(
  new URL('../../app/src/main/java/com/xingyao/card/core/serial/SerialConnectionManager.java', import.meta.url),
  'utf8'
)
const deviceSerialSource = fs.readFileSync(
  new URL('../../app/src/main/java/com/xingyao/card/core/serial/DeviceSerialManager.java', import.meta.url),
  'utf8'
)
const deviceCoreServiceSource = fs.readFileSync(
  new URL('../../app/src/main/java/com/xingyao/card/service/DeviceCoreService.java', import.meta.url),
  'utf8'
)
const bridgeSource = fs.readFileSync(
  new URL('../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java', import.meta.url),
  'utf8'
)
const mainActivitySource = fs.readFileSync(
  new URL('../../app/src/main/java/com/xingyao/card/MainActivity.java', import.meta.url),
  'utf8'
)

test('serial debug page reuses existing abilities without generating protocol frames', () => {
  assert.match(pageSource, /卡槽控制复用客户端现有串口能力/)
  assert.match(pageSource, /额外调试指令仍待确认，不会自动生成协议帧/)
  assert.doesNotMatch(pageSource, /boardAddressForSlot|buildCommandHex|FUNCTION_OPEN_DOOR/)
  assert.doesNotMatch(pageSource, /for\s*\(let\s+addr\s*=\s*1/)
})

test('serial debug controls stay actionable and report disconnected state explicitly', () => {
  assert.doesNotMatch(pageSource, /:disabled="!simulatorAvailable"/)
  assert.doesNotMatch(pageSource, /:class="\{ muted: !simulatorAvailable \}"/)
  assert.match(pageSource, /services\.getSerialStatus\(\)/)
  assert.match(pageSource, /uni\.showToast\(\{ title: '串口未连接'/)
  assert.match(pageSource, /services\.reconnectSerial\(\)/)
  assert.match(pageSource, /services\.disconnectSerial\(\)/)
  assert.match(pageSource, /serialConnected \? '断开串口' : '连接串口'/)
})

test('manual debug path only accepts and sends operator-provided HEX', () => {
  assert.match(pageSource, /输入已按协议核对的完整 HEX 帧/)
  assert.match(pageSource, /services\.sendSerial\(payload, 'HEX'\)/)
  assert.doesNotMatch(pageSource, /writeText|encoding\s*===\s*'TEXT'/)
  assert.match(deviceSerialSource, /\.put\("source", task\.isManual \? "manual" : "poll"\)/)
  assert.match(deviceCoreServiceSource, /onDataManualSent\(JSONObject data\) \{\s*EventBus\.getDefault\(\)\.post\(new SerialDataReceivedEvent\(data\)\);/)
})

test('unexposed serial controls fail explicitly while polling control delegates to Android', () => {
  assert.match(serviceSource, /SERIAL_TEXT_MODE_NOT_EXPOSED/)
  assert.match(serviceSource, /SERIAL_DEBUG_TOGGLE_NOT_EXPOSED/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.setPolling', \{ enabled \}\)/)
  assert.match(serviceSource, /真实串口卡槽地址拓扑尚未确认，已阻止自动轮询变更/)
  assert.doesNotMatch(serviceSource, /console\.log\('serialDebugLogging:'/)
  assert.doesNotMatch(serviceSource, /console\.log\('serialPolling:'/)
})

test('Android exclusively schedules automatic polling while Vue consumes snapshots and events', () => {
  assert.match(deviceSerialSource, /scheduleAtFixedRate\(\s*this::pollNext/)
  assert.match(deviceSerialSource, /WorkCardProtocol\.groupQuery\(address, currentUnixSeconds\(\)\)/)
  assert.match(deviceSerialSource, /WorkCardProtocol\.directQuery\(address, currentUnixSeconds\(\)\)/)
  assert.match(deviceSerialSource, /activeResponse != null/)
  assert.doesNotMatch(deviceSerialSource, /activePollAddress|activePollStartedAt/)
  assert.doesNotMatch(serviceSource, /createPollingWaiter|refreshSimulatorSlot|waitForSimulatorSlotState/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.slotsSnapshot'\)/)
  assert.match(serviceSource, /nativeBridge\.on\('slot\.status', handleEvent\)/)
  assert.match(serviceSource, /nativeBridge\.on\('cabinet\.slotsSnapshot', handleEvent\)/)
  assert.match(serviceSource, /result\.confirmationSource \|\| 'SERIAL_COMMAND_ACCEPTED'/)
})

test('startup broadcast waits for the serial quiet window then emits one snapshot to Vue', () => {
  assert.match(deviceSerialSource, /WorkCardProtocol\.broadcastQuery\(currentUnixSeconds\(\)\)/)
  assert.match(deviceSerialSource, /startupBroadcastActive = true/)
  assert.match(deviceSerialSource, /if \(!startupBroadcastActive\) queueSlotEvent\(slotStatus\)/)
  assert.match(deviceSerialSource, /startupBroadcastActive = false;\s*firstRoundPushed = true;\s*slotStateManager\.pushSnapshotImmediate\(\)/)
  assert.match(deviceSerialSource, /default void onSlotsSnapshot\(JSONArray slots\)/)
  assert.match(deviceCoreServiceSource, /new SlotSnapshotEvent\(slots\)/)
  assert.match(bridgeSource, /emit\("cabinet\.slotsSnapshot", new JSONObject\(\)\.put\("slots", event\.slots\)\)/)
})

test('serial reconnect creates a fresh write worker and retires the previous worker', () => {
  assert.doesNotMatch(serialConnectionSource, /private\s+final\s+Thread\s+writeWorker/)
  assert.match(serialConnectionSource, /Thread worker = new Thread\(this::runWriteWorker/)
  assert.match(serialConnectionSource, /while \(writeWorkerRunning && writeWorker == current\)/)
  assert.match(serialConnectionSource, /writeWorker = null/)
})

test('query, version, LED and normal open use existing native serial abilities', () => {
  assert.match(serviceSource, /nativeBridge\.request\('serial\.querySlot', \{ slotNumber \}\)/)
  assert.match(serviceSource, /async function readBoardVersion\(address\)/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.readVersion', \{ slotNumber \}\)/)
  assert.match(serviceSource, /return dispatchDoorCommand\(\{[\s\S]*operationId: `serialDebug:/)
  assert.match(serviceSource, /const DOOR_COMMAND_MODE = Object\.freeze\(\{[\s\S]*ADMIN_TAKE: 'ADMIN_TAKE',[\s\S]*EMPLOYEE_ISSUE: 'EMPLOYEE_ISSUE'/)
  assert.match(serviceSource, /async function sendDoorCommandAndWaitAck\(slotNumber, commandMode, operationId = ''\)/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.setLedDutyCycle'/)
  assert.match(pageSource, /services\.unlockDoor\(selectedSlotNumber\)/)
  assert.match(pageSource, /services\.unlockAllDoors\(\)/)
  assert.match(pageSource, /services\.openSerialDoor\(currentSlot\(\), false\)/)
})

test('only the serial debug administrator take opens a slot input dialog before reusing the existing workflow', () => {
  assert.match(pageSource, /showAdminTakeDialog/)
  assert.match(pageSource, /请输入需要取卡的卡槽号/)
  assert.match(pageSource, /v-model="adminTakeSlotNumber"/)
  assert.match(pageSource, /parseSlotNumber\(adminTakeSlotNumber\.value\)/)
  assert.match(pageSource, /services\.unlockDoor\(selectedSlotNumber\)/)
  assert.doesNotMatch(pageSource, /takeCardAdmin\s*=.*openSerialDoor/)
  assert.doesNotMatch(serviceSource, /getAdminTakeCardOptions/)
})

test('serial port discovery and switch use existing topology-neutral bridge capabilities', () => {
  assert.match(pageSource, /services\.listSerialPorts\(\)/)
  assert.match(pageSource, /services\.reconnectSerial\(\)/)
  assert.match(pageSource, /serialPorts\.value = Array\.isArray\(result\?\.ports\)/)
  assert.doesNotMatch(pageSource, /扫描未接入|重连未接入/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.listPorts'/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.reconnect'/)
  assert.match(serviceSource, /nativeBridge\.request\('serial\.disconnect'/)
})

test('serial status uses native status and keeps the documented getLogs envelope', () => {
  assert.match(serviceSource, /nativeBridge\.request\('serial\.status'\)/)
  assert.match(serviceSource, /Array\.isArray\(result\?\.logs\) \? result\.logs/)
  assert.match(serviceSource, /当前原生桥未返回串口连接状态/)
})

test('card operations use a connected Android serial capability without rebuilding raw slot commands', () => {
  assert.match(serviceSource, /nativeBridge\.request\('serial\.openDoor', \{ slotNumber, administrator \}\)/)
  assert.match(serviceSource, /String\(serialCapability\?\.state \|\| ''\)\.trim\(\)\.toUpperCase\(\)/)
  assert.match(serviceSource, /if \(state !== 'CONNECTED'\)/)
  assert.match(serviceSource, /serialCapability\?\.simulator === true/)
  assert.doesNotMatch(serviceSource, /buildSlotQueryHex/)
  assert.doesNotMatch(serviceSource, /serialStatus && serialStatus\.connected/)
  assert.doesNotMatch(serviceSource, /拓扑已确认：槽号=从机地址 1:1/)
})

test('batch eject treats serial command acceptance as successful card opening', () => {
  assert.match(adminSource, /空卡槽跳过，其余卡槽均尝试开门/)
  assert.match(engineeringSource, /空卡槽跳过，其余卡槽均尝试开门/)
  assert.match(adminSource, /doorOpenedCount/)
  assert.match(engineeringSource, /doorOpenedCount/)
  assert.match(serviceSource, /state: 'COMMAND_ACCEPTED'/)
  assert.match(serviceSource, /result\.confirmationSource \|\| 'SERIAL_COMMAND_ACCEPTED'/)
  assert.match(serviceSource, /const accepted = plan\.targetCount > 0 && successCount === plan\.targetCount && failedCount === 0/)
  assert.match(serviceSource, /已开卡' \+ successCount \+ '张工卡/)
  assert.match(serviceSource, /ttsFlush: false/)
  assert.match(serviceSource, /const result = await sendAdministratorDoorCommandWithPhysicalFallback\(slotNumber, operationId, currentSlot\)/)
  assert.match(serviceSource, /createDoorOperationScheduler/)
  assert.match(doorSchedulerSource, /SERIAL_ACK_TIMEOUT/)
  assert.doesNotMatch(adminSource, /完全弹出所有电磁锁/)
  assert.doesNotMatch(engineeringSource, /title: '操作完成'|Android 数据层/)
})

test('native debug bulk-open cannot bypass the Vue acknowledgement sequence', () => {
  assert.match(deviceSerialSource, /原生批量开门已禁用，请使用 Vue 一键弹卡流程逐槽等待开门应答/)
  assert.doesNotMatch(deviceSerialSource, /for \(int address = 1; address <= totalSlots; address\+\+\) \{\s*enqueueManual\(new SendTask\(\s*WorkCardProtocol\.openDoor\(address, administrator\), "door\.all"/)
})

test('administrator and remote opening use serial acceptance plus status sync without an employee card event', () => {
  assert.match(serviceSource, /async function executeLocalAdminTakeCard\(slotNumber,/)
  assert.match(serviceSource, /result = await executeAdminEjectSlot\(\{ slotNumber: address \}, parent\)/)
  assert.match(serviceSource, /reportDeviceStatusImmediately\('admin-take'\)/)
  assert.match(serviceSource, /const reportPending = statusReport\?\.sent !== true/)
  assert.match(serviceSource, /state: reportPending \? 'REPORT_PENDING' : 'COMPLETED'/)
  assert.match(serviceSource, /stage: result\.reportPending \? 'REPORT_PENDING' : 'COMPLETED'/)
  assert.match(serviceSource, /卡槽状态等待后台同步/)
  assert.doesNotMatch(serviceSource, /saveAdminTakeCardEvent|waitForAdminTakeBackendConfirmation/)
  assert.match(serviceSource, /const requestedCount = plan\.targetCount \+ plan\.failures\.length/)
  assert.match(serviceSource, /physicalConfirmed = result\.physicalConfirmed === true/)
  assert.match(serviceSource, /ackMissing: result\.ackMissing === true/)
  assert.match(serviceSource, /successCount \+= 1/)
  assert.match(serviceSource, /return executeLocalAdminTakeCard\(slotNumber,/)
  assert.match(serviceSource, /executeOpen: \(\{ operationId, operatorId, msgId, slotId, authType \}\) => executeAdminOpenDoor/)
  assert.match(serviceSource, /reportDeviceStatusImmediately\('remote-open'\)/)
  assert.match(serviceSource, /state: reportPending \? 'REPORT_PENDING' : 'COMPLETED'/)
  assert.match(serviceSource, /statusReportSentCount/)
  assert.match(serviceSource, /announceAdminCardOpened\(address\)/)
})

test('missing board ACK becomes success only after the target was known occupied and serial now confirms empty', () => {
  assert.match(serviceSource, /async function sendAdministratorDoorCommandWithPhysicalFallback/)
  assert.match(serviceSource, /if \(error\?\.code !== 'SERIAL_ACK_TIMEOUT'\) throw error/)
  assert.match(serviceSource, /wasCardPresentBeforeDoorCommand\(initialSlot, slotNumber\)/)
  assert.match(serviceSource, /isAdminCardPhysicallyRemoved\(projectedSlot, slotNumber\)/)
  assert.match(serviceSource, /confirmationSource: 'SERIAL_SLOT_EMPTY_AFTER_ACK_TIMEOUT'/)
})

test('administrator card operations always use the administrator door mode while employee identity take uses issue mode', () => {
  assert.match(serviceSource, /return await sendDoorCommandAndWaitAck\(slotNumber, DOOR_COMMAND_MODE\.ADMIN_TAKE, operationId\)/)
  assert.match(serviceSource, /sendAdministratorDoorCommandWithPhysicalFallback\(address, operationId, initialSlot\)/)
  assert.match(serviceSource, /sendAdministratorDoorCommandWithPhysicalFallback\(slotNumber, operationId, currentSlot\)/)
  assert.doesNotMatch(serviceSource, /sendAdministratorDoorCommandWithPhysicalFallback\([^\n]*,\s*(true|false),/)
  assert.match(serviceSource, /sendOpenDoor: \(\{ operationId, slotNumber, requiresBoardAck, ackTimeoutMs \}\) => dispatchDoorCommand\(\{[\s\S]*commandMode: DOOR_COMMAND_MODE\.EMPLOYEE_ISSUE/)
})

test('backgrounding the activity cancels an active face session instead of restoring its camera overlay', () => {
  assert.match(mainActivitySource, /protected void onPause\(\) \{\s*if \(jsBridge != null\) jsBridge\.cancelFaceOperationForActivityPause\(\);/)
  assert.match(bridgeSource, /public void cancelFaceOperationForActivityPause\(\)[\s\S]*activity_background/)
  assert.match(bridgeSource, /emit\("face\." \+ action \+ "\.cancelled", data\)/)
})
