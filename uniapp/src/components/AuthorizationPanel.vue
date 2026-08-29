<template>
  <view class="authorization-panel">
    <view class="authorization-head">
      <view class="authorization-icon"><IconGlyph name="shield" /></view>
      <view class="authorization-copy">
        <text class="authorization-title">人员授权信息</text>
      </view>
      <!-- 授权状态标签已隐藏（不展示授权状态） -->
    </view>

    <view class="authorization-details">
      <view class="detail-row"><text>设备编号</text><b>{{ deviceCodeText }}</b></view>
      <view class="detail-row"><text>最后检查</text><b>{{ lastCheckedAt || '尚未检查' }}</b></view>
    </view>

    <view v-if="authorizationFeatures.length" class="feature-list">
      <text class="feature-label">可用功能</text>
      <view class="feature-values"><text v-for="feature in authorizationFeatures" :key="feature">{{ feature }}</text></view>
    </view>

    <button v-permission="'system.authorization.refresh'" class="auth-refresh-button" :disabled="authorizationRefreshing" @click="refreshAuthorization(true)">
      <view><IconGlyph name="refresh" /></view>
      <text>{{ authorizationRefreshing ? '正在检查' : '重新检查' }}</text>
    </button>
  </view>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { appState } from '@/state/appState.js'
import { services } from '@/services/index.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const authorizationRefreshing = ref(false)
const lastCheckedAt = ref('')
const authorization = computed(() => appState.runtime.deviceAuthorization || {})
const authorizationFeatures = computed(() => Array.isArray(authorization.value.features) ? authorization.value.features : [])
const deviceCodeText = computed(() => appState.deviceInfo.deviceCode || appState.settings.deviceCode || appState.settings.deviceId || '未配置')

function formatDateTime(value) {
  const timestamp = Number(value)
  if (!timestamp) return ''
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (part) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function refreshAuthorization(showToast = false) {
  if (authorizationRefreshing.value) return
  authorizationRefreshing.value = true
  try {
    const runtime = await services.getRuntime()
    lastCheckedAt.value = formatDateTime(runtime?.timestamp || Date.now())
    if (runtime?.authorizationChecked === false) {
      if (showToast) uni.showToast({ title: runtime.authorizationError || '未能取得最新授权状态', icon: 'none', zIndex: 9999 })
      return
    }
    if (showToast) uni.showToast({ title: '授权状态已更新', icon: 'success', zIndex: 9999 })
  } catch (error) {
    if (showToast) uni.showToast({ title: toUserErrorMessage(error, '授权信息读取失败'), icon: 'none', zIndex: 9999 })
  } finally {
    authorizationRefreshing.value = false
  }
}

onMounted(() => refreshAuthorization(false))
</script>
<style scoped>
.authorization-panel { width:100%; padding:28px; box-sizing:border-box; }
.authorization-head { min-height:54px; padding-right:36px; display:flex; align-items:center; gap:14px; }
.authorization-icon { width:48px; height:48px; flex:0 0 auto; padding:11px; box-sizing:border-box; border-radius:8px; background:#eaf2fd; color:#246fca; }
.authorization-copy { min-width:0; flex:1; display:flex; flex-direction:column; gap:4px; }
.authorization-title { color:#1f2b3d; font-size:19px; font-weight:700; }
.authorization-summary { color:#6f7e92; font-size:13px; line-height:1.45; overflow-wrap:anywhere; }
.authorization-badge { min-height:28px; padding:0 10px; border-radius:6px; display:flex; align-items:center; flex:0 0 auto; font-size:12px; font-weight:700; }
.authorization-badge.success { background:#e7f6f0; color:#11865f; }
.authorization-badge.error { background:#fdecef; color:#c73545; }
.authorization-badge.waiting { background:#edf1f5; color:#60738d; }
.authorization-details { margin-top:20px; border-top:1px solid #e3e9f0; }
.detail-row { min-height:46px; padding:8px 0; border-bottom:1px solid #e3e9f0; box-sizing:border-box; display:grid; grid-template-columns:88px minmax(0,1fr); align-items:center; gap:18px; }
.detail-row text { color:#718096; font-size:13px; }
.detail-row b { color:#27364a; font-size:13px; font-weight:600; text-align:right; overflow-wrap:anywhere; }
.feature-list { margin-top:16px; display:flex; align-items:flex-start; gap:16px; }
.feature-label { width:72px; flex:0 0 auto; padding-top:4px; color:#718096; font-size:13px; }
.feature-values { min-width:0; display:flex; flex-wrap:wrap; gap:6px; }
.feature-values text { min-height:26px; padding:4px 8px; box-sizing:border-box; border-radius:5px; background:#eef3f8; color:#48617f; font-size:12px; overflow-wrap:anywhere; }
.auth-refresh-button { width:100%; height:46px; margin-top:20px; border:0; border-radius:8px; background:#246fca; color:#fff; display:flex; align-items:center; justify-content:center; gap:8px; font-size:14px; font-weight:600; }
.auth-refresh-button::after { display:none; }
.auth-refresh-button>view { width:17px; height:17px; }
.auth-refresh-button[disabled] { opacity:.55; }
@media(max-width:560px) { .authorization-panel{width:100%;padding:22px 18px 18px}.authorization-head{align-items:flex-start}.detail-row{grid-template-columns:76px minmax(0,1fr);gap:10px}.feature-list{flex-direction:column;gap:8px}.feature-label{width:auto} }
</style>
