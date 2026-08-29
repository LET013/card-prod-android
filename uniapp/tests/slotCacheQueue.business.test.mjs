import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const projectRoot = new URL('../', import.meta.url)

test('ordinary slot projections enqueue cache writes without waiting for SQLite completion', async () => {
  const [serviceSource, mainSource] = await Promise.all([
    readFile(new URL('src/services/index.js', projectRoot), 'utf8'),
    readFile(new URL('src/main.js', projectRoot), 'utf8')
  ])

  assert.match(serviceSource, /function queueSlotSnapshot\(slot, source = 'SERIAL', fresh = true\)/)
  assert.match(serviceSource, /function queueSlotsSnapshot\(slots, source = 'LOCAL_OPERATION', fresh = false\)/)
  assert.match(serviceSource, /items\.forEach\(\(slot\) => queueSlotSnapshot\(slot, source, fresh\)\)/)
  assert.match(mainSource, /queueSlotsSnapshot\(slots, 'SERIAL', true\)/)
  assert.match(mainSource, /queueSlotSnapshot\(cached \|\| slot, 'SERIAL', true\)/)
  assert.doesNotMatch(mainSource, /cacheSlotSnapshot\(cached \|\| slot, 'SERIAL', true\)\.catch/)
})

test('cache signature does not write every voltage or current jitter immediately', async () => {
  const source = await readFile(new URL('src/services/index.js', projectRoot), 'utf8')
  const signatureStart = source.indexOf('function slotCacheSignature')
  const signatureEnd = source.indexOf('async function flushPendingSlotCache', signatureStart)
  const signature = source.slice(signatureStart, signatureEnd)

  assert.doesNotMatch(signature, /voltage|current|batteryPercent/)
})
