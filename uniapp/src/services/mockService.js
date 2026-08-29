/**
 * Mock 服务 — JsBridgeV2 六大通道的浏览器端模拟实现。
 *
 * 在非 Release 模式下使用，模拟 Java 层的响应行为，
 * 确保 Vue 组件在浏览器开发环境中可正常运行。
 */

import { appState, replaceDeviceInfoProjection, replaceHistoryProjection } from '@/state/appState.js'

const TAG = 'MockService'

// ── Mock 人脸照片（1x1 像素 JPEG） ──
// registerBiometric → saveFacePhoto → deliverFacePhotoToServer 链路需要 faceImageBase64
const MOCK_FACE_IMAGE_BASE64 = 'data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY4kKQ0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYI4SUpTMzNDgrJDRFNhclRUJDVUVP/2gAMAwEAAARADH/xAApEAEAAQMEAgEDBQAAAAAAAAABEQAhQTFRYXGBkRChscHw/9oACAEBAAE/EPJKExChL2o2g5d6Us5Yy+4q0U8e6ICYWKc42ry90vmp8P4qUGV3HrbSlWjx7EfNKMXCCg3sNE4jo97OKQJGSRPBGW+NYQafJh39dFDgTLIY4GDr81H8PP7eaO91GdkTQUjR3snt7qG+bn2/zRS6YJgDq8SPKdB/9k='

// ── 模拟延迟 ──

function delay(ms = 300) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ── 事件监听 ──

const mockEventListeners = {}
const mockHistory = []
const mockSerialLogs = []
const mockEmployees = [
  {
    id: '1',
    employeeId: '1',
    employeeCode: 'EMP001',
    employeeName: '张三',
    departmentName: '技术部',
    faceRegistered: true,
    fingerprintRegistered: false,
    enabled: true,
    avatarUrl: ''
  },
  {
    id: '2',
    employeeId: '2',
    employeeCode: 'EMP002',
    employeeName: '李四',
    departmentName: '运维部',
    faceRegistered: false,
    fingerprintRegistered: true,
    enabled: true,
    avatarUrl: ''
  }
]

function mockEmit(eventName, data) {
  const listeners = mockEventListeners[eventName] || []
  const wildcard = mockEventListeners['*'] || []
  ;[...listeners, ...wildcard].forEach(cb => {
    try { cb(data, eventName) } catch (e) { console.error(TAG, 'event handler error:', e) }
  })
}

