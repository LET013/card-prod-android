const EMPLOYEE_ACTIONS = new Set(['add', 'update', 'enable', 'disable'])
const EMPLOYEE_STATUS_ACTIONS = new Set(['enable', 'disable'])

const workflowError = (code, message) => {
  const error = new Error(message)
  error.code = code
  return error
}

const normalizeLong = (value, fieldName) => {
  const number = Number(value)
  if (!Number.isSafeInteger(number)) {
    throw workflowError('EMPLOYEE_FIELD_INVALID', `${fieldName}必须为整数`)
  }
  return number
}

export function buildEmployeeMutationRequest(input = {}) {
  const action = String(input.action || 'add').trim().toLowerCase()
  if (!EMPLOYEE_ACTIONS.has(action)) {
    throw workflowError('EMPLOYEE_ACTION_INVALID', '员工操作仅支持 add、update、enable 或 disable')
  }

  if (EMPLOYEE_STATUS_ACTIONS.has(action)) {
    if (input.employeeId == null || String(input.employeeId).trim() === '') {
      throw workflowError('EMPLOYEE_ID_REQUIRED', '启用或停用员工必须提供员工ID')
    }
    return { action, employeeId: normalizeLong(input.employeeId, '员工ID') }
  }

  const employeeName = String(input.employeeName || '').trim()
  if (!employeeName) {
    throw workflowError('EMPLOYEE_NAME_REQUIRED', '员工姓名不能为空')
  }

  const request = { action, employeeName }
  if (action === 'update') {
    if (input.employeeId == null || String(input.employeeId).trim() === '') {
      throw workflowError('EMPLOYEE_ID_REQUIRED', '更新员工必须提供员工ID')
    }
    request.employeeId = normalizeLong(input.employeeId, '员工ID')
  }

  const employeeCode = input.employeeCode
  if (employeeCode != null) request.employeeCode = String(employeeCode).trim()
  const deptId = input.deptId
  if (deptId != null && String(deptId).trim() !== '') {
    request.deptId = normalizeLong(deptId, '部门ID')
  }
  for (const field of ['cardNo', 'department', 'email', 'phone', 'status', 'position']) {
    if (input[field] != null) request[field] = String(input[field]).trim()
  }

  return request
}

export function parseEmployeeMutationResponse(response, expectedAction) {
  const status = Number(response?.status || 0)
  if (status && (status < 200 || status >= 300)) {
    const payload = response?.body || {}
    throw workflowError(`HTTP_${status}`, payload.msg || payload.message || `保存员工HTTP失败(${status})`)
  }
  if (response?.error && status === 0) {
    throw workflowError('HTTP_ERROR', response.error)
  }

  const payload = response?.body || response || {}
  const code = payload?.code
  if (code == null) {
    throw workflowError('BACKEND_CODE_MISSING', '保存员工响应缺少业务状态码')
  }
  if (![0, '0'].includes(code)) {
    throw workflowError(`BACKEND_${code}`, payload.msg || payload.message || '保存员工失败')
  }

  const returnedAction = String(payload.action || '').trim().toLowerCase()
  if (returnedAction && !EMPLOYEE_ACTIONS.has(returnedAction)) {
    throw workflowError('EMPLOYEE_RESPONSE_ACTION_INVALID', '保存员工响应 action 无效')
  }
  if (returnedAction && returnedAction !== expectedAction) {
    throw workflowError('EMPLOYEE_RESPONSE_ACTION_MISMATCH', '保存员工响应 action 与请求不一致')
  }
  if (payload.employeeId == null || String(payload.employeeId).trim() === '') {
    throw workflowError('EMPLOYEE_RESPONSE_ID_MISSING', '保存员工响应缺少 employeeId')
  }

  return {
    action: returnedAction || expectedAction,
    employeeId: normalizeLong(payload.employeeId, '响应员工ID')
  }
}

export function createEmployeeMutationWorkflow({
  postEmployee,
  getEmployeeById,
  upsertEmployees,
  loadEmployees,
  replaceEmployees,
  now = Date.now
} = {}) {
  if ([postEmployee, getEmployeeById, upsertEmployees, loadEmployees, replaceEmployees].some((item) => typeof item !== 'function')) {
    throw new Error('employee mutation workflow dependencies are incomplete')
  }

  return async function mutateEmployee(input = {}) {
    const request = buildEmployeeMutationRequest(input)
    const response = await postEmployee(request)
    const confirmed = parseEmployeeMutationResponse(response, request.action)
    let cacheUpdated = false
    let cacheError = ''
    let employees = []
    let employee = null

    try {
      const existing = request.action === 'update' || EMPLOYEE_STATUS_ACTIONS.has(request.action)
        ? await getEmployeeById(String(confirmed.employeeId))
        : null
      if (EMPLOYEE_STATUS_ACTIONS.has(request.action) && !existing) {
        throw workflowError('EMPLOYEE_CACHE_MISSING', '服务端已保存，但本机未找到员工缓存')
      }
      const status = request.action === 'enable'
        ? '0'
        : (request.action === 'disable' ? '1' : (request.status ?? (existing?.enabled === false ? '1' : '0')))
      const department = request.department ?? input.departmentName ?? existing?.departmentName ?? existing?.department ?? ''
      const cachedRecord = {
        ...(existing || {}),
        employeeId: confirmed.employeeId,
        employeeCode: request.employeeCode ?? existing?.employeeCode ?? '',
        employeeNo: request.employeeCode ?? existing?.employeeNo ?? '',
        employeeName: request.employeeName ?? existing?.employeeName ?? '',
        phone: request.phone ?? existing?.phone ?? '',
        phoneNumber: request.phone ?? existing?.phoneNumber ?? '',
        cardNo: request.cardNo ?? existing?.cardNo ?? '',
        email: request.email ?? existing?.email ?? '',
        position: request.position ?? existing?.position ?? '',
        deptId: request.deptId ?? existing?.deptId,
        departmentId: request.deptId ?? existing?.departmentId,
        department,
        departmentName: department,
        status,
        updatedAt: now()
      }
      const stored = await upsertEmployees([cachedRecord], cachedRecord.updatedAt)
      if (!Array.isArray(stored?.saved) || stored.saved.length !== 1) {
        throw workflowError('EMPLOYEE_CACHE_NOT_WRITTEN', '服务端已保存，但本机员工缓存未更新')
      }
      const includeDisabled = EMPLOYEE_STATUS_ACTIONS.has(request.action)
      employees = await loadEmployees({ includeDisabled })
      replaceEmployees(includeDisabled ? employees.filter((item) => item.enabled !== false) : employees)
      employee = employees.find((item) => String(item.employeeId) === String(confirmed.employeeId)) || stored.saved[0]
      cacheUpdated = true
    } catch (error) {
      cacheError = error?.message || '本机员工缓存更新失败'
    }

    return {
      saved: true,
      action: confirmed.action,
      employeeId: confirmed.employeeId,
      employee,
      employees,
      cacheUpdated,
      cacheError
    }
  }
}
