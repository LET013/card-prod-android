<!--
  admin.vue — 管理员后台仪表盘（选项卡式全局导航）
  
  4 个选项卡，每个 Tab 内展示 Icon 卡片导航：
    账号权限 — 角色管理 / 用户管理 卡片 → 页面跳转  |  修改密码 卡片 → 弹窗
    系统管理 — 人脸/授权/设备设置 卡片 → 弹窗  |  人员/单元/历史/重启 卡片 → 页面跳转
    设备维护 — 工程模式 7 项操作卡片 → 弹窗
    实时状态 — 卡位实时状态页面 / MQTT 通信状态弹窗
-->

<template>
  <view class="admin-dashboard">
    <PasswordModal
      v-if="secondaryPasswordVisible"
      title="管理二级密码验证"
      help="请输入 6 位管理二级密码以确认身份"
      @close="closeSecondaryPassword"
      @submit="onSecondarySubmit"
    />

    <AdminHeader :role-label="roleLabel" :user-label="userLabel" @exit="exitAdmin" />

    <!-- ====== Tab 栏 ====== -->
    <view class="tab-bar">
      <view
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-item"
        :class="{ active: activeTab === tab.key }"
        @click="switchTab(tab.key)"
      >
        <view class="tab-icon"><IconGlyph :name="tab.icon" /></view>
        <text class="tab-label">{{ tab.label }}</text>
      </view>
    </view>

    <!-- ====== Tab 内容区 ====== -->
    <scroll-view class="tab-scroll" scroll-y :show-scrollbar="false">
      <view class="tab-content">
      <!-- ███ 账号权限 ███ -->
      <view v-show="activeTab === 'account'" class="tab-panel">
        <view class="sys-grid">
          <AdminMenuCard v-if="ip('account.role.view')" label="角色管理" desc="配置角色权限范围" icon="role-manage" color="#3566A6" layout="tile" size="dashboard" :show-desc="false" @click="go('/pages/admin/role-manage')" />
          <AdminMenuCard v-if="ip('account.user.view')" label="用户管理" desc="维护管理员账号与角色" icon="user-manage" color="#19C93A" layout="tile" size="dashboard" :show-desc="false" @click="go('/pages/admin/credential-manage')" />
          <AdminMenuCard v-if="ip('account.password.change')" label="修改密码" desc="更新当前账号登录密码" icon="password-change" color="#FF8B2F" layout="tile" size="dashboard" :show-desc="false" @click="showPasswordForm = true" />
        </view>
      </view>

      <!-- ███ 系统管理 ███ -->
      <view v-show="activeTab === 'system'" class="tab-panel">

        <view v-if="systemBiometric.length" class="sys-sec">
          <text class="sec-title">生物识别录入</text>
          <view class="sys-grid">
            <AdminMenuCard
              v-for="item in systemBiometric"
              :key="item.label"
              v-bind="item"
              layout="tile"
              size="dashboard"
              :show-desc="false"
              @click="onSysItem(item)"
            />
          </view>
        </view>

        <view v-if="systemData.length" class="sys-sec">
          <text class="sec-title">数据管理</text>
          <view class="sys-grid">
            <AdminMenuCard
              v-for="item in systemData"
              :key="item.label"
              v-bind="item"
              layout="tile"
              size="dashboard"
              :show-desc="false"
              @click="onSysItem(item)"
            />
          </view>
        </view>

        <view v-if="systemTools.length" class="sys-sec">
          <text class="sec-title">系统工具</text>
          <view class="sys-grid">
            <AdminMenuCard
              v-for="item in systemTools"
              :key="item.label"
              v-bind="item"
              layout="tile"
              size="dashboard"
              :show-desc="false"
              @click="onSysItem(item)"
            />
          </view>
        </view>

      </view>

      <!-- ███ 设备维护 ███ -->
      <view v-show="activeTab === 'maintenance'" class="tab-panel">
        <view class="sys-grid">
          <AdminMenuCard
            v-for="item in engMenuItems"
            :key="item.label"
            v-bind="item"
            layout="tile"
            size="dashboard"
            :show-desc="false"
            @click="handleEngAction(item)"
          />
        </view>
      </view>

      <!-- ███ 实时状态 ███ -->
      <view v-show="activeTab === 'status'" class="tab-panel">
        <view class="sys-grid status-grid">
          <AdminMenuCard v-if="ip('realtime.slot.view')" label="卡位实时状态" desc="查看全部卡位当前状态" icon="card-slot-status" color="#19C93A" layout="tile" size="dashboard" :show-desc="false" @click="openCardStatus" />
          <AdminMenuCard v-if="ip('realtime.communication.view')" label="通信状态" desc="查看设备连接与通信记录" icon="mqtt-status" color="#22A7D6" layout="tile" size="dashboard" :show-desc="false" @click="openMqttStatus" />
        </view>
      </view>

      </view>
    </scroll-view>

    <!-- ====== 修改密码弹窗 ====== -->
    <ModalShell v-if="showPasswordForm" closable closeOnMask sizeClass="modal-wide" @close="showPasswordForm = false">
      <ChangePasswordPanel @done="showPasswordForm = false" />
    </ModalShell>

    <!-- ====== MQTT 通信状态弹窗 ====== -->
    <ModalShell v-if="mqttStatusVisible" closable closeOnMask sizeClass="modal-communication" @close="closeMqttStatus">
      <view class="mqtt-status-panel">
        <view class="mqtt-status-head">
          <view class="mqtt-status-icon" :class="{ online: mqttStatus.connected }">
            <IconGlyph name="signal" />
          </view>
          <view class="mqtt-status-copy">
            <text class="mqtt-status-title">通信状态</text>
            <text class="mqtt-status-summary">{{ mqttStatus.loading ? '正在读取设备状态' : mqttStatus.message }}</text>
          </view>
        </view>

        <view class="mqtt-status-list">
          <view class="mqtt-status-row">
            <text class="mqtt-status-label">连接状态</text>
            <text class="mqtt-status-value" :class="mqttStatus.connected ? 'online' : 'offline'">
              {{ mqttStatus.loading ? '读取中' : (mqttStatus.connected ? '已连接' : '未连接') }}
            </text>
          </view>
          <view class="mqtt-status-row">
            <text class="mqtt-status-label">设备编码</text>
            <text class="mqtt-status-value">{{ mqttStatus.deviceCode || '未配置' }}</text>
          </view>
          <view class="mqtt-status-row">
            <text class="mqtt-status-label">最后检查</text>
            <text class="mqtt-status-value">{{ mqttStatus.checkedAt || '尚未检查' }}</text>
          </view>
        </view>

        <text v-if="mqttStatus.error" class="mqtt-status-error">{{ mqttStatus.error }}</text>

        <!-- ███ 通信日志区域 ███ -->
        <view class="mqtt-log-section">
          <view class="mqtt-log-header">
            <text class="mqtt-log-title">通信日志（最近 {{ mqttLogs.length }} 条）</text>
            <text v-permission="'realtime.communication.clear-log'" class="mqtt-log-clear" @click="clearCommLogs">清空</text>
          </view>
          <scroll-view v-if="mqttLogs.length > 0" class="mqtt-log-list" scroll-y="true">
            <view v-for="log in mqttLogs" :key="log.seq" class="mqtt-log-item" :class="'log-' + log.type">
              <text class="mqtt-log-time">{{ formatCommLogTime(log.timestamp) }}</text>
              <text class="mqtt-log-type">{{ commLogTypeLabel(log.type) }}</text>
              <text class="mqtt-log-detail">{{ log.detail }}</text>
            </view>
          </scroll-view>
          <view v-else class="mqtt-log-empty">
            <text>暂无通信日志</text>
          </view>
        </view>

        <button v-permission="'realtime.communication.refresh'" class="mqtt-refresh-button" :disabled="mqttStatus.loading" @click="refreshMqttStatus">
          <view class="mqtt-refresh-icon"><IconGlyph name="refresh" /></view>
          <text>{{ mqttStatus.loading ? '正在刷新' : '刷新状态' }}</text>
        </button>
      </view>
    </ModalShell>

    <!-- ====== 一键弹出确认弹窗 ====== -->
    <ModalShell v-if="confirmUnlockVisible" closable sizeClass="modal-compact" @close="closeUnlockDialog">
      <view class="eng-modal dialog-panel">
        <view class="dialog-heading">
          <view class="dialog-icon danger"><IconGlyph name="eject" /></view>
          <view class="dialog-heading-copy">
            <text class="dialog-title">确认一键弹出</text>
            <text class="dialog-subtitle">高风险设备操作</text>
          </view>
        </view>
        <view class="dialog-notice">
          <text>此操作会先检查全部卡槽，空卡槽跳过，其余卡槽均尝试开门。收到门已打开确认即计入完成；卡是否取走由后续卡槽状态反映。</text>
        </view>
        <view v-if="unlockFeedback" class="dialog-feedback" :class="unlockFeedback.type">
          <text class="dialog-feedback-title">{{ unlockFeedback.title }}</text>
          <text class="dialog-feedback-message">{{ unlockFeedback.message }}</text>
        </view>
        <view class="dialog-actions">
          <button class="dialog-button secondary" :disabled="engActioning" @click="closeUnlockDialog">{{ unlockFeedback ? '关闭' : '取消' }}</button>
          <button class="dialog-button danger" :disabled="engActioning" @click="doUnlockAll">{{ engActioning ? '正在执行' : (unlockFeedback && unlockFeedback.type === 'error' ? '重新尝试' : '确认执行') }}</button>
        </view>
      </view>
    </ModalShell>

    <!-- ====== 升级弹窗 ====== -->
    <ModalShell v-if="upgradeVisible" closable closeOnMask sizeClass="modal-compact" @close="closeUpgradeDialog">
      <AppUpdatePanel v-if="activeAction?.key === 'app'" />
      <view v-else class="eng-upgrade-card dialog-panel">
        <view class="dialog-heading centered">
          <view class="dialog-icon primary"><IconGlyph :name="upgradeIcon" /></view>
          <view class="dialog-heading-copy centered">
            <text class="dialog-title">{{ upgradeTitle }}</text>
          </view>
        </view>
        <view class="dialog-state">
          <text class="dialog-state-message">{{ activeAction?.key === 'board' ? firmwareUpgradeStatusText : upgradeCapability.message }}</text>
        </view>
        <button class="dialog-button primary full" @click="closeUpgradeDialog">知道了</button>
      </view>
    </ModalShell>

    <!-- ====== 硬件信息弹窗 ====== -->
    <ModalShell v-if="infoVisible" closable closeOnMask sizeClass="modal-compact" @close="infoVisible = false">
      <view class="eng-info-card dialog-panel">
        <view class="dialog-heading centered">
          <view class="dialog-icon primary"><IconGlyph :name="engInfo.icon" /></view>
          <view class="dialog-heading-copy centered">
            <text class="dialog-title">{{ engInfo.title }}</text>
            <text class="dialog-subtitle">设备信息</text>
          </view>
        </view>
        <view class="dialog-state info"><text class="dialog-state-message">{{ engInfo.content }}</text></view>
        <button class="dialog-button primary full" @click="infoVisible = false">确定</button>
      </view>
    </ModalShell>

    <!-- ====== 系统管理弹窗：人脸注册 ====== -->
    <ModalShell v-if="systemModal === 'face'" closable sizeClass="modal-wide" @close="systemModal = ''">
      <FaceRegisterPanel @done="systemModal = ''" />
    </ModalShell>

    <!-- ====== 系统管理弹窗：系统授权 ====== -->
    <ModalShell v-if="systemModal === 'auth'" closable sizeClass="modal-wide" @close="systemModal = ''">
      <AuthorizationPanel @done="systemModal = ''" />
    </ModalShell>

    <!-- ====== 系统管理弹窗：设备设置 ====== -->
    <ModalShell v-if="systemModal === 'config'" closable sizeClass="modal-full" @close="systemModal = ''">
      <DeviceConfigPanel class="device-config-panel-modal" @done="systemModal = ''" />
    </ModalShell>

  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import ModalShell from '@/components/ModalShell.vue'
