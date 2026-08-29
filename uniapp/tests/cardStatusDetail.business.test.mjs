import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const sourceUrl = new URL('../src/pages/card-status/card-status.vue', import.meta.url)

test('opens slot detail from the selected slot number without waiting for a cabinet query', async () => {
  const source = await readFile(sourceUrl, 'utf8')
  const openSlotSource = source.slice(source.indexOf('const openSlot ='), source.indexOf('const closeSlot ='))

  assert.match(source, /const selectedSlotNumber = ref\(0\)/)
  assert.match(source, /const selectedSlot = computed\(\(\) => getSlotProjection\(selectedSlotNumber\.value\)\)/)
  assert.match(openSlotSource, /selectedSlotNumber\.value = slotNumber/)
  assert.doesNotMatch(openSlotSource, /await|querySlot|slotsSnapshot|loadCachedSlots/)
})

test('emits local-only sanitized click-to-first-paint diagnostics', async () => {
  const source = await readFile(sourceUrl, 'utf8')

  assert.match(source, /\[slot-modal\] trace=\$\{traceId\} phase=\$\{phase\} slot=\$\{slotNumber\} elapsedMs=/)
  assert.match(source, /'first-paint'/)
  assert.match(source, /nextTick\(\(\) => \{[\s\S]*?requestPaintFrame\(\(\) => requestPaintFrame/)
  assert.doesNotMatch(source, /\[slot-modal\][\s\S]*?(password|cardNo|cardNumber|token|face)/i)
})
