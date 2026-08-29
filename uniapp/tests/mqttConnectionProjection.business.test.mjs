import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('authenticated MQTT status is the only source for the online projection', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const start = source.indexOf('async function refreshMqttConnectionProjection()')
  const end = source.indexOf('async function mqttRegisterCmd', start)
  const projection = source.slice(start, end)

  assert.match(projection, /const mqttConnected = isMqttBusinessReady\(status\)/)
  assert.match(projection, /appState\.deviceInfo\.mqttConnected = mqttConnected/)
  assert.match(projection, /authenticated: status\?\.authenticated === true/)
})

test('startup and authenticated event both refresh the MQTT online projection', async () => {
  const main = await readFile(new URL('../src/main.js', import.meta.url), 'utf8')
  const home = await readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8')

  assert.match(main, /nativeBridge\.on\('mqtt\.connected'[\s\S]*?services\.refreshMqttConnectionProjection\(\)/)
  assert.match(main, /if \(!mqttOnline\) return/)
  assert.match(home, /await services\.refreshMqttConnectionProjection\(\)/)
})
