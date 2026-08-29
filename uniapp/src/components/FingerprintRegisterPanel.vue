<template>
  <view>
    <scroll-view class="page-scroll" scroll-y>
      <view class="form-panel">
        <view class="panel-heading">
          <view class="top-icon finger"><IconGlyph name="fingerprint" /></view>
          <text class="form-title">员工指纹</text>
        </view>
        <view class="divider"></view>
        <view class="capability-state">
          <text class="capability-title">{{ capability.title }}</text>
          <text class="capability-message">{{ capability.message }}</text>
          <text class="capability-requirement">{{ capability.requirement }}</text>
          <text class="capability-code">阻塞码：{{ capability.code }}</text>
        </view>
        <button class="primary-gradient-button submit-button" @click="close">知道了</button>
      </view>
    </scroll-view>
  </view>
</template>
<script setup>
import IconGlyph from '@/components/IconGlyph.vue'
import { EMPLOYEE_FINGERPRINT_CAPABILITY } from '@/constants/biometric.js'

const emit = defineEmits(['done'])
const capability = EMPLOYEE_FINGERPRINT_CAPABILITY
const close = () => emit('done')
</script>
<style scoped>
.form-panel { width: min(92%, 680px); margin: 0 auto; padding: 28px 36px 32px; box-sizing: border-box; background: #fff; border-radius: 16px; display: flex; flex-direction: column; align-items: center; }
.panel-heading { width: min(100%, 560px); display: flex; align-items: center; gap: 14px; padding-right: 38px; box-sizing: border-box; }
.top-icon { width: 56px; height: 56px; flex: 0 0 auto; padding: 13px; box-sizing: border-box; border-radius: 12px; color: #fff; }
.top-icon.finger { background: #4ecde9; }
.form-title { color: #1f2b3d; font-size: 20px; font-weight: 700; line-height: 1.3; }
.divider { width: min(100%, 560px); height: 1px; margin: 18px 0 22px; background: #e5e9ef; }
.capability-state { width: min(100%, 560px); box-sizing: border-box; padding: 20px; border: 1px solid #f0c6cc; border-radius: 14px; background: #fff7f8; display: flex; flex-direction: column; gap: 10px; }
.capability-title { color: #a22f40; font-size: 17px; font-weight: 700; }
.capability-message, .capability-requirement { color: #5f6e82; font-size: 14px; line-height: 1.7; }
.capability-code { color: #9a3443; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.submit-button { width: min(100%, 560px); height: 56px; margin-top: 20px; }
@media (max-width: 540px) {
  .form-panel { width: 100%; padding: 24px 20px 26px; border-radius: 0; }
  .panel-heading { padding-right: 34px; }
  .top-icon { width: 50px; height: 50px; padding: 12px; }
  .form-title { font-size: 18px; }
}
</style>
