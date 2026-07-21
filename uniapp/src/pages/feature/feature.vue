<template>
  <view class="page-root feature-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="feature-panel">
        <template v-if="type==='password'">
          <view class="feature-icon yellow"><IconGlyph name="lock" /></view><text class="feature-title">修改密码</text>
          <view class="form-narrow">
            <picker :range="roleOptions" range-key="label" :value="roleIndex" @change="roleIndex=Number($event.detail.value)"><view class="large-input picker-input">修改对象：{{ roleOptions[roleIndex].label }}</view></picker>
            <input class="large-input" v-model="passwordForm.password" type="number" maxlength="6" password placeholder="请输入6位新密码" />
            <input class="large-input" v-model="passwordForm.confirm" type="number" maxlength="6" password placeholder="请再次输入新密码" />
            <button class="primary-gradient-button feature-button" @click="savePassword">保存密码</button>
          </view>
        </template>

        <template v-else-if="type==='restart'">
          <view class="feature-icon cyan"><IconGlyph name="refresh" /></view><text class="feature-title">重启应用</text>
          <text class="description">重启只影响WebView与应用界面。后续原生常驻设备服务、串口及长连接按服务生命周期管理。</text>
          <button class="danger-button" @click="restart">确认重启应用</button>
        </template>

        <template v-else-if="type==='authorization'">
          <view class="feature-icon blue"><IconGlyph name="shield" /></view><text class="feature-title">系统授权</text>
          <view class="status-card success"><text>设备授权状态</text><b>{{ appState.runtime.deviceAuthorization.message }}</b></view>
          <view class="status-card info"><text>设备ID</text><b>{{ appState.settings.deviceId }}</b></view>
          <button class="primary-gradient-button feature-button spaced-action" @click="simulateAuthorization">重新检测授权</button>
        </template>

        <template v-else-if="type==='engine'">
          <view class="feature-icon purple"><IconGlyph name="engine" /></view><text class="feature-title">激活识别引擎</text>
          <view class="status-card engine-status" :class="appState.runtime.recognitionEngine.state==='ACTIVE'?'success':'error'"><text>当前状态</text><b>{{ appState.runtime.recognitionEngine.message || '状态未知' }}</b></view>
          <view v-if="appState.runtime.recognitionEngine.lastCode" class="status-detail"><text>错误码</text><b>{{ appState.runtime.recognitionEngine.lastCode }}</b></view>
          <button class="primary-gradient-button feature-button spaced-action" :disabled="engineActivating" @click="activateEngine">{{ engineActivating ? '正在重新激活...' : '重新激活引擎' }}</button>
        </template>

        <template v-else-if="type==='units'">
          <view class="feature-icon violet"><IconGlyph name="device" /></view><text class="feature-title">单元管理</text>
          <view class="unit-grid">
            <view v-for="unit in units" :key="unit.id" class="unit-card"><view class="unit-head"><text>{{ unit.name }}</text><text :class="unit.online?'online':'offline'">{{ unit.online?'在线':'离线' }}</text></view><view class="unit-details"><text>地址：{{ unit.address }}</text><text>卡位：{{ unit.range }}</text><text>版本：{{ unit.version }}</text></view></view>
          </view>
        </template>

        <template v-else-if="type==='history'">
          <view class="feature-icon orange"><IconGlyph name="history" /></view><text class="feature-title">历史管理</text>
          <view class="history-list"><view v-for="item in history" :key="item.id" class="history-item"><view><b>{{ item.type }}</b><text>{{ item.employeeName }} · {{ item.slotNumber }}号卡门</text></view><view class="history-right"><text :class="item.result==='成功'?'ok':'failed'">{{ item.result }}</text><text>{{ item.createdAt }}</text></view></view></view>
        </template>

        <template v-else-if="type==='parameters'">
          <view class="feature-icon green"><IconGlyph name="sliders" /></view><text class="feature-title">参数设置</text>
          <view class="parameter-form">
            <view class="parameter-row"><text>人脸识别阈值</text><input v-model="parameters.faceRecognitionThreshold" type="digit" /></view>
            <view class="parameter-row"><text>镜头旋转角度</text><picker :range="[0,90,180,270]" @change="parameters.cameraRotation=[0,90,180,270][Number($event.detail.value)]"><view class="parameter-value">{{ parameters.cameraRotation }}°</view></picker></view>
            <view class="parameter-row"><text>单板升级间隔</text><input v-model="parameters.boardUpgradeIntervalMs" type="number" /><text>毫秒</text></view>
            <view class="parameter-row"><text>忽略Token获取</text><UiSwitch v-model="parameters.ignoreTokenFetch" /></view>
            <view class="parameter-row"><text>USB读卡</text><UiSwitch v-model="parameters.usbCardReaderEnabled" /></view>
            <button class="primary-gradient-button feature-button" @click="saveParameters">保存参数</button>
          </view>
        </template>

        <template v-else-if="type==='command'">
          <view class="feature-icon navy"><IconGlyph name="device" /></view><text class="feature-title">指令验证</text>
          <view class="form-narrow command-form"><textarea class="command-input" v-model="command" placeholder="输入待发送的十六进制或文本指令"></textarea><view class="serial-state" :class="serialStatus.state==='CONNECTED'?'connected':'disconnected'">{{ serialStatus.message || '正在读取串口状态' }}</view><button class="primary-gradient-button feature-button" @click="validateCommand">发送到串口</button><button class="serial-reconnect" @click="reconnectSerial">重新连接串口</button><view class="command-result">{{ commandResult || '等待输入指令' }}</view></view>
        </template>
      </view>
      <view class="back-wrap"><BackButton @click="back" /></view>
    </scroll-view>
  </view>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AdminHeader from '@/components/AdminHeader.vue';import BackButton from '@/components/BackButton.vue';import IconGlyph from '@/components/IconGlyph.vue';import UiSwitch from '@/components/UiSwitch.vue'
