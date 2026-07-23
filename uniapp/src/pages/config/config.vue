<template>
  <view class="page-root settings-page">
    <view class="settings-panel">
      <view class="panel-header">
        <text class="panel-title">设备配置</text>
        <text class="close-button" @click="goBack">×</text>
      </view>

      <scroll-view class="settings-scroll" scroll-y>
        <view class="section">
          <view class="section-title">设备与串口</view>
          <view class="field-row">
            <text class="field-label required">串口设备</text>
            <input class="field-input wide" v-model.trim="form.serialPort" placeholder="例如 /dev/ttyS5" />
          </view>
          <view class="field-row">
            <text class="field-label required">波特率</text>
            <input class="field-input" v-model="form.baudRate" type="number" />
          </view>
          <view class="field-row">
            <text class="field-label">机器标识</text>
            <input class="field-input wide readonly" value="由 AndroidID 生成，V4.1 不允许在此猜测或覆盖" disabled />
          </view>
          <view class="field-row">
            <text class="field-label">设备编码</text>
            <input class="field-input wide readonly" :value="form.deviceCode || '注册后由服务端下发'" disabled />
          </view>
          <view class="field-row">
            <text class="field-label">激活码</text>
            <input class="field-input wide" v-model.trim="form.activationCode" placeholder="仅待激活设备需要" />
          </view>
        </view>

        <view class="section">
          <view class="section-title">HTTP 配置</view>
          <text class="section-help">注册、激活、配置、员工/人脸同步和文件下载均使用此地址。</text>
          <view class="field-row">
            <text class="field-label required">本机HTTP协议</text>
            <view class="field-select" @click="openEditor('httpScheme')">{{ form.httpScheme || '请选择' }}</view>
          </view>
          <view class="field-row">
            <text class="field-label required">HTTP域名/IP</text>
            <input class="field-input wide" v-model.trim="form.httpServerAddress" placeholder="例如 api.example.com" />
          </view>
          <view class="field-row">
            <text class="field-label required">HTTP端口</text>
            <input class="field-input" v-model="form.httpPort" type="number" />
          </view>
          <view class="field-row">
            <text class="field-label">基础路径</text>
            <input class="field-input wide" v-model.trim="form.httpBasePath" placeholder="通常留空，例如 /prod" />
          </view>
        </view>

        <view class="section">
          <view class="section-title">实时通信配置</view>
          <view class="field-row">
            <text class="field-label required">通信方式</text>
            <view class="field-select" @click="openEditor('backendTransport')">{{ transportLabel }}</view>
          </view>

          <template v-if="String(form.backendTransport).toUpperCase()==='MQTT'">
            <text class="section-help">MQTT 与 HTTP 可使用完全不同的服务器。</text>
            <view class="field-row">
              <text class="field-label required">本机MQTT协议</text>
              <view class="field-select" @click="openEditor('mqttScheme')">{{ form.mqttScheme || '请选择' }}</view>
            </view>
            <view class="field-row">
              <text class="field-label required">MQTT域名/IP</text>
              <input class="field-input wide" v-model.trim="form.mqttServerAddress" placeholder="例如 mqtt.example.com" />
            </view>
            <view class="field-row">
              <text class="field-label required">MQTT端口</text>
              <input class="field-input" v-model="form.mqttPort" type="number" />
            </view>
          </template>

          <template v-else-if="String(form.backendTransport).toUpperCase()==='TCP'">
            <text class="section-help warning">TCP 是旧版兼容模式；新后端实时指令优先使用 MQTT。</text>
            <view class="field-row">
              <text class="field-label required">TCP域名/IP</text>
              <input class="field-input wide" v-model.trim="form.tcpServerAddress" placeholder="例如 192.168.1.10" />
            </view>
            <view class="field-row">
              <text class="field-label required">TCP端口</text>
              <input class="field-input" v-model="form.tcpPort" type="number" />
            </view>
          </template>

          <template v-else>
            <text class="section-help warning">HTTP 模式支持登录、心跳和上报；文档没有 HTTP 下行指令接口，因此远程开门不可用。</text>
          </template>
        </view>

        <view class="section">
          <view class="section-title">卡位配置</view>
          <view class="field-row">
            <text class="field-label required">卡位总数</text>
            <input class="field-input" v-model="form.totalCount" type="number" />
          </view>
          <view class="field-row">
            <text class="field-label required">分组大小</text>
            <input class="field-input" v-model="form.singleGroupCount" type="number" />
          </view>
          <text class="section-help warning">分组大小当前只用于界面分组和批次展示，不再把 100 个卡位取模映射到少量串口地址。</text>
        </view>

        <view class="section">
          <view class="section-title">轮询与卡号</view>
          <view class="field-row">
            <text class="field-label">自动轮询</text>
            <input class="field-input wide readonly" value="已禁用：缺少slotId到从机地址/切组协议" disabled />
          </view>
          <view class="field-row">
            <text class="field-label required">轮询间隔(ms)</text>
            <input class="field-input" v-model="form.serialPollingIntervalMs" type="number" />
          </view>
          <view class="field-row">
            <text class="field-label">轮询方式</text>
            <input class="field-input wide readonly" value="待确认硬件分组/切组协议" disabled />
          </view>
          <view class="field-row">
            <text class="field-label">卡号解析方式</text>
            <input class="field-input wide readonly" value="15字节ASCII（协议明确）" disabled />
          </view>
        </view>

        <view class="section">
          <view class="section-title">识别配置</view>
          <view class="field-row">
            <text class="field-label required">人脸识别阈值</text>
            <input class="field-input" v-model="form.faceRecognitionThreshold" type="digit" />
          </view>
          <view class="field-row">
            <text class="field-label">摄像头旋转角度</text>
            <view class="field-select" @click="openEditor('cameraRotation')">{{ form.cameraRotation }}度</view>
          </view>
          <view class="field-row">
            <text class="field-label">指纹识别</text>
            <input class="field-input wide readonly" value="待外接员工级指纹模块/SDK" disabled />
          </view>
          <view class="field-row">
            <text class="field-label">指纹识别阈值</text>
            <input class="field-input readonly" :value="form.fingerRecognitionThreshold || ''" placeholder="未接入" disabled />
          </view>
          <view class="field-row">
            <text class="field-label">Token验证</text>
            <input class="field-input wide readonly" value="V4.1 固定启用，不允许关闭" disabled />
          </view>
        </view>

        <view class="section status-section">
          <view class="section-title">运行状态</view>
          <view class="status-row">
            <text>设备授权</text>
            <text :class="statusClass(runtime.deviceAuthorization?.state)">{{ runtime.deviceAuthorization?.message || '状态未知' }}</text>
          </view>
          <view class="status-row">
            <text>识别引擎</text>
            <text :class="statusClass(runtime.recognitionEngine?.state)">{{ runtime.recognitionEngine?.message || '状态未知' }}</text>
          </view>
          <view class="status-row">
            <text>后端通信</text>
            <text :class="statusClass(runtime.socket?.state)">{{ runtime.socket?.message || '状态未知' }}</text>
          </view>
        </view>
      </scroll-view>

      <view class="panel-actions">
        <button class="button secondary" @click="goBack">取消</button>
        <button class="button primary" :disabled="saving" @click="save">{{ saving ? '保存中' : '确定' }}</button>
      </view>
    </view>

    <ModalShell v-if="editor.visible" closable close-on-mask @close="editor.visible=false">
      <view class="editor-card">
        <text class="editor-title">{{ editor.title }}</text>
        <view class="editor-options">
          <view v-for="option in editor.options" :key="option.value" class="editor-option"
            :class="{selected:String(editor.value)===String(option.value)}" @click="editor.value=option.value">
            {{ option.label }}
          </view>
        </view>
        <button class="button primary editor-save" @click="applyEditor">确定</button>
      </view>
    </ModalShell>
  </view>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import ModalShell from '@/components/ModalShell.vue'
