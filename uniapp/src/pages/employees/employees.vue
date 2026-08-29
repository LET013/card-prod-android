<template>
  <view class="page-root employees-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <AdminPageToolbar
      title="人员管理"
      hint="查看人员信息，并可手动刷新列表"
      :action-label="syncing ? '同步中' : '手动同步'"
      :action-disabled="syncing"
      :action-visible="canSyncEmployees"
      @back="back"
      @action="syncNow"
    />
    <view class="employees-content">
      <view class="employee-toolbar">
        <view class="search-wrap">
          <view class="search-icon"><IconGlyph name="search" /></view>
          <input v-model="query" class="search-input" placeholder="搜索姓名、工号或部门" confirm-type="search" @input="search" />
        </view>
        <view class="employee-toolbar-actions">
          <view class="directory-summary">
            <text>人员 {{ employees.length }}</text>
            <text class="face-count-summary">已录人脸 {{ faceRegisteredCount }} 人</text>
          </view>
          <button v-if="canCreateEmployees" class="employee-add" @click="openEmployeeEditor()">新增人员</button>
        </view>
      </view>
      <scroll-view class="employee-scroll" scroll-y>
        <view v-if="loading" class="state-text">正在加载人员……</view>
        <view v-else-if="searchError" class="state-text error">{{ searchError }}</view>
        <view v-else-if="!employees.length" class="state-text">没有匹配的人员</view>
        <view v-else class="employee-grid">
          <button v-for="employee in employees" :key="employee.id" class="employee-card" @click="openEmployee(employee)">
            <image v-if="employee.avatarUrl" class="avatar" :src="employee.avatarUrl" mode="aspectFill" />
            <view v-else class="avatar avatar-fallback"><IconGlyph name="user" /></view>
            <view class="employee-copy">
              <view class="employee-name-row">
                <b>{{ employee.employeeName || '未命名员工' }}</b>
                <text class="employee-state" :class="employee.enabled===false?'disabled':'enabled'">{{ employee.enabled===false ? '已停用' : '正常' }}</text>
              </view>
              <text>{{ employee.employeeCode || '--' }}</text>
              <text class="department-text">{{ employee.departmentName || employee.department || '未设置部门' }}</text>
              <view class="badge-row"><text :class="employee.faceRegistered?'ok':'muted'">人脸</text></view>
            </view>
            <view class="employee-chevron"><IconGlyph name="chevron-right" /></view>
          </button>
        </view>
      </scroll-view>
    </view>
    <ModalShell v-if="selectedEmployee" closable close-on-mask size-class="modal-wide" @close="closeEmployee">
      <view class="employee-detail">
        <view class="detail-head">
          <view class="detail-avatar"><IconGlyph name="user" /></view>
          <view><text>人员详情</text><b>{{ selectedEmployee.employeeName || '未命名员工' }}</b></view>
        </view>
        <view class="detail-status" :class="selectedEmployee.enabled===false?'disabled':'enabled'">{{ selectedEmployee.enabled===false ? '已停用' : '正常使用' }}</view>
        <view class="detail-rows">
          <view><text>员工工号</text><b>{{ selectedEmployee.employeeCode || '-' }}</b></view>
          <view><text>工卡号</text><b>{{ selectedEmployee.cardNo || '-' }}</b></view>
          <view><text>部门</text><b>{{ selectedEmployee.departmentName || selectedEmployee.department || '-' }}</b></view>
          <view><text>职位</text><b>{{ selectedEmployee.position || '-' }}</b></view>
          <view><text>联系电话</text><b>{{ selectedEmployee.phone || '-' }}</b></view>
          <view><text>人脸状态</text><b :class="selectedEmployee.faceRegistered?'detail-ok':'detail-muted'">{{ selectedEmployee.faceRegistered ? ((selectedEmployee.faceCount||facePhotos.length) ? `已录入 ${selectedEmployee.faceCount||facePhotos.length} 张` : '已录入') : '未录入' }}</b></view>
        </view>
        <view v-if="facePhotos.length" class="detail-face-photos">
          <view class="face-photo-thumb" v-for="(photo, idx) in facePhotos" :key="idx" @click="previewFacePhoto(photo)">
            <image :src="facePhotoSource(photo)" mode="aspectFill" class="face-thumb-img" />
          </view>
        </view>
        <view v-else-if="loadingFaces" class="detail-face-photos"><text class="detail-muted">正在加载人脸照片...</text></view>
        <view v-else class="detail-face-photos">
          <text class="detail-muted">{{ selectedEmployee.faceRegistered || selectedEmployee.faceCount ? '暂未获取到人脸照片' : '暂未录入人脸' }}</text>
        </view>
        <view class="detail-actions">
          <button v-if="canUpdateEmployees" class="edit-action" :disabled="employeeSaving" @click="openEmployeeEditor(selectedEmployee)">编辑资料</button>
          <button
            v-if="canEnableEmployees"
            class="authorization-action"
            :class="selectedEmployee.enabled===false ? 'enable' : 'disable'"
            :disabled="employeeSaving"
            @click="openAuthorizationConfirm(selectedEmployee)"
          >{{ selectedEmployee.enabled===false ? '恢复使用' : '停用人员' }}</button>
          <button v-if="canRegisterFace" class="face-action" :disabled="selectedEmployee.enabled===false" @click="startFaceEnrollment(selectedEmployee)">
            <view><IconGlyph name="face" /></view>
            <text>{{ selectedEmployee.faceRegistered ? '重新录入人脸' : '录入人脸' }}</text>
          </button>
        </view>
        <button class="detail-close" @click="closeEmployee">关闭</button>
      </view>
    </ModalShell>
    <ModalShell v-if="employeeEditorVisible" closable close-on-mask size-class="modal-wide employee-editor-modal" @close="closeEmployeeEditor">
      <view class="employee-editor">
        <view class="editor-head">
          <text>{{ editingEmployee ? '编辑人员' : '新增人员' }}</text>
          <text>填写人员基本信息</text>
        </view>
        <view class="editor-grid">
          <label class="editor-field"><text>员工编码</text><input v-model="employeeForm.employeeCode" /></label>
          <label class="editor-field"><text class="field-required">姓名</text><input v-model="employeeForm.employeeName" /></label>
          <label class="editor-field">
            <text>部门</text>
            <picker :range="departmentOptions" range-key="label" :disabled="departmentsLoading" @change="selectDepartment">
              <view class="editor-picker" :class="{ placeholder: !selectedDepartmentName }">
                {{ departmentsLoading ? '正在加载部门…' : (selectedDepartmentName || '请选择授权部门') }}
              </view>
            </picker>
            <text v-if="departmentLoadError" class="editor-field-error">{{ departmentLoadError }}</text>
          </label>
          <label class="editor-field"><text>手机号</text><input v-model="employeeForm.phone" /></label>
        </view>
        <view class="editor-actions">
          <button class="detail-close" :disabled="employeeSaving" @click="closeEmployeeEditor">取消</button>
          <button class="editor-save" :disabled="employeeSaving" @click="submitEmployee">{{ employeeSaving ? '保存中…' : '保存人员' }}</button>
        </view>
      </view>
    </ModalShell>

    <!-- 保存状态浮层（参照 DeviceConfigPanel 交互模式，修复 BUG-025 BUG-029） -->
    <Teleport to="body">
      <view v-if="savingModal.visible" class="saving-overlay" @click.stop>
        <view class="saving-dialog">
          <view class="saving-icon" v-if="savingModal.type === 'loading'">
            <view class="spinner"></view>
          </view>
          <view class="saving-icon" v-else-if="savingModal.type === 'success'">
            <view class="checkmark"></view>
          </view>
          <view class="saving-icon" v-else-if="savingModal.type === 'error'">
            <view class="error-mark">!</view>
          </view>
          <text class="saving-text">{{ savingModal.text }}</text>
        </view>
      </view>
    </Teleport>

    <Teleport to="body">
      <view v-if="authorizationConfirm.visible" class="authorization-confirm-mask" @click.stop>
        <view class="authorization-confirm-card">
          <text class="authorization-confirm-title">{{ authorizationConfirm.authorized ? '恢复人员使用' : '确认停用人员' }}</text>
          <text class="authorization-confirm-message">
            {{ authorizationConfirm.authorized
              ? `确认恢复“${authorizationConfirm.employee?.employeeName || '该人员'}”使用？`
              : `停用后“${authorizationConfirm.employee?.employeeName || '该人员'}”将不能继续使用设备。` }}
          </text>
          <text v-if="authorizationConfirm.error" class="authorization-confirm-error">{{ authorizationConfirm.error }}</text>
          <view class="authorization-confirm-actions">
            <button :disabled="authorizationConfirm.submitting" @click="closeAuthorizationConfirm">取消</button>
            <button
              class="confirm"
              :class="authorizationConfirm.authorized ? 'enable' : 'disable'"
              :disabled="authorizationConfirm.submitting"
              @click="submitAuthorizationChange"
            >{{ authorizationConfirm.submitting ? '提交中…' : '确认' }}</button>
          </view>
        </view>
      </view>
    </Teleport>
  </view>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import ModalShell from '@/components/ModalShell.vue'
