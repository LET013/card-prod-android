import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { resolveUpgradeCapability } from '../src/constants/upgrade.js'

const adminSource = fs.readFileSync(new URL('../src/pages/admin/admin.vue', import.meta.url), 'utf8')
const engineeringSource = fs.readFileSync(new URL('../src/pages/engineering/engineering.vue', import.meta.url), 'utf8')
const serviceSource = fs.readFileSync(new URL('../src/services/index.js', import.meta.url), 'utf8')
const appUpdatePanelSource = fs.readFileSync(new URL('../src/components/AppUpdatePanel.vue', import.meta.url), 'utf8')
const nativeManagerSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/core/maintenance/AppUpdateManager.java', import.meta.url), 'utf8')
const bridgeSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java', import.meta.url), 'utf8')

test('upgrade entries retain their availability boundary but use concise user-facing messages', () => {
  assert.equal(resolveUpgradeCapability('app').available, true)
  assert.equal(resolveUpgradeCapability('app').code, 'APP_UPGRADE_AVAILABLE')
  assert.equal(resolveUpgradeCapability('board').available, true)
  assert.equal(resolveUpgradeCapability('board').code, 'BOARD_FIRMWARE_UPGRADE_AVAILABLE')
  assert.equal(resolveUpgradeCapability('board').message, '当前没有待处理的升级任务')
  assert.equal(resolveUpgradeCapability('workCard').available, false)
  assert.equal(resolveUpgradeCapability('workCard').message, '当前暂不支持工作卡升级')
  assert.equal(resolveUpgradeCapability('mainBoard').available, false)
  assert.equal(resolveUpgradeCapability('mainBoard').message, '当前暂不支持主板升级')
})

test('APP upgrade uses the V4.2 check and native verified install path', () => {
  assert.match(serviceSource, /\/api\/v1\/app-version\/check/)
  assert.match(serviceSource, /app\.downloadUpdate/)
  assert.match(serviceSource, /app\.installUpdate/)
  assert.match(appUpdatePanelSource, /services\.checkAppUpdate\(\)/)
  assert.match(nativeManagerSource, /MessageDigest\.getInstance\("MD5"\)/)
  assert.match(nativeManagerSource, /FileProvider\.getUriForFile/)
  assert.match(bridgeSource, /"app\.downloadUpdate"/)
  assert.match(bridgeSource, /"app\.installUpdate"/)
  assert.equal(resolveUpgradeCapability('board').code, 'BOARD_FIRMWARE_UPGRADE_AVAILABLE')
})

test('upgrade pages contain no simulated success or selectable fake catalog flow', () => {
  for (const source of [adminSource, engineeringSource]) {
    assert.doesNotMatch(source, /模拟升级流程完成/)
    assert.doesNotMatch(source, /阻塞码：/)
    assert.doesNotMatch(source, /后台指令驱动/)
    assert.match(source, /resolveUpgradeCapability/)
    assert.match(source, /AppUpdatePanel/)
  }
  assert.match(adminSource, /硬件版本信息暂不可用/)
  assert.match(engineeringSource, /硬件版本信息暂不可用/)
})

test('firmware catalog service returns empty when no MQTT firmwareUpgrade push is pending', () => {
  assert.doesNotMatch(serviceSource, /UPGRADE_CATALOG_NOT_DEFINED/)
  assert.match(serviceSource, /getFirmwareUpgrade/)
})

test('board firmware closes download, serial transfer, cancellation and status reporting without fake hardware success', () => {
  assert.match(serviceSource, /\/api\/v1\/upgrade\/status/)
  assert.match(serviceSource, /serial\.firmwareUpgrade/)
  assert.match(serviceSource, /serial\.cancelFirmwareUpgrade/)
  assert.match(serviceSource, /hardwareVerified:\s*false/)
  assert.match(serviceSource, /TRANSMITTED/)
  assert.doesNotMatch(serviceSource, /downloadUrl\.split/)
  assert.doesNotMatch(serviceSource, /firmware upgrade queued/)
  assert.doesNotMatch(serviceSource, /firmwareId/)
  assert.match(adminSource, /待真机验证/)
  assert.match(engineeringSource, /待真机验证/)
})
