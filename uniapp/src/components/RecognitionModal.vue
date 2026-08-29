<template>
  <ModalShell
    :closable="!operationMode && (status !== 'SUCCESS' || successClosable)"
    :mask-class="operationMode ? 'modal-operation-mask' : ''"
    :size-class="operationMode ? 'modal-operation-card' : ''"
    @close="$emit('cancel')"
  >
    <view v-if="operationMode" class="operation-progress" :class="operationStateClass">
      <view class="operation-indicator"><view></view></view>
      <view class="operation-copy">
        <text class="operation-title">{{ operationTitle }}</text>
        <text class="operation-hint">{{ progressText }}</text>
      </view>
    </view>
    <view v-else class="recognition-content" :class="type.toLowerCase()">
      <template v-if="status === 'SUCCESS'">
        <view class="result-mark success">✓</view>
        <text class="eyebrow">验证完成</text>
        <text class="headline">{{ successText }}</text>
        <text class="hint">{{ successHint }}</text>
        <button class="done-button" :disabled="successActionDisabled" @click="$emit('finish')">{{ successActionText }}</button>
      </template>
      <template v-else>
        <view class="scan-stage">
          <view class="scan-glow"></view>
          <IconGlyph class="scan-icon" :name="type === 'FACE' ? 'face' : 'fingerprint'" />
          <view class="scan-track"><view class="scan-bar"></view></view>
        </view>
        <text class="eyebrow">{{ type === 'FACE' ? '人脸验证' : '系统指纹验证' }}</text>
        <text class="headline" :class="{ error: status === 'ERROR' || status === 'TAKE_ERROR', retry: status === 'RETRY' }">{{ promptText }}</text>
        <text class="hint">{{ progressText }}</text>
        <button class="cancel-button" :disabled="status === 'UPLOADING'" @click="$emit('cancel')">{{ status === 'UPLOADING' ? '正在上传' : (['ERROR', 'TAKE_ERROR'].includes(status) ? '关闭' : '取消') }}</button>
      </template>
    </view>
  </ModalShell>
