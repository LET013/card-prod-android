import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import initSqlJs from 'sql.js'

const sqlJsDist = fileURLToPath(new URL('../node_modules/sql.js/dist/', import.meta.url))
const localStoreSource = await readFile(new URL('../src/services/localStore.js', import.meta.url), 'utf8')
const localStoreModuleUrl = `data:text/javascript;base64,${Buffer.from(localStoreSource).toString('base64')}`
const { createLocalStore } = await import(localStoreModuleUrl)
const FACE_PHOTO_MAX_BYTES = 10 * 1024 * 1024

async function createSqliteStore(t, beforeInitialize = null, { withAdapter = false } = {}) {
  const SQL = await initSqlJs({ locateFile: (file) => `${sqlJsDist}${file}` })
  const database = new SQL.Database()
  database.run('PRAGMA foreign_keys = ON')
  t.after(() => database.close())

  const adapter = {
    async execute(sql, params = []) {
      database.run(sql, params)
      return { affectedRows: database.getRowsModified() }
    },
    async query(sql, params = []) {
      const statement = database.prepare(sql)
      const rows = []
      try {
        statement.bind(params)
        while (statement.step()) rows.push(statement.getAsObject())
      } finally {
        statement.free()
      }
      return { rows, count: rows.length }
    }
  }

  if (typeof beforeInitialize === 'function') {
    await beforeInitialize({ database, adapter })
  }
  const store = createLocalStore(adapter)
  await store.initializeSchema()
  return withAdapter ? { store, adapter } : store
}

test('local login verifies the most recently successful credential first', () => {
  const loginStart = localStoreSource.indexOf('const loginLocal = async')
  const loginEnd = localStoreSource.indexOf('const refreshLocalSession', loginStart)
  const loginSource = localStoreSource.slice(loginStart, loginEnd)
  assert.match(loginSource, /const recentCredential = credentials/)
  assert.match(loginSource, /Number\(credential\.last_login_at \|\| 0\) > 0/)
  assert.match(loginSource, /const fastCredential = preferredCredential \|\| recentCredential/)
  assert.ok(loginSource.indexOf('await verifyPasswordHash(plain, fastCredential)') < loginSource.indexOf('Promise.all('))
  assert.match(loginSource, /const systemCredentials = credentials\.filter/)
  assert.match(loginSource, /matched = await verifyCandidates\(systemCredentials\)/)
  assert.match(loginSource, /void pbkdf2Hash\(plain\)/)
  assert.match(loginSource, /WHERE credential_id = \? AND password_iterations = \?/)
})

test('uses the device-tuned password cost and refreshes system hashes during config sync', () => {
  assert.match(localStoreSource, /const PASSWORD_HASH_ITERATIONS = 30000/)
  assert.match(localStoreSource, /iterations = PASSWORD_HASH_ITERATIONS/)
  assert.match(localStoreSource, /Number\(record\.password_iterations \|\| PASSWORD_HASH_ITERATIONS\)/)
  assert.match(localStoreSource, /Number\(existing\?\.password_iterations \|\| 0\) !== PASSWORD_HASH_ITERATIONS/)
})

test('labels built-in permissions by their admin menu entry and migrates existing labels', async (t) => {
  const { adapter } = await createSqliteStore(t, null, { withAdapter: true })
  const updatedAt = Date.now()
  await adapter.execute(
    'INSERT OR IGNORE INTO local_permissions(permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    ['system.menu', '旧系统菜单名称', '*', 'system', 'menu', 1, 3, '', updatedAt, updatedAt, '{}']
  )
  await adapter.execute(
    'INSERT OR IGNORE INTO local_permissions(permission_key, permission_name, parent_key, category, item_type, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    ['system.settings.advanced', '高级参数', 'system.*', 'system', 'action', 1, 4, '', updatedAt, updatedAt, '{}']
  )
  await adapter.execute(
    'INSERT INTO local_roles(role_id, role_name, parent_role_id, is_system, enabled, sort_order, description, created_at, updated_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    ['LEGACY_ROLE', '旧角色', '', 0, 1, 99, '', updatedAt, updatedAt, '{}']
  )
  await adapter.execute(
    'INSERT INTO local_role_permissions(role_id, permission_key, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
    ['LEGACY_ROLE', 'system.menu', 1, updatedAt, updatedAt]
  )
  await adapter.execute(
    'INSERT INTO local_role_permissions(role_id, permission_key, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?)',
    ['LEGACY_ROLE', 'system.settings.advanced', 1, updatedAt, updatedAt]
  )
  await adapter.execute('DELETE FROM schema_meta WHERE key = ?', ['permissionTreeVersion'])

  const restartedStore = createLocalStore(adapter)
  await restartedStore.initializeSchema()
  const names = new Map((await restartedStore.listLocalPermissions()).map((item) => [item.permissionKey, item.permissionName]))
  assert.equal(names.get('account.role.*'), '角色管理')
  assert.equal(names.get('account.password.change'), '修改登录密码')
  assert.equal(names.get('system.face.register'), '录入人脸')
  assert.equal(names.get('maintenance.cabinet.eject-all'), '一键弹出')
  assert.equal(names.get('realtime.communication.view'), '查看通信状态')
  assert.equal(names.has('system.menu'), false)
  assert.equal(names.has('system.settings.advanced'), false)
  assert.deepEqual((await restartedStore.listLocalRoles()).find((role) => role.roleId === 'LEGACY_ROLE')?.permissionKeys, ['realtime.communication.*'])
})

