<template>
  <ModalShell closable @close="$emit('close')">
    <view class="password-content">
      <view class="person-icon"><view class="head"></view><view class="body"></view></view>
      <text class="password-title">请输入管理密码</text>

      <view class="digit-input-wrap" @click="focusPasswordInput">
        <input
          ref="passwordInput"
          class="native-password-input"
          :value="password"
          type="text"
          inputmode="numeric"
          password
          maxlength="6"
          :focus="inputFocused"
          @input="handleInput"
          @focus="inputFocused = true"
          @blur="inputFocused = false"
        />
        <view class="digit-row">
          <view v-for="n in 6" :key="n" class="digit-box">{{ password[n - 1] ? '●' : '' }}</view>
        </view>
      </view>

      <text class="password-help">系统管理员、运维人员、开发人员使用各自密码登录</text>
      <text v-if="error" class="password-error">{{ error }}</text>
    </view>
  </ModalShell>
</template>
<script setup>
import { nextTick, onMounted, ref } from 'vue'
import ModalShell from './ModalShell.vue'

const emit = defineEmits(['close', 'submit'])
const password = ref('')
const error = ref('')
const inputFocused = ref(true)
const passwordInput = ref(null)
let submitting = false

const focusPasswordInput = async () => {
  inputFocused.value = false
  await nextTick()
  inputFocused.value = true
  await nextTick()
  const target = passwordInput.value
  if (typeof target?.focus === 'function') target.focus()
  else if (typeof target?.$el?.querySelector === 'function') target.$el.querySelector('input')?.focus()
}

const submit = () => {
  if (password.value.length !== 6 || submitting) return
  submitting = true
  emit('submit', password.value, {
    setError: (message) => {
      error.value = message
      submitting = false
      focusPasswordInput()
    },
    clear: () => {
      password.value = ''
      submitting = false
      focusPasswordInput()
    }
  })
}

const handleInput = (event) => {
  const raw = event?.detail?.value ?? event?.target?.value ?? ''
  password.value = String(raw).replace(/\D/g, '').slice(0, 6)
  error.value = ''
  if (password.value.length === 6) submit()
}

onMounted(() => {
  nextTick(focusPasswordInput)
})
</script>
<style scoped>
.password-content {
  min-height: min(58dvh, 460px);
  padding: 30px clamp(24px, 5vw, 54px) 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.person-icon { width: clamp(82px, 12vw, 120px); height: clamp(82px, 12vw, 120px); border-radius: 50%; background: #e7f0ff; position: relative; }
.person-icon .head { width: 32%; aspect-ratio: 1; border-radius: 50%; background: #1f76ff; position: absolute; left: 34%; top: 21%; }
.person-icon .body { width: 57%; height: 31%; border-radius: 50% 50% 18% 18%; background: #1f76ff; position: absolute; left: 21.5%; bottom: 19%; }
.password-title { margin-top: 20px; font-size: clamp(18px, 2.5vw, 23px); color: #34373d; }
.digit-input-wrap { position: relative; width: 100%; margin-top: 24px; cursor: text; }
.native-password-input {
  position: absolute;
  inset: 0;
  z-index: 2;
  width: 100%;
  height: 100%;
  opacity: .01;
  color: transparent;
  caret-color: transparent;
  background: transparent;
  border: 0;
}
.digit-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; width: 100%; pointer-events: none; }
.digit-box { height: clamp(44px, 6.5vh, 58px); border-radius: 8px; background: #f5f5f5; color: #1f76ff; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.password-help { margin-top: 20px; text-align: center; font-size: 12px; color: #8290a3; }
.password-error { margin-top: 10px; color: #ef1010; font-size: 14px; }
</style>
