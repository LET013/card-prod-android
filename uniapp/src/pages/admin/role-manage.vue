<!--
  role-manage.vue — 角色管理独立页面（二级密码保护）
  列表查看角色，右侧抽屉展示详情，弹窗新增/编辑自定义角色
-->
<template>
  <view class="page-root">
    <!-- 二级密码弹窗 -->
    <PasswordModal
      v-if="passwordAuthVisible"
      v-bind="passwordAuthProps"
      @close="passwordAuthVisible = false"
      @submit="onSecondarySubmit"
    />

    <AdminHeader :role-label="roleLabel" :user-label="userLabel" @exit="exitAdmin" />

    <AdminPageToolbar
      title="角色管理"
      hint="点击角色查看权限详情；系统内置角色只读"
      back-label="返回"
      action-label="新增角色"
      :action-visible="canCreateRole"
      @back="goBack"
      @action="roleNew"
    />

    <scroll-view
      v-if="!adminManageReady || displayRoles.length"
      class="page-scroll"
      scroll-y
      :show-scrollbar="false"
    >
      <view v-if="!adminManageReady" class="lock-box">
        <IconGlyph name="lock" size="36" fill="#cbd4e0" />
        <text class="lock-txt">输入管理二级密码后可管理角色与用户</text>
        <button class="btn-unlock" @click="openSecondaryPassword">验证</button>
      </view>

      <view v-else class="role-panel">
        <view class="list-col">
          <view
            v-for="r in displayRoles"
            :key="r.roleId"
            class="list-row"
            @click="selectRole(r)"
          >
            <view class="list-row-copy">
              <view class="list-row-main">
                <view class="badge" :class="r.enabled ? 'enabled' : 'disabled'" />
                <text class="list-name">{{ r.roleName || r.roleId }}</text>
              </view>
              <view class="list-row-sub">
                <text class="list-code">{{ r.roleId }}</text>
                <text v-if="r.isSystem" class="tag-sm sys">内置</text>
                <text v-if="r.roleId === ROLE.DEVELOPER" class="tag-sm dev">D</text>
                <text v-if="r.parentRoleId" class="tag-sm parent">继承 {{ roleNameById(r.parentRoleId) }}</text>
              </view>
            </view>
            <button
              v-if="!r.isSystem"
              v-permission="'account.role.enable'"
              class="role-toggle"
              :class="r.enabled ? 'disable-action' : 'enable-action'"
              :disabled="roleToggleId === r.roleId"
              @click.stop="toggleRoleEnabled(r)"
            >{{ roleToggleId === r.roleId ? '处理中…' : (r.enabled ? '停用该角色' : '启用该角色') }}</button>
            <text class="row-arrow">›</text>
          </view>
        </view>
      </view>
    </scroll-view>

    <view v-if="detailVisible && selRole" class="drawer-mask" @click="closeRoleDetail">
      <view class="role-drawer" @click.stop>
        <view class="drawer-head">
          <view>
            <text class="drawer-title">{{ selRole.roleName || selRole.roleId }}</text>
            <text class="drawer-code">{{ selRole.roleId }}</text>
          </view>
          <button class="drawer-close" @click="closeRoleDetail">×</button>
        </view>
        <scroll-view class="drawer-body" scroll-y :show-scrollbar="false">
          <view class="detail-grid">
            <view class="detail-item"><text>角色类型</text><strong>{{ selRole.isSystem ? '系统内置' : '自定义' }}</strong></view>
            <view class="detail-item"><text>当前状态</text><strong>{{ selRole.enabled ? '已启用' : '已停用' }}</strong></view>
            <view class="detail-item"><text>关联用户</text><strong>{{ selRole.credentialCount || 0 }}</strong></view>
            <view class="detail-item"><text>父角色</text><strong>{{ selRole.parentRoleId ? roleNameById(selRole.parentRoleId) : '无' }}</strong></view>
          </view>
          <view v-if="selRole.description" class="detail-description">{{ selRole.description }}</view>
          <view class="permission-section">
            <view class="section-head">
              <text class="section-title">权限范围</text>
              <text class="section-count">已拥有 {{ effectivePermissionCount }} / {{ detailPermissionItems.length }} 项</text>
            </view>
            <text class="section-help">包含父角色继承和上级权限展开后的当前有效权限</text>
            <view v-if="detailPermissionItems.length" class="permission-view-list">
              <view
                v-for="permission in detailPermissionItems"
                :key="permission.permissionKey"
                class="permission-view-item"
                :class="{ granted: permission.granted }"
              >
                <checkbox :checked="permission.granted" disabled color="#1f76ff" />
                <view class="permission-option-text">
                  <text>{{ permission.permissionName || permission.permissionKey }}</text>
                  <small>{{ permission.permissionKey }}</small>
                </view>
              </view>
            </view>
            <text v-else class="permission-empty">暂无可展示的权限模块</text>
          </view>
        </scroll-view>
        <view class="drawer-actions">
          <template v-if="!selRole.isSystem">
            <button v-permission="'account.role.delete'" class="btn-minor" @click="deleteRole(selRole)">删除</button>
            <button v-permission="'account.role.update'" class="btn-grad" @click="editRole(selRole)">编辑角色</button>
          </template>
          <text v-else class="system-role-note">系统内置角色不可修改、停用或删除</text>
        </view>
      </view>
    </view>

    <view v-if="editorVisible" class="editor-mask" @click="closeRoleEditor">
      <view class="editor-card" @click.stop>
        <view class="editor-head">
          <text class="editor-title">{{ roleForm.isEdit ? '编辑角色' : '新增角色' }}</text>
          <button class="drawer-close" @click="closeRoleEditor">×</button>
        </view>
        <view class="editor-body">
          <view class="fld">
            <text class="fld-lbl field-required">角色名称</text>
            <view class="fld-control">
              <input class="fld-in" v-model="roleForm.roleName" maxlength="30" />
            </view>
          </view>
          <view class="fld">
            <text class="fld-lbl field-required">角色标识</text>
            <view class="fld-control" :class="{ disabled: roleForm.isEdit }">
              <input class="fld-in" v-model="roleForm.roleId" :disabled="roleForm.isEdit" maxlength="50" />
            </view>
          </view>
          <view class="fld">
            <text class="fld-lbl">父角色</text>
            <picker class="fld-picker-shell" :range="parentRoleNames" :value="parentRoleIndex" @change="onParentRole">
              <view class="fld-picker">{{ parentRoleNames[parentRoleIndex] || '无父角色' }}</view>
            </picker>
          </view>
          <text class="inherit-hint">父角色权限会自动继承；系统会拒绝循环继承。</text>
          <view class="fld">
            <text class="fld-lbl">描述</text>
            <view class="fld-control">
              <input class="fld-in" v-model="roleForm.description" maxlength="100" />
            </view>
          </view>
          <view class="permission-editor">
            <view class="section-head">
              <text class="section-title">直接权限</text>
            <text class="section-count">已选 {{ roleForm.perms.size }} 项</text>
            </view>
            <text class="section-help">可同时选择父角色和直接权限；"全部权限"仅保留给系统内置角色。</text>
            <PermissionTree :permissions="editablePermissions" :model-value="roleForm.perms" @update:model-value="setRolePermissions" />
          </view>
        </view>
        <view class="editor-actions">
          <button class="btn-sec" @click="closeRoleEditor">取消</button>
          <button v-permission="roleForm.isEdit ? 'account.role.update' : 'account.role.create'" class="btn-grad" :disabled="roleSaving" @click="saveRole">{{ roleSaving ? '保存中…' : '保存' }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import AdminHeader from '@/components/AdminHeader.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import IconGlyph from '@/components/IconGlyph.vue'
import PasswordModal from '@/components/PasswordModal.vue'
import PermissionTree from '@/components/PermissionTree.vue'
import { ROLE, ROLE_META } from '@/constants/app.js'
import { appState, hasPermission } from '@/state/appState.js'
import { services } from '@/services/index.js'

/* Header */
const roleLabel = computed(() => {
  const s = appState.session; if (!s) return ''
  return ROLE_META[s.role]?.label || s.role || ''
})
const userLabel = computed(() => appState.session?.credentialLabel || appState.session?.displayName || appState.session?.credentialId || '')

async function exitAdmin() { await services.logout(); uni.reLaunch({ url: '/pages/index/index' }) }
function goBack() { uni.navigateBack() }

/* 二级密码 */
const adminManageReady = computed(() => services.hasAdminManageSecondaryAccess())
const passwordAuthVisible = ref(false)
const passwordAuthProps = ref({})

function openSecondaryPassword() {
  passwordAuthProps.value = { title: '管理二级密码验证', help: '请输入 6 位管理二级密码以确认身份' }
  passwordAuthVisible.value = true
}
async function onSecondarySubmit(password, controls = {}) {
  try {
    await services.verifyAdminManageAccess(password)
    passwordAuthVisible.value = false
    await loadAll()
  } catch (error) {
    controls.setError?.(error?.message || '二级密码错误')
  }
}

/* 角色数据 */
const BUILTIN_ROLE_IDS = new Set([ROLE.SUPER_ADMIN, ROLE.DEVELOPER])
const roles = ref([])
const displayRoles = computed(() => roles.value.filter((r) => !BUILTIN_ROLE_IDS.has(r.roleId)))
const permissions = ref([])
const selRole = ref(null)
const detailVisible = ref(false)
const editorVisible = ref(false)
const roleSaving = ref(false)
const roleToggleId = ref('')
const canCreateRole = computed(() => hasPermission('account.role.create'))

const roleForm = reactive({ roleId: '', roleName: '', parentRoleId: null, description: '', perms: new Set(), isEdit: false })
const parentRoleOptions = computed(() => [
  { roleId: '', roleName: '无父角色' },
  ...roles.value.filter((role) => role.roleId !== roleForm.roleId && !BUILTIN_ROLE_IDS.has(role.roleId))
])
const parentRoleNames = computed(() => parentRoleOptions.value.map((role) => role.roleName || role.roleId))
const parentRoleIndex = computed(() => {
  const index = parentRoleOptions.value.findIndex((role) => role.roleId === roleForm.parentRoleId)
  return index >= 0 ? index : 0
})
const editablePermissions = computed(() => permissions.value.filter((permission) => permission.enabled && permission.permissionKey !== '*'))
const effectivePermissionKeys = computed(() => collectEffectivePermissionKeys(selRole.value))
const detailPermissionItems = computed(() => {
  const effectiveKeys = effectivePermissionKeys.value
  const hasWildcard = effectiveKeys.has('*') || effectiveKeys.has('admin.*')
  return editablePermissions.value.map((permission) => ({
    ...permission,
    granted: hasWildcard || effectiveKeys.has(permission.permissionKey)
  }))
})
const effectivePermissionCount = computed(() => detailPermissionItems.value.filter((permission) => permission.granted).length)

function roleNameById(roleId) { return roles.value.find((role) => role.roleId === roleId)?.roleName || roleId }
function isDeveloperRole(role) { return role?.roleId === ROLE.DEVELOPER }

async function loadRoles() { try { roles.value = await services.listLocalRoles() } catch (_) {} }
async function loadPermissions() { try { permissions.value = await services.listLocalPermissions() } catch (_) {} }
async function loadAll() { await Promise.all([loadRoles(), loadPermissions()]) }

function expandPermissionKey(permissionKey, visited = new Set()) {
  if (!permissionKey || visited.has(permissionKey)) return new Set()
  visited.add(permissionKey)
  const permission = permissions.value.find((item) => item.permissionKey === permissionKey && item.enabled)
  if (!permission) return new Set()
  const keys = new Set([permissionKey])
  for (const child of permissions.value.filter((item) => item.enabled && item.parentKey === permissionKey)) {
    for (const key of expandPermissionKey(child.permissionKey, visited)) keys.add(key)
  }
  return keys
}

function collectRolePermissionKeys(role, visited = new Set()) {
  if (!role?.roleId || !role.enabled || visited.has(role.roleId)) return new Set()
  visited.add(role.roleId)
  const keys = new Set(role.permissionKeys || [])
  if (role.parentRoleId) {
    const parent = roles.value.find((item) => item.roleId === role.parentRoleId)
    for (const key of collectRolePermissionKeys(parent, visited)) keys.add(key)
  }
  return keys
}

function collectEffectivePermissionKeys(role) {
  const keys = new Set()
  for (const permissionKey of collectRolePermissionKeys(role)) {
    for (const key of expandPermissionKey(permissionKey)) keys.add(key)
  }
  return keys
}

function selectRole(r) {
  selRole.value = r
  detailVisible.value = Boolean(r)
}

function closeRoleDetail() {
  detailVisible.value = false
  selRole.value = null
}

function fillRoleForm(r) {
  roleForm.roleId = r.roleId; roleForm.roleName = r.roleName || ''
  roleForm.parentRoleId = r.parentRoleId || ''; roleForm.description = r.description || ''
  roleForm.perms = new Set(r.permissionKeys || r.permissions || [])
  roleForm.isEdit = true
}

function roleNew() {
  resetRoleForm()
  editorVisible.value = true
}

function editRole(role) {
  if (!role || role.isSystem) return
  fillRoleForm(role)
  detailVisible.value = false
  editorVisible.value = true
}

function resetRoleForm() {
  roleForm.roleId = ''; roleForm.roleName = ''; roleForm.parentRoleId = ''; roleForm.description = ''
  roleForm.perms = new Set(); roleForm.isEdit = false
}

function closeRoleEditor() { editorVisible.value = false; resetRoleForm() }
function onParentRole(e) { roleForm.parentRoleId = parentRoleOptions.value[Number(e.detail.value)]?.roleId || '' }
function toggleRolePerm(permissionKey) {
  const next = new Set(roleForm.perms)
  if (next.has(permissionKey)) next.delete(permissionKey)
  else next.add(permissionKey)
  roleForm.perms = next
}
function setRolePermissions(permissionKeys) { roleForm.perms = new Set(permissionKeys || []) }

async function saveRole() {
  if (!roleForm.roleId || !roleForm.roleName) { uni.showToast({ title: '请填写名称和标识', icon: 'none' }); return }
  roleSaving.value = true
  try {
    await services.saveLocalRole({ roleId: roleForm.roleId, roleName: roleForm.roleName, parentRoleId: roleForm.parentRoleId, description: roleForm.description, permissionKeys: [...roleForm.perms] })
    uni.showToast({ title: '保存成功', icon: 'success' })
    await loadRoles()
    closeRoleEditor()
  } catch (e) { uni.showToast({ title: e.message || '保存失败', icon: 'none' }) }
  finally { roleSaving.value = false }
}

async function deleteRole(r) {
  if (!r?.roleId) return
  if (r.isSystem) {
    uni.showToast({ title: '系统内置角色不可删除', icon: 'none' })
    return
  }
  const confirmed = await new Promise((resolve) => {
    uni.showModal({
      title: '删除角色',
      content: `确认删除"${r.roleName || r.roleId}"？已关联用户必须先解除该角色。`,
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    })
  })
  if (!confirmed) return
  try { await services.deleteLocalRole(r.roleId); uni.showToast({ title: '已删除', icon: 'success' }); closeRoleDetail(); await loadRoles() }
  catch (e) { uni.showToast({ title: e.message || '删除失败', icon: 'none' }) }
}

function confirmRoleToggle(role, nextEnabled) {
  return new Promise((resolve) => {
    uni.showModal({
      title: nextEnabled ? '启用角色' : '停用角色',
      content: nextEnabled
        ? `确认启用"${role.roleName || role.roleId}"？重新登录后恢复授权。`
        : `确认停用"${role.roleName || role.roleId}"？关联用户重新登录后不再获得该角色权限。`,
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    })
  })
}

async function toggleRoleEnabled(role) {
  if (!role?.roleId || roleToggleId.value) return
  const nextEnabled = !role.enabled
  if (!await confirmRoleToggle(role, nextEnabled)) return
  roleToggleId.value = role.roleId
  try {
    await services.setLocalRoleEnabled(role.roleId, nextEnabled)
    const selectedRoleId = detailVisible.value ? (selRole.value?.roleId || '') : ''
    await loadRoles()
    if (selectedRoleId) selectRole(roles.value.find((item) => item.roleId === selectedRoleId) || null)
    uni.showToast({ title: nextEnabled ? '角色已启用' : '角色已停用', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '角色状态更新失败', icon: 'none' })
  } finally {
    roleToggleId.value = ''
  }
}

onMounted(async () => {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'ROLE_MANAGE', feature_label: '角色管理' })
  if (adminManageReady.value) await loadAll()
})
</script>

