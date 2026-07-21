<template>
  <view class="page-root config-page">
    <view class="config-scroll">
      <view class="config-content">
        <view class="brand-header">
          <view class="logo-box"><AppLogo /></view>
          <view class="brand-copy">
            <text class="brand-title">工作卡柜</text>
            <text class="brand-subtitle">卡柜号：{{ form.cabinetNumber }}</text>
          </view>
        </view>

        <view class="four-row first-row">
          <input class="dark-box short-box" v-model="form.serialPort" />
          <input class="dark-box" v-model="form.serialExtra" />
          <input class="dark-box short-box" v-model="form.baudRate" type="number" />
          <input class="dark-box" v-model="form.baudExtra" />
        </view>

        <view class="four-row">
          <view class="dark-box label-box">单组数量</view>
          <input class="dark-box" v-model="form.singleGroupCount" type="number" />
          <view class="dark-box label-box">总体数量</view>
          <input class="dark-box" v-model="form.totalCount" type="number" />
        </view>

        <view class="parse-row">
          <view class="plain-setting clickable" @click="openEditor('cardParseMode')">卡号解析：{{ form.cardParseMode }}</view>
          <view class="polling-setting">
            <text>轮询方式：单组轮询</text>
            <UiSwitch v-model="form.singleGroupPollingEnabled" />
          </view>
        </view>

        <view class="full-row">
          <view class="dark-box label-box">设备ID</view>
          <input class="dark-box" v-model="form.deviceId" />
        </view>
        <view class="full-row">
          <view class="dark-box label-box">设备编码</view>
          <input class="dark-box" v-model="form.deviceCode" placeholder="注册后自动写入" />
        </view>
        <view class="full-row">
          <view class="dark-box label-box">激活码</view>
          <input class="dark-box" v-model="form.activationCode" />
        </view>
        <view class="full-row server-row">
          <view class="dark-box label-box">API地址</view>
          <input class="dark-box" v-model="form.apiBaseUrl" />
        </view>
        <view class="full-row server-row">
          <view class="dark-box label-box">服务器IP</view>
          <input class="dark-box" v-model="form.serverAddress" />
        </view>
        <text class="server-help">*API地址用于HTTP注册激活，测试环境：http://card-test.quyohui.com</text>

        <view class="four-row port-row">
          <view class="dark-box label-box no-wrap">通信方式</view>
          <view class="dark-box clickable" @click="openEditor('backendTransport')">{{ form.backendTransport }}</view>
          <view class="dark-box label-box no-wrap">MQTT端口</view>
          <input class="dark-box" v-model="form.mqttPort" type="number" />
        </view>

        <view class="four-row port-row">
          <view class="dark-box label-box no-wrap">TCP端口</view>
          <input class="dark-box" v-model="form.tcpPort" type="number" />
          <view class="dark-box label-box no-wrap">HTTP端口</view>
          <input class="dark-box" v-model="form.httpPort" type="number" />
        </view>

        <view class="function-area">
          <view class="full-param clickable" @click="openEditor('faceRecognitionThreshold')">人脸识别阈值：{{ form.faceRecognitionThreshold }}</view>
          <view v-for="row in parameterRows" :key="row.left.key" class="param-pair">
            <view class="param-left clickable" @click="openEditor(row.left.key)">{{ row.left.label }}：{{ row.left.value }}</view>
            <view class="param-right">
              <text>{{ row.right.label }}</text>
              <UiSwitch v-model="form[row.right.key]" />
            </view>
          </view>
        </view>

        <view class="four-row protocol-row">
          <view class="dark-box label-box">起始符</view>
          <input class="dark-box white-placeholder" v-model="form.startCharacter" placeholder="请输入起始符" placeholder-style="color:#FFFFFF" />
          <view class="dark-box label-box">结尾符</view>
          <input class="dark-box white-placeholder" v-model="form.endCharacter" placeholder="请输入结尾符" placeholder-style="color:#FFFFFF" />
        </view>

        <view class="status-row">
          <view class="dark-box status-label">设备授权</view>
          <view class="status-value" :class="statusClass(runtime.deviceAuthorization?.state)">{{ runtime.deviceAuthorization?.message || '状态未知' }}</view>
        </view>
        <view class="status-row">
          <view class="dark-box status-label">识别引擎</view>
          <view class="status-value" :class="statusClass(runtime.recognitionEngine?.state)">{{ runtime.recognitionEngine?.message || '状态未知' }}</view>
        </view>

        <view class="actions">
          <button class="white-action-button back" @click="goBack">返回</button>
          <button class="white-action-button save" :disabled="saving" @click="save">{{ saving ? '保存中' : '保存修改' }}</button>
        </view>
      </view>
    </view>

    <ModalShell v-if="editor.visible" closable close-on-mask @close="editor.visible=false">
      <view class="editor-card">
        <text class="editor-title">{{ editor.title }}</text>
        <input v-if="editor.type==='number'" class="editor-input" v-model="editor.value" type="digit" focus />
        <view v-else class="editor-options">
          <view v-for="option in editor.options" :key="option" class="editor-option" :class="{selected:String(editor.value)===String(option)}" @click="editor.value=option">{{ option }}</view>
        </view>
        <button class="primary-gradient-button editor-save" @click="applyEditor">确定</button>
      </view>
    </ModalShell>
  </view>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AppLogo from '@/components/AppLogo.vue'
