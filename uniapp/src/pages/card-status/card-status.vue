<template>
  <view class="page-root status-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar
      title="卡状态"
      hint="卡片仅显示状态；点击可查看包含卡号的详细信息并进行管理员取卡"
      @back="back"
    />
    <view class="status-grid-stage">
      <CabinetSlotGrid
        :slots="displaySlots"
        :group-size="appState.settings.groupSize || appState.settings.singleGroupCount"
        :sort-direction="appState.settings.slotSortDirection"
        :operation-effects="appState.cabinetOperationEffects"
        interactive
        @slot-click="openSlot"
      />
    </view>
    <view class="status-footer">
      <StatusLegend class="footer-legend left" :items="leftLegendItems" :total="displaySlots.length" :columns="1" />
      <StatusLegend class="footer-legend right" :items="rightLegendItems" :total="displaySlots.length" :columns="1" />
    </view>
    <SlotDetailModal v-if="selectedSlot" :slot="selectedSlot" :allow-unlock="canUnlock" :unlocking="unlocking" :feedback="unlockFeedback" @close="closeSlot" @unlock="unlock" />
  </view>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import CabinetSlotGrid from '@/components/CabinetSlotGrid.vue'
import StatusLegend from '@/components/StatusLegend.vue'
import SlotDetailModal from '@/components/SlotDetailModal.vue'
import { appState, getSlotProjection, hasPermission, upsertSlotProjection } from '@/state/appState.js'
import { SLOT_STATUS, SLOT_STATUS_META } from '@/constants/app.js'
import { services } from '@/services/index.js'
import { summarizeSlotStatuses } from '@/state/slotProjection.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const roleLabel = computed(() => appState.session?.roleLabels?.join('、') || '')
const selectedSlotNumber = ref(0)
const unlockFeedback = ref(null)
const unlocking = ref(false)
const FEEDBACK_CLOSE_DELAY_MS = 1600
let feedbackCloseTimer = null
let slotModalTraceSequence = 0
let activeSlotModalTraceId = ''
let cardStatusMounted = false
const canUnlock = computed(() => hasPermission('realtime.slot.open'))
const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address ?? 0)
const selectedSlot = computed(() => getSlotProjection(selectedSlotNumber.value))
const displaySlots = computed(() => appState.slots)
const hasUsableSlotSnapshot = () => appState.slots.some((slot) => (
  String(slot?.status || '').trim().toUpperCase() !== SLOT_STATUS.LOADING
))
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
  legendItem('fault', SLOT_STATUS.CHARGING_FAULT),
  legendItem('comm', SLOT_STATUS.COMMUNICATION_FAULT),
  legendItem('unknown', SLOT_STATUS.UNKNOWN)
])
const monotonicNow = () => (
  typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now()
)
const requestPaintFrame = (callback) => (
  typeof requestAnimationFrame === 'function'
    ? requestAnimationFrame(callback)
    : setTimeout(callback, 0)
)
const logSlotModalTrace = (traceId, slotNumber, phase, startedAt) => {
  console.debug(
    `[slot-modal] trace=${traceId} phase=${phase} slot=${slotNumber} elapsedMs=${Math.round(monotonicNow() - startedAt)}`
  )
}
const logSlotModalFirstPaint = (traceId, slotNumber, startedAt) => {
  nextTick(() => {
    logSlotModalTrace(traceId, slotNumber, 'vue-ready', startedAt)
    requestPaintFrame(() => requestPaintFrame(() => {
      if (activeSlotModalTraceId !== traceId) return
      logSlotModalTrace(traceId, slotNumber, 'first-paint', startedAt)
    }))
  })
}
const logCardStatusEntryTrace = (phase, startedAt) => {
  console.debug(
    `[card-status] phase=${phase} slots=${appState.slots.length} elapsedMs=${Math.round(monotonicNow() - startedAt)}`
  )
}
const runAfterCardStatusFirstPaint = (callback) => {
  nextTick(() => requestPaintFrame(() => requestPaintFrame(callback)))
}
const openSlot = (slot) => {
  const slotNumber = slotNumberOf(slot)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return
  clearFeedbackCloseTimer()
  const traceId = `slot-modal-${++slotModalTraceSequence}`
  const startedAt = monotonicNow()
  activeSlotModalTraceId = traceId
  selectedSlotNumber.value = slotNumber
  unlockFeedback.value = null
  logSlotModalTrace(traceId, slotNumber, 'click', startedAt)
  logSlotModalFirstPaint(traceId, slotNumber, startedAt)
}
const closeSlot = () => {
  clearFeedbackCloseTimer()
  activeSlotModalTraceId = ''
  selectedSlotNumber.value = 0
  unlockFeedback.value = null
}
const clearFeedbackCloseTimer = () => {
  if (feedbackCloseTimer === null) return
  clearTimeout(feedbackCloseTimer)
  feedbackCloseTimer = null
}
const closeSlotAfterFeedback = () => {
  clearFeedbackCloseTimer()
  feedbackCloseTimer = setTimeout(() => {
    feedbackCloseTimer = null
    closeSlot()
  }, FEEDBACK_CLOSE_DELAY_MS)
}
onMounted(() => {
  cardStatusMounted = true
  const startedAt = monotonicNow()
  logCardStatusEntryTrace('mounted', startedAt)
  runAfterCardStatusFirstPaint(() => {
    if (!cardStatusMounted) return
    logCardStatusEntryTrace('first-paint', startedAt)
    services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'CARD_STATUS_VIEW', feature_label: '卡状态查看' })
    // 首页已有卡槽投影时，状态页只复用内存快照；SQLite 只负责冷启动降级恢复。
    if (hasUsableSlotSnapshot()) return
    services.loadCachedSlots().then((cachedSlots) => {
      if (!cardStatusMounted || hasUsableSlotSnapshot()) return
      if (Array.isArray(cachedSlots)) cachedSlots.forEach((slot) => upsertSlotProjection(slot))
    }).catch(() => {})
  })
})
onBeforeUnmount(() => {
  cardStatusMounted = false
  clearFeedbackCloseTimer()
})
const unlock = async (slot) => {
  if (unlocking.value) return
  unlockFeedback.value = null
  unlocking.value = true
  try {
    const result = await services.unlockDoor(slot.slotNumber)
    const reportPending = result?.reportPending === true
    unlockFeedback.value = reportPending
      ? {
          type: 'warning',
          title: '卡槽已开卡',
          message: result.message || `${slot.displayNumber || slot.slotNumber}号卡槽已开卡，卡槽状态待后台同步`
        }
      : {
          type: 'success',
          title: '卡槽已开卡',
          message: result.message || `${slot.displayNumber || slot.slotNumber}号卡槽已开卡`
        }
  } catch (error) {
    const unsupported = error?.code === 'SERIAL_TOPOLOGY_UNCONFIRMED'
    unlockFeedback.value = {
      type: 'error',
      title: unsupported ? '真机管理员取卡尚未接入' : '管理员取卡失败',
      message: toUserErrorMessage(error, '卡槽状态未确认，请检查设备后重试')
    }
  } finally {
    unlocking.value = false
    // 先展示成功或失败结果，再关闭详情窗，避免结果尚未渲染就消失。
    closeSlotAfterFeedback()
  }
}
const back = () => uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/admin/admin' }) })
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.status-page { background: #e6f0ff; }
.status-grid-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.status-grid-stage > :deep(.cabinet-slot-grid-host) {
  position: absolute;
  inset: 0;
}
.status-footer {
  flex: 0 0 auto;
  height: clamp(96px, 20vw, 168px);
  min-height: 0;
  background: #1f76ff;
  padding-top: clamp(10px, 2.5vw, 20px);
  padding-bottom: clamp(10px, 2.5vw, 20px);
  padding-left: clamp(36px, 4vw, 72px);
  padding-right: clamp(36px, 4vw, 72px);
  display: grid;
  grid-template-columns: max-content clamp(156px, 19.5vw, 280px) max-content;
  justify-content: space-between;
  column-gap: clamp(9px, 4.5vw, 36px);
  align-items: center;
  overflow: hidden;
}
.footer-back {
  width: 100%;
  height: clamp(42px, 7vw, 66px);
  font-size: clamp(15px, 2.5vw, 24px);
  font-weight: 500;
}
.footer-legend {
  min-width: 0;
  width: max-content;
  /* 8 项状态必须在固定底栏完整可见，不能裁掉第 4 行。 */
  --legend-track-width: clamp(44px, 11.25vw, 90px);
  --legend-track-height: clamp(5px, 1.25vw, 10px);
  --legend-count-width: clamp(10px, 2.5vw, 20px);
  --legend-count-gap: clamp(3px, .75vw, 6px);
  --legend-label-gap: clamp(3px, .875vw, 7px);
  --legend-font-size: clamp(8px, 2vw, 16px);
  --legend-row-gap: clamp(4px, 1.125vw, 9px);
}
.footer-legend.left { justify-self: start; }
.footer-legend.right { grid-column: 3; justify-self: end; }
@media (min-width:900px) and (orientation:landscape) {
  .status-footer { height:clamp(112px,16vh,148px); padding-left:clamp(38px,3.5vw,72px); padding-right:clamp(38px,3.5vw,72px); }
  .footer-legend { --legend-track-width:clamp(72px,7vw,108px); --legend-font-size:clamp(14px,1.25vw,18px); }
}
@media (min-width:1280px) and (orientation:landscape) {
  .status-footer { padding-left:clamp(72px,5vw,96px); padding-right:clamp(72px,5vw,96px); }
}
</style>