<style scoped>
/* ═══════ 根 ═══════ */
.page-root { width:100vw; height:100vh; display:flex; flex-direction:column; background:#f4f6fb; overflow:hidden; }
.page-scroll { flex:1 1 auto; min-height:0; overflow-y:auto; padding:clamp(12px,2vw,24px); box-sizing:border-box; }

/* ═══════ 锁定 ═══════ */
.lock-box { display:flex; flex-direction:column; align-items:center; gap:10px; padding:32px 24px; color:#a0b0c4; }
.lock-txt { font-size:14px; color:#8b98a9; }
.btn-unlock { height:32px; padding:0 14px; border:1px solid #e8a317; border-radius:8px; background:transparent; color:#c2790c; font-size:13px; font-weight:500; line-height:32px; }

/* ═══════ 角色列表 ═══════ */
.role-panel { width:min(100%,1180px); min-height:420px; margin:0 auto; }
.list-col { min-width:0; overflow:hidden; background:#fff; border:1px solid #e8edf5; border-radius:12px; box-sizing:border-box; }
.list-row { position:relative; min-height:92px; padding:18px 84px 18px 22px; display:flex; align-items:center; gap:clamp(28px,5vw,72px); box-sizing:border-box; cursor:pointer; transition:background .15s; border-bottom:1px solid #f0f3f8; }
.list-row:last-child { border-bottom:0; }
.list-row:not(.disabled):active { background:#eef5ff; }
.list-row.disabled { background:#f2f5f9; cursor:default; opacity:.72; }
.list-row.disabled .list-name,
.list-row.disabled .list-code { color:#9aa5b4; }
.list-row-copy { min-width:0; flex:1 1 auto; }
.list-row-main { min-width:0; display:flex; align-items:center; gap:10px; }
.badge { width:12px; height:12px; border-radius:50%; flex-shrink:0; }
.badge.enabled { background:#22a45d; }
.badge.disabled { background:#d83b4c; }
.list-name { min-width:0; font-size:20px; font-weight:650; color:#30343b; line-height:1.4; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.list-row-sub { min-width:0; display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin-top:8px; padding-left:22px; box-sizing:border-box; }
.list-code { min-width:0; max-width:100%; font-size:16px; color:#66758a; line-height:1.4; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tag-sm { font-size:12px; font-weight:600; color:#fff; border-radius:5px; padding:2px 8px; line-height:20px; }
.tag-sm.sys { background:#8b98a9; }
.tag-sm.dev { background:#5b68d6; }
.tag-sm.parent { max-width:130px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; background:#e7f1ff; color:#3267a8; }
.role-toggle { min-width:112px; height:40px; margin:0; padding:0 14px; display:inline-flex; align-items:center; justify-content:center; flex:0 0 auto; box-sizing:border-box; border-radius:7px; background:#fff; font-size:14px; font-weight:650; line-height:38px; }
.role-toggle::after { border:0; }
.role-toggle.disable-action { border:1px solid #e5a7ae; color:#c73245; background:#fff7f8; }
.role-toggle.enable-action { border:1px solid #8fd2b0; color:#137842; background:#f1fff7; }
.role-toggle[disabled] { opacity:.5; }
.row-arrow { position:absolute; top:50%; right:22px; color:#9aa7b8; font-size:52px; line-height:1; transform:translateY(-50%); }
/* ═══════ 抽屉和编辑弹窗 ═══════ */
.drawer-mask,
.editor-mask { position:fixed; inset:0; z-index:90; display:flex; justify-content:flex-end; background:rgba(25,35,50,.38); }
.role-drawer { width:min(480px,88vw); height:100%; display:flex; flex-direction:column; background:#fff; box-shadow:-12px 0 32px rgba(25,49,84,.16); }
.drawer-head,
.editor-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; padding:20px 22px; border-bottom:1px solid #edf1f6; }
.drawer-title,
.editor-title { display:block; color:#253044; font-size:20px; font-weight:700; }
.drawer-code { display:block; margin-top:5px; color:#8b98a9; font-size:14px; }
.drawer-close { width:32px; height:32px; margin:0; padding:0; border:0; background:#f3f5f8; color:#68768a; border-radius:50%; font-size:22px; line-height:30px; }
.drawer-body { flex:1 1 auto; min-height:0; padding:20px 22px; box-sizing:border-box; }
.detail-grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
.detail-item { min-width:0; padding:12px; border-radius:9px; background:#f7f9fc; }
.detail-item text { display:block; color:#8996a8; font-size:14px; }
.detail-item strong { display:block; margin-top:5px; color:#344054; font-size:16px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.detail-description { margin-top:12px; padding:12px; border:1px solid #edf1f6; border-radius:9px; color:#657287; font-size:15px; line-height:1.6; }
.permission-section,
.permission-editor { margin-top:20px; }
.section-head { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.section-title { color:#344054; font-size:18px; font-weight:700; }
.section-count { color:#6f7e92; font-size:14px; }
.section-help { display:block; margin-top:7px; color:#8a97a9; font-size:15px; line-height:1.5; }
.permission-view-list { display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-top:12px; }
.permission-view-item { min-width:0; display:flex; align-items:center; gap:7px; padding:9px; border:1px solid #e7ebf1; border-radius:8px; background:#f8f9fb; opacity:.68; }
.permission-view-item.granted { border-color:#b9d5fb; background:#f0f6ff; opacity:1; }
.permission-view-item checkbox { flex-shrink:0; transform:scale(.8); }
.permission-empty { display:block; margin-top:16px; color:#a3afbf; font-size:13px; text-align:center; }
.drawer-actions,
.editor-actions { display:flex; align-items:center; gap:10px; padding:14px 20px max(14px,env(safe-area-inset-bottom)); border-top:1px solid #edf1f6; }
.drawer-actions > button,
.editor-actions > button { margin:0; }
.drawer-actions > button { min-height:54px; font-size:17px; }
.drawer-actions .btn-minor { min-width:112px; padding:0 22px; font-size:17px; }
.drawer-actions .btn-grad,
.editor-actions .btn-grad { flex:1; }
.drawer-actions .btn-grad { height:54px; font-size:17px; }
.system-role-note { width:100%; color:#7b8899; font-size:13px; text-align:center; }
.editor-mask { align-items:center; justify-content:center; padding:20px; box-sizing:border-box; }
.editor-card { --form-control-height:52px; width:min(680px,94vw); max-height:min(780px,92vh); display:flex; flex-direction:column; background:#fff; border-radius:14px; box-shadow:0 18px 54px rgba(25,49,84,.22); overflow:hidden; }
.editor-body { flex:1 1 auto; min-height:0; height:100%; padding:20px 24px; box-sizing:border-box; overflow-y:auto; -webkit-overflow-scrolling:touch; }
.fld { display:flex; align-items:center; gap:10px; }
.fld + .fld { margin-top:12px; }
.fld-lbl { font-size:13px; font-weight:500; color:#4b5565; flex-shrink:0; width:62px; }
.fld-control,
.fld-picker-shell { flex:1; min-width:0; width:100%; height:var(--form-control-height); min-height:var(--form-control-height); box-sizing:border-box; }
.fld-control { display:flex; align-items:center; overflow:hidden; background:#fff; border:1px solid #b0bfd1; border-radius:8px; }
.fld-control:focus-within { border-color: #1f76ff; box-shadow: 0 0 0 2px rgba(31,118,255,.18); }
.fld-control:focus-within { border-color:#195fca; background:#fff; }
.fld-control.disabled { background:#eef1f6; border-color:#e4e8ef; }
.fld-in { display:flex; align-items:center; min-width:0; width:100%; height:var(--form-control-height) !important; min-height:var(--form-control-height) !important; box-sizing:border-box; border:0; background:transparent; padding:0 12px; font-size:14px; line-height:normal; color:#30343b; }
.fld-in[disabled] { color:#8b98a9; }
.fld-in :deep(.uni-input-wrapper),
.fld-in :deep(.uni-input-form),
.fld-in :deep(.uni-input-input) { height:100% !important; min-height:100% !important; }
.fld-picker { width:100%; height:var(--form-control-height); min-height:var(--form-control-height); display:flex; align-items:center; box-sizing:border-box; background:#fff; border:1px solid #b0bfd1; border-radius:8px; padding:0 12px; font-size:14px; color:#30343b; }
.inherit-hint { display:block; margin:5px 0 0 72px; color:#7f8da0; font-size:12px; line-height:1.5; }
.perm-scroll { display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-top:12px; }
.permission-option { min-width:0; display:flex; align-items:center; gap:8px; padding:10px; border:1px solid #e4e9f1; border-radius:8px; background:#fafbfd; }
.permission-option.checked { border-color:#8fbcff; background:#f0f6ff; }
.permission-option checkbox { flex-shrink:0; transform:scale(.82); }
.permission-option-text { min-width:0; }
.permission-option-text text,
.permission-option-text small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.permission-option-text text { color:#3b4658; font-size:15px; }
.permission-option-text small { margin-top:3px; color:#929eae; font-size:13px; }

/* ═══════ 按钮 ═══════ */
.btn-grad { display:flex; align-items:center; justify-content:center; width:100%; height:42px; border:0; border-radius:10px; background:linear-gradient(135deg,#4aa3ff,#1f76ff); color:#fff; font-size:14px; font-weight:600; box-shadow:0 3px 10px rgba(31,118,255,.16); }
.btn-grad[disabled] { opacity:.5; }
.btn-sec { height:34px; padding:0 14px; border:1px solid #d4dce8; border-radius:8px; background:#fff; color:#6b788e; font-size:13px; display:flex; align-items:center; justify-content:center; }
.btn-minor { height:34px; padding:0 12px; border:0; border-radius:8px; background:#f5f7fa; color:#d9273f; font-size:13px; display:flex; align-items:center; justify-content:center; }
@media (max-width:760px) {
  .detail-grid,
  .permission-view-list,
  .perm-scroll { grid-template-columns:1fr; }
  .editor-body { padding:16px; }
  .fld { align-items:stretch; flex-direction:column; }
  .fld-lbl { width:auto; }
  .inherit-hint { margin-left:0; }
}
</style>
