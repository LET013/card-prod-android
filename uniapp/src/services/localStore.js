const SCHEMA_VERSION = 11

const BIOMETRIC_TYPE = Object.freeze({
  FACE: 'FACE',
  FINGER: 'FINGER'
})

export const FACE_PHOTO_MAX_BYTES = 10 * 1024 * 1024
export const FACE_PHOTO_UPLOAD_STATE = Object.freeze({
  PENDING: 'PENDING',
  UPLOADED: 'UPLOADED',
  RETRY_WAIT: 'RETRY_WAIT',
  SYNCED: 'SYNCED',
  DISABLED: 'DISABLED'
})

const FACE_PHOTO_UPLOAD_STATES = new Set(Object.values(FACE_PHOTO_UPLOAD_STATE))

export const CONFIG_KEYS = {
  BOOTSTRAP: 'bootstrapConfig',
  RUNTIME: 'runtimeConfig',
  DRAFT: 'configDraft',
  INITIAL_ADMIN: 'initialAdminState',
  OFFLINE_ACTIVATION: 'offlineActivationState',
  OFFLINE_CONFIG_META: 'offlineConfigMeta',
  LOG_UPLOAD_POLICY: 'logUploadPolicy'
}

const LEGACY_CONFIG_KEYS = {
  BOOTSTRAP: 'bootstrap_config',
  DRAFT: 'device_config_draft'
}

const now = () => Date.now()

const textEncoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : null
const PASSWORD_HASH_ITERATIONS = 30000

const bytesToHex = (bytes) => Array.from(bytes)
  .map((byte) => byte.toString(16).padStart(2, '0'))
  .join('')

const hexToBytes = (hex) => {
  const normalized = String(hex || '').trim()
  const bytes = new Uint8Array(Math.floor(normalized.length / 2))
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = parseInt(normalized.slice(index * 2, index * 2 + 2), 16)
  }
  return bytes
}

const randomHex = (length = 16) => {
  const bytes = new Uint8Array(length)
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error('当前 WebView 不支持安全随机数，本地密码未写入')
  }
  globalThis.crypto.getRandomValues(bytes)
  return bytesToHex(bytes)
}

// 设备端采用适配 rk3568 WebView 的迭代次数，保留随机盐、SHA-256 与失败锁定保护。
const pbkdf2Hash = async (value, saltHex = randomHex(), iterations = PASSWORD_HASH_ITERATIONS) => {
  if (!globalThis.crypto?.subtle || !textEncoder) {
    throw new Error('当前 WebView 不支持安全密码校验')
  }
  const keyMaterial = await globalThis.crypto.subtle.importKey(
    'raw',
    textEncoder.encode(String(value || '')),
    'PBKDF2',
    false,
    ['deriveBits']
  )
  const derived = await globalThis.crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: hexToBytes(saltHex),
      iterations,
      hash: 'SHA-256'
    },
    keyMaterial,
    256
  )
  return {
    algorithm: 'PBKDF2_SHA256',
    iterations,
    salt: saltHex,
    hash: bytesToHex(new Uint8Array(derived))
  }
}

const verifyPasswordHash = async (plain, record = {}) => {
  if (!record?.password_hash || !record?.password_salt) return false
  const derived = await pbkdf2Hash(
    plain,
    record.password_salt,
    Number(record.password_iterations || PASSWORD_HASH_ITERATIONS)
  )
  return derived.hash === record.password_hash
}

const ROLE = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  DEVELOPER: 'DEVELOPER'
}
const DEFAULT_SUPER_ADMIN_PASSWORD = '123456'
const DEFAULT_DEVELOPER_PASSWORD = '666666'
const SYSTEM_CREDENTIAL_ROLES = {
  'builtin:SUPER_ADMIN': ROLE.SUPER_ADMIN,
  'builtin:DEVELOPER': ROLE.DEVELOPER
}

const SYSTEM_ROLES = [
  { roleId: ROLE.SUPER_ADMIN, roleName: '超级管理员', parentRoleId: '', sortOrder: 1 },
  { roleId: ROLE.DEVELOPER, roleName: '开发人员', parentRoleId: '', sortOrder: 2 }
]

const LEGACY_PERMISSION_KEYS = new Set([
  'admin.manage', 'system.menu', 'management.menu', 'cabinet.*', 'cabinet.view',
  'cabinet.unlock', 'cabinet.unlockAll', 'auth.password.manage', 'app.restart',
  'settings.basic', 'settings.advanced', 'upgrade.*', 'upgrade.app',
  'upgrade.firmware', 'biometric.register', 'employee.view', 'employee.edit',
  'unit.view', 'history.view', 'authorization.manage', 'debug.command'
])

const LEGACY_PERMISSION_REPLACEMENTS = {
  'admin.manage': ['account.role.*', 'account.user.*', 'account.secondary-password.change'],
  'system.menu': ['realtime.communication.*'],
  'cabinet.*': ['realtime.slot.*', 'maintenance.cabinet.eject-all', 'maintenance.hardware.view'],
  'cabinet.view': ['realtime.slot.view', 'maintenance.hardware.view'],
  'cabinet.unlock': ['realtime.slot.open'],
  'cabinet.unlockAll': ['maintenance.cabinet.eject-all'],
  'auth.password.manage': ['account.password.change'],
  'app.restart': ['system.restart'],
  'settings.basic': ['system.settings.*'],
  'settings.advanced': [],
  'upgrade.*': ['maintenance.app.upgrade', 'maintenance.firmware.*'],
  'upgrade.app': ['maintenance.app.upgrade'],
  'upgrade.firmware': ['maintenance.firmware.*'],
  'biometric.register': ['system.face.*', 'system.employee.face-register'],
  'employee.view': ['system.employee.view'],
  'employee.edit': ['system.employee.create', 'system.employee.update', 'system.employee.enable'],
  'unit.view': ['system.unit.view'],
  'history.view': ['system.history.view'],
  'authorization.manage': ['system.authorization.*'],
  'debug.command': ['maintenance.serial.*']
}

const RETIRED_PERMISSION_KEYS = ['system.settings.advanced']
const PERMISSION_TREE_VERSION = 'permission-tree-v3'
const SEED_PERMISSIONS = [
  { key: '*', name: '全部权限', parentKey: '' },
  { key: 'account.*', name: '账号权限', parentKey: '*' },
  { key: 'account.role.*', name: '角色管理', parentKey: 'account.*' },
  { key: 'account.role.view', name: '查看角色', parentKey: 'account.role.*' },
  { key: 'account.role.create', name: '新增角色', parentKey: 'account.role.*' },
  { key: 'account.role.update', name: '编辑角色', parentKey: 'account.role.*' },
  { key: 'account.role.enable', name: '启用/停用角色', parentKey: 'account.role.*' },
  { key: 'account.role.delete', name: '删除角色', parentKey: 'account.role.*' },
  { key: 'account.user.*', name: '用户管理', parentKey: 'account.*' },
  { key: 'account.user.view', name: '查看用户', parentKey: 'account.user.*' },
  { key: 'account.user.create', name: '新增用户', parentKey: 'account.user.*' },
  { key: 'account.user.update', name: '编辑用户', parentKey: 'account.user.*' },
  { key: 'account.user.unlock', name: '解锁用户', parentKey: 'account.user.*' },
  { key: 'account.user.delete', name: '删除用户', parentKey: 'account.user.*' },
  { key: 'account.password.change', name: '修改登录密码', parentKey: 'account.*' },
  { key: 'account.secondary-password.change', name: '修改管理二级密码', parentKey: 'account.*' },
  { key: 'system.*', name: '系统管理', parentKey: '*' },
  { key: 'system.face.*', name: '人脸注册', parentKey: 'system.*' },
  { key: 'system.face.sync', name: '同步人员', parentKey: 'system.face.*' },
  { key: 'system.face.search', name: '搜索人员', parentKey: 'system.face.*' },
  { key: 'system.face.register', name: '录入人脸', parentKey: 'system.face.*' },
  { key: 'system.employee.*', name: '人员管理', parentKey: 'system.*' },
  { key: 'system.employee.view', name: '查看人员', parentKey: 'system.employee.*' },
  { key: 'system.employee.sync', name: '手动同步', parentKey: 'system.employee.*' },
  { key: 'system.employee.create', name: '新增人员', parentKey: 'system.employee.*' },
  { key: 'system.employee.update', name: '编辑人员', parentKey: 'system.employee.*' },
  { key: 'system.employee.enable', name: '停用/恢复人员', parentKey: 'system.employee.*' },
  { key: 'system.employee.face-register', name: '人员人脸录入', parentKey: 'system.employee.*' },
  { key: 'system.unit.view', name: '单元管理', parentKey: 'system.*' },
  { key: 'system.history.view', name: '历史管理', parentKey: 'system.*' },
  { key: 'system.settings.*', name: '设备设置', parentKey: 'system.*' },
  { key: 'system.settings.view', name: '查看设备设置', parentKey: 'system.settings.*' },
  { key: 'system.settings.edit', name: '保存设备设置', parentKey: 'system.settings.*' },
  { key: 'system.authorization.*', name: '系统授权', parentKey: 'system.*' },
  { key: 'system.authorization.view', name: '查看系统授权', parentKey: 'system.authorization.*' },
  { key: 'system.authorization.refresh', name: '刷新授权信息', parentKey: 'system.authorization.*' },
  { key: 'system.restart', name: '重启应用', parentKey: 'system.*' },
  { key: 'maintenance.*', name: '设备维护', parentKey: '*' },
  { key: 'maintenance.app.upgrade', name: 'APP升级', parentKey: 'maintenance.*' },
  { key: 'maintenance.firmware.*', name: '固件升级', parentKey: 'maintenance.*' },
  { key: 'maintenance.firmware.board', name: '单板升级', parentKey: 'maintenance.firmware.*' },
  { key: 'maintenance.firmware.work-card', name: '工作卡升级', parentKey: 'maintenance.firmware.*' },
  { key: 'maintenance.firmware.main-board', name: '主板升级', parentKey: 'maintenance.firmware.*' },
  { key: 'maintenance.cabinet.eject-all', name: '一键弹出', parentKey: 'maintenance.*' },
  { key: 'maintenance.hardware.view', name: '硬件版本号', parentKey: 'maintenance.*' },
  { key: 'maintenance.serial.*', name: '串口调试台', parentKey: 'maintenance.*' },
  { key: 'maintenance.serial.config', name: '串口配置', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.admin-take', name: '管理员取卡', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.normal-take', name: '普通取卡', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.led', name: 'LED亮度调整', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.eject-all', name: '调试一键弹卡', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.read-status', name: '读取卡状态', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.read-version', name: '读取版本信息', parentKey: 'maintenance.serial.*' },
  { key: 'maintenance.serial.manual-command', name: '手动指令下发', parentKey: 'maintenance.serial.*' },
  { key: 'realtime.*', name: '实时状态', parentKey: '*' },
  { key: 'realtime.slot.*', name: '卡位实时状态', parentKey: 'realtime.*' },
  { key: 'realtime.slot.view', name: '查看卡位状态', parentKey: 'realtime.slot.*' },
  { key: 'realtime.slot.open', name: '卡槽开门', parentKey: 'realtime.slot.*' },
  { key: 'realtime.communication.*', name: '通信状态', parentKey: 'realtime.*' },
  { key: 'realtime.communication.view', name: '查看通信状态', parentKey: 'realtime.communication.*' },
  { key: 'realtime.communication.refresh', name: '刷新通信状态', parentKey: 'realtime.communication.*' },
  { key: 'realtime.communication.clear-log', name: '清空通信日志', parentKey: 'realtime.communication.*' }
]

const ROLE_PERMISSIONS = {
  [ROLE.SUPER_ADMIN]: ['*'],
  [ROLE.DEVELOPER]: ['*']
}

const AUTH_SESSION_KEY = 'current'
const DEFAULT_SESSION_TTL_SECONDS = 3600
const PASSWORD_LOCK_MS = 5 * 60 * 1000
const PASSWORD_MAX_FAILED = 5
const isSixDigitPassword = (value) => /^\d{6}$/.test(String(value || '').trim())
const permissionCategoryFromKey = (permissionKey = '') => {
  const key = String(permissionKey || '').trim()
  if (!key || key === '*') return 'root'
  return key.split('.')[0] || 'custom'
}

const DEFAULT_OPERABLE_ITEMS = [
  { code: 'realtime.communication.view', label: '通信状态', type: 'menu', route: '/pages/system/system' },
  { code: 'realtime.slot.view', label: '查看卡位状态', type: 'page', route: '/pages/card-status/card-status' },
  { code: 'realtime.slot.open', label: '卡槽开门', type: 'action', action: 'realtime.slot.open' },
  { code: 'maintenance.cabinet.eject-all', label: '一键弹出', type: 'action', action: 'maintenance.cabinet.eject-all' },
  { code: 'system.employee.view', label: '查看人员', type: 'page', route: '/pages/employees/employees' },
  { code: 'system.employee.update', label: '编辑人员', type: 'action', action: 'system.employee.update' },
  { code: 'system.face.register', label: '录入人脸', type: 'action', action: 'system.face.register' },
  { code: 'system.history.view', label: '历史管理', type: 'page', route: '/pages/feature/feature?type=history' },
  { code: 'system.unit.view', label: '单元管理', type: 'page', route: '/pages/feature/feature?type=units' },
  { code: 'system.settings.view', label: '查看设备设置', type: 'page', route: '/pages/config/config' },
  { code: 'system.authorization.view', label: '查看系统授权', type: 'action', action: 'system.authorization.view' },
  { code: 'maintenance.app.upgrade', label: 'APP升级', type: 'action', action: 'maintenance.app.upgrade' },
  { code: 'maintenance.firmware.board', label: '单板升级', type: 'action', action: 'maintenance.firmware.board' },
  { code: 'maintenance.serial.manual-command', label: '手动指令下发', type: 'action', action: 'maintenance.serial.manual-command' },
  { code: 'account.password.change', label: '修改登录密码', type: 'action', action: 'account.password.change' },
  { code: 'system.restart', label: '重启应用', type: 'action', action: 'system.restart' }
]

const schemaStatements = [
  'CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS vue_local_config (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS employees (employee_id TEXT PRIMARY KEY, employee_code TEXT, employee_name TEXT NOT NULL, department_name TEXT, enabled INTEGER NOT NULL DEFAULT 1, auth_state TEXT, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT NOT NULL)',
  'CREATE INDEX IF NOT EXISTS idx_employees_enabled ON employees(enabled)',
  'CREATE TABLE IF NOT EXISTS face_bindings (biometric_type TEXT NOT NULL, binding_id TEXT NOT NULL, employee_id TEXT NOT NULL, native_id TEXT, biometric_index INTEGER, enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, expires_at INTEGER, mime_type TEXT, byte_size INTEGER, photo_base64 TEXT, source TEXT, upload_state TEXT, upload_id TEXT, server_path TEXT, server_url TEXT, file_hash TEXT, last_error TEXT, synced_at INTEGER, raw_json TEXT, PRIMARY KEY(biometric_type, binding_id), FOREIGN KEY(employee_id) REFERENCES employees(employee_id))',
  'CREATE TABLE IF NOT EXISTS slots_snapshot (slot_number INTEGER PRIMARY KEY, status TEXT NOT NULL, card_id TEXT, employee_id TEXT, source TEXT NOT NULL, fresh INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, raw_json TEXT NOT NULL)',
  'CREATE INDEX IF NOT EXISTS idx_slots_snapshot_status ON slots_snapshot(status)',
  'CREATE TABLE IF NOT EXISTS operations (operation_id TEXT PRIMARY KEY, operation_type TEXT NOT NULL, employee_id TEXT, face_id TEXT, slot_number INTEGER, card_no TEXT, physical_confirmed_at INTEGER, state TEXT NOT NULL, offline INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, finished_at INTEGER, raw_json TEXT NOT NULL)',
  'CREATE INDEX IF NOT EXISTS idx_operations_state ON operations(state, updated_at)',
  'CREATE TABLE IF NOT EXISTS outbox_events (event_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, operation_id TEXT, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT \'PENDING\', attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, acked_at INTEGER)',
  'CREATE INDEX IF NOT EXISTS idx_outbox_state ON outbox_events(state, next_attempt_at)',
  'CREATE TABLE IF NOT EXISTS sync_cursors (scope TEXT PRIMARY KEY, fetched_version INTEGER NOT NULL DEFAULT 0, applied_version INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, raw_json TEXT)',
  'CREATE TABLE IF NOT EXISTS audit_events (event_id TEXT PRIMARY KEY, session_ref TEXT, actor_credential_id TEXT NOT NULL, actor_label TEXT, role_ids_json TEXT, event_type TEXT NOT NULL, feature_code TEXT, feature_label TEXT, route TEXT, action_code TEXT, action_label TEXT, source TEXT NOT NULL DEFAULT \'LOCAL_UI\', occurred_at INTEGER NOT NULL, metadata_json TEXT, created_at INTEGER NOT NULL)',
  'CREATE INDEX IF NOT EXISTS idx_audit_events_occurred ON audit_events(occurred_at)',
  'CREATE INDEX IF NOT EXISTS idx_audit_events_actor_time ON audit_events(actor_credential_id, occurred_at)'
]