test('persists the log upload policy and defaults to disabled', async (t) => {
  const store = await createSqliteStore(t)
  assert.deepEqual(await store.loadLogUploadPolicy(), { enabled: false })

  await store.saveLogUploadPolicy({
    enabled: true,
    command: 'enableLogUpload',
    msgId: 'msg-log-policy-1',
    operatorId: 'operator-1',
    updatedAt: 1_753_001_234_567
  })

  assert.deepEqual(await store.loadLogUploadPolicy(), {
    enabled: true,
    command: 'enableLogUpload',
    msgId: 'msg-log-policy-1',
    operatorId: 'operator-1',
    updatedAt: 1_753_001_234_567
  })
})

test('syncs both backend system passwords as hashes and permits reassignment between system accounts', async (t) => {
  const store = await createSqliteStore(t)
  const first = await store.syncSystemCredentialsFromConfig({
    developerPassword: '654321',
    superAdminPassword: '234567',
    deviceCode: 'DEVICE-001',
    source: 'CONFIG'
  })
  assert.equal(first.saved, true)
  assert.equal(first.developerUpdated, true)
  assert.equal(first.superAdminUpdated, true)
  assert.equal((await store.loginLocal('654321')).credentialId, 'builtin:DEVELOPER')
  assert.equal((await store.loginLocal('234567')).credentialId, 'builtin:SUPER_ADMIN')
  await assert.rejects(store.loginLocal('666666'), /密码错误/)
  await assert.rejects(
    store.changeLocalCredentialPassword({
      credentialId: 'builtin:DEVELOPER',
      oldPassword: '654321',
      newPassword: '765432'
    }),
    /客户端不允许修改/
  )

  await store.changeLocalCredentialPassword({
    credentialId: 'builtin:SUPER_ADMIN',
    oldPassword: '234567',
    newPassword: '345678'
  })
  await store.syncSystemCredentialsFromConfig({
    developerPassword: '765432',
    superAdminPassword: '654321',
    deviceCode: 'DEVICE-001',
    source: 'CONFIG_REFRESH'
  })
  assert.equal((await store.loginLocal('765432')).credentialId, 'builtin:DEVELOPER')
  assert.equal((await store.loginLocal('654321')).credentialId, 'builtin:SUPER_ADMIN')
  await assert.rejects(store.loginLocal('345678'), /密码错误/)

  const state = await store.loadInitialAdminState()
  assert.equal(state.hasPasswordHash, true)
  assert.equal(state.managedBy, 'BACKEND_CONFIG')
  assert.equal(state.developerUpdated, true)
  assert.equal(state.superAdminUpdated, true)
  assert.deepEqual(state.credentialIds, ['builtin:DEVELOPER', 'builtin:SUPER_ADMIN'])
  assert.equal(Object.hasOwn(state, 'developerPassword'), false)
  assert.equal(Object.hasOwn(state, 'superAdminPassword'), false)

  const superAdmin = (await store.listLocalCredentials())
    .find((credential) => credential.credentialId === 'builtin:SUPER_ADMIN')
  assert.equal(superAdmin.passwordState, 'ACTIVE')
  assert.equal(JSON.stringify(superAdmin).includes('654321'), false)

  const repeated = await store.syncSystemCredentialsFromConfig({
    developerPassword: '765432',
    superAdminPassword: '654321'
  })
  assert.equal(repeated.developerUpdated, false)
  assert.equal(repeated.superAdminUpdated, false)

  await store.saveLocalCredential({
    label: '自定义管理员',
    password: '112233',
    roleIds: ['SUPER_ADMIN']
  })

  await assert.rejects(
    store.syncSystemCredentialsFromConfig({ developerPassword: '112233', superAdminPassword: '998877' }),
    /已被其他用户使用/
  )
  await assert.rejects(
    store.syncSystemCredentialsFromConfig({}),
    /后台配置缺少开发人员密码/
  )
  await assert.rejects(
    store.syncSystemCredentialsFromConfig({ developerPassword: '123456' }),
    /后台配置缺少超级管理员密码/
  )
  await assert.rejects(
    store.syncSystemCredentialsFromConfig({ developerPassword: '123456', superAdminPassword: '123456' }),
    /不能相同/
  )
})

test('developer system role receives every defined permission while remaining protected', async (t) => {
  const store = await createSqliteStore(t)
  const session = await store.loginLocal('666666')

  assert.equal(session.roleIds.includes('DEVELOPER'), true)
  assert.equal(session.permissions.includes('*'), true)
  assert.equal(session.permissions.includes('account.role.update'), true)
  assert.equal(session.permissions.includes('system.employee.update'), true)
  assert.equal(session.permissions.includes('system.history.view'), true)
  await assert.rejects(
    store.saveLocalRole({ roleId: 'DEVELOPER', roleName: 'Changed Developer', permissionKeys: [] }),
    /系统内置角色只允许查看/
  )
})

