<template>
  <view class="page-root status-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="slot-scroll" scroll-y>
      <view class="slot-grid">
        <SlotCard v-for="slot in orderedSlots" :key="slot.id" :slot="slot" interactive @click="openSlot" />
      </view>
    </scroll-view>
    <view class="status-footer">
      <StatusLegend class="footer-legend left" :items="leftLegendItems" :total="displaySlots.length" :columns="1" />
      <BackButton class="footer-back" @click="back" />
      <StatusLegend class="footer-legend right" :items="rightLegendItems" :total="displaySlots.length" :columns="1" />
    </view>
    <SlotDetailModal v-if="selectedSlot" :slot="selectedSlot" :allow-unlock="canUnlock" :unlocking="unlocking" :feedback="unlockFeedback" @close="closeSlot" @unlock="unlock" />
  </view>
</template>
<script setup>
import { computed, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import SlotCard from '@/components/SlotCard.vue'
import StatusLegend from '@/components/StatusLegend.vue'
import BackButton from '@/components/BackButton.vue'
import SlotDetailModal from '@/components/SlotDetailModal.vue'
import { appState } from '@/state/appState.js'
import { ROLE_META, hasPermission, SLOT_STATUS, SLOT_STATUS_META } from '@/constants/app.js'
import { services } from '@/services/index.js'

const DISPLAY_SLOT_COUNT = 60
const COLUMNS = 6
const ROWS = 10
const roleLabel = computed(() => ROLE_META[appState.session?.role]?.label || '')
const selectedSlot = ref(null)
const unlockFeedback = ref(null)
const unlocking = ref(false)
const canUnlock = computed(() => hasPermission(appState.session, 'cabinet.unlock'))
const displaySlots = computed(() => appState.slots.slice(0, DISPLAY_SLOT_COUNT))
const orderedSlots = computed(() => {
  const result = []
  for (let row = 0; row < ROWS; row += 1) {
    for (let col = 0; col < COLUMNS; col += 1) {
      const index = col * ROWS + row
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
  { key: 'fault', label: '充电故障', color: SLOT_STATUS_META.CHARGING_FAULT.color, count: count(SLOT_STATUS.CHARGING_FAULT) },
  { key: 'comm', label: '通信故障', color: SLOT_STATUS_META.COMMUNICATION_FAULT.color, count: count(SLOT_STATUS.COMMUNICATION_FAULT) }
])
const openSlot = (slot) => {
  selectedSlot.value = slot
  unlockFeedback.value = null
}
const closeSlot = () => {
  selectedSlot.value = null
  unlockFeedback.value = null
}
const unlock = async (slot) => {
  if (unlocking.value) return
  unlockFeedback.value = null
  unlocking.value = true
  try {
    const result = await services.unlockDoor(slot.slotNumber)
    unlockFeedback.value = {
      type: 'success',
      title: '开门指令已发送',
      message: result.message || `${slot.displayNumber || slot.slotNumber}号卡门开门指令已发送`
    }
  } catch (error) {
    unlockFeedback.value = {
      type: 'error',
      title: '开门失败',
      message: error.message || '开门指令未发送成功，请检查串口连接和设备状态。'
    }
  } finally {
    unlocking.value = false
  }
}
const back = () => uni.navigateBack()
const exitAdmin = async () => { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.status-page { background: #e6f0ff; }
.slot-scroll { flex: 1; min-height: 0; }
.slot-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: clamp(4px, .7vw, 8px);
  padding: clamp(5px, .85vw, 10px);
  align-content: start;
}
.status-footer {
  flex: 0 0 auto;
  height: clamp(73px, 18.625vw, 149px);
  min-height: 0;
  background: #1f76ff;
  padding-block: clamp(7px, 2.25vw, 18px);
  padding-inline: clamp(20px, 5.5vw, 44px);
  display: grid;
  grid-template-columns: max-content clamp(138px, 35vw, 280px) max-content;
  justify-content: space-between;
  column-gap: clamp(9px, 4.5vw, 36px);
  align-items: center;
  overflow: hidden;
}
.footer-legend {
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
.footer-legend.left { justify-self: start; }
.footer-legend.right { justify-self: end; }
.footer-back {
  min-width: 0;
  width: 100%;
  max-width: none;
  justify-self: center;
}
.status-footer :deep(.back-button) {
  width: 100%;
  min-width: 0;
  height: clamp(39px, 10vw, 80px);
  padding-inline: clamp(8px, 2vw, 16px);
  border-radius: clamp(8px, 2vw, 16px);
  font-size: clamp(13px, 3.5vw, 28px);
  white-space: nowrap;
}
</style>
