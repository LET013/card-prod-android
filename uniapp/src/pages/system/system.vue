<template>
  <view class="page-root system-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="system-grid">
        <AdminMenuCard v-for="item in visibleMenus" :key="item.key" :label="item.label" :icon="item.icon" :color="item.color" @click="open(item)" />
      </view>
    </scroll-view>
    <view class="back-wrap"><BackButton @click="back" /></view>
  </view>
</template>
<script setup>
import { computed } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminMenuCard from '@/components/AdminMenuCard.vue'
import BackButton from '@/components/BackButton.vue'
import { appState } from '@/state/appState.js'
import { ROLE_META, hasPermission } from '@/constants/app.js'
import { services } from '@/services/index.js'
const roleLabel=computed(()=>ROLE_META[appState.session?.role]?.label||'')
const menus=[
{key:'password',label:'修改密码',icon:'lock',color:'#ffc95b',permission:'auth.password.manage',url:'/pages/feature/feature?type=password'},
{key:'restart',label:'重启应用',icon:'refresh',color:'#37b8d7',permission:'app.restart',url:'/pages/feature/feature?type=restart'},
{key:'authorization',label:'系统授权',icon:'shield',color:'#2f67a8',permission:'authorization.manage',url:'/pages/feature/feature?type=authorization'},
{key:'engineering',label:'工程模式',icon:'tool',color:'#ef4053',permission:'debug.command',url:'/pages/engineering/engineering'},
{key:'face',label:'人脸注册',icon:'face',color:'#ff5f67',permission:'biometric.register',url:'/pages/biometric/face'},
{key:'finger',label:'指纹注册',icon:'fingerprint',color:'#4ecde9',permission:'biometric.register',url:'/pages/biometric/fingerprint'},
{key:'employees',label:'人员管理',icon:'people',color:'#64a7f4',permission:'employee.view',url:'/pages/employees/employees'},
{key:'units',label:'单元管理',icon:'device',color:'#5e55dd',permission:'unit.view',url:'/pages/feature/feature?type=units'},
{key:'history',label:'历史管理',icon:'history',color:'#ff8f31',permission:'history.view',url:'/pages/feature/feature?type=history'},
{key:'parameters',label:'参数设置',icon:'sliders',color:'#16db3b',permission:'settings.advanced',url:'/pages/feature/feature?type=parameters'},
{key:'device',label:'设备设置',icon:'device',color:'#586a9d',permission:'settings.basic',url:'/pages/config/config'},
{key:'engine',label:'激活引擎',icon:'engine',color:'#db61e8',permission:'engine.activate',url:'/pages/feature/feature?type=engine'}]
const visibleMenus=computed(()=>menus.filter(item=>hasPermission(appState.session,item.permission)))
const open=(item)=>uni.navigateTo({url:item.url})
const back=()=>uni.navigateBack()
const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.system-page { background: #e6f0ff; }
.system-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(clamp(128px, 20vw, 180px), 1fr)); gap: clamp(8px, 1.6vw, 18px); padding: clamp(16px, 2.5vw, 30px) clamp(10px, 1.8vw, 22px); }
.system-grid :deep(.menu-card) { min-height: clamp(92px, 14.5vw, 166px); }
.system-grid :deep(.icon-circle) { width: clamp(36px, 5vw, 56px); height: clamp(36px, 5vw, 56px); padding: clamp(8px, 1.1vw, 13px); }
.system-grid :deep(.menu-label) { margin-top: clamp(9px, 1.4vh, 16px); font-size: clamp(13px, 1.8vw, 17px); }
.back-wrap { flex: 0 0 auto; padding: 10px 0 max(12px, env(safe-area-inset-bottom)); background: #e6f0ff; }
@media (max-width: 560px) { .system-grid { gap: 8px; padding-top: 12px; }.system-grid :deep(.menu-card) { min-height: 94px; } }
</style>
