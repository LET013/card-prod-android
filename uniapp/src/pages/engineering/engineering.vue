<template>
  <view class="page-root engineering-page">
    <AdminHeader :role-label="roleLabel" @exit="exitAdmin" />
    <scroll-view class="page-scroll" scroll-y>
      <view class="engineering-grid">
        <AdminMenuCard v-for="item in menus" :key="item.key" :label="item.label" :icon="item.icon" :color="item.color" @click="run(item)" />
      </view>
      <view class="back-wrap"><BackButton @click="back" /></view>
    </scroll-view>

    <ModalShell v-if="upgradeVisible" closable close-on-mask @close="upgradeVisible=false">
      <view class="upgrade-card">
        <text class="upgrade-title">升级文件列表</text>
        <view v-for="file in files" :key="file.id" class="file-row" @click="selectedFile=file.id">
          <view class="radio" :class="{selected:selectedFile===file.id}"></view>
          <view class="file-copy"><text>{{ file.fileName }}　{{ file.createdAt }}</text><text>{{ file.versionName }}</text></view>
        </view>
        <view v-if="progress>0" class="progress-wrap"><view class="progress-bar" :style="{width:`${progress}%`}"></view><text>{{ progress }}%</text></view>
        <button class="primary-gradient-button upgrade-button" :disabled="upgrading" @click="startUpgrade">{{ upgrading?'升级中':'马上升级' }}</button>
      </view>
    </ModalShell>

    <ModalShell v-if="infoVisible" closable close-on-mask @close="infoVisible=false">
      <view class="info-card"><view class="info-icon"><IconGlyph :name="info.icon" /></view><text class="info-title">{{ info.title }}</text><text class="info-copy">{{ info.content }}</text><button class="primary-gradient-button info-button" @click="infoVisible=false">确定</button></view>
    </ModalShell>
  </view>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue';import AdminMenuCard from '@/components/AdminMenuCard.vue';import BackButton from '@/components/BackButton.vue';import ModalShell from '@/components/ModalShell.vue';import IconGlyph from '@/components/IconGlyph.vue'
import { appState } from '@/state/appState.js';import { ROLE_META } from '@/constants/app.js';import { services } from '@/services/index.js'
const roleLabel=computed(()=>ROLE_META[appState.session?.role]?.label||'')
const menus=[
{key:'board',label:'单板升级',icon:'upgrade',color:'#5b9cf2'},
{key:'app',label:'APP升级',icon:'upgrade',color:'#596fe9'},
{key:'unlockAll',label:'一键弹出',icon:'card',color:'#4f9ae9'},
{key:'hardware',label:'硬件版本号',icon:'list',color:'#5b8fe5'},
{key:'workCard',label:'工作卡升级',icon:'upgrade',color:'#65a0f2'},
{key:'command',label:'指令验证',icon:'device',color:'#507be5'},
{key:'mainBoard',label:'主板升级',icon:'upgrade',color:'#5b8fe5'}]
const upgradeVisible=ref(false),infoVisible=ref(false),files=ref([]),selectedFile=ref(''),progress=ref(0),upgrading=ref(false)
const info=reactive({title:'',content:'',icon:'device'})
onMounted(async()=>{files.value=await services.getUpgradeFiles();selectedFile.value=files.value[0]?.id||''})
const run=async(item)=>{
 if(['board','app','workCard','mainBoard'].includes(item.key)){upgradeVisible.value=true;return}
 if(item.key==='unlockAll'){uni.showModal({title:'一键弹出',content:'确认模拟打开所有卡门？',success:async r=>{if(r.confirm){const result=await services.unlockAllDoors();uni.showToast({title:`成功 ${result.successCount} 个`,icon:'none'})}}});return}
 if(item.key==='hardware'){Object.assign(info,{title:'硬件版本号',content:'主板 V1.5 · 单板 V2.1 · 模拟环境',icon:'list'});infoVisible.value=true;return}
 if(item.key==='command'){uni.navigateTo({url:'/pages/serial-demo/serial-demo'})}
}
const startUpgrade=async()=>{if(!selectedFile.value){uni.showToast({title:'请选择升级文件',icon:'none'});return}upgrading.value=true;progress.value=1;try{await services.startUpgrade(selectedFile.value,v=>progress.value=v);uni.showToast({title:'升级完成',icon:'success'});setTimeout(()=>{upgradeVisible.value=false;progress.value=0},700)}finally{upgrading.value=false}}
const back=()=>uni.navigateBack();const exitAdmin=async()=>{await services.logout();uni.reLaunch({url:'/pages/index/index'})}
</script>
<style scoped>
.engineering-page { background: #e6f0ff; }
.engineering-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(clamp(128px, 27vw, 216px), 1fr)); gap: clamp(9px, 1.8vw, 20px); padding: clamp(22px, 3.5vw, 44px) clamp(10px, 1.8vw, 22px); }
.engineering-grid :deep(.menu-card) { min-height: clamp(96px, 15vw, 174px); }
.engineering-grid :deep(.icon-circle) { width: clamp(38px, 5.2vw, 58px); height: clamp(38px, 5.2vw, 58px); padding: clamp(8px, 1.2vw, 14px); }
.engineering-grid :deep(.menu-label) { margin-top: clamp(10px, 1.5vh, 17px); font-size: clamp(14px, 1.85vw, 17px); }
.back-wrap { padding: clamp(28px, 5vh, 66px) 0 max(24px, env(safe-area-inset-bottom)); }
.upgrade-card { padding: 30px; }
.upgrade-title { display: block; text-align: center; font-size: 20px; font-weight: 600; margin-bottom: 18px; }
.file-row { display: flex; align-items: flex-start; gap: 12px; padding: 15px 2px; border-bottom: 1px solid #e5ebf3; cursor: pointer; }
.radio { width: 17px; height: 17px; border: 2px solid #9babc0; border-radius: 50%; margin-top: 3px; flex: 0 0 auto; }
.radio.selected { border-color: #1f76ff; box-shadow: inset 0 0 0 4px white; background: #1f76ff; }
.file-copy { display: flex; flex-direction: column; gap: 6px; font-size: 14px; }
.file-copy text:last-child { color: #6f7e92; }
.upgrade-button { height: 52px; margin-top: 22px; width: 100%; }
.progress-wrap { height: 22px; border-radius: 999px; background: #e7eef8; margin-top: 20px; position: relative; overflow: hidden; }
.progress-bar { height: 100%; background: linear-gradient(90deg,#7ef8ad,#00dada); }
.progress-wrap text { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 12px; }
.info-card { padding: 30px; display: flex; flex-direction: column; align-items: center; }
.info-icon { width: 64px; height: 64px; border-radius: 50%; background: #1f76ff; color: #fff; padding: 15px; }
.info-title { font-size: 20px; font-weight: 600; margin-top: 18px; }
.info-copy { font-size: 14px; color: #65758b; margin-top: 12px; text-align: center; }
.info-button { width: 100%; height: 52px; margin-top: 22px; }
</style>