import PasswordModal from '@/components/PasswordModal.vue'

import FaceRegisterPanel from '@/components/FaceRegisterPanel.vue'
import AuthorizationPanel from '@/components/AuthorizationPanel.vue'
import DeviceConfigPanel from '@/components/DeviceConfigPanel.vue'
import AppUpdatePanel from '@/components/AppUpdatePanel.vue'
import ChangePasswordPanel from '@/components/ChangePasswordPanel.vue'
import { ROLE_META, hasPermission } from '@/constants/app.js'
import { resolveUpgradeCapability } from '@/constants/upgrade.js'
import { appState, getFirmwareUpgrade } from '@/state/appState.js'
import { services } from '@/services/index.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

/* ═══════════ Tab ═══════════ */

const ip = (permissionKey) => hasPermission(appState.session, permissionKey)

const tabDefinitions = [
  { key: 'account', icon: 'user', label: '账号权限' },
  { key: 'system', icon: 'settings', label: '系统管理' },
  { key: 'maintenance', icon: 'tool', label: '设备维护' },
  { key: 'status', icon: 'tab-status', label: '实时状态' }
]

const tabPermissionKeys = {
  account: ['account.*'],
  system: ['system.*'],
  maintenance: ['maintenance.*'],
  status: ['realtime.*']
}

