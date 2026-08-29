<template>
  <view class="page-root feature-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar :title="featurePageTitle" :hint="featurePageHint" @back="back" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="feature-panel">
        <template v-if="type==='restart'">
          <view class="feature-icon cyan"><IconGlyph name="refresh" /></view><text class="feature-title">重启应用</text>
          <text class="description">{{ restartCapability.message }}</text>
          <button class="danger-button" :disabled="restartLoading" @click="confirmRestart">{{ restartLoading ? '正在安排重启' : '重启应用' }}</button>
        </template>

        <template v-else-if="type==='authorization'">
          <AuthorizationPanel ref="authPanel" />
        </template>

        <template v-else-if="type==='units'">
          <view class="feature-icon violet"><IconGlyph name="device" /></view><text class="feature-title">单元管理</text>
          <view v-if="units.length" class="unit-list">
            <view v-for="unit in units" :key="unit.id" class="unit-card">
              <view class="unit-head" @click="toggleUnit(unit.id)">
                <view><text>{{ unit.name }}</text><small>卡位 {{ unit.range }}</small></view>
                <view class="unit-head-state"><text :class="unit.known?'online':'offline'">{{ unit.known?'状态已更新':'等待状态' }}</text><b>{{ expandedUnitIds.has(unit.id) ? '收起' : '展开' }}</b></view>
              </view>
              <view class="unit-summary">
                <view><b>{{ unit.totalSlots }}</b><text>卡槽总数</text></view>
                <view><b>{{ unit.cardCount }}</b><text>有卡</text></view>
                <view><b>{{ unit.emptyCount }}</b><text>空卡</text></view>
                <view><b>{{ unit.chargingCount }}</b><text>充电中</text></view>
              </view>
              <view v-if="expandedUnitIds.has(unit.id)" class="unit-slot-list">
                <view v-for="slot in unit.slots" :key="slot.slotNumber" class="unit-slot-row">
                  <view class="unit-slot-main"><b>{{ String(slot.slotNumber).padStart(2,'0') }} 号</b><text>{{ slotStatusLabel(slot) }}</text></view>
                  <view class="unit-slot-detail">
                    <text>门锁 {{ slotDoorLabel(slot) }}</text>
                    <text v-if="String(slot.status||'').toUpperCase()!=='EMPTY'">电压 {{ formatTelemetry(slot.voltage,'V') }} · 电流 {{ formatTelemetry(slot.current,'A') }}</text>
                    <text v-if="String(slot.status||'').toUpperCase()!=='EMPTY' && slotCardNumber(slot)" class="slot-card-no">卡号 {{ slotCardNumber(slot) }}</text>
                    <text v-if="slotFaultText(slot)" class="slot-fault">{{ slotFaultText(slot) }}</text>
                  </view>
                </view>
              </view>
            </view>
          </view>
          <view v-else class="status-detail"><text>单元信息</text><b>暂无单元数据</b></view>
        </template>

        <template v-else-if="type==='history'">
          <view class="feature-icon orange"><IconGlyph name="history" /></view><text class="feature-title">历史管理</text>
          <view class="history-controls">
            <picker :range="historyTypeOptions" range-key="label" :value="historyTypeIndex" :disabled="historyLoading" @change="changeHistoryType">
              <view class="history-filter"><text>操作类型</text><b>{{ selectedHistoryType.label }}</b></view>
            </picker>
            <picker :range="historyResultOptions" range-key="label" :value="historyResultIndex" :disabled="historyLoading" @change="changeHistoryResult">
              <view class="history-filter"><text>执行结果</text><b>{{ selectedHistoryResult.label }}</b></view>
            </picker>
            <button class="history-refresh" :disabled="historyLoading" @click="loadHistory">
              <view class="history-refresh-icon"><IconGlyph name="refresh" /></view>
              <text>{{ historyLoading ? '读取中' : '刷新' }}</text>
            </button>
          </view>
          <view v-if="historyLoading" class="status-detail history-status"><text>历史记录</text><b>正在读取本机记录</b></view>
          <view v-else-if="historyError" class="history-error">
            <view class="status-detail history-status"><text>历史记录</text><b>{{ historyError }}</b></view>
            <button class="history-retry" @click="loadHistory">重新读取</button>
          </view>
          <view v-else-if="!filteredHistory.length" class="status-detail history-status"><text>历史记录</text><b>{{ history.length ? '没有符合筛选条件的记录' : '暂无本机操作记录' }}</b></view>
          <view v-else class="history-list">
            <button v-for="item in filteredHistory" :key="item.id" class="history-item" @click="openHistoryDetail(item)">
              <view class="history-main">
                <b>{{ item.typeLabel }}</b>
                <text>{{ historyItemSummary(item) }}</text>
              </view>
              <view class="history-right">
                <text class="history-result" :class="item.resultKind">{{ item.resultLabel }}</text>
                <text>{{ item.timestamp || '时间未知' }}</text>
              </view>
              <view class="history-chevron"><IconGlyph name="chevron-right" /></view>
            </button>
          </view>
        </template>

        <template v-else-if="type==='command'">
          <view class="feature-icon navy"><IconGlyph name="device" /></view><text class="feature-title">指令验证</text>
          <text class="description">旧指令入口已停用。请进入串口调试台，仅使用人工核对的 HEX 指令；自动地址映射、文本发送和重连均不会在页面中伪造。</text>
          <button class="primary-gradient-button feature-button spaced-action" @click="openSerialConsole">进入串口调试台</button>
        </template>

        <template v-else-if="type==='realtime'">
          <view class="feature-icon cyan"><IconGlyph name="refresh" /></view><text class="feature-title">实时日志</text>
          <text class="description">暂无日志时，请确认设备已连接服务器且串口已开启。</text>
        </template>

        <template v-else>
          <view class="feature-icon navy"><IconGlyph name="device" /></view><text class="feature-title">功能已停用</text>
          <text class="description">当前版本不再提供该功能。</text>
          <button class="primary-gradient-button feature-button spaced-action" @click="openDeviceSettings">前往设备设置</button>
        </template>
      </view>
    </scroll-view>
    <ModalShell v-if="historyDetail" closable close-on-mask size-class="modal-wide" @close="closeHistoryDetail">
      <view class="history-detail-modal">
        <view class="history-detail-head">
          <view class="history-detail-icon"><IconGlyph name="history" /></view>
          <view><text>操作详情</text><b>{{ historyDetail.typeLabel }}</b></view>
        </view>
        <view class="history-detail-status" :class="historyDetail.resultKind">{{ historyDetail.resultLabel }}</view>
        <view class="history-detail-rows">
          <view v-if="historyDetail.faceId"><text>人脸编号</text><b>{{ historyDetail.faceId }}</b></view>
          <view v-if="historyDetail.employeeName"><text>员工姓名</text><b>{{ historyDetail.employeeName }}</b></view>
          <view><text>操作对象</text><b>{{ historyDetail.targetLabel }}</b></view>
          <view><text>操作人员</text><b>{{ historyDetail.operatorName }}</b></view>
          <view><text>操作时间</text><b>{{ historyDetail.timestamp || '时间未知' }}</b></view>
          <view><text>状态阶段</text><b>{{ historyDetail.state || '未知' }}</b></view>
          <view v-if="historyDetail.checkOutcome"><text>检查结果</text><b>{{ historyDetail.checkOutcome }}</b></view>
          <view v-if="historyDetail.requestedCount"><text>批量结果</text><b>共 {{ historyDetail.requestedCount }} 个，成功 {{ historyDetail.successCount }} 个，失败 {{ historyDetail.failedCount }} 个</b></view>
          <view v-if="historyDetail.errorMessage"><text>失败原因</text><b class="detail-error">{{ historyDetail.errorMessage }}</b></view>
          <view v-if="historyDetail.errorCode"><text>失败代码</text><b class="detail-code">{{ historyDetail.errorCode }}</b></view>
          <view><text>操作编号</text><b class="detail-code">{{ historyDetail.operationId }}</b></view>
        </view>
        <view v-if="historyDetail.failures.length && historyDetail.operationType !== 'FACE_ENROLLMENT'" class="history-failures">
          <text class="history-failures-title">失败卡槽</text>
          <view v-for="(failure, index) in historyDetail.failures" :key="`${failure.slotNumber}-${index}`">
            <b>{{ failure.slotNumber ? `${failure.slotNumber}号` : '未知卡槽' }}</b>
            <text>{{ failure.message || failure.code || '发送失败' }}</text>
          </view>
        </view>
        <button class="history-detail-close" @click="closeHistoryDetail">关闭</button>
      </view>
    </ModalShell>
  </view>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AdminHeader from '@/components/AdminHeader.vue';import AdminPageToolbar from '@/components/AdminPageToolbar.vue';import IconGlyph from '@/components/IconGlyph.vue';import AuthorizationPanel from '@/components/AuthorizationPanel.vue';import ModalShell from '@/components/ModalShell.vue'