</template>
<script setup>
import { computed } from 'vue'
import ModalShell from './ModalShell.vue'
import IconGlyph from './IconGlyph.vue'
defineEmits(['cancel','finish'])
const props=defineProps({type:{type:String,default:'FACE'},status:{type:String,default:'DETECTING'},statusMessage:{type:String,default:''},successText:{type:String,default:'操作成功'},successHint:{type:String,default:'请按提示继续操作'},successActionText:{type:String,default:'完成'},successActionDisabled:{type:Boolean,default:false},successClosable:{type:Boolean,default:false},operationMode:{type:Boolean,default:false},slotNumber:{type:[Number,String],default:0}})
const formattedSlotNumber=computed(()=>{
  const value=Number(props.slotNumber)
  return Number.isInteger(value)&&value>0 ? String(value).padStart(2,'0') : ''
})
const slotLabel=computed(()=>formattedSlotNumber.value ? `卡柜${formattedSlotNumber.value}` : '卡柜')
const operationStateClass=computed(()=>{
  if(props.status==='SUCCESS') return 'success'
  if(props.status==='NO_CARD'||props.status==='TAKE_ERROR'||props.status==='ERROR') return 'failure'
  return 'running'
})
const operationTitle=computed(()=>{
  if(props.status==='FACE_VERIFIED') return '识别成功'
  if(props.status==='CARD_PRESENTED') return '请取走工卡'
  if(props.status==='SUCCESS') return '取卡成功'
  if(props.status==='NO_CARD') return '暂无可用工作卡'
  if(props.status==='TAKE_ERROR'||props.status==='ERROR') return '出卡失败'
  return '正在处理'
})
const promptText=computed(()=>{
  if(props.status==='TAKE_ERROR') return '人脸已通过，取卡未完成'
  if(props.status==='ERROR') return '验证未完成'
  if(props.status==='RETRY') return '未识别，请重试'
  if(props.status==='UPLOADING') return '正在添加人脸'
  if(props.status==='TAKING') return '正在完成取卡'
  return props.type==='FACE' ? '请正对前置摄像头' : '请按系统提示验证指纹'
})
const progressText=computed(()=>props.statusMessage || ({PREPARING:'正在准备安全验证…',DETECTING:'正在开启摄像头…',COLLECTING:'等待系统采集…',MATCHING:'正在安全比对…',UPLOADING:'正在向服务器提交人脸照片…',SUCCESS:'取卡成功，请及时取走。',NO_CARD:'当前暂无可用工作卡，请联系管理员。',TAKE_ERROR:'出卡失败，请稍后重试或联系管理员。',RETRY:'手指位置或按压力度不合适，请重新放置。',ERROR:'系统指纹验证失败或已取消。'}[props.status]||'正在准备中…'))
</script>
<style scoped>
.recognition-content { min-height: clamp(330px, 48vh, 460px); padding: 42px clamp(30px, 6vw, 56px) 32px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; overflow: hidden; }
.operation-progress { min-height:56px; padding:11px 16px; display:flex; align-items:flex-start; gap:13px; box-sizing:border-box; color:#1f76ff; }
.operation-indicator { position:relative; width:34px; height:34px; margin-top:2px; flex:0 0 auto; border-radius:50%; background:currentColor; box-shadow:0 0 0 6px rgba(31,118,255,.1), 0 0 18px rgba(31,118,255,.36); }
.operation-indicator::after { content:""; position:absolute; inset:11px; border-radius:50%; background:#fff; }
.operation-indicator view { position:absolute; inset:-6px; border:2px solid currentColor; border-radius:50%; animation:operation-ring .72s ease-out infinite; }
.operation-copy { min-width:0; max-width:100%; overflow:visible; display:flex; flex:1 1 auto; flex-direction:column; gap:3px; text-align:left; }
.operation-title,
.operation-hint { display:block; width:100%; max-width:100%; height:auto; max-height:none; overflow:visible; text-overflow:clip; white-space:normal; overflow-wrap:anywhere; word-break:break-word; -webkit-line-clamp:unset; }
.operation-title { font-size:16px; line-height:1.3; font-weight:700; color:#26364d; }
.operation-hint { font-size:12px; line-height:1.45; color:#6e7f96; }
.operation-progress.success { color:#17bd6b; }
.operation-progress.success .operation-indicator { box-shadow:0 0 0 6px rgba(23,189,107,.1), 0 0 18px rgba(23,189,107,.38); }
.operation-progress.failure { color:#ef4059; }
.operation-progress.failure .operation-indicator { box-shadow:0 0 0 6px rgba(239,64,89,.1), 0 0 18px rgba(239,64,89,.38); }
.scan-stage { width: clamp(142px, 34vw, 200px); height: clamp(142px, 34vw, 200px); position: relative; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: #eff6ff; border: 1px solid #cae0ff; overflow: hidden; }
.fingerprint .scan-stage { background: #effcff; border-color: #bceef2; }
.scan-glow { position: absolute; inset: 16%; border-radius: 50%; background: linear-gradient(145deg,#377dff,#7eabff); opacity: .15; animation: breathe 1.8s ease-in-out infinite; }
.fingerprint .scan-glow { background: linear-gradient(145deg,#00cbd1,#65e2e6); }
.scan-icon { width: 46%; height: 46%; color: #1f76ff; z-index: 1; }
.fingerprint .scan-icon { color: #00bfc8; }
.scan-track { position:absolute; left:10%; right:10%; top:0; bottom:0; overflow:hidden; border-radius:inherit; }
.scan-bar { position:absolute; left:4%; right:4%; top:-12%; height:2px; background:#1f76ff; box-shadow:0 0 14px 3px rgba(31,118,255,.45); animation: sweep 1.8s ease-in-out infinite; }
.fingerprint .scan-bar { background:#00cbd1; box-shadow:0 0 14px 3px rgba(0,203,209,.42); }
.eyebrow { margin-top: 26px; font-size: 14px; letter-spacing: 2px; color: #7890ae; }
.headline { margin-top: 10px; font-size: clamp(20px, 3.1vw, 25px); line-height: 1.4; font-weight: 650; color: #27364d; }
.headline.error { color:#e4354a; }
.headline.retry { color:#f08b1f; }
.hint { margin-top: 9px; font-size: 17px; line-height: 1.6; color: #75849a; }
.cancel-button { width: min(100%, 220px); height: 44px; margin-top: 26px; border: 1px solid #d5e0ed; border-radius: 12px; background:#fff; color:#5c6d82; font-size:15px; display:flex; align-items:center; justify-content:center; }
.done-button { width: min(100%, 220px); height: 46px; margin-top: 26px; border: 0; border-radius: 12px; background: linear-gradient(135deg,#4aa3ff,#1f76ff 48%,#0a53c4); color:#fff; font-size:16px; font-weight:600; display:flex; align-items:center; justify-content:center; box-shadow: 0 8px 16px rgba(31,118,255,.22), inset 0 1px 1px rgba(255,255,255,.24); }
.result-mark { width: clamp(80px, 16vw, 108px); height: clamp(80px, 16vw, 108px); border-radius:50%; background:linear-gradient(145deg,#2fd677,#11a961); color:#fff; display:flex; align-items:center; justify-content:center; font-size:clamp(42px,8vw,58px); box-shadow:0 12px 24px rgba(20,181,99,.22); }
.success + .eyebrow { color:#20a967; }
@keyframes sweep { 0%,100% { top: 8%; opacity:.25; } 50% { top: 90%; opacity:1; } }
@keyframes breathe { 0%,100% { transform:scale(.88); opacity:.14; } 50% { transform:scale(1.12); opacity:.28; } }
@keyframes operation-ring { 0% { transform:scale(.55); opacity:.9; } 100% { transform:scale(1.18); opacity:0; } }
@media (prefers-reduced-motion: reduce) { .operation-indicator view { animation:none; } }
@media (max-width:420px) {
  .operation-progress { padding:10px 12px; gap:10px; }
  .operation-indicator { width:30px; height:30px; }
  .operation-indicator::after { inset:10px; }
  .operation-title { font-size:15px; }
  .operation-hint { font-size:12px; line-height:1.5; }
}
</style>
