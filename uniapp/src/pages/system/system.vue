<template>
  <view class="page-root system-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar title="系统设置" hint="系统维护、注册、授权和设备参数" @back="back" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="system-content">
        <!-- 生物识别录入 -->
        <view v-if="biometricSection.length" class="system-section">
          <text class="section-title">生物识别录入</text>
          <view class="system-grid">
            <AdminMenuCard v-for="item in biometricSection" :key="item.key" :label="item.label" :icon="item.icon" :color="item.color" @click="open(item)" />
          </view>
        </view>

        <!-- 数据管理 -->
        <view v-if="dataSection.length" class="system-section">
          <text class="section-title">数据管理</text>
          <view class="system-grid">
            <AdminMenuCard v-for="item in dataSection" :key="item.key" :label="item.label" :icon="item.icon" :color="item.color" @click="open(item)" />
          </view>
        </view>

        <!-- 系统工具 -->
        <view v-if="toolsSection.length" class="system-section">
          <text class="section-title">系统工具</text>
          <view class="system-grid">
            <AdminMenuCard v-for="item in toolsSection" :key="item.key" :label="item.label" :icon="item.icon" :color="item.color" @click="open(item)" />
          </view>
        </view>

        <view v-if="!hasAnySection" class="empty-state"><text>当前账号没有可用系统功能</text></view>
      </view>
    </scroll-view>
  </view>
</template>
<script setup>
import { computed, onMounted } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import { appState, hasPermission } from '@/state/appState.js'
import { services } from '@/services/index.js'

const roleLabel = computed(() => appState.session?.roleLabels?.join('、') || '')

const allSections = {
  biometric: [
    { key: 'face', label: '人脸注册', icon: 'face', color: '#FF6B7A', permission: 'system.face.*', url: '/pages/biometric/face' }
  ],
  data: [
    { key: 'employees', label: '人员管理', icon: 'people', color: '#5B9BFF', permission: 'system.employee.view', url: '/pages/employees/employees' },
    { key: 'units', label: '单元管理', icon: 'device', color: '#5965E9', permission: 'system.unit.view', url: '/pages/feature/feature?type=units' },
    { key: 'history', label: '历史管理', icon: 'history', color: '#FF9829', permission: 'system.history.view', url: '/pages/feature/feature?type=history' }
  ],
  tools: [
    { key: 'restart', label: '重启应用', icon: 'refresh', color: '#38BFD8', permission: 'system.restart', url: '/pages/feature/feature?type=restart' },
    { key: 'authorization', label: '系统授权', icon: 'shield', color: '#2F6DB2', permission: 'system.authorization.view', url: '/pages/feature/feature?type=authorization' },
    { key: 'device', label: '设备设置', icon: 'settings', color: '#657399', permission: 'system.settings.view', url: '/pages/config/config' }
  ]
}

const filterByPermission = (items) => items.filter((item) => hasPermission(item.permission))
const biometricSection = computed(() => filterByPermission(allSections.biometric))
const dataSection = computed(() => filterByPermission(allSections.data))
const toolsSection = computed(() => filterByPermission(allSections.tools))
const hasAnySection = computed(() => biometricSection.value.length || dataSection.value.length || toolsSection.value.length)

const open = (item) => {
  services.recordAuditEvent({ event_type: 'BUTTON_CLICK', action_code: `SYS_OPEN_${String(item.key).toUpperCase()}`, action_label: item.label || item.key })
  uni.navigateTo({ url: item.url })
}
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/admin/admin' }) })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
onMounted(() => {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'SYSTEM_PORTAL', feature_label: '系统管理入口' })
})
</script>
<style scoped>
.system-page { background: #e6f0ff; }
.system-content { padding: clamp(16px, 2.5vw, 28px) clamp(10px, 1.8vw, 22px); display: flex; flex-direction: column; gap: clamp(18px, 3vw, 30px); }
.system-section { display: flex; flex-direction: column; gap: clamp(6px, 1vw, 12px); }
.section-title {
  color: #627086;
  font-size: clamp(11px, 1.4vw, 13px);
  font-weight: 600;
  letter-spacing: 0.4px;
  padding-left: 4px;
}
.system-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(clamp(128px, 20vw, 180px), 1fr)); gap: clamp(8px, 1.6vw, 18px); }
.system-grid :deep(.menu-card) { min-height: clamp(92px, 14.5vw, 166px); }
.system-grid :deep(.icon-circle) { width: clamp(36px, 5vw, 56px); height: clamp(36px, 5vw, 56px); padding: clamp(8px, 1.1vw, 13px); }
.system-grid :deep(.menu-label) { margin-top: clamp(9px, 1.4vh, 16px); font-size: clamp(13px, 1.8vw, 17px); }
.empty-state { padding: 28px 16px 10px; text-align: center; color: #6d7b8f; font-size: 15px; }
@media (max-width: 560px) {
  .system-content { gap: 14px; padding-top: 10px; }
  .system-grid { gap: 8px; }
  .system-grid :deep(.menu-card) { min-height: 94px; }
}
</style>
