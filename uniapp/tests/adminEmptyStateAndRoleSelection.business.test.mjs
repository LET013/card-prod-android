import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('role and credential pages render no blank list container when there is no visible data', async () => {
  const [roleSource, credentialSource] = await Promise.all([
    readSource('src/pages/admin/role-manage.vue'),
    readSource('src/pages/admin/credential-manage.vue')
  ])

  assert.match(roleSource, /v-if="!adminManageReady \|\| displayRoles\.length"[\s\S]*class="page-scroll"/)
  assert.doesNotMatch(roleSource, /暂无角色/)
  assert.match(credentialSource, /v-if="!adminManageReady \|\| displayCredentials\.length"[\s\S]*class="page-scroll"/)
})

test('role and credential editor fields share the same touch-friendly fixed control height', async () => {
  const [roleSource, credentialSource] = await Promise.all([
    readSource('src/pages/admin/role-manage.vue'),
    readSource('src/pages/admin/credential-manage.vue')
  ])

  assert.equal((roleSource.match(/class="fld-control/g) || []).length, 3)
  assert.match(roleSource, /class="fld-picker-shell"/)
  assert.match(roleSource, /--form-control-height:52px/)
  assert.match(roleSource, /\.fld-control,[\s\S]*\.fld-picker-shell\s*\{[^}]*height:var\(--form-control-height\)[^}]*min-height:var\(--form-control-height\)/)
  assert.match(roleSource, /\.fld-in\s*\{[^}]*height:var\(--form-control-height\) !important[^}]*min-height:var\(--form-control-height\) !important/)

  assert.equal((credentialSource.match(/class="fld-control/g) || []).length, 4)
  assert.match(credentialSource, /--form-control-height:52px/)
  assert.match(credentialSource, /\.fld-control\s*\{[^}]*height:var\(--form-control-height\)[^}]*min-height:var\(--form-control-height\)/)
  assert.match(credentialSource, /\.fld-in\s*\{[^}]*height:var\(--form-control-height\) !important[^}]*min-height:var\(--form-control-height\) !important/)
})

test('credential role selection hides the super admin and developer system roles', async () => {
  const source = await readSource('src/pages/admin/credential-manage.vue')

  assert.match(source, /const BUILTIN_ROLE_IDS = new Set\(\[ROLE\.SUPER_ADMIN, ROLE\.DEVELOPER\]\)/)
  assert.match(source, /const displayRoles = computed\(\(\) => roles\.value\.filter\(\(r\) => !BUILTIN_ROLE_IDS\.has\(r\.roleId\)\)\)/)
  assert.match(source, /const editorRoles = displayRoles/)
  assert.match(source, /v-for="r in editorRoles"/)
})

test('credential details open in the same right-side drawer pattern as role details', async () => {
  const [roleSource, credentialSource] = await Promise.all([
    readSource('src/pages/admin/role-manage.vue'),
    readSource('src/pages/admin/credential-manage.vue')
  ])

  assert.match(roleSource, /class="drawer-mask"[\s\S]*class="role-drawer"/)
  assert.match(credentialSource, /v-if="credentialDetailVisible && selCred" class="drawer-mask"[\s\S]*class="credential-drawer"/)
  assert.match(credentialSource, /function selectCredential\(c\)[\s\S]*credentialDetailVisible\.value = true/)
  assert.match(credentialSource, /function closeCredentialDetail\(\)[\s\S]*credentialDetailVisible\.value = false/)
  assert.doesNotMatch(credentialSource, /class="dual-pane"|class="detail-col"/)
})
