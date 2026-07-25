<template>
  <view class="cabinet-header">
    <view class="brand-wrap">
      <view class="logo-box"><AppLogo /></view>
      <view class="brand-copy">
        <text class="brand-title">工作卡柜</text>
        <text class="brand-subtitle">卡柜号：{{ cabinetNumber }}</text>
      </view>
    </view>
    <view class="header-actions">
      <view class="sync-button" @click="$emit('sync')" aria-label="卡槽状态上报">
        <IconGlyph name="refresh" />
      </view>
      <view class="user-button" @click="$emit('user')" aria-label="管理员入口">
        <view class="user-head"></view>
        <view class="user-body"></view>
      </view>
    </view>
  </view>
</template>

<script setup>
import AppLogo from './AppLogo.vue'
import IconGlyph from './IconGlyph.vue'
defineProps({ cabinetNumber: { type: String, default: '8652566615555520' } })
defineEmits(['sync', 'user'])
</script>

<style scoped>
.cabinet-header {
  min-height: clamp(62px, 8vh, 80px);
  padding: 8px clamp(12px, 2vw, 22px);
  background: #1F76FF;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fff;
  flex: 0 0 auto;
}
.brand-wrap { display: flex; align-items: center; min-width: 0; }
.logo-box { width: clamp(52px, 7vw, 76px); height: clamp(34px, 4.7vw, 48px); flex: 0 0 auto; }
.brand-copy { display: flex; flex-direction: column; margin-left: 9px; min-width: 0; line-height: 1.12; }
.brand-title { font-size: clamp(18px, 2.4vw, 24px); font-weight: 500; white-space: nowrap; }
.brand-subtitle { font-size: clamp(12px, 1.55vw, 16px); margin-top: 4px; color: rgba(255,255,255,.94); white-space: nowrap; }
.header-actions { display:flex; align-items:center; gap:10px; }
.sync-button, .user-button { width: 36px; height: 36px; position: relative; flex: 0 0 36px; cursor:pointer; border-radius:50%; background:rgba(255,255,255,.17); border:1px solid rgba(255,255,255,.38); }
.sync-button { display:flex; align-items:center; justify-content:center; }
.sync-button :deep(svg) { width:20px; height:20px; }
.user-head { width: 11px; height: 11px; border-radius: 50%; background: #fff; position: absolute; left: 11px; top: 7px; }
.user-body { width: 20px; height: 11px; border-radius: 12px 12px 8px 8px; background: #fff; position: absolute; left: 7px; bottom: 6px; }
</style>