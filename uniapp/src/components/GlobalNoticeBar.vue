<template>
  <view v-if="notice" class="global-notice-bar" :class="`notice-${notice.type}`" @click="dismiss">
    <view class="notice-icon">
      <text v-if="notice.type === 'error'">&#x26A0;</text>
      <text v-else>&#x2139;</text>
    </view>
    <text class="notice-text">{{ notice.message }}</text>
    <view class="notice-close">
      <text>&#x2715;</text>
    </view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, watch } from 'vue'
import { appState, clearGlobalNotice } from '@/state/appState.js'

const notice = computed(() => appState.globalNotice)
let dismissTimer = null

const clearDismissTimer = () => {
  if (dismissTimer) clearTimeout(dismissTimer)
  dismissTimer = null
}

watch(notice, (value) => {
  clearDismissTimer()
  const noticeId = value?.id
  if (!noticeId) return
  dismissTimer = setTimeout(() => {
    if (appState.globalNotice?.id === noticeId) clearGlobalNotice()
  }, value.type === 'error' ? 7000 : 4000)
}, { immediate: true })

onBeforeUnmount(clearDismissTimer)

const dismiss = () => {
  clearDismissTimer()
  clearGlobalNotice()
}
</script>

<style scoped>
.global-notice-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  position: fixed;
  top: calc(env(safe-area-inset-top, 0px) + 10px);
  left: 50%;
  z-index: 2000;
  margin: 0;
  transform: translateX(-50%);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.4;
  cursor: pointer;
  animation: noticeIn .3s ease;
  width: min(calc(100vw - 24px), 760px);
  box-sizing: border-box;
  box-shadow: 0 6px 18px rgba(19, 57, 105, .18);
}
.notice-warn {
  background: #fff7e5;
  border: 1px solid #f5d48d;
  color: #7a5800;
}
.notice-error {
  background: #fff0f0;
  border: 1px solid #f5a0a0;
  color: #a01010;
}
.notice-info {
  background: #e8f4ff;
  border: 1px solid #a0c8f5;
  color: #1048a0;
}
.notice-icon {
  flex: 0 0 auto;
  font-size: 16px;
  line-height: 1;
}
.notice-text {
  flex: 1 1 auto;
  min-width: 0;
  word-break: break-all;
}
.notice-close {
  flex: 0 0 auto;
  font-size: 14px;
  opacity: .6;
  padding: 0 2px;
}

@keyframes noticeIn {
  from { opacity: 0; transform: translate(-50%, -12px); }
  to { opacity: 1; transform: translate(-50%, 0); }
}
</style>
