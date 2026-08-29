import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('home renders the configured slot grid immediately and hydrates cached slots without blocking it', async () => {
  const homeSource = await readSource('src/pages/index/index.vue')

  assert.match(homeSource, /<CabinetSlotGrid[\s\S]*?:slots="displaySlots"/)
  assert.doesNotMatch(homeSource, /CabinetSlotSkeleton|slotGridVisible|slotSkeletonVisible|revealSlotGrid/)
  assert.match(homeSource, /const restoreCachedSlotsWhenSerialUnavailable = async \(\) => \{[\s\S]*?services\.loadCachedSlots\(\),[\s\S]*?services\.getSerialStatus\(\)/)
  assert.match(homeSource, /restoreCachedSlotsWhenSerialUnavailable\(\)\.catch\(\(\) => \{\}\)/)
  assert.doesNotMatch(homeSource, /Promise\.race|SLOT_SKELETON_MAX_WAIT_MS|replaceSlotsProjection/)
  assert.doesNotMatch(homeSource, /services\.loadSettings\(/)
  assert.doesNotMatch(homeSource, /replaceSettingsProjection/)
})