import { appState } from '@/state/appState.js'
import { services } from '@/services/index.js'

const form = reactive({ ...appState.settings })
const runtime = reactive(JSON.parse(JSON.stringify(appState.runtime)))
const saving = ref(false)
const editor = reactive({ visible: false, key: '', title: '', value: '', options: [] })

const descriptors = {
  backendTransport: { title: '后端实时通信方式', options: [
    { value: 'MQTT', label: 'MQTT（推荐，支持实时下行）' },
    { value: 'HTTP', label: 'HTTP（仅登录、心跳和上报）' },
    { value: 'TCP', label: 'TCP（旧版兼容）' }
  ] },
  httpScheme: { title: 'HTTP协议', options: [
    { value: 'https', label: 'HTTPS' }, { value: 'http', label: 'HTTP' }
  ] },
  mqttScheme: { title: 'MQTT协议', options: [
    { value: 'ssl', label: 'SSL / MQTTS' }, { value: 'tcp', label: 'TCP / MQTT' }
  ] },
  cameraRotation: { title: '摄像头旋转角度', options: [0, 90, 180, 270].map(value => ({ value, label: `${value}度` })) }
}

const transportLabel = computed(() => ({ MQTT: 'MQTT', HTTP: 'HTTP', TCP: 'TCP（兼容）' }[String(form.backendTransport || '').toUpperCase()] || '请选择'))

