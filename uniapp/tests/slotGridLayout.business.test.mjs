import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  resolveSlotGridCardMetrics,
  resolveSlotGridLayout,
  resolveSlotGridPosition
} from '../src/components/slotGridLayout.js'

test('a 100-slot vertical cabinet uses five columns with slots 1-50 above the divider', () => {
  const layout = resolveSlotGridLayout(100, 10, 'VERTICAL')

  assert.deepEqual(layout, { total: 100, groupSize: 10, columns: 5, direction: 'VERTICAL' })
  assert.deepEqual(resolveSlotGridPosition(0, layout), { gridColumn: 1, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(10, layout), { gridColumn: 2, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(49, layout), { gridColumn: 5, gridRow: 10 })
  assert.deepEqual(resolveSlotGridPosition(50, layout), { gridColumn: 1, gridRow: 12 })
  assert.deepEqual(resolveSlotGridPosition(99, layout), { gridColumn: 5, gridRow: 21 })
})

test('a horizontal 100-slot cabinet keeps the physical upper and lower halves', () => {
  const layout = resolveSlotGridLayout(100, 10, 'HORIZONTAL')
  const metrics = resolveSlotGridCardMetrics(layout, { width: 1920, height: 760 })

  assert.deepEqual(layout, { total: 100, groupSize: 10, columns: 5, direction: 'HORIZONTAL' })
  assert.deepEqual(resolveSlotGridPosition(0, layout), { gridColumn: 1, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(10, layout), { gridColumn: 2, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(49, layout), { gridColumn: 5, gridRow: 10 })
  assert.deepEqual(resolveSlotGridPosition(50, layout), { gridColumn: 1, gridRow: 12 })
  assert.deepEqual(resolveSlotGridPosition(99, layout), { gridColumn: 5, gridRow: 21 })
  assert.equal(metrics.isCabinetHalfSplit, true)
})

test('card dimensions fill the visible cabinet region without hiding configured slots', () => {
  const layout = resolveSlotGridLayout(10, 5, 'VERTICAL')
  const compact = resolveSlotGridCardMetrics(layout, { width: 540, height: 620 })
  const large = resolveSlotGridCardMetrics(layout, { width: 720, height: 1000 })

  assert.deepEqual(layout, { total: 10, groupSize: 5, columns: 2, direction: 'VERTICAL' })
  assert.equal(compact.cardWidth, (540 - compact.padding * 2 - compact.gap) / 2)
  assert.equal(compact.cardHeight, (620 - compact.padding * 2 - compact.gap * 4) / 5)
  assert.ok(large.cardWidth > compact.cardWidth)
  assert.ok(large.cardHeight > compact.cardHeight)
  assert.equal(compact.gridWidth, compact.cardWidth * 2 + compact.gap + compact.padding * 2)
  assert.equal(compact.gridHeight, compact.cardHeight * 5 + compact.gap * 4 + compact.padding * 2)
})

test('a configured 100-slot cabinet fills the two physical display halves', () => {
  const layout = resolveSlotGridLayout(100, 10, 'VERTICAL')
  const metrics = resolveSlotGridCardMetrics(layout, { width: 1920, height: 760 })

  assert.equal(metrics.rows, 20)
  assert.equal(metrics.isCabinetHalfSplit, true)
  assert.equal(metrics.gridWidth, 1920)
  assert.equal(metrics.gridHeight, 760)
  assert.ok(metrics.cardWidth > 0)
  assert.ok(metrics.cardHeight > 0)
})

test('50- and 60-slot cabinets keep a normal grid without a center divider', () => {
  for (const totalSlots of [50, 60]) {
    const layout = resolveSlotGridLayout(totalSlots, 10, 'VERTICAL')
    const metrics = resolveSlotGridCardMetrics(layout, { width: 1280, height: 760 })

    assert.equal(metrics.isCabinetHalfSplit, false)
    assert.equal(metrics.halfDividerGap, 0)
    assert.equal(resolveSlotGridPosition(totalSlots - 1, layout).gridRow, 10)
  }
})

test('a 120-slot vertical cabinet uses six columns with ten slots above and below the divider', () => {
  const layout = resolveSlotGridLayout(120, 10, 'VERTICAL')

  assert.deepEqual(layout, { total: 120, groupSize: 10, columns: 6, direction: 'VERTICAL' })
  assert.deepEqual(resolveSlotGridPosition(0, layout), { gridColumn: 1, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(50, layout), { gridColumn: 6, gridRow: 1 })
  assert.deepEqual(resolveSlotGridPosition(59, layout), { gridColumn: 6, gridRow: 10 })
  assert.deepEqual(resolveSlotGridPosition(60, layout), { gridColumn: 1, gridRow: 12 })
  assert.deepEqual(resolveSlotGridPosition(119, layout), { gridColumn: 6, gridRow: 21 })

  const metrics = resolveSlotGridCardMetrics(layout, { width: 1920, height: 760 })
  assert.equal(metrics.rows, 20)
  assert.equal(metrics.isCabinetHalfSplit, true)
  assert.ok(metrics.halfDividerGap >= 10)
  assert.ok(metrics.halfDividerLineHeight >= 5)
})

test('home and card-status pages render the shared cabinet grid', async () => {
  const [homeSource, statusSource, gridSource, slotCardSource, slotDetailSource] = await Promise.all([
    readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/card-status/card-status.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/CabinetSlotGrid.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/SlotCard.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/SlotDetailModal.vue', import.meta.url), 'utf8')
  ])

  for (const source of [homeSource, statusSource]) {
    assert.match(source, /<CabinetSlotGrid/)
    assert.match(source, /:group-size="appState\.settings\.groupSize \|\| appState\.settings\.singleGroupCount"/)
    assert.match(source, /:sort-direction="appState\.settings\.slotSortDirection"/)
  }
  assert.doesNotMatch(statusSource, /const COLUMNS|orderedSlots|repeat\(6,/)
  assert.match(statusSource, /const FEEDBACK_CLOSE_DELAY_MS = 1600/)
  assert.match(statusSource, /onBeforeUnmount\(\(\) => \{\s*cardStatusMounted = false\s*clearFeedbackCloseTimer\(\)/)
  assert.match(statusSource, /finally \{\s*unlocking\.value = false\s*\/\/ 先展示成功或失败结果[\s\S]*?closeSlotAfterFeedback\(\)/)
  assert.match(slotDetailSource, /:disabled="unlocking \|\| Boolean\(feedback\)"/)
  assert.doesNotMatch(homeSource, /slotGridStyle|slotPosition/)
  assert.match(homeSource, /:active-slot-number="slotOperation\.slotNumber"/)
  assert.match(homeSource, /setTimeout\(clearSlotOperationEffect, 3000\)/)
assert.match(gridSource, /:operation-effect="effectForSlot\(slot\)"/)
assert.match(gridSource, /resolveSlotGridCardMetrics/)
assert.match(gridSource, /ResizeObserver/)
assert.match(gridSource, /uni\.getWindowInfo/)
assert.match(gridSource, /uni\.createSelectorQuery/)
assert.doesNotMatch(gridSource, /<scroll-view/)
assert.doesNotMatch(gridSource, /scroll-x|scroll-y/)
assert.match(gridSource, /repeat\(10, minmax\(0, 1fr\)\) \$\{cardMetrics\.value\.halfDividerGap\}px repeat\(10, minmax\(0, 1fr\)\)/)
assert.match(gridSource, /'--slot-card-height': `\$\{cardMetrics\.value\.cardHeight\}px`/)
assert.match(gridSource, /const showCabinetHalfDivider = computed\(\(\) => \([\s\S]*?cardMetrics\.value\.isCabinetHalfSplit/)
assert.match(gridSource, /cabinet-slot-grid--split-halves::before/)
assert.match(gridSource, /top: 50%/)
assert.match(gridSource, /left: 0/)
assert.match(gridSource, /right: 0/)
assert.match(gridSource, /height: var\(--slot-half-divider-line-height, 6px\)/)
assert.doesNotMatch(slotCardSource, /卡号 \{\{ cardNumber \}\}/)
assert.doesNotMatch(slotCardSource, /slot-summary-hint|--slot-card-number-size/)
assert.match(slotCardSource, /line-height:1\.25/)
  assert.doesNotMatch(slotCardSource, /slot-operation--targeted/)
  assert.doesNotMatch(slotCardSource, /slot-operation--dispatched/)
  assert.match(slotCardSource, /slot-operation--success/)
  assert.match(slotCardSource, /slot-operation--failure/)
})
