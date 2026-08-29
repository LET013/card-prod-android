import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('../src/pages/card-status/card-status.vue', import.meta.url), 'utf8')

test('reuses the mounted slot projection before reading SQLite', () => {
  assert.match(source, /class="status-grid-stage"/)
  assert.match(source, /const hasUsableSlotSnapshot = \(\) =>/)
  assert.match(source, /if \(hasUsableSlotSnapshot\(\)\) return\s+services\.loadCachedSlots\(\)/s)
  assert.doesNotMatch(source, /appState\.slots\.some\(\(slot\) => slot\?\.fresh\)/)
})

test('defers non-critical page work until after the first paint trace', () => {
  const firstPaint = source.indexOf("logCardStatusEntryTrace('first-paint'")
  const audit = source.indexOf('services.recordAuditEvent')
  const cache = source.indexOf('services.loadCachedSlots')
  assert.ok(firstPaint >= 0)
  assert.ok(audit > firstPaint)
  assert.ok(cache > firstPaint)
})
