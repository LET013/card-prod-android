<template>
  <view
    class="cabinet-slot-skeleton-host"
    aria-busy="true"
    aria-label="正在读取卡柜状态"
  >
    <view class="cabinet-slot-skeleton-grid" aria-hidden="true"></view>
  </view>
</template>

<script setup>
defineProps({
  totalSlots: { type: [Number, String], default: 0 },
  groupSize: { type: [Number, String], default: 0 },
  sortDirection: { type: String, default: 'HORIZONTAL' }
})
</script>

<style scoped>
.cabinet-slot-skeleton-host { flex: 1; min-height: 0; overflow: hidden; background: #e6f0ff; }
.cabinet-slot-skeleton-grid {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  background-color: #e6f0ff;
  background-image:
    linear-gradient(110deg, transparent 32%, rgba(255,255,255,.54) 47%, transparent 62%),
    repeating-linear-gradient(0deg, rgba(209,224,242,.42) 0 1px, transparent 1px clamp(46px, 7vw, 72px));
  background-position: 200% 0, 0 0;
  background-size: 230% 100%, 100% 100%;
  background-repeat: no-repeat, repeat;
  animation: cabinet-skeleton-shimmer 1.5s ease-in-out infinite;
}
@keyframes cabinet-skeleton-shimmer { to { background-position: -130% 0, 0 0; } }
@media (prefers-reduced-motion: reduce) { .cabinet-slot-skeleton-grid { animation: none; } }
</style>
