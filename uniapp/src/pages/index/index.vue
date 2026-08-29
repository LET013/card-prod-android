<template>
  <view class="page-root home-page">
    <CabinetHeader
      :cabinet-number="displayCabinetNumber"
      :activated="appState.deviceInfo.activated"
      :mqtt-online="appState.deviceInfo.mqttConnected"
      @user="openAdminLogin"
    />
    <GlobalNoticeBar />

    <view class="home-grid-stage">
      <CabinetSlotGrid
        :slots="displaySlots"
        :group-size="appState.settings.groupSize || appState.settings.singleGroupCount"
        :sort-direction="appState.settings.slotSortDirection"
        :active-slot-number="slotOperation.slotNumber"
        :operation-effect="slotOperation.effect"
        :operation-effects="appState.cabinetOperationEffects"
      />
    </view>

    <view class="home-footer">
      <view class="home-stats">
        <StatusLegend class="legend-group" :items="leftLegendItems" :total="displaySlots.length" :columns="1" />
        <view class="legend-separator" aria-hidden="true"></view>
        <StatusLegend class="legend-group" :items="rightLegendItems" :total="displaySlots.length" :columns="1" />
      </view>
      <button class="primary-gradient-button start-button" @click="startTakeCard">开始取卡</button>
    </view>

    <PasswordModal v-if="passwordVisible" @close="closeLogin" @submit="login" />

    <RecognitionModal v-if="recognition.visible" :type="recognition.type" :status="recognition.status" :status-message="recognition.message" :success-text="recognition.successText" :success-hint="recognition.successHint" :operation-mode="recognition.operationMode" :slot-number="recognition.slotNumber" @cancel="cancelRecognition" @finish="finishRecognition" />
  </view>
</template>

<script setup>
import { computed, reactive, ref, watch, onMounted, onUnmounted } from 'vue'
import CabinetHeader from '@/components/CabinetHeader.vue'
import GlobalNoticeBar from '@/components/GlobalNoticeBar.vue'
import CabinetSlotGrid from '@/components/CabinetSlotGrid.vue'
import StatusLegend from '@/components/StatusLegend.vue'
import PasswordModal from '@/components/PasswordModal.vue'
import RecognitionModal from '@/components/RecognitionModal.vue'
import {
  appState,
  setGlobalNotice,
  getSlotProjection,
  upsertSlotProjection,
  replaceDeviceInfoProjection
} from '@/state/appState.js'
import { services } from '@/services/index.js'
import { SLOT_STATUS, SLOT_STATUS_META } from '@/constants/app.js'
import { summarizeSlotStatuses } from '@/state/slotProjection.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'
import {
  createTakeCardResultPresentation,
  TAKE_CARD_RESULT
} from '@/state/takeCardResult.js'

const passwordVisible = ref(false)
const postLoginRoute = ref('/pages/admin/admin')
const recognition = reactive({ visible: false, type: 'FACE', status: 'DETECTING', message: '', successText: '验证完成', successHint: '请按提示继续操作', operationLocked: false, operationMode: false, slotNumber: null })
const slotOperation = reactive({ slotNumber: null, effect: '' })
const unsubs = []
let slotOperationTimer = null
let recognitionTimer = null
let initialSlotStatusTimer = null
let stopInitialSlotStatusWatch = null
let takeCardInFlight = false
let finalTakeResultShown = false
let takeCardSuccessAnnounced = false

// 仅限制首屏占位时长；它不是串口超时，也不改变后续实时状态处理。
const INITIAL_SLOT_STATUS_TIMEOUT_MS = 8000

const clearRecognitionTimer = () => {
  if (recognitionTimer) clearTimeout(recognitionTimer)
  recognitionTimer = null
}

const hideRecognition = () => {
  clearRecognitionTimer()
  recognition.visible = false
  recognition.operationLocked = false
  recognition.operationMode = false
  recognition.slotNumber = null
}

const scheduleRecognitionClose = (delayMs) => {
  clearRecognitionTimer()
  recognitionTimer = setTimeout(hideRecognition, delayMs)
}

const clearSlotOperationEffect = () => {
  if (slotOperationTimer) clearTimeout(slotOperationTimer)
  slotOperationTimer = null
  slotOperation.slotNumber = null
  slotOperation.effect = ''
}

