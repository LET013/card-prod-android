import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('every admin exit schedules one coalesced reconciliation sync without blocking logout', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const logoutStart = source.indexOf('async function logout()')
  const logoutEnd = source.indexOf('async function loginLocal', logoutStart)
  const logoutSource = source.slice(logoutStart, logoutEnd)

  assert.match(source, /appState\.faceSyncPending\.push\(\{[\s\S]*operationId,[\s\S]*employeeId,[\s\S]*fileHash: result\.fileHash,[\s\S]*updatedAt: Date\.now\(\)/)
  assert.match(logoutSource, /const result = await logoutLocal\(\)/)
  assert.match(logoutSource, /triggerAdminExitFaceSync\(\)\s*\.then/)
  assert.doesNotMatch(logoutSource, /syncIdentityData/)
  assert.doesNotMatch(logoutSource, /await triggerAdminExitFaceSync/)
  assert.match(logoutSource, /正在后台同步人脸数据/)
  assert.match(logoutSource, /人脸数据同步完成/)
  assert.match(logoutSource, /人脸数据同步失败，请稍后重试/)
  assert.match(logoutSource, /后台登记与人脸同步数据不一致/)
  assert.match(logoutSource, /faceSyncScheduled: true/)
})

test('admin exit reconciles the documented registered list and only repairs actual local gaps', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const reconciliationStart = source.indexOf('async function reconcileFaceSyncAfterAdminExit()')
  const reconciliationEnd = source.indexOf('function startFaceSyncScheduler', reconciliationStart)
  const reconciliationSource = source.slice(reconciliationStart, reconciliationEnd)

  assert.match(source, /const FACE_REGISTERED_EMPLOYEES_PATH = '\/api\/v1\/employee\/face\/registered'/)
  assert.match(source, /assertBackendSuccess\(response\?\.body \|\| response, '获取已注册人脸员工', \{ requireCode: true \}\)/)
  assert.match(source, /Array\.isArray\(data\.employeeIds\)/)
  assert.match(source, /localStore\.getEmployeeFaceCounts\(\)/)
  assert.match(source, /localStore\.loadEmployees\(\{ includeDisabled: true \}\)/)
  assert.match(source, /if \(employee && !employee\.enabled\) return false/)
  assert.match(reconciliationSource, /syncPagedDataset\('faceBindings', \{ full: true \}\)/)
  assert.match(reconciliationSource, /FACE_SYNC_RECONCILIATION_INCOMPLETE/)
})

test('face template import failure stops cursor advancement instead of claiming a local binding', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const applyStart = source.indexOf('async function applyFaceSyncItems')
  const applyEnd = source.indexOf('async function recoverInterruptedFaceEnrollments', applyStart)
  const applySource = source.slice(applyStart, applyEnd)

  assert.match(applySource, /await faceTemplateImport\(\{/)
  assert.doesNotMatch(applySource, /模板导入失败，继续保存照片和绑定/)
})

test('manual employee sync includes documented face bindings before deriving face status', async () => {
  const [serviceSource, employeePageSource] = await Promise.all([
    readFile(new URL('../src/services/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/employees/employees.vue', import.meta.url), 'utf8')
  ])
  const identityStart = serviceSource.indexOf('async function syncIdentityData')
  const identityEnd = serviceSource.indexOf('/** 加载设置', identityStart)
  const identitySource = serviceSource.slice(identityStart, identityEnd)

  assert.match(identitySource, /const faceResult = employeeOnly \? null : await syncPagedDataset\('faceBindings', options\)/)
  assert.match(identitySource, /faceBindings: faceResult/)
  assert.match(employeePageSource, /services\.syncEmployees\(\{full:true,source:'EMPLOYEE_MANAGEMENT_REFRESH'\}\)/)
  assert.doesNotMatch(employeePageSource, /employeeOnly:true,source:'EMPLOYEE_MANAGEMENT_REFRESH'/)
})

test('every admin exit path uses the unified logout workflow', async () => {
  const pages = [
    'admin/admin.vue',
    'admin/change-password.vue',
    'admin/credential-manage.vue',
    'admin/role-manage.vue',
    'biometric/face.vue',
    'biometric/fingerprint.vue',
    'card-status/card-status.vue',
    'employees/employees.vue',
    'engineering/engineering.vue',
    'feature/feature.vue',
    'serial-demo/serial-demo.vue',
    'system/system.vue'
  ]
  for (const page of pages) {
    const source = await readFile(new URL(`../src/pages/${page}`, import.meta.url), 'utf8')
    assert.match(source, /await services\.logout\(\)/, `${page} must await the unified logout workflow`)
    assert.doesNotMatch(source, /await services\.logoutLocal\(\)/, `${page} must not bypass exit sync`)
  }
})
