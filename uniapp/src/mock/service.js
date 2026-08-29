/**
 * Mock createMockService() — 替换 mockService.js
 *
 * exports createMockService() 返回与原始 mockService 完全相同的接口，
 * 但内部使用真实 HTTP fetch、MQTT、sql.js 等 mock 基础设施。
 *
 * 通过 Vite resolveId 插件透明注入，不改动 services/index.js。
 */

import { emit, on, off } from './events.js'
import { query as dbQuery, execute as dbExecute, clearDatabase } from './worker-adapter.js'
import {
  setCredentials as bridgeSetCredentials,
  setDeviceInfo as bridgeSetDeviceInfo,
  setActivated as bridgeSetActivated,
  emitDeviceInfo as bridgeEmitDeviceInfo,
  connectMqtt,
  request as bridgeRequest,
  serverUrl, deviceToken, deviceCode, mqttPassword
} from './bridge.js'
import { start as serialStart, stop as serialStop, setSlotState, getSnapshot, updateTotalSlots } from './serial-sim.js'
import { faceRecognitionStart, faceRecognitionCancel, faceEnrollmentStart, faceEnrollmentCancel } from './face-service.js'
import { startHeartbeat, sendHeartbeat, destroyMqttClient, setSigningKey, sendEnvelope } from './mqtt-sim.js'
import { log, warn, error, DEFAULT_TIMEOUT } from './config.js'

const TAG = 'MockService'

// ── Helpers ──

async function fetchJson(method, url, body = null) {
  const fullUrl = url.startsWith('http') ? url : `${serverUrl}${url}`
  const headers = { 'Content-Type': 'application/json' }
  if (deviceToken) headers['Authorization'] = `Bearer ${deviceToken}`

  const res = await fetch(fullUrl, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`HTTP ${res.status}: ${text.substring(0, 200)}`)
  }
  return res.json()
}

// ── Storage Helpers ──

function storageQuery(sql, params = []) {
  return dbQuery(sql, params).then(r => ({ rows: r.rows || [], count: r.count || 0 }))
}

function storageExecute(sql, params = []) {
  return dbExecute(sql, params).then(r => ({ affectedRows: r.affectedRows || 0 }))
}

// ── Bootstrap Config Persistence ──

async function loadBootstrapConfig() {
  try {
    const result = await storageQuery(
      "SELECT value FROM vue_local_config WHERE key='bootstrap_config'"
    )
    if (result.rows.length > 0) {
      return JSON.parse(result.rows[0].value)
    }
  } catch (e) {
    log(TAG, 'loadBootstrapConfig: not found or error:', e.message)
  }
  return null
}

async function saveBootstrapConfig(config) {
  const json = JSON.stringify(config)
  await storageExecute(
    "INSERT OR REPLACE INTO vue_local_config (key, value, updated_at) VALUES ('bootstrap_config', ?, ?)",
    [json, Date.now()]
  )
  log(TAG, 'Bootstrap config saved')
}

async function saveRuntimeConfig(config) {
  const json = JSON.stringify(config)
  await storageExecute(
    "INSERT OR REPLACE INTO vue_local_config (key, value, updated_at) VALUES ('runtime_config', ?, ?)",
    [json, Date.now()]
  )
  log(TAG, 'Runtime config saved')
}

// ── Slots Snapshot ──

async function saveSlotsSnapshot(slots) {
  const json = JSON.stringify(slots)
  await storageExecute(
    "INSERT OR REPLACE INTO vue_local_config (key, value, updated_at) VALUES ('slots_cache', ?, ?)",
    [json, Date.now()]
  )
}

async function loadSlotsSnapshot() {
  try {
    const result = await storageQuery(
      "SELECT value FROM vue_local_config WHERE key='slots_cache'"
    )
    if (result.rows.length > 0) {
      return JSON.parse(result.rows[0].value)
    }
  } catch (e) {
    // ignore
  }
  return []
}