import { SLOT_STATUS_META } from '@/constants/app.js'
import { APP_RESTART_CAPABILITY } from '@/constants/deviceMaintenance.js'
import { appState, hasPermission, upsertSlotProjection } from '@/state/appState.js';import { services } from '@/services/index.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'
const type=ref('history');onLoad(options=>type.value=options.type||'history')
const roleLabel=computed(()=>appState.session?.roleLabels?.join('、')||'')
const featureTitles={restart:'重启应用',authorization:'系统授权',units:'单元管理',history:'历史管理',command:'指令验证',realtime:'实时日志'}
const featureHints={restart:'安全重启设备应用',authorization:'查看本机授权状态',units:'查看本机卡槽单元',history:'查看本机操作记录',command:'进入受限串口调试台',realtime:'查看 MQTT 通讯及串口实时日志'}
const featurePageTitle=computed(()=>featureTitles[type.value]||'功能已停用')
const featurePageHint=computed(()=>featureHints[type.value]||'当前版本不再提供该功能')
const restartCapability=APP_RESTART_CAPABILITY
const restartLoading=ref(false)
const history=ref([])
const historyLoading=ref(false);const historyError=ref('')
const historyDetail=ref(null)
const expandedUnitIds=ref(new Set())
const historyTypeIndex=ref(0);const historyResultIndex=ref(0)
const historyTypeOptions=[
  {label:'全部类型',value:''},
  {label:'管理员开门',value:'ADMIN_UNLOCK'},
  {label:'管理员取卡',value:'ADMIN_TAKE_CARD'},
  {label:'管理员单槽弹卡',value:'ADMIN_EJECT_SLOT'},
  {label:'一键弹卡',value:'UNLOCK_ALL'},
  {label:'后台一键弹卡',value:'REMOTE_EJECT_ALL'},
  {label:'取卡',value:'TAKE_CARD'},
  {label:'还卡',value:'RETURN_CARD'},
  {label:'人脸开门',value:'FACE_OPEN'},
  {label:'人脸录入',value:'FACE_ENROLLMENT'},
  {label:'后台开柜',value:'REMOTE_OPEN'},
  {label:'重启应用',value:'RESTART_APP'},
  {label:'APP升级',value:'APP_UPDATE'},
  {label:'手动检查更新',value:'APP_UPDATE_CHECK'},
  {label:'固件升级',value:'FIRMWARE_UPGRADE'},
  {label:'设备自检',value:'DEVICE_SELF_CHECK'},
  {label:'新增人员',value:'EMPLOYEE_ADD'},
  {label:'修改人员',value:'EMPLOYEE_UPDATE'},
  {label:'停用人员',value:'EMPLOYEE_DISABLE'},
  {label:'启用人员',value:'EMPLOYEE_ENABLE'}
]
const historyResultOptions=[
  {label:'全部结果',value:''},
  {label:'成功',value:'success'},
  {label:'处理中',value:'pending'},
  {label:'部分完成',value:'warning'},
  {label:'失败',value:'failed'},
  {label:'未知',value:'unknown'}
]
const selectedHistoryType=computed(()=>historyTypeOptions[historyTypeIndex.value]||historyTypeOptions[0])
const selectedHistoryResult=computed(()=>historyResultOptions[historyResultIndex.value]||historyResultOptions[0])
const legacyHistoryTypes={
  '管理员开门':'ADMIN_UNLOCK',
  '一键弹卡':'UNLOCK_ALL',
  '取卡':'TAKE_CARD',
  '还卡':'RETURN_CARD',
  '后台开柜':'REMOTE_OPEN',
  '人脸开门':'FACE_OPEN'
}
const historyOperationTypeLabels={
  ADMIN_UNLOCK:'管理员开门',
  ADMIN_TAKE_CARD:'管理员取卡',
  ADMIN_EJECT_SLOT:'管理员单槽弹卡',
  REMOTE_OPEN:'后台开柜',
  REMOTE_EJECT_ALL:'后台一键弹卡',
  TAKE_CARD:'取卡',
  RETURN_CARD:'还卡',
  FACE_OPEN:'人脸开门',
  FACE_ENROLLMENT:'人脸录入',
  UNLOCK_ALL:'一键弹卡',
  RESTART_APP:'重启应用',
  APP_UPDATE:'APP升级',
  APP_UPDATE_CHECK:'手动检查更新',
  FIRMWARE_UPGRADE:'固件升级',
  DEVICE_SELF_CHECK:'设备自检',
  EMPLOYEE_ADD:'新增人员',
  EMPLOYEE_UPDATE:'修改人员',
  EMPLOYEE_DISABLE:'停用人员',
  EMPLOYEE_ENABLE:'启用人员'
}
const historyOperationTypeLabel=(operationType,fallback='')=>historyOperationTypeLabels[operationType]||fallback||'未知操作'
const STATE_RESULT_MAP = {
  PHYSICAL_CONFIRMED: '成功',
  RECEIVED: '处理中',
  VALIDATED: '处理中',
  SERIAL_SENT: '指令已发送',
  REPORT_PENDING: '已完成，待同步',
  FAILED: '失败',
  TIMED_OUT: '已超时',
  CANCELLED: '已取消'
}
const resolveHistoryResultKind=(result='')=>{
  if(result==='成功')return 'success'
  if(result==='处理中'||result==='指令已发送')return 'pending'
  if(result==='部分完成'||result==='已取消'||result==='已完成，待同步')return 'warning'
  if(result==='失败'||result==='已超时')return 'failed'
  return 'unknown'
}
const resolveResultFromState = (item) => {
  if (item.result) return item.result
  const state = String(item.state || '').toUpperCase()
  return STATE_RESULT_MAP[state] || ''
}
const normalizeHistoryItem=(source,index)=>{
  const item=source&&typeof source==='object'?source:{}
  const operationType=String(item.operationType||legacyHistoryTypes[item.type]||'UNKNOWN').toUpperCase()
  const slotNumber=Number(item.slotNumber||0)||null
  const requestedCount=Math.max(0,Number(item.requestedCount||0)||0)
  const successCount=Math.max(0,Number(item.successCount||0)||0)
  const failedCount=Math.max(0,Number(item.failedCount||0)||0)
  const nonSlotTargetLabels={FACE_ENROLLMENT:'人脸信息',RESTART_APP:'设备应用',APP_UPDATE:'设备应用',APP_UPDATE_CHECK:'设备应用',FIRMWARE_UPGRADE:'设备固件',EMPLOYEE_ADD:'人员',EMPLOYEE_UPDATE:'人员',EMPLOYEE_DISABLE:'人员',EMPLOYEE_ENABLE:'人员'}
  const targetLabel=item.targetLabel||(operationType==='FACE_ENROLLMENT'
    ? (item.employeeName || item.operatorName || '人脸信息')
    : operationType==='UNLOCK_ALL'
    ? `待弹卡槽${requestedCount?`（${requestedCount} 个）`:''}`
    : nonSlotTargetLabels[operationType]||(item.employeeName||item.employeeId)||(slotNumber?`${String(slotNumber).padStart(2,'0')} 号卡门`:'未指定卡槽'))
  return {
    ...item,
    id:item.id||item.operationId||`history-${index}`,
    operationId:item.operationId||item.id||`history-${index}`,
    operationType,
    typeLabel:historyOperationTypeLabel(operationType,item.typeLabel||item.type),
    operatorName:item.operatorName||item.employeeName||'本机管理员',
    targetLabel,
    resultKind:item.resultKind||resolveHistoryResultKind(resolveResultFromState(item)),
    resultLabel:item.resultLabel||resolveResultFromState(item)||'未知',
    state:item.state||item.result||'UNKNOWN',
    timestamp:item.createdAt||item.timestamp||'',
    checkOutcome:item.checkOutcome==='UPDATE_AVAILABLE'?'发现新版本':item.checkOutcome==='NO_UPDATE'?'已是最新版本':'',
    slotNumber,
    requestedCount,
    successCount,
    failedCount,
    failures:Array.isArray(item.failures)?item.failures:[],
    errorCode:item.errorCode||'',
    errorMessage:item.errorMessage||''
  }
}
const filteredHistory=computed(()=>history.value.filter(item=>{
  const typeMatches=!selectedHistoryType.value.value||item.operationType===selectedHistoryType.value.value
  const resultMatches=!selectedHistoryResult.value.value||item.resultKind===selectedHistoryResult.value.value
  return typeMatches&&resultMatches
}))
const CARD_PRESENT_UNIT_STATUSES=new Set(['OCCUPIED','CHARGING','FULL','CHARGING_FAULT','ILLEGAL_CARD'])
const units=computed(()=>{
  const slots=Array.isArray(appState.slots)?appState.slots:[]
  const groupSize=Math.max(1,Number(appState.settings.singleGroupCount||appState.settings.groupSize||16))
  const groups=new Map()
  slots.forEach(source=>{
    const slotNumber=Number(source.slotNumber||source.slotId||0)
    if(slotNumber<1)return
    const status=String(source.status||'UNKNOWN').toUpperCase()
    const slot={...source,slotNumber,status}
    const groupNumber=Math.floor((slotNumber-1)/groupSize)+1
    const group=groups.get(groupNumber)||{id:groupNumber,name:`单元 ${String(groupNumber).padStart(2,'0')}`,first:slotNumber,last:slotNumber,knownSlots:0,known:false,slots:[]}
    group.first=Math.min(group.first,slotNumber)
    group.last=Math.max(group.last,slotNumber)
    group.slots.push(slot)
    if(Number(slot.updatedAt||0)>0){group.knownSlots+=1;group.known=true}
    groups.set(groupNumber,group)
  })
  return Array.from(groups.values()).sort((left,right)=>left.id-right.id).map(group=>{
    group.slots.sort((left,right)=>left.slotNumber-right.slotNumber)
    return {
      ...group,
      range:`${group.first}-${group.last}`,
      totalSlots:group.slots.length,
      cardCount:group.slots.filter(slot=>CARD_PRESENT_UNIT_STATUSES.has(slot.status)).length,
      emptyCount:group.slots.filter(slot=>slot.status==='EMPTY').length,
      chargingCount:group.slots.filter(slot=>slot.status==='CHARGING').length
    }
  })
})
const toggleUnit=(unitId)=>{const next=new Set(expandedUnitIds.value);if(next.has(unitId))next.delete(unitId);else next.add(unitId);expandedUnitIds.value=next}
const slotStatusLabel=(slot)=>SLOT_STATUS_META[String(slot?.status||'').toUpperCase()]?.label||'状态未知'
const slotCardNumber=(slot)=>String(slot?.cardNumber||slot?.cardNo||slot?.cardId||'').trim()
const slotDoorLabel=(slot)=>{
  const code=String(slot?.doorLock||slot?.doorStatus||'')
  if(code==='0x00'||code==='0')return '已锁定'
  if(code==='0x01'||code==='1')return '已解锁'
  return code||'--'
}
const slotFaultText=(slot)=>{
  const code=String(slot?.faultCode||'').trim()
  const msg=String(slot?.faultMsg||slot?.faultMessage||'').trim()
  if(code&&code!=='0'&&msg)return `${code} · ${msg}`
  if(code&&code!=='0')return code
  if(msg)return msg
  return ''
}
const formatTelemetry = (value, unit) => {
  if (value == null || value === '') return '--'
  const num = Number(value)
  return Number.isNaN(num) ? '--' : `${num.toFixed(2)} ${unit}`
}
const loadHistory=async()=>{
  historyLoading.value=true
  historyError.value=''
  try{
    if(!hasPermission('system.history.view'))throw new Error('当前账号无历史查看权限')
    const result=await services.getHistory()
    history.value=Array.isArray(result)?result.map(normalizeHistoryItem):[]
  }catch(e){
    history.value=[]
    historyError.value=e.message||'历史记录读取失败'
  }finally{
    historyLoading.value=false
  }
}
const changeHistoryType=(event)=>{historyTypeIndex.value=Number(event.detail.value)||0}
const changeHistoryResult=(event)=>{historyResultIndex.value=Number(event.detail.value)||0}
const historyItemSummary=(item)=>item.operationType==='UNLOCK_ALL'
  ? `${item.operatorName} · 成功 ${item.successCount} / 失败 ${item.failedCount}`
  : item.operationType==='FACE_ENROLLMENT'
  ? `${item.operatorName} · ${item.state==='COMPLETED'?'录入完成':item.state==='FAILED'?'录入失败':'录入中'}`
  : `${item.operatorName} · ${item.targetLabel}`