const TAKE_FAILURE_STATES = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED'])
const NO_CARD_ERROR_CODES = new Set([
  'NO_TAKEABLE_CARD',
  'CARD_NOT_PRESENT'
])
const PRESERVED_BLOCKING_ERROR_CODES = new Set([
  'TAKE_CARD_IN_PROGRESS'
])

const presentTakeCardResult = (presentation) => {
  finalTakeResultShown = true
  recognition.visible = true
  recognition.operationMode = true
  recognition.operationLocked = false
  recognition.status = presentation.status
  recognition.slotNumber = presentation.slotNumber
  recognition.message = presentation.message
  recognition.successText = presentation.message
  recognition.successHint = ''

  if ((presentation.status === 'TAKE_ERROR' || presentation.status === 'NO_CARD') && !takeCardSuccessAnnounced) {
    services.announceTakeCardFailure()
  }

  clearSlotOperationEffect()
  if (presentation.effect && presentation.slotNumber) {
    slotOperation.slotNumber = presentation.slotNumber
    slotOperation.effect = presentation.effect
    slotOperationTimer = setTimeout(clearSlotOperationEffect, 3000)
  }
  scheduleRecognitionClose(3000)
}

const presentCardPresented = (slotNumber, message) => {
  recognition.visible = true
  recognition.operationMode = true
  recognition.operationLocked = true
  recognition.status = 'CARD_PRESENTED'
  recognition.slotNumber = slotNumber
  recognition.message = message
  clearSlotOperationEffect()
  if (slotNumber) {
    slotOperation.slotNumber = slotNumber
    slotOperation.effect = 'success'
    slotOperationTimer = setTimeout(clearSlotOperationEffect, 3000)
  }
}

const normalizeSlotForProjection = (slot = {}) => {
  const slotNumber = Number(slot.slotNumber ?? slot.slotId ?? slot.address)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return null
  const incomingUpdatedAt = Number(slot.updatedAt ?? slot.updated_at)
  return {
    ...slot,
    slotNumber,
    id: slot.id || `slot-${slotNumber}`,
    displayNumber: slot.displayNumber || String(slotNumber).padStart(2, '0'),
    source: slot.source || 'SERIAL',
    fresh: true,
    updatedAt: Number.isFinite(incomingUpdatedAt) && incomingUpdatedAt > 0 ? incomingUpdatedAt : Date.now()
  }
}

const applyDeviceInfo = (info) => { replaceDeviceInfoProjection(info) }

const applyCachedSlots = (slots) => {
  if (!Array.isArray(slots)) return false
  let applied = false
  slots.forEach((slot) => {
    const normalized = normalizeSlotForProjection(slot)
    if (!normalized) return
    const current = getSlotProjection(normalized.slotNumber)
    // 缓存只能补齐尚未收到实时串口状态的卡位。
    if (current?.fresh) return
    upsertSlotProjection({ ...normalized, fresh: Boolean(slot?.fresh) })
    applied = true
  })
  return applied
}

const restoreCachedSlotsWhenSerialUnavailable = async () => {
  const [slots, serial] = await Promise.all([
    services.loadCachedSlots(),
    services.getSerialStatus()
  ])
  if (String(serial?.state || '').trim().toUpperCase() === 'CONNECTED') return
  if (applyCachedSlots(slots)) {
    setGlobalNotice('串口暂不可用，当前显示上次缓存状态', 'info')
  }
}

const clearInitialSlotStatusTimer = () => {
  if (initialSlotStatusTimer) clearTimeout(initialSlotStatusTimer)
  initialSlotStatusTimer = null
}

const markUnresolvedSlotsUnknown = () => {
  initialSlotStatusTimer = null
  appState.slots.forEach((slot) => {
    if (String(slot?.status || '').trim().toUpperCase() !== SLOT_STATUS.LOADING) return
    upsertSlotProjection({ ...slot, status: SLOT_STATUS.UNKNOWN })
  })
}

const scheduleInitialSlotStatusTimeout = () => {
  clearInitialSlotStatusTimer()
  if (!appState.slots.some((slot) => String(slot?.status || '').trim().toUpperCase() === SLOT_STATUS.LOADING)) return
  initialSlotStatusTimer = setTimeout(markUnresolvedSlotsUnknown, INITIAL_SLOT_STATUS_TIMEOUT_MS)
}

