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
      <button v-if="allowUnlock" class="primary-button" :disabled="unlocking || Boolean(feedback)" @click="$emit('unlock',slot)">{{ unlocking ? '正在执行管理员取卡…' : '管理员取卡' }}</button>
    </view>
  </ModalShell>
</template>
<script setup>
import { computed } from 'vue'
import ModalShell from './ModalShell.vue'
import { SLOT_STATUS_META } from '@/constants/app.js'
const props=defineProps({slot:{type:Object,required:true},allowUnlock:{type:Boolean,default:false},unlocking:{type:Boolean,default:false},feedback:{type:Object,default:null}})
defineEmits(['close','unlock'])
const fields=computed(()=>{
  const status=String(props.slot.status||'').trim().toUpperCase()
  const items=[
    {label:'柜门号',value:props.slot.slotNumber},{label:'工卡卡号',value:props.slot.cardNumber||'无'},
    {label:'工作状态',value:props.slot.workStatus||'待机'},{label:'在位状态',value:props.slot.presenceStatus||'未知'},
    {label:'卡状态',value:SLOT_STATUS_META[props.slot.status]?.label||'未知'},{label:'门锁状态',value:props.slot.doorStatus||'未知'},
    {label:'故障码',value:props.slot.faultCode||'无'},{label:'故障信息',value:props.slot.faultMessage||'无'}
  ]
  if(status!=='EMPTY'){
    // BUG-034: 统一使用 Number() 转换，确保字符串型数值也经过 toFixed(2)
    const fmt=(val,unit)=>{const n=Number(val);return(n&&!Number.isNaN(n))?`${n.toFixed(2)} ${unit}`:`${val||'--'} ${unit}`}
    items.push({label:'电压',value:fmt(props.slot.voltage,'V')})
    items.push({label:'电流',value:fmt(props.slot.current,'A')})
  }
  return items
})
</script>
<style scoped>
.slot-detail { min-height: min(62vh, 560px); padding: 32px; color: #30343b; display: flex; flex-direction: column; }
.detail-title { font-size: 24px; font-weight: 600; text-align: center; margin-bottom: 24px; }
.detail-grid { flex: 1 1 auto; background: #f3f7fd; border-radius: 12px; padding: 12px 18px; }
.detail-row { min-height: 46px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e0e8f3; font-size: 16px; }
.detail-row:last-child { border-bottom: 0; }
.value { font-weight: 500; color: #195fca; text-align: right; margin-left: 16px; }
.unlock-feedback { flex:0 0 auto; margin-top:18px; border-radius:12px; padding:14px 16px; display:flex; flex-direction:column; gap:5px; line-height:1.45; font-size:15px; box-sizing:border-box; }
.unlock-feedback text { font-weight:700; }
.unlock-feedback b { font-weight:500; overflow-wrap:anywhere; }
.unlock-feedback.success { background:#e9fff4; color:#078d48; border:1px solid #a6efc9; }
.unlock-feedback.warning { background:#fff8e8; color:#a86500; border:1px solid #f3cc79; }
.unlock-feedback.error { background:#fff0f2; color:#d9273f; border:1px solid #ffc0c9; }
.primary-button { flex: 0 0 auto; width: 100%; height: 58px; margin-top: 28px; border: 0; border-radius: 14px; background: linear-gradient(135deg,#4aa3ff,#1f76ff 48%,#0a53c4); color: white; font-size: 18px; font-weight: 600; line-height: 1; display: flex; align-items: center; justify-content: center; box-shadow: 0 8px 16px rgba(31,118,255,.22), inset 0 1px 1px rgba(255,255,255,.24); }
.primary-button::after { border: 0; }
</style>
