<template>
  <view class="modal-mask" :class="maskClass" @click.self="closeOnMask && $emit('close')">
    <view class="modal-card" :class="sizeClass">
      <view v-if="closable" class="close-button" @click="$emit('close')">×</view>
      <slot />
    </view>
  </view>
</template>
<script setup>
defineProps({
  closable: Boolean,
  closeOnMask: Boolean,
  maskClass: { type: String, default: '' },
  sizeClass: { type: String, default: '' }
})
defineEmits(['close'])
</script>
<style scoped>
.modal-mask { position:fixed; inset:0; z-index:900; background:rgba(15,39,75,.52); display:flex; align-items:center; justify-content:center; padding:20px; box-sizing:border-box; }
.modal-mask.modal-operation-mask { align-items:flex-start; padding-top:max(10px, env(safe-area-inset-top)); background:transparent; pointer-events:none; }
.modal-card { position:relative; width:min(68vw,520px); min-width:min(88vw,300px); max-height:calc(100vh - 40px); overflow:auto; background:#fff; border-radius:clamp(12px,1.7vw,18px); box-shadow:0 10px 35px rgba(20,38,70,.18); }
.modal-card.modal-operation-card { width:min(calc(100vw - 24px),520px); min-width:0; max-height:none; overflow:visible; border:1px solid rgba(255,255,255,.72); border-radius:18px; background:rgba(255,255,255,.96); box-shadow:0 10px 30px rgba(17,44,84,.2), 0 0 0 1px rgba(31,118,255,.1); pointer-events:none; }
.modal-card.modal-compact { width:min(90vw,460px); min-width:0; }
.modal-card.modal-wide { width:min(88vw,720px); max-height:calc(100vh - 40px); }
.modal-card.modal-communication { width:min(94vw,980px); min-width:0; max-height:calc(100vh - 28px); }
.modal-card.modal-full { width:min(calc(100vw - 32px),1180px); min-width:0; height:min(820px,calc(100vh - 40px)); max-height:calc(100vh - 40px); overflow:hidden; }
.close-button { position:absolute; right:14px; top:14px; width:32px; height:32px; border:1px solid #cfd6df; border-radius:50%; color:#7a8798; background:#fff; display:flex; align-items:center; justify-content:center; font-size:24px; line-height:1; cursor:pointer; z-index:2; }
.close-button:active { background:#f1f4f7; }
@media (max-width:560px) { .modal-card { width:min(88vw,520px); } }
@supports (height: 100dvh) {
  .modal-card { max-height:calc(100dvh - 40px); }
  .modal-card.modal-wide { max-height:calc(100dvh - 40px); }
  .modal-card.modal-communication { max-height:calc(100dvh - 28px); }
  .modal-card.modal-full { height:min(820px,calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom))); max-height:calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom)); }
}
</style>
