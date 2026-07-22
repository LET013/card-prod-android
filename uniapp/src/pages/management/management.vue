<template>
  <view class="page-root management-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="management-grid">
        <AdminMenuCard
          v-for="item in menus"
          :key="item.key"
          :label="item.label"
          :icon="item.icon"
          :color="item.color"
          @click="run(item)"
        />
      </view>
      <view class="back-wrap"><BackButton @click="back" /></view>
    </scroll-view>

    <ModalShell v-if="confirmUnlockAll" closable close-on-mask @close="confirmUnlockAll = false">
      <view class="confirm-card">
        <view class="warn-icon">!</view>
        <text class="confirm-title">确认一键弹卡</text>
        <text class="confirm-copy">该操作将模拟打开全部卡门，操作不可撤销，请确认设备周围安全。</text>
        <view class="confirm-actions">
          <button class="cancel" @click="confirmUnlockAll = false">取消</button>
          <button class="danger" @click="unlockAll">确认弹卡</button>
        </view>
      </view>
    </ModalShell>

    <ModalShell v-if="upgradeVisible" closable close-on-mask @close="upgradeVisible = false">
      <view class="upgrade-card">
        <text class="upgrade-title">升级文件列表</text>
        <view v-for="file in files" :key="file.id" class="file-row" @click="selectedFile = file.id">
          <view class="radio" :class="{ selected: selectedFile === file.id }"></view>
          <view class="file-copy"><text>{{ file.fileName }}　{{ file.createdAt }}</text><text>{{ file.versionName }}</text></view>
        </view>
        <view v-if="progress > 0" class="progress-wrap"><view class="progress-bar" :style="{ width: `${progress}%` }"></view><text>{{ progress }}%</text></view>
        <button class="primary-gradient-button upgrade-button" :disabled="upgrading" @click="startUpgrade">{{ upgrading ? '升级中' : '马上升级' }}</button>
      </view>
    </ModalShell>

    <ModalShell v-if="infoVisible" closable close-on-mask @close="infoVisible = false">
      <view class="info-card">
        <view class="info-icon"><IconGlyph name="list" /></view>
        <text class="info-title">硬件版本号</text>
        <text class="info-copy">主板 V1.5 · 单板 V2.1 · 当前为本地 Mock 数据</text>
        <button class="primary-gradient-button info-button" @click="infoVisible = false">确定</button>
      </view>
    </ModalShell>
  </view>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import BackButton from '@/components/BackButton.vue'
import ModalShell from '@/components/ModalShell.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { appState } from '@/state/appState.js'
import { ROLE_META, hasPermission } from '@/constants/app.js'
import { services } from '@/services/index.js'

const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '')
const confirmUnlockAll = ref(false)
const upgradeVisible = ref(false)
const infoVisible = ref(false)
const files = ref([])
const selectedFile = ref('')
const progress = ref(0)
const upgrading = ref(false)

const allMenus = [
  { key: 'board', label: '单板升级', icon: 'upgrade', color: '#5b9cf2', permission: 'upgrade.firmware' },
  { key: 'app', label: 'APP升级', icon: 'upgrade', color: '#596fe9', permission: 'upgrade.app' },
  { key: 'unlockAll', label: '一键弹出', icon: 'card', color: '#4f9ae9', permission: 'cabinet.unlockAll' },
  { key: 'hardware', label: '硬件版本号', icon: 'list', color: '#5b8fe5', permission: 'cabinet.view' },
  { key: 'workCard', label: '工作卡升级', icon: 'upgrade', color: '#65a0f2', permission: 'upgrade.firmware' },
  { key: 'command', label: '指令验证', icon: 'device', color: '#507be5', permission: 'debug.command' },
  { key: 'mainBoard', label: '主板升级', icon: 'upgrade', color: '#5b8fe5', permission: 'upgrade.firmware' }
]
const menus = computed(() => allMenus.filter((item) => hasPermission(appState.session, item.permission)))

onMounted(async () => {
  files.value = await services.getUpgradeFiles()
  selectedFile.value = files.value[0]?.id || ''
})

