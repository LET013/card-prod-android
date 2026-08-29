import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { APP_RESTART_CAPABILITY } from '../src/constants/deviceMaintenance.js'

const featureSource = fs.readFileSync(new URL('../src/pages/feature/feature.vue', import.meta.url), 'utf8')
const serviceSource = fs.readFileSync(new URL('../src/services/index.js', import.meta.url), 'utf8')
const bridgeSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java', import.meta.url), 'utf8')
const restartManagerSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/core/maintenance/AppRestartManager.java', import.meta.url), 'utf8')
const manifestSource = fs.readFileSync(new URL('../../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8')

test('restart page calls the native process restart capability after confirmation', () => {
  assert.equal(APP_RESTART_CAPABILITY.available, true)
  assert.equal(APP_RESTART_CAPABILITY.code, 'APP_RESTART_AVAILABLE')
  assert.match(APP_RESTART_CAPABILITY.message, /重新启动/)
  assert.match(featureSource, /services\.restartApp\(\{delayMs:3000\}\)/)
  assert.match(featureSource, /确认重启应用/)
  assert.match(serviceSource, /nativeBridge\.request\('app\.restart'/)
  assert.match(serviceSource, /result\?\.status !== 'SCHEDULED'/)
  assert.match(serviceSource, /recoverPendingAppRestart/)
  assert.match(bridgeSource, /"app\.restart"/)
  assert.match(bridgeSource, /"app\.restartStatus"/)
  assert.match(restartManagerSource, /AlarmManager/)
  assert.match(restartManagerSource, /killProcess/)
  assert.match(manifestSource, /\.core\.maintenance\.AppRestartReceiver/)
})

test('browser mock performs a visible reload instead of returning a fixed restart success', () => {
  assert.match(serviceSource, /isMockDev\s*\?\s*scheduleMockAppRestart/)
  assert.match(serviceSource, /window\.sessionStorage\.setItem\(MOCK_APP_RESTART_STORAGE_KEY/)
  assert.match(serviceSource, /status: 'EXECUTED'/)
  assert.match(serviceSource, /window\.location\.reload\(\)/)
  assert.match(serviceSource, /armMockAppRestart\(status, Math\.max\(0, dueAt - Date\.now\(\)\)\)/)
})

test('legacy command page delegates to the topology-guarded serial console', () => {
  assert.match(featureSource, /pages\/serial-demo\/serial-demo/)
  assert.doesNotMatch(featureSource, /services\.sendSerial\(/)
  assert.doesNotMatch(featureSource, /services\.reconnectSerial\(/)
  assert.doesNotMatch(featureSource, /'TEXT'/)
})
