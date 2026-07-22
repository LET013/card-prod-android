<template>
  <ModalShell closable @close="$emit('close')">
    <view class="slot-detail">
      <view class="detail-title">{{ slot.displayNumber }}号卡位详情</view>
      <view class="detail-grid">
        <view v-for="item in fields" :key="item.label" class="detail-row"><text>{{ item.label }}</text><text class="value">{{ item.value }}</text></view>
      </view>
      <view v-if="feedback" class="unlock-feedback" :class="feedback.type">
        <text>{{ feedback.title }}</text>
        <b>{{ feedback.message }}</b>
      </view>
      <button v-if="allowUnlock" class="primary-button" :disabled="unlocking" @click="$emit('unlock',slot)">{{ unlocking ? '正在发送开门指令…' : '打开卡门' }}</button>
    </view>
  </ModalShell>
</template>
<script setup>
import { computed } from 'vue'
import ModalShell from './ModalShell.vue'
import { SLOT_STATUS_META } from '@/constants/app.js'
const props=defineProps({slot:{type:Object,required:true},allowUnlock:{type:Boolean,default:false},unlocking:{type:Boolean,default:false},feedback:{type:Object,default:null}})
defineEmits(['close','unlock'])
const fields=computed(()=>[
  {label:'柜门号',value:props.slot.slotNumber},{label:'工卡卡号',value:props.slot.cardNumber||'无'},
  {label:'工作状态',value:props.slot.workStatus||'待机'},{label:'在位状态',value:props.slot.presenceStatus||'未知'},
  {label:'卡状态',value:SLOT_STATUS_META[props.slot.status]?.label||'未知'},{label:'门锁状态',value:props.slot.doorStatus||'未知'},
  {label:'故障码',value:props.slot.faultCode||'无'},{label:'故障信息',value:props.slot.faultMessage||'无'},
  {label:'电压',value:props.slot.voltage==null?'--':`${props.slot.voltage} V`},{label:'电流',value:props.slot.current==null?'--':`${props.slot.current} A`}
])
</script>
<style scoped>
.slot-detail { min-height: min(62vh, 560px); padding: 28px; color: #30343b; display: flex; flex-direction: column; }
.detail-title { font-size: 20px; font-weight: 600; text-align: center; margin-bottom: 20px; }
.detail-grid { flex: 1 1 auto; background: #f3f7fd; border-radius: 12px; padding: 10px 16px; }
.detail-row { min-height: 38px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e0e8f3; font-size: 14px; }
.detail-row:last-child { border-bottom: 0; }
.value { font-weight: 500; color: #195fca; text-align: right; margin-left: 16px; }
.unlock-feedback { flex:0 0 auto; margin-top:18px; border-radius:12px; padding:12px 14px; display:flex; flex-direction:column; gap:5px; line-height:1.45; font-size:14px; box-sizing:border-box; }
.unlock-feedback text { font-weight:700; }
.unlock-feedback b { font-weight:500; overflow-wrap:anywhere; }
.unlock-feedback.success { background:#e9fff4; color:#078d48; border:1px solid #a6efc9; }
.unlock-feedback.error { background:#fff0f2; color:#d9273f; border:1px solid #ffc0c9; }
.primary-button { flex: 0 0 auto; width: 100%; height: 56px; margin-top: 28px; border: 0; border-radius: 14px; background: linear-gradient(90deg,#71edaa,#00cfc8); color: white; font-size: 17px; font-weight: 600; line-height: 1; display: flex; align-items: center; justify-content: center; }
.primary-button::after { border: 0; }
</style>