onMounted(async () => {
  const results = await Promise.allSettled([services.loadSettings(), services.getRuntime()])
  if (results[0].status === 'fulfilled') Object.assign(form, results[0].value || {})
  if (results[1].status === 'fulfilled') Object.assign(runtime, results[1].value || {})
})

const openEditor = (key) => {
  const descriptor = descriptors[key]
  if (!descriptor) return
  Object.assign(editor, { visible: true, key, title: descriptor.title, value: form[key], options: descriptor.options })
}

const applyEditor = () => {
  form[editor.key] = editor.value
  editor.visible = false
}

const validPort = (value) => Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 65535
const validate = () => {
  if (!String(form.serialPort || '').trim()) return '串口设备不能为空'
  if (!Number.isInteger(Number(form.baudRate)) || Number(form.baudRate) < 1) return '波特率必须为正整数'
  if (!String(form.httpServerAddress || '').trim()) return 'HTTP域名/IP不能为空'
  if (!['http', 'https'].includes(String(form.httpScheme || '').toLowerCase())) return '请选择HTTP协议'
  if (!validPort(form.httpPort)) return 'HTTP端口必须是1～65535之间的整数'

  const mode = String(form.backendTransport || '').toUpperCase()
  if (!['MQTT', 'HTTP', 'TCP'].includes(mode)) return '请选择后端通信方式'
  if (mode === 'MQTT') {
    if (!String(form.mqttServerAddress || '').trim()) return 'MQTT域名/IP不能为空'
    if (!['tcp', 'ssl'].includes(String(form.mqttScheme || '').toLowerCase())) return '请选择MQTT协议'
    if (!validPort(form.mqttPort)) return 'MQTT端口必须是1～65535之间的整数'
  }
  if (mode === 'TCP') {
    if (!String(form.tcpServerAddress || '').trim()) return 'TCP域名/IP不能为空'
    if (!validPort(form.tcpPort)) return 'TCP端口必须是1～65535之间的整数'
  }

  const total = Number(form.totalCount)
  const group = Number(form.singleGroupCount)
  const interval = Number(form.serialPollingIntervalMs)
  const threshold = Number(form.faceRecognitionThreshold)
  if (!Number.isInteger(total) || total < 1 || total > 255) return '卡位总数必须为1～255之间的整数'
  if (!Number.isInteger(group) || group < 1 || group > total) return '分组大小必须为1～卡位总数之间的整数'
  if (!Number.isInteger(interval) || interval < 100) return '轮询间隔不能小于100ms'
  if (!Number.isFinite(threshold) || threshold < 0.6 || threshold > 1) return '人脸识别阈值必须在0.6～1之间'
  return ''
}

const save = async () => {
  const error = validate()
  if (error) { uni.showToast({ title: error, icon: 'none' }); return }
  saving.value = true
  try {
    const payload = {
      ...form,
      baudRate: String(Number(form.baudRate)),
      httpPort: Number(form.httpPort),
      mqttPort: Number(form.mqttPort),
      tcpPort: Number(form.tcpPort),
      totalCount: Number(form.totalCount),
      singleGroupCount: Number(form.singleGroupCount),
      serialPollingIntervalMs: Number(form.serialPollingIntervalMs),
      faceRecognitionThreshold: Number(form.faceRecognitionThreshold),
      serialPollingEnabled: false,
      cardNumberMode: 'VISIBLE',
      cardParseMode: '转可见符'
    }
    const saved = await services.saveSettings(payload)
    Object.assign(form, saved || payload)
    uni.showToast({ title: '保存成功', icon: 'success' })
    setTimeout(goHome, 450)
  } catch (error) {
    uni.showToast({ title: error.message || '保存失败', icon: 'none' })
  } finally {
    saving.value = false
  }
}

