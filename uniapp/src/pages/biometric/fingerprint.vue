<template>
  <view class="page-root biometric-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="form-panel">
        <view class="top-icon finger"><IconGlyph name="fingerprint" /></view>
        <text class="form-title">本机指纹授权</text>
        <view class="divider"></view>
        <view class="field"><text class="field-label">请输入职员ID</text><input class="field-input" v-model="employeeId" placeholder="请输入职员ID" /></view>
        <view class="field"><text class="field-label">请输入职员姓名</text><input class="field-input" v-model="employeeName" placeholder="点击输入姓名" /></view>
        <text class="privacy-note">将调用 Android 系统指纹认证；应用不会读取或保存指纹图像、模板或指纹编号。</text>
        <button class="primary-gradient-button submit-button" @click="register">确认系统指纹授权</button>
      </view>
      <view class="back-wrap"><BackButton @click="back" /></view>
    </scroll-view>
    <RecognitionModal v-if="process.visible" type="FINGERPRINT" :status="process.status" :status-message="process.message" :success-text="'系统指纹授权成功！'" :success-hint="'系统未向应用提供指纹数据；当前职员已标记为通过本机系统指纹授权。'" @cancel="cancelRegister" @finish="finishRegister" />
  </view>
</template>
<script setup>
import { computed, reactive, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue';import BackButton from '@/components/BackButton.vue';import IconGlyph from '@/components/IconGlyph.vue';import RecognitionModal from '@/components/RecognitionModal.vue'
import { appState } from '@/state/appState.js';import { ROLE_META } from '@/constants/app.js';import { services } from '@/services/index.js'
const roleLabel=computed(()=>ROLE_META[appState.session?.role]?.label||'');const employeeId=ref('LDOGIK_7855422222');const employeeName=ref('');const process=reactive({visible:false,status:'PREPARING',message:''})
const applyProgress=(progress)=>{if(typeof progress==='string'){process.status=progress;process.message='';return}process.status=progress?.status||process.status;process.message=progress?.message||''}
const register=async()=>{if(!employeeId.value.trim()||!employeeName.value.trim()){uni.showToast({title:'请输入职员ID和姓名',icon:'none'});return}process.visible=true;process.status='PREPARING';process.message='';try{await services.registerBiometric('FINGERPRINT',{employeeId:employeeId.value.trim(),employeeName:employeeName.value.trim()},applyProgress);process.status='SUCCESS';process.message=''}catch(e){process.status='ERROR';process.message=e.message||'录入失败'}}
const cancelRegister=async()=>{process.visible=false;await services.cancelRecognition('FINGERPRINT')}
const finishRegister=()=>{process.visible=false}
const back=()=>uni.navigateBack();const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.biometric-page { background: #e6f0ff; }
.form-panel { width: min(92%, 760px); min-height: clamp(520px, 72vh, 820px); margin: clamp(24px, 3.5vh, 46px) auto 0; background: #fff; border-radius: clamp(16px, 2vw, 24px); padding: clamp(34px, 5vh, 62px) clamp(36px, 7vw, 100px); display: flex; flex-direction: column; align-items: center; }
.top-icon { width: clamp(76px, 11vw, 112px); height: clamp(76px, 11vw, 112px); border-radius: 50%; padding: clamp(17px, 2.5vw, 27px); color: #fff; }
.top-icon.face { background: #ff5f67; }
.top-icon.finger { background: #4ecde9; }
.form-title { font-size: clamp(20px, 2.8vw, 26px); margin-top: 22px; }
.divider { width: 100%; height: 1px; background: #e5e5e5; margin: clamp(26px, 4vh, 42px) 0; }
.field { width: min(100%, 560px); margin-bottom: clamp(24px, 3.5vh, 38px); }
.field-label { display: block; font-size: 16px; font-weight: 500; margin-bottom: 13px; }
.field-input { height: clamp(52px, 6.8vh, 70px); background: #f5f8fc; border-radius: 14px; padding: 0 18px; font-size: 16px; color: #555; }
.privacy-note { width: min(100%, 560px); margin: -4px 0 18px; color: #738198; font-size: 13px; line-height: 1.6; }
.submit-button { width: min(100%, 560px); height: clamp(52px, 6.8vh, 70px); margin-top: 6px; }
.back-wrap { padding: clamp(28px, 4.5vh, 54px) 0 max(22px, env(safe-area-inset-bottom)); }
@media (max-width: 540px) { .form-panel { padding: 28px 22px; min-height: 500px; } }
</style>