import UiSwitch from '@/components/UiSwitch.vue'
import ModalShell from '@/components/ModalShell.vue'
import { appState } from '@/state/appState.js'
import { services } from '@/services/index.js'

const form=reactive({ ...appState.settings })
const runtime=reactive(JSON.parse(JSON.stringify(appState.runtime)))
const saving=ref(false)
const editor=reactive({visible:false,key:'',title:'',type:'number',value:'',options:[]})
const descriptors={
  faceRecognitionThreshold:{title:'人脸识别阈值',type:'number'},
  cameraRotation:{title:'镜头旋转角度',type:'options',options:[0,90,180,270]},
  codeValueType:{title:'Code值类型',type:'options',options:['字符','十六进制','十进制']},
  cardSuccessResponseType:{title:'取卡成功响应',type:'options',options:['短链接','完整响应','不响应']},
  toastDisplay:{title:'Toast提示框',type:'options',options:['显示','隐藏']},
  boardUpgradeIntervalMs:{title:'单板升级时间间隔（毫秒）',type:'number'},
  cardParseMode:{title:'卡号解析方式',type:'options',options:['转可见符','十六进制','原始字符']},
  backendTransport:{title:'后端通信方式',type:'options',options:['MQTT','TCP']}
}

const parameterRows=computed(()=>[
  {left:{key:'cameraRotation',label:'镜头旋转角度',value:form.cameraRotation},right:{key:'ignoreTokenFetch',label:'是否忽略Token获取'}},
  {left:{key:'codeValueType',label:'Code值类型',value:form.codeValueType},right:{key:'faceRegistrationResponseEnabled',label:'人脸注册：有响应'}},
  {left:{key:'cardSuccessResponseType',label:'取卡成功响应',value:form.cardSuccessResponseType},right:{key:'tcpDoorCommandResponseEnabled',label:'TCP卡门指令响应：有响应'}},
  {left:{key:'toastDisplay',label:'Toast提示框',value:form.toastDisplay},right:{key:'secondaryDoorEnabled',label:'二级门：未启用'}},
  {left:{key:'boardUpgradeIntervalMs',label:'单板升级时间间隔',value:`${form.boardUpgradeIntervalMs}毫秒`},right:{key:'usbCardReaderEnabled',label:'USB读卡：未启用'}}
])

onMounted(async()=>{
  const [settings,status]=await Promise.all([services.loadSettings(),services.getRuntime()])
  Object.assign(form,settings||{})
  Object.assign(runtime,status||{})
})

