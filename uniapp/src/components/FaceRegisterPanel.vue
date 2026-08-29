<template>
  <view>
    <scroll-view class="page-scroll" scroll-y>
      <view class="form-panel">
        <view class="panel-heading">
          <view class="top-icon face"><IconGlyph name="face" /></view>
          <text class="form-title">人脸注册</text>
        </view>
        <view class="divider"></view>
        <view class="sync-row">
          <text class="sync-status">{{ employeeStatusText }}</text>
          <button v-permission="'system.face.sync'" class="refresh-button" :disabled="syncLoading" @click="refreshEmployees">手动同步</button>
        </view>
        <view class="field compact">
          <text class="field-label">员工查询</text>
          <view class="search-box">
            <input class="field-input search-input" v-model="employeeKeyword" placeholder="输入姓名或工号" confirm-type="search" @confirm="searchEmployee" />
            <button v-permission="'system.face.search'" class="search-button" :disabled="cacheLoading" @click="searchEmployee">搜索</button>
          </view>
          <view v-if="searchResults.length" class="result-list">
            <view v-for="employee in searchResults" :key="employee.employeeId" class="result-item" @click="pickEmployee(employee)">
              <text class="result-name">{{ employee.employeeName || '未命名' }}</text>
              <text class="result-meta">工号：{{ employee.employeeCode || '-' }}</text>
            </view>
          </view>
          <text v-else-if="searchTouched && employeeKeyword.trim()" class="result-empty">未找到匹配员工</text>
        </view>
        <view v-if="selectedEmployee" class="field">
          <text class="field-label">选择职员 <text class="required-mark">*</text></text>
          <view class="field-input selector readonly">
            {{ `${selectedEmployee.employeeName || '未命名'}（工号：${selectedEmployee.employeeCode || '-'}）` }}
          </view>
        </view>
        <view v-if="selectedEmployee" class="field">
          <text class="field-label">已录入人脸</text>
          <view class="face-summary">
            <text>{{ faceSummaryText }}</text>
          </view>
        </view>
        <button
          v-permission="'system.face.register'"
          class="primary-gradient-button submit-button"
          :disabled="cacheLoading || faceLoading || !selectedEmployee"
          @click="register"
        >{{ hasRegisteredFace ? '重新录入人脸' : '添加人脸' }}</button>
      </view>
    </scroll-view>
    <RecognitionModal
      v-if="process.visible"
      type="FACE"
      :status="process.status"
      :success-text="process.successText"
      :success-hint="process.successHint"
      :success-action-text="process.successActionText"
      @finish="finishProcess"
      @cancel="closeProcess"
    />
  </view>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import IconGlyph from '@/components/IconGlyph.vue'
import RecognitionModal from '@/components/RecognitionModal.vue'
import { services } from '@/services/index.js'
import { appState } from '@/state/appState.js'
import { toUserErrorMessage } from '@/utils/userMessage.js'

const props = defineProps({ initialEmployeeId: { type: String, default: '' } })
const emit = defineEmits(['done'])

const employees = ref([])
const selectedEmployeeId = ref('')
const employeeKeyword = ref('')
const searchResults = ref([])
const searchTouched = ref(false)
const cacheLoading = ref(false)
const syncLoading = ref(false)
const faceLoading = ref(false)
const employeeFaces = ref([])
const process = reactive({
  visible: false,
  status: 'PREPARING',
  successText: '添加成功',
  successHint: '服务器已添加，退出管理员模式后同步到本机',
  successActionText: '完成'
})

const selectedEmployee = computed(() => (
  employees.value.find(item => String(item.employeeId) === String(selectedEmployeeId.value)) || null
))
// 服务器已确认但尚未增量同步的人脸立即计入展示，并与本机已同步数量明确区分。
const pendingFaces = computed(() => {
  const employeeId = String(selectedEmployeeId.value || '')
  const syncedHashes = new Set(employeeFaces.value.map(item => String(item?.fileHash || '')).filter(Boolean))
  return (appState.faceSyncPending || []).filter(item => (
    String(item?.employeeId || '') === employeeId
      && (!item?.fileHash || !syncedHashes.has(String(item.fileHash)))
  ))
})
const displayedFaceCount = computed(() => employeeFaces.value.length + pendingFaces.value.length)
const hasRegisteredFace = computed(() => (
  selectedEmployee.value?.faceRegistered === true || displayedFaceCount.value > 0
))
const faceSummaryText = computed(() => {
  if (faceLoading.value) return '正在读取…'
  if (displayedFaceCount.value < 1) return '暂未录入'
  if (pendingFaces.value.length > 0) {
    return `已添加 ${displayedFaceCount.value} 张（${pendingFaces.value.length} 张待退出后同步）`
  }
  return `已录入 ${employeeFaces.value.length} 张`
})
const employeeStatusText = computed(() => {
  if (cacheLoading.value) return '正在加载人员'
  if (syncLoading.value) return '正在更新人员'
  return `已加载 ${employees.value.length} 名员工`
})
const employeeFields = item => [
  item.employeeName,
  item.employeeCode
].map(value => String(value || '').trim()).filter(Boolean)

