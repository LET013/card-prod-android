<template>
  <view class="app-update-panel">
    <view class="update-summary">
      <view class="summary-item"><text>当前版本</text><b>{{ currentVersionText }}</b></view>
      <view class="summary-item"><text>APP渠道</text><b>{{ channelLabel }}</b></view>
    </view>

    <view v-if="versionInfo" class="version-card">
      <view class="version-head">
        <view><text class="version-caption">发现新版本</text><text class="version-name">{{ versionInfo.versionName }}</text></view>
        <text v-if="versionInfo.forceUpdate" class="force-badge">强制更新</text>
      </view>
      <view class="version-meta">
        <text>版本号 {{ versionInfo.versionCode }}</text>
        <text>{{ appSize }}</text>
      </view>
      <view class="release-notes-block">
        <text class="release-notes-title">版本更新日志：</text>
        <scroll-view class="release-notes" scroll-y>
          <text>{{ versionInfo.releaseNotes || '本次更新暂无说明' }}</text>
        </scroll-view>
      </view>
    </view>

    <view v-if="progressVisible" class="progress-block">
      <view class="progress-head"><text>下载并校验</text><b>{{ progress }}%</b></view>
      <view class="progress-track"><view class="progress-value" :style="{ width: progress + '%' }" /></view>
    </view>

    <view class="update-feedback" :class="feedbackType">
      <text>{{ feedback }}</text>
    </view>

    <button class="update-primary" :disabled="busy || primaryDisabled" @click="runPrimary">
      {{ primaryLabel }}
    </button>
    <button v-if="versionInfo && !busy" class="update-secondary" @click="checkUpdate">重新检查</button>
  </view>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { services } from '@/services/index.js'
import { formatAppSize } from '@/services/appUpdateWorkflow.js'
import { resolveAppChannelLabel } from '@/constants/appChannel.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const currentInfo = ref({})
const versionInfo = ref(null)
const operationId = ref('')
const nativeState = ref('NONE')
const progress = ref(0)
const busy = ref(false)
const feedback = ref('正在读取当前版本')
const feedbackType = ref('info')

const currentVersionText = computed(() => {
  const name = String(currentInfo.value?.currentVersionName || '').trim() || '未知'
  const code = Number(currentInfo.value?.currentVersionCode || 0)
  return code > 0 ? `${name}（${code}）` : name
})
const channelLabel = computed(() => resolveAppChannelLabel(currentInfo.value?.channelId))
const appSize = computed(() => formatAppSize(versionInfo.value?.apkSize))
const progressVisible = computed(() => progress.value > 0 || nativeState.value === 'DOWNLOADING')
const readyToInstall = computed(() => ['VERIFIED', 'PERMISSION_REQUIRED', 'INSTALLER_OPENED'].includes(nativeState.value))
const primaryDisabled = computed(() => nativeState.value === 'INSTALLER_OPENED')
const primaryLabel = computed(() => {
  if (busy.value) return nativeState.value === 'DOWNLOADING' ? `下载中 ${progress.value}%` : '处理中'
  if (!versionInfo.value) return '检查更新'
  if (nativeState.value === 'PERMISSION_REQUIRED') return '授权后继续安装'
  if (nativeState.value === 'INSTALLER_OPENED') return '请在系统界面完成安装'
  if (readyToInstall.value) return '安装更新'
  return '下载并校验'
})

const applyNativeStatus = (status = {}) => {
  currentInfo.value = { ...currentInfo.value, ...status }
  nativeState.value = String(status.status || nativeState.value || 'NONE').toUpperCase()
  operationId.value = String(status.operationId || operationId.value || '')
  progress.value = Math.max(0, Math.min(100, Number(status.progress || progress.value || 0)))
}

const loadStatus = async () => {
  const status = await services.getAppUpdateStatus()
  applyNativeStatus(status)
  if (['VERIFIED', 'PERMISSION_REQUIRED'].includes(nativeState.value)) {
    feedback.value = nativeState.value === 'VERIFIED'
      ? '安装包校验已通过'
      : '已打开安装授权设置，授权后返回并继续安装'
  } else if (nativeState.value === 'INSTALLER_OPENED') {
    feedback.value = '系统安装器已打开，请按系统提示完成安装'
  }
}

const checkUpdate = async () => {
  if (busy.value) return
  busy.value = true
  feedbackType.value = 'info'
  feedback.value = '正在检查新版本'
  try {
    const result = await services.checkAppUpdate({ source: 'MANUAL' })
    currentInfo.value = { ...currentInfo.value, ...result }
    versionInfo.value = result.versionInfo || null
    feedback.value = versionInfo.value ? '已获取新版本信息' : '当前已经是最新版本'
  } catch (error) {
    feedbackType.value = 'error'
    feedback.value = toUserErrorMessage(error, '版本检查失败')
  } finally {
    busy.value = false
  }
}

