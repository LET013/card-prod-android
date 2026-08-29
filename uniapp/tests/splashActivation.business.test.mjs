import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const splashSource = await readFile(new URL('../src/pages/splash/splash.vue', import.meta.url), 'utf8')
const updatePanelSource = await readFile(new URL('../src/components/AppUpdatePanel.vue', import.meta.url), 'utf8')

test('activation screen has no client-side bypass into the main page', () => {
  assert.doesNotMatch(splashSource, /临时跳过|skipActivation|activation-btn\s+skip/)
})

test('activation screen keeps the documented verification action and waiting phase', () => {
  assert.match(splashSource, /services\.bootstrapActivate\(code\)/)
  assert.match(splashSource, /WAITING_ACTIVATION_CODE/)
  assert.match(splashSource, /请输入激活码/)
})

test('startup force update can download, verify and open the system installer', () => {
  assert.match(splashSource, /services\.downloadAppUpdate\(forcedVersionInfo\.value/)
  assert.match(splashSource, /services\.installAppUpdate\(forceUpdateOperationId\.value/)
  assert.match(splashSource, /BOOTSTRAP_FORCE/)
  assert.match(splashSource, /正在下载并校验更新包安全性/)
  assert.match(splashSource, /安装包校验已通过/)
  assert.match(updatePanelSource, /正在下载并校验更新包安全性/)
  assert.match(updatePanelSource, /安装包校验已通过/)
  assert.doesNotMatch(splashSource, /DEFAULT_APP_CHANNEL/)
  assert.match(splashSource, /channelLabel/)
  assert.match(splashSource, /@click="runForcedUpdate"/)
})

test('APP update screens label and scroll long release notes', () => {
  for (const source of [splashSource, updatePanelSource]) {
    assert.match(source, /版本更新日志：/)
    assert.match(source, /<scroll-view class="(?:force-)?release-notes(?:-content)?" scroll-y>/)
  }
})

test('startup errors stay on the splash page instead of entering device settings', () => {
  assert.doesNotMatch(splashSource, /navigateToStartupConfig/)
  assert.doesNotMatch(splashSource, /pages\/config\/config\$\{query\}/)
})

test('startup restores and projects the registered device code', () => {
  assert.match(splashSource, /services\.bootstrapDeviceInfo\(\)/)
  assert.match(splashSource, /syncDeviceInfo\(data\)/)
  assert.match(splashSource, /replaceDeviceInfoProjection\(/)
  assert.doesNotMatch(splashSource, /case 'REGISTERING':[\s\S]{0,160}deviceCode\.value\s*=\s*data\.deviceCode\s*\|\|\s*''/)
})

test('startup never displays or hardcodes the developer password', () => {
  assert.doesNotMatch(splashSource, /开发人员默认密码/)
  assert.doesNotMatch(splashSource, /DEFAULT_DEVELOPER_PASSWORD/)
  assert.doesNotMatch(splashSource, /initial-password-value/)
})