const matchEmployees = keyword => {
  const normalized = keyword.trim().toLowerCase()
  if (!normalized) return []
  const exact = employees.value.filter(item => (
    employeeFields(item).some(value => value.toLowerCase() === normalized)
  ))
  if (exact.length) return exact
  return employees.value.filter(item => (
    employeeFields(item).some(value => value.toLowerCase().includes(normalized))
  ))
}

const loadEmployeeFaces = async (employeeId) => {
  const targetId = String(employeeId || '')
  employeeFaces.value = []
  if (!targetId) {
    faceLoading.value = false
    return
  }
  faceLoading.value = true
  try {
    const faces = await services.listEmployeeFaces(targetId)
    if (String(selectedEmployeeId.value) === targetId) {
      employeeFaces.value = Array.isArray(faces) ? faces : []
    }
  } catch {
    if (String(selectedEmployeeId.value) === targetId) {
      employeeFaces.value = []
    }
  } finally {
    if (String(selectedEmployeeId.value) === targetId) {
      faceLoading.value = false
    }
  }
}

const pickEmployee = async employee => {
  selectedEmployeeId.value = employee?.employeeId || ''
  searchResults.value = []
  searchTouched.value = false
  await loadEmployeeFaces(selectedEmployeeId.value)
}

const searchEmployee = async () => {
  const keyword = employeeKeyword.value.trim()
  searchTouched.value = true
  searchResults.value = []
  if (!keyword) {
    uni.showToast({ title: '请输入姓名或工号', icon: 'none' })
    return
  }
  const matches = matchEmployees(keyword)
  if (matches.length === 1) {
    await pickEmployee(matches[0])
    uni.showToast({ title: '已匹配员工', icon: 'success' })
    return
  }
  searchResults.value = matches
}

const loadLocalEmployees = async () => {
  cacheLoading.value = true
  try {
    employees.value = await services.searchEmployees('')
    searchResults.value = []
    searchTouched.value = false
    const initialEmployee = employees.value.find(item => (
      String(item.employeeId) === String(props.initialEmployeeId)
    ))
    selectedEmployeeId.value = initialEmployee?.employeeId || (
      employees.value.length === 1 ? employees.value[0].employeeId : ''
    )
    employeeKeyword.value = initialEmployee?.employeeName || initialEmployee?.employeeCode || ''
    await loadEmployeeFaces(selectedEmployeeId.value)
  } catch (error) {
    employees.value = []
    selectedEmployeeId.value = ''
    uni.showToast({ title: toUserErrorMessage(error, '人员加载失败'), icon: 'none' })
  } finally {
    cacheLoading.value = false
  }
}

const refreshEmployees = async () => {
  syncLoading.value = true
  const previousId = selectedEmployee.value?.employeeId || ''
  try {
    employees.value = await services.syncEmployees({
      full: true,
      source: 'FACE_EMPLOYEE_REFRESH'
    })
    searchResults.value = []
    searchTouched.value = false
    if (!employees.value.length) {
      uni.showToast({ title: '暂无可用人员', icon: 'none' })
    }
  } catch (error) {
    try {
      employees.value = await services.searchEmployees('')
    } catch {
      employees.value = []
    }
    uni.showToast({ title: toUserErrorMessage(error, '人员同步失败'), icon: 'none' })
  } finally {
    const matched = employees.value.find(item => String(item.employeeId) === String(previousId))
    selectedEmployeeId.value = matched?.employeeId || (
      employees.value.length === 1 ? employees.value[0].employeeId : ''
    )
    await loadEmployeeFaces(selectedEmployeeId.value)
    syncLoading.value = false
  }
}

const closeProcess = () => {
  if (process.status === 'UPLOADING') return
  process.visible = false
  emit('done')
}

const applyEnrollmentResult = () => {
  process.successText = '添加成功'
  process.successHint = '服务器已添加，退出管理员模式后同步到本机'
  process.successActionText = '完成'
  process.status = 'SUCCESS'
}

const finishProcess = () => closeProcess()