async function trimSlotsAfter(maxSlot) {
  const slots = await loadSlotsSnapshot()
  if (slots.length > maxSlot) {
    const trimmed = slots.slice(0, maxSlot)
    await saveSlotsSnapshot(trimmed)
    log(TAG, `trimSlotsAfter: trimmed from ${slots.length} to ${maxSlot}`)
  }
}

// ══════════════════════════════════════════
//  createMockService
// ══════════════════════════════════════════

// ══════════════════════════════════════════
//  Bootstrap 中间状态（支持分步交互）
// ══════════════════════════════════════════

let bootstrapState = null
function resetBootstrapState() {
  bootstrapState = null
}

async function continueAfterActivation() {
  const s = bootstrapState
  if (!s) throw new Error('No stored bootstrap state')

  // ══ 阶段C: 获取设备配置 ══
  emit('bootstrap.progress', { phase: 'GETTING_CONFIG', progress: 60 })
  log(TAG, 'Phase C: Getting config...')

  let configResp
  try {
    configResp = await fetchJson('GET', '/api/v1/device/config')
  } catch (e) {
    error(TAG, 'Get config failed:', e.message)
    emit('bootstrap.error', { phase: 'GETTING_CONFIG', message: e.message })
    throw e
  }

  const config = configResp.data || configResp
  await saveRuntimeConfig(config)
  log(TAG, 'Config saved:', config)

  const totalSlots = config.totalSlots || 12
  updateTotalSlots(totalSlots)
  bridgeSetDeviceInfo({ totalSlots })

  // ══ 阶段D: MQTT 连接 + 登录 ══
  emit('bootstrap.progress', { phase: 'CONNECTING_MQTT', progress: 70 })
  log(TAG, 'Phase D: Connecting MQTT...')

  const { createMqttClient: createMqtt, destroyMqttClient: destroyMqtt } = await import('./mqtt-sim.js')
  await setupMqtt(s.code, s.mqttPw, s.clientId)

  emit('bootstrap.progress', { phase: 'MQTT_CONNECTED', progress: 80 })

  emit('bootstrap.progress', { phase: 'LOGGING_IN', progress: 85 })
  log(TAG, 'Sending MQTT login...')

  // 确保 signingKey 已注入 mqtt-sim
  if (s.signingKey) setSigningKey(s.signingKey)

  const { sendEnvelope: sendEnv } = await import('./mqtt-sim.js')
  await sendEnv('login', { version: '1.0.0-mock', ip: '127.0.0.1' }, 'lg')

  // 不再用 setTimeout 模拟 loginResp —— 等待服务端真实响应
  // 服务端将在 card/${dc}/down/response 返回 loginResp，通过 onDownstream 触发 mqtt.loginResp 事件

  startHeartbeat()

  emit('bootstrap.progress', { phase: 'LOGGED_IN', progress: 100 })
  log(TAG, 'Bootstrap complete')

  emit('bootstrap.success', {
    deviceCode: s.code,
    totalSlots,
    communicationMode: config.communicationMode || 'MQTT',
    config
  })

  bridgeSetActivated(true)
  bridgeEmitDeviceInfo()

  resetBootstrapState()
  return { deviceCode: s.code, deviceToken: s.token, totalSlots, config }
}