test('resolves an active face binding to its employee without biometric data', async (t) => {
  const store = await createSqliteStore(t)
  const checkedAt = 2_000_000
  await store.upsertEmployees([{
    employeeId: 'employee-1',
    employeeName: 'Employee One',
    cardNo: 'CARD-001',
    status: 0,
    expiresAt: checkedAt + 10_000
  }], checkedAt)

  const saved = await store.saveLocalFaceBinding({
    faceId: '52',
    faceAiId: '10001_52',
    employeeId: 'employee-1',
    faceIndex: 0,
    expiresAt: checkedAt + 10_000,
    faceFeature: 'must-not-be-persisted',
    faceImageBase64: 'must-not-be-persisted'
  }, checkedAt)

  assert.equal(saved.saved, true)
  assert.equal('faceFeature' in saved.faceBinding, false)
  assert.equal('faceImageBase64' in saved.faceBinding, false)

  const resolved = await store.resolveEmployeeByFaceId('10001_52', checkedAt)
  assert.equal(resolved.ok, true)
  assert.equal(resolved.faceBinding.faceId, '52')
  assert.equal(resolved.faceBinding.faceAiId, '10001_52')
  assert.equal(resolved.employee.employeeId, 'employee-1')
  assert.equal(resolved.cardNo, 'CARD-001')
})

test('stores multiple server faces for one employee and resolves each FaceAI id', async (t) => {
  const store = await createSqliteStore(t)
  await store.upsertEmployees([{
    employeeId: '10001',
    employeeName: 'Multi Face Employee',
    status: 0
  }])
  await store.upsertFaceBindings([
    { faceId: '52', faceAiId: '10001_52', employeeId: '10001', status: '0' },
    { faceId: '55', faceAiId: '10001_55', employeeId: '10001', status: '0' }
  ])

  const bindings = await store.listFaceBindingsByEmployee('10001')
  assert.equal(bindings.length, 2)
  assert.deepEqual(new Set(bindings.map((item) => item.faceAiId)), new Set(['10001_52', '10001_55']))
  assert.equal((await store.resolveEmployeeByFaceId('10001_52')).employee.employeeId, '10001')
  assert.equal((await store.resolveEmployeeByFaceId('10001_55')).employee.employeeId, '10001')
})

test('returns explicit reasons for unusable face bindings', async (t) => {
  const store = await createSqliteStore(t)
  const checkedAt = 3_000_000

  assert.deepEqual(
    await store.resolveEmployeeByFaceId('', checkedAt),
    { ok: false, reason: 'INVALID_FACE_ID' }
  )
  assert.deepEqual(
    await store.resolveEmployeeByFaceId('missing', checkedAt),
    { ok: false, reason: 'FACE_BINDING_NOT_FOUND', faceId: 'missing' }
  )

  await store.upsertEmployees([{
    employeeId: 'employee-2',
    employeeName: 'Employee Two',
    status: 0,
    expiresAt: checkedAt + 10_000
  }], checkedAt)
  await store.upsertFaceBindings([{
    faceId: 'employee-2_0',
    employeeId: 'employee-2',
    status: 1,
    expiresAt: checkedAt + 10_000
  }], checkedAt)

  assert.equal(
    (await store.resolveEmployeeByFaceId('employee-2_0', checkedAt)).reason,
    'FACE_BINDING_DISABLED'
  )

  await store.upsertFaceBindings([{
    faceId: 'employee-2_0',
    employeeId: 'employee-2',
    status: 0,
    expiresAt: checkedAt - 1
  }], checkedAt)
  assert.equal(
    (await store.resolveEmployeeByFaceId('employee-2_0', checkedAt)).reason,
    'FACE_BINDING_EXPIRED'
  )

  await store.upsertEmployees([{
    employeeId: 'employee-expired',
    employeeName: 'Expired Employee',
    status: 0,
    expiresAt: checkedAt - 1
  }], checkedAt)
  await store.upsertFaceBindings([{
    faceId: 'employee-expired_0',
    employeeId: 'employee-expired',
    status: 0,
    expiresAt: checkedAt + 10_000
  }], checkedAt)
  assert.equal(
    (await store.resolveEmployeeByFaceId('employee-expired_0', checkedAt)).reason,
    'EMPLOYEE_EXPIRED'
  )

  await store.upsertEmployees([{
    employeeId: 'employee-disabled',
    employeeName: 'Disabled Employee',
    status: 1
  }], checkedAt)
  await store.upsertFaceBindings([{
    faceId: 'employee-disabled_0',
    employeeId: 'employee-disabled',
    status: 0
  }], checkedAt)
  assert.equal(
    (await store.resolveEmployeeByFaceId('employee-disabled_0', checkedAt)).reason,
    'EMPLOYEE_DISABLED'
  )
})

test('keeps card numbers as slot metadata without creating a card-to-slot lookup truth', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveSlotsSnapshot([
    { slotNumber: 1, status: 'OCCUPIED', cardNo: 'CARD-001' },
    { slotNumber: 2, status: 'CHARGING', cardNo: 'CARD-001' }
  ], 'BACKEND', true)

  assert.equal((await store.getSlotSnapshot(1)).cardId, 'CARD-001')
  assert.deepEqual(
    (await store.listFreshSlotsSnapshot()).map((slot) => [slot.slotNumber, slot.cardId]),
    [[1, 'CARD-001'], [2, 'CARD-001']]
  )
  assert.equal(Object.hasOwn(store, 'findSlotByCardNo'), false)
})

