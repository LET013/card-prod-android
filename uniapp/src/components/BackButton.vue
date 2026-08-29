<template><button class="back-button" @click="$emit('click')">{{ label }}</button></template>
<script setup>defineProps({ label: { type: String, default: '返回上一页' } });defineEmits(['click'])</script>
<style scoped>
.back-button { width: clamp(170px, 30vw, 300px); height: clamp(52px, 7vh, 76px); margin: 0 auto; border: 1px solid #d4dbe5; border-radius: clamp(12px,1.8vw,19px); background: linear-gradient(180deg,#fff,#f5f8fa); color: #34373d; font-size: clamp(16px,2vw,19px); display: flex; align-items: center; justify-content: center; box-shadow: 0 2px 6px rgba(50,70,100,.08); }
.back-button::after { border: none; }
</style>
