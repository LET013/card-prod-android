<template>
  <view class="page-root admin-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <view class="admin-content">
      <AdminMenuCard
        v-for="item in visibleMenus"
        :key="item.key"
        :label="item.label"
        :icon="item.icon"
        :color="item.color"
        @click="go(item.url)"
      />
    </view>
  </view>
</template>
<script setup>
import { computed, onMounted } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import { appState } from '@/state/appState.js'
import { ROLE_META, hasPermission } from '@/constants/app.js'
import { services } from '@/services/index.js'

const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '未登录')
const menus = [
  { key: 'system', label: '系统设置', icon: 'settings', color: '#1f76ff', permission: 'system.menu', url: '/pages/system/system' },
  { key: 'management', label: '管理功能', icon: 'user', color: '#1f76ff', permission: 'management.menu', url: '/pages/management/management' },
  { key: 'status', label: '查看卡状态', icon: 'list', color: '#1f76ff', permission: 'cabinet.view', url: '/pages/card-status/card-status' }
]
const visibleMenus = computed(() => menus.filter((item) => hasPermission(appState.session, item.permission)))
onMounted(() => { if (!appState.session) uni.reLaunch({ url: '/pages/index/index' }) })
const go = (url) => uni.navigateTo({ url })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.admin-page { background: #e6f0ff; }
.admin-content { padding: clamp(28px, 4vw, 54px) clamp(12px, 2vw, 24px); display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: clamp(10px, 2vw, 24px); align-content: start; }
.admin-content :deep(.menu-card) { min-height: clamp(108px, 19vw, 205px); }
.admin-content :deep(.icon-circle) { width: clamp(42px, 6vw, 66px); height: clamp(42px, 6vw, 66px); }
.admin-content :deep(.menu-label) { font-size: clamp(15px, 2vw, 18px); }
</style>
