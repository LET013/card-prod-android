<template>
  <view class="page-root config-page" style="background:#e8f2ff;">
    <DeviceConfigPanel @done="goBack" />
  </view>
</template>

<script setup>
import { onMounted } from 'vue'
import DeviceConfigPanel from '@/components/DeviceConfigPanel.vue'
import { services } from '@/services/index.js'

onMounted(() => {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'DEVICE_CONFIG', feature_label: '设备配置管理' })
})

const goBack = () => {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  if (pages.length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: '/pages/admin/admin' })
}
</script>

<style scoped>
.config-page { background: #e8f2ff !important; color: #233045; }
</style>