const tabs = computed(() => tabDefinitions.filter((tab) => tabPermissionKeys[tab.key].some(ip)))
const activeTab = ref(tabs.value[0]?.key || '')

function switchTab(key) {
  activeTab.value = key
  const tabDef = tabDefinitions.find(t => t.key === key)
  auditClick(`ADMIN_TAB_${key.toUpperCase()}`, tabDef?.label || key)
}

/* ═══════════ Header ═══════════ */

const roleLabel = computed(() => {
  const s = appState.session; if (!s) return ''
  if (Array.isArray(s.roleLabels) && s.roleLabels.length) return s.roleLabels.join('、')
  return ROLE_META[s.role]?.label || s.role || ''
})
const userLabel = computed(() => appState.session?.credentialLabel || appState.session?.displayName || appState.session?.credentialId || '')

async function exitAdmin() {
  auditClick('ADMIN_EXIT', '退出管理')
  await services.logout()
  uni.reLaunch({ url: '/pages/index/index' })
}

const ADMIN_MANAGE_ROUTES = new Set([
  '/pages/admin/role-manage',
  '/pages/admin/credential-manage'
])
const secondaryPasswordVisible = ref(false)
const pendingAdminRoute = ref('')

function go(route) {
  if (!route) return
  if (ADMIN_MANAGE_ROUTES.has(route) && !services.hasAdminManageSecondaryAccess()) {
    pendingAdminRoute.value = route
    secondaryPasswordVisible.value = true
    return
  }
  uni.navigateTo({ url: route })
}

