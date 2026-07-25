<template>
  <view class="page-root home-page">
    <CabinetHeader
      :cabinet-number="appState.settings.cabinetNumber"
      @sync="openStatusPanel"
      @user="passwordVisible = true"
    />

    <scroll-view class="slot-scroll" scroll-y>
      <view class="slot-grid">
        <SlotCard v-for="slot in orderedSlots" :key="slot.id" :slot="slot" />
      </view>
    </scroll-view>

    <view class="home-footer">
      <view class="home-stats">
        <StatusLegend class="legend-group" :items="leftLegendItems" :total="displaySlots.length" :columns="1" />
        <view class="legend-separator" aria-hidden="true"></view>
        <StatusLegend class="legend-group" :items="rightLegendItems" :total="displaySlots.length" :columns="1" />
      </view>
      <button class="primary-gradient-button start-button" @click="methodVisible = true">开始取卡</button>
    </view>

    <PasswordModal v-if="passwordVisible" @close="passwordVisible = false" @submit="login" />

    <ModalShell v-if="statusPanelVisible" closable close-on-mask @close="statusPanelVisible = false">
      <view class="status-report-card">
        <view class="status-report-icon"><IconGlyph name="refresh" /></view>
        <text class="status-report-title">卡槽状态上报</text>
        <text class="status-report-copy">将 Android 卡槽 Map 中已经确认的状态提交到现有 MQTT statusReport 发送链。</text>

        <view class="report-status-list">
          <view class="report-status-row">
            <text>后端会话</text>
            <text class="report-value" :class="backendStateClass">{{ backendStateText }}</text>
          </view>
          <view class="report-status-row">
            <text>本地结果</text>
            <text class="report-value" :class="reportStateClass">{{ reportStateText }}</text>
          </view>
          <view class="report-status-row">
            <text>已知卡槽</text>
            <text class="report-value">{{ statusReport.knownSlotCount || 0 }}</text>
          </view>
          <view class="report-status-row">
            <text>最后时间</text>
            <text class="report-value">{{ reportTime }}</text>
          </view>
        </view>

        <view class="report-message">{{ statusReport.message || '尚未手动上报卡槽状态' }}</view>
        <view class="report-warning">“已提交”只表示进入客户端发送链，不代表服务端已经返回 code=0。</view>

        <button class="primary-gradient-button report-button" :disabled="reporting" @click="submitStatusReport">
          {{ reporting ? '正在提交...' : '立即上报卡槽状态' }}
        </button>
      </view>
    </ModalShell>

    <ModalShell v-if="methodVisible" closable close-on-mask @close="methodVisible = false">
      <view class="method-card">
        <text class="method-title">选择验证方式</text>
        <text class="method-copy">请选择一种安全验证方式继续</text>
        <view class="method-list">
          <view class="method-option face" @click="startRecognition('FACE')"><view class="method-icon"><IconGlyph name="face"/></view><view class="method-text"><text>人脸识别</text><text>使用前置摄像头验证</text></view><text class="method-arrow">›</text></view>
          <view class="method-option finger" @click="startRecognition('FINGERPRINT')"><view class="method-icon"><IconGlyph name="fingerprint"/></view><view class="method-text"><text>指纹识别</text><text>调用系统安全认证</text></view><text class="method-arrow">›</text></view>
        </view>
      </view>
    </ModalShell>

    <RecognitionModal v-if="recognition.visible" :type="recognition.type" :status="recognition.status" :status-message="recognition.message" :success-text="recognition.successText" :success-hint="recognition.successHint" @cancel="cancelRecognition" @finish="finishRecognition" />
  </view>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import CabinetHeader from '@/components/CabinetHeader.vue'
import SlotCard from '@/components/SlotCard.vue'
import StatusLegend from '@/components/StatusLegend.vue'
import PasswordModal from '@/components/PasswordModal.vue'
import ModalShell from '@/components/ModalShell.vue'
import RecognitionModal from '@/components/RecognitionModal.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { appState } from '@/state/appState.js'
import { services } from '@/services/index.js'
import { SLOT_STATUS, SLOT_STATUS_META } from '@/constants/app.js'

const HOME_SLOT_COUNT = 60
const HOME_COLUMNS = 6
const HOME_ROWS = 10

const passwordVisible = ref(false)
const methodVisible = ref(false)
const statusPanelVisible = ref(false)
const reporting = ref(false)
const recognition = reactive({ visible: false, type: 'FACE', status: 'DETECTING', message: '', successText: '4号卡门已开锁', successHint: '请您尽快完成现场操作' })

onMounted(async () => {
  try {
    const slots = await services.getSlots()
    appState.slots.splice(0, appState.slots.length, ...slots)
  } catch (error) {}
})