export function createMockService() {
  return {
    name: 'mock-service-v2',
    env: 'mock',

    // ── Event Bus (compat with services/index.js usage) ──
    on,
    off,
    emit,
    getEventBusState: () => ({ listenersCount: 0 }),

    // ══════════════════════════════════════
    //  Bootstrap
    // ══════════════════════════════════════

    async bootstrap({ serverUrl: inputUrl, activationCode = '' }) {
      log(TAG, 'bootstrap starting, serverUrl:', inputUrl)

      if (inputUrl) {
        bridgeSetCredentials({ serverUrl: inputUrl })
      }

      // 生成浏览器设备指纹
      const machineId = `browser_${btoa(navigator.userAgent + screen.width + 'x' + screen.height).substring(0, 40).replace(/[^a-zA-Z0-9]/g, '')}`

      // ══ 阶段0: 设备注册 ══
      emit('bootstrap.progress', { phase: 'REGISTERING', progress: 10 })
      log(TAG, 'Phase 0: Registering device...')

      let registerResp
      try {
        registerResp = await fetchJson('POST', '/api/v1/device/register', {
          mac: 'AA:BB:CC:DD:EE:FF',
          model: 'KOMEI-CARD-V1',
          machineId,
          osType: 'DEV',
          osVersion: '12.0',
          version: '1.2.3',
          versionCode: 1,
          channelId: 'official'
        })
      } catch (e) {
        error(TAG, 'Register failed:', e.message)
        emit('bootstrap.error', { phase: 'REGISTERING', message: e.message })
        throw e
      }

      const token = registerResp.deviceToken || registerResp.data?.deviceToken
      const code = registerResp.deviceCode || registerResp.data?.deviceCode
      if (!token || !code) {
        const msg = 'Register response missing deviceToken or deviceCode'
        error(TAG, msg, registerResp)
        emit('bootstrap.error', { phase: 'REGISTERING', message: msg })
        throw new Error(msg)
      }

      bridgeSetCredentials({ deviceToken: token, deviceCode: code })
      bridgeSetDeviceInfo({ machineId, model: 'KOMEI-CARD-V1', version: '1.2.3', os: 'DEV 12.0',deviceCode: code })
      log(TAG, `Registered: deviceCode=${code}`)

      // ══ 阶段A: 设备激活 ══
      emit('bootstrap.progress', { phase: 'ACTIVATING', progress: 30 })
      log(TAG, 'Phase A: Activating...')

      let activateResp
      try {
        activateResp = await fetchJson('POST', '/api/v1/device/activate', { deviceId: code, machineId })
      } catch (e) {
        error(TAG, 'Activate failed:', e.message)
        emit('bootstrap.error', { phase: 'ACTIVATING', message: e.message })
        throw e
      }

      const mqttPw = activateResp.mqttPassword || activateResp.data?.mqttPassword
      const registerCode = activateResp.registerCode || activateResp.data?.registerCode

      if (mqttPw) {
        // 路径A: 已激活
        const signingKey = activateResp.signingKey || activateResp.data?.signingKey || ''
        const clientId = activateResp.clientId || activateResp.data?.clientId || (activateResp.data?.valid && '')
        bridgeSetCredentials({ mqttPassword: mqttPw, signingKey })
        setSigningKey(signingKey)
        bootstrapState = { code, token, machineId, mqttPw, clientId, signingKey }
        log(TAG, `Activated (path A), signingKey=${signingKey ? 'present' : 'MISSING'}`)
      } else if (registerCode) {
        // 路径B: 需要激活码
        const expireTime = activateResp.data?.expireTime || activateResp.expireTime || ''
        emit('bootstrap.progress', {
          phase: 'WAITING_ACTIVATION_CODE', progress: 40,
          registerCode, deviceCode: code, expireTime
        })
        log(TAG, 'Waiting activation code:', registerCode)

        // 保存中间状态，等待用户输入激活码
        bootstrapState = { code, token, registerCode, machineId }
        bridgeSetCredentials({ registerCode })

        // 如果调用者已传入 activationCode，直接继续
        if (!activationCode) return

        emit('bootstrap.progress', { phase: 'VERIFYING_CODE', progress: 50 })
        log(TAG, 'Phase B: Verifying code...')

        let verifyResp
        try {
          verifyResp = await fetchJson('POST', '/api/v1/device/verify', { registerCode: bootstrapState.registerCode, activeKey: activationCode })
        } catch (e) {
          error(TAG, 'Verify failed:', e.message)
          emit('bootstrap.error', { phase: 'VERIFYING_CODE', message: e.message })
          throw e
        }

        const vPw = verifyResp.mqttPassword || verifyResp.data?.mqttPassword
        if (!vPw) {
          const msg = 'Verify response missing mqttPassword'
          emit('bootstrap.error', { phase: 'VERIFYING_CODE', message: msg })
          throw new Error(msg)
        }
        const vSigningKey = verifyResp.signingKey || verifyResp.data?.signingKey || ''
        const vClientId = verifyResp.clientId || verifyResp.data?.clientId || ''
        bridgeSetCredentials({ mqttPassword: vPw, signingKey: vSigningKey })
        setSigningKey(vSigningKey)
        bootstrapState.mqttPw = vPw
        bootstrapState.clientId = vClientId
        bootstrapState.signingKey = vSigningKey
        log(TAG, `Verified, signingKey=${vSigningKey ? 'present' : 'MISSING'}`)
      } else {
        const msg = 'Activate response missing mqttPassword and registerCode'
        emit('bootstrap.error', { phase: 'ACTIVATING', message: msg })
        throw new Error(msg)
      }

      // 路径A（直接激活）或路径B（验证成功）→ 继续后续流程
      return continueAfterActivation()
    },

    // ══ Bootstrap 后续交互 ══

    async bootstrapActivate(code) {
      if (!bootstrapState) {
        const msg = 'No active bootstrap session'
        emit('bootstrap.error', { phase: 'VERIFYING_CODE', message: msg })
        throw new Error(msg)
      }

      emit('bootstrap.progress', { phase: 'VERIFYING_CODE', progress: 50 })
      log(TAG, 'Phase B: Verifying code:', code)

      let verifyResp
      try {
        verifyResp = await fetchJson('POST', '/api/v1/device/verify', { registerCode: bootstrapState.registerCode, activeKey: code })
      } catch (e) {
        error(TAG, 'Verify failed:', e.message)
        emit('bootstrap.error', { phase: 'VERIFYING_CODE', message: e.message })
        throw e
      }

      const vPw = verifyResp.mqttPassword || verifyResp.data?.mqttPassword
      if (!vPw) {
        const msg = 'Verify response missing mqttPassword'
        emit('bootstrap.error', { phase: 'VERIFYING_CODE', message: msg })
        throw new Error(msg)
      }
      const vSigningKey = verifyResp.signingKey || verifyResp.data?.signingKey || ''
      const vClientId = verifyResp.clientId || verifyResp.data?.clientId || ''
      bridgeSetCredentials({ mqttPassword: vPw, signingKey: vSigningKey })
      setSigningKey(vSigningKey)
      bootstrapState.mqttPw = vPw
      bootstrapState.clientId = vClientId
      bootstrapState.signingKey = vSigningKey
      log(TAG, `Verified, signingKey=${vSigningKey ? 'present' : 'MISSING'}`)

      return continueAfterActivation()
    },

    async bootstrapRetry() {
      resetBootstrapState()
      log(TAG, 'bootstrapRetry: resetting state, restart from scratch')
      return this.bootstrap({})
    },

    async bootstrapRefreshCode() {
      if (!bootstrapState) {
        const msg = 'No active bootstrap session to refresh'
        emit('bootstrap.error', { phase: 'ACTIVATING', message: msg })
        throw new Error(msg)
      }

      emit('bootstrap.progress', { phase: 'ACTIVATING', progress: 30 })
      log(TAG, 'Refreshing activation code...')

      let activateResp
      try {
        activateResp = await fetchJson('POST', '/api/v1/device/activate', {
          deviceId: bootstrapState.code,
          machineId: bootstrapState.machineId
        })
      } catch (e) {
        error(TAG, 'Activate refresh failed:', e.message)
        emit('bootstrap.error', { phase: 'ACTIVATING', message: e.message })
        throw e
      }

      const registerCode = activateResp.registerCode || activateResp.data?.registerCode
      if (!registerCode) {
        const msg = 'Refresh response missing registerCode'
        emit('bootstrap.error', { phase: 'ACTIVATING', message: msg })
        throw new Error(msg)
      }

      bootstrapState.registerCode = registerCode
      const expireTime = activateResp.data?.expireTime || activateResp.expireTime || ''
      emit('bootstrap.progress', {
        phase: 'WAITING_ACTIVATION_CODE', progress: 40,
        registerCode, deviceCode: bootstrapState.code, expireTime
      })
      log(TAG, 'New registerCode:', registerCode)
      return { registerCode, expireTime }
    },

    async bootstrapCancel() {
      resetBootstrapState()
      log(TAG, 'bootstrapCancel: state cleared')
    },

    async bootstrapDeviceInfo() {
      return bridgeRequest('bootstrap.deviceInfo')
    },

    // ══════════════════════════════════════
    //  Config Persistence
    // ══════════════════════════════════════

    loadBootstrapConfig,
    saveBootstrapConfig,

    async loadSettings() {
      try {
        const result = await storageQuery(
          "SELECT value FROM vue_local_config WHERE key='runtime_config'"
        )
        if (result.rows.length > 0) {
          return JSON.parse(result.rows[0].value)
        }
      } catch (e) {
        log(TAG, 'loadSettings: not found')
      }
      return null
    },

    saveRuntimeConfig,

    async loadSlotsSnapshot() {
      const slots = await loadSlotsSnapshot()
      // 如果 sqlite 没有缓存，从 serial-sim 拿
      if (!slots || slots.length === 0) {
        return getSnapshot()
      }
      return slots
    },

    async saveSlotsSnapshot(slots) {
      // 同步写 sqlite
      return saveSlotsSnapshot(slots)
    },

    async trimSlotsAfter(maxSlot) {
      return trimSlotsAfter(maxSlot)
    },

    // ══════════════════════════════════════
    //  HTTP
    // ══════════════════════════════════════

    async httpGet(url) {
      return fetchJson('GET', url)
    },

    async httpPost(url, body) {
      return fetchJson('POST', url, body)
    },

    // ══════════════════════════════════════
    //  MQTT
    // ══════════════════════════════════════

    mqttConnect() {
      log(TAG, 'mqttConnect called (handled by bootstrap)')
      return Promise.resolve(true)
    },

    async mqttSend(cmd, data) {
      const { sendEnvelope: sendEnv } = await import('./mqtt-sim.js')
      return sendEnv(cmd, data || {})
    },

    // ══════════════════════════════════════
    //  Serial
    // ══════════════════════════════════════

    serialSend(cmd, data, timeout) {
      log(TAG, `serialSend: ${cmd}`, data)
      // mock 不需要超时，直接返回
      if (cmd === 'start') {
        serialStart(data?.totalSlots || 12)
      } else if (cmd === 'setSlot') {
        setSlotState(data?.slotNumber, data?.status, data)
      }
      return Promise.resolve({ success: true })
    },

    getSerialState() {
      return { slots: getSnapshot(), totalSlots: getSnapshot().length }
    },

    // ══════════════════════════════════════
    //  Face
    // ══════════════════════════════════════

    faceRecognitionStart(options) {
      return faceRecognitionStart(options)
    },

    faceRecognitionCancel() {
      faceRecognitionCancel()
    },

    faceEnrollmentStart(faceId, options) {
      return faceEnrollmentStart(faceId, options)
    },

    faceEnrollmentCancel() {
      faceEnrollmentCancel()
    },

    // ══════════════════════════════════════
    //  Storage
    // ══════════════════════════════════════

    storageQuery(sql, params) {
      return storageQuery(sql, params)
    },

    storageExecute(sql, params) {
      return storageExecute(sql, params)
    },

    storageInsert(sql, params) {
      return storageExecute(sql, params)
    },

    getStorageState() {
      return { initialized: true, type: 'localStorage + Map' }
    },

    // ══════════════════════════════════════
    //  Admin
    // ══════════════════════════════════════

    async clearAllData() {
      await clearDatabase()
      destroyMqttClient()
      serialStop()
      log(TAG, 'All data cleared')
      return { success: true }
    },

    async getStatus() {
      const slots = getSnapshot()
      return {
        connected: true,
        mqtt: { connected: true },
        slots: { total: slots.length, occupied: slots.filter(s => s.status === 'OCCUPIED').length },
        storage: 'localStorage',
        face: 'mock',
        env: 'mock-browser'
      }
    }
  }
}

// ══════════════════════════════════════════
//  MQTT Login Helper (internal)
// ══════════════════════════════════════════

async function setupMqtt(dc, pw, cid) {
  // 委托给 bridge.js，统一 MQTT 客户端和 device.info 推送
  return connectMqtt({ clientId: cid })
}
