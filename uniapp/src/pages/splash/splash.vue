<template>
  <view class="page-root splash-page">
    <!-- 品牌 Logo（始终显示） -->
    <image class="splash-logo" src="/static/brand/logo-white.png" mode="aspectFit" />

    <!-- 首次启动：服务器地址输入弹层 -->
    <view class="activation-overlay" v-if="showServerInput">
      <view class="activation-card">
        <text class="activation-title">连接服务器</text>
        <text class="activation-desc">请输入服务器地址</text>
        <view class="server-inputs">
          <text class="server-label">域名 / IP <text class="required-mark">*</text></text>
          <view class="input-field" @click.stop="focusHost">
            <input ref="hostRef" class="activation-input" v-model="serverHost"
              placeholder="请输入域名或IP"
              placeholder-style="color:rgba(255,255,255,0.55)" />
          </view>
          <text class="server-label">端口 <text class="required-mark">*</text></text>
          <view class="input-field" @click.stop="focusPort">
            <input ref="portRef" class="activation-input" v-model="serverPort"
              type="number" placeholder="请输入端口"
              placeholder-style="color:rgba(255,255,255,0.55)" />
          </view>
          <text class="server-label">APP渠道</text>
          <view class="channel-display">{{ channelLabel }}</view>
        </view>
        <view class="activation-actions">
          <button class="activation-btn submit" style="flex: 1;"
            :disabled="!canSubmitServer || submitting"
            @click="submitServerConfig">
            {{ submitting ? '连接中...' : '连接' }}
          </button>
        </view>
      </view>
    </view>

    <!-- 进度文字 -->
    <text class="progress-text" v-if="phaseText && !showServerInput">{{ phaseText }}</text>

    <!-- 激活码输入弹层 -->
    <view class="activation-overlay" v-if="showActivationCode && !showServerInput">
      <view class="activation-card">
        <text class="activation-title">设备编码:{{ deviceCode }}</text>
        <text class="activation-title">设备激活</text>
        <text class="activation-desc">注册码：{{ registerCode }}</text>
        <view class="expiry-row" v-if="expireTime">
          <text class="activation-expiry">有效期：{{ formatExpireTime }}</text>
          <text class="refresh-code-btn" @click.stop="refreshActivationCode">
            {{ refreshingCode ? '刷新中...' : '刷新' }}
          </text>
        </view>
        <text class="server-label activation-code-label">激活码 <text class="required-mark">*</text></text>
        <input class="activation-input" v-model="activationCode" placeholder="请输入激活码" placeholder-style="rgba(255,255,255,0.5)" />
        <view class="activation-actions">
          <button class="activation-btn submit" :disabled="!activationCode.trim() || submitting"
            @click="submitActivationCode">
            {{ submitting ? '处理中...' : '确定' }}
          </button>
        </view>
      </view>
    </view>

    <!-- 启动阶段强制升级 -->
    <view class="activation-overlay" v-if="isForceUpdate">
      <view class="activation-card force-update-card">
        <text class="activation-title">需要升级 APP</text>
        <text class="activation-desc">{{ forceUpdateMessage }}</text>
        <view v-if="forcedVersionInfo" class="force-version-info">
          <view><text>目标版本</text><b>{{ forcedVersionInfo.versionName }}（{{ forcedVersionInfo.versionCode }}）</b></view>
          <view><text>文件大小</text><b>{{ forcedVersionSize }}</b></view>
          <view class="force-release-notes">
            <text class="force-release-notes-title">版本更新日志：</text>
            <scroll-view class="force-release-notes-content" scroll-y>
              <text>{{ forcedVersionInfo.releaseNotes || '本次更新暂无说明' }}</text>
            </scroll-view>
          </view>
        </view>
        <view v-if="forceUpdateProgress > 0" class="force-progress">
          <view :style="{ width: forceUpdateProgress + '%' }" />
        </view>
        <text class="force-update-status" :class="{ error: forceUpdateError }">{{ forceUpdateStatus }}</text>
        <button class="activation-btn submit"
          :disabled="forceUpdateLoading || !forcedVersionInfo || forceUpdateStage === 'INSTALLER_OPENED'"
          @click="runForcedUpdate">
          {{ forceUpdateActionLabel }}
        </button>
      </view>
    </view>

    <!-- 错误 -->
    <view class="error-section" v-if="errorMessage && !showServerInput && !isForceUpdate">
      <text class="error-text">{{ errorMessage }}</text>
      <button class="retry-btn" @click="retryBootstrap" :disabled="isForceUpdate">
        {{ isForceUpdate ? '请升级 APP' : '重试' }}
      </button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { services } from '@/services/index.js'
