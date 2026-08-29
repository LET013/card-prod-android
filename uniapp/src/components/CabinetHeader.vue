<template>
  <view class="cabinet-header">
    <view class="brand-wrap">
      <view class="logo-box"><AppLogo /></view>
      <view class="brand-copy">
        <view class="brand-title-row">
          <text class="brand-title">工作卡柜</text>
          <view class="status-dots">
            <view :class="['status-dot', activated ? 'active' : 'offline']" title="设备激活状态"></view>
            <view :class="['status-dot', mqttOnline ? 'online' : 'offline']" title="MQTT"></view>
          </view>
        </view>
        <text class="brand-subtitle">卡柜号：{{ cabinetNumber }}</text>
      </view>
    </view>
    <view class="user-button" @click="$emit('user')" aria-label="管理员入口"><IconGlyph name="user" size="28" /></view>
  </view>
</template>

<script setup>
import AppLogo from './AppLogo.vue'
import IconGlyph from './IconGlyph.vue'
defineProps({
  cabinetNumber: { type: String, default: '8652566615555520' },
  activated: { type: Boolean, default: false },
  mqttOnline: { type: Boolean, default: false }
})
defineEmits(['user'])
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
.brand-title-row { display: flex; align-items: center; gap: 3px; }
.status-dots { display: flex; align-items: center; }
.status-dot + .status-dot { margin-left: 3px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; }
.status-dot.active { background: #36d399; }
.status-dot.online { background: #36d399; }
.status-dot.offline { background: #f87272; }
.brand-title { font-size: clamp(18px, 2.4vw, 24px); font-weight: 500; white-space: nowrap; }
.brand-subtitle { font-size: clamp(12px, 1.55vw, 16px); margin-top: 4px; color: rgba(255,255,255,.94); white-space: nowrap; }
.user-button { width:44px; height:44px; display:flex; align-items:center; justify-content:center; flex:0 0 44px; cursor:pointer; border-radius:50%; color:#fff; background:rgba(255,255,255,.17); border:1px solid rgba(255,255,255,.38); }
@media (min-width:900px) and (orientation:landscape) {
  .cabinet-header { min-height:68px; padding-left:clamp(28px,3vw,52px); padding-right:clamp(28px,3vw,52px); }
  .logo-box { width:64px; height:42px; }
  .brand-copy { margin-left:12px; }
  .brand-title { font-size:22px; }
  .brand-subtitle { font-size:14px; }
  .status-dot + .status-dot { margin-left:3px; }
  .user-button { margin-right:4px; }
}
</style>
