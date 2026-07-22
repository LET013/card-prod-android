<template>
  <view class="modal-mask" @click.self="closeOnMask && $emit('close')">
    <view class="modal-card" :class="sizeClass">
      <view v-if="closable" class="close-button" @click="$emit('close')">×</view>
      <slot />
    </view>
  </view>
</template>
<script setup>
defineProps({ closable: Boolean, closeOnMask: Boolean, sizeClass: { type:String, default:'' } })
defineEmits(['close'])
</script>
<style scoped>
.modal-mask { position:fixed; inset:0; z-index:1000; background:rgba(15,39,75,.52); display:flex; align-items:center; justify-content:center; padding:20px; box-sizing:border-box; }
.modal-card { position:relative; width:min(68vw,520px); min-width:min(88vw,300px); max-height:88dvh; overflow:auto; background:#fff; border-radius:clamp(12px,1.7vw,18px); box-shadow:0 10px 35px rgba(20,38,70,.18); }
.close-button { position:absolute; right:14px; top:10px; width:34px; height:34px; border:2px solid #aaa; border-radius:50%; color:#999; display:flex; align-items:center; justify-content:center; font-size:26px; line-height:1; cursor:pointer; z-index:2; }
@media (max-width:560px) { .modal-card { width:min(88vw,520px); } }
</style>
