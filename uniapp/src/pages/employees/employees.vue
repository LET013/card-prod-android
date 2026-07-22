<template>
  <view class="page-root employees-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <view class="employees-content">
      <view class="search-wrap"><input v-model="query" class="search-input" placeholder="输入职员姓名或ID代码进行搜索" @input="search" /></view>
      <scroll-view class="employee-scroll" scroll-y>
        <view v-if="loading" class="state-text">正在加载人员……</view>
        <view v-else-if="!employees.length" class="state-text">没有匹配的人员</view>
        <view v-else class="employee-grid">
          <view v-for="employee in employees" :key="employee.id" class="employee-card" :class="{selected:selectedId===employee.id}" @click="selectedId=selectedId===employee.id?'':employee.id">
            <image class="avatar" :src="employee.avatarUrl" mode="aspectFill" />
            <view class="employee-copy"><text><b>姓名：</b>{{ employee.employeeName }}</text><text><b>代码：</b>{{ employee.employeeCode }}</text><view class="badge-row"><text :class="employee.faceRegistered?'ok':'muted'">人脸</text><text :class="employee.fingerprintRegistered?'ok':'muted'">指纹</text></view></view>
            <view v-if="selectedId===employee.id && canEdit" class="delete-overlay"><button class="delete-button" aria-label="删除人员" @click.stop="remove(employee)"><IconGlyph name="trash" /></button></view>
          </view>
        </view>
      </scroll-view>
      <view class="employee-actions"><BackButton @click="back" /></view>
    </view>
  </view>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import BackButton from '@/components/BackButton.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import { appState } from '@/state/appState.js'
import { ROLE_META, hasPermission } from '@/constants/app.js'
import { services } from '@/services/index.js'
const roleLabel=computed(()=>ROLE_META[appState.session?.role]?.label||'')
const canEdit=computed(()=>hasPermission(appState.session,'employee.edit'))
const query=ref('');const employees=ref([]);const loading=ref(false);const selectedId=ref('')
const search=async()=>{loading.value=true;try{employees.value=await services.searchEmployees(query.value)}finally{loading.value=false}}
onMounted(search)
const remove=(employee)=>uni.showModal({title:'删除人员',content:`确认删除 ${employee.employeeName}？`,success:async r=>{if(r.confirm){await services.deleteEmployee(employee.id);selectedId.value='';await search()}}})
const back=()=>uni.navigateBack();const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.employees-page { background: #e6f0ff; }
.employees-content { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: clamp(18px, 2.5vw, 34px) clamp(12px, 2vw, 26px) max(14px, env(safe-area-inset-bottom)); }
.search-wrap { background: #d3e5ff; border-radius: 14px; height: clamp(48px, 6.4vh, 64px); padding: 0 clamp(16px, 2.4vw, 28px); display: flex; align-items: center; }
.search-input { width: 100%; font-size: 16px; color: #3d4d62; }
.employee-scroll { flex: 1; min-height: 0; margin-top: 16px; }
.employee-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: clamp(10px, 1.8vw, 20px); }
.employee-card { position: relative; min-height: clamp(112px, 16vh, 180px); background: #fff; border-radius: 15px; padding: clamp(14px, 2vw, 24px); display: flex; align-items: center; gap: clamp(12px, 2vw, 24px); overflow: hidden; }
.employee-card.selected { background: #fff; outline: 2px solid #7aaeff; }
.avatar { width: clamp(62px, 9vw, 100px); height: clamp(62px, 9vw, 100px); border-radius: 50%; flex: 0 0 auto; }
.employee-copy { display: flex; flex-direction: column; gap: 10px; font-size: clamp(14px, 1.9vw, 17px); color: #333; min-width: 0; }
.employee-copy > text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.badge-row { display: flex; gap: 6px; }
.badge-row text { font-size: 11px; padding: 3px 7px; border-radius: 999px; }
.badge-row .ok { background: #dcf9e7; color: #06a843; }
.badge-row .muted { background: #edf0f4; color: #8d98a7; }
.delete-overlay { position: absolute; right: 12px; top: 12px; z-index: 2; }
.delete-button { width: 42px; height: 42px; border-radius: 50%; background: #ef4053; color: #fff; padding: 11px; display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 10px rgba(239,64,83,.25); }
.employee-actions { display: flex; align-items: center; justify-content: center; padding-top: 16px; }
.state-text { text-align: center; color: #728198; padding: 50px 0; font-size: 16px; }
@media (max-width: 430px) {
  .employees-content { padding-left: 9px; padding-right: 9px; }
  .employee-grid { gap: 8px; }
  .employee-card { padding: 10px; gap: 8px; min-height: 104px; }
  .avatar { width: 52px; height: 52px; }
  .employee-copy { font-size: 12px; gap: 7px; }
  .employee-actions :deep(.back-button) { width: clamp(170px, 46vw, 240px); }
}
</style>
