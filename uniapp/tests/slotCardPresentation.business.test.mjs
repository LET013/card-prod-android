import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { SLOT_STATUS_META } from '../src/constants/app.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('home slots are display-only and never open the slot detail modal', async () => {
  const source = await readSource('src/pages/index/index.vue')

  assert.doesNotMatch(source, /SlotDetailModal/)
  assert.doesNotMatch(source, /@slot-click=/)
  assert.doesNotMatch(source, /openSlotDetail|selectedSlotNumber/)
})

test('card status uses the shared compact grid and preserves maintenance actions', async () => {
  const [pageSource, gridSource, cardSource] = await Promise.all([
    readSource('src/pages/card-status/card-status.vue'),
    readSource('src/components/CabinetSlotGrid.vue'),
    readSource('src/components/SlotCard.vue')
  ])

  assert.match(pageSource, /<CabinetSlotGrid[\s\S]*?\binteractive\b/)
  assert.doesNotMatch(pageSource, /<CabinetSlotGrid[\s\S]*?\bdetailed\b/)
  assert.match(pageSource, /@slot-click="openSlot"/)
  assert.match(pageSource, /:allow-unlock="canUnlock"/)
  assert.doesNotMatch(gridSource, /:detailed=/)
  assert.doesNotMatch(cardSource, /卡号 \{\{ cardNumber \}\}/)
  assert.doesNotMatch(cardSource, /slot-detail-grid/)
})

test('slot colors follow the cabinet indicator semantics', () => {
  assert.equal(SLOT_STATUS_META.OCCUPIED.color, '#F97316')
  assert.equal(SLOT_STATUS_META.CHARGING.color, '#EF4444')
  assert.equal(SLOT_STATUS_META.FULL.color, '#16A34A')
  assert.equal(SLOT_STATUS_META.CHARGING_FAULT.color, '#64748B')
  assert.equal(SLOT_STATUS_META.COMMUNICATION_FAULT.color, '#475569')
  assert.equal(SLOT_STATUS_META.EMPTY.color, '#E7EBF0')
})
