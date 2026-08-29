import assert from 'node:assert/strict'
import test from 'node:test'
import { canonicalizeRemoteDeviceConfigLayout } from '../src/services/deviceConfigLayout.js'

test('remote legacy totalCount replaces a stale local totalSlots during config merge', () => {
  const cached = { totalSlots: 120, totalCount: 120, groupSize: 10 }
  const remote = canonicalizeRemoteDeviceConfigLayout({ totalCount: 100, singleGroupCount: 20 })

  assert.deepEqual(remote, {
    totalCount: 100,
    singleGroupCount: 20,
    totalSlots: 100,
    groupSize: 20
  })
  assert.equal({ ...cached, ...remote }.totalSlots, 100)
})

test('remote canonical layout fields remain the source of truth', () => {
  assert.deepEqual(canonicalizeRemoteDeviceConfigLayout({
    totalSlots: 100,
    totalCount: 120,
    groupSize: 10,
    singleGroupCount: 20,
    slotLayoutDirection: 'VERTICAL'
  }), {
    totalSlots: 100,
    totalCount: 100,
    groupSize: 10,
    singleGroupCount: 10,
    slotLayoutDirection: 'VERTICAL',
    slotSortDirection: 'VERTICAL'
  })
})
