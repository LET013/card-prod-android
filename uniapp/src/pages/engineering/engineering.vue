<template>
  <view class="page-root engineering-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar title="工程模式" hint="设备升级、指令验证和硬件维护" @back="back" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="engineering-grid">
        <AdminMenuCard
          v-for="item in menus"
          :key="item.key"
          :label="item.label"
          :icon="item.icon"
          :color="item.color"
          variant="line"
          @click="run(item)"
        />
      </view>
      <view v-if="!menus.length" class="empty-state">
        <text>当前账号没有可用工程功能</text>
      </view>
    </scroll-view>

    <ModalShell v-if="confirmUnlockAll" closable close-on-mask @close="confirmUnlockAll = false">
      <view class="confirm-card">
        <view class="warn-icon"><IconGlyph name="eject" /></view>
        <text class="confirm-title">确认一键弹卡</text>
        <text class="confirm-copy">客户端会先检查全部卡槽，空卡槽跳过，其余卡槽均尝试开门，并等待门已打开确认；卡是否取走由后续卡槽状态反映。真实设备拓扑未确认时不会执行。</text>
        <view class="confirm-actions">
          <button class="cancel" @click="confirmUnlockAll = false">取消</button>
          <button class="danger" @click="unlockAll">确认弹卡</button>
        </view>
      </view>
    </ModalShell>

    <ModalShell v-if="upgradeVisible" closable close-on-mask @close="upgradeVisible = false">
      <AppUpdatePanel v-if="activeAction?.key === 'app'" />
      <view v-else class="upgrade-card">
        <view class="dialog-icon"><IconGlyph :name="upgradeIcon" /></view>
        <text class="upgrade-title">{{ upgradeTitle }}</text>
        <text class="upgrade-state-message">{{ activeAction?.key === 'board' ? firmwareUpgradeStatusText : upgradeCapability.message }}</text>
        <button class="primary-gradient-button upgrade-button" @click="upgradeVisible = false">知道了</button>
      </view>
    </ModalShell>

    <ModalShell v-if="infoVisible" closable close-on-mask @close="infoVisible = false">
      <view class="info-card">
        <view class="info-icon"><IconGlyph :name="info.icon" /></view>
        <text class="info-title">{{ info.title }}</text>
        <text class="info-copy">{{ info.content }}</text>
        <button class="primary-gradient-button info-button" @click="infoVisible = false">确定</button>
      </view>
    </ModalShell>
  </view>
