import assert from 'node:assert/strict'
import test from 'node:test'

import { assertMqttDispatchAccepted } from '../src/services/mqttDispatch.js'

test('accepts the documented native MQTT result and the formal Mock boolean result', () => {
  const nativeResult = { sent: true, msgId: 'msg-1' }
  assert.equal(assertMqttDispatchAccepted(nativeResult, 'syncUserResp'), nativeResult)
  assert.equal(assertMqttDispatchAccepted(true, 'syncUserResp'), true)
})

test('rejects ambiguous or negative MQTT dispatch results so outbox delivery can retry', () => {
  for (const result of [null, undefined, false, { sent: false }, {}]) {
    assert.throws(
      () => assertMqttDispatchAccepted(result, 'syncUserResp'),
      (error) => error?.code === 'MQTT_SEND_NOT_ACCEPTED' && /syncUserResp/.test(error.message)
    )
  }
})