import { appState, hasPermission } from '@/state/appState.js'
import { services } from '@/services/index.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'
const roleLabel=computed(()=>appState.session?.roleLabels?.join('、')||'')
const canRegisterFace=computed(()=>hasPermission('system.employee.face-register'))
const canSyncEmployees = computed(() => hasPermission('system.employee.sync'))
const canCreateEmployees = computed(() => hasPermission('system.employee.create'))
const canUpdateEmployees = computed(() => hasPermission('system.employee.update'))
const canEnableEmployees = computed(() => hasPermission('system.employee.enable'))
const query=ref('');const employees=ref([]);const loading=ref(false);const syncing=ref(false);const selectedEmployee=ref(null);const facePhotos=ref([]);const loadingFaces=ref(false);const searchError=ref('')
const employeeEditorVisible = ref(false)
const editingEmployee = ref(null)
const employeeSaving = ref(false)
const departmentOptions = ref([])
const departmentsLoading = ref(false)
const departmentLoadError = ref('')
const authorizationConfirm = reactive({
  visible: false,
  employee: null,
  authorized: false,
  submitting: false,
  error: ''
})
const savingModal = reactive({
  visible: false,
  type: 'loading', // 'loading' | 'success' | 'error'
  text: ''
})
const employeeForm = reactive({
  employeeId: '',
  employeeCode: '',
  employeeName: '',
  deptId: '',
  phone: '',
  status: '0'
})
const selectedDepartmentName = computed(() => {
  const selected = departmentOptions.value.find(item => String(item.deptId) === String(employeeForm.deptId))
  return selected?.deptName || ''
})
const faceRegisteredCount=computed(()=>employees.value.filter(employee=>employee.faceRegistered).length)
let searchSequence=0
const search=async()=>{const sequence=++searchSequence;loading.value=true;searchError.value='';try{if(!hasPermission('system.employee.view'))throw new Error('当前账号无人员查看权限');const result=await services.searchEmployees(query.value,{includeDisabled:true});if(sequence===searchSequence)employees.value=Array.isArray(result)?result:[]}catch(error){if(sequence===searchSequence){employees.value=[];searchError.value=toUserErrorMessage(error,'人员读取失败')}}finally{if(sequence===searchSequence)loading.value=false}}
const syncNow=async()=>{if(syncing.value)return;syncing.value=true;try{const result=await services.syncEmployees({full:true,source:'EMPLOYEE_MANAGEMENT_REFRESH'});const syncedCount=Array.isArray(result)?result.length:0;employees.value=Array.isArray(result)?result:[];await search();uni.showToast({title:`已同步 ${syncedCount} 名人员`,icon:'none'})}catch(error){await search().catch(()=>{});uni.showToast({title:toUserErrorMessage(error,'人员同步失败'),icon:'none'})}finally{syncing.value=false}}
onMounted(search)
const openEmployee=(employee)=>{selectedEmployee.value=employee}
const closeEmployee=()=>{selectedEmployee.value=null;facePhotos.value=[]}