onMounted(() => {
  services.init()
  // main.js 已是全局唯一的串口状态投影和缓存入口；首页不可重复订阅，
  // 否则同一批轮询数据会再次触发 SQLite 合并写入。
  unsubs.push(services.on('device.info', applyDeviceInfo))
  // 配置总数先生成“加载中”网格；只有串口不可用时才降级显示上次缓存状态。
  restoreCachedSlotsWhenSerialUnavailable().catch(() => {})
  stopInitialSlotStatusWatch = watch(
    () => appState.slots.length,
    scheduleInitialSlotStatusTimeout,
    { immediate: true }
  )
  // 初始信息拉取在后台完成，不阻塞已显示的卡柜。
  services.bootstrapDeviceInfo().then((info) => {
    if (info) replaceDeviceInfoProjection(info)
  }).catch((e) => console.warn('bootstrapDeviceInfo failed:', e))
  services.refreshMqttConnectionProjection()
    .catch((e) => console.warn('refresh MQTT connection projection failed:', e))
})

onUnmounted(() => {
  unsubs.forEach((fn) => fn?.())
  unsubs.length = 0
  clearRecognitionTimer()
  clearInitialSlotStatusTimer()
  stopInitialSlotStatusWatch?.()
  stopInitialSlotStatusWatch = null
  if (slotOperationTimer) clearTimeout(slotOperationTimer)
  slotOperationTimer = null
})

const totalSlots = computed(() => {
  const value = Number(appState.settings.totalSlots || appState.settings.totalCount)
  return Number.isInteger(value) && value > 0 ? value : 0
})
const displayCabinetNumber = computed(() =>
  appState.deviceInfo.deviceCode ||
  appState.settings.cabinetNumber ||
  appState.settings.deviceCode ||
  appState.settings.deviceId ||
  '未配置'
)
// appState 已按 totalSlots 补齐并保持槽号顺序，页面直接消费稳定投影，避免每个状态批次重复排序。
const displaySlots = computed(() => appState.slots)
const LEGEND_STATUSES = new Set([
  SLOT_STATUS.EMPTY,
  SLOT_STATUS.OCCUPIED,
  SLOT_STATUS.CHARGING,
  SLOT_STATUS.FULL,
  SLOT_STATUS.ILLEGAL_CARD,
  SLOT_STATUS.CHARGING_FAULT,
  SLOT_STATUS.COMMUNICATION_FAULT,
  SLOT_STATUS.UNKNOWN
])
const normalizeLegendStatus = (status) => {
  if (status === SLOT_STATUS.LOADING) return null
  return LEGEND_STATUSES.has(status) ? status : SLOT_STATUS.UNKNOWN
}
const slotStatusCounts = computed(() => summarizeSlotStatuses(displaySlots.value))
const count = (status) => slotStatusCounts.value[normalizeLegendStatus(status)] || 0
const legendItem = (key, status) => ({
  key,
  label: SLOT_STATUS_META[status].label,
  color: SLOT_STATUS_META[status].color,
  gradient: SLOT_STATUS_META[status].gradient,
  count: count(status)
})
const leftLegendItems = computed(() => [
  legendItem('empty', SLOT_STATUS.EMPTY),
  legendItem('occupied', SLOT_STATUS.OCCUPIED),
  legendItem('charging', SLOT_STATUS.CHARGING),
  legendItem('full', SLOT_STATUS.FULL)
])
const rightLegendItems = computed(() => [
  legendItem('illegal', SLOT_STATUS.ILLEGAL_CARD),
  legendItem('chargeFault', SLOT_STATUS.CHARGING_FAULT),
  legendItem('comm', SLOT_STATUS.COMMUNICATION_FAULT),
  legendItem('unknown', SLOT_STATUS.UNKNOWN)
])

const login = async (password, helpers) => {
  try {
    const session = await services.loginLocal(password)
    passwordVisible.value = false
    const target = session?.needsPasswordChange
      ? '/pages/admin/change-password?force=1'
      : postLoginRoute.value
    postLoginRoute.value = '/pages/admin/admin'
    uni.navigateTo({
      url: target,
      success: () => {
        if (target === '/pages/admin/admin') services.announceAdminWelcome(session?.credentialLabel)
      }
    })
  } catch (error) {
    helpers.setError(toUserErrorMessage(error, '密码错误'))
    helpers.clear()
  }
}

