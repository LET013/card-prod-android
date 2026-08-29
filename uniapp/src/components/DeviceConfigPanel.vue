<template>
  <view class="config-scroll">
    <view class="content">
      <view class="page-heading">
        <view class="heading-copy">
          <text class="page-title">设备设置</text>
          <view class="heading-meta">
            <view class="meta-chip">
              <text>设备编号</text>
              <b>{{ displayDeviceCode }}</b>
            </view>
            <view class="meta-chip" :class="authorizationBadgeClass">
              <text>授权状态</text>
              <b>{{ authorizationSummaryText }}</b>
            </view>
            <view class="meta-chip">
              <text>通信方式</text>
              <b>{{ form.communicationMode }}</b>
            </view>
          </view>
        </view>
      </view>

      <view class="config-switcher">
        <view
          v-for="section in configSections"
          :key="section.key"
          class="switch-card"
          :class="[section.key, { active: activeSection === section.key }]"
          @click="activeSection = section.key"
        >
          <view class="switch-icon"><IconGlyph :name="section.icon" /></view>
          <view class="switch-copy">
            <text class="switch-title">{{ section.title }}</text>
          </view>
        </view>
      </view>

      <view class="settings-layout">
        <view v-show="activeSection === 'hardware'" class="section-card hardware-card">
          <view class="section-head"><view><text class="section-index">01</text><text class="section-name">串口/硬件</text></view><text class="section-desc">卡槽、分组、轮询</text></view>
          <view class="field-grid compact-grid">
            <view class="field wide"><text class="field-label required">串口设备路径</text><input class="config-input" v-model="form.serialPort" placeholder="/dev/ttyS5" /></view>
            <view class="field"><text class="field-label required">波特率</text><input class="config-input" v-model="form.baudRate" type="number" /></view>
            <view class="field"><text class="field-label required">卡槽总数</text><input class="config-input" v-model="form.totalSlots" type="number" /></view>
            <view class="field"><text class="field-label required">每组卡槽数</text><input class="config-input" v-model="form.groupSize" type="number" /></view>
            <view class="field"><text class="field-label">串口轮询间隔(ms)</text><input class="config-input" v-model="form.serialPollInterval" type="number" /></view>
            <view class="field"><text class="field-label">单板响应超时(ms)</text><input class="config-input" v-model="form.serialResponseTimeout" type="number" /></view>
            <view class="field"><text class="field-label">卡槽状态推送(ms)</text><input class="config-input" v-model="form.slotStatusPushInterval" type="number" /></view>
            <view class="field wide"><text class="field-label">轮询方式</text><text class="config-readonly">自动轮询</text></view>
            <view class="field wide switch-field"><view><text class="field-label">串口轮询开关</text><text class="field-help">启用后按配置周期读取卡槽状态</text></view><SwitchControl v-model="form.serialPollEnabled" /></view>
            <view class="field wide sort-direction-field">
              <text class="field-label">组内卡位排序方向</text>
              <view class="segmented">
                <button :class="{ active: form.slotSortDirection === 'VERTICAL' }" @click="form.slotSortDirection = 'VERTICAL'">垂直方向</button>
                <button :class="{ active: form.slotSortDirection === 'HORIZONTAL' }" @click="form.slotSortDirection = 'HORIZONTAL'">水平方向</button>
              </view>
            </view>
          </view>
        </view>

        <view v-show="activeSection === 'network'" class="section-card network-card">
          <view class="section-head"><view><text class="section-index">02</text><text class="section-name">通信/长连接</text></view><text class="section-desc">服务器地址和通信方式</text></view>
          <view class="field-grid compact-grid">
            <view class="field"><text class="field-label">设备编号</text><text class="config-readonly">{{ displayDeviceCode }}</text><text class="field-help">由系统注册分配，不允许手动修改</text></view>
            <view class="field"><text class="field-label">APP渠道</text><text class="config-readonly">{{ appChannelLabel }}</text><text class="field-help">用于设备注册和版本检测</text></view>
            <view class="field wide"><text class="field-label required">HTTP地址</text><input class="config-input" v-model="form.httpHost" placeholder="card-test.quyohui.com" /></view>
            <view class="field"><text class="field-label required">HTTP端口</text><input class="config-input" v-model="form.httpPort" type="number" /></view>
            <view class="field wide"><text class="field-label" :class="{ required: usesMqttConnection(form.communicationMode) }">长连接地址</text><input class="config-input" v-model="form.mqttHost" placeholder="119.146.88.108" :disabled="!usesMqttConnection(form.communicationMode)" /></view>
            <view class="field"><text class="field-label" :class="{ required: usesMqttConnection(form.communicationMode) }">长连接端口</text><input class="config-input" v-model="form.mqttPort" type="number" :disabled="!usesMqttConnection(form.communicationMode)" /></view>
            <view class="field full"><text class="field-label">通信方式</text><view class="segmented"><button :class="{ active: form.communicationMode === 'MQTT' }" @click="setCommunicationMode('MQTT')">长连接</button><button :class="{ active: form.communicationMode === 'HTTP' }" @click="setCommunicationMode('HTTP')">HTTP</button></view></view>
            <view class="field"><text class="field-label required">长连接心跳(ms)</text><input class="config-input" v-model="form.mqttHeartbeatInterval" type="number" /></view>
            <view class="field"><text class="field-label" :class="{ required: usesMqttConnection(form.communicationMode) }">长连接初次重连(ms)</text><input class="config-input" v-model="form.mqttReconnectInitialInterval" type="number" :disabled="!usesMqttConnection(form.communicationMode)" /></view>
            <view class="field"><text class="field-label" :class="{ required: usesMqttConnection(form.communicationMode) }">长连接最大重连(ms)</text><input class="config-input" v-model="form.mqttReconnectMaxInterval" type="number" :disabled="!usesMqttConnection(form.communicationMode)" /></view>
            <view class="field"><text class="field-label">状态上报间隔(秒)</text><input class="config-input" v-model="form.mqttStatusReportInterval" type="number" :disabled="!usesMqttConnection(form.communicationMode)" /></view>
          </view>
        </view>

        <view v-show="activeSection === 'recognition'" class="section-card recognition-card">
          <view class="section-head"><view><text class="section-index">03</text><text class="section-name">识别参数</text></view><text class="section-desc">人脸识别参数</text></view>
          <view class="field-grid">
            <view class="field"><text class="field-label required">人脸识别阈值</text><input class="config-input" v-model="form.faceRecognitionThreshold" type="digit" /></view>
            <view class="field"><text class="field-label">人脸识别超时(秒)</text><input class="config-input" v-model="form.faceRecognitionTimeout" type="number" /></view>
            <view class="field"><text class="field-label">人脸搜索超时(秒)</text><input class="config-input" v-model="form.searchTimeout" type="number" /></view>
            <view class="field"><text class="field-label">搜索结果间隔(秒)</text><input class="config-input" v-model="form.searchIntervalTime" type="number" /></view>
            <view class="field"><text class="field-label">人脸录入超时(秒)</text><input class="config-input" v-model="form.captureTimeout" type="number" /></view>
            <view class="field switch-field"><view><text class="field-label">静默活体检测</text><text class="field-help">开启后会自动判断摄像头前是否是真人</text></view><SwitchControl v-model="form.needFaceLiveness" /></view>
          </view>
        </view>

        <view v-show="activeSection === 'camera'" class="section-card camera-card">
          <view class="section-head"><view><text class="section-index">04</text><text class="section-name">摄像头</text></view><text class="section-desc">方向、镜像和帧尺寸</text></view>
          <view class="field-grid">
            <view class="field full"><text class="field-label">摄像头</text><view class="segmented"><button :class="{ active: form.cameraFacing === 'front' }" @click="setCameraFacing(false)">前置</button><button :class="{ active: form.cameraFacing === 'back' }" @click="setCameraFacing(true)">后置</button></view></view>
            <view class="field full"><text class="field-label">镜头旋转角度</text><view class="segmented four"><button v-for="value in rotations" :key="value" :class="{ active: Number(form.cameraRotation) === value }" @click="form.cameraRotation = value">{{ value }}°</button></view></view>
            <view class="field"><text class="field-label">摄像头帧宽</text><input class="config-input" v-model="form.cameraFrameWidth" type="number" /></view>
            <view class="field"><text class="field-label">摄像头帧高</text><input class="config-input" v-model="form.cameraFrameHeight" type="number" /></view>
            <view class="field switch-field"><view><text class="field-label">摄像头镜像</text><text class="field-help">前置摄像头通常保持镜像</text></view><SwitchControl v-model="form.cameraMirror" /></view>
          </view>
        </view>
      </view>

      <view class="action-bar">
        <view v-if="message" class="message" :class="{ error: messageType === 'error' }">{{ message }}</view>
        <view v-else class="message muted">修改后请点击保存</view>
        <button class="ghost-button" @click="goBack">返回</button>
        <button v-permission="'system.settings.edit'" class="save-button" :disabled="saving" @click="save">{{ saving ? '保存中' : '保存修改' }}</button>
      </view>

      <!-- 保存状态浮层 -->
      <Teleport to="body">
        <view v-if="savingModal.visible" class="saving-overlay" @click.stop>
          <view class="saving-dialog">
            <view class="saving-icon" v-if="savingModal.type === 'loading'">
              <view class="spinner"></view>
            </view>
            <view class="saving-icon" v-else-if="savingModal.type === 'success'">
              <view class="checkmark"></view>
            </view>
            <text class="saving-text">{{ savingModal.text }}</text>
          </view>
        </view>
      </Teleport>
    </view>
  </view>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { appState, replaceSettingsProjection, normalizeSettingsProjection } from '@/state/appState.js'