const statusClass = (state) => {
  if (['AUTHORIZED', 'ACTIVE', 'AUTHENTICATED', 'READY'].includes(state)) return 'status-success'
  if (['CONNECTING', 'CHECKING', 'AUTHORIZING', 'LOGIN_SENT', 'SYNCING'].includes(state)) return 'status-warning'
  if (['UNAUTHORIZED', 'ERROR', 'AUTH_FAILED', 'AUTH_TIMEOUT', 'EXPIRED'].includes(state)) return 'status-error'
  return 'status-muted'
}

const goHome = () => uni.reLaunch({ url: '/pages/index/index' })
const goBack = () => {
  const pages = getCurrentPages()
  if (pages.length > 1) uni.navigateBack()
  else uni.reLaunch({ url: appState.settings.initialized ? '/pages/index/index' : '/pages/splash/splash' })
}
</script>

<style scoped>
.settings-page{width:100%;height:100%;min-height:100vh;background:#f3f5f9;color:#30343b;display:flex;align-items:center;justify-content:center;padding:clamp(10px,2vw,24px);box-sizing:border-box}.settings-panel{width:min(760px,100%);height:min(1120px,calc(100vh - 20px));background:#fff;border-radius:10px;box-shadow:0 10px 32px rgba(20,35,65,.14);display:flex;flex-direction:column;overflow:hidden}.panel-header{height:58px;padding:0 18px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #e4e8ef;flex:0 0 auto}.panel-title{font-size:20px;font-weight:600}.close-button{font-size:30px;line-height:1;color:#a1a7b2;padding:8px;cursor:pointer}.settings-scroll{flex:1;height:0}.section{padding:18px 24px 20px;border-bottom:1px solid #dde2ea}.section-title{font-size:16px;font-weight:600;margin-bottom:14px;display:flex;align-items:center;gap:10px}.section-title:after{content:'';height:1px;background:#dfe4ec;flex:1}.section-help{display:block;margin:-5px 0 14px 114px;color:#858c98;font-size:13px;line-height:1.5}.section-help.warning{color:#c67b20}.field-row{display:flex;align-items:center;min-height:48px;margin:7px 0;gap:14px}.field-label{width:104px;text-align:right;color:#60656f;font-weight:600;font-size:14px;flex:0 0 auto}.field-label.required:before{content:'*';color:#f04444;margin-right:5px}.field-input,.field-select{width:200px;height:38px;border:1px solid #d7dde7;border-radius:5px;background:#fff;color:#555d68;padding:0 13px;box-sizing:border-box;font-size:14px;display:flex;align-items:center}.field-input.wide,.field-select{width:min(460px,calc(100% - 118px))}.field-input.readonly{background:#f4f6f9;color:#9298a3}.field-select{cursor:pointer;position:relative}.field-select:after{content:'⌄';margin-left:auto;color:#a7adba}.status-section{padding-bottom:28px}.status-row{display:flex;justify-content:space-between;align-items:center;padding:8px 0 8px 118px;font-size:14px}.status-success{color:#20a36a}.status-warning{color:#c98218}.status-error{color:#e14747}.status-muted{color:#9298a3}.panel-actions{height:68px;display:flex;justify-content:flex-end;align-items:center;gap:10px;padding:0 20px;border-top:1px solid #e3e7ee;flex:0 0 auto;background:#fff}.button{height:38px;min-width:82px;border-radius:5px;font-size:14px;line-height:38px;padding:0 18px;margin:0}.button:after{border:0}.button.primary{background:#2878ff;color:#fff}.button.secondary{background:#fff;color:#646b76;border:1px solid #d8dde6}.button[disabled]{opacity:.55}.editor-card{width:min(420px,82vw);background:#fff;border-radius:10px;padding:22px}.editor-title{display:block;font-size:18px;font-weight:600;margin-bottom:14px}.editor-options{border:1px solid #e0e4eb;border-radius:6px;overflow:hidden}.editor-option{padding:13px 15px;border-bottom:1px solid #eef0f4}.editor-option:last-child{border-bottom:0}.editor-option.selected{background:#eef5ff;color:#2878ff}.editor-save{margin:18px 0 0 auto;display:block}@media(max-width:600px){.settings-page{padding:0}.settings-panel{height:100vh;border-radius:0}.section{padding-left:14px;padding-right:14px}.field-label{width:96px}.field-input.wide,.field-select{width:calc(100% - 110px)}.section-help{margin-left:110px}.status-row{padding-left:110px}}
</style>
