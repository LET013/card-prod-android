import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { toUserErrorMessage } from '../src/utils/userMessage.js'

const readSource = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('technical transport failures are converted to actionable user messages', () => {
  assert.equal(
    toUserErrorMessage(new Error('saveEmployeeResp timed out for msgId employee_update_5'), '人员保存失败'),
    '人员保存超时，请稍后重试'
  )
  assert.equal(
    toUserErrorMessage(new Error('SQLite unavailable'), '配置保存失败'),
    '配置保存失败'
  )
  assert.equal(
    toUserErrorMessage(new Error('当前账号无人员查看权限'), '人员读取失败'),
    '当前账号无人员查看权限'
  )
  assert.equal(
    toUserErrorMessage(new Error('设备未登录，请先发送 login 指令: deviceId=2A45688C'), '人员保存失败'),
    '设备正在重新连接，请稍后重试'
  )
})

test('employee and face pages do not expose internal storage or response wording', async () => {
  const [employeeSource, faceSource] = await Promise.all([
    readSource('../src/pages/employees/employees.vue'),
    readSource('../src/components/FaceRegisterPanel.vue')
  ])
  const source = `${employeeSource}\n${faceSource}`

  assert.doesNotMatch(source, /服务端确认后更新本机缓存|后端同步接口|已同步至后台|筛选本地员工|部门ID/)
  assert.doesNotMatch(employeeSource, />员工ID</)
  assert.match(employeeSource, /toUserErrorMessage\(error, '人员保存失败'\)/)
  assert.match(employeeSource, /人员已保存，请点击手动同步刷新列表/)
})

test('general pages avoid implementation-specific diagnostic wording', async () => {
  const [featureSource, settingsSource, splashSource] = await Promise.all([
    readSource('../src/pages/feature/feature.vue'),
    readSource('../src/components/DeviceConfigPanel.vue'),
    readSource('../src/pages/splash/splash.vue')
  ])

  assert.doesNotMatch(featureSource, /Android 原生日志采集能力|Logcat 采集通道/)
  assert.doesNotMatch(settingsSource, /写入本机缓存|SQLite不可用|原生通信参数/)
  assert.doesNotMatch(splashSource, /本机缓存加载失败|服务端未返回完整升级信息|未配置后端服务器地址/)
})

test('regular user flows filter raw internal exceptions before display', async () => {
  const [homeSource, cardStatusSource, passwordSource] = await Promise.all([
    readSource('../src/pages/index/index.vue'),
    readSource('../src/pages/card-status/card-status.vue'),
    readSource('../src/components/ChangePasswordPanel.vue')
  ])

  assert.match(homeSource, /recognition\.message = toUserErrorMessage/)
  assert.match(cardStatusSource, /message: toUserErrorMessage/)
  assert.match(passwordSource, /errorText\.value = toUserErrorMessage/)
})
