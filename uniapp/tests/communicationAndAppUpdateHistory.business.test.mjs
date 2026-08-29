import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const readSource = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('communication refresh reads the current native connection and presents newest logs first', async () => {
  const [serviceSource, adminSource] = await Promise.all([
    readSource('../src/services/index.js'),
    readSource('../src/pages/admin/admin.vue')
  ])

  assert.match(serviceSource, /return mqttCommLogBuffer\.slice\(start\)\.reverse\(\)/)
  assert.match(adminSource, /const mqttConnected = await services\.refreshMqttConnectionProjection\(\)/)
  assert.match(adminSource, /applyMqttStatus\(\{ mqttConnected, timestamp: Date\.now\(\) \}\)/)
  assert.match(adminSource, /loadCommLogs\(\)[\s\S]*?applyMqttStatus\(await services\.getRuntime/)
})

test('manual update checks persist both successful and failed checks in operation history', async () => {
  const [panelSource, serviceSource, historySource, storeSource] = await Promise.all([
    readSource('../src/components/AppUpdatePanel.vue'),
    readSource('../src/services/index.js'),
    readSource('../src/pages/feature/feature.vue'),
    readSource('../src/services/localStore.js')
  ])

  assert.match(panelSource, /checkAppUpdate\(\{ source: 'MANUAL' \}\)/)
  assert.match(serviceSource, /operationType: 'APP_UPDATE_CHECK'[\s\S]*?checkOutcome: versionInfo \? 'UPDATE_AVAILABLE' : 'NO_UPDATE'/)
  assert.match(serviceSource, /operationType: 'APP_UPDATE_CHECK'[\s\S]*?state: 'FAILED'[\s\S]*?rawError/)
  assert.match(historySource, /APP_UPDATE_CHECK:'手动检查更新'/)
  assert.match(storeSource, /APP_UPDATE_CHECK: '手动检查更新'/)
})
