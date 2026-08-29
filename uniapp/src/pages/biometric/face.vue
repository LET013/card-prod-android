<template>
  <view class="page-root biometric-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar title="人脸注册" hint="采集照片并提交服务器添加人脸" @back="back" />
    <FaceRegisterPanel :initial-employee-id="initialEmployeeId" @done="back" />
  </view>
</template>
<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AdminHeader from '@/components/AdminHeader.vue';import AdminPageToolbar from '@/components/AdminPageToolbar.vue';import FaceRegisterPanel from '@/components/FaceRegisterPanel.vue'
import { appState } from '@/state/appState.js';import { ROLE_META } from '@/constants/app.js';import { services } from '@/services/index.js'
const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '')
const initialEmployeeId = ref('')
onLoad((options = {}) => { initialEmployeeId.value = String(options.employeeId || '').trim() })
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/admin/admin' }) })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.biometric-page { background: #e6f0ff; }
</style>