function closeSecondaryPassword() {
  secondaryPasswordVisible.value = false
  pendingAdminRoute.value = ''
}

async function onSecondarySubmit(password, controls = {}) {
  try {
    await services.verifyAdminManageAccess(password)
    const route = pendingAdminRoute.value
    secondaryPasswordVisible.value = false
    pendingAdminRoute.value = ''
    if (route) uni.navigateTo({ url: route })
  } catch (error) {
    controls.setError?.(error?.message || '二级密码错误')
  }
}

/* ═══════════ 模态弹窗状态 ═══════════ */

const showPasswordForm = ref(false)

/* ═══════════ 系统管理 Tab ═══════════ */

const systemBiometric = computed(() => {
  const items = []
  if (ip('system.face.*')) {
    items.push({ label: '人脸注册', desc: '录入人脸识别信息', icon: 'face-register', color: '#FF6371', interaction: 'modal', modalKey: 'face' })
  }
  return items
})

const systemData = computed(() => {
  const items = []
  if (ip('system.employee.view'))  items.push({ label: '人员管理', desc: '内部员工基础信息维护', icon: 'employee-manage', color: '#22A7D6', route: '/pages/employees/employees' })
  if (ip('system.unit.view'))      items.push({ label: '单元管理', desc: '单元/部门归属管理', icon: 'unit-manage', color: '#C04BD9', route: '/pages/feature/feature?type=units' })
  if (ip('system.history.view'))   items.push({ label: '历史管理', desc: '取卡/还卡/开关门记录', icon: 'history-manage', color: '#FF8531', route: '/pages/feature/feature?type=history' })
  return items
})

const systemTools = computed(() => {
  const items = []
  if (ip('system.restart'))          items.push({ label: '重启应用', desc: '安全重启并恢复应用运行状态', icon: 'restart-app', color: '#2A73FF', route: '/pages/feature/feature?type=restart' })
  if (ip('system.authorization.view')) items.push({ label: '系统授权', desc: '签发激活码、配置许可', icon: 'authorization', color: '#2D62A4', interaction: 'modal', modalKey: 'auth' })
  if (ip('system.settings.view'))       items.push({ label: '设备设置', desc: '服务器与设备基础配置', icon: 'device-settings', color: '#6572A0', interaction: 'modal', modalKey: 'config' })
  return items
})

/* 系统管理：卡片点击分发 */
const systemModal = ref('')

function onSysItem(item) {
  if (item.interaction === 'modal' && item.modalKey) {
    auditClick(`ADMIN_SYS_MODAL_${item.modalKey.toUpperCase()}`, item.label)
    systemModal.value = item.modalKey
    return
  }
  if (item.route) {
    auditClick(`ADMIN_SYS_NAV`, item.label)
    uni.navigateTo({ url: item.route })
  }
}

/* ═══════════ 设备维护 Tab ═══════════ */

const ipEng = (p) => hasPermission(appState.session, p)

const engMenuItems = computed(() => {
  const list = []
  if (ipEng('maintenance.firmware.board'))  list.push({ key: 'board',     label: '单板升级', icon: 'board-upgrade', color: '#2877F5', desc: '当前没有待处理的升级任务' })
  if (ipEng('maintenance.app.upgrade'))       list.push({ key: 'app',       label: 'APP 升级', icon: 'app-upgrade', color: '#20A7D8', desc: '在线获取最新版本' })
  if (ipEng('maintenance.cabinet.eject-all')) list.push({ key: 'unlockAll', label: '一键弹出', icon: 'eject', color: '#FFAC12', desc: '逐卡校验并确认实际弹出结果' })
  if (ipEng('maintenance.hardware.view'))      list.push({ key: 'hardware',  label: '硬件版本号', icon: 'hardware', color: '#2E63A4', desc: '硬件版本信息暂不可用' })
  if (ipEng('maintenance.firmware.work-card'))  list.push({ key: 'workCard',  label: '工作卡升级', icon: 'work-card-upgrade', color: '#EF4059', desc: '当前暂不支持工作卡升级' })
  if (ipEng('maintenance.serial.*'))     list.push({ key: 'command',   label: '指令验证', icon: 'command-check', color: '#FF5C7F', desc: '串口调试控制台', route: '/pages/serial-demo/serial-demo' })
  if (ipEng('maintenance.firmware.main-board'))  list.push({ key: 'mainBoard', label: '主板升级', icon: 'main-board', color: '#8D2BD8', desc: '当前暂不支持主板升级' })
  return list
})