const rbacSchemaStatements = [
  'CREATE TABLE IF NOT EXISTS local_roles (role_id TEXT PRIMARY KEY, role_name TEXT NOT NULL, parent_role_id TEXT, is_system INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, description TEXT NOT NULL DEFAULT \'\', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, raw_json TEXT)',
  'CREATE INDEX IF NOT EXISTS idx_local_roles_parent ON local_roles(parent_role_id)',
  'CREATE TABLE IF NOT EXISTS local_permissions (permission_key TEXT PRIMARY KEY, permission_name TEXT NOT NULL, parent_key TEXT, category TEXT NOT NULL DEFAULT \'\', item_type TEXT NOT NULL DEFAULT \'action\', enabled INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, description TEXT NOT NULL DEFAULT \'\', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, raw_json TEXT)',
  'CREATE INDEX IF NOT EXISTS idx_local_permissions_parent ON local_permissions(parent_key)',
  'CREATE TABLE IF NOT EXISTS local_role_permissions (role_id TEXT NOT NULL, permission_key TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(role_id, permission_key), FOREIGN KEY(role_id) REFERENCES local_roles(role_id), FOREIGN KEY(permission_key) REFERENCES local_permissions(permission_key))',
  'CREATE INDEX IF NOT EXISTS idx_local_role_permissions_permission ON local_role_permissions(permission_key)',
  'CREATE TABLE IF NOT EXISTS local_auth_credentials (credential_id TEXT PRIMARY KEY, credential_label TEXT NOT NULL, password_hash TEXT NOT NULL, password_salt TEXT NOT NULL, password_iterations INTEGER NOT NULL DEFAULT 100000, password_algorithm TEXT NOT NULL DEFAULT \'PBKDF2_SHA256\', password_state TEXT NOT NULL DEFAULT \'ACTIVE\', enabled INTEGER NOT NULL DEFAULT 1, is_system INTEGER NOT NULL DEFAULT 0, failed_count INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0, ttl_seconds INTEGER NOT NULL DEFAULT 3600, last_login_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, raw_json TEXT)',
  'CREATE TABLE IF NOT EXISTS local_credential_roles (credential_id TEXT NOT NULL, role_id TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(credential_id, role_id), FOREIGN KEY(credential_id) REFERENCES local_auth_credentials(credential_id), FOREIGN KEY(role_id) REFERENCES local_roles(role_id))',
  'CREATE INDEX IF NOT EXISTS idx_local_credential_roles_role ON local_credential_roles(role_id)',
  'CREATE TABLE IF NOT EXISTS local_auth_session (session_key TEXT PRIMARY KEY, credential_id TEXT NOT NULL, role_ids TEXT NOT NULL, login_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, ttl_seconds INTEGER NOT NULL DEFAULT 3600, updated_at INTEGER NOT NULL, FOREIGN KEY(credential_id) REFERENCES local_auth_credentials(credential_id))',
  'CREATE TABLE IF NOT EXISTS local_secondary_password (password_id TEXT PRIMARY KEY, password_hash TEXT NOT NULL, password_salt TEXT NOT NULL, password_iterations INTEGER NOT NULL DEFAULT 100000, password_algorithm TEXT NOT NULL DEFAULT \'PBKDF2_SHA256\', enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, raw_json TEXT)',
  'CREATE TABLE IF NOT EXISTS local_operable_items (item_code TEXT PRIMARY KEY, permission_key TEXT NOT NULL, item_type TEXT NOT NULL, label TEXT NOT NULL, route TEXT, action TEXT, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, raw_json TEXT, FOREIGN KEY(permission_key) REFERENCES local_permissions(permission_key))',
  'CREATE INDEX IF NOT EXISTS idx_local_operable_items_permission ON local_operable_items(permission_key)'
]

const authTables = [
  'local_auth_session',
  'local_credential_roles',
  'local_auth_credentials',
  'local_secondary_password',
  'local_operable_items',
  'local_role_permissions',
  'local_permissions',
  'local_users',
  'local_roles'
]

const dropOrClearTable = async (execute, table) => {
  try {
    await execute(`DROP TABLE IF EXISTS ${table}`)
    return
  } catch (error) {
    const message = String(error?.message || error || '')
    if (!message.includes('Unsupported SQL')) throw error
  }
  try {
    await execute(`DELETE FROM ${table}`)
  } catch (error) {
    // Mock SQLite may not know old tables before they are created; that is equivalent to empty.
  }
}

const normalizeRows = (result) => {
  if (!result) return []
  if (Array.isArray(result)) return result
  const rows = result.rows || result.data?.rows || result.body?.rows
  if (Array.isArray(rows)) return rows
  if (rows && typeof rows.length === 'number' && typeof rows.item === 'function') {
    return Array.from({ length: rows.length }, (_, index) => rows.item(index))
  }
  return []
}

const parseJson = (value, fallback = null) => {
  if (!value || typeof value !== 'string') return fallback
  try { return JSON.parse(value) } catch (error) { return fallback }
}

const formatDateTime = (value) => {
  const time = Number(value || 0)
  if (!Number.isFinite(time) || time <= 0) return ''
  const date = new Date(time)
  const pad = (number) => String(number).padStart(2, '0')
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate())
  ].join('-') + ' ' + [
    pad(date.getHours()),
    pad(date.getMinutes()),
    pad(date.getSeconds())
  ].join(':')
}

const operationTypeLabel = (type) => {
  const normalized = String(type || '').toUpperCase()
  const labels = {
    ADMIN_UNLOCK: '管理员开门',
    ADMIN_TAKE_CARD: '管理员取卡',
    ADMIN_EJECT_SLOT: '管理员单槽弹卡',
    REMOTE_OPEN: '后台开柜',
    REMOTE_EJECT_ALL: '后台一键弹卡',
    TAKE_CARD: '取卡',
    RETURN_CARD: '还卡',
    FACE_OPEN: '人脸开门',
    FACE_ENROLLMENT: '人脸录入',
    UNLOCK_ALL: '一键弹卡',
    RESTART_APP: '重启应用',
    APP_UPDATE: 'APP升级',
    APP_UPDATE_CHECK: '手动检查更新',
    FIRMWARE_UPGRADE: '固件升级',
    DEVICE_SELF_CHECK: '设备自检',
    EMPLOYEE_ADD: '新增人员',
    EMPLOYEE_UPDATE: '修改人员',
    EMPLOYEE_DISABLE: '停用人员',
    EMPLOYEE_ENABLE: '启用人员'
  }
  return labels[normalized] || normalized || '操作'
}

const TERMINAL_OPERATION_STATES = new Set([
  'SUCCESS', 'SUCCEEDED', 'DONE', 'COMPLETED',
  'TRANSMITTED',
  'FAILED', 'ERROR', 'TIMEOUT', 'TIMED_OUT', 'DENIED',
  'CANCELLED', 'PARTIAL'
])

const operationResultMeta = (state) => {
  const normalized = String(state || '').toUpperCase()
  if (['SUCCESS', 'SUCCEEDED', 'DONE', 'COMPLETED'].includes(normalized)) {
    return { label: '成功', kind: 'success' }
  }
  if (normalized === 'TRANSMITTED') return { label: '已传输，待真机验证', kind: 'warning' }
  if (normalized === 'SERIAL_SENT') return { label: '指令已发送', kind: 'pending' }
  if (normalized === 'REPORT_PENDING') return { label: '已完成，待同步', kind: 'warning' }
  if (normalized === 'PARTIAL') return { label: '部分完成', kind: 'warning' }
  if (normalized === 'CANCELLED') return { label: '已取消', kind: 'warning' }
  if (['FAILED', 'ERROR', 'TIMEOUT', 'TIMED_OUT', 'DENIED'].includes(normalized)) {
    return { label: normalized === 'TIMED_OUT' || normalized === 'TIMEOUT' ? '已超时' : '失败', kind: 'failed' }
  }
  if (RECOVERABLE_OPERATION_STATES.has(normalized)) return { label: '处理中', kind: 'pending' }
  return { label: '未知状态', kind: 'unknown' }
}

const RECOVERABLE_OPERATION_STATE_LIST = Object.freeze([
  'RECEIVED',
  'CREATED',
  'VALIDATED',
  'QUEUED',
  'PENDING',
  'PROCESSING',
  'RUNNING',
  'CAPTURED',
  'PHOTO_UPLOADING',
  'RECORD_CREATING',
  'SERVER_CREATED',
  'TEMPLATE_APPLIED',
  'RESTART_SCHEDULED',
  'DOWNLOADING',
  'VERIFIED',
  'PERMISSION_REQUIRED',
  'INSTALL_REQUESTED',
  'INSTALLER_OPENED',
  'SERIAL_SENT',
  'SERIAL_ACKED',
  'BOARD_ACKED',
  'PHYSICAL_PENDING',
  'PHYSICAL_CONFIRMED',
  'REPORT_PENDING'
])
const RECOVERABLE_OPERATION_STATES = new Set(RECOVERABLE_OPERATION_STATE_LIST)
const PHYSICAL_EVIDENCE_OPERATION_STATE_LIST = Object.freeze([
  'PHYSICAL_CONFIRMED',
  'REPORT_PENDING',
  'COMPLETED'
])

const isEnabledRow = (value) => Number(value) === 1 || value === true

const isExpiredAt = (expiresAt, atMs) => {
  const timestamp = Number(expiresAt || 0)
  return timestamp > 0 && timestamp <= atMs
}

const employeeFromRow = (row) => {
  if (!row) return null
  const raw = parseJson(row.raw_json, {}) || {}
  const employeeId = String(row.employee_id ?? raw.employeeId ?? raw.id ?? '').trim()
  if (!employeeId) return null
  return {
    ...raw,
    id: raw.id || employeeId,
    employeeId,
    employeeCode: String(row.employee_code ?? raw.employeeCode ?? ''),
    employeeName: String(row.employee_name ?? raw.employeeName ?? ''),
    departmentName: String(row.department_name ?? raw.departmentName ?? raw.department ?? ''),
    enabled: isEnabledRow(row.enabled ?? raw.enabled),
    authState: String(row.auth_state ?? raw.authState ?? ''),
    updatedAt: Number(row.updated_at ?? raw.updatedAt ?? 0),
    expiresAt: Number(row.expires_at ?? raw.expiresAt ?? 0) || null
  }
}

const faceBindingFromRow = (row) => {
  if (!row) return null
  const raw = cleanBiometricRaw(parseJson(row.raw_json, {}) || {})
  const faceId = String(row.binding_id ?? row.face_id ?? raw.faceId ?? '').trim()
  const unifiedRow = row.biometric_type != null || row.binding_id != null
  const faceAiId = String(unifiedRow
    ? (row.native_id ?? '')
    : (row.face_ai_id ?? raw.faceAiId ?? faceId)).trim()
  const employeeId = String(row.employee_id ?? raw.employeeId ?? '').trim()
  if (!faceId || !faceAiId || !employeeId) return null
  return {
    ...raw,
    faceId,
    faceAiId,
    employeeId,
    faceIndex: (row.biometric_index ?? row.face_index) == null
      ? null
      : Number(row.biometric_index ?? row.face_index),
    enabled: isEnabledRow(row.enabled ?? raw.enabled),
    updatedAt: Number(row.updated_at ?? raw.updatedAt ?? 0),
    expiresAt: Number(row.expires_at ?? raw.expiresAt ?? 0) || null
  }
}

const facePhotoFromRow = (row) => {
  if (!row) return null
  const faceId = String(row.binding_id || row.face_id || '').trim()
  const employeeId = String(row.employee_id || '').trim()
  const photoBase64 = String(row.photo_base64 || '').trim()
  if (!faceId || !employeeId || !photoBase64) return null
  return {
    ...(parseJson(row.raw_json, {}) || {}),
    faceId,
    employeeId,
    mimeType: String(row.mime_type || 'image/jpeg'),
    byteSize: Number(row.byte_size || 0),
    photoBase64,
    source: String(row.source || 'UNKNOWN'),
    uploadState: String(row.upload_state || FACE_PHOTO_UPLOAD_STATE.PENDING),
    uploadId: String(row.upload_id || ''),
    serverPath: String(row.server_path || ''),
    serverUrl: String(row.server_url || ''),
    fileHash: String(row.file_hash || ''),
    lastError: String(row.last_error || ''),
    updatedAt: Number(row.updated_at || 0),
    syncedAt: Number(row.synced_at || 0) || null
  }
}

const slotFromRow = (row) => {
  if (!row) return null
  const raw = parseJson(row.raw_json, {}) || {}
  const slotNumber = Number(row.slot_number ?? raw.slotNumber ?? 0)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return null
  const cardId = String(row.card_id ?? raw.cardId ?? raw.cardNo ?? raw.cardNumber ?? '')
  return {
    ...raw,
    id: raw.id || `slot-${slotNumber}`,
    slotNumber,
    displayNumber: raw.displayNumber || String(slotNumber).padStart(2, '0'),
    status: String(row.status ?? raw.status ?? 'UNKNOWN').toUpperCase(),
    cardId,
    employeeId: String(row.employee_id ?? raw.employeeId ?? ''),
    source: String(row.source ?? raw.source ?? ''),
    fresh: isEnabledRow(row.fresh ?? raw.fresh),
    updatedAt: Number(row.updated_at ?? raw.updatedAt ?? 0)
  }
}

const operationFromRow = (row) => {
  if (!row) return null
  const raw = parseJson(row.raw_json, {}) || {}
  const operationId = String(row.operation_id ?? raw.operationId ?? '').trim()
  if (!operationId) return null
  return {
    ...raw,
    operationId,
    operationType: String(row.operation_type ?? raw.operationType ?? '').toUpperCase(),
    employeeId: String(row.employee_id ?? raw.employeeId ?? ''),
    faceId: String(row.face_id ?? raw.faceId ?? ''),
    slotNumber: row.slot_number == null ? null : Number(row.slot_number),
    cardNo: String(row.card_no ?? raw.cardNo ?? '').trim(),
    physicalConfirmedAt: Number(row.physical_confirmed_at ?? raw.physicalConfirmedAt ?? 0) || null,
    state: String(row.state ?? raw.state ?? '').toUpperCase(),
    offline: isEnabledRow(row.offline ?? raw.offline),
    createdAt: Number(row.created_at ?? raw.createdAt ?? 0),
    updatedAt: Number(row.updated_at ?? raw.updatedAt ?? 0),
    finishedAt: Number(row.finished_at ?? raw.finishedAt ?? 0) || null
  }
}

const outboxEventFromRow = (row) => {
  if (!row) return null
  return {
    eventId: row.event_id,
    eventType: row.event_type,
    operationId: row.operation_id || '',
    payload: parseJson(row.payload, null),
    state: row.state,
    attemptCount: Number(row.attempt_count || 0),
    nextAttemptAt: Number(row.next_attempt_at || 0),
    lastError: row.last_error || '',
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0),
    ackedAt: Number(row.acked_at || 0)
  }
}

const createLocalId = (prefix) => {
  if (globalThis.crypto?.randomUUID) return `${prefix}:${globalThis.crypto.randomUUID()}`
  return `${prefix}:${now()}:${Math.floor(Math.random() * 1000000)}`
}

const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address)

const cleanBiometricRaw = (value = {}) => {
  const raw = { ...(value || {}) }
  delete raw.faceFeature
  delete raw.faceImage
  delete raw.faceImageBase64
  delete raw.fingerFeature
  return raw
}

const cleanFacePhotoMetadata = (value = {}) => {
  const raw = cleanBiometricRaw(value)
  delete raw.photoBase64
  delete raw.imageBase64
  delete raw.base64
  delete raw.faceFeature
  return raw
}

const normalizePhotoBase64 = (rawValue) => {
  let value = String(rawValue || '').trim()
  const commaIndex = value.indexOf(',')
  if (value.startsWith('data:') && commaIndex >= 0) value = value.slice(commaIndex + 1)
  value = value.replace(/\s+/g, '')
  if (!value || !/^[A-Za-z0-9+/]*={0,2}$/.test(value) || value.length % 4 === 1) {
    return { ok: false, reason: 'INVALID_FACE_PHOTO_BASE64' }
  }
  const padding = value.endsWith('==') ? 2 : (value.endsWith('=') ? 1 : 0)
  const byteSize = Math.floor(value.length * 3 / 4) - padding
  if (byteSize <= 0) return { ok: false, reason: 'EMPTY_FACE_PHOTO' }
  if (byteSize > FACE_PHOTO_MAX_BYTES) {
    return { ok: false, reason: 'FACE_PHOTO_TOO_LARGE', byteSize, maxBytes: FACE_PHOTO_MAX_BYTES }
  }
  return { ok: true, photoBase64: value, byteSize }
}

