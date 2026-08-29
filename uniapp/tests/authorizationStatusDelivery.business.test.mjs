import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const serviceSource = fs.readFileSync(new URL('../src/services/index.js', import.meta.url), 'utf8')
const mainSource = fs.readFileSync(new URL('../src/main.js', import.meta.url), 'utf8')
const bridgeSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java', import.meta.url), 'utf8')
const bootstrapSource = fs.readFileSync(new URL('../../app/src/main/java/com/xingyao/card/core/bootstrap/DeviceBootstrapManager.java', import.meta.url), 'utf8')

test('manual employee enable and disable sends only employeeId and action through saveEmployee', () => {
  const actionStart = serviceSource.indexOf('async function setEmployeeAuthorization')
  const actionEnd = serviceSource.indexOf('async function listEmployeeFaces', actionStart)
  const actionSource = serviceSource.slice(actionStart, actionEnd)

  assert.match(actionSource, /saveEmployee\(\{[\s\S]*employeeId: id,[\s\S]*action: authorized \? 'enable' : 'disable'[\s\S]*\}\)/)
  assert.match(serviceSource, /sendMqttAndWaitForResponse\('saveEmployee', request, 'saveEmployeeResp', msgId\)/)
  assert.doesNotMatch(serviceSource, /authStatusChange/)
  assert.match(serviceSource, /setEmployeeAuthorization, deleteEmployee/)
})

test('business MQTT calls require server authentication instead of broker connectivity alone', () => {
  assert.match(bootstrapSource, /public boolean isMqttAuthenticated\(\) \{ return mqttAuthenticated; \}/)
  assert.match(bridgeSource, /result\.put\("authenticated", bootstrapManager != null && bootstrapManager\.isMqttAuthenticated\(\)\)/)
  assert.match(bridgeSource, /emit\("mqtt\.transportConnected", data\)/)
  assert.match(bridgeSource, /"MQTT_NOT_AUTHENTICATED"/)
  assert.match(bridgeSource, /mqttConnected = mqttClient\.isConnected\(\)[\s\S]{0,120}bootstrapManager\.isMqttAuthenticated\(\)/)
  assert.match(bridgeSource, /emit\("mqtt\.connected", data\);[\s\S]{0,80}emitDeviceInfo\(null\)/)
  assert.match(bridgeSource, /generation == lastEmittedMqttAuthenticationGeneration/)
  assert.match(serviceSource, /function isMqttBusinessReady\(status\) \{[\s\S]*status\?\.connected === true && status\?\.authenticated === true/)
  assert.ok((serviceSource.match(/isMqttBusinessReady\(/g) || []).length >= 10)
})

test('employee confirmation is rendered above the detail modal', () => {
  const pageSource = fs.readFileSync(new URL('../src/pages/employees/employees.vue', import.meta.url), 'utf8')
  assert.match(pageSource, /<Teleport to="body">[\s\S]*authorization-confirm-mask/)
  assert.match(pageSource, /z-index:10020/)
  assert.match(pageSource, /services\.setEmployeeAuthorization\(/)
})

test('native device authorization events update only the device authorization projection', () => {
  assert.match(mainSource, /nativeBridge\.on\('device\.authorizationChanged'/)
  assert.match(mainSource, /appState\.runtime\.deviceAuthorization = \{ \.\.\.data/)
  assert.doesNotMatch(mainSource, /reportDeviceAuthorizationChange/)
  assert.doesNotMatch(mainSource, /flushPendingAuthorizationStatusChanges/)
})

test('runtime refresh queries employee authorization without mapping it to device activation', () => {
  const runtimeStart = serviceSource.indexOf('async function getRuntime')
  const runtimeEnd = serviceSource.indexOf('/** 获取串口状态 */', runtimeStart)
  const runtimeSource = serviceSource.slice(runtimeStart, runtimeEnd)
  assert.match(runtimeSource, /httpGet\('\/api\/v1\/device\/auth\/status'\)/)
  assert.doesNotMatch(runtimeSource, /setEmployeeAuthorization\(/)
  assert.doesNotMatch(runtimeSource, /authorized:\s*deviceInfo\.activated/)
})
