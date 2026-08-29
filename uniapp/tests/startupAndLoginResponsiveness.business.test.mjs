import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseBootstrapServerUrl } from '../src/constants/config.js'

const readSource = (relativePath) => readFile(new URL(`../${relativePath}`, import.meta.url), 'utf8')

test('bootstrap server URL preserves the first-start host and effective port', () => {
  assert.deepEqual(parseBootstrapServerUrl('https://card-test.quyohui.com'), {
    serverUrl: 'https://card-test.quyohui.com',
    host: 'card-test.quyohui.com',
    port: 443
  })
  assert.equal(parseBootstrapServerUrl('http://10.0.0.8:8080').port, 8080)
})

test('startup enters home from the completed bootstrap config without waiting for a duplicate HTTP configuration request', async () => {
  const [splashSource, serviceSource] = await Promise.all([
    readSource('src/pages/splash/splash.vue'),
    readSource('src/services/index.js')
  ])
  const navigationIndex = splashSource.indexOf("uni.reLaunch({ url: '/pages/index/index' })")
  const identitySyncIndex = splashSource.indexOf("const identitySync = services.env === 'release'")
  assert.match(splashSource, /RUNNING 前已收到原生层发布的完整 config/)
  assert.match(splashSource, /services\.loadSettings\(\{ transport: 'HTTP' \}\)\.catch/)
  assert.match(serviceSource, /async function syncRuntimeConfigFromServer\(\)[\s\S]*?requestDeviceConfig\(\{ transport: 'HTTP' \}\)/)
  assert.match(splashSource, /identitySync\.catch\(/)
  assert.doesNotMatch(splashSource, /await identitySync/)
  assert.doesNotMatch(splashSource, /setTimeout\(\(\) => navigateToMain\(\), 500\)/)
  assert.ok(navigationIndex >= 0)
  assert.ok(identitySyncIndex > navigationIndex)
  const settingsRefreshIndex = splashSource.indexOf("services.loadSettings({ transport: 'HTTP' })")
  assert.ok(settingsRefreshIndex > navigationIndex)
  assert.match(splashSource, /uni\.reLaunch\(\{ url: '\/pages\/index\/index' \}\)\s*setTimeout\(\(\) => \{/)
})

test('local login fast-paths known system credentials and exposes progress', async () => {
  const [storeSource, modalSource] = await Promise.all([
    readSource('src/services/localStore.js'),
    readSource('src/components/PasswordModal.vue')
  ])
  assert.match(storeSource, /preferredCredentialId = plain === DEFAULT_DEVELOPER_PASSWORD/)
  assert.match(storeSource, /await verifyPasswordHash\(plain, fastCredential\)/)
  assert.match(storeSource, /credentials\s*\.filter\(\(credential\) => credential !== fastCredential\)/)
  assert.match(storeSource, /SELECT permission_key, parent_key FROM local_permissions WHERE enabled = 1/)
  assert.match(storeSource, /expandPermission\(permissionKey, permissionGraph\)/)
  assert.match(modalSource, /const submitting = ref\(false\)/)
  assert.match(modalSource, /正在验证，请稍候/)
  assert.match(modalSource, /:disabled="submitting"/)
})

test('settings cache includes bootstrap and current runtime projections before remote refresh', async () => {
  const source = await readSource('src/services/index.js')
  assert.match(source, /localStore\.loadBootstrapConfig\(\)/)
  assert.match(source, /\.\.\.\(bootstrapConfig \|\| \{\}\),[\s\S]*\.\.\.\(appState\.settings \|\| \{\}\),[\s\S]*\.\.\.\(runtimeConfig \|\| \{\}\),[\s\S]*\.\.\.\(localDraft \|\| \{\}\)/)
})