import { buildBootstrapServerUrl, SERVER_INPUT_DEFAULTS, STARTUP_MODE } from '@/constants/config.js'
import { normalizeAppChannelId, resolveAppChannelLabel } from '@/constants/appChannel.js'
import { formatAppSize } from '@/services/appUpdateWorkflow.js'
import { appState, replaceDeviceInfoProjection, replaceSettingsProjection, setGlobalNotice } from '@/state/appState.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

// ── UI 状态 ──
const phaseText = ref('')
const showServerInput = ref(false)
const showActivationCode = ref(false)
const registerCode = ref('')
const deviceCode = ref('')
const expireTime = ref('')
const activationCode = ref('')
const serverHost = ref(SERVER_INPUT_DEFAULTS.host)
const serverPort = ref(String(SERVER_INPUT_DEFAULTS.port))
const channelId = ref('')
const hostRef = ref(null)
const portRef = ref(null)
const submitting = ref(false)
const refreshingCode = ref(false)
const errorMessage = ref('')
const isForceUpdate = ref(false)
const forcedVersionInfo = ref(null)
const forceUpdateMessage = ref('APP 版本过低，请升级到最新版本后再使用')
const forceUpdateStatus = ref('等待下载升级包')
const forceUpdateError = ref(false)
const forceUpdateLoading = ref(false)
const forceUpdateProgress = ref(0)
const forceUpdateOperationId = ref('')
const forceUpdateStage = ref('READY')
const completed = ref(false)
const startupMode = ref(STARTUP_MODE.ONLINE)

// ── 事件取消器 ──
const unsubs = []

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const syncDeviceInfo = (info = {}) => {
  const code = String(info?.deviceCode || '').trim()
  channelId.value = normalizeAppChannelId(info?.channelId || appState.deviceInfo.channelId)
  if (code) deviceCode.value = code
  replaceDeviceInfoProjection({
    ...appState.deviceInfo,
    ...info,
    deviceCode: code || appState.deviceInfo.deviceCode || '',
    channelId: channelId.value
  })
  return code
}

const refreshPersistedDeviceInfo = async () => {
  try {
    const info = await services.bootstrapDeviceInfo()
    syncDeviceInfo(info)
  } catch (error) {
    console.warn('[bootstrap] load persisted device info failed:', error?.message || error)
  }
}

/** 服务器地址提交按钮是否可用 */
const canSubmitServer = computed(() => {
  const host = serverHost.value.trim()
  const port = Number(serverPort.value)
  return host.length > 0 && Number.isInteger(port) && port > 0 && port <= 65535
})
const channelLabel = computed(() => resolveAppChannelLabel(channelId.value))
const forcedVersionSize = computed(() => formatAppSize(forcedVersionInfo.value?.apkSize))
const forceUpdateActionLabel = computed(() => {
  if (forceUpdateLoading.value) {
    return forceUpdateStage.value === 'DOWNLOADING'
      ? `下载中 ${forceUpdateProgress.value}%`
      : '处理中'
  }
  if (forceUpdateStage.value === 'PERMISSION_REQUIRED') return '授权后继续安装'
  if (forceUpdateStage.value === 'INSTALLER_OPENED') return '请在系统界面完成安装'
  if (forceUpdateStage.value === 'VERIFIED') return '安装更新'
  return '下载并安装'
})

