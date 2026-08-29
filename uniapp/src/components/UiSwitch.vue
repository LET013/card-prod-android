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
.ui-switch { width:42px; height:24px; border-radius:999px; background:#d8dce2; padding:2px; box-sizing:border-box; cursor:pointer; flex:0 0 auto; box-shadow:inset 0 1px 2px rgba(35,48,69,.14); transition:background .16s ease, box-shadow .16s ease; }
.thumb { width:20px; height:20px; border-radius:50%; background:#fff; box-shadow:0 1px 3px rgba(20,35,60,.28); transition:transform .16s ease; }
.ui-switch.on { background:#1f76ff; box-shadow:inset 0 1px 2px rgba(8,56,130,.16); }
.ui-switch.on .thumb { transform:translateX(18px); }
.ui-switch.disabled { opacity:.55; cursor:default; }
</style>