const downloadUpdate = async () => {
  busy.value = true
  nativeState.value = 'DOWNLOADING'
  progress.value = 0
  feedbackType.value = 'info'
  feedback.value = '正在下载并校验更新包安全性'
  try {
    const result = await services.downloadAppUpdate(versionInfo.value, {
      onProgress: (value) => { progress.value = Number(value?.progress || 0) }
    })
    operationId.value = result.operationId
    nativeState.value = 'VERIFIED'
    progress.value = 100
    feedback.value = '安装包校验已通过'
  } catch (error) {
    nativeState.value = 'FAILED'
    feedbackType.value = 'error'
    feedback.value = toUserErrorMessage(error, 'APP 更新下载失败')
  } finally {
    busy.value = false
  }
}

const installUpdate = async () => {
  busy.value = true
  feedbackType.value = 'info'
  feedback.value = '正在打开系统安装流程'
  try {
    const result = await services.installAppUpdate(operationId.value)
    applyNativeStatus(result)
    feedback.value = nativeState.value === 'PERMISSION_REQUIRED'
      ? '已打开安装授权设置，授权后返回并再次点击继续安装'
      : '系统安装器已打开，请按系统提示完成安装'
  } catch (error) {
    feedbackType.value = 'error'
    feedback.value = toUserErrorMessage(error, '系统安装流程打开失败')
  } finally {
    busy.value = false
  }
}

const runPrimary = () => {
  if (!versionInfo.value) return checkUpdate()
  if (readyToInstall.value) return installUpdate()
  return downloadUpdate()
}

onMounted(async () => {
  services.init()
  try {
    await loadStatus()
  } catch (error) {
    feedback.value = toUserErrorMessage(error, '升级状态读取失败')
    feedbackType.value = 'error'
  }
  await checkUpdate()
})
</script>

<style scoped>
.app-update-panel { width: 100%; box-sizing: border-box; padding: 26px; display: flex; flex-direction: column; gap: 14px; }
.update-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.summary-item { min-width: 0; border-radius: 9px; background: #f3f7fd; border: 1px solid #dfe8f4; padding: 10px 12px; display: flex; flex-direction: column; gap: 5px; }
.summary-item text { color: #75849a; font-size: 12px; }
.summary-item b { color: #24344c; font-size: 15px; overflow-wrap: anywhere; }
.version-card { border-radius: 10px; border: 1px solid #cfe1fb; background: #f7fbff; padding: 15px; }
.version-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.version-head > view { display: flex; flex-direction: column; gap: 5px; }
.version-caption { color: #6d7d93; font-size: 12px; }
.version-name { color: #1d2e47; font-size: 20px; font-weight: 750; }
.force-badge { flex: 0 0 auto; color: #cf3348; background: #fff0f3; border-radius: 999px; padding: 4px 9px; font-size: 12px; font-weight: 650; }
.version-meta { display: flex; gap: 16px; margin-top: 10px; color: #62748d; font-size: 12px; }
.release-notes-block { margin-top: 12px; }
.release-notes-title { display: block; color: #52647d; font-size: 13px; font-weight: 650; margin-bottom: 6px; }
.release-notes { height: 88px; box-sizing: border-box; padding: 8px 10px; border-radius: 7px; background: #fff; border: 1px solid #e0e9f5; }
.release-notes text { display: block; color: #52647d; font-size: 13px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.progress-block { border-radius: 9px; background: #f5f8fc; padding: 12px; }
.progress-head { display: flex; justify-content: space-between; color: #52647c; font-size: 12px; }
.progress-track { height: 7px; margin-top: 8px; border-radius: 999px; background: #dce7f5; overflow: hidden; }
.progress-value { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #3ba4ff, #1f76ff); transition: width .18s ease; }
.update-feedback { min-height: 42px; border-radius: 8px; background: #eef6ff; color: #496682; padding: 10px 12px; box-sizing: border-box; font-size: 13px; line-height: 1.55; }
.update-feedback.error { background: #fff2f4; color: #b43245; }
.update-primary, .update-secondary { width: 100%; height: 48px; margin: 0; border-radius: 8px; font-size: 15px; line-height: 48px; }
.update-primary { background: #1f76ff; color: #fff; font-weight: 650; }
.update-primary[disabled] { opacity: .55; }
.update-secondary { background: #eef3fa; color: #50637b; }
.update-primary::after, .update-secondary::after { border: 0; }
@media(max-width:560px) { .update-summary { grid-template-columns: 1fr; } }
</style>
