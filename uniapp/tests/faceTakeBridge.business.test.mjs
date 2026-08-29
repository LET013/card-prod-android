import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const bridgeUrl = new URL(
  '../../app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java',
  import.meta.url
)

test('closes the native face controller before immediately emitting a successful recognition', async () => {
  const source = await readFile(bridgeUrl, 'utf8')
  const callbackStart = source.indexOf('public void onFaceVerified(String faceId, float score)')
  const callbackEnd = source.indexOf('public void onCancelled()', callbackStart)
  const callback = source.slice(callbackStart, callbackEnd)

  assert.ok(callbackStart >= 0)
  assert.doesNotMatch(source, /minRecognitionDuration|defer emit\+hide/)
  assert.match(callback, /cancelCurrentFaceOperation\(\);[\s\S]*emit\("face\.recognized", data\);/)
})

test('exposes the current Android serial slot snapshot as a read-only bridge action', async () => {
  const source = await readFile(bridgeUrl, 'utf8')

  assert.match(source, /case "serial\.slotsSnapshot":[\s\S]*handleSerialSlotsSnapshot\(requestId\);/)
  assert.match(source, /getSlotStateManager\(\)\.getSnapshot\(\)/)
  assert.match(source, /put\("capturedAt", System\.currentTimeMillis\(\)\)[\s\S]*put\("slots", slots\)/)
})
