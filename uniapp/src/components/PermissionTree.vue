<template>
  <view class="permission-tree">
    <view v-for="root in roots" :key="root.permissionKey" class="tree-root">
      <view class="tree-row root-row" @click="toggleExpanded(root.permissionKey)">
        <text class="tree-arrow">{{ expanded.has(root.permissionKey) ? '⌄' : '›' }}</text>
        <button class="tree-check" :class="checkClass(root)" @click.stop="toggle(root)">{{ checkMark(root) }}</button>
        <text class="tree-name" @click.stop="toggle(root)">{{ root.permissionName }}</text>
      </view>
      <view v-if="expanded.has(root.permissionKey)" class="tree-children">
        <template v-for="branch in root.children" :key="branch.permissionKey">
          <view class="tree-row branch-row" @click="branch.children.length && toggleExpanded(branch.permissionKey)">
            <text class="tree-arrow">{{ branch.children.length ? (expanded.has(branch.permissionKey) ? '⌄' : '›') : '' }}</text>
            <button class="tree-check" :class="checkClass(branch)" @click.stop="toggle(branch)">{{ checkMark(branch) }}</button>
            <text class="tree-name" @click.stop="toggle(branch)">{{ branch.permissionName }}</text>
          </view>
          <view v-if="branch.children.length && expanded.has(branch.permissionKey)" class="tree-leaves">
            <view v-for="leaf in branch.children" :key="leaf.permissionKey" class="tree-row leaf-row">
              <text class="tree-arrow" />
              <button class="tree-check" :class="checkClass(leaf)" @click="toggle(leaf)">{{ checkMark(leaf) }}</button>
              <view class="tree-copy" @click="toggle(leaf)">
                <text class="tree-name">{{ leaf.permissionName }}</text>
                <text class="tree-code">{{ leaf.permissionKey }}</text>
              </view>
            </view>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  permissions: { type: Array, default: () => [] },
  modelValue: { type: [Array, Set], default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const byKey = computed(() => new Map(props.permissions.filter((item) => item?.enabled).map((item) => [item.permissionKey, { ...item, children: [] }])))
const roots = computed(() => {
  const nodes = byKey.value
  for (const node of nodes.values()) {
    if (node.parentKey && nodes.has(node.parentKey)) nodes.get(node.parentKey).children.push(node)
  }
  const sorted = (items) => items.sort((a, b) => Number(a.sortOrder || 0) - Number(b.sortOrder || 0) || a.permissionKey.localeCompare(b.permissionKey))
  for (const node of nodes.values()) sorted(node.children)
  return sorted(Array.from(nodes.values()).filter((node) => node.parentKey === '*'))
})
const expanded = ref(new Set(['account.*', 'system.*', 'maintenance.*', 'realtime.*']))
const selected = computed(() => new Set(props.modelValue instanceof Set ? props.modelValue : props.modelValue || []))

function descendants(node, result = []) {
  result.push(node)
  for (const child of node.children || []) descendants(child, result)
  return result
}
function leaves(node) {
  const all = descendants(node, [])
  return all.filter((item) => !item.children?.length).map((item) => item.permissionKey)
}
function effectiveKeys() {
  const result = new Set()
  for (const key of selected.value) {
    const node = byKey.value.get(key)
    if (!node) continue
    descendants(node, []).forEach((item) => result.add(item.permissionKey))
  }
  return result
}
function stateFor(node) {
  const leafKeys = leaves(node)
  const effective = effectiveKeys()
  const count = leafKeys.filter((key) => effective.has(key)).length
  return { checked: leafKeys.length > 0 && count === leafKeys.length, partial: count > 0 && count < leafKeys.length }
}
function checkClass(node) {
  const state = stateFor(node)
  return { checked: state.checked, partial: state.partial }
}
function checkMark(node) {
  const state = stateFor(node)
  return state.checked ? '✓' : (state.partial ? '−' : '')
}
function materializeSelectedAncestors(node, next) {
  let current = node
  while (current.parentKey && byKey.value.has(current.parentKey)) {
    current = byKey.value.get(current.parentKey)
    if (!next.has(current.permissionKey)) continue
    next.delete(current.permissionKey)
    leaves(current).forEach((key) => next.add(key))
  }
}
function toggle(node) {
  const next = new Set(selected.value)
  materializeSelectedAncestors(node, next)
  const state = stateFor(node)
  const branchKeys = new Set(descendants(node, []).map((item) => item.permissionKey))
  if (state.checked) {
    branchKeys.forEach((key) => next.delete(key))
  } else {
    branchKeys.forEach((key) => next.delete(key))
    next.add(node.permissionKey)
  }
  emit('update:modelValue', Array.from(next))
}
function toggleExpanded(key) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}
</script>

<style scoped>
.permission-tree { margin-top:12px; border:1px solid #dfe7f2; border-radius:8px; overflow:hidden; background:#fff; }
.tree-root + .tree-root { border-top:1px solid #edf1f6; }
.tree-row { min-height:36px; display:flex; align-items:center; gap:7px; padding:0 10px; box-sizing:border-box; color:#3e4a5c; }
.root-row { background:#f7f9fc; font-weight:700; }
.branch-row { padding-left:27px; font-weight:600; }
.leaf-row { padding-left:49px; align-items:flex-start; padding-top:6px; padding-bottom:6px; }
.tree-arrow { width:12px; color:#9aa8bb; font-size:15px; line-height:1; text-align:center; }
.tree-check { width:17px; height:17px; min-width:17px; margin:0; padding:0; display:flex; align-items:center; justify-content:center; border:1px solid #cbd5e1; border-radius:3px; background:#fff; color:#fff; font-size:13px; line-height:1; }
.tree-check::after { border:0; }
.tree-check.checked { border-color:#1f76ff; background:#1f76ff; }
.tree-check.partial { border-color:#1f76ff; background:#eaf3ff; color:#1f76ff; }
.tree-name { min-width:0; color:#3d495b; font-size:14px; line-height:1.35; }
.tree-copy { min-width:0; flex:1; }
.tree-code { display:block; margin-top:2px; color:#9aa6b8; font-size:11px; line-height:1.25; }
</style>
