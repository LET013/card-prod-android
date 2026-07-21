<template>
  <view class="admin-header" @click="$emit('exit')">
    <text class="admin-title">点击退出管理模式</text>
    <text v-if="roleLabel" class="role-label">{{ roleLabel }}</text>
  </view>
</template>
<script setup>
defineProps({ roleLabel: { type: String, default: '' } })
defineEmits(['exit'])
</script>
<style scoped>
.admin-header { height: clamp(56px, 7.5vh, 76px); padding: 0 clamp(12px, 2vw, 22px); background: #F10B10; color: #fff; display: flex; align-items: center; justify-content: center; position: relative; flex: 0 0 auto; cursor: pointer; }
.admin-title { font-size: clamp(18px, 2.35vw, 23px); font-weight: 600; }
.role-label { position: absolute; right: clamp(10px, 1.8vw, 20px); font-size: 12px; padding: 5px 10px; background: rgba(255,255,255,.2); border-radius: 999px; }
</style>
