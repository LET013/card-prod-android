<template>
  <view class="admin-header" role="button" aria-label="退出管理模式" title="退出管理模式" @click="$emit('exit')">
    <view class="admin-header-inner">
      <view class="header-icon"><IconGlyph name="shield" /></view>
      <text class="admin-title">欢迎（{{ roleLabel || '未识别角色' }}）用户：{{ userLabel || '本地管理员' }}，进入管理模式，点击此处退出管理模式</text>
      <view class="header-icon exit-icon"><IconGlyph name="logout" /></view>
    </view>
  </view>
</template>
<script setup>
import IconGlyph from './IconGlyph.vue'

defineProps({
  roleLabel: { type: String, default: '' },
  userLabel: { type: String, default: '' }
})
defineEmits(['exit'])
</script>
<style scoped>
.admin-header {
  min-height: 60px;
  padding: env(safe-area-inset-top) clamp(12px, 2vw, 32px) 0;
  background: #e20d1a;
  border-bottom: 3px solid #b90b15;
  box-shadow: 0 3px 10px rgba(123, 5, 14, .18);
  box-sizing: border-box;
  flex: 0 0 auto;
  color: #fff;
  cursor: pointer;
  transition: background-color .15s ease;
}
.admin-header:active { background: #ca0b17; }
.admin-header-inner {
  width: 100%;
  max-width: 1520px;
  min-height: 57px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-icon {
  width: 34px;
  height: 34px;
  padding: 7px;
  border-radius: 8px;
  box-sizing: border-box;
  color: #fff;
  background: rgba(255, 255, 255, .12);
  border: 1px solid rgba(255, 255, 255, .28);
  flex: 0 0 auto;
}
.admin-title {
  min-width: 0;
  flex: 1;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
  text-align: center;
  overflow-wrap: anywhere;
}
.exit-icon { background: rgba(111, 0, 8, .18); }

@media (max-width: 560px) {
  .admin-header { min-height: 72px; padding: 7px 10px; }
  .admin-header-inner { min-height: 55px; gap: 8px; }
  .header-icon { width: 30px; height: 30px; padding: 6px; }
  .admin-title { font-size: 13px; line-height: 1.5; }
}
</style>
