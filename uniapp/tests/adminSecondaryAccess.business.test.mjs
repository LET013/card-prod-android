import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('customer-removed permission management has no page, entry or route registration', async () => {
  const [pagesSource, mainSource, adminSource] = await Promise.all([
    readSource('src/pages.json'),
    readSource('src/main.js'),
    readSource('src/pages/admin/admin.vue')
  ])

  assert.doesNotMatch(pagesSource, /pages\/admin\/permission-manage/)
  assert.doesNotMatch(mainSource, /pages\/admin\/permission-manage/)
  assert.doesNotMatch(adminSource, /权限管理|pages\/admin\/permission-manage/)
})

test('credential management keeps customer-approved local wording and developer restrictions', async () => {
  const source = await readSource('src/pages/admin/credential-manage.vue')

  assert.match(source, /管理用户和密码仅保存在本设备/)
  assert.match(source, /INITIAL:\s*'使用初始密码，建议修改'/)
  assert.match(source, /ACTIVE:\s*'正常'/)
  assert.match(source, /role\?\.roleId !== DEVELOPER_ROLE_ID/)
  assert.match(source, /credForm\.isSystem/)
  assert.match(source, /!roleIds\.some\(\(roleId\) => PRIVILEGED_ROLE_IDS\.has\(roleId\)\)/)
})

test('credential editor and detail drawer visually separate account status, roles and login state', async () => {
  const source = await readSource('src/pages/admin/credential-manage.vue')

  assert.match(source, /class="section-title-bar"[\s\S]*基本信息/)
  assert.match(source, /class="setting-group status-setting"[\s\S]*账号状态[\s\S]*账号启用/)
  assert.match(source, /class="setting-group role-setting"[\s\S]*关联角色/)
  assert.match(source, /v-if="currentCredential" class="detail-section"[\s\S]*登录状态/)
  assert.match(source, /\.account-settings\s*\{\s*display:grid;/)
})

test('role management uses a detail drawer, edits custom permissions in a modal and protects system roles', async () => {
  const source = await readSource('src/pages/admin/role-manage.vue')

  assert.match(source, /class="role-drawer"/)
  assert.match(source, /class="editor-card"/)
  assert.match(source, /class="perm-scroll"/)
  assert.match(source, /class="permission-view-list"/)
  assert.match(source, /v-for="permission in detailPermissionItems"/)
  assert.match(source, /:checked="permission\.granted" disabled/)
  assert.match(source, /listLocalPermissions/)
  assert.match(source, /toggleRolePerm/)
  assert.match(source, /v-if="!r\.isSystem"[\s\S]*class="role-toggle"/)
  assert.match(source, /v-if="!selRole\.isSystem"/)
  assert.match(source, /const BUILTIN_ROLE_IDS = new Set\(\[ROLE\.SUPER_ADMIN, ROLE\.DEVELOPER\]\)/)
  assert.match(source, /displayRoles = computed\(\(\) => roles\.value\.filter\(\(r\) => !BUILTIN_ROLE_IDS\.has\(r\.roleId\)\)\)/)
  assert.match(source, /系统内置角色不可修改、停用或删除/)
  assert.match(source, /permissionKeys:\s*\[\.\.\.roleForm\.perms\]/)
})

test('admin entry verifies custom administrators before opening sensitive management pages', async () => {
  const source = await readSource('src/pages/admin/admin.vue')

  for (const route of ['role-manage', 'credential-manage']) {
    assert.match(source, new RegExp(`'/pages/admin/${route}'`))
  }
  assert.match(source, /ADMIN_MANAGE_ROUTES\.has\(route\) && !services\.hasAdminManageSecondaryAccess\(\)/)
  assert.match(source, /@submit="onSecondarySubmit"/)
  assert.match(source, /await services\.verifyAdminManageAccess\(password\)/)
})

test('sensitive management pages use the PasswordModal submit contract', async () => {
  const pages = [
    'src/pages/admin/role-manage.vue',
    'src/pages/admin/credential-manage.vue'
  ]

  for (const page of pages) {
    const source = await readSource(page)
    assert.doesNotMatch(source, /@verified=/, `${page} must not listen for an event PasswordModal never emits`)
    assert.match(source, /@submit="onSecondarySubmit"/)
    assert.match(source, /await services\.verifyAdminManageAccess\(password\)/)
  }
})

test('secondary-password changes require exactly six numeric digits', async () => {
  const source = await readSource('src/pages/admin/credential-manage.vue')

  assert.match(source, /maxlength="6"[^>]*v-model="secPw\.old"/)
  assert.match(source, /maxlength="6"[^>]*v-model="secPw\.newPass"/)
  assert.match(source, /!\/\^\\d\{6\}\$\/\.test\(secPw\.old\)/)
  assert.match(source, /!\/\^\\d\{6\}\$\/\.test\(secPw\.newPass\)/)
})