const confirmUnlockVisible = ref(false)
const engActioning = ref(false)
const unlockFeedback = ref(null)
const upgradeVisible = ref(false)
const infoVisible = ref(false)
const activeAction = ref(null)
const engInfo = reactive({ title: '', content: '', icon: 'hardware' })

const upgradeTitle = computed(() => activeAction.value?.label || '升级状态')
const upgradeIcon = computed(() => activeAction.value?.icon || 'board-upgrade')
const upgradeCapability = computed(() => resolveUpgradeCapability(activeAction.value?.key))
const firmwareUpgradeStatusText = computed(() => {
  const upgrade = getFirmwareUpgrade()
  if (!upgrade) return '当前没有待处理的升级任务'
  const version = upgrade.firmwareVersion ? `v${upgrade.firmwareVersion}` : '未知版本'
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
  const label = labels[upgrade.status] || upgrade.status
  return `${version} · ${label}${progress > 0 ? ` · ${progress}%` : ''}`
})

function handleEngAction(item) {
  auditClick(`ADMIN_ENG_${String(item.key).toUpperCase()}`, item.label)
  if (item.key === 'unlockAll') {
    unlockFeedback.value = null
    confirmUnlockVisible.value = true
    return
  }
  if (['board', 'app', 'workCard', 'mainBoard'].includes(item.key)) {
    activeAction.value = item
    upgradeVisible.value = true
    return
  }
  if (item.key === 'hardware') {
    engInfo.title = '硬件版本号'
    engInfo.content = '硬件版本信息暂不可用'
    engInfo.icon = 'hardware'
    infoVisible.value = true
    return
  }
  if (item.route) { uni.navigateTo({ url: item.route }) }
}

function closeUnlockDialog() {
  if (engActioning.value) return
  confirmUnlockVisible.value = false
  unlockFeedback.value = null
}

async function doUnlockAll() {
  if (engActioning.value) return
  auditClick('ADMIN_ENG_UNLOCK_ALL_EXECUTE', '一键弹出执行')
  engActioning.value = true
  unlockFeedback.value = null
  try {
    const result = await services.unlockAllDoors()
    const successCount = Number(result?.successCount)
    const failedCount = Number(result?.failedCount)
    const doorOpenedCount = Number(result?.doorOpenedCount)
    const physicalConfirmedCount = Number(result?.physicalConfirmedCount)
    const pendingTakeCount = Number(result?.pendingTakeCount)
    const targetCount = Number(result?.targetCount)
    const hasCounts = Number.isFinite(successCount) && Number.isFinite(failedCount) && Number.isFinite(doorOpenedCount) && Number.isFinite(physicalConfirmedCount) && Number.isFinite(pendingTakeCount)
    if (!hasCounts || successCount < 0 || failedCount < 0 || doorOpenedCount < 0 || physicalConfirmedCount < 0 || pendingTakeCount < 0) {
      throw new Error('客户端未返回有效的一键弹出闭环结果')
    }
    const hasFailure = failedCount > 0
    const noCardEjected = physicalConfirmedCount === 0
    const noTarget = Number.isFinite(targetCount) && targetCount === 0
    unlockFeedback.value = {
      type: noCardEjected ? 'warning' : ((hasFailure || pendingTakeCount > 0) ? 'warning' : 'success'),
      title: noTarget ? '没有可弹出的卡' : (noCardEjected ? '一键弹卡待确认' : ((hasFailure || pendingTakeCount > 0) ? '一键弹卡部分完成' : '一键弹卡完成')),
      message: result?.message || `已确认弹出 ${physicalConfirmedCount} 张工卡，已打开待取 ${pendingTakeCount} 张，失败 ${failedCount} 项`
    }
  } catch (error) {
    unlockFeedback.value = {
      type: 'error',
      title: '一键弹出未执行',
      message: toUserErrorMessage(error, '一键弹出失败，请检查设备状态后重试')
    }
  }
  finally { engActioning.value = false }
}

function closeUpgradeDialog() {
  upgradeVisible.value = false
}

/* ═══════════ 实时状态 ═══════════ */

function openCardStatus() {
  auditClick('ADMIN_OPEN_CARD_STATUS', '查看卡柜状态')
  uni.navigateTo({ url: '/pages/card-status/card-status' })
}

const mqttStatusVisible = ref(false)
const mqttStatus = reactive({
  loading: false,
  connected: false,
  deviceCode: '',
  checkedAt: '',
  message: '设备通信状态尚未读取',
  error: ''
})

