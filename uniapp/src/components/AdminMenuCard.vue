<template>
  <view class="menu-card" @click="$emit('click')">
    <view class="icon-circle" :style="{ background: color }"><IconGlyph :name="icon" /></view>
    <text class="menu-label">{{ label }}</text>
  </view>
</template>
<script setup>
import IconGlyph from './IconGlyph.vue'
defineProps({ label: String, icon: String, color: { type: String, default: '#1F76FF' } })
defineEmits(['click'])
</script>
<style scoped>
.menu-card { min-height: clamp(104px, 17vw, 190px); background: linear-gradient(180deg,#ffffff,#f7faff); border: 1px solid #dbe5f2; border-radius: clamp(12px,1.5vw,18px); box-shadow: 0 4px 12px rgba(55,91,140,.10); display: flex; flex-direction: column; align-items: center; justify-content: center; cursor: pointer; transition: transform .16s ease, box-shadow .16s ease; }
.menu-card:active { transform: scale(.97); box-shadow: 0 2px 6px rgba(55,91,140,.08); }
.icon-circle { width: clamp(40px, 5.8vw, 64px); height: clamp(40px, 5.8vw, 64px); border-radius: 50%; color: #fff; padding: clamp(9px, 1.4vw, 15px); box-shadow: inset 0 1px 1px rgba(255,255,255,.25), 0 5px 10px rgba(60,100,150,.16); }
.menu-label { margin-top: clamp(12px, 2vh, 21px); font-size: clamp(15px, 2vw, 18px); color: #2f4056; font-weight: 600; text-align: center; white-space: nowrap; }
</style>
