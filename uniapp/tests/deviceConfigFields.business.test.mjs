import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildBootstrapServerUrl,
  MQTT_CONNECTION_CONFIG_FIELDS,
  MQTT_TIMING_DEFAULTS,
  normalizeBootstrapServerUrl,
  normalizeMqttTimingConfig,
  SERVER_INPUT_DEFAULTS,
  usesMqttConnection
} from '../src/constants/config.js'

test('测试环境启动地址使用已验证的 HTTPS 入口', () => {
  assert.deepEqual(SERVER_INPUT_DEFAULTS, {
    host: 'card-test.quyohui.com',
    port: '443'
  })
  assert.equal(
    buildBootstrapServerUrl(SERVER_INPUT_DEFAULTS.host, SERVER_INPUT_DEFAULTS.port),
    'https://card-test.quyohui.com'
  )
})

test('旧测试地址缓存迁移到 HTTPS，其他 HTTP 地址保持不变', () => {
  assert.equal(
    normalizeBootstrapServerUrl('http://card-test.quyohui.com:80'),
    'https://card-test.quyohui.com'
  )
  assert.equal(
    normalizeBootstrapServerUrl('http://192.168.1.100:8800'),
    'http://192.168.1.100:8800'
  )
  assert.equal(
    buildBootstrapServerUrl('https://gateway.example.com', 8443),
    'https://gateway.example.com:8443'
  )
})

test('MQTT timing fields use the documented config defaults', () => {
  assert.deepEqual(MQTT_TIMING_DEFAULTS, {
    mqttHeartbeatInterval: 60000,
    mqttReconnectInitialInterval: 1000,
    mqttReconnectMaxInterval: 60000
  })
  assert.deepEqual(normalizeMqttTimingConfig(), MQTT_TIMING_DEFAULTS)
})

test('MQTT timing fields retain positive integer server values', () => {
  assert.deepEqual(normalizeMqttTimingConfig({
    mqttHeartbeatInterval: 45000,
    mqttReconnectInitialInterval: 1500,
    mqttReconnectMaxInterval: 90000
  }), {
    mqttHeartbeatInterval: 45000,
    mqttReconnectInitialInterval: 1500,
    mqttReconnectMaxInterval: 90000
  })
})

test('MQTT timing fields normalize editable numeric strings and reject invalid values', () => {
  assert.deepEqual(normalizeMqttTimingConfig({
    mqttHeartbeatInterval: '30000',
    mqttReconnectInitialInterval: 0,
    mqttReconnectMaxInterval: 'invalid'
  }), {
    mqttHeartbeatInterval: 30000,
    mqttReconnectInitialInterval: 1000,
    mqttReconnectMaxInterval: 60000
  })
})

test('HTTP-only mode excludes only MQTT connection-specific config fields', () => {
  assert.deepEqual(MQTT_CONNECTION_CONFIG_FIELDS, [
    'mqttHost',
    'mqttPort',
    'mqttReconnectInitialInterval',
    'mqttReconnectMaxInterval'
  ])
  assert.equal(usesMqttConnection('HTTP'), false)
  assert.equal(usesMqttConnection('MQTT'), true)
  assert.equal(usesMqttConnection('BOTH'), true)
})
