<template>
  <view class="password-panel">
    <view class="feature-icon yellow"><IconGlyph name="lock" /></view>
    <text class="feature-title">修改密码</text>
    <view class="form-narrow">
      <view class="password-field">
        <text class="field-label">原密码 <text class="required-mark">*</text></text>
        <input v-model="form.oldPassword" class="large-input" password maxlength="6" type="number" placeholder="请输入6位原密码" />
      </view>
      <view class="password-field">
        <text class="field-label">新密码 <text class="required-mark">*</text></text>
        <input v-model="form.newPassword" class="large-input" password maxlength="6" type="number" placeholder="请输入6位新密码" />
      </view>
      <view class="password-field">
        <text class="field-label">确认密码 <text class="required-mark">*</text></text>
        <input v-model="form.confirmPassword" class="large-input" password maxlength="6" type="number" placeholder="请再次输入新密码" />
      </view>
      <view v-if="errorText" class="error-text">{{ errorText }}</view>
      <button class="primary-gradient-button feature-button" :loading="submitting" @click="submit">保存密码</button>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { services } from '@/services/index.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const emit = defineEmits(['done'])
const submitting = ref(false)
const errorText = ref('')
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validatePassword = (value, message) => {
  if (!/^\d{6}$/.test(String(value || '').trim())) throw new Error(message)
}

const submit = async () => {
  try {
    validatePassword(form.oldPassword, '请输入6位数字原密码')
    validatePassword(form.newPassword, '请输入6位数字新密码')
    if (form.newPassword !== form.confirmPassword) throw new Error('两次输入的新密码不一致')
    errorText.value = ''
    submitting.value = true
    await services.changeLocalPassword(form.oldPassword, form.newPassword)
    uni.showToast({ title: '密码已修改', icon: 'success' })
    emit('done')
  } catch (error) {
    errorText.value = toUserErrorMessage(error, '密码修改失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.password-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: clamp(30px, 4.5vh, 56px) clamp(26px, 4vw, 64px);
}
.feature-icon {
  width: clamp(68px, 9.5vw, 100px);
  height: clamp(68px, 9.5vw, 100px);
  border-radius: 50%;
  color: #fff;
  padding: clamp(16px, 2.2vw, 24px);
}
.yellow { background: #ffc95b; }
.feature-title {
  font-size: clamp(20px, 2.7vw, 26px);
  margin-top: 18px;
  font-weight: 500;
}
.form-narrow {
  width: min(100%, 460px);
  margin-top: 30px;
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.password-field { display: flex; flex-direction: column; gap: 8px; }
.field-label { color: #4b5565; font-size: 14px; font-weight: 500; }
.required-mark { color: #ef4053; }
.large-input {
  height: clamp(50px, 6.4vh, 66px);
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #b8c7da;
  border-radius: 12px;
  padding: 0 16px;
  font-size: 16px;
  color: #1b2b42;
}
.large-input:focus { border-color: #2878ff; }
.error-text {
  color: #c43d3d;
  font-size: 14px;
  margin: -4px 0 0;
}
.feature-button {
  height: clamp(50px, 6.4vh, 66px);
  width: 100%;
}
@media (max-width: 520px) {
  .password-panel { padding: 28px 20px; }
}
</style>
