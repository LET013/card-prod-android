import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const readSource = (path) => fs.readFileSync(new URL(path, import.meta.url), 'utf8')
const homeSource = readSource('../src/pages/index/index.vue')
const adminSource = readSource('../src/pages/admin/admin.vue')
const systemSource = readSource('../src/pages/system/system.vue')
const employeesSource = readSource('../src/pages/employees/employees.vue')
const configSource = readSource('../src/components/DeviceConfigPanel.vue')
const pageManifestSource = readSource('../src/pages.json')
const mainSource = readSource('../src/main.js')
const serviceSource = readSource('../src/services/index.js')
const defaultConfigSource = readSource('../src/mock/data.js')
const acceptanceSource = readSource('../AGENTS.md')

test('customer-abandoned employee fingerprint stays disabled and hidden from every entry', () => {
  assert.match(defaultConfigSource, /fingerEnabled:\s*'0'/)
  assert.match(configSource, /fingerEnabled:\s*'0'/)
  assert.doesNotMatch(homeSource, /员工指纹|showEmployeeFingerprintBlocker|method-option finger/)
  assert.doesNotMatch(adminSource, /员工指纹|FingerprintRegisterPanel|modalKey:\s*'finger'/)
  assert.doesNotMatch(systemSource, /员工指纹|biometric\/fingerprint/)
  assert.doesNotMatch(employeesSource, />指纹<|>指纹状态</)
  assert.doesNotMatch(configSource, />指纹识别阈值<|>指纹识别</)
  assert.doesNotMatch(pageManifestSource, /pages\/biometric\/fingerprint/)
  assert.doesNotMatch(mainSource, /pages\/biometric\/fingerprint/)
  assert.doesNotMatch(serviceSource, /selfCheckDetail\('员工指纹模块'/)
  assert.match(acceptanceSource, /当前版本暂时放弃员工指纹功能/)
})

test('single available camera authentication starts face recognition without a method picker', () => {
  assert.match(homeSource, /@click="startTakeCard"/)
  assert.match(homeSource, /const startTakeCard = \(\) => startRecognition\('FACE'\)/)
  assert.doesNotMatch(homeSource, /选择验证方式|methodVisible|method-option/)
})
