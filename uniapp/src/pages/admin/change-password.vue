<template>
  <view class="page-root password-page">
    <AdminHeader :role-label="roleLabel" :user-label="userLabel" @exit="exitAdmin" />
    <AdminPageToolbar
      :title="forceMode ? '修改初始密码' : '修改登录密码'"
      hint="登录密码仅保存在本设备"
      :back-label="forceMode ? '退出' : '返回'"
      @back="toolbarBack"
    />
    <scroll-view class="page-scroll" scroll-y>
      <ChangePasswordPanel @done="handleDone" />
    </scroll-view>
  </view>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import ChangePasswordPanel from '@/components/ChangePasswordPanel.vue'
import { appState } from '@/state/appState.js'
import { services } from '@/services/index.js'

const pages = getCurrentPages()
const currentRoute = pages[pages.length - 1]?.options || {}
const forceMode = computed(() => currentRoute.force === '1' || appState.session?.needsPasswordChange === true)
const roleLabel = computed(() => appState.session?.roleLabels?.join('、') || '未登录')
const userLabel = computed(() => appState.session?.credentialLabel || '本地管理员')

onMounted(() => {
  if (forceMode.value) services.announceAdminWelcome(appState.session?.credentialLabel)
})

const handleDone = () => {
  uni.redirectTo({ url: '/pages/admin/admin' })
}

const toolbarBack = () => {
  if (forceMode.value) {
    exitAdmin()
    return
  }
  uni.navigateBack()
}

const exitAdmin = async () => {
  await services.logout()
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<style scoped>
.password-page { background: #e6f0ff; }
</style>