function formatCheckedAt(timestamp) {
  const date = new Date(Number(timestamp))
  if (Number.isNaN(date.getTime())) return ''
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function applyMqttStatus(runtime = null) {
  const runtimeConnected = typeof runtime?.mqttConnected === 'boolean'
    ? runtime.mqttConnected
    : appState.deviceInfo?.mqttConnected === true
  mqttStatus.connected = runtimeConnected
  mqttStatus.deviceCode = runtime?.deviceInfo?.deviceCode
    || appState.deviceInfo?.deviceCode
    || appState.settings?.deviceCode
    || appState.settings?.deviceId
    || ''
  mqttStatus.message = appState.runtime?.socket?.message
    || (runtimeConnected ? '服务器已连接' : '服务器未连接')
  if (runtime?.timestamp) mqttStatus.checkedAt = formatCheckedAt(runtime.timestamp)
}

async function refreshMqttStatus() {
  if (mqttStatus.loading) return
  auditClick('ADMIN_REFRESH_MQTT_STATUS', '刷新通信状态')
  mqttStatus.loading = true
  mqttStatus.error = ''
  try {
    // 原生状态和通信日志可立即刷新，不等待无关的授权 HTTP 查询完成。
    const mqttConnected = await services.refreshMqttConnectionProjection()
    applyMqttStatus({ mqttConnected, timestamp: Date.now() })
    loadCommLogs()
    applyMqttStatus(await services.getRuntime({ requireCommunicationPermission: true }))
    loadCommLogs()
  } catch (error) {
    applyMqttStatus()
    mqttStatus.error = error?.message || '通信状态读取失败'
  } finally {
    mqttStatus.loading = false
  }
}

function openMqttStatus() {
  mqttStatusVisible.value = true
  services.startMqttCommLogCapture()
  refreshMqttStatus()
  if (_logRefreshTimer) clearInterval(_logRefreshTimer)
  _logRefreshTimer = setInterval(loadCommLogs, 2000)
}

function closeMqttStatus() {
  mqttStatusVisible.value = false
  if (_logRefreshTimer) {
    clearInterval(_logRefreshTimer)
    _logRefreshTimer = null
  }
}

// ═══════════ MQTT 通信日志 ═══════════

const mqttLogs = ref([])
let _logRefreshTimer = null

function loadCommLogs() {
  mqttLogs.value = services.getMqttCommLogs(200)
}

function formatCommLogTime(ts) {
  const d = new Date(Number(ts))
  if (Number.isNaN(d.getTime())) return ''
  const pad = (v) => String(v).padStart(2, '0')
  return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
}

function commLogTypeLabel(type) {
  const map = { transport_connected: '通道连接', connected: '连接', disconnected: '断开', message_tx: '发送', message_rx: '收到', error: '错误' }
  return map[type] || type
}

function clearCommLogs() {
  services.clearMqttCommLogs()
  mqttLogs.value = []
}

/* ═══════════ 审计埋点 ═══════════ */

function auditClick(actionCode, actionLabel) {
  services.recordAuditEvent({ event_type: 'BUTTON_CLICK', action_code: actionCode, action_label: actionLabel })
}

function auditFeatureEnter() {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'ADMIN_DASHBOARD', feature_label: '管理首页' })
}

onMounted(() => { auditFeatureEnter() })

onUnmounted(() => {
  if (_logRefreshTimer) {
    clearInterval(_logRefreshTimer)
    _logRefreshTimer = null
  }
})

</script>