import { services } from '@/services/index.js'
import {
  MQTT_TIMING_DEFAULTS,
  normalizeMqttTimingConfig,
  usesMqttConnection
} from '@/constants/config.js'
import { normalizeAppChannelId, resolveAppChannelLabel } from '@/constants/appChannel.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const emit = defineEmits(['done'])

const SwitchControl = defineComponent({
  props: { modelValue: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('view', {
      class: ['switch-control', props.modelValue ? 'on' : ''],
      style: {
        width: '42px',
        height: '24px',
        borderRadius: '999px',
        background: props.modelValue ? '#1f76ff' : '#d8dce2',
        padding: '2px',
        boxSizing: 'border-box',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'flex-start',
        transition: 'background .16s ease',
        boxShadow: props.modelValue
          ? 'inset 0 1px 2px rgba(8, 56, 130, .16)'
          : 'inset 0 1px 2px rgba(35, 48, 69, .14)'
      },
      onClick: () => emit('update:modelValue', !props.modelValue)
    }, [h('view', {
      class: 'switch-knob',
      style: {
        width: '20px',
        height: '20px',
        borderRadius: '50%',
        background: '#fff',
        transform: props.modelValue ? 'translateX(18px)' : 'translateX(0)',
        transition: 'transform .16s ease',
        boxShadow: '0 1px 3px rgba(20, 35, 60, .28)'
      }
    })])
  }
})