test('writes a received slot snapshot in one SQLite request', () => {
  const start = localStoreSource.indexOf('const saveSlotsSnapshot = async')
  const end = localStoreSource.indexOf('const loadSlotsSnapshot', start)
  const source = localStoreSource.slice(start, end)
  assert.match(source, /records\.map\(\(\) => '\(\?, \?, \?, \?, \?, \?, \?, \?\)'\)\.join\(', '\)/)
  assert.match(source, /INSERT OR REPLACE INTO slots_snapshot/)
  assert.doesNotMatch(source, /for \(const slot of Array\.isArray\(slots\)/)
})

test('loads one operation and lists only recoverable operations', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveOperationRecord({
    operationId: 'operation-pending',
    operationType: 'FACE_OPEN',
    state: 'PHYSICAL_PENDING',
    slotNumber: 8,
    employeeId: 'employee-1'
  })
  await store.saveOperationRecord({
    operationId: 'operation-complete',
    operationType: 'FACE_OPEN',
    state: 'COMPLETED',
    slotNumber: 9,
    employeeId: 'employee-2'
  })

  const pending = await store.getOperationRecord('operation-pending')
  assert.equal(pending.operationId, 'operation-pending')
  assert.equal(pending.state, 'PHYSICAL_PENDING')
  assert.equal(pending.slotNumber, 8)

  const recoverable = await store.listRecoverableOperations()
  assert.deepEqual(recoverable.map((operation) => operation.operationId), ['operation-pending'])
})

test('migrates schema 7 physical evidence into operation columns', async (t) => {
  const legacyOperation = {
    operationId: 'legacy-take-card',
    operationType: 'TAKE_CARD',
    employeeId: 'employee-legacy',
    cardNo: 'CARD-LEGACY',
    slotNumber: 5,
    state: 'COMPLETED',
    physicalConfirmedAt: 3_000,
    createdAt: 2_900,
    updatedAt: 3_100,
    finishedAt: 3_100
  }
  let legacyDatabase = null
  await createSqliteStore(t, ({ database }) => {
    legacyDatabase = database
    legacyDatabase.run(
      'CREATE TABLE operations (operation_id TEXT PRIMARY KEY, operation_type TEXT NOT NULL, employee_id TEXT, face_id TEXT, slot_number INTEGER, state TEXT NOT NULL, offline INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, finished_at INTEGER, raw_json TEXT NOT NULL)'
    )
    legacyDatabase.run(
      'INSERT INTO operations(operation_id, operation_type, employee_id, face_id, slot_number, state, offline, created_at, updated_at, finished_at, raw_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        legacyOperation.operationId,
        legacyOperation.operationType,
        legacyOperation.employeeId,
        '',
        legacyOperation.slotNumber,
        legacyOperation.state,
        0,
        legacyOperation.createdAt,
        legacyOperation.updatedAt,
        legacyOperation.finishedAt,
        JSON.stringify(legacyOperation)
      ]
    )
  })

  const migrated = legacyDatabase.exec(
    "SELECT card_no, physical_confirmed_at FROM operations WHERE operation_id = 'legacy-take-card'"
  )
  assert.deepEqual(migrated[0].values[0], ['CARD-LEGACY', 3_000])
})

test('keeps operation intent fields and maps administrator history details', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveOperationRecord({
    operationId: 'unlock-all-1',
    operationType: 'UNLOCK_ALL',
    operatorName: 'Local Admin',
    state: 'VALIDATED',
    requestedCount: 3,
    targetAddresses: [1, 2, 3],
    createdAt: 1_000,
    updatedAt: 1_000
  })
  await store.saveOperationRecord({
    operationId: 'unlock-all-1',
    operationType: 'UNLOCK_ALL',
    state: 'PARTIAL',
    successCount: 2,
    failedCount: 1,
    failures: [{ slotNumber: 3, code: 'SERIAL_TIMEOUT', message: 'timeout' }],
    updatedAt: 2_000
  })
  await store.saveOperationRecord({
    operationId: 'unlock-one-2',
    operationType: 'ADMIN_UNLOCK',
    operatorName: 'Local Admin',
    slotNumber: 19,
    state: 'SERIAL_SENT',
    createdAt: 3_000,
    updatedAt: 3_000
  })
  await store.saveOperationRecord({
    operationId: 'face-enrollment-3',
    operationType: 'FACE_ENROLLMENT',
    operatorName: 'Local Admin',
    employeeId: 'employee-3',
    state: 'COMPLETED',
    createdAt: 4_000,
    updatedAt: 4_000
  })
  await store.saveOperationRecord({
    operationId: 'admin-take-4',
    operationType: 'ADMIN_TAKE_CARD',
    operatorName: 'Local Admin',
    slotNumber: 8,
    state: 'COMPLETED',
    createdAt: 5_000,
    updatedAt: 5_000
  })

  const persistedBatch = await store.getOperationRecord('unlock-all-1')
  assert.equal(persistedBatch.createdAt, 1_000)
  assert.equal(persistedBatch.requestedCount, 3)
  assert.deepEqual(persistedBatch.targetAddresses, [1, 2, 3])
  assert.equal(persistedBatch.finishedAt, 2_000)

  const history = await store.listOperationHistory()
  const batch = history.find((item) => item.operationId === 'unlock-all-1')
  const single = history.find((item) => item.operationId === 'unlock-one-2')
  const faceEnrollment = history.find((item) => item.operationId === 'face-enrollment-3')
  const adminTake = history.find((item) => item.operationId === 'admin-take-4')
  assert.equal(batch.type, '一键弹卡')
  assert.equal(batch.targetLabel, '待弹卡槽（3个）')
  assert.equal(batch.result, '部分完成')
  assert.equal(batch.resultKind, 'warning')
  assert.equal(batch.successCount, 2)
  assert.equal(batch.failedCount, 1)
  assert.deepEqual(batch.failures, [{ slotNumber: 3, code: 'SERIAL_TIMEOUT', message: 'timeout' }])
  assert.equal(single.targetLabel, '19号卡门')
  assert.equal(single.result, '指令已发送')
  assert.equal(single.resultKind, 'pending')
  assert.equal(faceEnrollment.type, '人脸录入')
  assert.equal(faceEnrollment.targetLabel, '人脸信息')
  assert.equal(faceEnrollment.result, '成功')
  assert.equal(faceEnrollment.resultKind, 'success')
  assert.equal(adminTake.type, '管理员取卡')
  assert.equal(adminTake.targetLabel, '08号卡门')
})

