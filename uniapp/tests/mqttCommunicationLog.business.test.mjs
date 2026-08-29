import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const readSource = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('MQTT communication logs keep local summaries and route explicit app logs through the native upload switch', async () => {
  const [service, admin, bridge, appLog] = await Promise.all([
    readSource('../src/services/index.js'),
    readSource('../src/pages/admin/admin.vue'),
    readSource('../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java'),
    readSource('../../app/src/main/java/com/xingyao/card/core/log/AppLog.java')
  ])

  assert.match(service, /_mqttCommLogWrite\(type, detail/)
  assert.match(service, /收到: cmd=\$\{cmd\}/)
  assert.match(service, /MQTT 已连接/)
  assert.match(service, /MQTT 已断开/)
  const bufferStart = service.indexOf('function _mqttCommLogWrite')
  const bufferEnd = service.indexOf('function startMqttCommLogCapture', bufferStart)
  assert.match(service.slice(bufferStart, bufferEnd), /diagnostics\.log\.write/)
  assert.doesNotMatch(service, /diagnostics\.logcat\.batch|sendLogcatLine/)
  assert.match(admin, /connected: '连接'/)
  assert.match(admin, /disconnected: '断开'/)
  assert.match(admin, /message_rx: '收到'/)
  assert.match(bridge, /diagnostics\.log\.setUploadEnabled/)
  assert.match(bridge, /diagnostics\.log\.write/)
  assert.match(appLog, /if \(UPLOAD_ENABLED\.get\(\)\) \{\s*upload\(/)
  assert.match(appLog, /upload\(safeLevel, safeTag, safeMessage\);\s*}\s*printLocal\(/)
  assert.match(appLog, /data\.put\("level", "DEBUG"\.equals\(level\) \? "INFO" : level\)/)
  assert.match(appLog, /data\.put\("message",/)
  assert.match(appLog, /data\.put\("timestamp",/)
  assert.doesNotMatch(appLog, /ProcessBuilder|logcat --pid|data\.put\("content",/)
})

test('MQTT disconnect immediately clears the cabinet online projection', async () => {
  const mainSource = await readSource('../src/main.js')
  assert.match(mainSource, /nativeBridge\.on\('mqtt\.disconnected',[\s\S]*appState\.deviceInfo\.mqttConnected = false/)
})