import { appState } from '@/state/appState.js';import { ROLE,ROLE_META } from '@/constants/app.js';import { services } from '@/services/index.js'
const type=ref('history');onLoad(options=>type.value=options.type||'history')
const roleLabel=computed(()=>ROLE_META[appState.session?.role]?.label||'')
const roleOptions=[{value:ROLE.SYSTEM_ADMIN,label:'系统管理员'},{value:ROLE.OPS,label:'运维人员'},{value:ROLE.DEVELOPER,label:'开发人员'}];const roleIndex=ref(0);const passwordForm=reactive({password:'',confirm:''});const engineActivating=ref(false);const history=ref([]);const parameters=reactive({...appState.settings});const command=ref('');const commandResult=ref('');const serialStatus=reactive({...appState.runtime.serial})
const units=Array.from({length:10},(_,i)=>({id:i+1,name:`单板 ${String(i+1).padStart(2,'0')}`,address:`0x${(i+1).toString(16).padStart(2,'0').toUpperCase()}`,range:`${i*10+1}-${i*10+10}`,version:'V2.1',online:i!==7}))
onMounted(async()=>{if(type.value==='history')history.value=await services.getHistory();if(type.value==='command')Object.assign(serialStatus,await services.getSerialStatus());if(type.value==='engine')await services.getRuntime()})
const savePassword=async()=>{if(passwordForm.password.length!==6||passwordForm.password!==passwordForm.confirm){uni.showToast({title:'两次输入的6位密码不一致',icon:'none'});return}try{await services.savePassword(roleOptions[roleIndex.value].value,passwordForm.password);uni.showToast({title:'保存成功',icon:'success'});passwordForm.password='';passwordForm.confirm=''}catch(e){uni.showToast({title:e.message||'保存失败',icon:'none'})}}
const restart=async()=>{await services.restartApp();uni.showToast({title:'已发送模拟重启指令',icon:'none'})}
const simulateAuthorization=()=>{appState.runtime.deviceAuthorization={state:'AUTHORIZED',message:'已授权'};uni.showToast({title:'授权状态已刷新',icon:'success'})}
const activateEngine=async()=>{engineActivating.value=true;appState.runtime.recognitionEngine={...appState.runtime.recognitionEngine,state:'ACTIVATING',message:'正在重新激活虹软 ArcFace'};try{await services.reactivateFaceEngine();await services.getRuntime();setTimeout(()=>services.getRuntime().catch(()=>{}),1500);uni.showToast({title:'已发送重新激活指令',icon:'none'})}catch(e){uni.showToast({title:e.message||'重新激活失败',icon:'none'})}finally{engineActivating.value=false}}
const saveParameters=async()=>{await services.saveSettings({...appState.settings,...parameters});Object.assign(appState.settings,parameters);uni.showToast({title:'参数已保存',icon:'success'})}
const validateCommand=async()=>{const input=command.value.trim();if(!input){uni.showToast({title:'请输入指令',icon:'none'});return}const hex=input.replace(/[^0-9a-f]/ig,'');const isHex=hex.length>0&&hex.length===input.replace(/\s/g,'').length&&hex.length%2===0;try{const result=await services.sendSerial(isHex?hex:input,isHex?'HEX':'TEXT');commandResult.value=`[${isHex?'HEX':'TEXT'}] 已发送 ${result.bytes||0} 字节${result.hex?`：${result.hex}`:''}`;Object.assign(serialStatus,await services.getSerialStatus())}catch(error){commandResult.value=`发送失败：${error.message||'未知错误'}`;Object.assign(serialStatus,await services.getSerialStatus())}}
const reconnectSerial=async()=>{try{Object.assign(serialStatus,await services.reconnectSerial())}catch(error){uni.showToast({title:error.message||'重连失败',icon:'none'})}}
const back=()=>uni.navigateBack();const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.feature-page { background: #e6f0ff; }
.feature-panel { width: min(94%, 880px); min-height: clamp(500px, 70vh, 800px); margin: clamp(22px, 3.2vh, 42px) auto 0; background: #fff; border-radius: clamp(15px, 2vw, 23px); padding: clamp(30px, 4.5vh, 56px) clamp(26px, 4vw, 64px); display: flex; flex-direction: column; align-items: center; }
.feature-icon { width: clamp(68px, 9.5vw, 100px); height: clamp(68px, 9.5vw, 100px); border-radius: 50%; color: #fff; padding: clamp(16px, 2.2vw, 24px); }
.yellow{background:#ffc95b}.cyan{background:#37b8d7}.blue{background:#2f67a8}.purple{background:#db61e8}.violet{background:#5e55dd}.orange{background:#ff8f31}.green{background:#16db3b}.navy{background:#586a9d}
.feature-title { font-size: clamp(20px, 2.7vw, 26px); margin-top: 18px; font-weight: 500; }
.description { max-width: 650px; font-size: 15px; line-height: 1.7; color: #65758b; text-align: center; margin-top: 28px; }
.form-narrow, .parameter-form { width: min(100%, 600px); margin-top: 30px; display: flex; flex-direction: column; gap: 15px; }
.large-input { height: clamp(50px, 6.4vh, 66px); background: #f4f7fb; border-radius: 12px; padding: 0 16px; font-size: 16px; display: flex; align-items: center; }
.picker-input { color: #39495e; }
.feature-button { height: clamp(50px, 6.4vh, 66px); width: 100%; }
.feature-panel > .feature-button { width: min(100%, 650px); }
.spaced-action { margin-top: 18px; }
.danger-button { width: min(100%, 520px); height: 54px; margin-top: 32px; border-radius: 12px; background: #ef4053; color: #fff; font-size: 16px; font-weight:600; line-height:1; display:flex; align-items:center; justify-content:center; }
.status-card { width: min(100%, 650px); min-height: 68px; border-radius: 12px; margin-top: 18px; padding: 14px 18px; display: flex; justify-content: space-between; align-items: center; gap:14px; color: #fff; font-size: 16px; box-sizing:border-box; }
.status-card text { flex:0 0 auto; white-space:nowrap; font-weight:600; }
.status-card b { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right; }
.status-card.engine-status { align-items:flex-start; }
.status-card.engine-status b { overflow:visible; text-overflow:clip; white-space:normal; text-align:left; overflow-wrap:anywhere; line-height:1.45; }
.status-card.success{background:#05b63f}.status-card.error{background:#ef1010}.status-card.info{background:#1f76ff}
.status-detail { width:min(100%,650px); min-height:48px; margin-top:12px; border-radius:12px; background:#f4f7fb; color:#42556d; display:flex; align-items:center; justify-content:space-between; gap:16px; padding:0 18px; box-sizing:border-box; font-size:15px; }
.status-detail b { color:#ef1010; font-weight:600; overflow-wrap:anywhere; }
.unit-grid { width: 100%; display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin-top: 26px; }
.unit-card { background: #f4f7fb; border-radius: 12px; padding: 16px; }
.unit-head { display: flex; justify-content: space-between; font-size: 16px; font-weight: 600; }
.unit-head .online{color:#05b63f}.unit-head .offline{color:#ef4053}
.unit-details { display: flex; flex-direction: column; gap: 6px; color: #65758b; margin-top: 10px; font-size: 14px; }
.history-list { width: 100%; margin-top: 24px; }
.history-item { min-height: 68px; border-bottom: 1px solid #e4eaf2; display: flex; justify-content: space-between; align-items: center; gap: 16px; font-size: 14px; }
.history-item>view { display: flex; flex-direction: column; gap: 6px; }
.history-right { text-align: right; color: #7b899b; }
.history-right .ok{color:#05b63f;font-weight:600}.history-right .failed{color:#ef4053;font-weight:600}
.parameter-row { min-height: 54px; background: #f4f7fb; border-radius: 11px; padding: 0 15px; display: flex; align-items: center; justify-content: space-between; gap: 14px; font-size: 16px; }
.parameter-row input { width: 108px; text-align: right; color: #1f5eb8; }
.parameter-value { color: #1f5eb8; }
.command-form { min-width:0; }.command-input { width: 100%; max-width:100%; min-width:0; height: 140px; background: #f4f7fb; border-radius: 12px; padding: 15px; font-size: 15px; box-sizing:border-box; resize:none; }
.command-result { width:100%; max-width:100%; min-height: 76px; border-radius: 12px; background: #27364a; color: #dff0ff; padding: 15px; font-family: monospace; line-height: 1.6; font-size: 13px; box-sizing:border-box; overflow-wrap:anywhere; }
.serial-state { width:100%; max-width:100%; box-sizing:border-box; padding: 11px 14px; border-radius: 10px; font-size: 14px; color: #fff; overflow-wrap:anywhere; }
.serial-state.connected { background: #05b63f; }.serial-state.disconnected { background: #ef4053; }
.serial-reconnect { height: 44px; border: 1px solid #1f76ff; border-radius: 10px; color: #1f76ff; background: #fff; font-size: 15px; display:flex; align-items:center; justify-content:center; }
.back-wrap { padding: clamp(26px, 4.5vh, 54px) 0 max(22px, env(safe-area-inset-bottom)); }
@media(max-width:560px){.feature-panel{padding:28px 20px}.unit-grid{grid-template-columns:1fr}}
</style>