/** 格式化 expireTime 时间戳 → YYYY-MM-DD HH:mm:ss */
const formatExpireTime = computed(() => {
  const t = Number(expireTime.value)
  if (!t) return ''
  // 判断是秒级还是毫秒级时间戳
  const ms = t > 1e12 ? t : t * 1000
  const d = new Date(ms)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

/**
 * 手动聚焦 uni-app input（穿透包装器，定位原生 input）
 * Tab 键可正常获焦说明 input 本身可聚焦，问题是触摸事件被 uni-app 包装器截断。
 * 参考 PasswordModal.vue 的 focusPasswordInput 模式。
 */
const focusNativeInput = async (refObj) => {
  await nextTick()
  const el = refObj.value
  if (!el) return
  if (typeof el.focus === 'function') {
    el.focus()
  } else if (typeof el.$el?.querySelector === 'function') {
    el.$el.querySelector('input')?.focus()
  }
}
const focusHost = () => focusNativeInput(hostRef)
const focusPort = () => focusNativeInput(portRef)

/**
 * 首次启动：用户输入服务器地址后保存并启动 bootstrap。
 */
async function submitServerConfig() {
  if (!canSubmitServer.value || submitting.value) return
  submitting.value = true
  const host = serverHost.value.trim()
  const port = Number(serverPort.value)
  const serverUrl = buildBootstrapServerUrl(host, port)
  const bootstrapConfig = { serverUrl }
  try {
    // 持久化 serverUrl 到 SQLite（下次启动自动跳过输入）
    await services.saveBootstrapConfig(bootstrapConfig)
    console.log('[bootstrap] server config saved:', serverUrl)
    showServerInput.value = false
    phaseText.value = '正在启动...'
    // 发起 bootstrap
    await services.bootstrap(bootstrapConfig)
  } catch (e) {
    console.warn('[bootstrap] submit server config failed:', e)
    errorMessage.value = toUserErrorMessage(e, '服务器地址保存失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 开始 bootstrap 流程（缓存命中时直接启动）。
 * 不再回退到本地硬编码默认值 — 首次启动无缓存时由 submitServerConfig 接管。
 */
async function startBootstrap() {
  try {
    if (startupMode.value === STARTUP_MODE.OFFLINE) {
      errorMessage.value = '离线版启动流程已预留，当前在线版暂不支持离线激活'
      return
    }
    const config = await services.loadBootstrapConfig()
    if (!config || !config.serverUrl) {
      // 首次启动，无缓存 config — 显示服务器地址输入 UI
      showServerInput.value = true
      return
    }
    console.log('[bootstrap] starting with cached config:', config)
    await services.bootstrap(config)
  } catch (e) {
    console.warn('[bootstrap] start call failed:', e)
    if (e?.code !== 'TIMEOUT') {
      errorMessage.value = toUserErrorMessage(e, '启动失败')
      showActivationCode.value = false
    }
  }
}

/** 导航到业务页 */
function navigateToMain() {
  // RUNNING 前已收到原生层发布的完整 config，首页直接使用它构建卡位，不能再等待同一接口。
  uni.reLaunch({ url: '/pages/index/index' })
  setTimeout(() => {
    // HTTP 配置复核和密码缓存留在后台，失败不阻塞已完成的启动。
    services.loadSettings({ transport: 'HTTP' }).catch((error) => {
      console.warn('[bootstrap] background settings refresh failed:', error?.message || error)
    })
    const identitySync = services.env === 'release'
      ? services.syncIdentityData({ source: 'STARTUP' })
      : services.syncEmployees({ source: 'STARTUP' })
    identitySync.catch((error) => {
      console.warn('[bootstrap] identity sync failed, using local cache:', error?.message || error)
      if (String(error?.message || '').includes('设备未授权组织')) {
        setGlobalNotice('设备未授权组织，人员数据尚未同步；本机卡柜与管理员登录仍可使用。', 'warn')
      }
    })
  }, 0)
}

// ── 激活码交互 ──
function submitActivationCode() {
  const code = activationCode.value.trim()
  if (!code) return
  submitting.value = true
  services.bootstrapActivate(code).finally(() => {
    submitting.value = false
  })
}

/** 刷新注册码和有效期（重新调用 activate API） */
async function refreshActivationCode() {
  if (refreshingCode.value) return
  refreshingCode.value = true
  activationCode.value = ''
  try {
    await services.bootstrapRefreshCode()
  } catch (e) {
    console.warn('[bootstrap] refresh code failed:', e)
  } finally {
    refreshingCode.value = false
  }
}

function retryBootstrap() {
  if (isForceUpdate.value) return
  errorMessage.value = ''
  phaseText.value = '正在重试...'
  showActivationCode.value = false
  services.bootstrapRetry().catch(() => {})
}

async function installForcedUpdate() {
  const result = await services.installAppUpdate(forceUpdateOperationId.value, {
    source: 'BOOTSTRAP_FORCE'
  })
  forceUpdateStage.value = String(result?.status || '').toUpperCase()
  if (forceUpdateStage.value === 'PERMISSION_REQUIRED') {
    forceUpdateStatus.value = '已打开安装授权设置，授权后返回并继续安装'
  } else if (forceUpdateStage.value === 'INSTALLER_OPENED') {
    forceUpdateStatus.value = '系统安装器已打开，请按系统提示完成安装'
  } else {
    throw new Error('系统安装流程未返回有效状态')
  }
}

async function runForcedUpdate() {
  if (forceUpdateLoading.value || !forcedVersionInfo.value) return
  forceUpdateLoading.value = true
  forceUpdateError.value = false
  try {
    if (['VERIFIED', 'PERMISSION_REQUIRED'].includes(forceUpdateStage.value)) {
      await installForcedUpdate()
      return
    }
    forceUpdateStage.value = 'DOWNLOADING'
    forceUpdateProgress.value = 0
    forceUpdateStatus.value = '正在下载并校验更新包安全性'
    const result = await services.downloadAppUpdate(forcedVersionInfo.value, {
      source: 'BOOTSTRAP_FORCE',
      onProgress: (progress) => {
        forceUpdateProgress.value = Number(progress?.progress || 0)
      }
    })
    forceUpdateOperationId.value = result.operationId
    forceUpdateProgress.value = 100
    forceUpdateStage.value = 'VERIFIED'
    forceUpdateStatus.value = '安装包校验已通过'
    await installForcedUpdate()
  } catch (error) {
    forceUpdateError.value = true
    forceUpdateStatus.value = toUserErrorMessage(error, 'APP 升级失败')
    if (forceUpdateStage.value === 'DOWNLOADING') forceUpdateStage.value = 'FAILED'
  } finally {
    forceUpdateLoading.value = false
  }
}

// ── 事件处理 ──
function onBootstrapProgress(data) {
  if (completed.value) return
  // data = { phase, message, deviceCode, registerCode, expireTime }
  const phase = data?.phase || ''
  const msg = data?.message || ''
  syncDeviceInfo(data)
  console.log('[bootstrap] progress:', phase, msg)

  switch (phase) {
    case 'VERSION_CHECK':
      phaseText.value = '正在检查版本...'
      break
    case 'REGISTERING':
      phaseText.value = '正在注册设备...';
      break
    case 'REGISTERED':
      phaseText.value = '设备已注册: ' + deviceCode.value
      break
    case 'ACTIVATING':
      phaseText.value = '正在检查激活状态...'
      break
    case 'ACTIVATED':
      phaseText.value = '设备已激活'
      break
    case 'WAITING_ACTIVATION_CODE':
      phaseText.value = '等待输入激活码...'
      registerCode.value = data.registerCode || ''
      expireTime.value = data.expireTime || ''
      activationCode.value = ''
      showActivationCode.value = true
      refreshingCode.value = false
      break
    case 'VERIFYING_CODE':
      phaseText.value = '正在验证激活码...'
      showActivationCode.value = false
      break
    case 'CODE_VERIFY_FAILED':
      phaseText.value = '激活码验证失败: ' + (msg || '请重试')
      registerCode.value = data.registerCode || registerCode.value
      activationCode.value = ''
      showActivationCode.value = true
      break
    case 'GETTING_CONFIG':
      phaseText.value = '正在获取设备配置...'
      break
    case 'CONNECTING_MQTT':
      phaseText.value = '正在连接服务器...'
      break
    case 'MQTT_CONNECTED':
      phaseText.value = '服务器已连接'
      break
    case 'LOGGING_IN':
      phaseText.value = '正在登录...'
      break
    case 'LOGGED_IN':
      phaseText.value = '已登录'
      break
    default:
      if (msg) phaseText.value = msg
      break
  }
}

function onBootstrapConfig(data) {
  syncDeviceInfo(data)
  if (data && typeof data === 'object') {
    replaceSettingsProjection(data)
    // 冷启动后主动推送摄像头配置到原生层：prewarmCameraX 用默认参数预绑定了摄像头，
    // 此处根据服务端配置切换摄像头方向等参数，re-bind 发生在 splash 阶段（WebView 遮挡），
    // 用户触发人脸识别时画面即时可用，无延迟。
    services.syncFaceCameraConfig().catch(() => {})
  }
}

function onBootstrapSuccess(data) {
  console.log('[bootstrap] success:', data)
  if (completed.value) return
  syncDeviceInfo(data)
  completed.value = true
  phaseText.value = '启动完成'
  navigateToMain().catch((error) => {
    console.warn('[bootstrap] navigate to main failed:', error?.message || error)
    errorMessage.value = '数据加载失败，请重新启动应用'
  })
}

function onBootstrapError(data) {
  if (completed.value) return
  // data = { phase, message, code }
  const msg = data?.message || ''
  const code = data?.code || ''
  console.error('[bootstrap] error:', code, msg)

  if (code === 'FORCE_UPDATE' || data?.phase === 'FORCE_UPDATE') {
    isForceUpdate.value = true
    forcedVersionInfo.value = data?.versionInfo || null
    forceUpdateMessage.value = msg || 'APP 版本过低，请升级到最新版本后再使用'
    forceUpdateStatus.value = forcedVersionInfo.value ? '等待下载升级包' : '升级信息不完整，请联系管理员'
    forceUpdateError.value = !forcedVersionInfo.value
    forceUpdateStage.value = 'READY'
    errorMessage.value = ''
    phaseText.value = '需要升级'
    showActivationCode.value = false
    return
  }

  if (msg && msg.includes('未配置')) {
    errorMessage.value = '请先填写服务器地址'
    showActivationCode.value = false
    showServerInput.value = true
    return
  }

  errorMessage.value = msg || '启动失败'
  showActivationCode.value = false
}

// ── 生命周期 ──
onMounted(async () => {
  // 初始化 NativeBridge（创建 window.NativeBridge.receive，接收 Java 下行响应/事件）
  services.init()

  // 品牌展示 900ms
  phaseText.value = ''
  await delay(900)

  // 确保与 Android 的消息通道已就绪
  const channelReady = await services.waitForChannel(3000)
  if (!channelReady) {
    console.warn('[bootstrap] Android message channel not ready after 3s, proceeding anyway')
  }

  phaseText.value = '正在启动...'

  // 注册 bootstrap 事件监听
  unsubs.push(services.on('bootstrap.progress', onBootstrapProgress))
  unsubs.push(services.on('bootstrap.config', onBootstrapConfig))
  unsubs.push(services.on('bootstrap.success', onBootstrapSuccess))
  unsubs.push(services.on('bootstrap.error', onBootstrapError))

  await refreshPersistedDeviceInfo()

  // 启动 bootstrap
  startBootstrap()
})

onUnmounted(() => {
  unsubs.forEach((fn) => fn?.())
  unsubs.length = 0
})
</script>

<style scoped>
.splash-page {
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  height: 100vh;
  height: 100dvh;
  background: #1f76ff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
}
.splash-logo {
  width: clamp(140px, 26vw, 260px);
  height: clamp(100px, 18vw, 190px);
}
.progress-text {
  color: rgba(255, 255, 255, 0.85);
  font-size: 28rpx;
  margin-top: 40rpx;
  text-align: center;
  padding: 0 60rpx;
  line-height: 1.5;
}

/* ── 弹层通用 ── */
.activation-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}
.activation-card {
  background: #1a2844;
  border-radius: 24rpx;
  padding: 48rpx 40rpx;
  width: 560rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  box-shadow: 0 12rpx 40rpx rgba(0, 0, 0, 0.35);
}
.activation-title {
  color: #fff;
  font-size: 36rpx;
  font-weight: 600;
  text-align: center;
}
.activation-desc {
  color: rgba(255, 255, 255, 0.7);
  font-size: 26rpx;
  text-align: center;
  line-height: 1.5;
}
.expiry-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8rpx;
}
.activation-expiry {
  color: rgba(255, 255, 255, 0.7);
  font-size: 26rpx;
  line-height: 1.5;
}
.refresh-code-btn {
  color: rgba(255, 255, 255, 0.65);
  font-size: 26rpx;
  padding: 4rpx 16rpx;
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 8rpx;
  transition: color 0.2s, border-color 0.2s;
}
.refresh-code-btn:active {
  color: rgba(255, 255, 255, 0.85);
  border-color: rgba(255, 255, 255, 0.5);
}
.activation-input {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 12rpx;
  color: #fff;
  font-size: 30rpx;
  padding: 24rpx;
  text-align: left;
  width: 100%;
  min-height: 80rpx;
  display: flex;
  align-items: center;
}
/* 服务器地址输入 */
.input-field {
  position: relative;
  width: 100%;
  cursor: text;
}
.server-inputs {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.server-label {
  color: rgba(255, 255, 255, 0.6);
  font-size: 24rpx;
  padding-left: 8rpx;
}
.required-mark { color: #ff8d9a; }
.activation-code-label { align-self: stretch; margin-bottom: 8rpx; }
.channel-display {
  min-height: 80rpx;
  border-radius: 12rpx;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(0, 0, 0, 0.22);
  color: #fff;
  font-size: 30rpx;
  padding: 0 24rpx;
  display: flex;
  align-items: center;
}
.activation-btn.submit[disabled] {
  opacity: 0.4;
}
.activation-actions {
  display: flex;
  gap: 24rpx;
  margin-top: 8rpx;
}
.activation-btn {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: 12rpx;
  font-size: 28rpx;
  text-align: center;
  border: none;
  font-weight: 500;
}
.activation-btn.submit {
  background: #fff;
  color: #1a2844;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.2);
}
.force-update-card { width: min(660rpx, 86vw); }
.force-version-info { border-radius: 12rpx; background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.15); padding: 20rpx; display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 16rpx; }
.force-version-info > view { display: flex; flex-direction: column; gap: 6rpx; }
.force-version-info text { color: rgba(255,255,255,.62); font-size: 22rpx; }
.force-version-info b { color: #fff; font-size: 27rpx; overflow-wrap: anywhere; }
.force-release-notes { grid-column: 1 / -1; min-width: 0; }
.force-release-notes-title { display: block; color: rgba(255,255,255,.82) !important; font-size: 22rpx; margin-bottom: 6rpx; }
.force-release-notes-content { height: 156rpx; box-sizing: border-box; padding: 10rpx 12rpx; border-radius: 8rpx; background: rgba(0,0,0,.12); }
.force-release-notes-content text { display: block; color: rgba(255,255,255,.88) !important; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.force-progress { height: 10rpx; border-radius: 999px; background: rgba(255,255,255,.18); overflow: hidden; }
.force-progress view { height: 100%; border-radius: inherit; background: #fff; transition: width .18s ease; }
.force-update-status { color: rgba(255,255,255,.78); font-size: 24rpx; line-height: 1.55; text-align: center; }
.force-update-status.error { color: #ffb5bd; }

/* 错误 */
.error-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32rpx;
  margin-top: 48rpx;
}
.error-text {
  color: rgba(255, 255, 255, 0.8);
  font-size: 28rpx;
  text-align: center;
  padding: 0 60rpx;
  line-height: 1.5;
}
.retry-btn {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  font-size: 28rpx;
  padding: 16rpx 48rpx;
  border-radius: 12rpx;
  border: 1px solid rgba(255, 255, 255, 0.3);
}
</style>

<!-- 非 scoped：穿透 uni-app <uni-input> 包装器，强制原生 input 使用白色文字 -->
<style>
.activation-card uni-input input,
.activation-card uni-input[class] input,
.activation-card .activation-input input,
.activation-card .uni-input-input {
  color: #fff !important;
  caret-color: #fff !important;
  text-align: left !important;
  font-size: 30rpx !important;
  background: transparent !important;
  border: none !important;
  outline: none !important;
  -webkit-text-fill-color: #fff !important;
}
</style>
