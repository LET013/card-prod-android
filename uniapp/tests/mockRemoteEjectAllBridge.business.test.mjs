import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const mqttSimSource = fs.readFileSync(new URL('../src/mock/mqtt-sim.js', import.meta.url), 'utf8')
const bridgeSource = fs.readFileSync(new URL('../src/mock/bridge.js', import.meta.url), 'utf8')
const mainSource = fs.readFileSync(new URL('../src/main.js', import.meta.url), 'utf8')

test('formal Mock preserves the complete downstream envelope and original msgId', () => {
  assert.match(mqttSimSource, /sendEnvelope\(cmd, data, msgPrefix = 'mq', requestedMsgId = ''\)/)
  assert.match(mqttSimSource, /String\(requestedMsgId \|\| ''\)\.trim\(\) \|\| generateMsgId\(msgPrefix\)/)
  assert.match(mqttSimSource, /onDownstream\(data\)/)
  assert.doesNotMatch(mqttSimSource, /onDownstream\(data\.cmd/)
})

test('formal Mock routes business commands through mqtt.message and sends signed envelopes', () => {
  assert.match(bridgeSource, /emit\('mqtt\.message', message\)/)
  assert.match(bridgeSource, /emit\(`mqtt\.\$\{cmd\}`, message\?\.data \?\? \{\}\)/)
  assert.match(bridgeSource, /case 'send': return sendMqttEnvelope\(payload\)/)
  assert.match(bridgeSource, /sendEnvelope\(cmd, responseData, 'mq', msgId\)/)
  assert.doesNotMatch(bridgeSource, /case 'send': return publishMqtt/)
})

test('card projection accepts both native object snapshots and Mock array snapshots', () => {
  assert.match(mainSource, /const slots = Array\.isArray\(data\) \? data : data\?\.slots/)
  assert.match(mainSource, /replaceSlotsProjection\(slots\)/)
  assert.match(mainSource, /cacheSlotsSnapshot\(slots, 'SERIAL', true\)/)
})