const rotations = [0, 90, 180, 270]
const activeSection = ref('hardware')

const form = reactive({
  channelId: '',
  cabinetNumber: '',
  deviceId: '',
  deviceCode: '',
  serialPort: '/dev/ttyS1',
  baudRate: 115200,
  groupSize: 10,
  totalSlots: 10,
  serialPollEnabled: true,
  serialPollInterval: 500,
  serialResponseTimeout: 3000,
  slotStatusPushInterval: 10000,
  mqttStatusReportInterval: 300,
  communicationMode: 'MQTT',
  httpHost: '',
  httpPort: 8082,
  mqttHost: '',
  mqttPort: 1883,
  ...MQTT_TIMING_DEFAULTS,
  faceRecognitionThreshold: 0.8,
  fingerThreshold: 0.8,
  fingerEnabled: '0',
  faceRecognitionTimeout: 60,
  searchTimeout: 120,
  searchIntervalTime: 3,
  needFaceLiveness: true,
  captureTimeout: 30,
  cameraFacing: 'front',
  cameraMirror: true,
  cameraRotation: 0,
  cameraFrameWidth: 640,
  cameraFrameHeight: 480,
  pollingMode: 'AUTO',
  slotSortDirection: 'HORIZONTAL'
})

const configSections = [
  { key: 'hardware', title: '串口/硬件', icon: 'hardware' },
  { key: 'network', title: '通信/长连接', icon: 'network' },
  { key: 'recognition', title: '识别参数', icon: 'face' },
  { key: 'camera', title: '摄像头', icon: 'camera' }
]

const saving = ref(false)
const message = ref('')
const messageType = ref('info')

const savingModal = reactive({
  visible: false,
  type: 'loading',
  text: ''
})

const displayDeviceCode = computed(() =>
  appState.deviceInfo.deviceCode ||
  form.deviceCode ||
  appState.settings.deviceCode ||
  form.deviceId ||
  appState.settings.deviceId ||
  '未配置'
)

const authorizationState = computed(() => String(appState.runtime.deviceAuthorization?.state || '').toUpperCase())
const authorizationSummaryText = computed(() => {
  const authorization = appState.runtime.deviceAuthorization || {}
  if (['AUTHORIZED', 'ACTIVE_UNKNOWN'].includes(authorizationState.value)) return '已激活'
  if (authorizationState.value === 'UNAUTHORIZED') return '未授权'
  if (appState.deviceInfo.activated) return '已激活'
  return authorization.message || '未授权，请联系管理员'
})
const authorizationBadgeClass = computed(() => ({
  authorized: ['AUTHORIZED', 'ACTIVE_UNKNOWN'].includes(authorizationState.value) ||
    (authorizationState.value !== 'UNAUTHORIZED' && appState.deviceInfo.activated),
  unauthorized: authorizationState.value === 'UNAUTHORIZED',
  pending: !['AUTHORIZED', 'ACTIVE_UNKNOWN', 'UNAUTHORIZED'].includes(authorizationState.value) && !appState.deviceInfo.activated
}))
const appChannelLabel = computed(() => resolveAppChannelLabel(appState.deviceInfo.channelId || form.channelId))

const setMessage = (text, type = 'info') => {
  message.value = text
  messageType.value = type
}

const normalizeSlotSortDirection = (value) =>
  String(value || '').toUpperCase() === 'VERTICAL' ? 'VERTICAL' : 'HORIZONTAL'