const facePhotoSource = (photo) => {
  const source = String(photo?.photoBase64 || photo?.faceImageBase64 || photo?.imageBase64 || '').trim()
  if (!source || /^(data:|blob:|https?:|file:|content:)/i.test(source)) return source
  const mimeType = String(photo?.mimeType || 'image/jpeg').trim() || 'image/jpeg'
  return `data:${mimeType};base64,${source}`
}
const previewFacePhoto = (photo) => {
  const current = facePhotoSource(photo)
  const urls = facePhotos.value.map(facePhotoSource).filter(Boolean)
  if (!current || !urls.length) return
  uni.previewImage({ current, urls })
}

// BUG-024: 加载选中员工的人脸照片
watch(selectedEmployee, async (emp) => {
  facePhotos.value = []
  if (!emp || !emp.employeeId) return
  loadingFaces.value = true
  try {
    const bindings = await services.listEmployeeFaces(emp.employeeId)
    facePhotos.value = Array.isArray(bindings) ? bindings.filter(b => b && (b.photoBase64 || b.faceImageBase64 || b.imageBase64)) : []
  } catch (e) {
    console.warn('load face photos for employee failed:', e)
    facePhotos.value = []
  } finally {
    loadingFaces.value = false
  }
})
const resetEmployeeForm = () => {
  Object.assign(employeeForm, {
    employeeId: '',
    employeeCode: '',
    employeeName: '',
    deptId: '',
    phone: '',
    status: '0'
  })
}

