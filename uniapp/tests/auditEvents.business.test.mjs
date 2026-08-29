import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import initSqlJs from 'sql.js'

const sqlJsDist = fileURLToPath(new URL('../node_modules/sql.js/dist/', import.meta.url))
const localStoreSource = await readFile(new URL('../src/services/localStore.js', import.meta.url), 'utf8')
const localStoreModuleUrl = `data:text/javascript;base64,${Buffer.from(localStoreSource).toString('base64')}`
const { createLocalStore } = await import(localStoreModuleUrl)

async function createStoreContext(t, beforeInitialize = null) {
  const SQL = await initSqlJs({ locateFile: (file) => `${sqlJsDist}${file}` })
  const database = new SQL.Database()
  database.run('PRAGMA foreign_keys = ON')
  t.after(() => database.close())

  if (typeof beforeInitialize === 'function') {
    await beforeInitialize({ database })
  }

  const store = createLocalStore({
    database,
    query: (sql, params) => {
      const stmt = database.prepare(sql)
      if (params) stmt.bind(params)
      const rows = []
      while (stmt.step()) { rows.push(stmt.getAsObject()) }
      stmt.free()
      return rows
    },
    execute: (sql, params) => {
      database.run(sql, params || [])
    },
    now: () => Date.now()
  })

  await store.initializeSchema()
  return { store, database }
}

test('audit_events table exists after schema initialization', async (t) => {
  const { database } = await createStoreContext(t)
  const tables = database.exec("SELECT name FROM sqlite_master WHERE type='table' AND name='audit_events'")
  assert.equal(tables.length, 1)
})

test('insertAuditEvent returns eventId and occurredAt', async (t) => {
  const { store } = await createStoreContext(t)
  const result = await store.insertAuditEvent({
    actor_credential_id: 'admin',
    actor_label: '管理员',
    event_type: 'LOGIN',
    occurred_at: Date.now(),
    session_ref: 'admin_1000',
    role_ids_json: JSON.stringify(['admin']),
    source: 'LOCAL_UI'
  })
  assert.ok(result.eventId)
  assert.ok(result.occurredAt)
  assert.ok(result.eventId.startsWith('audit_'))
})

test('insertAuditEvent stores BUTTON_CLICK data', async (t) => {
  const { store, database } = await createStoreContext(t)
  await store.insertAuditEvent({
    actor_credential_id: 'op1',
    actor_label: '操作员',
    event_type: 'BUTTON_CLICK',
    action_code: 'ADMIN_EXIT',
    action_label: '退出管理',
    feature_code: 'ADMIN_DASHBOARD',
    occurred_at: Date.now(),
    source: 'LOCAL_UI'
  })
  const rows = database.exec('SELECT action_code, action_label, feature_code FROM audit_events')
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0].values[0], ['ADMIN_EXIT', '退出管理', 'ADMIN_DASHBOARD'])
})

test('insertAuditEvent stores FEATURE_ENTER data', async (t) => {
  const { store, database } = await createStoreContext(t)
  await store.insertAuditEvent({
    actor_credential_id: 'admin',
    actor_label: '管理员',
    event_type: 'FEATURE_ENTER',
    feature_code: 'DEVICE_CONFIG',
    feature_label: '设备配置管理',
    occurred_at: Date.now(),
    source: 'LOCAL_UI'
  })
  const rows = database.exec('SELECT feature_code FROM audit_events')
  assert.equal(rows[0].values[0][0], 'DEVICE_CONFIG')
})

test('cleanupAuditEvents removes records older than 30 days', async (t) => {
  const { store, database } = await createStoreContext(t)

  const now = Date.now()
  const oldTime = now - 31 * 24 * 60 * 60 * 1000
  const recentTime = now - 5 * 24 * 60 * 60 * 1000

  // insertAuditEvent triggers cleanup internally, so old record will be cleaned immediately
  await store.insertAuditEvent({
    actor_credential_id: 'old',
    actor_label: '旧用户',
    event_type: 'LOGIN',
    occurred_at: oldTime,
    source: 'LOCAL_UI'
  })
  // Only this recent record should survive the auto-cleanup triggered by insert
  await store.insertAuditEvent({
    actor_credential_id: 'recent',
    actor_label: '新用户',
    event_type: 'LOGIN',
    occurred_at: recentTime,
    source: 'LOCAL_UI'
  })

  const count = database.exec('SELECT COUNT(*) as c FROM audit_events')
  assert.equal(count[0].values[0][0], 1, 'Only the recent record should survive 30-day cleanup')
})

test('insertAuditEvent triggers cleanup automatically', async (t) => {
  const { store, database } = await createStoreContext(t)

  const now = Date.now()
  const oldTime = now - 31 * 24 * 60 * 60 * 1000

  await store.insertAuditEvent({
    actor_credential_id: 'old1',
    event_type: 'LOGIN',
    occurred_at: oldTime,
    source: 'LOCAL_UI'
  })

  // This insert should trigger cleanup of the old record
  await store.insertAuditEvent({
    actor_credential_id: 'new1',
    event_type: 'LOGIN',
    occurred_at: now,
    source: 'LOCAL_UI'
  })

  const count = database.exec('SELECT COUNT(*) as c FROM audit_events')
  assert.equal(count[0].values[0][0], 1)
})

test('audit_events indices exist for occurred_at and actor+time', async (t) => {
  const { database } = await createStoreContext(t)

  const countOccurred = database.exec(
    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_audit_events_occurred'"
  )
  assert.equal(countOccurred.length, 1, 'idx_audit_events_occurred should exist')

  const countActor = database.exec(
    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_audit_events_actor_time'"
  )
  assert.equal(countActor.length, 1, 'idx_audit_events_actor_time should exist')
})