const openEditor=(key)=>{
  const desc=descriptors[key]
  editor.visible=true;editor.key=key;editor.title=desc.title;editor.type=desc.type;editor.options=desc.options||[];editor.value=form[key]
}
const applyEditor=()=>{
  const numberKeys=['faceRecognitionThreshold','cameraRotation','boardUpgradeIntervalMs']
  form[editor.key]=numberKeys.includes(editor.key)?Number(editor.value):editor.value
  editor.visible=false
}
const statusClass=(state)=>{
  if(['AUTHORIZED','ACTIVE'].includes(state)) return 'success'
  if(['UNAUTHORIZED','ERROR','CODE_IN_USE','CODE_INVALID','EXPIRED'].includes(state)) return 'error'
  if(['CHECKING','AUTHORIZING'].includes(state)) return 'warning'
  return 'unknown'
}
const validate=()=>{
  if(!String(form.deviceId).trim()) return '设备ID不能为空'
  if(!String(form.apiBaseUrl||form.serverAddress).trim()) return 'API地址不能为空'
  if(!String(form.activationCode).trim()) return '激活码不能为空'
  const mqtt=Number(form.mqttPort),tcp=Number(form.tcpPort),http=Number(form.httpPort),single=Number(form.singleGroupCount),total=Number(form.totalCount),threshold=Number(form.faceRecognitionThreshold)
  if(!['MQTT','TCP'].includes(String(form.backendTransport).toUpperCase())) return '后端通信方式必须是 MQTT 或 TCP'
  if(!Number.isInteger(mqtt)||mqtt<1||mqtt>65535) return 'MQTT端口必须是1～65535之间的整数'
  if(!Number.isInteger(tcp)||tcp<1||tcp>65535) return 'TCP端口必须是1～65535之间的整数'
  if(!Number.isInteger(http)||http<1||http>65535) return 'HTTP端口必须是1～65535之间的整数'
  if(!Number.isInteger(single)||single<1||!Number.isInteger(total)||total<1) return '卡位数量必须为正整数'
  if(single>total) return '单组数量不能大于总体数量'
  if(Number.isNaN(threshold)||threshold<0||threshold>1) return '人脸识别阈值必须在0～1之间'
  return ''
}
const save=async()=>{
  const error=validate();if(error){uni.showToast({title:error,icon:'none'});return}
  saving.value=true
  try{await services.saveSettings({...form});Object.assign(appState.settings,form,{initialized:true});uni.showToast({title:'保存成功',icon:'success'});setTimeout(goHome,450)}catch(error){uni.showToast({title:error.message||'保存失败',icon:'none'})}finally{saving.value=false}
}
const goHome=()=>{
  if(typeof window!=='undefined'&&window.location){
    window.location.replace('/index.html#/pages/index/index')
    return
  }
  uni.reLaunch({url:'/pages/index/index'})
}
const goBack=()=>{
  const pages=getCurrentPages();if(pages.length>1) uni.navigateBack();else uni.reLaunch({url:appState.settings.initialized?'/pages/index/index':'/pages/splash/splash'})
}
</script>