const normalizeEnabled = (status) => String(status ?? '0') !== '1'

const normalizeEmployeeRecord = (employee, syncVersion = 0) => {
  const employeeId = String(employee?.employeeId ?? '').trim()
  const employeeName = String(employee?.employeeName ?? '').trim()
  if (!employeeId || !employeeName) return null
  const updatedAt = Number(employee?.updatedAt || employee?.updateTime || syncVersion || now())
  const enabled = normalizeEnabled(employee?.status)
  const raw = {
    ...employee,
    id: employeeId,
    employeeId,
    employeeName,
    employeeCode: employee.employeeCode || '',
    departmentName: employee.departmentName || employee.department || '',
    faceRegistered: employee.faceRegistered === '1' || employee.faceRegistered === 1 || employee.faceRegistered === true,
    fingerprintRegistered: employee.fingerRegistered === '1' || employee.fingerRegistered === 1 || employee.fingerRegistered === true || employee.fingerprintRegistered === true,
    enabled,
    updatedAt
  }
  return {
    employeeId,
    employeeCode: raw.employeeCode,
    employeeName,
    departmentName: raw.departmentName,
    enabled: enabled ? 1 : 0,
    authState: String(employee.status ?? ''),
    updatedAt,
    expiresAt: Number(employee.expiresAt || employee.expireTime || 0) || null,
    raw
  }
}

const normalizeFaceBindingRecord = (item, syncVersion = 0) => {
  const faceId = String(item?.faceId ?? '').trim()
  const faceAiId = String(item?.faceAiId ?? faceId).trim()
  const employeeId = String(item?.employeeId ?? '').trim()
  if (!faceId || !faceAiId || !employeeId) return null
  const updatedAt = Number(item?.updatedAt || item?.updateTime || syncVersion || now())
  const enabled = typeof item?.enabled === 'boolean'
    ? item.enabled
    : normalizeEnabled(item?.status)
  const raw = {
    ...cleanBiometricRaw(item),
    faceId,
    faceAiId,
    employeeId,
    enabled,
    updatedAt
  }
  return {
    faceId,
    faceAiId,
    employeeId,
    faceIndex: Number.isInteger(Number(item.faceIndex)) ? Number(item.faceIndex) : null,
    enabled: raw.enabled ? 1 : 0,
    updatedAt,
    expiresAt: Number(item.expiresAt || item.expireTime || 0) || null,
    raw
  }
}

const normalizeFingerBindingRecord = (item, syncVersion = 0) => {
  const fingerId = String(item?.fingerId ?? '').trim()
  const employeeId = String(item?.employeeId ?? '').trim()
  if (!fingerId || !employeeId) return null
  const updatedAt = Number(item?.updatedAt || item?.updateTime || syncVersion || now())
  const raw = {
    ...cleanBiometricRaw(item),
    fingerId,
    employeeId,
    fingerIndex: Number(item.fingerIndex || 0) || null,
    enabled: normalizeEnabled(item?.status),
    updatedAt
  }
  return {
    fingerId,
    employeeId,
    fingerIndex: raw.fingerIndex,
    enabled: raw.enabled ? 1 : 0,
    updatedAt,
    expiresAt: Number(item.expiresAt || item.expireTime || 0) || null,
    raw
  }
}

const normalizeSlotRecord = (slot, source = 'SERIAL', fresh = true) => {
  const slotNumber = slotNumberOf(slot)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return null
  const status = String(slot.status || slot.chargingStatus || 'UNKNOWN').toUpperCase()
  const updatedAt = Number(slot.updatedAt || slot.updated_at || now())
  const raw = {
    ...slot,
    id: slot.id || `slot-${slotNumber}`,
    slotNumber,
    displayNumber: slot.displayNumber || String(slotNumber).padStart(2, '0'),
    status,
    fresh: Boolean(fresh),
    source,
    updatedAt
  }
  return {
    slotNumber,
    status,
    cardId: slot.cardId || slot.cardNo || slot.cardNumber || '',
    employeeId: slot.employeeId || '',
    source,
    fresh: fresh ? 1 : 0,
    updatedAt,
    raw
  }
}

