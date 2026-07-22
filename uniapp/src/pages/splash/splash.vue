<template>
  <view class="page-root splash-page"><image class="splash-logo" src="/static/brand/logo-white.png" mode="aspectFit" /></view>
</template>
<script setup>
import { onMounted } from 'vue'
import { appState } from '@/state/appState.js'
import { mockService } from '@/services/mockService.js'
import { services } from '@/services/index.js'
onMounted(async()=>{
  await Promise.all([
    mockService.wait(900),
    services.loadSettings().catch(() => null)
  ])
  const target=appState.settings.initialized?'/pages/index/index':'/pages/config/config?first=1'
  uni.reLaunch({url:target})
})
</script>
<style scoped>
.splash-page{width:100%;min-height:100vh;min-height:100dvh;height:100vh;height:100dvh;background:#1f76ff;display:flex;align-items:center;justify-content:center}.splash-logo{width:clamp(140px,26vw,260px);height:clamp(100px,18vw,190px)}
</style>
