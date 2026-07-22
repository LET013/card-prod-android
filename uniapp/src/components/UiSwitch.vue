<template>
  <view class="ui-switch" :class="{ on: modelValue, disabled }" @click.stop="toggle">
    <view class="thumb"></view>
  </view>
</template>
<script setup>
const props = defineProps({ modelValue: Boolean, disabled: Boolean })
const emit = defineEmits(['update:modelValue', 'change'])
const toggle = () => {
  if (props.disabled) return
  const value = !props.modelValue
  emit('update:modelValue', value)
  emit('change', value)
}
</script>
<style scoped>
.ui-switch { width:clamp(36px,4.2vw,46px); height:clamp(20px,2.3vw,25px); border-radius:999px; background:#d8d8d8; padding:2px; box-sizing:border-box; transition:.18s; cursor:pointer; flex:0 0 auto; }
.thumb { height:100%; aspect-ratio:1; border-radius:50%; background:#fff; box-shadow:0 1px 3px rgba(0,0,0,.18); transition:.18s transform; }
.ui-switch.on { background:#14e76d; }
.ui-switch.on .thumb { transform:translateX(calc(clamp(36px,4.2vw,46px) - clamp(20px,2.3vw,25px))); }
.ui-switch.disabled { opacity:.55; }
</style>