test('does not save a local face binding for a missing or disabled employee', async (t) => {
  const store = await createSqliteStore(t)
  assert.deepEqual(
    await store.saveLocalFaceBinding({ faceId: 'missing_0', employeeId: 'missing' }),
    { saved: false, reason: 'EMPLOYEE_NOT_FOUND' }
  )

  await store.upsertEmployees([{
    employeeId: 'employee-disabled',
    employeeName: 'Disabled Employee',
    status: 1
  }])
  const result = await store.saveLocalFaceBinding({
    faceId: 'employee-disabled_0',
    employeeId: 'employee-disabled'
  })
  assert.equal(result.saved, false)
  assert.equal(result.reason, 'EMPLOYEE_DISABLED')
})

test('stores local face photos without scheduling server upload', async (t) => {
  const store = await createSqliteStore(t)
  await store.upsertEmployees([{
    employeeId: 'employee-photo',
    employeeName: 'Photo Employee',
    status: 0
  }])

  const photoBytes = Buffer.from('local-face-photo')
  const saved = await store.saveFacePhoto({
    faceId: 'employee-photo_0',
    employeeId: 'employee-photo',
    mimeType: 'image/jpeg',
    photoBase64: `data:image/jpeg;base64,${photoBytes.toString('base64')}`,
    source: 'LOCAL_ENROLLMENT',
    uploadState: 'SYNCED',
    fileHash: 'a'.repeat(64),
    serverPath: '/profile/face/employee-photo.jpg',
    faceFeature: 'must-not-be-in-metadata'
  })

  assert.equal(saved.saved, true)
  assert.equal(saved.photo.photoBase64, photoBytes.toString('base64'))
  assert.equal(saved.photo.byteSize, photoBytes.length)
  assert.equal(saved.photo.uploadState, 'SYNCED')
  assert.equal(saved.photo.fileHash, 'a'.repeat(64))
  assert.equal(saved.photo.serverPath, '/profile/face/employee-photo.jpg')
  assert.equal((await store.getFacePhotoByFileHash('a'.repeat(64))).faceId, 'employee-photo_0')
  assert.equal('faceFeature' in saved.photo, false)
  assert.equal((await store.listPendingFacePhotos()).length, 0)
  assert.equal((await store.listFaceBindingsByEmployee('employee-photo')).length, 0)
  assert.equal(await store.getFaceBindingById('employee-photo_0'), null)

  await store.upsertFaceBindings([{
    faceId: 'employee-photo_0',
    faceAiId: 'employee-photo_employee-photo_0',
    employeeId: 'employee-photo',
    status: 0
  }])
  assert.equal((await store.listFaceBindingsByEmployee('employee-photo')).length, 1)
  assert.equal((await store.getFacePhotoByFaceId('employee-photo_0')).photoBase64, photoBytes.toString('base64'))
})

test('rejects the same persisted photo hash for a second face record', async (t) => {
  const store = await createSqliteStore(t)
  await store.upsertEmployees([
    { employeeId: '10001', employeeName: 'Employee One', status: 0 },
    { employeeId: '10002', employeeName: 'Employee Two', status: 0 }
  ])
  const photoBase64 = Buffer.from('duplicate-photo').toString('base64')
  const fileHash = 'b'.repeat(64)
  assert.equal((await store.saveFacePhoto({
    faceId: '52',
    employeeId: '10001',
    photoBase64,
    fileHash
  })).saved, true)
  const duplicate = await store.saveFacePhoto({
    faceId: '88',
    employeeId: '10002',
    photoBase64,
    fileHash
  })
  assert.equal(duplicate.saved, false)
  assert.equal(duplicate.reason, 'FACE_PHOTO_DUPLICATE')
  assert.deepEqual(duplicate.duplicate, { faceId: '52', employeeId: '10001' })
})