const flattenDepartmentTree = (root) => {
  const result = []
  const queue = [{ node: root, depth: 0 }]
  const visited = new Set()
  for (let index = 0; index < queue.length; index += 1) {
    const { node, depth } = queue[index]
    const deptId = String(node?.deptId ?? '').trim()
    if (!deptId || visited.has(deptId)) continue
    visited.add(deptId)
    const deptName = String(node?.deptName || '').trim()
    result.push({ deptId, deptName, label: `${'　'.repeat(depth)}${deptName || deptId}` })
    const children = Array.isArray(node?.children) ? node.children : []
    children.forEach(child => queue.push({ node: child, depth: depth + 1 }))
  }
  return result
}

const loadDepartmentOptions = async () => {
  departmentsLoading.value = true
  departmentLoadError.value = ''
  try {
    departmentOptions.value = flattenDepartmentTree(await services.getDepartmentTree())
  } catch (error) {
    departmentOptions.value = []
    departmentLoadError.value = toUserErrorMessage(error, '部门列表加载失败')
  } finally {
    departmentsLoading.value = false
  }
}

const selectDepartment = (event) => {
  const selected = departmentOptions.value[Number(event?.detail?.value)]
  employeeForm.deptId = selected?.deptId || ''
}

const openEmployeeEditor = async (employee = null) => {
  resetEmployeeForm()
  editingEmployee.value = employee || null
  if (employee) {
    Object.assign(employeeForm, {
      employeeId: String(employee.employeeId || ''),
      employeeCode: employee.employeeCode || '',
      employeeName: employee.employeeName || '',
      deptId: employee.departmentId == null && employee.deptId == null
        ? ''
        : String(employee.departmentId ?? employee.deptId),
      phone: employee.phone || '',
      status: String(employee.status ?? (employee.enabled === false ? '1' : '0'))
    })
  }
  selectedEmployee.value = null
  employeeEditorVisible.value = true
  await loadDepartmentOptions()
}

