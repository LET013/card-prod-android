<!--
  credential-manage.vue — 用户/凭证管理独立页面（二级密码保护）
  列表查看用户，右侧抽屉展示详情
  新增/编辑：居中弹窗表单
-->
<template>
  <view class="page-root">
    <PasswordModal
      v-if="passwordAuthVisible"
      v-bind="passwordAuthProps"
      @close="passwordAuthVisible = false"
      @submit="onSecondarySubmit"
    />

    <AdminHeader :role-label="roleLabel" :user-label="userLabel" @exit="exitAdmin" />

    <AdminPageToolbar
      title="用户管理"
      hint="管理用户和密码仅保存在本设备"
      back-label="返回"
      action-label="新增用户"
      :action-visible="canCreateCredential"
      @back="goBack"
      @action="credNew"
    />

    <scroll-view
      v-if="!adminManageReady || displayCredentials.length"
      class="page-scroll"
      scroll-y
      :show-scrollbar="false"
    >
      <view v-if="!adminManageReady" class="lock-box">
        <IconGlyph name="lock" size="36" fill="#cbd4e0" />
        <text class="lock-txt">输入管理二级密码后可管理角色与用户</text>
        <button class="btn-unlock" @click="openSecondaryPassword">验证</button>
      </view>

      <view v-else class="credential-panel">
        <view class="list-col">
          <view
            v-for="c in displayCredentials"
            :key="c.credentialId"
            class="list-row"
            :class="{ sel: selCred?.credentialId === c.credentialId }"
            @click="selectCredential(c)"
          >
            <view class="list-row-main">
              <text class="list-name">{{ c.label || c.credentialId }}</text>
            </view>
            <view class="list-row-sub">
              <text class="list-code">{{ c.credentialId }}</text>
              <view class="role-chips">
                <text v-for="rId in c.roleIds" :key="rId" class="rc">{{ rId }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>

    <view v-if="credentialDetailVisible && selCred" class="drawer-mask" @click="closeCredentialDetail">
      <view class="credential-drawer" @click.stop>
        <view class="drawer-head">
          <view>
            <text class="drawer-title">{{ selCred.label || selCred.credentialId }}</text>
            <view class="drawer-meta">
              <text class="drawer-code">{{ selCred.credentialId }}</text>
              <text class="status-badge" :class="{ disabled: selCred.enabled === false }">{{ selCred.enabled === false ? '已停用' : '已启用' }}</text>
            </view>
          </view>
          <button class="drawer-close" @click="closeCredentialDetail">×</button>
        </view>

        <scroll-view class="drawer-body" scroll-y :show-scrollbar="false">
          <view class="detail-section">
            <view class="section-title-bar">
              <text>基本信息</text>
              <small>账号名称、标识与登录参数</small>
            </view>
            <view class="detail-grid">
              <view class="detail-item">
                <text class="di-label">用户名</text>
                <text class="di-value">{{ selCred.label || '-' }}</text>
              </view>
              <view class="detail-item">
                <text class="di-label">标识</text>
                <text class="di-value">{{ selCred.credentialId }}</text>
              </view>
              <view class="detail-item">
                <text class="di-label">会话时长</text>
                <text class="di-value">{{ Number(selCred.ttlSeconds || 3600) }} 秒</text>
              </view>
              <view class="detail-item">
                <text class="di-label">账号状态</text>
                <text class="di-value">{{ selCred.enabled === false ? '已停用' : '已启用' }}</text>
              </view>
            </view>
          </view>

          <view class="detail-section">
            <view class="section-title-bar">
              <text>关联角色</text>
              <small>角色决定该账号可使用的管理功能</small>
            </view>
            <view v-if="displayCredRoleIds(selCred.roleIds).length" class="chip-wrap">
              <text v-for="rId in displayCredRoleIds(selCred.roleIds)" :key="rId" class="chip on">{{ roleNameOf(rId) }}</text>
            </view>
            <text v-else class="detail-empty">未分配角色</text>
          </view>

          <view v-if="currentCredential" class="detail-section">
            <view class="section-title-bar">
              <text>登录状态</text>
              <small>本机密码、锁定和最近登录情况</small>
            </view>
            <view class="detail-grid">
              <view class="detail-item">
                <text class="di-label">密码状态</text>
                <text class="di-value">{{ passwordStateText }}</text>
              </view>
              <view class="detail-item">
                <text class="di-label">失败次数</text>
                <text class="di-value">{{ currentCredential.failedCount || 0 }}</text>
              </view>
              <view class="detail-item">
                <text class="di-label">锁定至</text>
                <text class="di-value">{{ formatTime(currentCredential.lockedUntil) }}</text>
              </view>
              <view class="detail-item">
                <text class="di-label">上次登录</text>
                <text class="di-value">{{ formatTime(currentCredential.lastLoginAt) }}</text>
              </view>
            </view>
          </view>

          <view v-if="showSecondaryPanel" class="sec-panel">
            <view class="div-line" />
            <text class="sec-title">管理二级密码</text>
            <input class="pw-in" type="password" maxlength="6" v-model="secPw.old" placeholder="二级原密码（6位数字）" />
            <input class="pw-in" type="password" maxlength="6" v-model="secPw.newPass" placeholder="二级新密码（6位数字）" />
            <button class="btn-grad" style="margin-top:4px" :disabled="secPwSaving" @click="submitSecondaryPassword">{{ secPwSaving ? '提交中…' : '修改密码' }}</button>
          </view>
        </scroll-view>

        <view class="drawer-actions">
          <button v-permission="'account.user.unlock'" class="btn-sec" @click="unlockCredential(selCred)">解锁</button>
          <button v-permission="'account.user.delete'" class="btn-minor" :disabled="selCred.isSystem" @click="deleteCredential(selCred)">删除</button>
          <button v-permission="'account.user.update'" class="btn-grad" :disabled="selCred.isDeveloper" @click="startEditCredential">编辑用户</button>
        </view>
      </view>
    </view>

    <!-- 新增/编辑用户弹窗 -->
    <view v-if="credEditorVisible" class="editor-mask" @click="closeCredEditor">
      <view class="editor-card" @click.stop>
        <view class="editor-head">
          <text class="editor-title">{{ credForm.isEdit ? '编辑用户' : '新增用户' }}</text>
          <button class="drawer-close" @click="closeCredEditor">×</button>
        </view>
        <scroll-view class="editor-body" scroll-y :show-scrollbar="false">
          <view class="fld">
            <text class="fld-lbl field-required">用户名</text>
            <view class="fld-control">
              <input class="fld-in" v-model="credForm.label" />
            </view>
          </view>
          <view class="fld">
            <text class="fld-lbl field-required">标识</text>
            <view class="fld-control" :class="{ disabled: credForm.isEdit }">
              <input class="fld-in" v-model="credForm.credentialId" :disabled="credForm.isEdit" />
            </view>
          </view>
          <view class="fld">
            <text class="fld-lbl" :class="{ 'field-required': !credForm.isEdit }">密码</text>
            <view class="fld-control">
              <input class="fld-in" type="password" maxlength="6" v-model="credForm.password" :placeholder="credForm.isEdit ? '留空不修改；修改时输入6位数字' : '请输入6位数字'" />
            </view>
          </view>
          <view class="fld">
            <text class="fld-lbl field-required">会话时长</text>
            <view class="fld-control">
              <input class="fld-in" type="number" v-model="credForm.ttlSeconds" placeholder="单位：秒" />
            </view>
          </view>

          <view class="account-settings">
            <view class="setting-group status-setting">
              <view class="setting-head">
                <text class="setting-title">账号状态</text>
                <text class="setting-desc">控制该账号是否允许登录</text>
              </view>
              <view class="status-control">
                <text>账号启用</text>
                <UiSwitch v-model="credForm.enabled" :disabled="credForm.isSystem" />
              </view>
            </view>

            <view class="setting-group role-setting">
              <view class="setting-head">
                <text class="setting-title">关联角色</text>
                <text class="setting-desc">角色决定该账号可使用的管理功能</text>
              </view>
              <view class="chip-wrap">
                <view
                  v-for="r in editorRoles"
                  :key="r.roleId"
                  class="chip"
                  :class="{ on: credForm.roleIds.includes(r.roleId), disabled: !canToggleRole(r) }"
                  @click="toggleCredRole(r)"
                >
                  <text>{{ r.roleName || r.roleId }}</text>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>
        <view class="editor-actions">
          <button class="btn-sec" @click="closeCredEditor">取消</button>
          <button v-permission="credForm.isEdit ? 'account.user.update' : 'account.user.create'" class="btn-grad" :disabled="credSaving || credForm.isDeveloper" @click="saveCredential">{{ credSaving ? '保存中…' : '保存' }}</button>
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
import UiSwitch from '@/components/UiSwitch.vue'
import { ROLE, ROLE_META } from '@/constants/app.js'
import { appState, hasPermission } from '@/state/appState.js'
import { services } from '@/services/index.js'

const DEVELOPER_ROLE_ID = ROLE.DEVELOPER
const DEVELOPER_CREDENTIAL_ID = 'builtin:DEVELOPER'
const PRIVILEGED_ROLE_IDS = new Set([ROLE.SUPER_ADMIN, ROLE.DEVELOPER])
const PASSWORD_STATE_LABELS = {
  INITIAL: '使用初始密码，建议修改',
  ACTIVE: '正常',
  LOCKED: '已锁定',
  EXPIRED: '需要修改'
}

/* Header */
const roleLabel = computed(() => {
  const s = appState.session; if (!s) return ''
  return ROLE_META[s.role]?.label || s.role || ''
})
const canCreateCredential = computed(() => hasPermission('account.user.create'))
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

async function loadAll() { await Promise.all([loadRoles(), loadCredentials()]) }

/* 角色（用于 chips） */
const BUILTIN_ROLE_IDS = new Set([ROLE.SUPER_ADMIN, ROLE.DEVELOPER])
const roles = ref([])
const displayRoles = computed(() => roles.value.filter((r) => !BUILTIN_ROLE_IDS.has(r.roleId)))
const editorRoles = displayRoles
async function loadRoles() { try { roles.value = await services.listLocalRoles() } catch (_) {} }
function roleNameOf(roleId) {
  return roles.value.find((r) => r.roleId === roleId)?.roleName || roleId
}
/* 凭证 */
const credentials = ref([])
const selCred = ref(null)
const credentialDetailVisible = ref(false)
const credSaving = ref(false)
const credEditorVisible = ref(false)
const displayCredentials = computed(() => credentials.value.filter((c) => !c.isSystem))
const displayCredRoleIds = (roleIds) => (Array.isArray(roleIds) ? roleIds.filter((id) => !BUILTIN_ROLE_IDS.has(id)) : [])
const credForm = reactive({ credentialId: '', label: '', password: '', ttlSeconds: 3600, enabled: true, isSystem: false, roleIds: [], isEdit: false, isDeveloper: false })
const currentCredential = computed(() => credentials.value.find((item) => item.credentialId === selCred.value?.credentialId) || null)
const passwordStateText = computed(() => {
  const state = String(currentCredential.value?.passwordState || '').toUpperCase()
  return PASSWORD_STATE_LABELS[state] || (state || '-')
})

async function loadCredentials() { try { credentials.value = await services.listLocalCredentials() } catch (_) {} }

function selectCredential(c) {
  if (!c) { closeCredentialDetail(); return }
  selCred.value = c
  credentialDetailVisible.value = true
}

function closeCredentialDetail() {
  credentialDetailVisible.value = false
  selCred.value = null
}

function credNew() {
  resetCredForm()
  credForm.isEdit = false
  credForm.isDeveloper = false
  credentialDetailVisible.value = false
  credEditorVisible.value = true
}

function startEditCredential() {
  const c = selCred.value
  if (!c) return
  resetCredForm()
  credForm.credentialId = c.credentialId
  credForm.label = c.label || ''
  credForm.password = ''
  credForm.ttlSeconds = Number(c.ttlSeconds || 3600)
  credForm.enabled = c.enabled !== false
  credForm.isSystem = c.isSystem === true
  credForm.roleIds = [...(c.roleIds || [])]
  credForm.isEdit = true
  credForm.isDeveloper = c.credentialId === DEVELOPER_CREDENTIAL_ID
  credentialDetailVisible.value = false
  credEditorVisible.value = true
}

function closeCredEditor() {
  credEditorVisible.value = false
}

function resetCredForm() {
  credForm.credentialId = ''; credForm.label = ''; credForm.password = ''; credForm.ttlSeconds = 3600
  credForm.enabled = true; credForm.isSystem = false; credForm.roleIds = []; credForm.isEdit = false; credForm.isDeveloper = false
}

function canToggleRole(role) {
  if (credForm.isSystem) return false
  return role?.roleId !== DEVELOPER_ROLE_ID
}

function toggleCredRole(role) {
  if (!canToggleRole(role)) {
    if (role?.roleId === DEVELOPER_ROLE_ID) uni.showToast({ title: '开发人员角色仅用于系统内置账号', icon: 'none' })
    return
  }
  const arr = [...credForm.roleIds]; const idx = arr.indexOf(role.roleId)
  idx >= 0 ? arr.splice(idx, 1) : arr.push(role.roleId); credForm.roleIds = arr
}

async function saveCredential() {
  if (!credForm.credentialId) { uni.showToast({ title: '请填写用户标识', icon: 'none' }); return }
  if ((!credForm.isEdit || credForm.password) && !/^\d{6}$/.test(credForm.password)) { uni.showToast({ title: '密码必须为6位数字', icon: 'none' }); return }
  credSaving.value = true
  try {
    const data = { credentialId: credForm.credentialId, label: credForm.label, roleIds: credForm.roleIds, enabled: credForm.enabled, ttlSeconds: Number(credForm.ttlSeconds || 3600) }
    if (credForm.password) data.password = credForm.password
    await services.saveLocalCredential(data)
    uni.showToast({ title: '保存成功', icon: 'success' })
    await loadCredentials()
    const saved = credentials.value.find((item) => item.credentialId === credForm.credentialId)
    if (saved) selCred.value = saved
    closeCredEditor()
  } catch (e) { uni.showToast({ title: e.message || '保存失败', icon: 'none' }) }
  finally { credSaving.value = false }
}

async function deleteCredential(c) {
  if (!c?.credentialId) return
  try { await services.deleteLocalCredential(c.credentialId); uni.showToast({ title: '已删除', icon: 'success' }); selectCredential(null); await loadCredentials() }
  catch (e) { uni.showToast({ title: e.message || '删除失败', icon: 'none' }) }
}

async function unlockCredential(c) {
  if (!c?.credentialId) return
  try { await services.unlockLocalCredential(c.credentialId); uni.showToast({ title: '已解锁', icon: 'success' }); await loadCredentials() }
  catch (e) { uni.showToast({ title: e.message || '解锁失败', icon: 'none' }) }
}

/* 二级密码修改 */
const showSecondaryPanel = computed(() => {
  const roleIds = Array.isArray(appState.session?.roleIds) ? appState.session.roleIds : []
  return adminManageReady.value && !roleIds.some((roleId) => PRIVILEGED_ROLE_IDS.has(roleId))
})
const secPw = reactive({ old: '', newPass: '' })
const secPwSaving = ref(false)

async function submitSecondaryPassword() {
  if (!secPw.old || !secPw.newPass) { uni.showToast({ title: '请填写新旧密码', icon: 'none' }); return }
  if (!/^\d{6}$/.test(secPw.old) || !/^\d{6}$/.test(secPw.newPass)) { uni.showToast({ title: '二级密码必须为6位数字', icon: 'none' }); return }
  secPwSaving.value = true
  try { await services.changeSecondaryPassword(secPw.old, secPw.newPass); uni.showToast({ title: '已修改', icon: 'success' }); secPw.old = ''; secPw.newPass = '' }
  catch (e) { uni.showToast({ title: e.message || '修改失败', icon: 'none' }) }
  finally { secPwSaving.value = false }
}

function formatTime(value) {
  const timestamp = Number(value || 0)
  if (!timestamp) return '-'
  return new Date(timestamp).toLocaleString()
}

onMounted(async () => {
  services.recordAuditEvent({ event_type: 'FEATURE_ENTER', feature_code: 'CRED_MANAGE', feature_label: '账号管理' })
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

/* ═══════ 用户列表 ═══════ */
.credential-panel { width:min(100%,1180px); min-height:420px; margin:0 auto; }
.list-col { min-width:0; overflow:hidden; background:#fff; border:1px solid #e8edf5; border-radius:12px; box-sizing:border-box; }
.list-row { min-height:80px; padding:18px 22px; box-sizing:border-box; cursor:pointer; transition:background .15s; border-bottom:1px solid #f0f3f8; }
.list-row:last-child { border-bottom:0; }
.list-row:active { background:#eef5ff; }
.list-row.sel { background:#eaf2ff; }
.list-row.sel .list-name { color:#195fca; }
.list-row-main { min-width:0; display:flex; align-items:center; gap:10px; }
.list-name { min-width:0; font-size:16px; font-weight:600; color:#30343b; line-height:1.4; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.list-row-sub { min-width:0; display:flex; flex-wrap:wrap; align-items:flex-start; gap:8px; margin-top:8px; }
.list-code { min-width:0; max-width:100%; font-size:13px; color:#8b98a9; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.role-chips { min-width:0; flex:1 1 100%; display:flex; flex-wrap:wrap; gap:4px; }
.rc { max-width:100%; font-size:12px; color:#5b68d6; background:#eef1ff; border-radius:5px; padding:2px 8px; line-height:20px; font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; box-sizing:border-box; }

/* ═══════ 用户详情抽屉 ═══════ */
.drawer-mask { position:fixed; inset:0; z-index:90; display:flex; justify-content:flex-end; background:rgba(25,35,50,.38); }
.credential-drawer { width:min(480px,88vw); height:100%; display:flex; flex-direction:column; background:#fff; box-shadow:-12px 0 32px rgba(25,49,84,.16); }
.drawer-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; padding:20px 22px; border-bottom:1px solid #edf1f6; }
.drawer-title { display:block; color:#253044; font-size:20px; font-weight:700; }
.drawer-meta { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:5px; }
.drawer-code { color:#8b98a9; font-size:14px; }
.drawer-body { flex:1 1 auto; min-height:0; padding:20px 22px; box-sizing:border-box; }
.drawer-actions { display:flex; align-items:center; gap:10px; padding:14px 20px max(14px,env(safe-area-inset-bottom)); border-top:1px solid #edf1f6; }
.drawer-actions > button { margin:0; }
.drawer-actions .btn-sec,
.drawer-actions .btn-minor { min-width:104px; height:54px; padding:0 20px; font-size:16px; }
.drawer-actions .btn-grad { flex:1; }
.drawer-actions .btn-grad { height:54px; font-size:17px; }
.drawer-close { width:32px; height:32px; margin:0; padding:0; border:0; border-radius:50%; background:#f2f5f9; color:#6b788e; font-size:20px; line-height:1; display:flex; align-items:center; justify-content:center; }
.status-badge { flex-shrink:0; padding:3px 10px; border-radius:20px; background:#e8f7ee; color:#19a34a; font-size:12px; font-weight:500; }
.status-badge.disabled { background:#f2f4f7; color:#8b98a9; }
.detail-section { display:flex; flex-direction:column; gap:10px; }
.detail-section + .detail-section { margin-top:20px; }
.section-title-bar { display:flex; align-items:baseline; gap:10px; padding-left:10px; border-left:3px solid #1f76ff; }
.section-title-bar text { color:#23344d; font-size:17px; font-weight:700; }
.section-title-bar small { color:#8b98a9; font-size:14px; }
.detail-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.detail-item { min-height:52px; box-sizing:border-box; display:flex; flex-direction:column; justify-content:center; padding:8px 10px; border-radius:8px; background:#f5f7fa; }
.di-label { font-size:14px; color:#6d7b8f; }
.di-value { font-size:16px; color:#30343b; font-weight:500; margin-top:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.chip-wrap { display:flex; flex-wrap:wrap; gap:8px; }
.chip { padding:4px 12px; border-radius:8px; font-size:13px; line-height:22px; background:#f5f7fa; color:#4b5565; border:1px solid #e4e8ef; cursor:pointer; user-select:none; transition:all .15s; }
.chip.on { background:#eef1ff; color:#195fca; border-color:#5b8ef5; box-shadow:0 0 0 1px rgba(25,95,202,.08); }
.chip.disabled { background:#f2f5f9; color:#9aa5b4; border-color:#e4e8ef; cursor:default; opacity:.72; }
.detail-empty { font-size:13px; color:#b0becf; }

/* ═══════ 二级密码面板 ═══════ */
.sec-panel { display:flex; flex-direction:column; gap:10px; margin-top:4px; }
.sec-title { font-size:13px; font-weight:500; color:#4b5565; }
.div-line { height:1px; background:#e8edf5; margin:6px 0; }
.pw-in { width:100%; height:40px; box-sizing:border-box; background:#f5f7fa; border:1px solid #e4e8ef; border-radius:8px; padding:0 12px; font-size:14px; color:#30343b; }
.pw-in:focus { border-color:#195fca; background:#fff; }
.pw-in::placeholder { color:#b0becf; }

/* ═══════ 弹窗编辑器 ═══════ */
.editor-mask { position:fixed; inset:0; z-index:500; display:flex; align-items:center; justify-content:center; padding:20px; box-sizing:border-box; background:rgba(0,0,0,.45); }
.editor-card { --form-control-height:52px; width:min(680px,94vw); max-height:min(780px,92vh); display:flex; flex-direction:column; background:#fff; border-radius:14px; box-shadow:0 18px 54px rgba(25,49,84,.22); overflow:hidden; }
.editor-head { display:flex; align-items:center; justify-content:space-between; padding:14px 18px; border-bottom:1px solid #edf1f6; }
.editor-title { font-size:16px; font-weight:700; color:#23344d; }
.editor-body { flex:1 1 auto; min-height:0; height:100%; padding:20px 24px; box-sizing:border-box; }
.fld { display:flex; align-items:center; gap:10px; }
.fld + .fld { margin-top:12px; }
.fld-lbl { font-size:13px; font-weight:500; color:#4b5565; flex-shrink:0; width:62px; }
.fld-control { flex:1; min-width:0; width:100%; height:var(--form-control-height); min-height:var(--form-control-height); display:flex; align-items:center; overflow:hidden; box-sizing:border-box; background:#fff; border:1px solid #b8c7da; border-radius:8px; }
.fld-control:focus-within { border-color:#195fca; background:#fff; }
.fld-control.disabled { background:#eef1f6; border-color:#e4e8ef; }
.fld-in { display:flex; align-items:center; min-width:0; width:100%; height:var(--form-control-height) !important; min-height:var(--form-control-height) !important; box-sizing:border-box; border:0; background:transparent; padding:0 12px; font-size:14px; line-height:normal; color:#30343b; }
.fld-in[disabled] { color:#8b98a9; }
.fld-in :deep(.uni-input-wrapper),
.fld-in :deep(.uni-input-form),
.fld-in :deep(.uni-input-input) { height:100% !important; min-height:100% !important; }
.account-settings { display:grid; grid-template-columns:minmax(180px,.65fr) minmax(0,1.35fr); gap:10px; margin-top:16px; }
.setting-group { box-sizing:border-box; padding:10px 12px; border:1px solid #e1e8f2; border-radius:10px; background:#fff; }
.setting-head { display:flex; flex-direction:column; gap:2px; }
.setting-title { color:#23344d; font-size:13px; font-weight:700; }
.setting-desc { color:#8b98a9; font-size:10px; line-height:1.45; }
.status-control { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:32px; margin-top:8px; color:#4b5565; font-size:12px; }
.role-setting .chip-wrap { margin-top:8px; }
.editor-actions { display:flex; align-items:center; gap:10px; padding:14px 20px max(14px,env(safe-area-inset-bottom)); border-top:1px solid #edf1f6; }
.editor-actions > button { margin:0; }
.editor-actions .btn-grad { flex:1; }

/* ═══════ 按钮 ═══════ */
.btn-grad { display:flex; align-items:center; justify-content:center; width:100%; height:42px; border:0; border-radius:10px; background:linear-gradient(135deg,#4aa3ff,#1f76ff); color:#fff; font-size:14px; font-weight:600; box-shadow:0 3px 10px rgba(31,118,255,.16); }
.btn-grad[disabled] { opacity:.5; }
.btn-sec { height:34px; padding:0 14px; border:1px solid #d4dce8; border-radius:8px; background:#fff; color:#6b788e; font-size:13px; display:flex; align-items:center; justify-content:center; }
.btn-minor { height:34px; padding:0 12px; border:0; border-radius:8px; background:#f5f7fa; color:#d9273f; font-size:13px; display:flex; align-items:center; justify-content:center; }
.btn-minor[disabled] { opacity:.45; }

@media (max-width: 900px) {
  .account-settings { grid-template-columns:1fr; }
}
@media (max-width:760px) {
  .detail-grid { grid-template-columns:1fr; }
  .editor-body { padding:16px; }
  .fld { align-items:stretch; flex-direction:column; }
  .fld-lbl { width:auto; }
}
</style>
