/**
 * worker-adapter.js — localStorage + 内存 Map 模拟 SQLite
 *
 * 替代 sql.js + Web Worker 方案，消除 WASM 加载 / Worker 通信 / 模块解析
 * 等复杂性问题。提供与原始 worker-adapter 完全兼容的 API。
 *
 * 仅 npm run dev:mock 时被 bridge.js 和 service.js 引用。
 *
 * Exports: query, execute, clearDatabase, destroy
 */

const LS_KEY = 'mock_bridge_db'
// 内存 Map: table → (key → { value, updated_at, ...row })
let store = null

function getTable(name) {
  if (!store) store = new Map()
  let tbl = store.get(name)
  if (!tbl) {
    tbl = new Map()
    store.set(name, tbl)
  }
  return tbl
}

function loadStore() {
  if (store) return store
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (raw) {
      const data = JSON.parse(raw)
      store = new Map()
      for (const [tblName, rows] of Object.entries(data)) {
        store.set(tblName, new Map(Object.entries(rows)))
      }
      return store
    }
  } catch (e) {
    // corrupted data, ignore
  }
  store = new Map()
  return store
}

function saveStore() {
  try {
    const obj = {}
    for (const [tblName, tbl] of store) {
      obj[tblName] = Object.fromEntries(tbl)
    }
    localStorage.setItem(LS_KEY, JSON.stringify(obj))
  } catch (e) {
    // localStorage full or unavailable
  }
}

function ensureInit() {
  loadStore()
}

// ── SQL Parser (minimal, handles only patterns used in localStore.js) ──

function extractTable(sql) {
  const m = sql.match(/(?:FROM|INTO)\s+(\w+)/i)
  return m ? m[1] : 'vue_local_config'
}

function splitCsv(value = '') {
  const result = []
  let current = ''
  let depth = 0
  let quote = ''
  for (const char of value) {
    if (quote) {
      current += char
      if (char === quote) quote = ''
      continue
    }
    if (char === '\'' || char === '"') {
      quote = char
      current += char
      continue
    }
    if (char === '(') depth += 1
    if (char === ')') depth -= 1
    if (char === ',' && depth === 0) {
      result.push(current.trim())
      current = ''
    } else {
      current += char
    }
  }
  if (current.trim()) result.push(current.trim())
  return result
}

function extractColumns(sql) {
  const match = sql.match(/INTO\s+\w+\s*\(([^)]*)\)/i)
  return match ? splitCsv(match[1]).map((col) => col.trim()) : []
}

function extractSelectedColumns(sql) {
  const match = sql.match(/SELECT\s+(.+?)\s+FROM/i)
  if (!match) return ['*']
  return splitCsv(match[1]).map((col) => col.trim())
}

function valueFromToken(token, params, paramIndex) {
  if (token === '?') return { value: params[paramIndex], nextIndex: paramIndex + 1 }
  const quoted = token.match(/^'([^']*)'$/)
  if (quoted) return { value: quoted[1], nextIndex: paramIndex }
  if (/^\d+$/.test(token)) return { value: Number(token), nextIndex: paramIndex }
  if (/^null$/i.test(token)) return { value: null, nextIndex: paramIndex }
  return { value: token, nextIndex: paramIndex }
}

function primaryKeyFor(row) {
  if (row.role_id && row.permission_key) return `${row.role_id}:${row.permission_key}`
  if (row.credential_id && row.role_id) return `${row.credential_id}:${row.role_id}`
  return String(
    row.key ??
    row.role_id ??
    row.permission_key ??
    row.credential_id ??
    row.password_id ??
    row.item_code ??
    row.session_key ??
    row.employee_id ??
    row.face_id ??
    row.finger_id ??
    row.slot_number ??
    row.operation_id ??
    row.event_id ??
    row.scope ??
    row._key ??
    ''
  )
}

function rowMatchesWhere(row, sql, params = []) {
  const where = sql.match(/WHERE\s+(.+?)(?:\s+ORDER\s+BY|\s+LIMIT|$)/i)?.[1]
  if (!where) return true
  let paramIndex = 0
  return where.split(/\s+AND\s+/i).every((part) => {
    const match = part.trim().match(/^(\w+)\s*(=|>|<)\s*(\?|'.*?'|\d+)$/)
    if (!match) return true
    const [, column, op, token] = match
    const parsed = valueFromToken(token, params, paramIndex)
    paramIndex = parsed.nextIndex
    const left = row[column]
    const right = parsed.value
    if (op === '=') return String(left ?? '') === String(right ?? '')
    if (op === '>') return Number(left || 0) > Number(right || 0)
    if (op === '<') return Number(left || 0) < Number(right || 0)
    return true
  })
}

function projectRow(row, columns) {
  if (columns.length === 1 && columns[0] === '*') return { ...row }
  const projected = {}
  for (const column of columns) {
    const alias = column.match(/^(\w+)(?:\s+AS\s+(\w+))?$/i)
    if (alias) {
      projected[alias[2] || alias[1]] = row[alias[1]]
    }
  }
  return projected
}

/**
 * SELECT value FROM <table> WHERE key='xxx' / key=?
 * SELECT raw_json FROM <table> ORDER BY ...
 */