const openAdminLogin = () => {
  postLoginRoute.value = '/pages/admin/admin'
  passwordVisible.value = true
}

const closeLogin = () => {
  postLoginRoute.value = '/pages/admin/admin'
  passwordVisible.value = false
}

// 当前仅开放摄像头人脸认证，单一认证方式直接进入识别。
const startTakeCard = () => startRecognition('FACE')

const startRecognition = async (type) => {
  if (takeCardInFlight) {
    uni.showToast({ title: '取卡操作正在处理中，请勿重复操作', icon: 'none' })
    return
  }
  clearRecognitionTimer()
  clearSlotOperationEffect()
  finalTakeResultShown = false
  takeCardSuccessAnnounced = false
  recognition.visible = true
  recognition.type = type
  recognition.status = 'PREPARING'
  recognition.message = ''
  recognition.operationLocked = false
  recognition.operationMode = false
  recognition.slotNumber = null
  recognition.successText = type === 'FINGERPRINT' ? '系统指纹验证完成' : '验证完成'
  recognition.successHint = '请按提示继续操作'
  let faceVerified = false
  try {
    const result = await services.runRecognition(type, applyRecognitionProgress)
    if (result?.status === 'ORGANIZATION_UNAUTHORIZED') {
      hideRecognition()
      uni.showModal({
        title: '未授权组织',
        content: result.message,
        showCancel: false,
        confirmText: '知道了'
      })
      return
    }
    if (result?.status === 'UNREGISTERED') {
      hideRecognition()
      uni.showModal({
        title: '未登记人脸',
        content: '请先录入职员信息和人脸，再进行取卡操作。',
        confirmText: '去录入',
        success: ({ confirm }) => {
          if (!confirm) return
          postLoginRoute.value = '/pages/biometric/face?source=recognition'
          passwordVisible.value = true
        }
      })
      return
    }
    if (!result || result.accepted === false) {
      const message = result?.message || (type === 'FINGERPRINT' ? '系统指纹验证未完成' : '识别未完成')
      throw new Error(message)
    }
    if (type === 'FACE') {
      faceVerified = true
      takeCardInFlight = true
      recognition.operationLocked = true
      recognition.operationMode = true
      recognition.visible = true
      recognition.status = 'FACE_VERIFIED'
      recognition.message = '识别成功，正在为您出卡'
      const takeResult = await services.takeCard(result, applyTakeProgress)
      if (!finalTakeResultShown) {
        presentTakeCardResult(createTakeCardResultPresentation({
          outcome: TAKE_CARD_RESULT.SUCCESS,
          slotNumber: takeResult.slotNumber,
          batteryPercent: takeResult.batteryPercent
        }))
      }
      return
    }
    recognition.status = 'SUCCESS'
    recognition.message = ''
    recognition.successText = result.slotNumber
      ? `${result.slotNumber}号卡门已开锁`
      : (result.message || (type === 'FINGERPRINT' ? '系统指纹验证成功' : '人脸验证成功'))
    recognition.successHint = result.canCompleteTake === false
      ? (result.closeLoopMessage || '系统指纹只能确认本机用户，暂不能完成员工级取卡闭环。')
      : '请您尽快完成现场操作'
    if (type !== 'FINGERPRINT') scheduleRecognitionClose(2600)
  } catch (error) {
    console.log('startRecognition error:', error);
    recognition.operationLocked = false
    if (faceVerified && !finalTakeResultShown) {
      if (NO_CARD_ERROR_CODES.has(error?.code)) {
        presentTakeCardResult(createTakeCardResultPresentation({ outcome: TAKE_CARD_RESULT.NO_CARD }))
      } else {
        const presentation = createTakeCardResultPresentation({
          outcome: TAKE_CARD_RESULT.FAILURE,
          slotNumber: recognition.slotNumber
        })
        if (PRESERVED_BLOCKING_ERROR_CODES.has(error?.code)) {
          presentation.message = toUserErrorMessage(error, presentation.message)
        }
        presentTakeCardResult(presentation)
      }
    } else if (!faceVerified) {
      recognition.status = 'ERROR'
      recognition.message = toUserErrorMessage(error, '识别失败')
      scheduleRecognitionClose(2400)
    }
  } finally {
    takeCardInFlight = false
  }
}
const applyTakeProgress = (progress = {}) => {
  const slotNumber = Number(progress.slotNumber)
  const state = String(progress.state || '').trim().toUpperCase()
  if (Number.isInteger(slotNumber) && slotNumber > 0) recognition.slotNumber = slotNumber
  if (state === 'CARD_PRESENTED_ANNOUNCEMENT') {
    const hasPhysicalSlotNumber = Number.isInteger(slotNumber) && slotNumber > 0
    if (hasPhysicalSlotNumber && !takeCardSuccessAnnounced) {
      takeCardSuccessAnnounced = true
      services.announceTakeCardSuccess(slotNumber)
    }
    return
  }
  if (state === 'CARD_PRESENTED') {
    if (!finalTakeResultShown) {
      presentTakeCardResult(createTakeCardResultPresentation({
        outcome: TAKE_CARD_RESULT.SUCCESS,
        slotNumber,
        batteryPercent: progress.batteryPercent,
        opened: progress.physicalConfirmed === false
      }))
    }
  } else if (state === 'PHYSICAL_CONFIRMED' && !finalTakeResultShown) {
    presentTakeCardResult(createTakeCardResultPresentation({
      outcome: TAKE_CARD_RESULT.SUCCESS,
      slotNumber,
      batteryPercent: progress.batteryPercent,
      opened: progress.physicalConfirmed === false
    }))
  } else if (TAKE_FAILURE_STATES.has(state) && !finalTakeResultShown) {
    presentTakeCardResult(createTakeCardResultPresentation({
      outcome: TAKE_CARD_RESULT.FAILURE,
      slotNumber
    }))
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
  if (recognition.operationLocked) {
    uni.showToast({ title: '已进入开门与卡位确认阶段，暂不能取消', icon: 'none' })
    return
  }
  const type = recognition.type
  hideRecognition()
  await services.cancelRecognition(type)
}
const finishRecognition = () => {
  hideRecognition()
}
</script>

<style scoped>
.home-page { background: #e6f0ff; }
.home-grid-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.home-grid-stage > :deep(.cabinet-slot-grid-host),
.home-grid-stage > :deep(.cabinet-slot-skeleton-host) {
  position: absolute;
  inset: 0;
}
.home-footer {
  flex: 0 0 auto;
  /* About 149px on the 800px design canvas, about 73px on a 393px phone. */
  height: clamp(73px, 18.625vw, 149px);
  min-height: 0;
  background: #1f76ff;
  padding-top: clamp(7px, 2.25vw, 18px);
  padding-bottom: clamp(7px, 2.25vw, 18px);
  padding-left: clamp(32px, 3.5vw, 72px);
  padding-right: clamp(32px, 3.5vw, 72px);
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
.legend-separator {
  width: 1px;
  height: 82%;
  align-self: center;
  background: rgba(255, 255, 255, .18);
}
.start-button {
  width: 100%;
  height: clamp(39px, 10vw, 80px);
  border-radius: clamp(8px, 2vw, 16px);
  border: 1px solid rgba(255,255,255,.42);
  background: linear-gradient(135deg, #6CF2A5 0%, #1EDDD3 50%, #10B7D8 100%);
  box-shadow: 0 12px 24px rgba(0, 107, 150, .30), inset 0 1px 1px rgba(255,255,255,.30);
  font-size: clamp(13px, 3.5vw, 28px);
  font-weight: 650;
  text-shadow: 0 1px 2px rgba(0,85,115,.22);
  white-space: nowrap;
}
@media (min-width:900px) and (orientation:landscape) {
  .home-footer { height:clamp(92px,14vh,132px); padding-left:clamp(34px,3vw,64px); padding-right:clamp(34px,3vw,64px); }
  .home-stats { column-gap:clamp(12px,1.5vw,24px); }
  .start-button { width:clamp(220px,17vw,300px); height:clamp(56px,8vh,72px); font-size:clamp(19px,1.8vw,25px); }
}
/* rk3568_r 实机为 1920×1080：页脚两端预留明确的触控与视觉安全区。 */
@media (min-width:1280px) and (orientation:landscape) {
  .home-footer { grid-template-columns:max-content 300px; padding-left:clamp(72px,5vw,96px); padding-right:clamp(72px,5vw,96px); }
}
</style>
