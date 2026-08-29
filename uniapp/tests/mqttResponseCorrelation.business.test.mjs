import assert from 'node:assert/strict'
import test from 'node:test'

import { selectMqttResponseCorrelation } from '../src/services/mqttResponseCorrelation.js'

test('uses the documented response msgId when it matches the pending uplink', () => {
  const result = selectMqttResponseCorrelation(
    ['statusReportResp:status-1'],
    { cmd: 'statusReportResp', msgId: 'status-1', data: { code: 0 } }
  )

  assert.deepEqual(result, {
    key: 'statusReportResp:status-1',
    requestMsgId: 'status-1',
    mode: 'EXACT'
  })
})

test('recovers the original uplink msgId from the backend duplicate response', () => {
  const result = selectMqttResponseCorrelation(
    ['statusReportResp:status_123_random_000001'],
    {
      cmd: 'statusReportResp',
      msgId: 'server-generated-response-id',
      data: {
        code: -1,
        msg: '消息去重拦截: msgId=status_123_random_000001'
      }
    }
  )

  assert.equal(result?.requestMsgId, 'status_123_random_000001')
  assert.equal(result?.mode, 'DUPLICATE_MSG')
})

test('correlates a backend-generated response UUID only to the sole waiter for that command', () => {
  const result = selectMqttResponseCorrelation(
    ['statusReportResp:status-2', 'cardEventResp:card-1'],
    { cmd: 'statusReportResp', msgId: 'server-uuid', data: { code: 0 } }
  )

  assert.equal(result?.key, 'statusReportResp:status-2')
  assert.equal(result?.mode, 'SOLE_PENDING_COMMAND')
})

test('does not guess when multiple requests await the same response command', () => {
  const result = selectMqttResponseCorrelation(
    ['statusReportResp:status-1', 'statusReportResp:status-2'],
    { cmd: 'statusReportResp', msgId: 'server-uuid', data: { code: 0 } }
  )

  assert.equal(result, null)
})