export function createLocalStore(adapter = {}) {
  let storage = adapter
  let initialized = false
  let initPromise = null

  const setAdapter = (nextAdapter = {}) => {
    storage = nextAdapter
    initialized = false
    initPromise = null
  }

  const execute = (sql, params = []) => {
    if (!storage || typeof storage.execute !== 'function') {
      throw new Error('SQLite execute adapter is unavailable')
    }
    return storage.execute(sql, params)
  }

  const query = (sql, params = []) => {
    if (!storage || typeof storage.query !== 'function') {
      throw new Error('SQLite query adapter is unavailable')
    }
    return storage.query(sql, params)
  }

  const initializeSchema = async () => {
    if (initialized) return { schemaVersion: SCHEMA_VERSION }
    if (initPromise) return initPromise
    initPromise = (async () => {
      for (const sql of schemaStatements) await execute(sql)
      await execute(
        "DELETE FROM outbox_events WHERE event_type = ? AND state != ? AND (operation_id LIKE ? OR operation_id LIKE ?)",
        ['CARD_EVENT', 'SENT', 'unlockAll:%', 'adminTake:%']
      )
      await ensureOperationSchema()
      await ensureUnifiedFaceBindingsSchema()
      await ensureAuditEventsSchema()
      await ensureRbacSchema()
      await seedAccessControlDefaults()
      await execute(
        'INSERT OR REPLACE INTO schema_meta(key, value, updated_at) VALUES(?, ?, ?)',
        ['schemaVersion', String(SCHEMA_VERSION), now()]
      )
      await execute(
        'INSERT OR REPLACE INTO schema_meta(key, value, updated_at) VALUES(?, ?, ?)',
        ['lastMigrationAt', String(now()), now()]
      )
      initialized = true
      return { schemaVersion: SCHEMA_VERSION }
    })()
    try {
      return await initPromise
    } catch (error) {
      initialized = false
      throw error
    } finally {
      initPromise = null
    }
  }

  const saveJsonConfig = async (key, value) => {
    await initializeSchema()
    await execute(
      'INSERT OR REPLACE INTO vue_local_config(key, value, updated_at) VALUES(?, ?, ?)',
      [key, JSON.stringify(value || {}), now()]
    )
    return value || {}
  }

  const loadJsonConfig = async (key, fallbackKeys = []) => {
    await initializeSchema()
    const keys = [key, ...fallbackKeys]
    for (const currentKey of keys) {
      const result = await query('SELECT value FROM vue_local_config WHERE key = ? LIMIT 1', [currentKey])
      const row = normalizeRows(result)[0]
      const value = parseJson(row?.value, null)
      if (value) return value
    }
    return null
  }

  const currentSchemaVersion = async () => {
    try {
      const result = await query('SELECT value FROM schema_meta WHERE key = ? LIMIT 1', ['schemaVersion'])
      return Number(normalizeRows(result)[0]?.value || 0)
    } catch (error) {
      return 0
    }
  }

  const ensureRbacSchema = async () => {
    const schemaVersion = await currentSchemaVersion()
    if (schemaVersion < 3) {
      for (const table of authTables) {
        await dropOrClearTable(execute, table)
      }
    }
    for (const sql of rbacSchemaStatements) await execute(sql)
    await ensureColumn('local_auth_credentials', 'ttl_seconds', 'ttl_seconds INTEGER NOT NULL DEFAULT 3600')
    await ensureColumn('local_roles', 'description', 'description TEXT NOT NULL DEFAULT \'\'')
    await backfillRoleDescriptions()
    await ensureColumn('local_permissions', 'category', 'category TEXT NOT NULL DEFAULT \'\'')
    await ensureColumn('local_permissions', 'description', 'description TEXT NOT NULL DEFAULT \'\'')
    await ensureStrictAuthSessionSchema()
  }

  const ensureColumn = async (table, column, definition) => {
    let columns = []
    try {
      columns = normalizeRows(await query(`PRAGMA table_info(${table})`, []))
        .map((row) => row.name)
    } catch (error) {
      const message = String(error?.message || error || '')
      if (message.includes('Unsupported SQL')) return
      throw error
    }
    if (!columns.includes(column)) {
      try {
        await execute(`ALTER TABLE ${table} ADD COLUMN ${definition}`)
      } catch (error) {
        const message = String(error?.message || error || '')
        if (!message.includes('Unsupported SQL')) throw error
      }
    }
  }

  const ensureOperationSchema = async () => {
    await ensureColumn('operations', 'card_no', 'card_no TEXT')
    await ensureColumn('operations', 'physical_confirmed_at', 'physical_confirmed_at INTEGER')
    const columns = normalizeRows(await query('PRAGMA table_info(operations)', []))
      .map((row) => String(row.name || ''))
    if (!columns.includes('card_no') || !columns.includes('physical_confirmed_at')) return
    const placeholders = PHYSICAL_EVIDENCE_OPERATION_STATE_LIST.map(() => '?').join(', ')
    const legacyRows = normalizeRows(await query(
      `SELECT operation_id, card_no, physical_confirmed_at, raw_json FROM operations
       WHERE state IN (${placeholders})
         AND (card_no IS NULL OR card_no = '' OR physical_confirmed_at IS NULL OR physical_confirmed_at <= 0)`,
      PHYSICAL_EVIDENCE_OPERATION_STATE_LIST
    ))
    for (const row of legacyRows) {
      const raw = parseJson(row.raw_json, {}) || {}
      const cardNo = String(row.card_no || raw.cardNo || '').trim()
      const physicalConfirmedAt = Number(
        row.physical_confirmed_at || raw.physicalConfirmedAt || raw.cardEvent?.timestamp || 0
      ) || null
      if (!cardNo || !physicalConfirmedAt) continue
      await execute(
        'UPDATE operations SET card_no = ?, physical_confirmed_at = ? WHERE operation_id = ?',
        [cardNo, physicalConfirmedAt, row.operation_id]
      )
    }
  }

  const upsertPasswordCredential = async ({
    credentialId,
    label,
    password,
    roleIds,
    enabled = true,
    isSystem = true,
    passwordState = 'ACTIVE',
    ttlSeconds = DEFAULT_SESSION_TTL_SECONDS,
    raw = {}
  }) => {
    const plain = String(password || '').trim()
    if (!credentialId || !plain) return { saved: false, reason: 'EMPTY_CREDENTIAL' }
    if (!isSixDigitPassword(plain)) return { saved: false, reason: 'PASSWORD_FORMAT' }
    const updatedAt = now()
    const normalizedTtl = Math.max(60, Number(ttlSeconds || DEFAULT_SESSION_TTL_SECONDS))
    const hash = await pbkdf2Hash(plain)
    const existing = normalizeRows(await query(
      'SELECT created_at, last_login_at FROM local_auth_credentials WHERE credential_id = ? LIMIT 1',
      [credentialId]
    ))[0] || {}
    const createdAt = Number(existing.created_at || updatedAt)
    const lastLoginAt = existing.last_login_at || null
    await execute(
      'INSERT OR REPLACE INTO local_auth_credentials(credential_id, credential_label, password_hash, password_salt, password_iterations, password_algorithm, password_state, enabled, is_system, failed_count, locked_until, ttl_seconds, last_login_at, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        credentialId,
        label || credentialId,
        hash.hash,
        hash.salt,
        hash.iterations,
        hash.algorithm,
        passwordState,
        enabled ? 1 : 0,
        isSystem ? 1 : 0,
        0,
        0,
        normalizedTtl,
        lastLoginAt,
        createdAt,
        updatedAt,
        JSON.stringify({ credentialId, label, roleIds, ttlSeconds: normalizedTtl, ...raw, updatedAt })
      ]
    )
    await execute('DELETE FROM local_credential_roles WHERE credential_id = ?', [credentialId])
    for (const roleId of Array.isArray(roleIds) ? roleIds : []) {
      await execute(
        'INSERT OR REPLACE INTO local_credential_roles(credential_id, role_id, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
        [credentialId, roleId, 1, updatedAt, updatedAt]
      )
    }
    return { saved: true, credentialId, roleIds, updatedAt }
  }

  const loadPermissionKeysForRole = async (roleId) => {
    const result = await query(
      'SELECT permission_key FROM local_role_permissions WHERE role_id = ? AND enabled = 1 ORDER BY permission_key ASC',
      [roleId]
    )
    return normalizeRows(result).map((row) => row.permission_key).filter(Boolean)
  }

  const countCredentialsForRole = async (roleId) => {
    const result = await query(
      'SELECT COUNT(*) AS count FROM local_credential_roles WHERE role_id = ? AND enabled = 1',
      [roleId]
    )
    return Number(normalizeRows(result)[0]?.count || 0)
  }

  const countPermissionRefs = async (permissionKey) => {
    const roleRefs = normalizeRows(await query(
      'SELECT COUNT(*) AS count FROM local_role_permissions WHERE permission_key = ?',
      [permissionKey]
    ))[0]
    const childRefs = normalizeRows(await query(
      'SELECT COUNT(*) AS count FROM local_permissions WHERE parent_key = ?',
      [permissionKey]
    ))[0]
    const itemRefs = normalizeRows(await query(
      'SELECT COUNT(*) AS count FROM local_operable_items WHERE permission_key = ?',
      [permissionKey]
    ))[0]
    return {
      roleCount: Number(roleRefs?.count || 0),
      childCount: Number(childRefs?.count || 0),
      itemCount: Number(itemRefs?.count || 0)
    }
  }

  const assertPermissionKeyFormat = (permissionKey) => {
    if (!permissionKey) throw new Error('请输入权限标识')
    if (!/^[A-Za-z0-9.*_-]+(\.[A-Za-z0-9.*_-]+)*$/.test(permissionKey)) {
      throw new Error('权限标识只能包含字母、数字、点、星号、下划线和短横线')
    }
  }

  const assertPermissionParentIsValid = async (permissionKey, parentKey) => {
    if (!parentKey) return
    if (permissionKey === parentKey) throw new Error('父权限不能选择自身')
    const visited = new Set([permissionKey])
    let current = parentKey
    while (current) {
      if (visited.has(current)) throw new Error('权限层级存在循环')
      visited.add(current)
      const row = normalizeRows(await query(
        'SELECT parent_key FROM local_permissions WHERE permission_key = ? LIMIT 1',
        [current]
      ))[0]
      if (!row) throw new Error('父权限不存在')
      current = row.parent_key || ''
    }
  }

  const listLocalPermissions = async ({ includeDisabled = true, includeLegacy = false } = {}) => {
    await initializeSchema()
    const where = includeDisabled ? '' : 'WHERE enabled = 1'
    const result = await query(
      `SELECT permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, raw_json FROM local_permissions ${where} ORDER BY category ASC, sort_order ASC, permission_key ASC`,
      []
    )
    return normalizeRows(result)
      .filter((row) => includeLegacy || !LEGACY_PERMISSION_KEYS.has(String(row.permission_key || '').trim()))
      .map((row) => ({
      permissionKey: row.permission_key,
      permissionName: row.permission_name,
      parentKey: row.parent_key || '',
      category: row.category || permissionCategoryFromKey(row.permission_key),
      itemType: row.item_type || 'action',
      enabled: Number(row.enabled || 0) === 1,
      sortOrder: Number(row.sort_order || 0),
      description: row.description || parseJson(row.raw_json, {})?.description || ''
      }))
  }

  const saveLocalPermission = async ({
    permissionKey,
    permissionName,
    parentKey = '',
    category = '',
    itemType = 'action',
    enabled = true,
    sortOrder = 100,
    description = ''
  } = {}) => {
    await initializeSchema()
    const key = String(permissionKey || '').trim()
    const name = String(permissionName || '').trim()
    const normalizedParent = String(parentKey || '').trim()
    assertPermissionKeyFormat(key)
    if (!name) throw new Error('请输入权限名称')
    if (key === '*' && normalizedParent) throw new Error('全部权限不能设置父级')
    await assertPermissionParentIsValid(key, normalizedParent)
    const existing = normalizeRows(await query(
      'SELECT * FROM local_permissions WHERE permission_key = ? LIMIT 1',
      [key]
    ))[0] || null
    const normalizedSortOrder = Number(sortOrder)
    if (!Number.isInteger(normalizedSortOrder)) throw new Error('权限排序必须为整数')
    const updatedAt = now()
    await execute(
      'INSERT OR REPLACE INTO local_permissions(permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        key,
        name,
        normalizedParent,
        String(category || permissionCategoryFromKey(key)).trim(),
        String(itemType || 'action').trim(),
        key === '*' ? 1 : (enabled ? 1 : 0),
        normalizedSortOrder,
        String(description || ''),
        Number(existing?.created_at || updatedAt),
        updatedAt,
        JSON.stringify({ description: String(description || ''), updatedAt })
      ]
    )
    const remainingAdmins = await enabledAdminCredentialIds()
    if (!remainingAdmins.length) {
      if (existing) {
        await execute(
          'INSERT OR REPLACE INTO local_permissions(permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
          [
            existing.permission_key,
            existing.permission_name,
            existing.parent_key || '',
            existing.category || '',
            existing.item_type || 'action',
            Number(existing.enabled || 0),
            Number(existing.sort_order || 0),
            existing.description || '',
            Number(existing.created_at || updatedAt),
            Number(existing.updated_at || updatedAt),
            existing.raw_json || null
          ]
        )
      } else {
        await execute('DELETE FROM local_permissions WHERE permission_key = ?', [key])
      }
      throw new Error('权限修改会移除最后一条账号权限管理授权路径')
    }
    return { saved: true, permissionKey: key, updatedAt }
  }

  const deleteLocalPermission = async (permissionKey) => {
    await initializeSchema()
    const key = String(permissionKey || '').trim()
    if (!key) throw new Error('请选择权限点')
    if (key === '*') throw new Error('全部权限不可删除')
    const existing = normalizeRows(await query(
      'SELECT permission_key FROM local_permissions WHERE permission_key = ? LIMIT 1',
      [key]
    ))[0]
    if (!existing) throw new Error('权限点不存在')
    const refs = await countPermissionRefs(key)
    if (refs.childCount > 0) throw new Error('该权限仍有子权限，不能删除')
    if (refs.roleCount > 0) throw new Error('该权限仍被角色使用，不能删除')
    if (refs.itemCount > 0) throw new Error('该权限仍关联可操作项，不能删除')
    await execute('DELETE FROM local_permissions WHERE permission_key = ?', [key])
    return { deleted: true, permissionKey: key }
  }

  const listLocalRoles = async () => {
    await initializeSchema()
    const result = await query(
      'SELECT role_id, role_name, parent_role_id, is_system, enabled, sort_order, description, raw_json FROM local_roles ORDER BY sort_order ASC, role_name ASC',
      []
    )
    const roles = []
    for (const row of normalizeRows(result)) {
      const roleId = row.role_id
      const raw = parseJson(row.raw_json, {})
      roles.push({
        roleId,
        roleName: row.role_name,
        parentRoleId: row.parent_role_id || '',
        isSystem: Number(row.is_system || 0) === 1,
        enabled: Number(row.enabled || 0) === 1,
        sortOrder: Number(row.sort_order || 0),
        description: row.description || raw.description || '',
        permissionKeys: await loadPermissionKeysForRole(roleId),
        credentialCount: await countCredentialsForRole(roleId)
      })
    }
    return roles
  }

  const assertRoleParentIsValid = async (roleId, parentRoleId) => {
    if (!parentRoleId) return
    if (roleId === parentRoleId) throw new Error('父角色不能选择自身')
    const visited = new Set([roleId])
    let current = parentRoleId
    while (current) {
      if (visited.has(current)) throw new Error('角色继承存在循环')
      visited.add(current)
      const row = normalizeRows(await query(
        'SELECT parent_role_id FROM local_roles WHERE role_id = ? LIMIT 1',
        [current]
      ))[0]
      if (!row) throw new Error('父角色不存在')
      current = row.parent_role_id || ''
    }
  }

  const saveLocalRole = async ({
    roleId,
    roleName,
    parentRoleId = '',
    description = '',
    permissionKeys = []
  } = {}) => {
    await initializeSchema()
    const normalizedName = String(roleName || '').trim()
    if (!normalizedName) throw new Error('请输入角色名称')
    const id = String(roleId || '').trim() || createLocalId('role')
    if (id === ROLE.SUPER_ADMIN || id === ROLE.DEVELOPER) {
      throw new Error('系统内置角色只允许查看，不允许修改')
    }
    if (String(parentRoleId || '') === ROLE.DEVELOPER) throw new Error('开发人员角色不能作为父角色')
    const updatedAt = now()
    const existing = normalizeRows(await query(
      'SELECT created_at, is_system, sort_order FROM local_roles WHERE role_id = ? LIMIT 1',
      [id]
    ))[0] || {}
    await assertRoleParentIsValid(id, parentRoleId)
    const duplicate = normalizeRows(await query(
      'SELECT role_id FROM local_roles WHERE role_name = ? AND role_id <> ? LIMIT 1',
      [normalizedName, id]
    ))[0]
    if (duplicate) throw new Error('角色名称已存在')
    const normalizedPermissionKeys = Array.from(new Set(permissionKeys)).filter(Boolean)
    for (const permissionKey of normalizedPermissionKeys) {
      const permission = normalizeRows(await query(
        'SELECT permission_key FROM local_permissions WHERE permission_key = ? LIMIT 1',
        [permissionKey]
      ))[0]
      if (!permission) throw new Error(`权限点不存在：${permissionKey}`)
    }
    await execute(
      'INSERT OR REPLACE INTO local_roles(role_id, role_name, parent_role_id, is_system, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        id,
        normalizedName,
        String(parentRoleId || ''),
        Number(existing.is_system || 0),
        1,
        Number(existing.sort_order || 100),
        String(description || ''),
        Number(existing.created_at || updatedAt),
        updatedAt,
        JSON.stringify({ updatedAt })
      ]
    )
    await execute('DELETE FROM local_role_permissions WHERE role_id = ?', [id])
    for (const permissionKey of normalizedPermissionKeys) {
      await execute(
        'INSERT OR REPLACE INTO local_role_permissions(role_id, permission_key, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
        [id, permissionKey, 1, updatedAt, updatedAt]
      )
    }
    return { roleId: id, saved: true, updatedAt }
  }

  const deleteLocalRole = async (roleId) => {
    await initializeSchema()
    const id = String(roleId || '').trim()
    const role = normalizeRows(await query('SELECT is_system FROM local_roles WHERE role_id = ? LIMIT 1', [id]))[0]
    if (!role) throw new Error('角色不存在')
    if (Number(role.is_system || 0) === 1) throw new Error('系统内置角色不可删除')
    const child = normalizeRows(await query(
      'SELECT role_id FROM local_roles WHERE parent_role_id = ? LIMIT 1',
      [id]
    ))[0]
    if (child) throw new Error('该角色仍被子角色继承，不能删除')
    const refs = await countCredentialsForRole(id)
    if (refs > 0) throw new Error('该角色仍有关联用户，不能删除')
    await execute('DELETE FROM local_role_permissions WHERE role_id = ?', [id])
    await execute('DELETE FROM local_roles WHERE role_id = ?', [id])
    return { deleted: true, roleId: id }
  }

  const loadRoleIdsForCredential = async (credentialId) => {
    const result = await query(
      'SELECT role_id FROM local_credential_roles WHERE credential_id = ? AND enabled = 1 ORDER BY role_id ASC',
      [credentialId]
    )
    return normalizeRows(result).map((row) => row.role_id).filter(Boolean)
  }

  const restoreSystemCredentialBinding = async (credentialId) => {
    const roleId = SYSTEM_CREDENTIAL_ROLES[credentialId]
    if (!roleId) return []
    const updatedAt = now()
    await execute(
      'UPDATE local_roles SET is_system = ?, updated_at = ? WHERE role_id = ?',
      [1, updatedAt, roleId]
    )
    await execute(
      'UPDATE local_auth_credentials SET enabled = ?, is_system = ?, updated_at = ? WHERE credential_id = ?',
      [1, 1, updatedAt, credentialId]
    )
    await execute(
      'INSERT OR REPLACE INTO local_credential_roles(credential_id, role_id, enabled, created_at, updated_at) VALUES(?, ?, ?, COALESCE((SELECT created_at FROM local_credential_roles WHERE credential_id = ? AND role_id = ?), ?), ?)',
      [credentialId, roleId, 1, credentialId, roleId, updatedAt, updatedAt]
    )
    return loadRoleIdsForCredential(credentialId)
  }

  const credentialHasPermission = async (credentialId, permissionKey) => {
    const roleIds = await loadRoleIdsForCredential(credentialId)
    const permissions = await resolvePermissions(roleIds)
    return permissions.includes(permissionKey)
  }

  const credentialHasAccountManagementAccess = async (credentialId) => {
    return credentialHasPermission(credentialId, 'account.role.*')
  }

  const enabledAdminCredentialIds = async ({ excludeCredentialId = '' } = {}) => {
    const result = await query('SELECT credential_id FROM local_auth_credentials WHERE enabled = 1', [])
    const ids = []
    for (const row of normalizeRows(result)) {
      const credentialId = row.credential_id
      if (credentialId === excludeCredentialId) continue
      if (await credentialHasAccountManagementAccess(credentialId)) ids.push(credentialId)
    }
    return ids
  }

  const listLocalCredentials = async () => {
    await initializeSchema()
    const result = await query(
      'SELECT credential_id, credential_label, password_state, enabled, is_system, failed_count, locked_until, ttl_seconds, last_login_at, created_at, updated_at FROM local_auth_credentials ORDER BY is_system DESC, credential_label ASC',
      []
    )
    const credentials = []
    for (const row of normalizeRows(result)) {
      const roleIds = await loadRoleIdsForCredential(row.credential_id)
      const roles = await loadRoles(roleIds, { includeDisabled: true })
      credentials.push({
        credentialId: row.credential_id,
        label: row.credential_label,
        passwordState: row.password_state,
        enabled: Number(row.enabled || 0) === 1,
        isSystem: Number(row.is_system || 0) === 1,
        failedCount: Number(row.failed_count || 0),
        lockedUntil: Number(row.locked_until || 0),
        ttlSeconds: Number(row.ttl_seconds || DEFAULT_SESSION_TTL_SECONDS),
        lastLoginAt: Number(row.last_login_at || 0),
        createdAt: Number(row.created_at || 0),
        updatedAt: Number(row.updated_at || 0),
        roleIds,
        roles,
        roleLabels: roles.map((role) => role.roleName || role.roleId)
      })
    }
    return credentials
  }

  const assertPasswordUnique = async (password, excludeCredentialIds = '') => {
    const excluded = new Set(
      (Array.isArray(excludeCredentialIds) ? excludeCredentialIds : [excludeCredentialIds]).filter(Boolean)
    )
    const credentials = normalizeRows(await query('SELECT * FROM local_auth_credentials', []))
    for (const credential of credentials) {
      if (excluded.has(credential.credential_id)) continue
      if (await verifyPasswordHash(password, credential)) throw new Error('该密码已被其他用户使用')
    }
  }

  const tableExists = async (tableName) => {
    const result = await query(
      'SELECT name FROM sqlite_master WHERE type = ? AND name = ? LIMIT 1',
      ['table', tableName]
    )
    return normalizeRows(result).length > 0
  }

  const countRows = async (sql, params = []) => {
    const result = await query(sql, params)
    return Number(normalizeRows(result)[0]?.count || 0)
  }

  const createUnifiedFaceBindingsIndexes = async () => {
    await execute('CREATE INDEX IF NOT EXISTS idx_face_bindings_employee ON face_bindings(employee_id, biometric_type)')
    await execute('CREATE UNIQUE INDEX IF NOT EXISTS idx_face_bindings_native_id ON face_bindings(biometric_type, native_id)')
    await execute('CREATE INDEX IF NOT EXISTS idx_face_bindings_upload_state ON face_bindings(biometric_type, upload_state, updated_at)')
    await execute('CREATE INDEX IF NOT EXISTS idx_face_bindings_file_hash ON face_bindings(biometric_type, file_hash)')
  }

  const migrateLegacyFaceBindings = async () => {
    await ensureColumn('face_bindings', 'face_ai_id', 'face_ai_id TEXT')
    await execute("UPDATE face_bindings SET face_ai_id = face_id WHERE face_ai_id IS NULL OR face_ai_id = ''")

    const hasFacePhotos = await tableExists('face_photos')
    const hasFingerBindings = await tableExists('finger_bindings')
    if (hasFacePhotos) {
      await ensureColumn('face_photos', 'server_path', 'server_path TEXT')
      await ensureColumn('face_photos', 'file_hash', 'file_hash TEXT')
    }

    const expectedFaceCount = hasFacePhotos
      ? await countRows(
          'SELECT COUNT(*) AS count FROM (SELECT face_id FROM face_bindings UNION SELECT face_id FROM face_photos)'
        )
      : await countRows('SELECT COUNT(*) AS count FROM face_bindings')
    const expectedFingerCount = hasFingerBindings
      ? await countRows('SELECT COUNT(*) AS count FROM finger_bindings')
      : 0

    await execute('BEGIN IMMEDIATE')
    try {
      await execute('DROP TABLE IF EXISTS face_bindings_unified_v10')
      await execute(
        'CREATE TABLE face_bindings_unified_v10 (biometric_type TEXT NOT NULL, binding_id TEXT NOT NULL, employee_id TEXT NOT NULL, native_id TEXT, biometric_index INTEGER, enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, expires_at INTEGER, mime_type TEXT, byte_size INTEGER, photo_base64 TEXT, source TEXT, upload_state TEXT, upload_id TEXT, server_path TEXT, server_url TEXT, file_hash TEXT, last_error TEXT, synced_at INTEGER, raw_json TEXT, PRIMARY KEY(biometric_type, binding_id), FOREIGN KEY(employee_id) REFERENCES employees(employee_id))'
      )

      if (hasFacePhotos) {
        await execute(
          `INSERT INTO face_bindings_unified_v10(
            biometric_type, binding_id, employee_id, native_id, biometric_index, enabled,
            updated_at, expires_at, mime_type, byte_size, photo_base64, source, upload_state,
            upload_id, server_path, server_url, file_hash, last_error, synced_at, raw_json
          )
          SELECT ?, fb.face_id, fb.employee_id, fb.face_ai_id, fb.face_index, fb.enabled,
            MAX(fb.updated_at, COALESCE(fp.updated_at, 0)), fb.expires_at,
            fp.mime_type, fp.byte_size, fp.photo_base64, fp.source, fp.upload_state,
            fp.upload_id, fp.server_path, fp.server_url, fp.file_hash, fp.last_error,
            fp.synced_at, fb.raw_json
          FROM face_bindings fb
          LEFT JOIN face_photos fp ON fp.face_id = fb.face_id`,
          [BIOMETRIC_TYPE.FACE]
        )
        await execute(
          `INSERT INTO face_bindings_unified_v10(
            biometric_type, binding_id, employee_id, native_id, biometric_index, enabled,
            updated_at, expires_at, mime_type, byte_size, photo_base64, source, upload_state,
            upload_id, server_path, server_url, file_hash, last_error, synced_at, raw_json
          )
          SELECT ?, fp.face_id, fp.employee_id, NULL, NULL, 0,
            fp.updated_at, NULL, fp.mime_type, fp.byte_size, fp.photo_base64, fp.source,
            fp.upload_state, fp.upload_id, fp.server_path, fp.server_url, fp.file_hash,
            fp.last_error, fp.synced_at, fp.raw_json
          FROM face_photos fp
          LEFT JOIN face_bindings fb ON fb.face_id = fp.face_id
          WHERE fb.face_id IS NULL`,
          [BIOMETRIC_TYPE.FACE]
        )
      } else {
        await execute(
          `INSERT INTO face_bindings_unified_v10(
            biometric_type, binding_id, employee_id, native_id, biometric_index, enabled,
            updated_at, expires_at, raw_json
          )
          SELECT ?, face_id, employee_id, face_ai_id, face_index, enabled,
            updated_at, expires_at, raw_json
          FROM face_bindings`,
          [BIOMETRIC_TYPE.FACE]
        )
      }

      if (hasFingerBindings) {
        await execute(
          `INSERT INTO face_bindings_unified_v10(
            biometric_type, binding_id, employee_id, native_id, biometric_index, enabled,
            updated_at, expires_at, raw_json
          )
          SELECT ?, finger_id, employee_id, NULL, finger_index, enabled,
            updated_at, expires_at, raw_json
          FROM finger_bindings`,
          [BIOMETRIC_TYPE.FINGER]
        )
      }

      const migratedFaceCount = await countRows(
        'SELECT COUNT(*) AS count FROM face_bindings_unified_v10 WHERE biometric_type = ?',
        [BIOMETRIC_TYPE.FACE]
      )
      const migratedFingerCount = await countRows(
        'SELECT COUNT(*) AS count FROM face_bindings_unified_v10 WHERE biometric_type = ?',
        [BIOMETRIC_TYPE.FINGER]
      )
      if (migratedFaceCount !== expectedFaceCount || migratedFingerCount !== expectedFingerCount) {
        throw new Error('生物识别绑定合表迁移数量校验失败，旧表已保留')
      }

      await execute('DROP INDEX IF EXISTS idx_face_bindings_employee')
      await execute('DROP INDEX IF EXISTS idx_face_bindings_face_ai_id')
      await execute('ALTER TABLE face_bindings RENAME TO face_bindings_legacy_v9')
      await execute('ALTER TABLE face_bindings_unified_v10 RENAME TO face_bindings')
      await execute('DROP TABLE face_bindings_legacy_v9')
      if (hasFacePhotos) await execute('DROP TABLE face_photos')
      if (hasFingerBindings) await execute('DROP TABLE finger_bindings')
      await createUnifiedFaceBindingsIndexes()
      await execute('COMMIT')
    } catch (error) {
      try { await execute('ROLLBACK') } catch (_) {}
      throw error
    }
  }

  const ensureAuditEventsSchema = async () => {
    const columns = normalizeRows(await query('PRAGMA table_info(audit_events)', []))
      .map((row) => String(row.name || ''))
    if (!columns.includes('session_ref')) {
      await ensureColumn('audit_events', 'session_ref', 'session_ref TEXT')
    }
    if (!columns.includes('source')) {
      await ensureColumn('audit_events', 'source', 'source TEXT NOT NULL DEFAULT \'LOCAL_UI\'')
    }
    if (!columns.includes('metadata_json')) {
      await ensureColumn('audit_events', 'metadata_json', 'metadata_json TEXT')
    }
  }

  const insertAuditEvent = async (event) => {
    const eventId = `audit_${now()}_${Math.random().toString(36).slice(2, 8)}`
    const occurredAt = event.occurred_at || now()
    await execute(
      `INSERT INTO audit_events(event_id, session_ref, actor_credential_id, actor_label, role_ids_json,
         event_type, feature_code, feature_label, route, action_code, action_label,
         source, occurred_at, metadata_json, created_at)
       VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        eventId,
        String(event.session_ref ?? ''),
        String(event.actor_credential_id ?? ''),
        String(event.actor_label ?? ''),
        event.role_ids_json ? String(event.role_ids_json) : null,
        String(event.event_type ?? ''),
        event.feature_code ? String(event.feature_code) : null,
        event.feature_label ? String(event.feature_label) : null,
        event.route ? String(event.route) : null,
        event.action_code ? String(event.action_code) : null,
        event.action_label ? String(event.action_label) : null,
        String(event.source || 'LOCAL_UI'),
        occurredAt,
        event.metadata_json ? String(event.metadata_json) : null,
        now()
      ]
    )
    await cleanupAuditEvents()
    return { eventId, occurredAt }
  }

  const cleanupAuditEvents = async () => {
    const cutoff = now() - 30 * 24 * 60 * 60 * 1000
    await execute(
      'DELETE FROM audit_events WHERE occurred_at < ?',
      [cutoff]
    )
  }

  const ensureUnifiedFaceBindingsSchema = async () => {
    const columns = normalizeRows(await query('PRAGMA table_info(face_bindings)', []))
      .map((row) => String(row.name || ''))
    if (!columns.includes('biometric_type')) await migrateLegacyFaceBindings()
    await createUnifiedFaceBindingsIndexes()
  }

  const saveLocalCredential = async ({
    credentialId,
    label,
    password = '',
    roleIds = [],
    enabled = true,
    ttlSeconds = DEFAULT_SESSION_TTL_SECONDS
  } = {}) => {
    await initializeSchema()
    const normalizedLabel = String(label || '').trim()
    if (!normalizedLabel) throw new Error('请输入用户名称')
    let normalizedRoleIds = Array.from(new Set(roleIds)).filter(Boolean)
    if (!normalizedRoleIds.length) throw new Error('至少选择一个角色')
    const id = String(credentialId || '').trim() || createLocalId('credential')
    const existing = normalizeRows(await query('SELECT * FROM local_auth_credentials WHERE credential_id = ? LIMIT 1', [id]))[0]
    if (id === 'builtin:DEVELOPER') throw new Error('开发人员账号为系统内置，不允许修改')
    if (normalizedRoleIds.includes(ROLE.DEVELOPER)) throw new Error('开发人员角色仅限系统内置账号使用')
    if (Number(existing?.is_system || 0) === 1 && id === 'builtin:SUPER_ADMIN') {
      normalizedRoleIds = [ROLE.SUPER_ADMIN]
    }
    const currentlyHasAdminAccess = Number(existing?.enabled || 0) === 1 && await credentialHasAccountManagementAccess(id)
    const nextHasAdminAccess = Boolean(enabled) && (await resolvePermissions(normalizedRoleIds)).includes('account.role.*')
    if (currentlyHasAdminAccess && !nextHasAdminAccess) {
      const remainingAdmins = await enabledAdminCredentialIds({ excludeCredentialId: id })
      if (!remainingAdmins.length) throw new Error('不能移除最后一个管理员用户的管理权限')
    }
    const plain = String(password || '').trim()
    if (plain) {
      if (!isSixDigitPassword(plain)) throw new Error('请输入6位数字密码')
      await assertPasswordUnique(plain, id)
      return upsertPasswordCredential({
        credentialId: id,
        label: normalizedLabel,
        password: plain,
        roleIds: normalizedRoleIds,
        enabled,
        isSystem: Number(existing?.is_system || 0) === 1,
        passwordState: existing?.password_state || 'INITIAL',
        ttlSeconds,
        raw: { source: existing ? 'LOCAL_EDIT' : 'LOCAL_CREATE' }
      })
    }
    if (!existing) throw new Error('新增用户必须设置6位密码')
    const updatedAt = now()
    await execute(
      'UPDATE local_auth_credentials SET credential_label = ?, enabled = ?, ttl_seconds = ?, updated_at = ? WHERE credential_id = ?',
      [normalizedLabel, enabled ? 1 : 0, Math.max(60, Number(ttlSeconds || DEFAULT_SESSION_TTL_SECONDS)), updatedAt, id]
    )
    await execute('DELETE FROM local_credential_roles WHERE credential_id = ?', [id])
    for (const roleId of normalizedRoleIds) {
      await execute(
        'INSERT OR REPLACE INTO local_credential_roles(credential_id, role_id, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
        [id, roleId, 1, updatedAt, updatedAt]
      )
    }
    return { saved: true, credentialId: id, roleIds: normalizedRoleIds, updatedAt }
  }

  const deleteLocalCredential = async (credentialId) => {
    await initializeSchema()
    const id = String(credentialId || '').trim()
    const credential = normalizeRows(await query('SELECT is_system FROM local_auth_credentials WHERE credential_id = ? LIMIT 1', [id]))[0]
    if (!credential) throw new Error('用户不存在')
    if (Number(credential.is_system || 0) === 1) throw new Error('系统内置用户不可删除')
    if (await credentialHasAccountManagementAccess(id)) {
      const remainingAdmins = await enabledAdminCredentialIds({ excludeCredentialId: id })
      if (!remainingAdmins.length) throw new Error('不能删除最后一个管理员用户')
    }
    await execute('DELETE FROM local_credential_roles WHERE credential_id = ?', [id])
    await execute('DELETE FROM local_auth_credentials WHERE credential_id = ?', [id])
    return { deleted: true, credentialId: id }
  }

  const unlockLocalCredential = async (credentialId) => {
    await initializeSchema()
    const id = String(credentialId || '').trim()
    await execute(
      'UPDATE local_auth_credentials SET failed_count = ?, locked_until = ?, updated_at = ? WHERE credential_id = ?',
      [0, 0, now(), id]
    )
    return { unlocked: true, credentialId: id }
  }

  const verifySecondaryPassword = async (password) => {
    const plain = String(password || '').trim()
    if (!isSixDigitPassword(plain)) throw new Error('请输入6位数字二级密码')
    await initializeSchema()
    const row = normalizeRows(await query(
      'SELECT * FROM local_secondary_password WHERE password_id = ? AND enabled = 1 LIMIT 1',
      ['default']
    ))[0]
    if (!row || !await verifyPasswordHash(plain, row)) throw new Error('二级密码错误')
    return { verified: true }
  }

  const changeSecondaryPassword = async (oldPassword, newPassword) => {
    const next = String(newPassword || '').trim()
    if (!isSixDigitPassword(next)) throw new Error('请输入6位数字新二级密码')
    await verifySecondaryPassword(oldPassword)
    const hash = await pbkdf2Hash(next)
    const updatedAt = now()
    await execute(
      'INSERT OR REPLACE INTO local_secondary_password(password_id, password_hash, password_salt, password_iterations, password_algorithm, enabled, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM local_secondary_password WHERE password_id = ?), ?), ?, ?)',
      ['default', hash.hash, hash.salt, hash.iterations, hash.algorithm, 1, 'default', updatedAt, updatedAt, JSON.stringify({ source: 'LOCAL_EDIT', updatedAt })]
    )
    return { changed: true, updatedAt }
  }

  const changeLocalCredentialPassword = async ({ credentialId, oldPassword, newPassword } = {}) => {
    const id = String(credentialId || '').trim()
    const previous = String(oldPassword || '').trim()
    const next = String(newPassword || '').trim()
    if (!id) throw new Error('本地登录会话无效')
    if (id === 'builtin:DEVELOPER') throw new Error('开发人员密码由后台配置管理，客户端不允许修改')
    if (!isSixDigitPassword(previous)) throw new Error('请输入6位数字原密码')
    if (!isSixDigitPassword(next)) throw new Error('请输入6位数字新密码')
    if (previous === next) throw new Error('新密码不能与原密码相同')
    await initializeSchema()
    const credential = normalizeRows(await query(
      'SELECT * FROM local_auth_credentials WHERE credential_id = ? AND enabled = 1 LIMIT 1',
      [id]
    ))[0]
    if (!credential) throw new Error('本地管理员不存在或已禁用')
    if (!await verifyPasswordHash(previous, credential)) throw new Error('原密码错误')
    await assertPasswordUnique(next, id)
    const hash = await pbkdf2Hash(next)
    const updatedAt = now()
    await execute(
      'UPDATE local_auth_credentials SET password_hash = ?, password_salt = ?, password_iterations = ?, password_algorithm = ?, password_state = ?, failed_count = ?, locked_until = ?, updated_at = ? WHERE credential_id = ?',
      [hash.hash, hash.salt, hash.iterations, hash.algorithm, 'ACTIVE', 0, 0, updatedAt, id]
    )
    // 验证写入成功（JsBridge storage.execute 可能不返回 affected rows）
    const verify = normalizeRows(await query(
      'SELECT password_state FROM local_auth_credentials WHERE credential_id = ?',
      [id]
    ))[0]
    if (!verify || verify.password_state !== 'ACTIVE') {
      throw new Error('密码修改写入数据库失败，请重试')
    }
    return { changed: true, credentialId: id, passwordState: 'ACTIVE', updatedAt }
  }

  const migrateLegacyPermissionAssignments = async (updatedAt) => {
    const marker = normalizeRows(await query(
      'SELECT value FROM schema_meta WHERE key = ? LIMIT 1',
      ['permissionTreeVersion']
    ))[0]?.value
    if (marker === PERMISSION_TREE_VERSION) return
    for (const [legacyKey, replacements] of Object.entries(LEGACY_PERMISSION_REPLACEMENTS)) {
      const bindings = normalizeRows(await query(
        'SELECT role_id FROM local_role_permissions WHERE permission_key = ? AND enabled = 1',
        [legacyKey]
      ))
      for (const binding of bindings) {
        for (const permissionKey of replacements) {
          await execute(
            'INSERT OR IGNORE INTO local_role_permissions(role_id, permission_key, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
            [binding.role_id, permissionKey, 1, updatedAt, updatedAt]
          )
        }
      }
      await execute('DELETE FROM local_role_permissions WHERE permission_key = ?', [legacyKey])
    }
    for (const permissionKey of RETIRED_PERMISSION_KEYS) {
      await execute('DELETE FROM local_role_permissions WHERE permission_key = ?', [permissionKey])
      await execute('DELETE FROM local_permissions WHERE permission_key = ?', [permissionKey])
    }
    await execute(
      'INSERT OR REPLACE INTO schema_meta(key, value, updated_at) VALUES(?, ?, ?)',
      ['permissionTreeVersion', PERMISSION_TREE_VERSION, updatedAt]
    )
  }

  const seedAccessControlDefaults = async () => {
    const updatedAt = now()
    for (const role of SYSTEM_ROLES) {
      await execute(
        'INSERT OR IGNORE INTO local_roles(role_id, role_name, parent_role_id, is_system, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          role.roleId,
          role.roleName,
          role.parentRoleId || '',
          1,
          1,
          role.sortOrder,
          role.description || '',
          updatedAt,
          updatedAt,
          JSON.stringify(role)
        ]
      )
      await execute(
        'UPDATE local_roles SET is_system = ?, updated_at = ? WHERE role_id = ?',
        [1, updatedAt, role.roleId]
      )
    }
    for (const [index, permission] of SEED_PERMISSIONS.entries()) {
      await execute(
        'INSERT OR IGNORE INTO local_permissions(permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          permission.key,
          permission.name,
          permission.parentKey || '',
          permission.category || permissionCategoryFromKey(permission.key),
          permission.itemType || 'action',
          1,
          index + 1,
          permission.description || '',
          updatedAt,
          updatedAt,
          JSON.stringify(permission)
        ]
      )
      // 内置权限编码稳定，名称随页面入口调整；升级后同步更新已有本机库的展示名称。
      await execute(
        'UPDATE local_permissions SET permission_name = ?, updated_at = ? WHERE permission_key = ? AND permission_name <> ?',
        [permission.name, updatedAt, permission.key, permission.name]
      )
    }
    await migrateLegacyPermissionAssignments(updatedAt)
    for (const [roleId, permissions] of Object.entries(ROLE_PERMISSIONS)) {
      await execute('DELETE FROM local_role_permissions WHERE role_id = ?', [roleId])
      for (const permissionKey of permissions) {
        await execute(
          'INSERT OR REPLACE INTO local_role_permissions(role_id, permission_key, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
          [roleId, permissionKey, 1, updatedAt, updatedAt]
        )
      }
    }
    for (const item of DEFAULT_OPERABLE_ITEMS) {
      await execute(
        'INSERT OR IGNORE INTO local_operable_items(item_code, permission_key, item_type, label, route, action, enabled, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          item.code,
          item.code,
          item.type,
          item.label,
          item.route || '',
          item.action || '',
          1,
          updatedAt,
          updatedAt,
          JSON.stringify(item)
        ]
      )
    }
    const superAdmin = normalizeRows(await query(
      'SELECT credential_id FROM local_auth_credentials WHERE credential_id = ? LIMIT 1',
      ['builtin:SUPER_ADMIN']
    ))[0]
    if (!superAdmin) {
      await upsertPasswordCredential({
        credentialId: 'builtin:SUPER_ADMIN',
        label: '超级管理员',
        password: DEFAULT_SUPER_ADMIN_PASSWORD,
        roleIds: [ROLE.SUPER_ADMIN],
        isSystem: true,
        passwordState: 'INITIAL',
        raw: { source: 'LOCAL_SEED' }
      })
    } else {
      await restoreSystemCredentialBinding('builtin:SUPER_ADMIN')
    }
    const developer = normalizeRows(await query(
      'SELECT credential_id FROM local_auth_credentials WHERE credential_id = ? LIMIT 1',
      ['builtin:DEVELOPER']
    ))[0]
    if (!developer) {
      await upsertPasswordCredential({
        credentialId: 'builtin:DEVELOPER',
        label: '开发人员',
        password: DEFAULT_DEVELOPER_PASSWORD,
        roleIds: [ROLE.DEVELOPER],
        isSystem: true,
        passwordState: 'ACTIVE',
        raw: { source: 'LOCAL_SEED_FALLBACK', managedBy: 'LOCAL_FALLBACK' }
      })
    } else {
      await restoreSystemCredentialBinding('builtin:DEVELOPER')
    }
    const secondaryPassword = normalizeRows(await query(
      'SELECT password_id FROM local_secondary_password WHERE password_id = ? LIMIT 1',
      ['default']
    ))[0]
    if (!secondaryPassword) {
      const hash = await pbkdf2Hash('123456')
      await execute(
        'INSERT INTO local_secondary_password(password_id, password_hash, password_salt, password_iterations, password_algorithm, enabled, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)',
        ['default', hash.hash, hash.salt, hash.iterations, hash.algorithm, 1, updatedAt, updatedAt, JSON.stringify({ source: 'LOCAL_SEED' })]
      )
    }
  }

  const saveBootstrapConfig = (value) => saveJsonConfig(CONFIG_KEYS.BOOTSTRAP, value)
  const saveRuntimeConfig = (value) => saveJsonConfig(CONFIG_KEYS.RUNTIME, value)
  const saveConfigDraft = (value) => saveJsonConfig(CONFIG_KEYS.DRAFT, value)
  const saveOfflineActivationState = (value) => saveJsonConfig(CONFIG_KEYS.OFFLINE_ACTIVATION, value)
  const saveOfflineConfigMeta = (value) => saveJsonConfig(CONFIG_KEYS.OFFLINE_CONFIG_META, value)
  const saveLogUploadPolicy = (value = {}) => saveJsonConfig(CONFIG_KEYS.LOG_UPLOAD_POLICY, {
    ...value,
    enabled: value.enabled === true
  })
  const loadBootstrapConfig = () => loadJsonConfig(CONFIG_KEYS.BOOTSTRAP, [LEGACY_CONFIG_KEYS.BOOTSTRAP])
  const loadRuntimeConfig = () => loadJsonConfig(CONFIG_KEYS.RUNTIME)
  const loadConfigDraft = () => loadJsonConfig(CONFIG_KEYS.DRAFT, [LEGACY_CONFIG_KEYS.DRAFT])
  const loadOfflineActivationState = () => loadJsonConfig(CONFIG_KEYS.OFFLINE_ACTIVATION)
  const loadOfflineConfigMeta = () => loadJsonConfig(CONFIG_KEYS.OFFLINE_CONFIG_META)
  const loadLogUploadPolicy = async () => {
    const saved = await loadJsonConfig(CONFIG_KEYS.LOG_UPLOAD_POLICY)
    return saved ? { ...saved, enabled: saved.enabled === true } : { enabled: false }
  }

  const resolveConfigPassword = (value, label) => {
    const plain = String(value == null ? '' : value).trim()
    if (!plain) throw new Error(`后台配置缺少${label}`)
    if (!isSixDigitPassword(plain)) throw new Error(`${label}必须为6位数字`)
    return plain
  }

  const syncSystemCredentialsFromConfig = async ({
    developerPassword,
    superAdminPassword,
    deviceCode = '',
    source = 'CONFIG'
  } = {}) => {
    const updatedAt = now()
    await initializeSchema()
    const resolvedDeveloperPassword = resolveConfigPassword(
      developerPassword,
      '开发人员密码'
    )
    const resolvedSuperAdminPassword = resolveConfigPassword(
      superAdminPassword,
      '超级管理员密码'
    )
    if (resolvedDeveloperPassword === resolvedSuperAdminPassword) {
      throw new Error('后台开发人员密码与超级管理员密码不能相同')
    }

    const systemCredentialIds = ['builtin:DEVELOPER', 'builtin:SUPER_ADMIN']
    await assertPasswordUnique(resolvedDeveloperPassword, systemCredentialIds)
    await assertPasswordUnique(resolvedSuperAdminPassword, systemCredentialIds)

    const existingRows = normalizeRows(await query(
      'SELECT * FROM local_auth_credentials WHERE credential_id IN (?, ?)',
      systemCredentialIds
    ))
    const existingById = new Map(existingRows.map((row) => [row.credential_id, row]))
    const definitions = [
      {
        credentialId: 'builtin:DEVELOPER',
        label: '开发人员',
        password: resolvedDeveloperPassword,
        roleIds: [ROLE.DEVELOPER]
      },
      {
        credentialId: 'builtin:SUPER_ADMIN',
        label: '超级管理员',
        password: resolvedSuperAdminPassword,
        roleIds: [ROLE.SUPER_ADMIN]
      }
    ]
    const rowsToWrite = []
    const updatedById = new Map()
    for (const definition of definitions) {
      const existing = existingById.get(definition.credentialId)
      const passwordMatches = existing && await verifyPasswordHash(definition.password, existing)
      const raw = parseJson(existing?.raw_json, {}) || {}
      const needsWrite = !passwordMatches
        || Number(existing?.password_iterations || 0) !== PASSWORD_HASH_ITERATIONS
        || existing?.password_state !== 'ACTIVE'
        || Number(existing?.enabled || 0) !== 1
        || Number(existing?.is_system || 0) !== 1
        || raw.managedBy !== 'BACKEND_CONFIG'
      updatedById.set(definition.credentialId, needsWrite)
      if (!needsWrite) continue
      const hash = await pbkdf2Hash(definition.password)
      const normalizedTtl = Math.max(60, Number(existing?.ttl_seconds || DEFAULT_SESSION_TTL_SECONDS))
      rowsToWrite.push([
        definition.credentialId,
        definition.label,
        hash.hash,
        hash.salt,
        hash.iterations,
        hash.algorithm,
        'ACTIVE',
        1,
        1,
        0,
        0,
        normalizedTtl,
        existing?.last_login_at || null,
        Number(existing?.created_at || updatedAt),
        updatedAt,
        JSON.stringify({
          credentialId: definition.credentialId,
          label: definition.label,
          roleIds: definition.roleIds,
          ttlSeconds: normalizedTtl,
          deviceCode,
          source,
          managedBy: 'BACKEND_CONFIG',
          cachedAt: updatedAt,
          updatedAt
        })
      ])
    }
    if (rowsToWrite.length > 0) {
      const rowPlaceholders = `(${new Array(16).fill('?').join(', ')})`
      await execute(
        `INSERT OR REPLACE INTO local_auth_credentials(credential_id, credential_label, password_hash, password_salt, password_iterations, password_algorithm, password_state, enabled, is_system, failed_count, locked_until, ttl_seconds, last_login_at, created_at, updated_at, raw_json) VALUES ${rowsToWrite.map(() => rowPlaceholders).join(', ')}`,
        rowsToWrite.flat()
      )
    }

    await restoreSystemCredentialBinding('builtin:DEVELOPER')
    await restoreSystemCredentialBinding('builtin:SUPER_ADMIN')
    const developerUpdated = updatedById.get('builtin:DEVELOPER') === true
    const superAdminUpdated = updatedById.get('builtin:SUPER_ADMIN') === true
    await saveJsonConfig(CONFIG_KEYS.INITIAL_ADMIN, {
      deviceCode,
      credentialIds: systemCredentialIds,
      source,
      managedBy: 'BACKEND_CONFIG',
      cachedAt: updatedAt,
      hasPasswordHash: true,
      developerUpdated,
      superAdminUpdated
    })
    return {
      saved: true,
      reason: 'SYSTEM_CREDENTIALS_SYNCED',
      credentialIds: systemCredentialIds,
      developerUpdated,
      superAdminUpdated,
      cachedAt: updatedAt
    }
  }

  const syncDeveloperCredentialFromConfig = syncSystemCredentialsFromConfig
  const saveInitialAdminPassword = syncSystemCredentialsFromConfig

  const loadInitialAdminState = () => loadJsonConfig(CONFIG_KEYS.INITIAL_ADMIN)

  const loadRoles = async (roleIds = [], { includeDisabled = false } = {}) => {
    if (!roleIds.length) return []
    const roles = []
    for (const roleId of roleIds) {
      const result = await query(
        `SELECT role_id, role_name, parent_role_id, enabled FROM local_roles WHERE role_id = ?${includeDisabled ? '' : ' AND enabled = 1'} LIMIT 1`,
        [roleId]
      )
      const row = normalizeRows(result)[0]
      if (row) {
        roles.push({
          roleId: row.role_id,
          roleName: row.role_name,
          parentRoleId: row.parent_role_id || '',
          enabled: Number(row.enabled || 0) === 1
        })
      }
    }
    return roles
  }

  const collectRoleChain = async (roleIds = [], visited = new Set()) => {
    const collected = []
    for (const roleId of roleIds) {
      if (!roleId || visited.has(roleId)) continue
      visited.add(roleId)
      const roles = await loadRoles([roleId])
      const role = roles[0]
      if (!role) continue
      collected.push(role.roleId)
      if (role.parentRoleId) {
        collected.push(...await collectRoleChain([role.parentRoleId], visited))
      }
    }
    return Array.from(new Set(collected))
  }

  const expandPermission = (permissionKey, permissionGraph, visited = new Set()) => {
    if (!permissionKey || visited.has(permissionKey)) return []
    visited.add(permissionKey)
    if (!permissionGraph.enabled.has(permissionKey)) return []
    const children = permissionGraph.children.get(permissionKey) || []
    const expanded = [permissionKey]
    for (const child of children) {
      expanded.push(...expandPermission(child, permissionGraph, visited))
    }
    return expanded
  }

  const resolvePermissions = async (roleIds = []) => {
    const roleChain = await collectRoleChain(roleIds)
    const direct = []
    for (const roleId of roleChain) {
      const result = await query(
        'SELECT permission_key FROM local_role_permissions WHERE role_id = ? AND enabled = 1',
        [roleId]
      )
      direct.push(...normalizeRows(result).map((row) => row.permission_key).filter(Boolean))
    }
    // 权限树一次性读入内存展开，避免管理员登录时为每个节点重复跨 WebView 查询 SQLite。
    const permissionRows = normalizeRows(await query(
      'SELECT permission_key, parent_key FROM local_permissions WHERE enabled = 1 ORDER BY sort_order ASC, permission_key ASC',
      []
    ))
    const permissionGraph = { enabled: new Set(), children: new Map() }
    for (const row of permissionRows) {
      const permissionKey = String(row.permission_key || '').trim()
      if (!permissionKey) continue
      permissionGraph.enabled.add(permissionKey)
      const parentKey = String(row.parent_key || '').trim()
      if (!parentKey) continue
      const children = permissionGraph.children.get(parentKey) || []
      children.push(permissionKey)
      permissionGraph.children.set(parentKey, children)
    }
    const expanded = []
    for (const permissionKey of Array.from(new Set(direct))) {
      expanded.push(...expandPermission(permissionKey, permissionGraph))
    }
    return Array.from(new Set(expanded)).sort()
  }

  const setLocalRoleEnabled = async (roleId, enabled) => {
    await initializeSchema()
    const id = String(roleId || '').trim()
    if (!id) throw new Error('请选择角色')
    if (id === ROLE.SUPER_ADMIN || id === ROLE.DEVELOPER) {
      throw new Error('系统内置角色不允许停用')
    }
    const existing = normalizeRows(await query(
      'SELECT enabled FROM local_roles WHERE role_id = ? LIMIT 1',
      [id]
    ))[0]
    if (!existing) throw new Error('角色不存在')
    const nextEnabled = enabled ? 1 : 0
    if (Number(existing.enabled || 0) === nextEnabled) {
      return { roleId: id, enabled: nextEnabled === 1, changed: false }
    }
    const updatedAt = now()
    await execute('UPDATE local_roles SET enabled = ?, updated_at = ? WHERE role_id = ?', [nextEnabled, updatedAt, id])
    if (nextEnabled === 0) {
      const remainingAdmins = await enabledAdminCredentialIds()
      if (!remainingAdmins.length) {
        await execute('UPDATE local_roles SET enabled = ?, updated_at = ? WHERE role_id = ?', [1, now(), id])
        throw new Error('不能停用最后一个具有账号权限管理能力的角色')
      }
    }
    return { roleId: id, enabled: nextEnabled === 1, changed: true, updatedAt }
  }

  const buildSessionProjection = async (credential = {}, roleIds = []) => {
    const roles = await loadRoles(roleIds)
    const permissions = await resolvePermissions(roleIds)
    const loginAt = now()
    const ttlSeconds = Math.max(60, Number(credential.ttl_seconds || DEFAULT_SESSION_TTL_SECONDS))
    const expiresAt = loginAt + ttlSeconds * 1000
    return {
      credentialId: credential.credential_id,
      credentialLabel: credential.credential_label,
      roles,
      roleIds,
      permissions,
      loginAt,
      expiresAt,
      ttlSeconds,
      needsPasswordChange: credential.password_state === 'INITIAL'
    }
  }

  const backfillRoleDescriptions = async () => {
    let rows = []
    try {
      rows = normalizeRows(await query('SELECT role_id, description, raw_json FROM local_roles', []))
    } catch (error) {
      const message = String(error?.message || error || '')
      if (message.includes('no such column')) return
      throw error
    }
    for (const row of rows) {
      if (String(row.description || '').trim()) continue
      const raw = parseJson(row.raw_json, {})
      const description = String(raw.description || '').trim()
      if (!description) continue
      await execute(
        'UPDATE local_roles SET description = ?, updated_at = ? WHERE role_id = ?',
        [description, now(), row.role_id]
      )
    }
  }

  const tableColumns = async (table) => {
    try {
      return normalizeRows(await query(`PRAGMA table_info(${table})`, []))
        .map((row) => row.name)
        .filter(Boolean)
    } catch (error) {
      const message = String(error?.message || error || '')
      if (message.includes('Unsupported SQL')) return []
      throw error
    }
  }

  const ensureStrictAuthSessionSchema = async () => {
    const columns = await tableColumns('local_auth_session')
    if (!columns.length) return
    const extraColumns = columns.filter((column) => column === 'permission_keys' || column === 'raw_json')
    if (!extraColumns.length && columns.includes('ttl_seconds') && columns.includes('updated_at')) return
    const ttlExpr = columns.includes('ttl_seconds') ? 'ttl_seconds' : String(DEFAULT_SESSION_TTL_SECONDS)
    const updatedExpr = columns.includes('updated_at') ? 'updated_at' : '0'
    await execute(
      'CREATE TABLE IF NOT EXISTS local_auth_session_next (session_key TEXT PRIMARY KEY, credential_id TEXT NOT NULL, role_ids TEXT NOT NULL, login_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, ttl_seconds INTEGER NOT NULL DEFAULT 3600, updated_at INTEGER NOT NULL, FOREIGN KEY(credential_id) REFERENCES local_auth_credentials(credential_id))'
    )
    await execute(
      `INSERT OR REPLACE INTO local_auth_session_next(session_key, credential_id, role_ids, login_at, expires_at, ttl_seconds, updated_at) SELECT session_key, credential_id, role_ids, login_at, expires_at, ${ttlExpr}, ${updatedExpr} FROM local_auth_session`
    )
    await execute('DROP TABLE IF EXISTS local_auth_session')
    await execute('ALTER TABLE local_auth_session_next RENAME TO local_auth_session')
  }

  const clearLocalSession = async () => {
    await initializeSchema()
    await execute('DELETE FROM local_auth_session WHERE session_key = ?', [AUTH_SESSION_KEY])
    return { success: true }
  }

  const logoutLocal = clearLocalSession

  const loginLocal = async (password) => {
    const plain = String(password || '').trim()
    if (!isSixDigitPassword(plain)) {
      throw new Error('请输入6位数字密码')
    }
    await initializeSchema()
    const credentials = normalizeRows(await query('SELECT * FROM local_auth_credentials WHERE enabled = 1', []))
    const current = now()
    const preferredCredentialId = plain === DEFAULT_DEVELOPER_PASSWORD
      ? 'builtin:DEVELOPER'
      : plain === DEFAULT_SUPER_ADMIN_PASSWORD
        ? 'builtin:SUPER_ADMIN'
        : ''
    const preferredCredential = preferredCredentialId
      ? credentials.find((credential) => credential.credential_id === preferredCredentialId)
      : null
    // 最近成功账号先校验可让常用登录只执行一次 PBKDF2，密码唯一性仍由所有写入入口保证。
    const recentCredential = credentials
      .filter((credential) => Number(credential.last_login_at || 0) > 0)
      .sort((left, right) => Number(right.last_login_at || 0) - Number(left.last_login_at || 0))[0] || null
    const fastCredential = preferredCredential || recentCredential
    let matched = []
    if (
      fastCredential &&
      Number(fastCredential.locked_until || 0) <= current &&
      await verifyPasswordHash(plain, fastCredential)
    ) {
      matched = [fastCredential]
    } else {
      const verifyCandidates = async (candidates) => {
        const verificationResults = await Promise.all(
          candidates.map(async (credential) => ({
            credential,
            eligible: Number(credential.locked_until || 0) <= current,
            matched: Number(credential.locked_until || 0) <= current
              ? await verifyPasswordHash(plain, credential)
              : false
          }))
        )
        return verificationResults
          .filter((result) => result.eligible && result.matched)
          .map((result) => result.credential)
      }
      // 登录弹窗首先服务于后台配置的两组系统密码。先只校验这两条，
      // 不能因大量普通本机账号并行哈希而拖慢管理员进入。
      const systemCredentials = credentials.filter((credential) =>
        Boolean(SYSTEM_CREDENTIAL_ROLES[credential.credential_id]) && credential !== fastCredential
      )
      matched = await verifyCandidates(systemCredentials)
      if (!matched.length) {
        matched = await verifyCandidates(credentials.filter((credential) =>
          credential !== fastCredential && !SYSTEM_CREDENTIAL_ROLES[credential.credential_id]
        ))
      }
    }
    if (matched.length > 1) throw new Error('本地管理员密码配置冲突')
    if (!matched.length) {
      for (const credential of credentials) {
        if (Number(credential.locked_until || 0) > current) continue
        const failedCount = Number(credential.failed_count || 0) + 1
        const lockedUntil = failedCount >= PASSWORD_MAX_FAILED ? current + PASSWORD_LOCK_MS : 0
        await execute(
          'UPDATE local_auth_credentials SET failed_count = ?, locked_until = ?, updated_at = ? WHERE credential_id = ?',
          [failedCount, lockedUntil, current, credential.credential_id]
        )
      }
      throw new Error('密码错误')
    }
    const candidates = []
    for (const credential of matched) {
      let roleIds = await loadRoleIdsForCredential(credential.credential_id)
      if (!roleIds.length && SYSTEM_CREDENTIAL_ROLES[credential.credential_id]) {
        roleIds = await restoreSystemCredentialBinding(credential.credential_id)
      }
      const activeRoleIds = (await loadRoles(roleIds)).map((role) => role.roleId)
      if (activeRoleIds.length) candidates.push({ credential, roleIds: activeRoleIds })
    }
    let selected = preferredCredentialId
      ? candidates.find((item) => item.credential.credential_id === preferredCredentialId)
      : null
    if (!selected) {
      if (candidates.length === 1) {
        selected = candidates[0]
      } else if (candidates.length > 1) {
        throw new Error('本地管理员密码配置冲突')
      }
    }
    if (!selected) throw new Error('本地管理员未绑定启用角色')
    const { credential, roleIds } = selected
    const session = await buildSessionProjection(credential, roleIds)
    await execute(
      'UPDATE local_auth_credentials SET failed_count = ?, locked_until = ?, last_login_at = ?, updated_at = ? WHERE credential_id = ?',
      [0, 0, current, current, credential.credential_id]
    )
    await execute(
      'INSERT OR REPLACE INTO local_auth_session(session_key, credential_id, role_ids, login_at, expires_at, ttl_seconds, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?)',
      [
        AUTH_SESSION_KEY,
        credential.credential_id,
        JSON.stringify(roleIds),
        session.loginAt,
        session.expiresAt,
        session.ttlSeconds,
        current
      ]
    )
    const previousIterations = Number(credential.password_iterations || PASSWORD_HASH_ITERATIONS)
    if (previousIterations !== PASSWORD_HASH_ITERATIONS) {
      // 历史自定义凭据在成功登录后后台迁移，避免把本次页面跳转继续阻塞在哈希重算上。
      void pbkdf2Hash(plain)
        .then((hash) => execute(
          'UPDATE local_auth_credentials SET password_hash = ?, password_salt = ?, password_iterations = ?, password_algorithm = ?, updated_at = ? WHERE credential_id = ? AND password_iterations = ?',
          [hash.hash, hash.salt, hash.iterations, hash.algorithm, now(), credential.credential_id, previousIterations]
        ))
        .catch(() => {})
    }
    return session
  }

  const refreshLocalSession = async ({ expiresAt, session } = {}) => {
    const nextExpiresAt = Number(expiresAt || 0)
    if (!Number.isFinite(nextExpiresAt) || nextExpiresAt <= now()) {
      return { refreshed: false, reason: 'INVALID_EXPIRES_AT' }
    }
    await initializeSchema()
    const updatedAt = now()
    await execute(
      'UPDATE local_auth_session SET expires_at = ?, ttl_seconds = ?, updated_at = ? WHERE session_key = ?',
      [
        nextExpiresAt,
        Number(session?.ttlSeconds || DEFAULT_SESSION_TTL_SECONDS),
        updatedAt,
        AUTH_SESSION_KEY
      ]
    )
    return { refreshed: true, expiresAt: nextExpiresAt, updatedAt }
  }

  const upsertSlotSnapshot = async (slot, source = 'SERIAL', fresh = true) => {
    const record = normalizeSlotRecord(slot, source, fresh)
    if (!record) return null
    await initializeSchema()
    await execute(
      'INSERT OR REPLACE INTO slots_snapshot(slot_number, status, card_id, employee_id, source, fresh, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?)',
      [
        record.slotNumber,
        record.status,
        record.cardId,
        record.employeeId,
        record.source,
        record.fresh,
        record.updatedAt,
        JSON.stringify(record.raw)
      ]
    )
    return record.raw
  }

  const saveSlotsSnapshot = async (slots = [], source = 'LOCAL_OPERATION', fresh = false) => {
    const records = (Array.isArray(slots) ? slots : [])
      .map((slot) => normalizeSlotRecord(slot, source, fresh))
      .filter(Boolean)
    if (!records.length) return []
    await initializeSchema()
    const values = records.map(() => '(?, ?, ?, ?, ?, ?, ?, ?)').join(', ')
    const params = records.flatMap((record) => [
      record.slotNumber,
      record.status,
      record.cardId,
      record.employeeId,
      record.source,
      record.fresh,
      record.updatedAt,
      JSON.stringify(record.raw)
    ])
    await execute(
      `INSERT OR REPLACE INTO slots_snapshot(slot_number, status, card_id, employee_id, source, fresh, updated_at, raw_json) VALUES ${values}`,
      params
    )
    return records.map((record) => record.raw)
  }

  const loadSlotsSnapshot = async () => {
    await initializeSchema()
    const result = await query('SELECT raw_json FROM slots_snapshot ORDER BY slot_number ASC', [])
    return normalizeRows(result)
      .map((row) => parseJson(row?.raw_json, null))
      .filter(Boolean)
      .map((slot) => ({ ...slot, fresh: false }))
  }

  const listFreshSlotsSnapshot = async () => {
    await initializeSchema()
    const result = await query(
      'SELECT * FROM slots_snapshot WHERE fresh = 1 ORDER BY slot_number ASC',
      []
    )
    return normalizeRows(result).map(slotFromRow).filter(Boolean)
  }

  const getSlotSnapshot = async (slotNumber) => {
    const normalizedSlotNumber = Number(slotNumber)
    if (!Number.isInteger(normalizedSlotNumber) || normalizedSlotNumber < 1) return null
    await initializeSchema()
    const result = await query(
      'SELECT * FROM slots_snapshot WHERE slot_number = ? LIMIT 1',
      [normalizedSlotNumber]
    )
    return slotFromRow(normalizeRows(result)[0])
  }

  const saveOperationRecord = async (operation = {}) => {
    const operationType = String(operation.operationType || operation.type || '').trim().toUpperCase()
    if (!operationType) return null
    await initializeSchema()
    const current = now()
    const operationId = String(operation.operationId || createLocalId(`operation:${operationType}`)).trim()
    const existingResult = await query('SELECT * FROM operations WHERE operation_id = ? LIMIT 1', [operationId])
    const existing = operationFromRow(normalizeRows(existingResult)[0])
    const state = String(operation.state || existing?.state || 'PENDING').trim().toUpperCase()
    const createdAt = Number(operation.createdAt || existing?.createdAt || current)
    const updatedAt = Number(operation.updatedAt || current)
    const hasFinishedAt = Object.prototype.hasOwnProperty.call(operation, 'finishedAt')
    const finishedAt = hasFinishedAt
      ? Number(operation.finishedAt || 0) || null
      : (TERMINAL_OPERATION_STATES.has(state) ? updatedAt : (existing?.finishedAt || null))
    const slotNumber = operation.slotNumber == null
      ? (existing?.slotNumber ?? null)
      : Number(operation.slotNumber)
    const cardNo = String(operation.cardNo ?? existing?.cardNo ?? '').trim()
    const physicalConfirmedAt = Number(
      operation.physicalConfirmedAt ?? existing?.physicalConfirmedAt ?? 0
    ) || null
    const raw = {
      ...(existing || {}),
      ...operation,
      operationId,
      operationType,
      state,
      slotNumber,
      cardNo,
      physicalConfirmedAt,
      createdAt,
      updatedAt,
      finishedAt
    }
    await execute(
      'INSERT OR REPLACE INTO operations(operation_id, operation_type, employee_id, face_id, slot_number, card_no, physical_confirmed_at, state, offline, created_at, updated_at, finished_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        operationId,
        operationType,
        String(operation.employeeId ?? existing?.employeeId ?? ''),
        String(operation.faceId ?? existing?.faceId ?? ''),
        raw.slotNumber,
        cardNo || null,
        physicalConfirmedAt,
        state,
        Boolean(operation.offline ?? existing?.offline) ? 1 : 0,
        createdAt,
        updatedAt,
        finishedAt,
        JSON.stringify(raw)
      ]
    )
    return raw
  }

  const listOperationHistory = async (limit = 50) => {
    await initializeSchema()
    const requestedLimit = Number(limit)
    const normalizedLimit = Number.isFinite(requestedLimit)
      ? Math.max(1, Math.min(200, Math.trunc(requestedLimit)))
      : 50
    const result = await query(
      'SELECT * FROM operations ORDER BY COALESCE(finished_at, updated_at, created_at) DESC LIMIT ?',
      [normalizedLimit]
    )
    return normalizeRows(result)
      .map(operationFromRow)
      .filter(Boolean)
      .map((operation) => {
      const timestamp = Number(operation?.finishedAt || operation?.updatedAt || operation?.createdAt || 0)
      const slotNumber = Number(operation.slotNumber || 0)
      const requestedCount = Math.max(0, Number(operation.requestedCount || 0))
      const successCount = Math.max(0, Number(operation.successCount || 0))
      const failedCount = Math.max(0, Number(operation.failedCount || 0))
      const failures = Array.isArray(operation.failures)
        ? operation.failures.map((failure) => ({
          slotNumber: Number(failure?.slotNumber || 0) || '',
          code: String(failure?.code || ''),
          message: String(failure?.message || '')
        }))
        : []
      const rawError = operation.rawError && typeof operation.rawError === 'object'
        ? operation.rawError
        : {}
      const resultMeta = operationResultMeta(operation.state)
      const nonSlotTargetLabels = {
        FACE_ENROLLMENT: '人脸信息',
        RESTART_APP: '设备应用',
        APP_UPDATE: '设备应用',
        APP_UPDATE_CHECK: '设备应用',
        FIRMWARE_UPGRADE: '设备固件',
        EMPLOYEE_ADD: '人员',
        EMPLOYEE_UPDATE: '人员',
        EMPLOYEE_DISABLE: '人员',
        EMPLOYEE_ENABLE: '人员'
      }
      const targetLabel = operation.operationType === 'UNLOCK_ALL'
        ? (requestedCount > 0 ? `待弹卡槽（${requestedCount}个）` : '待弹卡槽')
        : (nonSlotTargetLabels[operation.operationType] || operation.employeeName || operation.employeeId || (slotNumber > 0 ? `${String(slotNumber).padStart(2, '0')}号卡门` : '未指定卡槽'))
      return {
        id: operation.operationId,
        operationId: operation.operationId,
        type: operationTypeLabel(operation.operationType),
        operationType: operation.operationType,
        employeeName: operation.employeeName || operation.operatorName || operation.operator || operation.employeeId || '本机管理员',
        operatorName: operation.operatorName || operation.operator || operation.employeeName || operation.employeeId || '本机管理员',
        slotNumber: slotNumber || '',
        targetLabel,
        result: resultMeta.label,
        resultKind: resultMeta.kind,
        state: operation.state,
        timestamp,
        createdAt: formatDateTime(timestamp),
        requestedCount,
        successCount,
        failedCount,
        failures,
        errorCode: String(operation.errorCode || rawError.code || ''),
        errorMessage: String(operation.errorMessage || rawError.message || ''),
        raw: operation
      }
    })
  }

  const getOperationRecord = async (operationId) => {
    const id = String(operationId || '').trim()
    if (!id) return null
    await initializeSchema()
    const result = await query('SELECT * FROM operations WHERE operation_id = ? LIMIT 1', [id])
    return operationFromRow(normalizeRows(result)[0])
  }

  const listRecoverableOperations = async (limit = 50) => {
    const normalizedLimit = Math.max(1, Math.min(200, Number(limit || 50)))
    await initializeSchema()
    const statePlaceholders = RECOVERABLE_OPERATION_STATE_LIST.map(() => '?').join(', ')
    const result = await query(
      `SELECT * FROM operations WHERE state IN (${statePlaceholders}) ORDER BY updated_at ASC LIMIT ?`,
      [...RECOVERABLE_OPERATION_STATE_LIST, normalizedLimit]
    )
    return normalizeRows(result)
      .map(operationFromRow)
      .filter((operation) => operation && RECOVERABLE_OPERATION_STATES.has(operation.state))
      .slice(0, normalizedLimit)
  }

  const getOutboxEvent = async (eventId) => {
    const id = String(eventId || '').trim()
    if (!id) return null
    await initializeSchema()
    const result = await query('SELECT * FROM outbox_events WHERE event_id = ? LIMIT 1', [id])
    return outboxEventFromRow(normalizeRows(result)[0])
  }

  const upsertOutboxEvent = async (event = {}) => {
    const eventId = String(event.eventId || '').trim()
    const eventType = String(event.eventType || '').trim()
    if (!eventId || !eventType) return null
    await initializeSchema()
    const current = now()
    const existing = await getOutboxEvent(eventId)
    const state = String(event.state || existing?.state || 'PENDING').toUpperCase()
    const attemptCount = Number.isFinite(Number(event.attemptCount))
      ? Number(event.attemptCount)
      : Number(existing?.attemptCount || 0)
    const createdAt = Number(existing?.createdAt || event.createdAt || current)
    const payload = event.payload === undefined ? existing?.payload || {} : event.payload
    await execute(
      'INSERT OR REPLACE INTO outbox_events(event_id, event_type, operation_id, payload, state, attempt_count, next_attempt_at, last_error, created_at, updated_at, acked_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        eventId,
        eventType,
        String(event.operationId ?? existing?.operationId ?? ''),
        JSON.stringify(payload || {}),
        state,
        attemptCount,
        Number(event.nextAttemptAt ?? existing?.nextAttemptAt ?? 0),
        String(event.lastError ?? existing?.lastError ?? ''),
        createdAt,
        current,
        Number(event.ackedAt ?? existing?.ackedAt ?? 0)
      ]
    )
    return getOutboxEvent(eventId)
  }

  const listDueOutboxEvents = async (eventType, limit = 20) => {
    const type = String(eventType || '').trim()
    if (!type) return []
    await initializeSchema()
    const current = now()
    const result = await query(
      'SELECT * FROM outbox_events WHERE event_type = ? AND state IN (?, ?) AND next_attempt_at <= ? ORDER BY created_at ASC LIMIT ?',
      [type, 'PENDING', 'FAILED', current, Math.max(1, Number(limit || 20))]
    )
    return normalizeRows(result).map(outboxEventFromRow).filter(Boolean)
  }

  const markOutboxEventSent = async (eventId) => {
    const id = String(eventId || '').trim()
    if (!id) return null
    await initializeSchema()
    const current = now()
    await execute(
      'UPDATE outbox_events SET state = ?, updated_at = ?, acked_at = ?, last_error = ? WHERE event_id = ?',
      ['SENT', current, current, '', id]
    )
    return getOutboxEvent(id)
  }

  const markOutboxEventFailed = async (eventId, error, retryDelayMs = 10000) => {
    const id = String(eventId || '').trim()
    if (!id) return null
    await initializeSchema()
    const current = now()
    await execute(
      'UPDATE outbox_events SET state = ?, attempt_count = attempt_count + 1, next_attempt_at = ?, last_error = ?, updated_at = ? WHERE event_id = ?',
      [
        'FAILED',
        current + Math.max(1000, Number(retryDelayMs || 10000)),
        String(error?.message || error || ''),
        current,
        id
      ]
    )
    return getOutboxEvent(id)
  }

  const deleteSlotsSnapshotAbove = async (maxSlotNumber) => {
    if (!Number.isInteger(maxSlotNumber) || maxSlotNumber < 1) return 0
    await initializeSchema()
    await execute('DELETE FROM slots_snapshot WHERE slot_number > ?', [maxSlotNumber])
    return 0 // SQLite execute doesn't return affected rows count
  }

  const getSyncCursor = async (scope) => {
    await initializeSchema()
    const result = await query('SELECT fetched_version, applied_version, raw_json FROM sync_cursors WHERE scope = ? LIMIT 1', [scope])
    const row = normalizeRows(result)[0]
    return {
      scope,
      fetchedVersion: Number(row?.fetched_version || 0),
      appliedVersion: Number(row?.applied_version || 0),
      raw: parseJson(row?.raw_json, null)
    }
  }

  const advanceSyncCursor = async (scope, version, raw = {}) => {
    const nextVersion = Number(version || 0)
    if (!scope || !Number.isFinite(nextVersion) || nextVersion < 0) return null
    const updatedAt = now()
    await initializeSchema()
    await execute(
      'INSERT OR REPLACE INTO sync_cursors(scope, fetched_version, applied_version, updated_at, raw_json) VALUES(?, ?, ?, ?, ?)',
      [scope, nextVersion, nextVersion, updatedAt, JSON.stringify({ ...(raw || {}), appliedAt: updatedAt })]
    )
    return { scope, fetchedVersion: nextVersion, appliedVersion: nextVersion, updatedAt }
  }

  const upsertEmployees = async (items = [], syncVersion = 0) => {
    await initializeSchema()
    const saved = []
    let skipped = 0
    for (const item of Array.isArray(items) ? items : []) {
      const record = normalizeEmployeeRecord(item, syncVersion)
      if (!record) {
        skipped += 1
        continue
      }
      await execute(
        'INSERT OR REPLACE INTO employees(employee_id, employee_code, employee_name, department_name, enabled, auth_state, updated_at, expires_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          record.employeeId,
          record.employeeCode,
          record.employeeName,
          record.departmentName,
          record.enabled,
          record.authState,
          record.updatedAt,
          record.expiresAt,
          JSON.stringify(record.raw)
        ]
      )
      saved.push(record.raw)
    }
    return { saved, skipped }
  }

  const disableEmployees = async (employeeIds = [], syncVersion = 0) => {
    await initializeSchema()
    const ids = Array.isArray(employeeIds) ? employeeIds : []
    const updatedAt = Number(syncVersion || now())
    for (const id of ids) {
      const employeeId = String(id ?? '').trim()
      if (!employeeId) continue
      await execute('UPDATE employees SET enabled = ?, auth_state = ?, updated_at = ? WHERE employee_id = ?', [0, 'DELETED', updatedAt, employeeId])
      await execute(
        'UPDATE face_bindings SET enabled = ?, upload_state = CASE WHEN biometric_type = ? THEN ? ELSE upload_state END, updated_at = ? WHERE employee_id = ?',
        [0, BIOMETRIC_TYPE.FACE, FACE_PHOTO_UPLOAD_STATE.DISABLED, updatedAt, employeeId]
      )
    }
    return { disabled: ids.length }
  }

  const upsertFaceBindings = async (items = [], syncVersion = 0) => {
    await initializeSchema()
    const saved = []
    let skipped = 0
    for (const item of Array.isArray(items) ? items : []) {
      const record = normalizeFaceBindingRecord(item, syncVersion)
      if (!record) {
        skipped += 1
        continue
      }
      await execute(
        `INSERT INTO face_bindings(
          biometric_type, binding_id, employee_id, native_id, biometric_index,
          enabled, updated_at, expires_at, raw_json
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(biometric_type, binding_id) DO UPDATE SET
          employee_id = excluded.employee_id,
          native_id = excluded.native_id,
          biometric_index = excluded.biometric_index,
          enabled = excluded.enabled,
          updated_at = excluded.updated_at,
          expires_at = excluded.expires_at,
          raw_json = excluded.raw_json`,
        [
          BIOMETRIC_TYPE.FACE,
          record.faceId,
          record.employeeId,
          record.faceAiId,
          record.faceIndex,
          record.enabled,
          record.updatedAt,
          record.expiresAt,
          JSON.stringify(record.raw)
        ]
      )
      saved.push(record.raw)
    }
    return { saved, skipped }
  }

  const upsertFingerBindings = async (items = [], syncVersion = 0) => {
    await initializeSchema()
    const saved = []
    let skipped = 0
    for (const item of Array.isArray(items) ? items : []) {
      const record = normalizeFingerBindingRecord(item, syncVersion)
      if (!record) {
        skipped += 1
        continue
      }
      await execute(
        `INSERT INTO face_bindings(
          biometric_type, binding_id, employee_id, native_id, biometric_index,
          enabled, updated_at, expires_at, raw_json
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(biometric_type, binding_id) DO UPDATE SET
          employee_id = excluded.employee_id,
          biometric_index = excluded.biometric_index,
          enabled = excluded.enabled,
          updated_at = excluded.updated_at,
          expires_at = excluded.expires_at,
          raw_json = excluded.raw_json`,
        [
          BIOMETRIC_TYPE.FINGER,
          record.fingerId,
          record.employeeId,
          null,
          record.fingerIndex,
          record.enabled,
          record.updatedAt,
          record.expiresAt,
          JSON.stringify(record.raw)
        ]
      )
      saved.push(record.raw)
    }
    return { saved, skipped }
  }

  const loadEmployees = async ({ includeDisabled = false } = {}) => {
    await initializeSchema()
    const where = includeDisabled ? '' : 'WHERE enabled = 1'
    const result = await query(`SELECT raw_json FROM employees ${where} ORDER BY employee_name ASC, employee_id ASC`, [])
    return normalizeRows(result)
      .map((row) => parseJson(row?.raw_json, null))
      .filter(Boolean)
  }

  const getEmployeeById = async (employeeId) => {
    const id = String(employeeId || '').trim()
    if (!id) return null
    await initializeSchema()
    const result = await query('SELECT * FROM employees WHERE employee_id = ? LIMIT 1', [id])
    return employeeFromRow(normalizeRows(result)[0])
  }

  const getFaceBindingById = async (faceId) => {
    const id = String(faceId || '').trim()
    if (!id) return null
    await initializeSchema()
    const result = await query(
      'SELECT * FROM face_bindings WHERE biometric_type = ? AND (binding_id = ? OR native_id = ?) LIMIT 1',
      [BIOMETRIC_TYPE.FACE, id, id]
    )
    return faceBindingFromRow(normalizeRows(result)[0])
  }

  const listFaceBindingsByEmployee = async (employeeId, { includeDisabled = false } = {}) => {
    const id = String(employeeId || '').trim()
    if (!id) return []
    await initializeSchema()
    const enabledClause = includeDisabled ? '' : 'AND enabled = 1'
    const result = await query(
      `SELECT * FROM face_bindings WHERE biometric_type = ? AND employee_id = ? ${enabledClause} ORDER BY updated_at DESC, binding_id DESC`,
      [BIOMETRIC_TYPE.FACE, id]
    )
    return normalizeRows(result).map(faceBindingFromRow).filter(Boolean)
  }

  /** BUG-026: 批量获取所有员工的人脸绑定数量 */
  const getEmployeeFaceCounts = async () => {
    await initializeSchema()
    const result = await query(
      `SELECT employee_id, COUNT(*) as face_count FROM face_bindings WHERE biometric_type = ? AND enabled = 1 GROUP BY employee_id`,
      [BIOMETRIC_TYPE.FACE]
    )
    const map = {}
    for (const row of normalizeRows(result)) {
      if (row.employee_id) map[String(row.employee_id)] = Number(row.face_count) || 0
    }
    return map
  }

  const saveLocalFaceBinding = async (binding = {}, syncVersion = 0) => {
    const employeeId = String(binding.employeeId || '').trim()
    const faceId = String(binding.faceId || '').trim()
    if (!employeeId || !faceId) return { saved: false, reason: 'INVALID_FACE_BINDING' }
    const employee = await getEmployeeById(employeeId)
    if (!employee) return { saved: false, reason: 'EMPLOYEE_NOT_FOUND' }
    if (!employee.enabled) return { saved: false, reason: 'EMPLOYEE_DISABLED', employee }
    const result = await upsertFaceBindings([
      {
        ...cleanBiometricRaw(binding),
        faceId,
        employeeId,
        enabled: binding.enabled !== false
      }
    ], syncVersion)
    if (!result.saved.length) return { saved: false, reason: 'INVALID_FACE_BINDING' }
    return {
      saved: true,
      employee,
      faceBinding: await getFaceBindingById(faceId)
    }
  }

  const getFacePhotoByFaceId = async (faceId) => {
    const id = String(faceId || '').trim()
    if (!id) return null
    await initializeSchema()
    const result = await query(
      'SELECT * FROM face_bindings WHERE biometric_type = ? AND binding_id = ? LIMIT 1',
      [BIOMETRIC_TYPE.FACE, id]
    )
    return facePhotoFromRow(normalizeRows(result)[0])
  }

  const getFacePhotoByFileHash = async (fileHash) => {
    const hash = String(fileHash || '').trim().toLowerCase()
    if (!hash) return null
    await initializeSchema()
    const result = await query(
      'SELECT * FROM face_bindings WHERE biometric_type = ? AND file_hash = ? LIMIT 1',
      [BIOMETRIC_TYPE.FACE, hash]
    )
    return facePhotoFromRow(normalizeRows(result)[0])
  }

  const saveFacePhoto = async (photo = {}) => {
    const faceId = String(photo.faceId || '').trim()
    const employeeId = String(photo.employeeId || '').trim()
    if (!faceId || !employeeId) return { saved: false, reason: 'INVALID_FACE_PHOTO_OWNER' }
    const employee = await getEmployeeById(employeeId)
    if (!employee) return { saved: false, reason: 'EMPLOYEE_NOT_FOUND' }
    if (!employee.enabled) return { saved: false, reason: 'EMPLOYEE_DISABLED', employee }

    const normalizedPhoto = normalizePhotoBase64(
      photo.photoBase64 || photo.faceImageBase64 || photo.imageBase64 || photo.base64
    )
    if (!normalizedPhoto.ok) return { saved: false, ...normalizedPhoto }

    const existing = await getFacePhotoByFaceId(faceId)
    const fileHash = String(photo.fileHash ?? existing?.fileHash ?? '').trim().toLowerCase()
    if (fileHash && !/^[a-f0-9]{64}$/.test(fileHash)) {
      return { saved: false, reason: 'INVALID_FACE_PHOTO_HASH' }
    }
    if (fileHash) {
      const duplicate = await getFacePhotoByFileHash(fileHash)
      if (duplicate && duplicate.faceId !== faceId) {
        return {
          saved: false,
          reason: 'FACE_PHOTO_DUPLICATE',
          duplicate: { faceId: duplicate.faceId, employeeId: duplicate.employeeId }
        }
      }
    }
    const uploadState = String(photo.uploadState || existing?.uploadState || FACE_PHOTO_UPLOAD_STATE.PENDING).toUpperCase()
    if (!FACE_PHOTO_UPLOAD_STATES.has(uploadState)) {
      return { saved: false, reason: 'INVALID_FACE_PHOTO_UPLOAD_STATE' }
    }
    const updatedAt = Number(photo.updatedAt || now())
    const raw = {
      ...cleanFacePhotoMetadata(existing || {}),
      ...cleanFacePhotoMetadata(photo),
      faceId,
      employeeId,
      mimeType: String(photo.mimeType || existing?.mimeType || 'image/jpeg'),
      byteSize: normalizedPhoto.byteSize,
      source: String(photo.source || existing?.source || 'UNKNOWN'),
      uploadState,
      fileHash,
      updatedAt
    }
    await initializeSchema()
    await execute(
      `INSERT INTO face_bindings(
        biometric_type, binding_id, employee_id, native_id, biometric_index, enabled,
        updated_at, expires_at, mime_type, byte_size, photo_base64, source, upload_state,
        upload_id, server_path, server_url, file_hash, last_error, synced_at, raw_json
      ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(biometric_type, binding_id) DO UPDATE SET
        employee_id = excluded.employee_id,
        updated_at = excluded.updated_at,
        mime_type = excluded.mime_type,
        byte_size = excluded.byte_size,
        photo_base64 = excluded.photo_base64,
        source = excluded.source,
        upload_state = excluded.upload_state,
        upload_id = excluded.upload_id,
        server_path = excluded.server_path,
        server_url = excluded.server_url,
        file_hash = excluded.file_hash,
        last_error = excluded.last_error,
        synced_at = excluded.synced_at,
        raw_json = excluded.raw_json`,
      [
        BIOMETRIC_TYPE.FACE,
        faceId,
        employeeId,
        null,
        null,
        0,
        updatedAt,
        null,
        raw.mimeType,
        normalizedPhoto.byteSize,
        normalizedPhoto.photoBase64,
        raw.source,
        uploadState,
        String(photo.uploadId ?? existing?.uploadId ?? ''),
        String(photo.serverPath ?? existing?.serverPath ?? ''),
        String(photo.serverUrl ?? existing?.serverUrl ?? ''),
        fileHash,
        String(photo.lastError ?? existing?.lastError ?? ''),
        Number(photo.syncedAt ?? existing?.syncedAt ?? 0) || null,
        JSON.stringify(raw)
      ]
    )
    return { saved: true, photo: await getFacePhotoByFaceId(faceId) }
  }

  const updateFacePhotoUploadState = async (faceId, update = {}) => {
    const current = await getFacePhotoByFaceId(faceId)
    if (!current) return { saved: false, reason: 'FACE_PHOTO_NOT_FOUND' }
    const uploadState = String(update.uploadState || current.uploadState).toUpperCase()
    if (!FACE_PHOTO_UPLOAD_STATES.has(uploadState)) {
      return { saved: false, reason: 'INVALID_FACE_PHOTO_UPLOAD_STATE' }
    }
    const updatedAt = Number(update.updatedAt || now())
    await execute(
      'UPDATE face_bindings SET upload_state = ?, upload_id = ?, server_url = ?, last_error = ?, updated_at = ?, synced_at = ? WHERE biometric_type = ? AND binding_id = ?',
      [
        uploadState,
        String(update.uploadId ?? current.uploadId ?? ''),
        String(update.serverUrl ?? current.serverUrl ?? ''),
        String(update.lastError ?? current.lastError ?? ''),
        updatedAt,
        Number(update.syncedAt ?? current.syncedAt ?? 0) || null,
        BIOMETRIC_TYPE.FACE,
        current.faceId
      ]
    )
    return { saved: true, photo: await getFacePhotoByFaceId(current.faceId) }
  }

  const listPendingFacePhotos = async (limit = 20) => {
    await initializeSchema()
    const safeLimit = Math.max(1, Math.min(100, Number(limit) || 20))
    const result = await query(
      'SELECT * FROM face_bindings WHERE biometric_type = ? AND upload_state IN (?, ?, ?) ORDER BY updated_at ASC LIMIT ?',
      [
        BIOMETRIC_TYPE.FACE,
        FACE_PHOTO_UPLOAD_STATE.PENDING,
        FACE_PHOTO_UPLOAD_STATE.UPLOADED,
        FACE_PHOTO_UPLOAD_STATE.RETRY_WAIT,
        safeLimit
      ]
    )
    return normalizeRows(result).map(facePhotoFromRow).filter(Boolean)
  }

  const resolveEmployeeByFaceId = async (faceId, atMs = now()) => {
    const id = String(faceId || '').trim()
    if (!id) return { ok: false, reason: 'INVALID_FACE_ID' }
    const checkedAt = Number(atMs)
    const current = Number.isFinite(checkedAt) && checkedAt > 0 ? checkedAt : now()
    const faceBinding = await getFaceBindingById(id)
    if (!faceBinding) return { ok: false, reason: 'FACE_BINDING_NOT_FOUND', faceId: id }
    if (!faceBinding.enabled) return { ok: false, reason: 'FACE_BINDING_DISABLED', faceId: id, faceBinding }
    if (isExpiredAt(faceBinding.expiresAt, current)) {
      return { ok: false, reason: 'FACE_BINDING_EXPIRED', faceId: id, faceBinding }
    }
    const employee = await getEmployeeById(faceBinding.employeeId)
    if (!employee) return { ok: false, reason: 'EMPLOYEE_NOT_FOUND', faceId: id, faceBinding }
    if (!employee.enabled) return { ok: false, reason: 'EMPLOYEE_DISABLED', faceId: id, faceBinding, employee }
    if (isExpiredAt(employee.expiresAt, current)) {
      return { ok: false, reason: 'EMPLOYEE_EXPIRED', faceId: id, faceBinding, employee }
    }
    const cardNo = String(employee.cardNo || employee.cardId || employee.cardNumber || '').trim()
    return { ok: true, faceId: id, faceBinding, employee, cardNo }
  }

  return {
    setAdapter,
    initializeSchema,
    saveBootstrapConfig,
    saveRuntimeConfig,
    saveConfigDraft,
    saveInitialAdminPassword,
    syncSystemCredentialsFromConfig,
    syncDeveloperCredentialFromConfig,
    saveOfflineActivationState,
    saveOfflineConfigMeta,
    saveLogUploadPolicy,
    loadBootstrapConfig,
    loadRuntimeConfig,
    loadConfigDraft,
    loadInitialAdminState,
    loginLocal,
    logoutLocal,
    clearLocalSession,
    refreshLocalSession,
    listLocalPermissions,
    saveLocalPermission,
    deleteLocalPermission,
    listLocalRoles,
    saveLocalRole,
    setLocalRoleEnabled,
    deleteLocalRole,
    listLocalCredentials,
    saveLocalCredential,
    deleteLocalCredential,
    unlockLocalCredential,
    verifySecondaryPassword,
    changeSecondaryPassword,
    changeLocalCredentialPassword,
    loadOfflineActivationState,
    loadOfflineConfigMeta,
    loadLogUploadPolicy,
    upsertSlotSnapshot,
    saveSlotsSnapshot,
    loadSlotsSnapshot,
    listFreshSlotsSnapshot,
    getSlotSnapshot,
    saveOperationRecord,
    listOperationHistory,
    getOperationRecord,
    listRecoverableOperations,
    getOutboxEvent,
    upsertOutboxEvent,
    listDueOutboxEvents,
    markOutboxEventSent,
    markOutboxEventFailed,
    deleteSlotsSnapshotAbove,
    getSyncCursor,
    advanceSyncCursor,
    upsertEmployees,
    disableEmployees,
    upsertFaceBindings,
    upsertFingerBindings,
    loadEmployees,
    getEmployeeById,
    getFaceBindingById,
    listFaceBindingsByEmployee,
    getEmployeeFaceCounts,
    saveLocalFaceBinding,
    getFacePhotoByFaceId,
    getFacePhotoByFileHash,
    saveFacePhoto,
    updateFacePhotoUploadState,
    listPendingFacePhotos,
    resolveEmployeeByFaceId,
    insertAuditEvent,
    cleanupAuditEvents
  }
}