const assignForm = (settings = {}) => {
  const normalized = normalizeSettingsProjection(settings)
  const resolvedDeviceCode = normalized.deviceCode ||
    appState.deviceInfo.deviceCode ||
    appState.settings.deviceCode ||
    normalized.deviceId ||
    appState.settings.deviceId ||
    ''
  Object.assign(form, {
    cabinetNumber: normalized.cabinetNumber || '',
    deviceId: normalized.deviceId || resolvedDeviceCode,
    deviceCode: resolvedDeviceCode,
    serialPort: normalized.serialPort || '/dev/ttyS1',
    baudRate: Number(normalized.baudRate || 115200),
    groupSize: Number(normalized.groupSize || normalized.singleGroupCount || 10),
    totalSlots: Number(normalized.totalSlots || normalized.totalCount || 10),
    serialPollEnabled: normalized.serialPollEnabled !== false && normalized.serialPollingEnabled !== false,
    serialPollInterval: Number(normalized.serialPollInterval || normalized.serialPollingIntervalMs || 500),
    serialResponseTimeout: Number(normalized.serialResponseTimeout || normalized.serialResponseTimeoutMs || 3000),
    slotStatusPushInterval: Number(normalized.slotStatusPushInterval || normalized.slotStatusReportIntervalMs || normalized.slotUiPushIntervalMs || 10000),
    mqttStatusReportInterval: Number(normalized.mqttStatusReportInterval || 300),
    communicationMode: ['MQTT', 'HTTP'].includes(normalized.communicationMode) ? normalized.communicationMode : 'MQTT',
    httpHost: normalized.httpHost || '',
    httpPort: Number(normalized.httpPort || 8082),
    mqttHost: normalized.mqttHost || normalized.mqttServerAddress || '',
    mqttPort: Number(normalized.mqttPort || 1883),
    ...normalizeMqttTimingConfig(normalized),
    faceRecognitionThreshold: Number(normalized.faceRecognitionThreshold ?? normalized.faceThreshold ?? 0.8),
    fingerThreshold: Number(normalized.fingerThreshold ?? normalized.fingerRecognitionThreshold ?? 0.8),
    fingerEnabled: normalized.fingerEnabled === '1' || normalized.fingerEnabled === 1 || normalized.fingerprintEnabled ? '1' : '0',
    faceRecognitionTimeout: Number(normalized.faceRecognitionTimeout || 60),
    searchTimeout: Number(normalized.searchTimeout || 120),
    searchIntervalTime: Number(normalized.searchIntervalTime || 3),
    needFaceLiveness: ['1', 1, true].includes(normalized.needFaceLiveness),
    captureTimeout: Number(normalized.captureTimeout || 30),
    cameraFacing: normalized.cameraFacing === 'back' ? 'back' : 'front',
    cameraMirror: normalized.cameraMirror != null ? ['1', 1, true].includes(normalized.cameraMirror) : normalized.cameraFacing !== 'back',
    cameraRotation: Number(normalized.cameraRotation ?? 0),
    cameraFrameWidth: Number(normalized.cameraFrameWidth || 640),
    cameraFrameHeight: Number(normalized.cameraFrameHeight || 480),
    pollingMode: 'AUTO',
    slotSortDirection: normalizeSlotSortDirection(normalized.slotSortDirection || normalized.slotLayoutDirection)
  })
}