test('migrates schema 8 face bindings to a backfilled FaceAI id', async (t) => {
  const store = await createSqliteStore(t, async ({ database }) => {
    database.run('CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)')
    database.run("INSERT INTO schema_meta(key, value, updated_at) VALUES('schemaVersion', '8', 1)")
    database.run('CREATE TABLE employees (employee_id TEXT PRIMARY KEY, employee_code TEXT, employee_name TEXT NOT NULL, department_name TEXT, enabled INTEGER NOT NULL DEFAULT 1, auth_state TEXT, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT NOT NULL)')
    database.run("INSERT INTO employees(employee_id, employee_name, enabled, updated_at, raw_json) VALUES('10001', 'Legacy Employee', 1, 1, '{\"employeeId\":\"10001\",\"employeeName\":\"Legacy Employee\",\"enabled\":true}')")
    database.run('CREATE TABLE face_bindings (face_id TEXT PRIMARY KEY, employee_id TEXT NOT NULL, face_index INTEGER, enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT)')
    database.run("INSERT INTO face_bindings(face_id, employee_id, enabled, updated_at, raw_json) VALUES('10001_0', '10001', 1, 1, '{\"faceId\":\"10001_0\",\"employeeId\":\"10001\",\"enabled\":true}')")
  })
  const binding = await store.getFaceBindingById('10001_0')
  assert.equal(binding.faceId, '10001_0')
  assert.equal(binding.faceAiId, '10001_0')
})

test('migrates face bindings, face photos and finger bindings into one face_bindings table', async (t) => {
  let database
  const store = await createSqliteStore(t, async (context) => {
    database = context.database
    database.run('CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)')
    database.run("INSERT INTO schema_meta(key, value, updated_at) VALUES('schemaVersion', '9', 1)")
    database.run('CREATE TABLE employees (employee_id TEXT PRIMARY KEY, employee_code TEXT, employee_name TEXT NOT NULL, department_name TEXT, enabled INTEGER NOT NULL DEFAULT 1, auth_state TEXT, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT NOT NULL)')
    database.run("INSERT INTO employees(employee_id, employee_name, enabled, updated_at, raw_json) VALUES('10001', 'Legacy Employee', 1, 1, '{}')")
    database.run('CREATE TABLE face_bindings (face_id TEXT PRIMARY KEY, face_ai_id TEXT, employee_id TEXT NOT NULL, face_index INTEGER, enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT)')
    database.run("INSERT INTO face_bindings(face_id, face_ai_id, employee_id, face_index, enabled, updated_at, raw_json) VALUES('52', '10001_52', '10001', 2, 1, 2, '{}')")
    database.run("CREATE TABLE face_photos (face_id TEXT PRIMARY KEY, employee_id TEXT NOT NULL, mime_type TEXT NOT NULL, byte_size INTEGER NOT NULL, photo_base64 TEXT NOT NULL, source TEXT NOT NULL, upload_state TEXT NOT NULL DEFAULT 'PENDING', upload_id TEXT, server_path TEXT, server_url TEXT, file_hash TEXT, last_error TEXT, updated_at INTEGER NOT NULL, synced_at INTEGER, raw_json TEXT)")
    database.run("INSERT INTO face_photos(face_id, employee_id, mime_type, byte_size, photo_base64, source, upload_state, file_hash, updated_at, raw_json) VALUES('52', '10001', 'image/jpeg', 3, 'YWJj', 'SERVER_SYNC', 'SYNCED', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 3, '{}')")
    database.run("INSERT INTO face_photos(face_id, employee_id, mime_type, byte_size, photo_base64, source, upload_state, file_hash, updated_at, raw_json) VALUES('orphan', '10001', 'image/jpeg', 3, 'ZGVm', 'LOCAL_ENROLLMENT', 'PENDING', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', 4, '{}')")
    database.run('CREATE TABLE finger_bindings (finger_id TEXT PRIMARY KEY, employee_id TEXT NOT NULL, finger_index INTEGER, enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, expires_at INTEGER, raw_json TEXT)')
    database.run("INSERT INTO finger_bindings(finger_id, employee_id, finger_index, enabled, updated_at, raw_json) VALUES('52', '10001', 1, 1, 4, '{}')")
  })

  assert.equal((await store.getFaceBindingById('10001_52')).faceId, '52')
  assert.equal((await store.getFacePhotoByFaceId('52')).photoBase64, 'YWJj')
  assert.equal(await store.getFaceBindingById('orphan'), null)
  await store.upsertFingerBindings([{ fingerId: '53', employeeId: '10001', fingerIndex: 2, status: 0 }])

  const records = database.exec('SELECT biometric_type, binding_id, employee_id, native_id, enabled FROM face_bindings ORDER BY biometric_type, binding_id')[0].values
  assert.deepEqual(records, [
    ['FACE', '52', '10001', '10001_52', 1],
    ['FACE', 'orphan', '10001', null, 0],
    ['FINGER', '52', '10001', null, 1],
    ['FINGER', '53', '10001', null, 1]
  ])
  const legacyTables = database.exec("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('face_photos', 'finger_bindings')")
  assert.equal(legacyTables.length, 0)
  assert.equal(database.exec("SELECT value FROM schema_meta WHERE key='schemaVersion'")[0].values[0][0], '11')
})

test('rejects invalid or oversized face photos before SQLite write', async (t) => {
  const store = await createSqliteStore(t)
  await store.upsertEmployees([{
    employeeId: 'employee-photo-limit',
    employeeName: 'Photo Limit Employee',
    status: 0
  }])

  const invalid = await store.saveFacePhoto({
    faceId: 'invalid-photo',
    employeeId: 'employee-photo-limit',
    photoBase64: 'not base64!'
  })
  assert.equal(invalid.saved, false)
  assert.equal(invalid.reason, 'INVALID_FACE_PHOTO_BASE64')

  const oversizedLength = Math.ceil((FACE_PHOTO_MAX_BYTES + 1) * 4 / 3 / 4) * 4
  const oversized = await store.saveFacePhoto({
    faceId: 'oversized-photo',
    employeeId: 'employee-photo-limit',
    photoBase64: 'A'.repeat(oversizedLength)
  })
  assert.equal(oversized.saved, false)
  assert.equal(oversized.reason, 'FACE_PHOTO_TOO_LARGE')
  assert.equal(await store.getFacePhotoByFaceId('oversized-photo'), null)
})