const closeEmployeeEditor = () => {
  if (employeeSaving.value) return
  employeeEditorVisible.value = false
  editingEmployee.value = null
  resetEmployeeForm()
}

const employeeMutationPayload = (action) => ({
  action,
  employeeId: action === 'update' ? employeeForm.employeeId : undefined,
  employeeCode: employeeForm.employeeCode,
  employeeName: employeeForm.employeeName,
  deptId: employeeForm.deptId,
  department: selectedDepartmentName.value,
  phone: employeeForm.phone,
  status: employeeForm.status
})

const delayForResult = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const submitEmployee = async () => {
  if (employeeSaving.value) return
  const action = editingEmployee.value ? 'update' : 'add'
  if (!employeeForm.employeeName.trim()) {
    uni.showToast({ title: '员工姓名不能为空', icon: 'none', zIndex: 9999 })
    return
  }
  if (departmentLoadError.value) {
    uni.showToast({ title: departmentLoadError.value, icon: 'none', zIndex: 9999 })
    return
  }
  if (employeeForm.deptId && !selectedDepartmentName.value) {
    uni.showToast({ title: '请选择设备授权范围内的部门', icon: 'none', zIndex: 9999 })
    return
  }

  employeeSaving.value = true
  savingModal.visible = true
  savingModal.type = 'loading'
  savingModal.text = action === 'add' ? '正在新增人员...' : '正在更新人员...'
  try {
    const result = await services.saveEmployee(employeeMutationPayload(action))
    employeeEditorVisible.value = false
    editingEmployee.value = null
    resetEmployeeForm()
    await search()
    savingModal.type = 'success'
    savingModal.text = result.cacheUpdated
      ? (action === 'add' ? '人员已新增' : '人员已更新')
      : '人员已保存，请点击手动同步刷新列表'
    await delayForResult(1500)
    savingModal.visible = false
  } catch (error) {
    savingModal.type = 'error'
    savingModal.text = toUserErrorMessage(error, '人员保存失败')
    await delayForResult(1800)
    savingModal.visible = false
    uni.showToast({ title: toUserErrorMessage(error, '人员保存失败'), icon: 'none' })
  } finally {
    employeeSaving.value = false
  }
}