<style scoped>
/* ═══════ 根 ═══════ */
.admin-dashboard { width:100%; min-height:100vh; min-height:100dvh; height:100vh; height:100dvh; display:flex; flex-direction:column; background:#eef2f7; overflow:hidden; }

/* ═══════ Tab 栏 ═══════ */
.tab-bar {
  width: min(calc(100% - clamp(24px, 4vw, 72px)), 1520px);
  margin: clamp(8px, 1.2vw, 16px) auto 0;
  flex: 0 0 auto;
  display: flex;
  background: #fff;
  border: 1px solid #dfe5ed;
  border-radius: 10px;
  box-sizing: border-box;
  overflow: hidden;
}
.tab-item {
  min-width: 0;
  height: 60px;
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: #718096;
  cursor: pointer;
  transition: background-color .15s ease, color .15s ease;
}
.tab-item + .tab-item { border-left: 1px solid #edf0f4; }
.tab-item::after { content: ''; position: absolute; right: 22px; bottom: 0; left: 22px; height: 4px; border-radius: 4px 4px 0 0; background: transparent; }
.tab-item.active { color: #245fae; background: #f4f7fb; }
.tab-item.active::after { background: #2f6fbf; }
.tab-icon { width: 24px; height: 24px; flex: 0 0 auto; }
.tab-label { color: inherit; font-size: 16px; font-weight: 650; line-height: 1; white-space: nowrap; }

/* ═══════ 滚动内容 ═══════ */
.tab-scroll { flex:1 1 auto; min-height:0; overflow-y:auto; }
.tab-content { width:min(calc(100% - clamp(24px, 4vw, 72px)), 1520px); margin:0 auto; padding:clamp(14px, 1.7vw, 24px) 0 clamp(20px, 2vw, 32px); box-sizing:border-box; }
.tab-panel { display:flex; flex-direction:column; gap:clamp(14px, 1.5vw, 22px); }

/* ═══════ 系统设置 / 卡片网格 ═══════ */
.sys-sec { margin-bottom:2px; }
.sec-title { display:block; color:#454d58; font-size:14px; font-weight:600; margin:0 0 10px 1px; }
.sys-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:clamp(12px, 1.2vw, 18px); }

/* ═══════ MQTT 状态 ═══════ */
.mqtt-status-panel { width:100%; padding:24px; box-sizing:border-box; }
.mqtt-status-head { display:flex; align-items:center; gap:14px; }
.mqtt-status-icon { width:48px; height:48px; flex:0 0 auto; display:flex; align-items:center; justify-content:center; padding:12px; box-sizing:border-box; border-radius:8px; color:#60738d; background:#edf1f5; }
.mqtt-status-icon.online { color:#11865f; background:#e7f6f0; }
.mqtt-status-copy { min-width:0; display:flex; flex-direction:column; gap:5px; }
.mqtt-status-title { color:#1d2a3d; font-size:18px; font-weight:700; line-height:1.25; }
.mqtt-status-summary { color:#718096; font-size:13px; line-height:1.4; overflow-wrap:anywhere; }
.mqtt-status-list { margin-top:20px; border-top:1px solid #e6ebf1; }
.mqtt-status-row { min-height:44px; display:flex; align-items:center; justify-content:space-between; gap:16px; border-bottom:1px solid #e6ebf1; }
.mqtt-status-label { flex:0 0 auto; color:#718096; font-size:13px; }
.mqtt-status-value { min-width:0; color:#27364a; font-size:13px; font-weight:600; text-align:right; overflow-wrap:anywhere; }
.mqtt-status-value.online { color:#11865f; }
.mqtt-status-value.offline { color:#c64141; }
.mqtt-status-error { display:block; margin-top:12px; color:#c64141; font-size:12px; line-height:1.5; overflow-wrap:anywhere; }
.mqtt-refresh-button { width:100%; height:42px; margin-top:18px; padding:0 16px; display:flex; align-items:center; justify-content:center; gap:7px; border:0; border-radius:8px; background:#246fca; color:#fff; font-size:14px; font-weight:600; line-height:1; }
.mqtt-refresh-button::after { display:none; }
.mqtt-refresh-button[disabled] { opacity:.55; }
.mqtt-refresh-icon { width:16px; height:16px; flex:0 0 auto; }

/* ═══════ MQTT 通信日志 ═══════ */
.mqtt-log-section { margin-top: 16px; border-top: 1px solid #e6ebf1; padding-top: 12px; }
.mqtt-log-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.mqtt-log-title { color: #718096; font-size: 12px; font-weight: 600; }
.mqtt-log-clear { color: #246fca; font-size: 11px; cursor: pointer; }
.mqtt-log-list { max-height: min(46vh, 420px); overflow: hidden; }
.mqtt-log-item { padding: 5px 6px; display: flex; align-items: flex-start; gap: 8px; border-bottom: 1px solid #f0f3f7; font-size: 12px; line-height: 1.4; }
.mqtt-log-item:last-child { border-bottom: 0; }
.mqtt-log-time { flex: 0 0 auto; color: #a0aec0; font-size: 11px; font-family: monospace; min-width: 62px; }
.mqtt-log-type { flex: 0 0 auto; font-size: 11px; font-weight: 600; min-width: 32px; }
.mqtt-log-detail { flex: 1 1 auto; min-width: 0; color: #4a5568; overflow-wrap: anywhere; }
.log-connected .mqtt-log-type { color: #11865f; }
.log-disconnected .mqtt-log-type { color: #c64141; }
.log-message_tx .mqtt-log-type { color: #7a4ec4; }
.log-message_rx .mqtt-log-type { color: #2f6fbf; }
.log-error .mqtt-log-type { color: #c64141; }
.mqtt-log-empty { padding: 14px 0; text-align: center; color: #a0aec0; font-size: 12px; }

@media (max-width: 760px) {
  .tab-bar, .tab-content { width: calc(100% - 24px); }
  .tab-bar { margin-top: 8px; }
  .tab-content { padding-top: 16px; }
}

@media (max-width: 560px) {
  .tab-item { height: 58px; flex-direction: row; gap: 6px; }
  .tab-item::after { right: 12px; left: 12px; }
  .tab-icon { width: 20px; height: 20px; }
  .tab-label { font-size: 14px; }
  .tab-content { padding: 14px 0 22px; }
  .sys-grid { gap: 10px; }
  .tab-panel { gap: 14px; }
}

@media (max-width: 420px) {
  .sys-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

/* ═══════ 维护区二级弹窗 ═══════ */
.dialog-panel { width:100%; padding:26px; box-sizing:border-box; }
.dialog-heading { display:flex; align-items:center; gap:14px; padding-right:38px; }
.dialog-heading.centered { flex-direction:column; gap:10px; padding:0 38px; text-align:center; }
.dialog-icon { width:48px; height:48px; flex:0 0 auto; padding:12px; display:flex; align-items:center; justify-content:center; box-sizing:border-box; border-radius:10px; }
.dialog-icon.primary { color:#216ed0; background:#eaf2fd; }
.dialog-icon.danger { color:#c73545; background:#fdecef; }
.dialog-heading-copy { min-width:0; display:flex; flex-direction:column; gap:4px; }
.dialog-heading-copy.centered { align-items:center; }
.dialog-title { color:#1f2b3d; font-size:19px; font-weight:700; line-height:1.25; }
.dialog-subtitle { color:#7a8798; font-size:12px; line-height:1.4; }
.dialog-notice { margin-top:20px; padding:14px 15px; border:1px solid #f1d4d8; border-radius:8px; background:#fff7f8; color:#75525a; font-size:13px; line-height:1.6; }
.dialog-feedback { margin-top:12px; padding:12px 14px; display:flex; flex-direction:column; gap:4px; border:1px solid #dbe5f0; border-radius:8px; background:#f5f8fb; }
.dialog-feedback.success { border-color:#bfe2d4; background:#edf8f4; }
.dialog-feedback.warning { border-color:#ead9ad; background:#fff9e9; }
.dialog-feedback.error { border-color:#f0c6cc; background:#fff2f4; }
.dialog-feedback-title { color:#26364b; font-size:13px; font-weight:700; }
.dialog-feedback.success .dialog-feedback-title { color:#167658; }
.dialog-feedback.warning .dialog-feedback-title { color:#8b6a18; }
.dialog-feedback.error .dialog-feedback-title { color:#b63243; }
.dialog-feedback-message { color:#67768a; font-size:12px; line-height:1.5; overflow-wrap:anywhere; }
.dialog-actions { width:100%; margin-top:20px; display:grid; grid-template-columns:1fr 1fr; gap:10px; }
.dialog-button { min-width:0; height:44px; margin:0; border:0; border-radius:8px; display:flex; align-items:center; justify-content:center; font-size:14px; font-weight:600; line-height:1; }
.dialog-button.secondary { border:1px solid #d5dde7; background:#fff; color:#536277; }
.dialog-button.primary { background:#246fca; color:#fff; }
.dialog-button.danger { background:#d93b4d; color:#fff; }
.dialog-button.full { width:100%; margin-top:18px; }
.dialog-button[disabled] { opacity:.5; }

/* ═══════ 工程弹窗：升级 ═══════ */
.eng-upgrade-card, .eng-info-card { display:flex; flex-direction:column; align-items:stretch; }
.eng-file-list { width:100%; max-height:260px; margin-top:18px; overflow:auto; border:1px solid #e0e6ee; border-radius:8px; }
.eng-file-row { width:100%; display:flex; align-items:flex-start; gap:12px; padding:14px; border-bottom:1px solid #e5ebf3; box-sizing:border-box; cursor:pointer; }
.eng-file-row:last-child { border-bottom:0; }
.eng-file-row.selected { background:#f1f6fd; }
.eng-radio { width:17px; height:17px; border:2px solid #9babc0; border-radius:50%; margin-top:3px; flex:0 0 auto; }
.eng-radio.sel { border-color:#1f76ff; background:#1f76ff; box-shadow:inset 0 0 0 4px #fff; }
.eng-file-copy { min-width:0; display:flex; flex-direction:column; gap:6px; color:#27364a; font-size:13px; overflow-wrap:anywhere; }
.eng-file-copy text:last-child { color:#6f7e92; }
.eng-progress-wrap { width:100%; height:22px; border-radius:999px; background:#e7eef8; margin-top:20px; position:relative; overflow:hidden; }
.eng-progress-bar { height:100%; background:#246fca; }
.eng-progress-wrap text { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; font-size:12px; }
.dialog-state { width:100%; min-height:104px; margin-top:18px; padding:18px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:6px; box-sizing:border-box; border:1px dashed #cfd8e4; border-radius:8px; background:#f8fafc; text-align:center; }
.dialog-state.error { border-color:#edc6cc; background:#fff5f6; }
.dialog-state.available { border-color:#bdd5f5; background:#f2f7ff; }
.dialog-state.info { min-height:88px; border-style:solid; }
.dialog-state-title { color:#35455b; font-size:14px; font-weight:700; }
.dialog-state-message { max-width:100%; color:#718096; font-size:12px; line-height:1.55; overflow-wrap:anywhere; }
.dialog-state-code { max-width:100%; color:#9a3443; font-size:11px; line-height:1.5; overflow-wrap:anywhere; }
.dialog-state-code.available { color:#246fca; font-size:12px; }
.inline-retry-button { width:auto; min-width:92px; height:34px; margin-top:6px; padding:0 14px; border:1px solid #b9cce5; border-radius:7px; background:#fff; color:#246fca; font-size:12px; }

@media (max-width:560px) {
  .dialog-panel { padding:22px 18px 18px; }
  .dialog-title { font-size:17px; }
  .dialog-actions { grid-template-columns:1fr; }
  .dialog-heading.centered { padding:0 30px; }
}
</style>
