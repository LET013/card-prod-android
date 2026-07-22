<template>
  <view class="slot-card" :class="{ interactive }" :style="cardStyle" @click="handleClick">
    <text class="slot-number">{{ slot.displayNumber }}</text>
    <text class="slot-label">{{ meta.label }}</text>
  </view>
</template>
<script setup>
import { computed } from 'vue'
import { SLOT_STATUS_META } from '@/constants/app.js'
const props = defineProps({
  slot: { type: Object, required: true },
  interactive: { type: Boolean, default: false }
})
const emit = defineEmits(['click'])
const meta = computed(() => SLOT_STATUS_META[props.slot.status] || SLOT_STATUS_META.UNKNOWN)
const cardStyle = computed(() => ({ background: meta.value.color, color: meta.value.text }))
const handleClick = () => { if (props.interactive) emit('click', props.slot) }
</script>
<style scoped>
.slot-card {
  position: relative;
  min-width: 0;
  height: clamp(56px, 6.8dvh, 78px);
  border-radius: clamp(6px, .8vw, 9px);
  padding: 7px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(53,73,100,.15);
  overflow: hidden;
}
.slot-card.interactive { cursor: pointer; }
.slot-card::after { content: ""; position: absolute; left: 0; right: 0; top: 50%; height: 1px; background: rgba(120,135,155,.10); }
.slot-number { font-size: clamp(14px, 1.8vw, 16px); font-weight: 600; z-index: 1; }
.slot-label { font-size: clamp(12px, 1.65vw, 16px); align-self: center; white-space: nowrap; z-index: 1; }
</style>