test('marks restarted slot snapshots as cached while preserving their update time', async (t) => {
  const store = await createSqliteStore(t)
  const updatedAt = 1_725_000_000_000

  await store.upsertSlotSnapshot({
    slotNumber: 7,
    status: 'OCCUPIED',
    cardId: 'CARD-007',
    updatedAt
  }, 'SERIAL', true)

  const snapshots = await store.loadSlotsSnapshot()
  assert.equal(snapshots.length, 1)
  assert.equal(snapshots[0].slotNumber, 7)
  assert.equal(snapshots[0].fresh, false)
  assert.equal(snapshots[0].updatedAt, updatedAt)
  assert.equal(snapshots[0].source, 'SERIAL')
})

test('inherits parent role permissions and rejects cyclic or dangling role inheritance', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveLocalRole({
    roleId: 'ROLE_PARENT',
    roleName: 'Parent Role',
    permissionKeys: ['system.employee.view']
  })
  await store.saveLocalRole({
    roleId: 'ROLE_CHILD',
    roleName: 'Child Role',
    parentRoleId: 'ROLE_PARENT',
    permissionKeys: ['system.history.view']
  })
  await store.saveLocalCredential({
    credentialId: 'credential:role-inheritance',
    label: 'Role Inheritance User',
    password: '314159',
    roleIds: ['ROLE_CHILD']
  })

  const session = await store.loginLocal('314159')
  assert.equal(session.roleIds.includes('ROLE_CHILD'), true)
  assert.equal(session.permissions.includes('system.employee.view'), true)
  assert.equal(session.permissions.includes('system.history.view'), true)

  await assert.rejects(
    store.saveLocalRole({
      roleId: 'ROLE_PARENT',
      roleName: 'Parent Role',
      parentRoleId: 'ROLE_CHILD',
      permissionKeys: ['system.employee.view']
    }),
    /循环/
  )
  await assert.rejects(store.deleteLocalRole('ROLE_PARENT'), /子角色继承/)
})

test('disabled roles stop granting permissions while bindings remain visible and can be restored', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveLocalRole({
    roleId: 'ROLE_DISABLED_PARENT',
    roleName: 'Disabled Parent',
    permissionKeys: ['system.employee.view']
  })
  await store.saveLocalRole({
    roleId: 'ROLE_DISABLED_CHILD',
    roleName: 'Disabled Child',
    parentRoleId: 'ROLE_DISABLED_PARENT',
    permissionKeys: ['system.history.view']
  })
  await store.saveLocalCredential({
    credentialId: 'credential:disabled-role',
    label: 'Disabled Role User',
    password: '271828',
    roleIds: ['ROLE_DISABLED_CHILD']
  })

  const initialSession = await store.loginLocal('271828')
  assert.equal(initialSession.permissions.includes('system.employee.view'), true)
  assert.equal(initialSession.permissions.includes('system.history.view'), true)

  await store.setLocalRoleEnabled('ROLE_DISABLED_PARENT', false)
  const childOnlySession = await store.loginLocal('271828')
  assert.equal(childOnlySession.permissions.includes('system.employee.view'), false)
  assert.equal(childOnlySession.permissions.includes('system.history.view'), true)

  await store.setLocalRoleEnabled('ROLE_DISABLED_CHILD', false)
  await assert.rejects(store.loginLocal('271828'), /未绑定启用角色/)
  const credentials = await store.listLocalCredentials()
  const listed = credentials.find((item) => item.credentialId === 'credential:disabled-role')
  assert.deepEqual(listed.roleIds, ['ROLE_DISABLED_CHILD'])
  assert.equal(listed.roles[0].enabled, false)

  await store.setLocalRoleEnabled('ROLE_DISABLED_CHILD', true)
  const restoredSession = await store.loginLocal('271828')
  assert.equal(restoredSession.permissions.includes('system.history.view'), true)
  assert.equal(restoredSession.permissions.includes('system.employee.view'), false)
})

test('locks builtin system roles against edit, disable and deletion', async (t) => {
  const store = await createSqliteStore(t)
  for (const roleId of ['SUPER_ADMIN', 'DEVELOPER']) {
    await assert.rejects(
      store.saveLocalRole({ roleId, roleName: `Changed ${roleId}`, permissionKeys: [] }),
      /系统内置角色只允许查看/
    )
    await assert.rejects(store.setLocalRoleEnabled(roleId, false), /系统内置角色不允许停用/)
    await assert.rejects(store.deleteLocalRole(roleId), /系统内置角色不可删除/)
  }
  const roles = await store.listLocalRoles()
  assert.equal(roles.find((role) => role.roleId === 'SUPER_ADMIN')?.enabled, true)
  assert.equal(roles.find((role) => role.roleId === 'DEVELOPER')?.enabled, true)
})

