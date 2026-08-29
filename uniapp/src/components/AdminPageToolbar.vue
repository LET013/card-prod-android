<template>
  <view class="admin-toolbar">
    <!-- 左侧：返回箭头 + 标题区域 -->
    <view class="toolbar-left" @click="$emit('back')">
      <view class="back-icon-wrap">
        <text class="back-icon">‹</text>
      </view>
      <view class="title-wrap">
        <text class="toolbar-title">{{ title }}</text>
        <text v-if="hint" class="toolbar-hint">{{ hint }}</text>
      </view>
    </view>

    <!-- 右侧操作区 -->
    <view v-if="hasActions" class="toolbar-right">
      <!-- 单 action 按钮（兼容旧 actionLabel） -->
      <view
        v-if="actionLabel && actionVisible"
        class="action-btn"
        :class="{ 'action-btn--disabled': actionDisabled }"
        @click="!actionDisabled && $emit('action')"
      >
        <text class="action-btn-text">{{ actionLabel }}</text>
      </view>

      <!-- 自定义 slot（兼容旧 #action 插槽） -->
      <slot name="action" />

      <!-- 更多菜单（actions 数组） -->
      <view v-if="actions && actions.length" class="more-wrap" @click.stop="toggleMenu">
        <text class="more-dots">···</text>
      </view>

      <!-- 下拉菜单 -->
      <view v-if="menuOpen" class="menu-backdrop" @click.stop="closeMenu" />
      <view v-if="menuOpen" class="menu-dropdown">
        <view
          v-for="(item, idx) in actions"
          :key="idx"
          class="menu-item"
          :class="{ 'menu-item--danger': item.danger }"
          @click.stop="handleMenuItem(item, idx)"
        >
          <IconGlyph v-if="item.icon" :name="item.icon" class="menu-item-icon" />
          <text class="menu-item-text">{{ item.label }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import IconGlyph from '@/components/IconGlyph.vue'

const props = defineProps({
  title: { type: String, required: true },
  hint: { type: String, default: '' },
  backLabel: { type: String, default: '返回' },
  actionLabel: { type: String, default: '' },
  actionVisible: { type: Boolean, default: true },
  actionDisabled: { type: Boolean, default: false },
  // 新增：多 action 数组，显示为 ··· 溢出菜单
  actions: { type: Array, default: () => [] }
  // actions item: { label: string, icon?: string, handler: function, danger?: boolean }
})

defineEmits(['back', 'action'])

const menuOpen = ref(false)

const hasActions = computed(() => {
  return (props.actionLabel && props.actionVisible) || (props.actions && props.actions.length)
})

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function closeMenu() {
  menuOpen.value = false
}

function handleMenuItem(item, idx) {
  closeMenu()
  if (typeof item.handler === 'function') {
    item.handler(item, idx)
  }
}
</script>

<style scoped>
.admin-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: clamp(50px, 8vh, 70px);
  padding: clamp(8px, 1.2vh, 14px) clamp(14px, 2.2vw, 24px);
  background: #fff;
  border-bottom: 1px solid #eceff1;
  flex: 0 0 auto;
  position: relative;
}

/* ===== 左侧返回区域 ===== */
.toolbar-left {
  display: flex;
  align-items: center;
  gap: clamp(6px, 1vw, 12px);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  min-width: 0;
  flex: 1;
}

.back-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: clamp(28px, 4.5vw, 38px);
  height: clamp(28px, 4.5vw, 38px);
  border-radius: 50%;
  background: #E8F1FE;
  flex-shrink: 0;
  transition: background 0.15s;
}
.toolbar-left:active .back-icon-wrap {
  background: #CDE2FF;
}

.back-icon {
  font-size: clamp(22px, 3.8vw, 30px);
  color: #1F76FF;
  line-height: 1;
  font-weight: 300;
  margin-bottom: 1px;
}

.title-wrap {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.toolbar-title {
  font-size: clamp(16px, 2.4vw, 22px);
  font-weight: 700;
  color: #263238;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toolbar-hint {
  font-size: clamp(11px, 1.6vw, 14px);
  color: #90a4ae;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== 右侧操作区 ===== */
.toolbar-right {
  display: flex;
  align-items: center;
  gap: clamp(6px, 1vw, 10px);
  flex-shrink: 0;
  position: relative;
}

/* 单 action 按钮 */
.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(6px, 1vh, 10px) clamp(14px, 2vw, 22px);
  border-radius: clamp(16px, 2.4vw, 22px);
  background: linear-gradient(135deg, #4AA3FF 0%, #1F76FF 48%, #0A53C4 100%);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: opacity 0.15s;
}
.action-btn:active {
  opacity: 0.85;
}
.action-btn--disabled {
  opacity: 0.45;
  pointer-events: none;
}

.action-btn-text {
  font-size: clamp(12px, 1.8vw, 15px);
  font-weight: 600;
  color: #fff;
  line-height: 1;
  white-space: nowrap;
}

/* 更多按钮 */
.more-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: clamp(30px, 5vw, 40px);
  height: clamp(30px, 5vw, 40px);
  border-radius: 50%;
  background: #E8F1FE;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: background 0.15s;
}
.more-wrap:active {
  background: #CDE2FF;
}

.more-dots {
  font-size: clamp(18px, 3vw, 24px);
  color: #1F76FF;
  line-height: 1;
  letter-spacing: 1px;
  font-weight: 700;
}

/* 菜单遮罩 */
.menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 99;
  background: transparent;
}

/* 下拉菜单 */
.menu-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: clamp(130px, 30vw, 180px);
  background: #fff;
  border-radius: clamp(8px, 1.2vw, 12px);
  box-shadow: 0 4px 24px rgba(0,0,0,0.14), 0 1px 3px rgba(0,0,0,0.08);
  z-index: 100;
  overflow: hidden;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: clamp(6px, 1vw, 10px);
  padding: clamp(10px, 1.6vh, 14px) clamp(12px, 1.8vw, 18px);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: background 0.12s;
  border-bottom: 1px solid #f5f5f5;
}
.menu-item:last-child {
  border-bottom: none;
}
.menu-item:active {
  background: #f5f7f8;
}
.menu-item--danger .menu-item-text {
  color: #e53935;
}

.menu-item-icon {
  width: clamp(14px, 2vw, 18px);
  height: clamp(14px, 2vw, 18px);
  flex-shrink: 0;
  color: #78909c;
}

.menu-item-text {
  font-size: clamp(13px, 1.9vw, 16px);
  color: #37474f;
  line-height: 1;
  white-space: nowrap;
}
</style>