</template>
<script setup>
import { computed, reactive, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import ModalShell from '@/components/ModalShell.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import AppUpdatePanel from '@/components/AppUpdatePanel.vue'
import { resolveUpgradeCapability } from '@/constants/upgrade.js'
import { appState, getFirmwareUpgrade, hasPermission } from '@/state/appState.js'
import { services } from '@/services/index.js'

const roleLabel = computed(() => appState.session?.roleLabels?.join('、') || '')
const confirmUnlockAll = ref(false)
const upgradeVisible = ref(false)
const infoVisible = ref(false)
const activeAction = ref(null)
const info = reactive({ title: '', content: '', icon: 'device' })

const allMenus = [
  { key: 'board', label: '单板升级', icon: 'board-upgrade', color: '#1F76FF', permission: 'maintenance.firmware.board' },
  { key: 'app', label: 'APP升级', icon: 'app-upgrade', color: '#1F76FF', permission: 'maintenance.app.upgrade' },
  { key: 'unlockAll', label: '一键弹出', icon: 'eject', color: '#1F76FF', permission: 'maintenance.cabinet.eject-all' },
  { key: 'hardware', label: '硬件版本号', icon: 'hardware', color: '#1F76FF', permission: 'maintenance.hardware.view' },
  { key: 'workCard', label: '工作卡升级', icon: 'work-card-upgrade', color: '#1F76FF', permission: 'maintenance.firmware.work-card' },
  { key: 'command', label: '指令验证', icon: 'command-check', color: '#1F76FF', permission: 'maintenance.serial.manual-command' },
  { key: 'mainBoard', label: '主板升级', icon: 'main-board', color: '#1F76FF', permission: 'maintenance.firmware.main-board' }
]
const menus = computed(() => allMenus.filter((item) => hasPermission(item.permission)))
const upgradeTitle = computed(() => activeAction.value?.label || '升级状态')
const upgradeIcon = computed(() => activeAction.value?.icon || 'board-upgrade')
const upgradeCapability = computed(() => resolveUpgradeCapability(activeAction.value?.key))
const firmwareUpgradeStatusText = computed(() => {
  const upgrade = getFirmwareUpgrade()
  if (!upgrade) return '当前没有待处理的升级任务'
  const progress = Number(upgrade.progress || 0)
  const labels = {
    PENDING: '等待执行',
    VALIDATED: '参数已校验',
    DOWNLOADING: '正在下载',
    DOWNLOADED: '下载完成',
    ENABLING: '正在启用升级模式',
    TRANSMITTING: '正在串口传输',
    TRANSMITTED: '已传输，待真机验证',
    CANCELLED: '已取消',
    FAILED: '执行失败'
  }
  const state = labels[upgrade.status] || upgrade.status
  return `v${upgrade.firmwareVersion || '-'} · ${state}${progress > 0 ? ` · ${progress}%` : ''}`
})

const run = (item) => {
  if (['board', 'app', 'workCard', 'mainBoard'].includes(item.key)) {
    activeAction.value = item
    upgradeVisible.value = true
    return
  }
  if (item.key === 'unlockAll') {
    confirmUnlockAll.value = true
    return
  }
  if (item.key === 'hardware') {
    info.title = '硬件版本号'
    info.content = '硬件版本信息暂不可用'
    info.icon = 'hardware'
    infoVisible.value = true
    return
  }
  if (item.key === 'command') uni.navigateTo({ url: '/pages/serial-demo/serial-demo' })
}
const unlockAll = async () => {
  confirmUnlockAll.value = false
  try {
    const result = await services.unlockAllDoors()
    const successCount = Number(result?.successCount)
    const failedCount = Number(result?.failedCount)
    const doorOpenedCount = Number(result?.doorOpenedCount)
    const physicalConfirmedCount = Number(result?.physicalConfirmedCount)
    const pendingTakeCount = Number(result?.pendingTakeCount)
    const targetCount = Number(result?.targetCount)
    if (!Number.isFinite(successCount) || !Number.isFinite(failedCount) || !Number.isFinite(doorOpenedCount) || !Number.isFinite(physicalConfirmedCount) || !Number.isFinite(pendingTakeCount) || successCount < 0 || failedCount < 0 || doorOpenedCount < 0 || physicalConfirmedCount < 0 || pendingTakeCount < 0) {
      throw new Error('客户端未返回有效的一键弹出闭环结果')
    }
    const title = Number.isFinite(targetCount) && targetCount === 0
      ? '没有可弹出的卡'
      : (physicalConfirmedCount === 0 ? '一键弹卡待确认' : ((failedCount > 0 || pendingTakeCount > 0) ? '一键弹卡部分完成' : '一键弹卡完成'))
    uni.showModal({
      title,
      content: result?.message || `已确认弹出 ${physicalConfirmedCount} 张工卡，已打开待取 ${pendingTakeCount} 张，失败 ${failedCount} 项。`,
      showCancel: false
    })
  } catch (error) {
    uni.showToast({ title: error.message || '指令未下发', icon: 'none' })
  }
}
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/admin/admin' }) })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.engineering-page { background: #e6f0ff; }
.engineering-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(clamp(128px, 27vw, 216px), 1fr)); gap: clamp(9px, 1.8vw, 20px); padding: clamp(22px, 3.5vw, 44px) clamp(10px, 1.8vw, 22px); }
.engineering-grid :deep(.menu-card) { min-height: clamp(96px, 15vw, 174px); }
.engineering-grid :deep(.icon-circle) { width: clamp(38px, 5.2vw, 58px); height: clamp(38px, 5.2vw, 58px); padding: clamp(8px, 1.2vw, 14px); }
.engineering-grid :deep(.menu-label) { margin-top: clamp(10px, 1.5vh, 17px); font-size: clamp(14px, 1.85vw, 17px); }
.empty-state { padding: 28px 16px 10px; text-align: center; color: #6d7b8f; font-size: 15px; }
.confirm-card, .upgrade-card, .info-card { padding: 30px; display: flex; flex-direction: column; align-items: center; }
.warn-icon, .dialog-icon { width: 62px; height: 62px; border-radius: 50%; color: #fff; display: flex; align-items: center; justify-content: center; padding: 15px; box-shadow: 0 8px 18px rgba(31,118,255,.18); }
.warn-icon { background: #ff9829; }
.dialog-icon { background: linear-gradient(135deg,#4aa3ff,#1f76ff 48%,#0a53c4); }
.confirm-title, .upgrade-title, .info-title { font-size: 20px; font-weight: 600; margin-top: 18px; }
.confirm-copy, .info-copy { font-size: 14px; line-height: 1.7; color: #69788d; margin-top: 12px; text-align: center; }
.confirm-actions { display: flex; gap: 12px; width: 100%; margin-top: 22px; }
.confirm-actions button { flex: 1; height: 48px; border-radius: 10px; font-size: 16px; line-height:1; display:flex; align-items:center; justify-content:center; }
.cancel { background: #edf2f8; color: #4c5b70; }
.danger { background: #ef4053; color: #fff; }
.upgrade-button, .info-button { height: 52px; width: 100%; margin-top: 22px; }
.upgrade-state-title { margin-top:18px; color:#a22f40; font-size:15px; font-weight:700; text-align:center; }
.upgrade-state-message { margin-top:10px; color:#69788d; font-size:13px; line-height:1.65; text-align:left; overflow-wrap:anywhere; }
.upgrade-state-code { margin-top:10px; color:#9a3443; font-size:11px; line-height:1.5; text-align:center; overflow-wrap:anywhere; }
.upgrade-state-code.available { color:#246fca; font-size:12px; }
.info-icon { width: 64px; height: 64px; border-radius: 50%; background: #1f76ff; color: #fff; padding: 15px; }
</style>