const displaySlots = computed(() => appState.slots.slice(0, HOME_SLOT_COUNT))
const orderedSlots = computed(() => {
  const result = []
  for (let row = 0; row < HOME_ROWS; row += 1) {
    for (let col = 0; col < HOME_COLUMNS; col += 1) {
      const index = col * HOME_ROWS + row
      if (index < displaySlots.value.length) result.push(displaySlots.value[index])
    }
  }
  return result
})
const count = (status) => displaySlots.value.filter((item) => item.status === status).length
const leftLegendItems = computed(() => [
  { key: 'empty', label: '空卡', color: SLOT_STATUS_META.EMPTY.color, count: count(SLOT_STATUS.EMPTY) },
  { key: 'occupied', label: '有卡', color: SLOT_STATUS_META.OCCUPIED.color, count: count(SLOT_STATUS.OCCUPIED) },
  { key: 'charging', label: '充电中', color: SLOT_STATUS_META.CHARGING.color, count: count(SLOT_STATUS.CHARGING) },
  { key: 'full', label: '已充满', color: SLOT_STATUS_META.FULL.color, count: count(SLOT_STATUS.FULL) }
])
const rightLegendItems = computed(() => [
  { key: 'illegal', label: '非法卡', color: SLOT_STATUS_META.ILLEGAL_CARD.color, count: count(SLOT_STATUS.ILLEGAL_CARD) },
  { key: 'chargeFault', label: '充电故障', color: SLOT_STATUS_META.CHARGING_FAULT.color, count: count(SLOT_STATUS.CHARGING_FAULT) },
  { key: 'comm', label: '通信故障', color: SLOT_STATUS_META.COMMUNICATION_FAULT.color, count: count(SLOT_STATUS.COMMUNICATION_FAULT) }
])

const statusReport = computed(() => appState.runtime.statusReport || {})
const backendState = computed(() => appState.runtime.socket?.state || 'DISCONNECTED')
const backendStateText = computed(() => backendState.value === 'AUTHENTICATED' ? '已认证' : (appState.runtime.socket?.message || backendState.value))
const backendStateClass = computed(() => backendState.value === 'AUTHENTICATED' ? 'ok' : 'warn')
const reportStateText = computed(() => ({
  IDLE: '尚未上报',
  SUBMITTING: '正在提交',
  BLOCKED: '未发送',
  NO_DATA: '无可上报数据',
  SUBMITTED: '已提交发送链',
  FAILED: '本地发送失败'
}[statusReport.value.state] || statusReport.value.state || '尚未上报'))
const reportStateClass = computed(() => ({ SUBMITTED: 'ok', SUBMITTING: 'pending', IDLE: 'muted' }[statusReport.value.state] || 'warn'))
const reportTime = computed(() => {
  const value = statusReport.value.submittedAt || statusReport.value.failedAt || statusReport.value.requestedAt
  if (!value) return '—'
  const date = new Date(Number(value))
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
})

const openStatusPanel = async () => {
  statusPanelVisible.value = true
  try { await services.getRuntime() } catch (error) {}
}

const submitStatusReport = async () => {
  if (reporting.value) return
  reporting.value = true
  try {
    await services.reportStatusNow()
  } catch (error) {
    appState.runtime.statusReport = {
      state: 'FAILED',
      message: error.message || '状态上报请求失败',
      knownSlotCount: 0,
      failedAt: Date.now()
    }
  } finally {
    reporting.value = false
  }
}

const login = async (password, helpers) => {
  try {
    await services.login(password)
    passwordVisible.value = false
    uni.navigateTo({ url: '/pages/admin/admin' })
  } catch (error) {
    helpers.setError(error.message || '密码错误')
    helpers.clear()
  }
}

const startRecognition = async (type) => {
  methodVisible.value = false
  recognition.visible = true
  recognition.type = type
  recognition.status = 'PREPARING'
  recognition.message = ''
  try {
    const result = await services.runRecognition(type, applyRecognitionProgress)
    if (result.status === 'UNREGISTERED') {
      recognition.visible = false
      uni.showModal({
        title: '未登记人脸',
        content: '请先录入职员信息和人脸，再进行取卡操作。',
        confirmText: '去录入',
        success: ({ confirm }) => { if (confirm) uni.navigateTo({ url: '/pages/biometric/face?source=recognition' }) }
      })
      return
    }
    recognition.status = 'SUCCESS'
    recognition.message = ''
    recognition.successText = result.slotNumber ? `${result.slotNumber}号卡门已开锁` : (result.message || '人脸验证成功')
    recognition.successHint = type === 'FINGERPRINT' ? '指纹验证成功，可以继续取卡操作。' : '请您尽快完成现场操作'
    if (type !== 'FINGERPRINT') setTimeout(() => { recognition.visible = false }, 2600)
  } catch (error) {
    recognition.status = 'ERROR'
    recognition.message = error.message || '识别失败'
    setTimeout(() => { recognition.visible = false }, 2400)
  }
}
const applyRecognitionProgress = (progress) => {
  if (typeof progress === 'string') {
    recognition.status = progress
    recognition.message = ''
    return
  }
  recognition.status = progress?.status || recognition.status
  recognition.message = progress?.message || ''
}
const cancelRecognition = async () => {
  const type = recognition.type
  recognition.visible = false
  await services.cancelRecognition(type)
}
const finishRecognition = () => {
  recognition.visible = false
}
</script>

