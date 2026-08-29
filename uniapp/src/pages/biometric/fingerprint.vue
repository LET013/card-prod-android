<template>
  <view class="page-root biometric-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar title="员工指纹" hint="等待外接指纹模块与 SDK" @back="back" />
    <FingerprintRegisterPanel @done="back" />
  </view>
</template>
<script setup>
import { computed } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue';import AdminPageToolbar from '@/components/AdminPageToolbar.vue';import FingerprintRegisterPanel from '@/components/FingerprintRegisterPanel.vue'
import { appState } from '@/state/appState.js';import { ROLE_META } from '@/constants/app.js';import { services } from '@/services/index.js'
const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '')
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/admin/admin' }) })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.biometric-page { background: #e6f0ff; }
</style>
