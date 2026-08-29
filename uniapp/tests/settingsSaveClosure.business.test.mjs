import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('device config save validates transport and backend business success before reporting remoteSaved', async () => {
  const source = await readSource('src/services/index.js')
  const saveStart = source.indexOf('async function saveSettings(settings)')
  const saveEnd = source.indexOf('/**\n * 保存用户输入的启动配置', saveStart)
  const saveSource = source.slice(saveStart, saveEnd)

  assert.match(saveSource, /assertHttpSuccess\(remote, '保存设备配置'\)/)
  assert.match(saveSource, /assertBackendSuccess\(envelope, '保存设备配置', \{ requireCode: true \}\)/)
  assert.match(saveSource, /assertSavedSlotLayout\(normalized, remoteData\)/)
  assert.match(saveSource, /localStore\.saveConfigDraft\(saved\)/)
  assert.match(saveSource, /replaceSettingsProjection\(saved\)/)
  assert.match(saveSource, /restartRequired: nativeRuntimeChanges\.length > 0/)
  assert.ok(saveSource.indexOf('assertBackendSuccess') < saveSource.indexOf('remoteSaved: true'))
  assert.ok(saveSource.indexOf('assertSavedSlotLayout') < saveSource.indexOf('localStore.saveRuntimeConfig(saved)'))
})

test('device config UI distinguishes local draft, remote failure and restart-required success', async () => {
  const source = await readSource('src/components/DeviceConfigPanel.vue')

  assert.match(source, /后台未确认卡槽布局，本机未应用；请检查后台配置后重试/)
  assert.match(source, /通信和串口参数重启应用后生效/)
  assert.doesNotMatch(source, /result\?\.remoteSaved \? '配置已保存并同步到服务器' : '配置已保存到本机'/)
})

test('device code and app channel are read-only system values in device settings', async () => {
  const source = await readSource('src/components/DeviceConfigPanel.vue')

  assert.match(source, /设备编号<\/text><text class="config-readonly">\{\{ displayDeviceCode \}\}/)
  assert.match(source, /由系统注册分配，不允许手动修改/)
  assert.match(source, /APP渠道<\/text><text class="config-readonly">\{\{ appChannelLabel \}\}/)
  assert.doesNotMatch(source, /v-model="form\.(deviceCode|channelId)"/)
})

test('device settings exposes the documented vertical and horizontal slot ordering beside serial polling', async () => {
  const source = await readSource('src/components/DeviceConfigPanel.vue')

  assert.match(source, /组内卡位排序方向/)
  assert.match(source, /form\.slotSortDirection === 'VERTICAL'/)
  assert.match(source, /form\.slotSortDirection === 'HORIZONTAL'/)
  assert.match(source, /slotSortDirection: 'HORIZONTAL'/)
  assert.match(source, /normalizeSlotSortDirection\(normalized\.slotSortDirection/)
  assert.ok(source.indexOf('串口轮询开关') < source.indexOf('组内卡位排序方向'))
  assert.doesNotMatch(source, /slotSortDirection: 'ASC'/)
})