<style scoped>
.home-page { background: #e6f0ff; }
.slot-scroll { flex: 1; min-height: 0; }
.slot-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: clamp(4px, .7vw, 8px);
  padding: clamp(5px, .85vw, 10px);
  align-content: start;
}
.home-footer {
  flex: 0 0 auto;
  height: clamp(73px, 18.625vw, 149px);
  min-height: 0;
  background: #1f76ff;
  padding-block: clamp(7px, 2.25vw, 18px);
  padding-inline: clamp(20px, 5.5vw, 44px);
  display: grid;
  grid-template-columns: max-content clamp(116px, 29.5vw, 236px);
  justify-content: space-between;
  column-gap: clamp(12px, 4vw, 32px);
  align-items: center;
  overflow: hidden;
}
.home-stats {
  min-width: 0;
  width: max-content;
  display: grid;
  grid-template-columns: max-content 1px max-content;
  column-gap: clamp(7px, 1.75vw, 14px);
  align-items: stretch;
}
.legend-group {
  min-width: 0;
  width: max-content;
  --legend-track-width: clamp(44px, 11.25vw, 90px);
  --legend-track-height: clamp(5px, 1.25vw, 10px);
  --legend-count-width: clamp(10px, 2.5vw, 20px);
  --legend-count-gap: clamp(3px, .75vw, 6px);
  --legend-label-gap: clamp(3px, .875vw, 7px);
  --legend-font-size: clamp(8px, 2vw, 16px);
  --legend-row-gap: clamp(4px, 1.125vw, 9px);
}
.legend-separator { width: 1px; height: 82%; align-self: center; background: rgba(255, 255, 255, .18); }
.start-button { width: 100%; height: clamp(39px, 10vw, 80px); border-radius: clamp(8px, 2vw, 16px); font-size: clamp(13px, 3.5vw, 28px); white-space: nowrap; }
.status-report-card { width:min(84vw, 480px); padding:30px 26px 25px; display:flex; flex-direction:column; align-items:center; }
.status-report-icon { width:64px; height:64px; border-radius:18px; display:flex; align-items:center; justify-content:center; background:#e2edff; color:#1f76ff; }
.status-report-icon :deep(svg) { width:34px; height:34px; }
.status-report-title { margin-top:16px; font-size:22px; font-weight:650; color:#27364d; }
.status-report-copy { margin-top:8px; color:#7a899e; font-size:13px; line-height:1.6; text-align:center; }
.report-status-list { width:100%; margin-top:22px; border-radius:14px; background:#f5f8fc; padding:4px 16px; box-sizing:border-box; }
.report-status-row { min-height:48px; display:flex; align-items:center; justify-content:space-between; gap:14px; border-bottom:1px solid #e6edf6; color:#5a6b82; font-size:14px; }
.report-status-row:last-child { border-bottom:0; }
.report-value { color:#33445b; font-weight:600; text-align:right; overflow-wrap:anywhere; }
.report-value.ok { color:#08a944; }.report-value.warn { color:#e66a32; }.report-value.pending { color:#1f76ff; }.report-value.muted { color:#8795a8; }
.report-message { width:100%; margin-top:16px; padding:12px 14px; border-radius:12px; background:#edf5ff; color:#365677; font-size:13px; line-height:1.55; box-sizing:border-box; text-align:center; }
.report-warning { margin-top:10px; color:#8b6a3f; font-size:12px; line-height:1.55; text-align:center; }
.report-button { width:100%; height:52px; margin-top:20px; border-radius:12px; }
.method-card { padding: 34px 28px 28px; width: min(82vw, 440px); }
.method-title { display: block; text-align: center; font-size: clamp(20px, 2.7vw, 24px); font-weight: 650; color:#27364d; }
.method-copy { display:block; text-align:center; color:#8290a3; font-size:13px; margin-top:9px; }
.method-list { display: flex; flex-direction:column; gap: 12px; margin-top: 25px; }
.method-option { min-height: 82px; padding: 12px 15px; border-radius: 16px; display:flex; align-items:center; gap:13px; background:#f4f8ff; border:1px solid #dce9fc; color:#27364d; cursor:pointer; }
.method-option.finger { background:#f1fcfd; border-color:#c8edf0; }
.method-icon { width:52px; height:52px; border-radius:14px; display:flex; align-items:center; justify-content:center; background:#e1ecff; color:#1f76ff; flex:0 0 auto; }
.finger .method-icon { background:#dbf7f7; color:#00bfc8; }
.method-icon svg { width:30px; height:30px; }
.method-text { min-width:0; display:flex; flex-direction:column; gap:5px; }
.method-text text:first-child { font-size:16px; font-weight:600; }.method-text text:last-child { font-size:12px; color:#8290a3; }
.method-arrow { margin-left:auto; font-size:29px; line-height:1; color:#9aafcb; }
</style>