test('creates, edits, inherits and safely deletes custom permission points', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveLocalPermission({
    permissionKey: 'custom.report.*',
    permissionName: 'Custom Reports',
    parentKey: '*',
    category: 'custom',
    sortOrder: 80,
    description: 'Custom report permissions'
  })
  await store.saveLocalPermission({
    permissionKey: 'custom.report.view',
    permissionName: 'View Custom Reports',
    parentKey: 'custom.report.*',
    category: 'custom',
    sortOrder: 1,
    description: 'Read-only report access'
  })
  await store.saveLocalPermission({
    permissionKey: 'custom.report.view',
    permissionName: 'Browse Custom Reports',
    parentKey: 'custom.report.*',
    category: 'reporting',
    sortOrder: 2,
    description: 'Updated report access'
  })
  await store.saveLocalRole({
    roleId: 'ROLE_CUSTOM_REPORT',
    roleName: 'Custom Report Role',
    permissionKeys: ['custom.report.*']
  })
  await store.saveLocalCredential({
    credentialId: 'credential:custom-permission',
    label: 'Custom Permission User',
    password: '161803',
    roleIds: ['ROLE_CUSTOM_REPORT']
  })

  const permissions = await store.listLocalPermissions()
  const edited = permissions.find((item) => item.permissionKey === 'custom.report.view')
  assert.equal(edited.permissionName, 'Browse Custom Reports')
  assert.equal(edited.category, 'reporting')
  assert.equal(edited.sortOrder, 2)
  assert.equal(edited.description, 'Updated report access')

  const session = await store.loginLocal('161803')
  assert.equal(session.permissions.includes('custom.report.*'), true)
  assert.equal(session.permissions.includes('custom.report.view'), true)

  await assert.rejects(
    store.saveLocalPermission({
      permissionKey: 'custom.report.*',
      permissionName: 'Custom Reports',
      parentKey: 'custom.report.view',
      category: 'custom',
      sortOrder: 80
    }),
    /循环/
  )
  await assert.rejects(store.deleteLocalPermission('custom.report.*'), /子权限/)
  await store.deleteLocalPermission('custom.report.view')
  assert.equal((await store.listLocalPermissions()).some((item) => item.permissionKey === 'custom.report.view'), false)
})

test('rolls back permission changes that would remove the final account administration path', async (t) => {
  const store = await createSqliteStore(t)
  await assert.rejects(
    store.saveLocalPermission({
      permissionKey: 'account.role.*',
      permissionName: 'Manage Roles and Credentials',
      parentKey: '*',
      category: 'admin',
      enabled: false,
      sortOrder: 1
    }),
    /最后一条账号权限管理授权路径/
  )
  const permissions = await store.listLocalPermissions()
  assert.equal(permissions.find((item) => item.permissionKey === 'account.role.*')?.enabled, true)
})

test('keeps credential enable state when changing passwords and restores login only after enable', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveLocalRole({
    roleId: 'ROLE_CREDENTIAL_VIEWER',
    roleName: 'Credential Viewer',
    permissionKeys: ['system.employee.view']
  })
  await store.saveLocalCredential({
    credentialId: 'credential:disabled-user',
    label: 'Disabled User',
    password: '135790',
    roleIds: ['ROLE_CREDENTIAL_VIEWER'],
    enabled: false
  })
  await assert.rejects(store.loginLocal('135790'), /密码错误/)

  await store.saveLocalCredential({
    credentialId: 'credential:disabled-user',
    label: 'Disabled User',
    password: '246802',
    roleIds: ['ROLE_CREDENTIAL_VIEWER'],
    enabled: false
  })
  let listed = (await store.listLocalCredentials()).find((item) => item.credentialId === 'credential:disabled-user')
  assert.equal(listed.enabled, false)
  await assert.rejects(store.loginLocal('246802'), /密码错误/)

  await store.saveLocalCredential({
    credentialId: 'credential:disabled-user',
    label: 'Disabled User',
    roleIds: ['ROLE_CREDENTIAL_VIEWER'],
    enabled: true
  })
  listed = (await store.listLocalCredentials()).find((item) => item.credentialId === 'credential:disabled-user')
  assert.equal(listed.enabled, true)
  const session = await store.loginLocal('246802')
  assert.equal(session.permissions.includes('system.employee.view'), true)
})

test('keeps the protected system administrator path while allowing custom administrator reassignment', async (t) => {
  const store = await createSqliteStore(t)
  await store.saveLocalRole({
    roleId: 'ROLE_ONLY_ADMIN',
    roleName: 'Only Admin',
    permissionKeys: ['account.role.*']
  })
  await store.saveLocalRole({
    roleId: 'ROLE_NO_ADMIN',
    roleName: 'No Admin',
    permissionKeys: ['system.employee.view']
  })
  await store.saveLocalCredential({
    credentialId: 'credential:only-admin',
    label: 'Only Admin User',
    password: '112358',
    roleIds: ['ROLE_ONLY_ADMIN'],
    enabled: true
  })
  await assert.rejects(store.setLocalRoleEnabled('SUPER_ADMIN', false), /系统内置角色不允许停用/)
  await store.saveLocalCredential({
    credentialId: 'credential:only-admin',
    label: 'Only Admin User',
    roleIds: ['ROLE_NO_ADMIN'],
    enabled: true
  })
  const session = await store.loginLocal('112358')
  assert.equal(session.permissions.includes('account.role.*'), false)
  const systemAdminSession = await store.loginLocal('123456')
  assert.equal(systemAdminSession.permissions.includes('account.role.*'), true)
})
