<template>
  <view ref="gridHostRef" class="cabinet-slot-grid-host">
    <view
      class="cabinet-slot-grid"
      :class="{ 'cabinet-slot-grid--split-halves': showCabinetHalfDivider }"
      :style="gridStyle"
    >
      <SlotCard
        v-for="(slot, index) in orderedSlots"
        :key="slot.id || `slot-${slotNumberOf(slot) || index}`"
        :slot="slot"
        :style="slotStyle(index)"
        :interactive="interactive"
        :operation-effect="effectForSlot(slot)"
        @click="emit('slot-click', $event)"
      />
    </view>
  </view>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import SlotCard from '@/components/SlotCard.vue'
import {
  resolveSlotGridCardMetrics,
  resolveSlotGridLayout,
  resolveSlotGridPosition
} from '@/components/slotGridLayout.js'

const props = defineProps({
  slots: { type: Array, default: () => [] },
  groupSize: { type: [Number, String], default: 0 },
  sortDirection: { type: String, default: 'HORIZONTAL' },
  interactive: { type: Boolean, default: false },
  activeSlotNumber: { type: [Number, String], default: 0 },
  operationEffect: { type: String, default: '' },
  operationEffects: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['slot-click'])
const gridHostRef = ref(null)
let resizeObserver = null
let lastMeasuredViewport = { width: 0, height: 0 }

const readWindowViewport = () => {
  if (typeof uni === 'undefined') return { width: 0, height: 0 }
  try {
    const info = typeof uni.getWindowInfo === 'function'
      ? uni.getWindowInfo()
      : uni.getSystemInfoSync?.()
    return {
      width: Math.floor(Number(info?.windowWidth) || 0),
      height: Math.floor(Number(info?.windowHeight) || 0)
    }
  } catch (_) {
    return { width: 0, height: 0 }
  }
}

const initialViewport = () => (
  lastMeasuredViewport.width > 0 && lastMeasuredViewport.height > 0
    ? { ...lastMeasuredViewport }
    : readWindowViewport()
)

const viewport = ref(initialViewport())

const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address ?? 0)
// 只在卡位数量变化时制作浅副本以触发 uni-app 的 v-for 依赖；不再为每个批次排序。
const orderedSlots = computed(() => [...props.slots])
const layout = computed(() => resolveSlotGridLayout(
  orderedSlots.value.length,
  props.groupSize,
  props.sortDirection
))
const cardMetrics = computed(() => resolveSlotGridCardMetrics(layout.value, viewport.value))
// 100/120 槽柜固定为上下两半，中线只表达已确认的物理边界。
const showCabinetHalfDivider = computed(() => (
  cardMetrics.value.isCabinetHalfSplit
))
const gridStyle = computed(() => ({
  width: '100%',
  height: '100%',
  gridTemplateColumns: `repeat(${layout.value.columns}, minmax(0, 1fr))`,
  gridTemplateRows: cardMetrics.value.isCabinetHalfSplit
    ? `repeat(10, minmax(0, 1fr)) ${cardMetrics.value.halfDividerGap}px repeat(10, minmax(0, 1fr))`
    : `repeat(${cardMetrics.value.rows}, minmax(0, 1fr))`,
  gap: `${cardMetrics.value.gap}px`,
  padding: `${cardMetrics.value.padding}px`,
  '--slot-grid-padding': `${cardMetrics.value.padding}px`,
  '--slot-card-height': `${cardMetrics.value.cardHeight}px`,
  '--slot-card-padding': `${cardMetrics.value.cardPadding}px`,
  '--slot-number-size': `${cardMetrics.value.numberFontSize}px`,
  '--slot-status-size': `${cardMetrics.value.statusFontSize}px`,
  '--slot-status-mark-size': `${cardMetrics.value.statusMarkSize}px`,
  '--slot-half-divider-line-height': `${cardMetrics.value.halfDividerLineHeight}px`
}))
const slotStyle = (index) => resolveSlotGridPosition(index, layout.value)
const effectForSlot = (slot) => {
  const slotNumber = slotNumberOf(slot)
  const activeSlotNumber = Number(props.activeSlotNumber)
  if (activeSlotNumber > 0 && slotNumber === activeSlotNumber) return props.operationEffect
  const backgroundEffect = props.operationEffects?.[slotNumber]
  return typeof backgroundEffect === 'string'
    ? backgroundEffect
    : String(backgroundEffect?.effect || '')
}

const setViewport = ({ width, height } = {}) => {
  const nextWidth = Math.floor(Number(width) || 0)
  const nextHeight = Math.floor(Number(height) || 0)
  if (nextWidth <= 0 || nextHeight <= 0) return
  if (nextWidth !== viewport.value.width || nextHeight !== viewport.value.height) {
    viewport.value = { width: nextWidth, height: nextHeight }
  }
  lastMeasuredViewport = { width: nextWidth, height: nextHeight }
}

const loadWindowViewport = () => {
  setViewport(readWindowViewport())
}

const updateViewport = () => {
  const element = gridHostRef.value?.$el || gridHostRef.value
  const width = element?.clientWidth
  const height = element?.clientHeight
  if (width > 0 && height > 0) setViewport({ width, height })
  else loadWindowViewport()
  if (typeof uni === 'undefined' || typeof uni.createSelectorQuery !== 'function') return
  uni.createSelectorQuery()
    .select('.cabinet-slot-grid-host')
    .boundingClientRect((rect) => setViewport(rect))
    .exec()
}

onMounted(() => {
  nextTick(() => {
    const element = gridHostRef.value?.$el || gridHostRef.value
    updateViewport()
    if (!element || typeof ResizeObserver === 'undefined') return
    resizeObserver = new ResizeObserver(updateViewport)
    resizeObserver.observe(element)
  })
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<style scoped>
.cabinet-slot-grid-host { flex: 1; min-height: 0; overflow: hidden; }
.cabinet-slot-grid {
  position: relative;
  display: grid;
  box-sizing: border-box;
  overflow: hidden;
}
.cabinet-slot-grid--split-halves::before {
  content: '';
  position: absolute;
  z-index: 2;
  top: 50%;
  left: 0;
  right: 0;
  height: var(--slot-half-divider-line-height, 6px);
  transform: translateY(-50%);
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(31,118,255,.65), #1f76ff 4%, #1f76ff 96%, rgba(31,118,255,.65));
  box-shadow: 0 0 0 1px rgba(255,255,255,.36), 0 0 10px rgba(31,118,255,.36);
  pointer-events: none;
}
</style>
