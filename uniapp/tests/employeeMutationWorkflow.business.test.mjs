import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  buildEmployeeMutationRequest,
  createEmployeeMutationWorkflow
} from '../src/services/employeeMutationWorkflow.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const createDependencies = (overrides = {}) => {
  const state = { request: null, cached: null, projection: null }
  return {
    state,
    dependencies: {
      postEmployee: async (request) => {
        state.request = request
        return { code: 0, msg: 'success', employeeId: 17, action: request.action }
      },
      getEmployeeById: async () => null,
      upsertEmployees: async (items) => {
        state.cached = items[0]
        return { saved: items, skipped: 0 }
      },
      loadEmployees: async () => [{ employeeId: '17', employeeName: '张三', enabled: true }],
      replaceEmployees: (employees) => { state.projection = employees },
      now: () => 1753001234567,
      ...overrides
    }
  }
}

test('builds the documented saveEmployee payload and caches only the backend employeeId', async () => {
  const { state, dependencies } = createDependencies()
  const mutateEmployee = createEmployeeMutationWorkflow(dependencies)
  const result = await mutateEmployee({
    action: 'add',
    employeeCode: ' EMP017 ',
    employeeName: ' 张三 ',
    deptId: '100',
    departmentName: '技术部',
    phone: '13800138000',
    department: '技术部',
    cardNo: '',
    email: 'zhangsan@example.com',
    status: '0',
    position: '工程师'
  })

  assert.deepEqual(state.request, {
    action: 'add',
    employeeName: '张三',
    employeeCode: 'EMP017',
    deptId: 100,
    department: '技术部',
    cardNo: '',
    email: 'zhangsan@example.com',
    phone: '13800138000',
    status: '0',
    position: '工程师'
  })
  assert.equal(state.cached.employeeId, 17)
  assert.equal(state.cached.updatedAt, 1753001234567)
  assert.equal(result.saved, true)
  assert.equal(result.cacheUpdated, true)
  assert.deepEqual(state.projection, result.employees)
})

test('builds documented update fields and allows the reserved cardNo to be empty', () => {
  assert.deepEqual(buildEmployeeMutationRequest({
    action: 'update',
    employeeId: '9',
    employeeName: '李四',
    cardNo: ''
  }), {
    action: 'update',
    employeeId: 9,
    employeeName: '李四',
    cardNo: ''
  })
})

test('builds minimal enable and disable payloads for employee status changes', async () => {
  assert.deepEqual(buildEmployeeMutationRequest({ action: 'enable', employeeId: '17' }), {
    action: 'enable',
    employeeId: 17
  })
  assert.deepEqual(buildEmployeeMutationRequest({ action: 'disable', employeeId: 17 }), {
    action: 'disable',
    employeeId: 17
  })

  const { state, dependencies } = createDependencies({
    getEmployeeById: async () => ({ employeeId: '17', employeeName: '张三', enabled: true }),
    loadEmployees: async () => [{ employeeId: '17', employeeName: '张三', enabled: false }]
  })
  await createEmployeeMutationWorkflow(dependencies)({ action: 'disable', employeeId: 17 })
  assert.deepEqual(state.request, { action: 'disable', employeeId: 17 })
  assert.equal(state.cached.status, '1')
  assert.deepEqual(state.projection, [])
})

test('personnel management reads disabled employees so they can be re-enabled', async () => {
  const serviceSource = await readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8')

  assert.match(serviceSource, /async function searchEmployees\(query = '', \{ includeDisabled = false \} = \{\}\)/)
  assert.match(serviceSource, /if \(includeDisabled\) \{[\s\S]*localStore\.loadEmployees\(\{ includeDisabled: true \}\)/)
  assert.match(serviceSource, /loadEmployees: \(options\) => localStore\.loadEmployees\(options\)/)
  assert.match(serviceSource, /return filterEmployeesByKeyword\(employees, keyword\)/)
})

test('falls back to the documented HTTP employee endpoint only before MQTT business login', async () => {
  const serviceSource = await readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8')

  assert.match(serviceSource, /if \(!isMqttBusinessReady\(mqttStatus\)\) \{\s*const response = await httpPost\('\/api\/v1\/employee', request\)/)
  assert.match(serviceSource, /assertHttpSuccess\(response, 'saveEmployee'\)/)
  assert.match(serviceSource, /await registerMqttBusinessHandlers\(\{ reason: 'save-employee', retry: false \}\)/)
})

test('accepts saveEmployeeResp without the optional action field', async () => {
  const { dependencies } = createDependencies({
    postEmployee: async () => ({ code: 0, msg: 'success', employeeId: 17 })
  })
  const result = await createEmployeeMutationWorkflow(dependencies)({
    action: 'add',
    employeeName: '张三'
  })
  assert.equal(result.action, 'add')
  assert.equal(result.saved, true)
})

test('rejects a generic formal Mock response without claiming success or writing cache', async () => {
  let cacheWrites = 0
  const { dependencies } = createDependencies({
    postEmployee: async () => ({ mock: true, path: '/api/v1/employee' }),
    upsertEmployees: async () => { cacheWrites += 1; return { saved: [] } }
  })
  const mutateEmployee = createEmployeeMutationWorkflow(dependencies)

  await assert.rejects(
    mutateEmployee({ action: 'add', employeeName: '张三' }),
    (error) => error?.code === 'BACKEND_CODE_MISSING'
  )
  assert.equal(cacheWrites, 0)
})

test('reports cache failure separately after a confirmed backend save', async () => {
  const { dependencies } = createDependencies({
    upsertEmployees: async () => { throw new Error('SQLite unavailable') }
  })
  const mutateEmployee = createEmployeeMutationWorkflow(dependencies)
  const result = await mutateEmployee({ action: 'add', employeeName: '张三' })

  assert.equal(result.saved, true)
  assert.equal(result.cacheUpdated, false)
  assert.match(result.cacheError, /SQLite unavailable/)
})

test('employee maintenance UI is permission-gated and calls only the business service', async () => {
  const source = await readFile(path.join(projectRoot, 'src/pages/employees/employees.vue'), 'utf8')

  assert.match(source, /canEditEmployees = computed\(\(\) => hasPermission\('employee\.edit'\)\)/)
  assert.match(source, /v-if="canEditEmployees" class="employee-add"/)
  assert.match(source, /await services\.saveEmployee\(/)
  assert.match(source, /await services\.getDepartmentTree\(\)/)
  assert.match(source, /<picker :range="departmentOptions"/)
  assert.doesNotMatch(source, /nativeBridge\./)
})