const openHistoryDetail=(item)=>{historyDetail.value=item}
const closeHistoryDetail=()=>{historyDetail.value=null}
const loadUnitSlots=async()=>{const cachedSlots=await services.loadCachedSlots();if(Array.isArray(cachedSlots))cachedSlots.forEach(slot=>upsertSlotProjection(slot))}
const confirmRestart=()=>uni.showModal({title:'确认重启应用',content:'应用将在 3 秒后关闭并重新启动，是否继续？',confirmText:'确认重启',success:async(result)=>{if(!result.confirm)return;restartLoading.value=true;try{await services.restartApp({delayMs:3000});uni.showToast({title:'应用即将重启',icon:'none'})}catch(error){uni.showToast({title:toUserErrorMessage(error,'重启安排失败'),icon:'none'})}finally{restartLoading.value=false}}})
onMounted(async()=>{services.recordAuditEvent({event_type:'FEATURE_ENTER',feature_code:'FEATURE_MAINTENANCE',feature_label:'功能维护'});if(type.value==='history')await loadHistory();if(type.value==='units')await loadUnitSlots()})
const openDeviceSettings=()=>uni.redirectTo({url:'/pages/config/config'})
const openSerialConsole=()=>uni.redirectTo({url:'/pages/serial-demo/serial-demo'})
const back=()=>uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/admin/admin'})});const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.feature-page { background: #e6f0ff; }
.feature-panel { width: min(94%, 880px); min-height: clamp(500px, 70vh, 800px); margin: clamp(14px, 2.2vh, 28px) auto 0; background: #fff; border-radius: clamp(15px, 2vw, 23px); padding: clamp(30px, 4.5vh, 56px) clamp(26px, 4vw, 64px); display: flex; flex-direction: column; align-items: center; }
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
.danger-button[disabled] { opacity: .58; }
.capability-code { margin-top:12px; color:#a22f40; font-size:12px; line-height:1.5; text-align:center; overflow-wrap:anywhere; }
.authorization-panel { width: min(100%, 650px); margin-top: 22px; display: grid; gap: 14px; }
.auth-card { min-height: 72px; border-radius: 16px; border: 1px solid #dbe5f2; background: linear-gradient(135deg,#f8fbff,#eef5ff); padding: 14px 18px; display: flex; justify-content: space-between; align-items: center; gap:14px; box-sizing:border-box; color:#22324a; box-shadow:0 8px 22px rgba(55,91,140,.08); }
.auth-card text { flex:0 0 auto; white-space:nowrap; color:#667085; font-weight:600; }
.auth-card b { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right; font-size:16px; }
.auth-card.success { border-color:#bcebd0; background:linear-gradient(135deg,#f4fff8,#e8fbf0); }
.auth-card.success b { color:#05a64a; }
.auth-card.error { border-color:#ffd1d8; background:linear-gradient(135deg,#fff7f8,#fff0f3); }
.auth-card.error b { color:#ef4053; }
.auth-card.waiting { border-color:#dce8fb; background:linear-gradient(135deg,#fbfdff,#f1f6fd); }
.auth-card.waiting b { color:#2f67a8; }
.device-card b { color:#30445f; }
.auth-refresh-button { height: 58px; border-radius: 16px; background: linear-gradient(135deg,#4aa3ff,#1f76ff 55%,#0a53c4); color:#fff; font-size:16px; font-weight:650; box-shadow:0 10px 18px rgba(31,118,255,.22), inset 0 1px 1px rgba(255,255,255,.25); display:flex; align-items:center; justify-content:center; }
.auth-refresh-button[disabled] { opacity:.58; }
.status-card { width: min(100%, 650px); min-height: 68px; border-radius: 12px; margin-top: 18px; padding: 14px 18px; display: flex; justify-content: space-between; align-items: center; gap:14px; color: #fff; font-size: 16px; box-sizing:border-box; }
.status-card text { flex:0 0 auto; white-space:nowrap; font-weight:600; }
.status-card b { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right; }
.status-card.engine-status { align-items:flex-start; }
.status-card.engine-status b { overflow:visible; text-overflow:clip; white-space:normal; text-align:left; overflow-wrap:anywhere; line-height:1.45; }
.status-card.success{background:#05b63f}.status-card.error{background:#ef1010}.status-card.info{background:#1f76ff}
.status-detail { width:min(100%,650px); min-height:48px; margin-top:12px; border-radius:12px; background:#f4f7fb; color:#42556d; display:flex; align-items:center; justify-content:space-between; gap:16px; padding:0 18px; box-sizing:border-box; font-size:15px; }
.status-detail b { color:#ef1010; font-weight:600; overflow-wrap:anywhere; }
.unit-list { width:100%; display:flex; flex-direction:column; gap:12px; margin-top:26px; }
.unit-card { overflow:hidden; background:#f7f9fc; border:1px solid #e5ebf3; border-radius:12px; }
.unit-head { min-height:62px; padding:12px 16px; box-sizing:border-box; display:flex; align-items:center; justify-content:space-between; gap:14px; cursor:pointer; }
.unit-head>view:first-child,.unit-head-state { min-width:0; display:flex; flex-direction:column; gap:4px; }
.unit-head>view:first-child text { color:#2c3c53; font-size:16px; font-weight:650; }
.unit-head small { color:#8592a4; font-size:12px; }
.unit-head-state { align-items:flex-end; font-size:12px; }
.unit-head-state .online{color:#07994a}.unit-head-state .offline{color:#d24a5b}.unit-head-state b{color:#1f76ff;font-weight:600}
.unit-summary { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:1px; border-top:1px solid #e5ebf3; background:#e5ebf3; }
.unit-summary>view { min-width:0; padding:11px 6px; background:#fff; text-align:center; }
.unit-summary b,.unit-summary text { display:block; }
.unit-summary b { color:#2a405e; font-size:17px; }
.unit-summary text { margin-top:3px; color:#7c899c; font-size:11px; }
.unit-slot-list { padding:0 16px 8px; border-top:1px solid #e5ebf3; background:#fff; }
.unit-slot-row { min-height:54px; display:flex; align-items:center; justify-content:space-between; gap:14px; border-bottom:1px solid #edf1f6; }
.unit-slot-row:last-child { border-bottom:0; }
.unit-slot-main,.unit-slot-detail { min-width:0; display:flex; flex-direction:column; gap:3px; }
.unit-slot-main b { color:#34465f; font-size:13px; }.unit-slot-main text { color:#1f76ff; font-size:12px; }
.unit-slot-detail { align-items:flex-end; color:#7b899b; font-size:12px; text-align:right; }
.slot-card-no { color:#1f76ff; font-weight:600; }
.history-controls { width:100%; margin-top:22px; display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr) 112px; gap:10px; }
.history-filter { height:52px; border:1px solid #dce5f1; border-radius:8px; padding:7px 13px; box-sizing:border-box; display:flex; flex-direction:column; justify-content:center; gap:2px; background:#f8fbff; }
.history-filter text { color:#7b899b; font-size:12px; }
.history-filter b { color:#26384f; font-size:14px; font-weight:600; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.history-refresh { height:52px; margin:0; border-radius:8px; background:#1f76ff; color:#fff; display:flex; align-items:center; justify-content:center; gap:7px; font-size:14px; font-weight:600; }
.history-refresh::after,.history-retry::after,.history-item::after,.history-detail-close::after { border:0; }
.history-refresh[disabled] { opacity:.55; }
.history-refresh-icon { width:18px; height:18px; }
.history-error { width:100%; display:flex; flex-direction:column; align-items:center; }
.history-status { width:100%; }
.history-retry { width:160px; height:44px; margin-top:12px; border:1px solid #1f76ff; border-radius:8px; background:#fff; color:#1f76ff; font-size:14px; display:flex; align-items:center; justify-content:center; }
.history-list { width:100%; margin-top:12px; border-top:1px solid #e4eaf2; }
.history-item { width:100%; min-height:74px; margin:0; padding:10px 4px; border:0; border-bottom:1px solid #e4eaf2; border-radius:0; background:#fff; display:grid; grid-template-columns:minmax(0,1fr) auto 20px; align-items:center; gap:14px; font-size:14px; line-height:1.4; text-align:left; }
.history-main,.history-right { min-width:0; display:flex; flex-direction:column; gap:5px; }
.history-main b { color:#24364e; font-size:15px; }
.history-main text,.history-right { color:#7b899b; }
.history-main text { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.history-right { text-align:right; }
.history-result { font-weight:650; }.history-result.success{color:#0a9b49}.history-result.pending{color:#1f76ff}.history-result.warning{color:#e68500}.history-result.failed{color:#df3347}.history-result.unknown{color:#7b899b}
.history-chevron { width:18px; height:18px; color:#a5b2c3; }
.history-detail-modal { padding:28px 32px 26px; }
.history-detail-head { min-height:54px; padding-right:42px; display:flex; align-items:center; gap:14px; }
.history-detail-icon { flex:0 0 46px; width:46px; height:46px; border-radius:50%; padding:11px; box-sizing:border-box; background:#fff0e4; color:#ed7b1d; }
.history-detail-head>view:last-child { min-width:0; display:flex; flex-direction:column; gap:3px; }
.history-detail-head text { color:#7b899b; font-size:13px; }.history-detail-head b { color:#22324a; font-size:20px; }
.history-detail-status { display:inline-flex; min-height:30px; margin-top:18px; padding:0 12px; border-radius:6px; align-items:center; font-size:14px; font-weight:650; }
.history-detail-status.success{background:#e8f8ef;color:#078a3f}.history-detail-status.pending{background:#e9f2ff;color:#1766d3}.history-detail-status.warning{background:#fff3dc;color:#b46900}.history-detail-status.failed{background:#ffedf0;color:#cf2940}.history-detail-status.unknown{background:#eef2f6;color:#637286}
.history-detail-rows { margin-top:16px; border-top:1px solid #e2e8f0; }
.history-detail-rows>view { min-height:48px; padding:9px 0; border-bottom:1px solid #e2e8f0; box-sizing:border-box; display:grid; grid-template-columns:92px minmax(0,1fr); align-items:center; gap:18px; }
.history-detail-rows text { color:#7b899b; font-size:14px; }.history-detail-rows b { color:#26384f; font-size:14px; font-weight:600; text-align:right; overflow-wrap:anywhere; }
.history-detail-rows .detail-error { color:#cf2940; }.history-detail-rows .detail-code { font-family:monospace; font-size:13px; }
.history-failures { margin-top:18px; }.history-failures-title { display:block; color:#26384f; font-size:14px; font-weight:650; margin-bottom:6px; }
.history-failures>view { min-height:40px; border-bottom:1px solid #edf1f6; display:grid; grid-template-columns:70px minmax(0,1fr); align-items:center; gap:12px; font-size:13px; }
.history-failures b { color:#cf2940; }.history-failures text { color:#65758b; overflow-wrap:anywhere; }
.history-detail-close { width:100%; height:48px; margin-top:22px; border-radius:8px; background:#1f76ff; color:#fff; font-size:15px; font-weight:650; display:flex; align-items:center; justify-content:center; }
.parameter-row { min-height: 54px; background: #f4f7fb; border-radius: 11px; padding: 0 15px; display: flex; align-items: center; justify-content: space-between; gap: 14px; font-size: 16px; }
.parameter-row input { width: 108px; text-align: right; color: #1f5eb8; }
.parameter-value { color: #1f5eb8; }
@media(max-width:560px){.feature-panel{padding:28px 20px}.unit-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.history-controls{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.history-refresh{grid-column:1/-1}.history-item{grid-template-columns:minmax(0,1fr) auto 18px;gap:9px}.history-detail-modal{padding:24px 20px 20px}.history-detail-rows>view{grid-template-columns:78px minmax(0,1fr);gap:10px}}
</style>
