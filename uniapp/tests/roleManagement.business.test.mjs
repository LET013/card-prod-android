import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('role editor accepts the visible no-parent option without requiring direct permissions', async () => {
  const source = await readFile(new URL('../src/pages/admin/role-manage.vue', import.meta.url), 'utf8')
  const saveStart = source.indexOf('async function saveRole()')
  const saveEnd = source.indexOf('async function deleteRole', saveStart)
  const saveSource = source.slice(saveStart, saveEnd)

  assert.match(source, /roleForm\.parentRoleId = ''/)
  assert.match(source, /无父角色/)
  assert.doesNotMatch(saveSource, /parentRoleId == null|请选择父角色或至少一项权限/)
  assert.match(saveSource, /services\.saveLocalRole/)
})

test('role list shows only the action for the current state', async () => {
  const source = await readFile(new URL('../src/pages/admin/role-manage.vue', import.meta.url), 'utf8')

  assert.match(source, /r\.enabled \? '停用该角色' : '启用该角色'/)
  assert.doesNotMatch(source, /class="tag-sm" :class="r\.enabled/)
  assert.doesNotMatch(source, /r\.enabled \? '已启用' : '已停用'/)
  assert.doesNotMatch(source, /r\.enabled \? '停用' : '启用'/)
})

test('role row aligns enlarged copy, state dot and centered action before the edge chevron', async () => {
  const source = await readFile(new URL('../src/pages/admin/role-manage.vue', import.meta.url), 'utf8')

  assert.match(source, /class="badge" :class="r\.enabled \? 'enabled' : 'disabled'"/)
  assert.match(source, /\.badge\.enabled \{ background:#22a45d; \}/)
  assert.match(source, /\.badge\.disabled \{ background:#d83b4c; \}/)
  assert.match(source, /\.list-row \{[\s\S]*?display:flex; align-items:center;/)
  assert.match(source, /\.list-name \{[\s\S]*?font-size:20px;/)
  assert.match(source, /\.list-row-sub \{[\s\S]*?padding-left:22px;/)
  assert.match(source, /\.list-code \{[\s\S]*?font-size:16px;/)
  assert.match(source, /\.role-toggle \{ min-width:112px; height:40px;[\s\S]*?flex:0 0 auto;/)
  assert.match(source, /\.row-arrow \{ position:absolute; top:50%; right:22px;[\s\S]*?font-size:52px;/)
})
