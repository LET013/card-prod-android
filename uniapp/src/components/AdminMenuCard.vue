<template>
  <view
    class="menu-card"
    :class="[`variant-${variant}`, `layout-${layout}`, `size-${size}`, { 'is-disabled': disabled }]"
    role="button"
    :aria-disabled="disabled"
    :title="desc || label"
    @click="handleClick"
  >
    <view class="icon-circle" :style="iconStyle"><IconGlyph :name="icon" /></view>
    <view class="menu-copy">
      <text class="menu-label">{{ label }}</text>
      <text v-if="showDesc && desc" class="menu-desc">{{ desc }}</text>
    </view>
    <view v-if="layout === 'row'" class="menu-chevron"><IconGlyph name="chevron-right" /></view>
  </view>
</template>
<script setup>
import { computed } from 'vue'
import IconGlyph from './IconGlyph.vue'
const props = defineProps({
  label: String,
  desc: { type: String, default: '' },
  icon: String,
  color: { type: String, default: '#1F76FF' },
  variant: { type: String, default: 'filled' },
  layout: { type: String, default: 'tile' },
  size: { type: String, default: 'default' },
  showDesc: { type: Boolean, default: true },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['click'])
const iconStyle = computed(() => props.variant === 'line'
  ? { color: props.color }
  : { background: props.color })

function handleClick() {
  if (!props.disabled) emit('click')
}
</script>
<style scoped>
.menu-card {
  min-width: 0;
  background: #fff;
  border: 1px solid #dce3ec;
  border-radius: 8px;
  box-shadow: 0 2px 7px rgba(34, 52, 78, .07);
  box-sizing: border-box;
  cursor: pointer;
  transition: transform .15s ease, border-color .15s ease, box-shadow .15s ease;
}
.menu-card:active { transform: translateY(1px); box-shadow: 0 1px 3px rgba(34, 52, 78, .08); }
.menu-card.is-disabled { opacity: .58; cursor: default; }
.menu-card.is-disabled:active { transform: none; }
.icon-circle {
  width: 46px;
  height: 46px;
  padding: 11px;
  border-radius: 50%;
  box-sizing: border-box;
  color: #fff;
  flex: 0 0 auto;
}
.menu-card.variant-line .icon-circle { background: #f2f5f9; box-shadow: inset 0 0 0 1px #e0e6ee; }
.menu-copy { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.menu-label { color: #27364a; font-size: 18px; font-weight: 650; line-height: 1.3; }
.menu-desc { color: #77859a; font-size: 12px; line-height: 1.45; }
.menu-chevron { width: 18px; height: 18px; color: #a5afbd; flex: 0 0 auto; }

.menu-card.layout-tile {
  min-height: 118px;
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
}
.layout-tile .menu-copy { align-items: center; }

.menu-card.size-dashboard.layout-tile {
  min-height: 0;
  aspect-ratio: 1.1;
  padding: 16px 12px;
  gap: 14px;
  border-radius: 14px;
  border-color: #d8e0ea;
  box-shadow: 0 3px 9px rgba(34, 52, 78, .08);
}
.size-dashboard.layout-tile .icon-circle { width: 46px; height: 46px; padding: 10px; }
.size-dashboard.layout-tile .menu-label { color: #3d4148; font-size: 19px; font-weight: 500; line-height: 1.25; }

.menu-card.layout-row {
  min-height: 84px;
  padding: 14px 14px;
  display: flex;
  align-items: center;
  gap: 13px;
}
.layout-row .menu-copy { flex: 1; }

@media (hover: hover) {
  .menu-card:not(.is-disabled):hover { border-color: #bdc9d9; box-shadow: 0 5px 14px rgba(34, 52, 78, .10); transform: translateY(-1px); }
}

@media (max-width: 560px) {
  .menu-card.layout-row { min-height: 76px; padding: 11px 12px; gap: 11px; }
  .layout-row .icon-circle { width: 40px; height: 40px; padding: 9px; }
  .menu-label { font-size: 17px; }
  .menu-desc { font-size: 11px; }
  .menu-card.size-dashboard.layout-tile { padding: 12px 8px; gap: 9px; border-radius: 12px; }
  .size-dashboard.layout-tile .icon-circle { width: 42px; height: 42px; padding: 9px; }
  .size-dashboard.layout-tile .menu-label { font-size: 17px; }
}

@media (min-width: 900px) {
  .menu-card.size-dashboard.layout-tile { aspect-ratio: 1.38; padding: 22px 16px; gap: 22px; border-radius: 18px; }
  .size-dashboard.layout-tile .icon-circle { width: 56px; height: 56px; padding: 6px; }
  .size-dashboard.layout-tile .menu-label { font-size: 25px; }
}
</style>
