/**
 * sqlite.worker.js — Web Worker for sql.js (SQLite WASM)
 *
 * 在主线程外运行 SQLite，避免阻塞 UI。支持：
 * - 基础 query/execute
 * - 8 张业务表自动建表 (vue_local_config)
 * - IndexedDB 持久化（防止刷新丢数据）
 *
 * 仅 npm run dev:mock 时通过 worker-adapter.js 加载。
 */

let db = null
let SQL = null

// ── DB Schema ──
const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS vue_local_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_employees (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    department TEXT DEFAULT '',
    face_enrolled INTEGER DEFAULT 0,
    fingerprint_enrolled INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT 0,
    updated_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_slots (
    slot_number INTEGER PRIMARY KEY,
    status TEXT DEFAULT 'EMPTY',
    employee_id TEXT DEFAULT '',
    occupied_at INTEGER DEFAULT 0,
    data TEXT DEFAULT '{}'
  )`,
  `CREATE TABLE IF NOT EXISTS vue_face_features (
    face_id TEXT PRIMARY KEY,
    employee_id TEXT NOT NULL,
    feature TEXT NOT NULL,
    tag TEXT DEFAULT '',
    group_name TEXT DEFAULT 'default',
    created_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_fingerprints (
    finger_id TEXT PRIMARY KEY,
    employee_id TEXT NOT NULL,
    created_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_operations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type TEXT NOT NULL,
    employee_id TEXT DEFAULT '',
    slot_number INTEGER DEFAULT 0,
    data TEXT DEFAULT '{}',
    created_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id TEXT UNIQUE NOT NULL,
    cmd TEXT NOT NULL,
    data TEXT DEFAULT '{}',
    processed INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS vue_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER DEFAULT 0
  )`
]

// ── IndexedDB Persistence ──
const IDB_NAME = 'MockSqliteStore'
const IDB_VERSION = 1
const IDB_STORE = 'sqlite_dump'

async function saveToIndexedDB(buffer) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(IDB_NAME, IDB_VERSION)
    request.onsuccess = () => {
      const db = request.result
      const tx = db.transaction(IDB_STORE, 'readwrite')
      const store = tx.objectStore(IDB_STORE)
      store.put({ id: 1, data: buffer, timestamp: Date.now() })
      tx.oncomplete = () => { db.close(); resolve() }
      tx.onerror = (e) => reject(e)
    }
    request.onerror = (e) => reject(e)
    request.onupgradeneeded = (e) => {
      e.target.result.createObjectStore(IDB_STORE, { keyPath: 'id' })
    }
  })
}

async function loadFromIndexedDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(IDB_NAME, IDB_VERSION)
    request.onsuccess = () => {
      const db = request.result
      const tx = db.transaction(IDB_STORE, 'readonly')
      const store = tx.objectStore(IDB_STORE)
      const getReq = store.get(1)
      getReq.onsuccess = () => { db.close(); resolve(getReq.result ? getReq.result.data : null) }
      getReq.onerror = (e) => reject(e)
    }
    request.onerror = (e) => reject(e)
    request.onupgradeneeded = (e) => {
      e.target.result.createObjectStore(IDB_STORE, { keyPath: 'id' })
    }
  })
}

async function persistDb() {
  if (!db) return
  try {
    const buffer = db.export()
    await saveToIndexedDB(new Uint8Array(buffer))
  } catch (e) {
    // IndexedDB 可能不可用（隐私模式），静默失败
  }
}

// ── Init ──

async function initDb() {
  // Lazy-load sql.js
  const initSqlJs = (await import('sql.js')).default
  SQL = await initSqlJs()

  // 尝试从 IndexedDB 恢复
  let savedBuffer = null
  try {
    savedBuffer = await loadFromIndexedDB()
  } catch (e) {
    // 不可用，从头初始化
  }

  if (savedBuffer) {
    db = new SQL.Database(savedBuffer)
  } else {
    db = new SQL.Database()
    // 建表
    for (const ddl of SCHEMA) {
      db.run(ddl)
    }
    db.run(`INSERT OR IGNORE INTO vue_local_config (key, value, updated_at) VALUES ('schema_version', '1', ?)`, [Date.now()])
    await persistDb()
  }

  return true
}

// ── Query ──

function doQuery(sql, params = []) {
  if (!db) throw new Error('Database not initialized')
  try {
    const stmt = db.prepare(sql)
    if (params.length > 0) stmt.bind(params)
    const rows = []
    while (stmt.step()) {
      rows.push(stmt.getAsObject())
    }
    stmt.free()
    return { rows, count: rows.length }
  } catch (e) {
    throw new Error(`SQL query error: ${e.message}`)
  }
}

function doExecute(sql, params = []) {
  if (!db) throw new Error('Database not initialized')
  try {
    db.run(sql, params)
    // 每次写操作后自动持久化（简单策略，可后续优化为 debounce）
    setTimeout(() => persistDb(), 100)
    return { affectedRows: db.getRowsModified() }
  } catch (e) {
    throw new Error(`SQL execute error: ${e.message}`)
  }
}

// ── Message Handler ──

self.onmessage = async function (e) {
  const { id, type, sql, params } = e.data

  try {
    if (type === 'init') {
      await initDb()
      self.postMessage({ id, ok: true, result: true })
      return
    }

    if (type === 'query') {
      const result = doQuery(sql, params)
      self.postMessage({ id, ok: true, result })
      return
    }

    if (type === 'execute') {
      const result = doExecute(sql, params)
      self.postMessage({ id, ok: true, result })
      return
    }

    if (type === 'clear') {
      // 删除所有表数据（保留表结构）
      const tables = ['vue_local_config', 'vue_employees', 'vue_slots', 'vue_face_features', 'vue_fingerprints', 'vue_operations', 'vue_messages', 'vue_settings']
      for (const table of tables) {
        db.run(`DELETE FROM ${table}`)
      }
      await persistDb()
      self.postMessage({ id, ok: true, result: { affectedRows: 0 } })
      return
    }

    if (type === 'destroy') {
      if (db) {
        db.close()
        db = null
      }
      self.postMessage({ id, ok: true, result: true })
      return
    }

    self.postMessage({ id, ok: false, error: `Unknown type: ${type}` })
  } catch (err) {
    self.postMessage({ id, ok: false, error: err.message })
  }
}