const buildPayload = () => {
  const httpHost = String(form.httpHost || '').trim()
  const httpBaseUrl = httpHost ? `http://${httpHost}${Number(form.httpPort) === 80 ? '' : ':' + Number(form.httpPort)}` : ''
  const communicationMode = ['MQTT', 'HTTP'].includes(form.communicationMode) ? form.communicationMode : 'MQTT'
  const payload = {
    ...appState.settings,
    cabinetNumber: String(form.cabinetNumber || '').trim(),
    deviceId: String(form.deviceId || '').trim(),
    deviceCode: String(form.deviceCode || form.deviceId || '').trim(),
    serialPort: String(form.serialPort || '').trim(),
    baudRate: Number(form.baudRate),
    groupSize: Number(form.groupSize),
    singleGroupCount: Number(form.groupSize),
    totalSlots: Number(form.totalSlots),
    totalCount: Number(form.totalSlots),
    serialPollEnabled: Boolean(form.serialPollEnabled),
    serialPollingEnabled: Boolean(form.serialPollEnabled),
    serialPollInterval: Number(form.serialPollInterval),
    serialPollingIntervalMs: Number(form.serialPollInterval),
    serialResponseTimeout: Number(form.serialResponseTimeout),
    serialResponseTimeoutMs: Number(form.serialResponseTimeout),
    slotStatusPushInterval: Number(form.slotStatusPushInterval),
    slotStatusReportIntervalMs: Number(form.slotStatusPushInterval),
    mqttStatusReportInterval: Number(form.mqttStatusReportInterval),
    pollingMode: 'AUTO',
    communicationMode,
    backendTransport: communicationMode,
    httpHost,
    serverUrl: httpBaseUrl,
    httpServerAddress: httpBaseUrl,
    httpPort: Number(form.httpPort),
    mqttHeartbeatInterval: Number(form.mqttHeartbeatInterval),
    faceRecognitionThreshold: Number(form.faceRecognitionThreshold),
    faceThreshold: Number(form.faceRecognitionThreshold),
    fingerThreshold: Number(form.fingerThreshold),
    fingerEnabled: form.fingerEnabled === '1' ? '1' : '0',
    faceRecognitionTimeout: Number(form.faceRecognitionTimeout),
    searchTimeout: Number(form.searchTimeout),
    searchIntervalTime: Number(form.searchIntervalTime),
    needFaceLiveness: Boolean(form.needFaceLiveness),
    captureTimeout: Number(form.captureTimeout),
    cameraFacing: form.cameraFacing === 'back' ? 'back' : 'front',
    cameraMirror: Boolean(form.cameraMirror),
    cameraRotation: Number(form.cameraRotation),
    cameraFrameWidth: Number(form.cameraFrameWidth),
    cameraFrameHeight: Number(form.cameraFrameHeight),
    slotSortDirection: form.slotSortDirection,
    initialized: true
  }
  if (usesMqttConnection(communicationMode)) {
    Object.assign(payload, {
      mqttHost: String(form.mqttHost || '').trim(),
      mqttServerAddress: String(form.mqttHost || '').trim(),
      mqttPort: Number(form.mqttPort),
      mqttReconnectInitialInterval: Number(form.mqttReconnectInitialInterval),
      mqttReconnectMaxInterval: Number(form.mqttReconnectMaxInterval)
    })
  }
  return payload
}

const validate = () => {
  const serialPort = String(form.serialPort || '').trim()
  const baudRate = Number(form.baudRate)
  const totalSlots = Number(form.totalSlots)
  const groupSize = Number(form.groupSize)
  const httpPort = Number(form.httpPort)
  const mqttPort = Number(form.mqttPort)
  const mqttHeartbeatInterval = Number(form.mqttHeartbeatInterval)
  const mqttReconnectInitialInterval = Number(form.mqttReconnectInitialInterval)
  const mqttReconnectMaxInterval = Number(form.mqttReconnectMaxInterval)
  const threshold = Number(form.faceRecognitionThreshold)
  const mqttConnectionEnabled = usesMqttConnection(form.communicationMode)
  if (!serialPort) return '串口不能为空'
  if (!Number.isInteger(baudRate) || baudRate <= 0) return '波特率必须大于0'
  if (!Number.isInteger(groupSize) || groupSize < 1) return '单组数量必须大于0'
  if (!Number.isInteger(totalSlots) || totalSlots < 1) return '总体数量必须大于0'
  if (groupSize > totalSlots) return '单组数量不能超过总体数量'
  if (!String(form.httpHost || '').trim()) return 'HTTP地址不能为空'
  if (!Number.isInteger(httpPort) || httpPort < 1 || httpPort > 65535) return 'HTTP端口必须是1到65535'
  if (!Number.isInteger(mqttHeartbeatInterval) || mqttHeartbeatInterval < 1) return '长连接心跳间隔必须是正整数'
  if (mqttConnectionEnabled && !String(form.mqttHost || '').trim()) return 'MQTT地址不能为空'
  if (mqttConnectionEnabled && (!Number.isInteger(mqttPort) || mqttPort < 1 || mqttPort > 65535)) return '长连接端口必须是1到65535'
  if (mqttConnectionEnabled && (!Number.isInteger(mqttReconnectInitialInterval) || mqttReconnectInitialInterval < 1)) return '长连接初次重连间隔必须是正整数'
  if (mqttConnectionEnabled && (!Number.isInteger(mqttReconnectMaxInterval) || mqttReconnectMaxInterval < 1)) return '长连接最大重连间隔必须是正整数'
  if (Number.isNaN(threshold) || threshold < 0 || threshold > 1) return '人脸识别阈值必须在0到1之间'
  return ''
}

const setCameraFacing = (isBack) => {
  form.cameraFacing = isBack ? 'back' : 'front'
  if (!isBack && form.cameraMirror == null) form.cameraMirror = true
}

const setCommunicationMode = (mode) => {
  form.communicationMode = ['MQTT', 'HTTP'].includes(mode) ? mode : 'MQTT'
}

