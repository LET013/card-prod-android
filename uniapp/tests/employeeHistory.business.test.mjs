import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const readSource = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('employee management keeps both successful and failed person operations in history', async () => {
  const source = await readSource('../src/services/index.js')
  const saveStart = source.indexOf('async function saveEmployee')
  const saveEnd = source.indexOf('async function deleteEmployee', saveStart)
  const saveSource = source.slice(saveStart, saveEnd)

  assert.match(saveSource, /await recordEmployeeHistory\(operationType, result\.employee \|\|/)
  assert.match(saveSource, /await recordEmployeeHistory\(operationType, input, \{ state: 'FAILED', error \}\)/)
  assert.match(saveSource, /enable: 'EMPLOYEE_ENABLE'/)
  assert.match(saveSource, /disable: 'EMPLOYEE_DISABLE'/)
})

test('history management exposes person add update disable and enable types', async () => {
  const [historySource, storeSource] = await Promise.all([
    readSource('../src/pages/feature/feature.vue'),
    readSource('../src/services/localStore.js')
  ])

  for (const [type, label] of Object.entries({
    EMPLOYEE_ADD: '新增人员',
    EMPLOYEE_UPDATE: '修改人员',
    EMPLOYEE_DISABLE: '停用人员',
    EMPLOYEE_ENABLE: '启用人员'
  })) {
    assert.match(historySource, new RegExp(`${type}:'${label}'`))
    assert.match(storeSource, new RegExp(`${type}: '${label}'`))
  }
})