const run = (item) => {
  if (['board', 'app', 'workCard', 'mainBoard'].includes(item.key)) {
    progress.value = 0
    upgradeVisible.value = true
    return
  }
  if (item.key === 'unlockAll') {
    confirmUnlockAll.value = true
    return
  }
  if (item.key === 'hardware') {
    infoVisible.value = true
    return
  }
  if (item.key === 'command') uni.navigateTo({ url: '/pages/serial-demo/serial-demo' })
}
const unlockAll = async () => {
  confirmUnlockAll.value = false
  try {
    const result = await services.unlockAllDoors()
    uni.showModal({ title: '操作完成', content: `成功 ${result.successCount} 个，失败 ${result.failedCount} 个`, showCancel: false })
  } catch (error) {
    uni.showToast({ title: error.message || '操作失败', icon: 'none' })
  }
}
const startUpgrade = async () => {
  if (!selectedFile.value) {
    uni.showToast({ title: '请选择升级文件', icon: 'none' })
    return
  }
  upgrading.value = true
  progress.value = 1
  try {
    await services.startUpgrade(selectedFile.value, (value) => { progress.value = value })
    uni.showToast({ title: '升级完成', icon: 'success' })
    setTimeout(() => { upgradeVisible.value = false; progress.value = 0 }, 700)
  } finally {
    upgrading.value = false
  }
}
const back = () => uni.navigateBack()
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.management-page { background: #e6f0ff; }
.management-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(clamp(128px, 27vw, 216px), 1fr)); gap: clamp(9px, 1.8vw, 20px); padding: clamp(22px, 3.5vw, 44px) clamp(10px, 1.8vw, 22px); }
.management-grid :deep(.menu-card) { min-height: clamp(96px, 15vw, 174px); }
.management-grid :deep(.icon-circle) { width: clamp(38px, 5.2vw, 58px); height: clamp(38px, 5.2vw, 58px); padding: clamp(8px, 1.2vw, 14px); }
.management-grid :deep(.menu-label) { margin-top: clamp(10px, 1.5vh, 17px); font-size: clamp(14px, 1.85vw, 17px); }
.back-wrap { padding: clamp(28px, 5vh, 66px) 0 max(24px, env(safe-area-inset-bottom)); }
.confirm-card, .upgrade-card, .info-card { padding: 30px; display: flex; flex-direction: column; align-items: center; }
.warn-icon { width: 62px; height: 62px; border-radius: 50%; background: #ff9829; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 40px; font-weight: 700; }
.confirm-title, .upgrade-title, .info-title { font-size: 20px; font-weight: 600; margin-top: 18px; }
.confirm-copy, .info-copy { font-size: 14px; line-height: 1.7; color: #69788d; margin-top: 12px; text-align: center; }
.confirm-actions { display: flex; gap: 12px; width: 100%; margin-top: 22px; }
.confirm-actions button { flex: 1; height: 48px; border-radius: 10px; font-size: 16px; line-height:1; display:flex; align-items:center; justify-content:center; }
.cancel { background: #edf2f8; color: #4c5b70; }
.danger { background: #ef4053; color: #fff; }
.file-row { width: 100%; display: flex; align-items: flex-start; gap: 12px; padding: 15px 2px; border-bottom: 1px solid #e5ebf3; cursor: pointer; }
.radio { width: 17px; height: 17px; border: 2px solid #9babc0; border-radius: 50%; margin-top: 3px; flex: 0 0 auto; }
.radio.selected { border-color: #1f76ff; box-shadow: inset 0 0 0 4px white; background: #1f76ff; }
.file-copy { display: flex; flex-direction: column; gap: 6px; font-size: 14px; }
.file-copy text:last-child { color: #6f7e92; }
.upgrade-button, .info-button { height: 52px; width: 100%; margin-top: 22px; }
.progress-wrap { width: 100%; height: 22px; border-radius: 999px; background: #e7eef8; margin-top: 20px; position: relative; overflow: hidden; }
.progress-bar { height: 100%; background: linear-gradient(90deg,#7ef8ad,#00dada); }
.progress-wrap text { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 12px; }
.info-icon { width: 64px; height: 64px; border-radius: 50%; background: #1f76ff; color: #fff; padding: 15px; }
</style>
