<template>
  <view class="slot-card" :class="[{ interactive, 'light-text': meta.text === '#FFFFFF' }, operationClass]" :style="cardStyle" @click="handleClick">
    <view class="slot-card-heading">
      <text class="slot-number">{{ slot.displayNumber }}</text>
      <view class="slot-status-mark" :aria-label="meta.label"><view></view></view>
    </view>
    <view class="slot-summary">
      <text class="slot-summary-label">{{ meta.label }}</text>
    </view>
  </view>
</template>
<script setup>
import { computed } from 'vue'
import { SLOT_STATUS_META } from '@/constants/app.js'
const props = defineProps({
  slot: { type: Object, required: true },
  interactive: { type: Boolean, default: false },
  operationEffect: { type: String, default: '' }
})
const emit = defineEmits(['click'])
const statusKey = computed(() => String(props.slot.status || '').trim().toUpperCase())
const meta = computed(() => SLOT_STATUS_META[statusKey.value] || SLOT_STATUS_META.UNKNOWN)
const supportedEffects = new Set(['success', 'failure'])
const operationClass = computed(() => supportedEffects.has(props.operationEffect)
  ? `slot-operation--${props.operationEffect}`
  : ''
)
const cardStyle = computed(() => ({
  background: meta.value.gradient || meta.value.color,
  borderColor: meta.value.border || 'rgba(255,255,255,.36)',
  '--slot-base-shadow': meta.value.shadow || '0 3px 9px rgba(53,73,100,.16)',
  '--slot-operation-color': props.operationEffect === 'success'
    ? '#20c878'
    : (props.operationEffect === 'failure' ? '#ef4059' : meta.value.color),
  color: meta.value.text
}))
const handleClick = () => { if (props.interactive) emit('click', props.slot) }
</script>
<style scoped>
.slot-card {
  position: relative;
  min-width: 0;
  height: var(--slot-card-height, clamp(56px, 6.8dvh, 78px));
  box-sizing: border-box;
  border-radius: clamp(6px, .8vw, 9px);
  border: 1px solid rgba(255,255,255,.32);
  padding: var(--slot-card-padding, 10px);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  box-shadow: var(--slot-base-shadow);
  transition: transform .2s ease, box-shadow .2s ease;
}
.slot-card.interactive { cursor: pointer; }
.slot-card::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(180deg, rgba(255,255,255,.22), rgba(255,255,255,0) 52%);
  pointer-events: none;
}
.slot-card-heading { flex: 0 0 auto; min-height: 0; display: flex; align-items: center; justify-content: space-between; gap: 4px; z-index: 1; }
.slot-number { font-size: var(--slot-number-size, clamp(16px, 2vw, 21px)); font-weight: 700; letter-spacing: .03em; line-height: 1.1; }
.slot-status-mark { display:flex; align-items:center; opacity:.86; }
.slot-status-mark>view { width:var(--slot-status-mark-size, 9px); height:var(--slot-status-mark-size, 9px); border-radius:50%; background:currentColor; box-shadow:0 0 0 2px rgba(255,255,255,.16); }
.slot-summary { position:relative; z-index:1; flex:1 1 auto; min-height:0; display:flex; align-items:center; justify-content:center; padding-block:1px; }
.slot-summary-label { display:block; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:var(--slot-status-size, clamp(14px,1.7vw,18px)); font-weight:700; line-height:1.25; padding-block:1px; }
.slot-card.light-text .slot-number { text-shadow: 0 1px 2px rgba(0,0,0,.18); }

.slot-card[class*="slot-operation--"] {
  z-index: 3;
  overflow: visible;
  will-change: box-shadow, filter;
}
.slot-operation--success {
  animation: slot-success-pulse .72s ease-in-out infinite;
}
.slot-operation--failure {
  animation: slot-failure-pulse .48s ease-in-out infinite;
}

@keyframes slot-success-pulse {
  0%, 100% { filter: brightness(.98) saturate(1.04); box-shadow: var(--slot-base-shadow), 0 0 7px 2px var(--slot-operation-color); }
  50% { filter: brightness(1.32) saturate(1.25); box-shadow: var(--slot-base-shadow), 0 0 12px 4px var(--slot-operation-color), 0 0 30px 10px var(--slot-operation-color); }
}
@keyframes slot-failure-pulse {
  0%, 100% { filter: brightness(.88) saturate(.92); box-shadow: var(--slot-base-shadow), 0 0 5px 1px var(--slot-operation-color); }
  50% { filter: brightness(1.28) saturate(1.22); box-shadow: var(--slot-base-shadow), 0 0 11px 4px var(--slot-operation-color), 0 0 27px 9px var(--slot-operation-color); }
}

@media (prefers-reduced-motion: reduce) {
  .slot-card[class*="slot-operation--"] {
    animation: none;
    filter: brightness(1.18) saturate(1.16);
    box-shadow: var(--slot-base-shadow), 0 0 10px 3px var(--slot-operation-color), 0 0 24px 8px var(--slot-operation-color);
  }
}
</style>
