<template>
  <ModalShell :closable="status !== 'SUCCESS'" @close="$emit('cancel')">
    <view class="recognition-content" :class="type.toLowerCase()">
      <template v-if="status === 'SUCCESS'">
        <view class="result-mark success">✓</view>
        <text class="eyebrow">验证完成</text>
        <text class="headline">{{ successText }}</text>
        <text class="hint">{{ successHint }}</text>
        <button class="done-button" @click="$emit('finish')">{{ successActionText }}</button>
      </template>
      <template v-else>
        <view class="scan-stage">
          <view class="scan-glow"></view>
          <IconGlyph class="scan-icon" :name="type === 'FACE' ? 'face' : 'fingerprint'" />
          <view class="scan-track"><view class="scan-bar"></view></view>
        </view>
        <text class="eyebrow">{{ type === 'FACE' ? '人脸验证' : '系统指纹验证' }}</text>
        <text class="headline" :class="{ error: status === 'ERROR', retry: status === 'RETRY' }">{{ promptText }}</text>
        <text class="hint">{{ progressText }}</text>
        <button class="cancel-button" @click="$emit('cancel')">{{ status === 'ERROR' ? '关闭' : '取消' }}</button>
      </template>
    </view>
  </ModalShell>
</template>
<script setup>
import { computed } from 'vue'
import ModalShell from './ModalShell.vue'
import IconGlyph from './IconGlyph.vue'
defineEmits(['cancel','finish'])
const props=defineProps({type:{type:String,default:'FACE'},status:{type:String,default:'DETECTING'},statusMessage:{type:String,default:''},successText:{type:String,default:'操作成功'},successHint:{type:String,default:'请按提示继续操作'},successActionText:{type:String,default:'完成'}})
const promptText=computed(()=>{
  if(props.status==='ERROR') return '验证未完成'
  if(props.status==='RETRY') return '未识别，请重试'
  return props.type==='FACE' ? '请正对前置摄像头' : '请按系统提示验证指纹'
})
const progressText=computed(()=>props.statusMessage || ({PREPARING:'正在准备安全验证…',DETECTING:'正在开启摄像头…',COLLECTING:'等待系统采集…',MATCHING:'正在安全比对…',RETRY:'手指位置或按压力度不合适，请重新放置。',ERROR:'系统指纹验证失败或已取消。'}[props.status]||'正在准备中…'))
</script>
<style scoped>
.recognition-content { min-height: clamp(330px, 48vh, 460px); padding: 42px clamp(30px, 6vw, 56px) 32px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; overflow: hidden; }
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
.hint { margin-top: 9px; font-size: 14px; line-height: 1.6; color: #75849a; }
.cancel-button { width: min(100%, 220px); height: 44px; margin-top: 26px; border: 1px solid #d5e0ed; border-radius: 12px; background:#fff; color:#5c6d82; font-size:15px; display:flex; align-items:center; justify-content:center; }
.done-button { width: min(100%, 220px); height: 46px; margin-top: 26px; border: 0; border-radius: 12px; background: linear-gradient(90deg,#71edaa,#00cfc8); color:#fff; font-size:16px; font-weight:600; display:flex; align-items:center; justify-content:center; }
.result-mark { width: clamp(80px, 16vw, 108px); height: clamp(80px, 16vw, 108px); border-radius:50%; background:linear-gradient(145deg,#2fd677,#11a961); color:#fff; display:flex; align-items:center; justify-content:center; font-size:clamp(42px,8vw,58px); box-shadow:0 12px 24px rgba(20,181,99,.22); }
.success + .eyebrow { color:#20a967; }
@keyframes sweep { 0%,100% { top: 8%; opacity:.25; } 50% { top: 90%; opacity:1; } }
@keyframes breathe { 0%,100% { transform:scale(.88); opacity:.14; } 50% { transform:scale(1.12); opacity:.28; } }
</style>