function formatDateTime(value = Date.now()) {
  const date = new Date(value)
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

function assertMockPermission(permissionKey) {
  if (!appState.session?.permissions?.has(permissionKey)) {
    throw new Error(`权限不足：${permissionKey}`)
  }
}

function pushMockHistory({ type, employeeName, slotNumber, result }) {
  const createdAt = Date.now()
  const item = {
    id: `MOCK_HISTORY_${createdAt}_${mockHistory.length + 1}`,
    type,
    employeeName: employeeName || '本机管理员',
    slotNumber,
    result,
    createdAt: formatDateTime(createdAt)
  }
  mockHistory.unshift(item)
  return item
}

// ── 创建 Mock 服务 ──

export function createMockService() {
  console.log(TAG, 'initialized')
  let mockBusinessUnsubscribe = null

  return {
    name: 'Mock',
    destroy,
    // 启动流程
    bootstrap,
    bootstrapActivate,
    bootstrapRetry,
    bootstrapRefreshCode,
    bootstrapCancel,
    // HTTP
    httpGet,
    httpPost,
    httpDownload,
    httpGetAsync,
    httpPostAsync,
    // MQTT
    mqttSend,
    mqttLoginStatus,
    mqttRegisterCmd,
    registerMqttBusinessHandlers,
    flushPendingMqttResponses,
    // 串口
    serialSend,
    serialGetLogs,
    serialSubscribe,
    serialUnsubscribe,
    getSerialStatus,
    // SQLite
    storageQuery,
    storageExecute,
    buildStatusReportPayload,
    reportDeviceStatus,
    scheduleStatusReport,
    // 人脸
    faceRecognitionStart,
    faceRecognitionCancel,
    faceEnrollmentStart,
    faceEnrollmentCancel,
    faceCount,
    runRecognition,
    registerBiometric,
    searchEmployees,
    syncEmployees,
    unlockDoor,
    getHistory,
    getRuntime,
    // 事件
    on,
    off
  }

  // ── 启动流程 ──

  async function bootstrap(config) {
    await delay(500)
    // 模拟引导流程事件
    setTimeout(() => mockEmit('bootstrap.progress', { phase: 'REGISTERING', message: '正在注册...' }), 100)
    setTimeout(() => mockEmit('bootstrap.progress', { phase: 'GETTING_CONFIG', message: '获取配置...' }), 500)
    setTimeout(() => mockEmit('bootstrap.config', {
      communicationMode: 'MQTT',
      rawConfig: { deviceId: 'MOCK_DEVICE' }
    }), 600)
    setTimeout(() => mockEmit('bootstrap.progress', { phase: 'LOGGED_IN', message: '登录成功' }), 800)
    setTimeout(() => mockEmit('bootstrap.success', { phase: 'RUNNING' }), 1000)
    return { accepted: true }
  }

  async function bootstrapActivate(code) {
    await delay(200)
    return { accepted: true }
  }

  async function bootstrapRetry() {
    await delay(200)
    return { accepted: true }
  }

  async function bootstrapRefreshCode() {
    await delay(200)
    return { accepted: true }
  }

  async function bootstrapCancel() {
    await delay(100)
    return { cancelled: true }
  }

  // ── HTTP ──

  async function httpGet(path) {
    await delay(200)
    return { status: 200, body: { mock: true, path } }
  }

  async function httpPost(path, body) {
    await delay(250)
    if (path === '/api/v1/employee/sync') {
      return {
        status: 200,
        body: {
          code: 0,
          msg: 'success',
          syncVersion: Date.now(),
          employees: mockEmployees.map((item) => ({
            employeeId: item.employeeId,
            employeeCode: item.employeeCode,
            employeeName: item.employeeName,
            department: item.departmentName,
            status: '0',
            faceRegistered: item.faceRegistered ? '1' : '0',
            fingerRegistered: item.fingerprintRegistered ? '1' : '0'
          })),
          deletedEmployeeIds: [],
          total: mockEmployees.length,
          page: body?.page || 1,
          pageSize: body?.pageSize || 50,
          hasMore: false
        }
      }
    }
    if (path === '/api/v1/employee/face/sync') {
      return {
        status: 200,
        body: {
          code: 0,
          msg: 'success',
          syncVersion: Date.now(),
          faceFeatures: [{ faceId: '1_0', employeeId: '1', featureVersion: 'mock', status: '0' }],
          total: 1,
          page: body?.page || 1,
          pageSize: body?.pageSize || 10,
          hasMore: false
        }
      }
    }
    if (path === '/api/v1/employee/finger/sync') {
      return {
        status: 200,
        body: {
          code: 0,
          msg: 'success',
          syncVersion: Date.now(),
          fingerFeatures: [{ fingerId: '2_1', employeeId: '2', fingerIndex: 1, featureVersion: 'mock', status: '0' }],
          total: 1,
          page: body?.page || 1,
          pageSize: body?.pageSize || 20,
          hasMore: false
        }
      }
    }
    return { status: 200, body: { mock: true, path, received: body } }
  }

  async function httpDownload(path, targetDir) {
    await delay(500)
    return { status: 200, filePath: `/mock/downloads/${path}`, size: 1024 }
  }

  async function httpGetAsync(path, requestId) {
    await delay(100)
    const id = requestId || `async_${Date.now()}`
    setTimeout(() => {
      mockEmit(`http.result.${id}`, { status: 200, body: { mock: true, path }, requestId: id })
    }, 300)
    return { accepted: true, requestId: id }
  }

  async function httpPostAsync(path, body, requestId) {
    await delay(100)
    const id = requestId || `async_${Date.now()}`
    setTimeout(() => {
      mockEmit(`http.result.${id}`, { status: 200, body: { mock: true, path }, requestId: id })
    }, 350)
    return { accepted: true, requestId: id }
  }

  // ── MQTT ──

  async function mqttSend(cmd, data, options = {}) {
    await delay(100)
    return { sent: true, msgId: options?.msgId || `mock_${Date.now()}` }
  }

  async function mqttLoginStatus() {
    await delay(100)
    return { connected: true }
  }

  async function getRuntime() {
    await delay(100)
    const deviceInfo = {
      deviceCode: appState.settings.deviceCode || appState.settings.deviceId || 'MOCK_DEVICE',
      activated: true,
      mqttConnected: true
    }
    replaceDeviceInfoProjection(deviceInfo)
    appState.runtime.socket = { state: 'CONNECTED', message: '后端通信已连接' }
    appState.runtime.deviceAuthorization = {
      state: 'AUTHORIZED',
      message: '授权有效（模拟）',
      authorized: true,
      authorizedUntil: 0,
      daysRemaining: 0,
      features: []
    }
    return {
      mqttConnected: true,
      deviceInfo,
      deviceAuthorization: appState.runtime.deviceAuthorization,
      timestamp: Date.now()
    }
  }

  async function mqttRegisterCmd(cmd) {
    await delay(50)
    return { registered: true, cmd }
  }

  async function registerMqttBusinessHandlers() {
    await delay(50)
    if (!mockBusinessUnsubscribe) {
      mockBusinessUnsubscribe = on('mqtt.message', handleMockMqttBusinessMessage)
    }
    return {
      registered: true,
      commands: ['syncUser', 'remoteOpen', 'syncEmployeeDataResp', 'syncFaceDataResp', 'syncFingerDataResp']
    }
  }

  async function flushPendingMqttResponses(reason = 'manual') {
    await delay(50)
    return { flushed: 0, failed: 0, reason }
  }

  // ── 串口 ──

  async function serialSend(hex) {
    await delay(100)
    const log = {
      timestamp: Date.now(),
      direction: 'TX',
      hex: String(hex || ''),
      message: 'mock 串口发送成功'
    }
    mockSerialLogs.unshift(log)
    return { sent: true, bytes: log.hex.length, hex: log.hex }
  }

  async function serialGetLogs(count) {
    await delay(100)
    return mockSerialLogs.slice(0, Math.max(1, Number(count || 100)))
  }

  async function serialSubscribe(cmd) {
    await delay(50)
    return { subscribed: true, cmd }
  }

  async function serialUnsubscribe(cmd) {
    await delay(50)
    return { unsubscribed: true, cmd }
  }

  async function getSerialStatus() {
    await delay(50)
    return {
      state: 'CONNECTED',
      connected: true,
      message: '模拟串口已连接',
      lastLog: mockSerialLogs[0] || null
    }
  }

  // ── SQLite ──

  async function storageQuery(sql, params) {
    await delay(50)
    return { rows: [], count: 0 }
  }

  async function storageExecute(sql, params) {
    await delay(50)
    return { affectedRows: 1 }
  }

  async function buildStatusReportPayload() {
    await delay(50)
    return {
      slots: [{
        slotId: 1,
        status: 'OCCUPIED',
        cardNo: 'CARD100001',
        voltage: 4.05,
        current: 0,
        faultCode: 0
      }]
    }
  }

  async function reportDeviceStatus() {
    const payload = await buildStatusReportPayload()
    return { sent: true, transport: 'MQTT', payload }
  }

  function scheduleStatusReport(reason = 'slot.status') {
    return { scheduled: true, reason, dueIn: 0 }
  }

  // ── 人脸 ──

  async function faceRecognitionStart(options) {
    await delay(100)
    // 模拟识别结果（1.5s 后）
    setTimeout(() => {
      mockEmit('face.recognized', { faceId: 'MOCK_001', score: 0.92 })
    }, 1500)
    return { accepted: true }
  }

  async function faceRecognitionCancel() {
    await delay(50)
    mockEmit('face.recognition.cancelled', {})
    return { cancelled: true }
  }

  async function faceEnrollmentStart(faceId) {
    await delay(100)
    // 模拟录入成功（2s 后），返回完整的 faceImageBase64 以通注册→保存→上传链路
    setTimeout(() => {
      mockEmit('face.enrolled', {
        faceId,
        faceFeature: 'MOCK_BASE64_FEATURE==',
        faceImageBase64: MOCK_FACE_IMAGE_BASE64,
        faceImageMimeType: 'image/jpeg',
        score: 0.95
      })
    }, 2000)
    return { accepted: true }
  }

  async function faceEnrollmentCancel() {
    await delay(50)
    mockEmit('face.enrollment.cancelled', {})
    return { cancelled: true }
  }

  async function faceCount() {
    await delay(50)
    return { count: 0 } // mock 环境无人脸库
  }

  async function runRecognition(type, progressCallback) {
    if (type === 'FINGERPRINT') {
      if (progressCallback) progressCallback({ status: 'COLLECTING', message: '请将已录入系统的手指放在指纹采集区（模拟）' })
      mockEmit('fingerprint.statusChanged', { status: 'WAITING_FOR_TOUCH', message: '请将已录入系统的手指放在指纹采集区（模拟）', operation: 'VERIFY', errorCode: 0 })
      await delay(900)
      mockEmit('fingerprint.statusChanged', { status: 'SUCCESS', message: '系统指纹验证成功（模拟）', operation: 'VERIFY', errorCode: 0 })
      return {
        accepted: true,
        success: true,
        status: 'SYSTEM_AUTHENTICATED',
        message: '系统指纹验证成功（模拟）',
        systemAuthenticated: true,
        canCompleteTake: false,
        closeLoopMessage: '系统指纹已通过，但当前能力只能确认本机用户，不能识别员工身份，取卡闭环未完成。',
        reason: 'EMPLOYEE_FINGERPRINT_NOT_AVAILABLE'
      }
    }
    if (type !== 'FACE') return { accepted: false, error: 'UNSUPPORTED_RECOGNITION_TYPE', message: '不支持的识别方式' }
    if (progressCallback) progressCallback({ status: 'DETECTING', message: '请正对摄像头' })
    await faceRecognitionStart({})
    await delay(1600)
    return { accepted: true, status: 'SUCCESS', faceId: 'MOCK_001', score: 0.92 }
  }

  async function registerBiometric(type, data, progressCallback) {
    if (type === 'FINGERPRINT') {
      if (progressCallback) progressCallback({ status: 'COLLECTING', message: '请按系统提示确认本机指纹（模拟）' })
      mockEmit('fingerprint.statusChanged', { status: 'WAITING_FOR_TOUCH', message: '请按系统提示确认本机指纹（模拟）', operation: 'ENROLL', errorCode: 0 })
      await delay(900)
      mockEmit('fingerprint.statusChanged', { status: 'SUCCESS', message: '本机系统指纹授权成功（模拟）', operation: 'ENROLL', errorCode: 0 })
      return {
        accepted: true,
        success: true,
        status: 'SYSTEM_AUTHENTICATED',
        employeeId: data?.employeeId || '',
        employeeName: data?.employeeName || '',
        message: '本机系统指纹授权成功（模拟）',
        systemAuthenticated: true,
        canCompleteEmployeeBinding: false,
        closeLoopMessage: '系统不会向应用提供指纹模板或编号，不能作为员工级指纹绑定闭环。',
        reason: 'EMPLOYEE_FINGERPRINT_NOT_AVAILABLE'
      }
    }
    if (type !== 'FACE') return { accepted: false, error: 'UNSUPPORTED_BIOMETRIC_TYPE', message: '不支持的生物特征类型' }
    const faceId = `${data.employeeId}_0`
    if (progressCallback) progressCallback('DETECTING')
    await faceEnrollmentStart(faceId)
    await delay(2100)
    return { accepted: true, faceId, score: 0.95 }
  }

  async function searchEmployees(query = '') {
    const keyword = String(query || '').trim().toLowerCase()
    if (!keyword) return mockEmployees
    return mockEmployees.filter((employee) => [
      employee.employeeId,
      employee.employeeCode,
      employee.employeeName,
      employee.departmentName
    ].some((value) => String(value || '').toLowerCase().includes(keyword)))
  }

  async function syncEmployees() {
    await delay(300)
    return mockEmployees
  }

  async function unlockDoor(slotNumber) {
    assertMockPermission('realtime.slot.open')
    await serialSend(`MOCK_UNLOCK_${slotNumber}`)
    pushMockHistory({
      type: '管理员开门',
      employeeName: appState.session?.credentialLabel || appState.session?.roleLabels?.join('、') || '本机管理员',
      slotNumber,
      result: '成功'
    })
    return {
      sent: true,
      mock: true,
      message: `${String(slotNumber).padStart(2, '0')}号卡门模拟开门成功`
    }
  }

  async function getHistory() {
    await delay(80)
    return replaceHistoryProjection(mockHistory)
  }

  async function handleMockRemoteOpenCommand(message = {}) {
    const msgId = String(message.msgId || '').trim()
    const data = message.data || {}
    const slotNumber = Number(data.slotId || data.slotNumber || 0) || null
    await serialSend(`MOCK_REMOTE_OPEN_${slotNumber || ''}`)
    pushMockHistory({
      type: '后台开柜',
      employeeName: data.operatorId || '后台',
      slotNumber,
      result: '成功'
    })
    return mqttSend('remoteOpenResp', { code: 0, msg: 'success' }, msgId ? { msgId } : {})
  }

  function handleMockMqttBusinessMessage(message = {}) {
    if (message?.cmd === 'remoteOpen') {
      handleMockRemoteOpenCommand(message).catch((error) => {
        console.warn(TAG, 'mock remoteOpen failed:', error)
      })
    }
  }

  // ── 事件 ──

  function on(eventName, callback) {
    if (!mockEventListeners[eventName]) {
      mockEventListeners[eventName] = []
    }
    mockEventListeners[eventName].push(callback)
    return () => off(eventName, callback)
  }

  function off(eventName, callback) {
    const listeners = mockEventListeners[eventName]
    if (listeners) {
      const idx = listeners.indexOf(callback)
      if (idx >= 0) listeners.splice(idx, 1)
    }
  }

  function destroy() {
    if (mockBusinessUnsubscribe) {
      try { mockBusinessUnsubscribe() } catch (e) {}
      mockBusinessUnsubscribe = null
    }
    Object.keys(mockEventListeners).forEach(key => delete mockEventListeners[key])
  }
}