function parseSelect(sql, params = []) {
  const tbl = extractTable(sql).toLowerCase()
  const tblMap = getTable(tbl)
  const columns = extractSelectedColumns(sql)
  let rows = Array.from(tblMap.values()).filter((row) => rowMatchesWhere(row, sql, params))
  if (/ORDER\s+BY/i.test(sql)) {
    const orderColumn = sql.match(/ORDER\s+BY\s+(\w+)/i)?.[1]
    if (orderColumn) rows = rows.sort((a, b) => String(a[orderColumn] ?? '').localeCompare(String(b[orderColumn] ?? '')))
  }
  const limit = Number(sql.match(/LIMIT\s+(\d+)/i)?.[1] || 0)
  if (limit > 0) rows = rows.slice(0, limit)
  const projected = rows.map((row) => projectRow(row, columns))
  return { rows: projected, count: projected.length }
}

/**
 * INSERT OR REPLACE INTO <table> (...cols...) VALUES (?, ?, ?...) / ('key', ?, ?)
 * Stores primary key as Map key, full params/values as Map value.
 */
function parseInsert(sql, params = []) {
  const tbl = extractTable(sql).toLowerCase()
  const tblMap = getTable(tbl)
  const columns = extractColumns(sql)
  const valuesMatch = sql.match(/VALUES\s*\(([\s\S]*)\)\s*$/i)
  const tokens = valuesMatch ? splitCsv(valuesMatch[1]) : columns.map(() => '?')
  const row = {}
  let paramIndex = 0
  columns.forEach((column, index) => {
    const parsed = valueFromToken(tokens[index] || '?', params, paramIndex)
    row[column] = parsed.value
    paramIndex = parsed.nextIndex
  })
  const key = primaryKeyFor(row) || String(params[0] || tblMap.size + 1)
  if (/INSERT\s+OR\s+IGNORE/i.test(sql) && tblMap.has(key)) {
    return { affectedRows: 0 }
  }
  tblMap.set(key, row)
  saveStore()
  return { affectedRows: 1 }
}

function parseUpdate(sql, params = []) {
  const table = sql.match(/UPDATE\s+(\w+)/i)?.[1]?.toLowerCase()
  const tblMap = getTable(table)
  const setPart = sql.match(/SET\s+(.+?)\s+WHERE/i)?.[1] || ''
  const assignments = splitCsv(setPart)
  let updated = 0
  for (const [key, row] of tblMap) {
    if (!rowMatchesWhere(row, sql, params.slice(assignments.length))) continue
    const next = { ...row }
    assignments.forEach((assignment, index) => {
      const [column] = assignment.split('=').map((item) => item.trim())
      next[column] = params[index]
    })
    tblMap.set(key, next)
    updated += 1
  }
  if (updated > 0) saveStore()
  return { affectedRows: updated }
}

/**
 * DELETE FROM <table> [WHERE ...]
 */
function parseDelete(sql, params = []) {
  const tbl = extractTable(sql).toLowerCase()
  const tblMap = getTable(tbl)

  // DELETE FROM <table> WHERE slot_number > ?
  const whereMatch = sql.match(/WHERE\s+(\w+)\s*(>|<|=)\s*\?/i)
  if (whereMatch) {
    let deleted = 0
    for (const [key, row] of tblMap) {
      if (rowMatchesWhere(row, sql, params)) {
        tblMap.delete(key)
        deleted++
      }
    }
    if (deleted > 0) saveStore()
    return { affectedRows: deleted }
  }
  // DELETE FROM <table> (no WHERE) — truncate
  const count = tblMap.size
  tblMap.clear()
  saveStore()
  return { affectedRows: count }
}

// ── DDL (no-op in localStorage) ──

function parseCreateTable(_sql) {
  return { affectedRows: 0 }
}

function parseCreateIndex(_sql) {
  return { affectedRows: 0 }
}

// ── Public API ──

/**
 * 查询（SELECT）
 */
export async function query(sql, params = []) {
  ensureInit()
  const upper = sql.trim().toUpperCase()
  if (upper.startsWith('SELECT')) {
    return parseSelect(sql, params)
  }
  throw new Error(`Unsupported SQL: ${sql.substring(0, 80)}`)
}

/**
 * 执行（INSERT/UPDATE/DELETE/DDL）
 */
export async function execute(sql, params = []) {
  ensureInit()
  const upper = sql.trim().toUpperCase()
  if (upper.startsWith('CREATE TABLE') || upper.startsWith('CREATE TABLE IF NOT EXISTS')) {
    return parseCreateTable(sql)
  }
  if (upper.startsWith('CREATE INDEX') || upper.startsWith('CREATE INDEX IF NOT EXISTS')) {
    return parseCreateIndex(sql)
  }
  if (upper.startsWith('INSERT OR REPLACE') || upper.startsWith('INSERT')) {
    return parseInsert(sql, params)
  }
  if (upper.startsWith('UPDATE')) {
    return parseUpdate(sql, params)
  }
  if (upper.startsWith('DELETE')) {
    return parseDelete(sql, params)
  }
  throw new Error(`Unsupported SQL: ${sql.substring(0, 80)}`)
}

/**
 * 清空所有业务表数据
 */
export async function clearDatabase() {
  ensureInit()
  let count = 0
  for (const tbl of store.values()) {
    count += tbl.size
    tbl.clear()
  }
  store.clear()
  saveStore()
  return { affectedRows: count }
}

/**
 * 销毁（清理内存、保持 localStorage 数据）
 */
export async function destroy() {
  store = null
}