const startFaceEnrollment=(employee)=>{if(!employee||employee.enabled===false)return;closeEmployee();uni.navigateTo({url:`/pages/biometric/face?employeeId=${encodeURIComponent(employee.employeeId||employee.id||'')}`})}
const openAuthorizationConfirm = (employee) => {
  authorizationConfirm.visible = true
  authorizationConfirm.employee = employee
  authorizationConfirm.authorized = employee?.enabled === false
  authorizationConfirm.submitting = false
  authorizationConfirm.error = ''
}
const closeAuthorizationConfirm = () => {
  if (authorizationConfirm.submitting) return
  authorizationConfirm.visible = false
  authorizationConfirm.employee = null
  authorizationConfirm.error = ''
}
const submitAuthorizationChange = async () => {
  if (authorizationConfirm.submitting || !authorizationConfirm.employee) return
  authorizationConfirm.submitting = true
  authorizationConfirm.error = ''
  try {
    const result = await services.setEmployeeAuthorization(
      authorizationConfirm.employee.employeeId,
      authorizationConfirm.authorized
    )
    const actionText = authorizationConfirm.authorized ? '已恢复使用' : '已停用'
    if (!result?.cacheUpdated) {
      authorizationConfirm.visible = false
      authorizationConfirm.employee = null
      selectedEmployee.value = null
      await search()
      uni.showToast({ title: `${actionText}，请点击手动同步刷新列表`, icon: 'none', duration: 3500, zIndex: 10030 })
      return
    }
    selectedEmployee.value = result.employee || {
      ...authorizationConfirm.employee,
      enabled: authorizationConfirm.authorized
    }
    authorizationConfirm.visible = false
    authorizationConfirm.employee = null
    await search()
    uni.showToast({ title: actionText, icon: 'success', zIndex: 10030 })
  } catch (error) {
    authorizationConfirm.error = toUserErrorMessage(error, '人员状态修改失败')
  } finally {
    authorizationConfirm.submitting = false
  }
}
const back=()=>uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/admin/admin'})});const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
onMounted(() => {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'EMPLOYEES_LIST', feature_label: '人员管理' })
})
</script>
<style scoped>
.employees-page { background: #e6f0ff; }
.employees-content { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: clamp(14px, 2vw, 24px) clamp(12px, 2vw, 26px) max(14px, env(safe-area-inset-bottom)); }
.employee-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: center; }
.search-wrap { background: #d3e5ff; border-radius: 8px; height: clamp(48px, 6.4vh, 60px); padding: 0 clamp(14px, 2vw, 22px); display: flex; align-items: center; gap:10px; }
.search-icon { width:20px; height:20px; flex:0 0 auto; color:#4f6d91; }
.search-input { width: 100%; font-size: 16px; color: #3d4d62; }
.directory-summary { min-width: 182px; min-height:48px; padding:0 18px; border-radius:8px; background:#fff; display:flex; align-items:center; gap:16px; color:#53657c; font-size:14px; white-space:nowrap; box-sizing:border-box; }
.face-count-summary { min-width: 88px; }
.employee-toolbar-actions { display:flex; align-items:center; gap:10px; }
.employee-add { height:48px; margin:0; padding:0 18px; border-radius:8px; border:0; background:#1f76ff; color:#fff; font-size:14px; line-height:48px; white-space:nowrap; }
.employee-add::after,.edit-action::after,.editor-save::after { border:0; }
.employee-scroll { flex: 1; min-height: 0; margin-top: 16px; }
.employee-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: clamp(10px, 1.8vw, 20px); }
.employee-card { position: relative; width:100%; min-height: clamp(112px, 16vh, 164px); margin:0; border:1px solid #dce5f1; background: #fff; border-radius: 8px; padding: clamp(14px, 2vw, 22px); display: flex; align-items: center; gap: clamp(12px, 2vw, 20px); overflow: hidden; text-align:left; line-height:1.4; box-sizing:border-box; }
.employee-card::after,.face-action::after,.detail-close::after { border:0; }
.avatar { width: clamp(62px, 9vw, 100px); height: clamp(62px, 9vw, 100px); border-radius: 50%; flex: 0 0 auto; }
.avatar-fallback { padding:clamp(15px,2vw,25px); box-sizing:border-box; background:#eaf2ff; color:#2a72dc; }
.employee-copy { flex:1; display: flex; flex-direction: column; gap: 7px; font-size: clamp(13px, 1.7vw, 16px); color: #66758a; min-width: 0; }
.employee-copy > text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.employee-name-row { display:flex; align-items:center; gap:8px; min-width:0; }
.employee-name-row b { color:#22324a; font-size:clamp(15px,1.8vw,18px); overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.employee-state { flex:0 0 auto; min-height:22px; padding:2px 7px; border-radius:4px; font-size:11px; display:flex; align-items:center; }
.employee-state.enabled { background:#e5f8ed; color:#078f45; }.employee-state.disabled { background:#ffedf0; color:#cc3044; }
.department-text { color:#8995a5; }
.badge-row { display: flex; gap: 6px; }
.badge-row text { font-size: 11px; padding: 3px 7px; border-radius: 4px; }
.badge-row .ok { background: #dcf9e7; color: #06a843; }
.badge-row .muted { background: #edf0f4; color: #8d98a7; }
.employee-chevron { width:18px; height:18px; flex:0 0 auto; color:#a4b1c2; }
.state-text { text-align: center; color: #728198; padding: 50px 0; font-size: 16px; }
.state-text.error { color: #c73545; }
.employee-detail { padding:28px 32px 26px; }
.detail-head { min-height:54px; padding-right:42px; display:flex; align-items:center; gap:14px; }
.detail-avatar { width:48px; height:48px; flex:0 0 auto; padding:11px; border-radius:50%; box-sizing:border-box; background:#eaf2ff; color:#2a72dc; }
.detail-head>view:last-child { min-width:0; display:flex; flex-direction:column; gap:3px; }.detail-head text { color:#7b899b; font-size:13px; }.detail-head b { color:#22324a; font-size:20px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.detail-status { display:inline-flex; min-height:30px; margin-top:18px; padding:0 12px; border-radius:6px; align-items:center; font-size:14px; font-weight:650; }.detail-status.enabled{background:#e5f8ed;color:#078f45}.detail-status.disabled{background:#ffedf0;color:#cc3044}
.detail-rows { margin-top:16px; border-top:1px solid #e2e8f0; }.detail-rows>view { min-height:46px; padding:8px 0; border-bottom:1px solid #e2e8f0; box-sizing:border-box; display:grid; grid-template-columns:92px minmax(0,1fr); align-items:center; gap:18px; }.detail-rows text { color:#7b899b; font-size:14px; }.detail-rows b { color:#26384f; font-size:14px; font-weight:600; text-align:right; overflow-wrap:anywhere; }.detail-rows .detail-ok{color:#078f45}.detail-rows .detail-muted{color:#8b97a6}
.detail-actions { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr)); gap:10px; margin-top:20px; }
.detail-face-photos { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; }
.face-photo-thumb { width: 60px; height: 60px; border-radius: 8px; overflow: hidden; border: 2px solid #e2e8f0; background: #f5f7fa; }
.face-thumb-img { width: 100%; height: 100%; }
.face-action,.edit-action,.authorization-action { width:100%; height:50px; margin:0; border-radius:8px; display:flex; align-items:center; justify-content:center; gap:8px; font-size:15px; font-weight:650; }
.face-action { background:#1f76ff; color:#fff; }.face-action>view{width:20px;height:20px}.face-action[disabled],.edit-action[disabled]{opacity:.5}
.edit-action { border:1px solid #1f76ff; background:#fff; color:#1f76ff; }
.authorization-action.disable { border:1px solid #ef4053; background:#fff5f6; color:#c92d40; }
.authorization-action.enable { border:1px solid #20a464; background:#f0fbf5; color:#14804d; }
.detail-close { width:100%; height:48px; margin-top:10px; border:1px solid #d8e0eb; border-radius:8px; background:#fff; color:#53657c; display:flex; align-items:center; justify-content:center; font-size:15px; }
.employee-editor { height:100%; max-height:100%; padding:32px 36px 28px; box-sizing:border-box; display:flex; flex-direction:column; overflow:hidden; }
:deep(.employee-editor-modal) { width:min(92vw,780px); height:min(760px,calc(100vh - 40px)); max-height:calc(100vh - 40px); overflow:hidden; }
.editor-head { padding-right:40px; display:flex; flex-direction:column; gap:7px; }.editor-head text:first-child{font-size:23px;font-weight:700;color:#22324a}.editor-head text:last-child{font-size:15px;color:#7b899b}
.editor-grid { flex:1 1 auto; min-height:0; margin-top:24px; padding:18px; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); grid-auto-rows:max-content; align-content:start; gap:18px 20px; overflow-y:auto; overscroll-behavior:contain; -webkit-overflow-scrolling:touch; border:1px solid #dce6f2; border-radius:12px; background:#f7faff; box-sizing:border-box; }
.editor-field { display:flex; flex-direction:column; gap:9px; color:#52647b; font-size:15px; font-weight:600; }.editor-field input{height:54px;padding:0 16px;border:1px solid #aabbd0;border-radius:9px;background:#fff;color:#26384f;font-size:16px;box-sizing:border-box}.editor-field input:focus{border-color:#1f76ff;box-shadow:0 0 0 2px rgba(31,118,255,.18)}.editor-field input[disabled]{background:#f2f4f7;color:#8a96a7;border-color:#d8e0eb}.field-required::after{content:' *';color:#e8423f;font-weight:700}
.editor-picker { height:54px; padding:0 16px; border:1px solid #aabbd0; border-radius:9px; background:#fff; color:#26384f; font-size:16px; font-weight:400; line-height:54px; box-sizing:border-box; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.editor-picker.placeholder{color:#8a96a7}.editor-field-error{color:#d9363e;font-size:13px;font-weight:500}
.editor-actions { flex:0 0 auto; display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-top:24px; }.editor-actions .detail-close{height:54px;margin-top:0;font-size:16px}.editor-save{display:flex;align-items:center;justify-content:center;height:54px;margin:0;border:0;border-radius:10px;background:#1f76ff;color:#fff;font-size:16px;font-weight:650;text-align:center;line-height:54px}.editor-save[disabled]{opacity:.55}
.authorization-confirm-mask { position:fixed; inset:0; z-index:10020; display:flex; align-items:center; justify-content:center; padding:20px; box-sizing:border-box; background:rgba(15,25,40,.58); }
.authorization-confirm-card { width:min(88vw,420px); padding:26px; box-sizing:border-box; border-radius:14px; background:#fff; box-shadow:0 18px 54px rgba(15,35,65,.28); }
.authorization-confirm-title { display:block; color:#22324a; font-size:19px; font-weight:700; }
.authorization-confirm-message { display:block; margin-top:14px; color:#5f6f84; font-size:14px; line-height:1.6; overflow-wrap:anywhere; }
.authorization-confirm-error { display:block; margin-top:12px; color:#c92d40; font-size:13px; line-height:1.5; }
.authorization-confirm-actions { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:22px; }
.authorization-confirm-actions button { height:44px; margin:0; border:1px solid #d7e0eb; border-radius:8px; background:#fff; color:#53657c; display:flex; align-items:center; justify-content:center; font-size:14px; }
.authorization-confirm-actions .confirm.disable { border-color:#ef4053; background:#ef4053; color:#fff; }
.authorization-confirm-actions .confirm.enable { border-color:#20a464; background:#20a464; color:#fff; }
.authorization-confirm-actions button[disabled] { opacity:.55; }
@media (max-width: 430px) {
  .employees-content { padding-left: 9px; padding-right: 9px; }
  .employee-toolbar { grid-template-columns:1fr; }
  .employee-toolbar-actions { justify-content:space-between; }
  .directory-summary { justify-content:space-between; }
  .employee-grid { gap: 8px; }
  .employee-card { padding: 10px; gap: 8px; min-height: 104px; }
  .avatar { width: 52px; height: 52px; }
  .employee-copy { font-size: 12px; gap: 7px; }
  .employee-detail { padding:24px 20px 20px; }
  .employee-editor { padding:24px 20px 20px; }
  .detail-rows>view { grid-template-columns:78px minmax(0,1fr); gap:10px; }
  .editor-grid { grid-template-columns:1fr; padding:14px; gap:16px; }
}

/* ===== 保存状态浮层（参照 DeviceConfigPanel，修复 BUG-025 BUG-029） ===== */
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
/* 成功打勾 */
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
/* 失败标记 */
.error-mark {
  width: 48px; height: 48px;
  border-radius: 50%;
  background: #e53e3e;
  color: #fff;
  font-size: 28px;
  font-weight: 800;
  display: flex; align-items: center; justify-content: center;
  animation: popIn .35s cubic-bezier(.34, 1.56, .64, 1);
}

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes scaleIn { from { opacity: 0; transform: scale(.88); } to { opacity: 1; transform: scale(1); } }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes popIn { from { transform: scale(0); } to { transform: scale(1); } }

@supports (height: 100dvh) {
  :deep(.employee-editor-modal) { height:min(760px,calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom))); max-height:calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom)); }
}

</style>