const save = async () => {
  const error = validate()
  if (error) {
    setMessage(error, 'error')
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  saving.value = true
  savingModal.visible = true
  savingModal.type = 'loading'
  savingModal.text = '正在保存...'
  services.recordAuditEvent({ event_type: 'BUTTON_CLICK', action_code: 'CONFIG_SAVE', action_label: '保存配置' })
  try {
    const payload = buildPayload()
    const result = await services.saveSettings(payload)
    if (!result?.remoteSaved) {
      const layoutNotConfirmed = ['CONFIG_RESPONSE_MISSING', 'CONFIG_LAYOUT_MISSING', 'CONFIG_LAYOUT_NOT_CONFIRMED'].includes(result?.errorCode)
      setMessage(layoutNotConfirmed ? '后台未确认卡槽布局，本机未应用；请检查后台配置后重试' : '配置未同步，本机未应用；请检查网络后重试', 'error')
      savingModal.visible = false
      uni.showToast({ title: layoutNotConfirmed ? '后台未确认配置' : '后台同步失败', icon: 'none' })
    } else if (!result?.localSaved) {
      setMessage('后台已保存，但本机持久化失败，请重新进入页面确认', 'error')
      savingModal.visible = false
    } else {
      const saved = result?.data || payload
      assignForm(saved)
      if (result?.restartRequired) {
        savingModal.type = 'success'
        savingModal.text = '配置保存成功'
        setMessage(`卡槽显示已更新：${saved.totalSlots}个卡槽，每组${saved.groupSize}个；通信和串口参数重启应用后生效`)
        await delayForResult(1800)
        savingModal.visible = false
        uni.showToast({ title: '重启后生效', icon: 'none' })
      } else {
        savingModal.type = 'success'
        savingModal.text = '配置保存成功'
        setMessage(`卡槽显示已更新：${saved.totalSlots}个卡槽，每组${saved.groupSize}个`)
        await delayForResult(1800)
        savingModal.visible = false
      }
    }
  } catch (error) {
    setMessage(toUserErrorMessage(error, '配置保存失败'), 'error')
    savingModal.visible = false
  } finally {
    saving.value = false
  }
}

const delayForResult = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const goBack = () => {
  services.recordAuditEvent({ event_type: 'BUTTON_CLICK', action_code: 'CONFIG_BACK', action_label: '返回配置' })
  emit('done')
}

onMounted(async () => {
  services.init()
  assignForm(appState.settings)

  const [settingsResult, runtimeResult] = await Promise.allSettled([
    services.loadSettings(),
    services.getRuntime()
  ])
  if (settingsResult.status === 'fulfilled') {
    const settings = settingsResult.value
    replaceSettingsProjection(settings || {})
    assignForm(settings || {})
  } else {
    setMessage(toUserErrorMessage(settingsResult.reason, '配置读取失败'), 'error')
  }
  if (runtimeResult.status === 'rejected') {
    console.warn('load config runtime failed:', runtimeResult.reason)
  } else {
    form.channelId = normalizeAppChannelId(runtimeResult.value?.deviceInfo?.channelId)
  }
})
</script>

<style scoped>
.config-scroll { width: 100%; height: min(88vh, 820px); min-height: 0; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; -webkit-overflow-scrolling: touch; touch-action: pan-y; background: #e8f2ff; }
.config-scroll.device-config-panel-modal { height: 100%; max-height: 100%; }
.content { width: min(100%, 1220px); margin: 0 auto; padding: clamp(18px, 2.6vw, 32px) clamp(12px, 2vw, 24px) max(22px, env(safe-area-inset-bottom)); box-sizing: border-box; }
.page-heading { margin-bottom: 12px; }
.heading-copy { min-height: 74px; border-radius: 8px; background: #fff; border: 1px solid #dbe6f3; box-shadow: 0 6px 18px rgba(68, 92, 130, .09); padding: 16px 58px 16px 20px; box-sizing: border-box; display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 18px; }
.page-title { display: block; font-size: clamp(24px, 2.6vw, 32px); line-height: 1.2; font-weight: 750; color: #1d2b42; }
.heading-meta { display: flex; flex-wrap: nowrap; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 0; min-width: 0; }
.meta-chip { min-width: 0; max-width: min(30vw, 260px); min-height: 30px; border-radius: 999px; background: #f4f8ff; border: 1px solid #dce8f7; padding: 5px 10px; box-sizing: border-box; display: flex; align-items: center; gap: 8px; color: #41536b; font-size: 13px; line-height: 1.2; }
.meta-chip text { flex: 0 0 auto; color: #6d7e94; white-space: nowrap; }
.meta-chip b { flex: 0 1 auto; min-width: 0; color: #1d2b42; font-size: 13px; font-weight: 750; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta-chip.authorized { border-color: #bcebd0; background: #f0fff6; }
.meta-chip.authorized b { color: #089b47; }
.meta-chip.unauthorized { border-color: #ffd1d8; background: #fff5f7; }
.meta-chip.unauthorized b { color: #df2f46; }
.meta-chip.pending { border-color: #dce8fb; background: #f6faff; }
.meta-chip.pending b { color: #2f67a8; }
.config-switcher { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 16px; }
.switch-card { position: relative; min-height: 74px; border-radius: 10px; background: linear-gradient(180deg, #fff, #f9fbff); border: 1px solid #dbe6f3; box-shadow: 0 6px 18px rgba(68, 92, 130, .08); padding: 12px 14px; box-sizing: border-box; display: grid; grid-template-columns: 38px minmax(0, 1fr); gap: 10px; align-items: center; cursor: pointer; overflow: hidden; transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease, background .16s ease; }
.switch-card::after { content: ""; position: absolute; left: 14px; right: 14px; bottom: 0; height: 3px; border-radius: 999px 999px 0 0; opacity: 0; transition: opacity .16s ease; }
.switch-card.active { border-color: #9fc7ff; background: linear-gradient(180deg, #f7fbff, #fff); box-shadow: 0 10px 24px rgba(31, 118, 255, .14); transform: translateY(-1px); }
.switch-card.active::after { opacity: 1; }
.switch-icon { width: 38px; height: 38px; border-radius: 11px; color: #fff; padding: 9px; box-sizing: border-box; box-shadow: 0 8px 16px rgba(32, 92, 170, .18); }
.switch-card.hardware .switch-icon { background: linear-gradient(135deg, #4aa3ff, #0a53c4); }
.switch-card.network .switch-icon { background: linear-gradient(135deg, #22c7d8, #1472e6); }
.switch-card.recognition .switch-icon { background: linear-gradient(135deg, #ff6b7a, #e11d48); }
.switch-card.camera .switch-icon { background: linear-gradient(135deg, #8b7cff, #4f46e5); }
.switch-card.hardware::after { background: #1f76ff; }
.switch-card.network::after { background: #17a6c8; }
.switch-card.recognition::after { background: #f43f5e; }
.switch-card.camera::after { background: #6554e8; }
.switch-copy { min-width: 0; display: flex; flex-direction: column; justify-content: center; }
.switch-title { color: #1d2b42; font-size: 17px; line-height: 1.25; font-weight: 760; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.settings-layout { display: block; }
.section-card { background: #fff !important; border: 1px solid #dbe6f3; border-radius: 8px; box-shadow: 0 6px 18px rgba(68, 92, 130, .09); padding: clamp(18px, 2vw, 24px); box-sizing: border-box; }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding-bottom: 15px; margin-bottom: 16px; border-bottom: 1px solid #e8eef7; }
.section-head > view { display: flex; align-items: center; gap: 10px; min-width: 0; }
.section-index { width: 34px; height: 34px; border-radius: 8px; background: #edf5ff; color: #1f76ff; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 800; flex: 0 0 auto; }
.section-name { font-size: 20px; line-height: 34px; font-weight: 760; color: #1d2b42; white-space: nowrap; }
.section-desc { color: #8392a8; font-size: 13px; line-height: 34px; white-space: nowrap; }
.field-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.compact-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.field { min-width: 0; min-height: 58px; border-radius: 8px; background: #f7faff; border: 1px solid #e1eaf5; padding: 8px 12px; box-sizing: border-box; }
.field.wide { grid-column: span 2; }
.field.full, .note-line { grid-column: 1 / -1; }
.field-label { display: block; color: #65748a; font-size: 13px; line-height: 1.25; margin-bottom: 5px; }
.field-label.required::after { content: ' *'; color: #e8423f; font-weight: 750; }
.field-help { display: block; color: #8b98aa; font-size: 12px; line-height: 1.35; margin-top: 5px; }
.config-input { width: 100%; height: 34px; min-width: 0; padding: 0 9px; border: 1px solid #b0bfd1; border-radius: 6px; background: #fff; color: #223047; font-size: 15px; line-height: 32px; box-sizing: border-box; }
.config-input:focus { border-color: #1f76ff; box-shadow: 0 0 0 2px rgba(31,118,255,.16); }
.config-input[disabled] { border-color: #d8e0eb; background: #eef1f5; color: #8b96a6; }
.config-readonly { display: block; min-height: 34px; padding: 0 9px; border: 1px dashed #c8d3e1; border-radius: 6px; background: #eef2f7; color: #526177; font-size: 15px; line-height: 32px; font-weight: 650; box-sizing: border-box; }
.segmented { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.segmented.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.segmented.four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.segmented button { height: 34px; min-width: 0; margin: 0; padding: 0 10px; border: 1px solid #d5dfec; border-radius: 6px; background: #fff; color: #516176; font-size: 14px; line-height: 32px; white-space: nowrap; }
.segmented button::after, .ghost-button::after, .save-button::after { border: 0; }
.segmented button.active { border-color: #1f76ff; background: #1f76ff; color: #fff; font-weight: 650; box-shadow: 0 5px 12px rgba(31, 118, 255, .22); }
.switch-field { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.note-line { min-height: 44px; border-radius: 8px; background: #eef6ff; color: #63748a; font-size: 13px; line-height: 1.6; padding: 10px 12px; box-sizing: border-box; }
.action-bar { position: sticky; bottom: 0; margin-top: 18px; min-height: 76px; border-radius: 8px; background: rgba(255, 255, 255, .96); border: 1px solid #dbe6f3; box-shadow: 0 -4px 18px rgba(68, 92, 130, .08); padding: 12px 14px; display: grid; grid-template-columns: minmax(0, 1fr) 132px 168px; gap: 14px; align-items: center; box-sizing: border-box; }
.message { min-width: 0; color: #516176; font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.message.muted { color: #8190a4; }
.message.error { color: #ef4053; font-weight: 600; }
.ghost-button, .save-button { height: 50px; margin: 0; border: 0; border-radius: 8px; background: #edf3fb; color: #3d4d63; font-size: 17px; line-height: 50px; display: flex; align-items: center; justify-content: center; }
.save-button { background: linear-gradient(135deg, #4aa3ff 0%, #1f76ff 48%, #0a53c4 100%); color: #fff; box-shadow: 0 8px 16px rgba(31,118,255,.22), inset 0 1px 1px rgba(255,255,255,.24); font-weight: 700; box-shadow: 0 8px 18px rgba(31, 118, 255, .22); }
.switch-control { width: 42px; height: 24px; border-radius: 999px; background: #d8dce2; border: 0; padding: 2px; box-sizing: border-box; flex: 0 0 auto; display: flex; align-items: center; justify-content: flex-start; cursor: pointer; box-shadow: inset 0 1px 2px rgba(35, 48, 69, .14); transition: background .16s ease; }
.switch-control.on { background: #1f76ff; box-shadow: inset 0 1px 2px rgba(8, 56, 130, .16); }
.switch-knob { width: 20px; height: 20px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(20, 35, 60, .28); transition: transform .16s ease; }
.switch-control.on .switch-knob { transform: translateX(18px); }
button[disabled] { opacity: .58; }

/* 保存状态浮层 */
.saving-overlay {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0, 0, 0, .45);
  display: flex; align-items: center; justify-content: center;
  animation: fadeIn .2s ease;
}
.saving-dialog {
  min-width: 220px; max-width: 80vw;
  background: #fff; border-radius: 14px;
  box-shadow: 0 20px 48px rgba(0, 0, 0, .18);
  padding: 32px 28px 28px;
  display: flex; flex-direction: column; align-items: center; gap: 18px;
  animation: scaleIn .22s cubic-bezier(.22, .54, .29, 1);
}
.saving-icon { width: 48px; height: 48px; display: flex; align-items: center; justify-content: center; }
.saving-text { font-size: 16px; font-weight: 650; color: #223047; line-height: 1.4; text-align: center; }

/* 旋转加载 */
.spinner {
  width: 36px; height: 36px;
  border: 3px solid #e1eaf5;
  border-top-color: #1f76ff;
  border-radius: 50%;
  animation: spin .7s linear infinite;
}
/* 成功对勾 */
.checkmark {
  width: 48px; height: 48px;
  border-radius: 50%;
  background: #06b155;
  position: relative;
  animation: popIn .35s cubic-bezier(.34, 1.56, .64, 1);
}
.checkmark::after {
  content: "";
  position: absolute; top: 13px; left: 16px;
  width: 14px; height: 8px;
  border-left: 3px solid #fff;
  border-bottom: 3px solid #fff;
  transform: rotate(-45deg);
}

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes scaleIn { from { opacity: 0; transform: scale(.88); } to { opacity: 1; transform: scale(1); } }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes popIn { from { transform: scale(0); } to { transform: scale(1); } }

@media(max-width:980px) { .config-switcher { grid-template-columns: repeat(2, minmax(0, 1fr)); } .field-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .compact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .field.wide { grid-column: span 2; } }
@media(max-width:640px) { .config-scroll { height:calc(100vh - 40px); } .content { padding: 12px 10px max(18px, env(safe-area-inset-bottom)); } .heading-copy { min-height: 70px; padding: 18px 52px 18px 18px; grid-template-columns: 1fr; gap: 10px; } .heading-meta { justify-content: flex-start; flex-wrap: wrap; } .meta-chip { max-width: 100%; } .field-grid, .action-bar { grid-template-columns: 1fr; } .action-bar { position: static; } .compact-grid { grid-template-columns: 1fr; } .field.wide { grid-column: 1 / -1; } .config-switcher { grid-template-columns: 1fr; } .switch-card { min-height: 66px; grid-template-columns: 36px minmax(0, 1fr); padding: 12px; } .switch-icon { width: 36px; height: 36px; padding: 8px; } .section-head { flex-direction: column; gap: 6px; } .section-desc { line-height: 1.4; } .segmented.three, .segmented.four { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@supports (height: 100dvh) { .config-scroll { height:min(88dvh,820px); } @media(max-width:640px) { .config-scroll { height:calc(100dvh - 40px); } } }
</style>