<style scoped>
.config-page{width:100%;height:100%;min-height:0;background:#1f76ff;color:#fff;overflow:hidden}.config-scroll{width:100%;height:0;min-height:0;flex:1 1 auto;overflow-y:auto;overflow-x:hidden;overscroll-behavior-y:contain;-webkit-overflow-scrolling:touch}.config-content{width:100%;max-width:1200px;margin:0 auto;padding:clamp(12px,1.5vw,22px);padding-bottom:max(48px,calc(env(safe-area-inset-bottom) + 26px));box-sizing:border-box}.brand-header{height:clamp(90px,11vh,126px);display:flex;align-items:flex-start;padding:clamp(7px,1vw,14px) clamp(12px,1.5vw,20px);box-sizing:border-box}.logo-box{width:clamp(72px,10vw,104px);height:clamp(48px,6.5vw,68px)}.brand-copy{display:flex;flex-direction:column;margin-left:clamp(14px,2vw,24px)}.brand-title{font-size:clamp(24px,3.4vw,38px);font-weight:500;line-height:1.1}.brand-subtitle{font-size:clamp(12px,1.6vw,18px);margin-top:7px}.four-row{display:grid;grid-template-columns:minmax(82px,126px) minmax(0,1fr) minmax(82px,126px) minmax(0,1fr);gap:clamp(5px,.8vw,12px);margin-bottom:clamp(9px,1.2vh,15px)}.first-row{margin-top:0}.dark-box{height:clamp(44px,5.8vh,60px);border:0;border-radius:clamp(8px,1vw,12px);background:#0a53c4;color:#fff;padding:0 clamp(11px,1.4vw,16px);font-size:clamp(13px,1.7vw,19px);display:flex;align-items:center;min-width:0}.label-box{justify-content:center;text-align:center;white-space:nowrap}.short-box{font-weight:500}.full-row,.status-row{display:grid;grid-template-columns:minmax(82px,126px) minmax(0,1fr);gap:clamp(5px,.8vw,12px);margin-bottom:clamp(9px,1.2vh,15px)}.parse-row{display:grid;grid-template-columns:1fr 1fr;gap:clamp(24px,4vw,70px);padding:clamp(8px,1.2vh,15px) clamp(18px,3vw,38px);margin-bottom:clamp(7px,1vh,13px);font-size:clamp(14px,1.8vw,20px)}.plain-setting,.polling-setting{min-height:34px;display:flex;align-items:center}.polling-setting{justify-content:space-between;gap:14px}.clickable{cursor:pointer}.server-row{margin-bottom:0}.server-help{display:block;margin-left:calc(min(126px,16vw) + clamp(5px,.8vw,12px));margin-top:clamp(5px,.8vh,9px);margin-bottom:clamp(16px,2vh,24px);font-size:clamp(10px,1.35vw,15px);color:#0755b8;white-space:normal;overflow-wrap:anywhere}.port-row{margin-bottom:clamp(18px,2.5vh,30px)}.no-wrap{white-space:nowrap}.function-area{font-size:clamp(14px,1.8vw,20px);margin-bottom:clamp(18px,2.4vh,30px)}.full-param{height:clamp(38px,4.7vh,52px);display:flex;align-items:center;padding-left:clamp(15px,2vw,24px)}.param-pair{display:grid;grid-template-columns:1fr 1fr;gap:clamp(24px,4vw,70px);min-height:clamp(42px,5.5vh,60px);align-items:center;padding:0 clamp(15px,2vw,24px)}.param-left,.param-right{display:flex;align-items:center;min-width:0}.param-right{justify-content:space-between;gap:14px}.protocol-row{margin-bottom:clamp(9px,1.2vh,15px)}.white-placeholder{color:#fff}.status-label{justify-content:center}.status-value{height:clamp(44px,5.8vh,60px);border-radius:clamp(8px,1vw,12px);padding:0 clamp(12px,1.5vw,18px);display:flex;align-items:center;font-size:clamp(13px,1.7vw,19px);color:#fff}.status-value.success{background:#05b63f}.status-value.error{background:#ef1010}.status-value.warning{background:#ff9829}.status-value.unknown{background:#8a98aa}.actions{display:flex;justify-content:center;gap:clamp(28px,6vw,72px);padding:clamp(42px,5.5vh,72px) 0 clamp(12px,2vh,24px)}.actions .white-action-button{height:clamp(62px,8vh,86px)}.actions .back{width:clamp(145px,20vw,205px)}.actions .save{width:clamp(195px,28vw,275px)}.editor-card{padding:clamp(34px,5vw,58px);display:flex;flex-direction:column}.editor-title{font-size:clamp(22px,3vw,34px);font-weight:600;text-align:center}.editor-input{height:58px;margin-top:28px;background:#f1f5fb;border-radius:12px;padding:0 18px;font-size:22px}.editor-options{display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin-top:28px}.editor-option{padding:16px 12px;border-radius:10px;background:#f1f5fb;text-align:center}.editor-option.selected{background:#1f76ff;color:#fff}.editor-save{height:58px;margin-top:28px}
@media(max-width:560px){.four-row{grid-template-columns:82px minmax(0,1fr) 88px minmax(0,1fr)}.server-help{margin-left:87px;font-size:10px}.parse-row,.param-pair{gap:14px;padding-left:10px;padding-right:10px}.brand-header{height:88px}.actions{gap:18px}}
</style>