const register = async () => {
  const employee = selectedEmployee.value
  if (!employee) {
    uni.showToast({ title: '请先选择人员', icon: 'none' })
    return
  }
  Object.assign(process, {
    visible: true,
    status: 'PREPARING',
    successText: '添加成功',
    successHint: '服务器已添加，退出管理员模式后同步到本机',
    successActionText: '完成'
  })
  try {
    const result = await services.registerBiometric(
      'FACE',
      { employeeId: employee.employeeId, employeeName: employee.employeeName },
      status => {
        process.status = status
      }
    )
    if (!result?.accepted || result?.serverAccepted !== true) {
      throw new Error(result?.message || '添加人脸失败')
    }
    applyEnrollmentResult()
  } catch (error) {
    process.visible = false
    console.warn('[face-enrollment] 添加人脸失败:', toUserErrorMessage(error, '添加人脸失败'))
    uni.showToast({ title: '添加人脸失败', icon: 'none' })
  }
}

onMounted(loadLocalEmployees)
</script>
<style scoped>
.page-scroll { flex: 1; }
.form-panel { width: min(92%, 680px); margin: 0 auto; padding: 28px 36px 32px; box-sizing: border-box; background: #fff; border-radius: 16px; display: flex; flex-direction: column; align-items: center; }
.panel-heading { width: min(100%, 560px); display: flex; align-items: center; gap: 14px; padding-right: 38px; box-sizing: border-box; }
.top-icon { width: 56px; height: 56px; flex: 0 0 auto; padding: 13px; box-sizing: border-box; border-radius: 12px; color: #fff; }
.top-icon.face { background: #ff5f67; }
.form-title { color: #1f2b3d; font-size: 20px; font-weight: 700; line-height: 1.3; }
.divider { width: min(100%, 560px); height: 1px; margin: 18px 0 22px; background: #e5e9ef; }
.field { width: min(100%, 560px); margin-bottom: 20px; }
.field.compact { margin-bottom: 18px; }
.field-label { display: block; font-size: 16px; font-weight: 500; margin-bottom: 13px; }
.field-input { height: 56px; box-sizing: border-box; background: #fff; border: 1px solid #b8c7da; border-radius: 12px; padding: 0 18px; font-size: 16px; color: #555; }
.required-mark { color: #ef4053; }
.search-box { width: 100%; display: grid; grid-template-columns: minmax(0, 1fr) 96px; gap: 10px; align-items: center; }
.search-input { width: 100%; box-sizing: border-box; }
.search-button { width: 96px; height: 56px; line-height: 56px; margin: 0; padding: 0; border-radius: 12px; background: #eef5ff; color: #2878ff; font-size: 15px; }
.search-button:after { border: 0; }
.result-list { width: 100%; margin-top: 10px; display: grid; gap: 8px; }
.result-item { min-height: 56px; box-sizing: border-box; padding: 10px 14px; border: 1px solid #dce8fb; border-radius: 12px; background: #fbfdff; }
.result-name { display: block; font-size: 15px; font-weight: 650; color: #23344d; }
.result-meta { display: block; margin-top: 5px; font-size: 12px; color: #6d7b8f; }
.result-empty { display: block; margin-top: 10px; font-size: 13px; color: #ef4053; }
.field-input.selector { display: flex; align-items: center; }
.field-input.placeholder { color: #9aa4b2; }
.field-input.readonly { color: #667085; background: #f3f6fa; border-style: dashed; border-color: #c5cfdb; }
.face-summary { min-height: 56px; box-sizing: border-box; padding: 14px 18px; border-radius: 12px; background: #f5f8fc; color: #536174; font-size: 14px; }
.face-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.face-tag { padding: 5px 10px; border-radius: 999px; background: #e9f2ff; color: #2878ff; font-size: 13px; }
.face-empty { display: block; margin-top: 6px; color: #8995a6; }
.sync-row { width: min(100%, 560px); display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.sync-status { color: #667085; font-size: 14px; }
.refresh-button { width: auto; min-width: 96px; height: 40px; line-height: 40px; margin: 0; padding: 0 16px; border-radius: 6px; background: #eef5ff; color: #2878ff; font-size: 14px; }
.refresh-button:after { border: 0; }
.submit-button { width: min(100%, 560px); height: 56px; margin-top: 4px; }
@media (max-width: 540px) {
  .form-panel { width: 100%; padding: 24px 20px 26px; border-radius: 0; }
  .panel-heading { padding-right: 34px; }
  .top-icon { width: 50px; height: 50px; padding: 12px; }
  .form-title { font-size: 18px; }
  .search-box { grid-template-columns: minmax(0, 1fr) 82px; gap: 8px; }
  .search-button { width: 82px; }
}
</style>
