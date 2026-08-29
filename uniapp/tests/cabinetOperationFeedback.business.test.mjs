import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

test('projects confirmed backend and administrator cabinet actions into both cabinet grids', async () => {
  const [grid, home, cardStatus, admin, appState] = await Promise.all([
    readFile(path.join(projectRoot, 'src/components/CabinetSlotGrid.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/pages/index/index.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/pages/card-status/card-status.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/pages/admin/admin.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/state/appState.js'), 'utf8')
  ])

  assert.match(grid, /operationEffects/)
  assert.match(grid, /props\.operationEffects\?\.\[slotNumber\]/)
  assert.match(home, /:operation-effects="appState\.cabinetOperationEffects"/)
  assert.match(cardStatus, /:operation-effects="appState\.cabinetOperationEffects"/)
  assert.match(cardStatus, /title: '管理员取卡完成'/)
  assert.match(cardStatus, /type: 'warning'/)
  assert.match(cardStatus, /title: '工卡已取出'/)
  assert.match(cardStatus, /title: unsupported \? '真机管理员取卡尚未接入' : '管理员取卡失败'/)
  assert.match(cardStatus, /卡槽状态未确认，请检查设备后重试/)
  assert.match(admin, /收到门已打开确认即计入完成/)
  assert.match(admin, /卡是否取走由后续卡槽状态反映/)
  assert.match(appState, /setCabinetOperationEffect/)
  assert.match(appState, /clearCabinetOperationEffect/)
})
