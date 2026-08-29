<template>
  <view
    class="legend-grid"
    :class="{ 'with-separator': separator && Number(columns) === 2 }"
    :style="{ '--legend-columns': columns }"
  >
    <view
      v-for="item in items"
      :key="item.key"
      class="legend-item"
    >
      <text class="legend-name">{{ item.label }}</text>
      <view class="legend-track">
        <view class="legend-fill" :style="{ background: item.gradient || item.color, width: barWidth(item.count) }"></view>
      </view>
      <text class="legend-count">{{ item.count }}</text>
    </view>
  </view>
</template>
<script setup>
const props = defineProps({
  items: { type: Array, default: () => [] },
  total: { type: Number, default: 1 },
  columns: { type: Number, default: 2 },
  separator: { type: Boolean, default: false }
})

const barWidth = (count) => `${Math.max(8, Math.min(100, (Number(count) / Math.max(1, props.total)) * 100))}%`


</script>
<style scoped>
.legend-grid {
  /*
   * The PDF uses an 800px-wide reference canvas. Every size below scales with
   * viewport width and stops growing at the reference value, so a narrow phone
   * keeps the same visual proportion instead of rendering desktop-sized text.
   */
  --legend-track-width: clamp(56px, 12.5vw, 100px);
  --legend-track-height: clamp(7px, 1.4vw, 11px);
  --legend-count-width: clamp(16px, 2.75vw, 24px);
  --legend-label-gap: clamp(4px, 1vw, 8px);
  --legend-count-gap: clamp(4px, .9vw, 7px);
  --legend-font-size: clamp(14px, 2.35vw, 21px);
  --legend-row-gap: clamp(6px, 1.3vw, 11px);
  position: relative;
  display: grid;
  /* One shared label, track and count column keeps all three tracks aligned. */
  grid-template-columns: max-content var(--legend-track-width) var(--legend-count-width);
  column-gap: 0;
  row-gap: var(--legend-row-gap);
  align-content: center;
  justify-content: start;
  width: max-content;
  max-width: 100%;
}
.legend-grid.with-separator::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 2px;
  bottom: 2px;
  width: 1px;
  background: rgba(255,255,255,.16);
  transform: translateX(-.5px);
}
.legend-item { display: contents; }
.legend-name,
.legend-count {
  color: #fff;
  font-size: var(--legend-font-size);
  line-height: 1.1;
  white-space: nowrap;
}
.legend-name {
  margin-right: var(--legend-label-gap);
  text-align: left;
}
.legend-track {
  width: var(--legend-track-width);
  height: var(--legend-track-height);
  align-self: center;
  border-radius: 999px;
  background: rgba(210,231,255,.48);
  overflow: hidden;
}
.legend-fill {
  height: 100%;
  min-width: clamp(4px, .9vw, 7px);
  border-radius: 999px;
}
.legend-count {
  width: var(--legend-count-width);
  margin-left: var(--legend-count-gap);
  text-align: left;
}
</style>
