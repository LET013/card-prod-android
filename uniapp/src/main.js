// ── Mock 模式：页面刷新时强制回到 splash 重新走启动流程 ──
// MQTT 连接、config 等内存状态不跨页面刷新持久化，
// 必须从 splash.vue 重新走 bootstrap，否则会导致连接状态异常。
(function forceSplashInMockMode() {
  if (typeof __CARD_MOCK_DEV__ !== 'undefined' && __CARD_MOCK_DEV__ && typeof window !== 'undefined') {
    const hash = window.location.hash
    if (hash && !hash.includes('/pages/splash/splash')) {
      console.log('[main] Mock dev mode: redirecting to splash page (current hash:', hash, ')')
      window.location.replace(window.location.pathname + '#/pages/splash/splash')
    }
  }
})()

import { createSSRApp, watch } from 'vue'
import App from './App.vue'
import nativeBridge from './services/nativeBridge.js'
import { services } from './services/index.js'
import { createSlotProjectionScheduler } from './services/slotProjectionScheduler.js'
import {
  appState,
  replaceSettingsProjection,
  replaceSlotsProjection,
  getSlotProjection,
  upsertSlotProjection,
  onTotalSlotsChange,
  hasAnyPermission
} from './state/appState.js'

let initialized = false
let hydrationPromise = null
let routeGuardInstalled = false
let sessionExpiryTimer = null
let sessionRefreshScheduled = false
const observedSlotStates = new Map()

const CHANGE_PASSWORD_PAGE = '/pages/admin/change-password'

const slotNumberOf = (slot) => Number(slot?.slotNumber ?? slot?.slotId ?? slot?.address)
const slotObservationSignature = (slot = {}) => [
  String(slot.status || '').trim().toUpperCase(),
  String(slot.cardNo ?? slot.cardNumber ?? slot.cardId ?? '').trim(),
  String(slot.faultMask ?? slot.faultCode ?? ''),
  String(slot.faultMessage ?? slot.faultMsg ?? '').trim()
].join('|')

const slotHasBusinessChange = (previous, current) => slotObservationSignature(previous) !== slotObservationSignature(current)

const observeSlotBusinessChange = (slot, { reportFault = false } = {}) => {
  const slotNumber = slotNumberOf(slot)
  if (!Number.isInteger(slotNumber) || slotNumber < 1) return
  const previous = observedSlotStates.get(slotNumber) || getSlotProjection(slotNumber)
  if (!slotHasBusinessChange(previous, slot)) return

  if (reportFault) {
    services.reportSlotHardwareFault(previous ? { ...previous } : null, slot).catch((error) => {
      console.warn('[main] report slot hardware fault failed:', error)
    })
  }
  services.observeReturnCard(previous ? { ...previous } : null, slot).catch((error) => {
    console.warn('[main] observe return card failed:', error)
  })
  observedSlotStates.set(slotNumber, { ...slot })
}

const PUBLIC_PAGES = new Set([
  '/pages/index/index',
  '/pages/splash/splash'
])

const PAGE_PERMISSIONS = {
  '/pages/admin/admin': [
    'account.*', 'system.*', 'maintenance.*', 'realtime.*'
  ],
  '/pages/system/system': ['system.*'],
  '/pages/card-status/card-status': ['realtime.slot.view'],
  '/pages/employees/employees': ['system.employee.view'],
  '/pages/biometric/face': ['system.face.*'],
  '/pages/config/config': ['system.settings.view'],
  '/pages/feature/feature': ['system.*'],
  '/pages/engineering/engineering': ['maintenance.*'],
  '/pages/serial-demo/serial-demo': ['maintenance.serial.*'],
  '/pages/admin/role-manage': ['account.role.view'],
  '/pages/admin/credential-manage': ['account.user.view'],
  [CHANGE_PASSWORD_PAGE]: []
}

const FEATURE_TYPE_PERMISSIONS = {
  history: ['system.history.view'],
  units: ['system.unit.view'],
  restart: ['system.restart'],
  authorization: ['system.authorization.view'],
  command: ['maintenance.serial.manual-command']
}

const SECONDARY_REQUIRED_PAGES = new Set([
  '/pages/admin/role-manage',
  '/pages/admin/credential-manage'
])

const normalizeRouteUrl = (url = '') => {
  const raw = String(url || '').trim()
  const [pathPart, queryPart = ''] = raw.split('?')
  const path = pathPart.startsWith('/') ? pathPart : `/${pathPart}`
  return { path, query: queryPart, raw }
}

const isStartupConfigRoute = ({ path, query }) => path === '/pages/config/config' &&
  (query === 'startupError=1' || query.startsWith('startupError='))

const getRoutePermissions = ({ path, query }) => {
  if (path !== '/pages/feature/feature') return PAGE_PERMISSIONS[path]
  const featureType = new URLSearchParams(query).get('type') || 'history'
  return FEATURE_TYPE_PERMISSIONS[featureType] || PAGE_PERMISSIONS[path]
}

const showRouteToast = (title) => {
  try { uni.showToast({ title, icon: 'none' }) } catch (error) {}
}

const clearSessionExpiryTimer = () => {
  if (sessionExpiryTimer) clearTimeout(sessionExpiryTimer)
  sessionExpiryTimer = null
}

const scheduleSessionExpiry = () => {
  clearSessionExpiryTimer()
  const expiresAt = Number(appState.session?.expiresAt || 0)
  if (!expiresAt) return
  const delay = expiresAt - Date.now()
  if (delay <= 0) {
    forceLogoutToHome('登录已过期')
    return
  }
  sessionExpiryTimer = setTimeout(() => {
    forceLogoutToHome('登录已过期')
  }, delay)
}

const forceLogoutToHome = (message) => {
  clearSessionExpiryTimer()
  services.logoutLocal().catch((error) => {
    console.warn('[routeGuard] logoutLocal failed:', error)
  })
  showRouteToast(message || '登录已过期')
  uni.reLaunch({ url: '/pages/index/index' })
}

const refreshSessionTtl = () => {
  return services.refreshLocalSession()
    .then((result) => {
      if (result?.refreshed) scheduleSessionExpiry()
      return result
    })
    .catch((error) => {
      console.warn('[routeGuard] refreshLocalSession failed:', error)
      return { refreshed: false, reason: error?.message || 'FAILED' }
    })
}

const runAfterNextPaint = (callback) => {
  if (typeof requestAnimationFrame !== 'function') {
    setTimeout(callback, 0)
    return
  }
  requestAnimationFrame(() => requestAnimationFrame(callback))
}

const scheduleSessionTtlRefresh = () => {
  if (sessionRefreshScheduled) return
  sessionRefreshScheduled = true
  runAfterNextPaint(() => {
    sessionRefreshScheduled = false
    refreshSessionTtl()
  })
}

const redirectToPasswordChange = () => {
  showRouteToast('请先修改初始密码')
  uni.redirectTo({ url: `${CHANGE_PASSWORD_PAGE}?force=1` })
}

const redirectToAdminForSecondaryPassword = () => {
  showRouteToast('请先输入二级密码')
  uni.redirectTo({ url: '/pages/admin/admin' })
}

const guardRoute = (url) => {
  const route = normalizeRouteUrl(url)
  if (!route.raw || PUBLIC_PAGES.has(route.path) || isStartupConfigRoute(route)) return true

  const session = appState.session
  if (!session) {
    showRouteToast('请先登录')
    uni.reLaunch({ url: '/pages/index/index' })
    return false
  }

  if (Number(session.expiresAt || 0) <= Date.now()) {
    forceLogoutToHome('登录已过期')
    return false
  }

  if (session.needsPasswordChange && route.path !== CHANGE_PASSWORD_PAGE) {
    redirectToPasswordChange()
    return false
  }

  const required = getRoutePermissions(route)
  if (required?.length && !hasAnyPermission(...required)) {
    showRouteToast('权限不足')
    return false
  }

  if (SECONDARY_REQUIRED_PAGES.has(route.path) && !services.hasAdminManageSecondaryAccess()) {
    redirectToAdminForSecondaryPassword()
    return false
  }

  scheduleSessionTtlRefresh()
  return true
}

function installRouteGuard() {
  if (routeGuardInstalled || typeof uni === 'undefined') return
  routeGuardInstalled = true
  ;['navigateTo', 'redirectTo', 'reLaunch', 'switchTab'].forEach((method) => {
    uni.addInterceptor(method, {
      invoke(args = {}) {
        return guardRoute(args.url || '')
      }
    })
  })
  uni.addInterceptor('navigateBack', {
    invoke() {
      if (!appState.session) return true
      if (Number(appState.session.expiresAt || 0) <= Date.now()) {
        forceLogoutToHome('登录已过期')
        return false
      }
      if (appState.session.needsPasswordChange) {
        redirectToPasswordChange()
        return false
      }
      scheduleSessionTtlRefresh()
      return true
    }
  })
}

function installPermissionDirective(app) {
  app.directive('permission', {
    mounted(el, binding) {
      const originalDisplay = el.style.display
      const apply = (value) => {
        const required = Array.isArray(value) ? value : [value]
        const granted = required.filter(Boolean).length > 0 && hasAnyPermission(...required.filter(Boolean))
        el.style.display = granted ? originalDisplay : 'none'
        el.setAttribute('aria-hidden', granted ? 'false' : 'true')
      }
      el.__permissionApply = apply
      el.__permissionValue = binding.value
      el.__permissionStop = watch(
        () => Array.from(appState.session?.permissions || []),
        () => apply(el.__permissionValue),
        { immediate: true }
      )
    },
    updated(el, binding) {
      el.__permissionValue = binding.value
      el.__permissionApply?.(binding.value)
    },
    unmounted(el) {
      el.__permissionStop?.()
      delete el.__permissionApply
      delete el.__permissionStop
      delete el.__permissionValue
    }
  })
}

function hydrateProjection() {
  if (hydrationPromise) return hydrationPromise
  hydrationPromise = services.loadSettings({ remote: false })
    .then((settings) => { if (settings) replaceSettingsProjection(settings) })
    .catch((e) => { appState.lastError = e?.message || '' })
    .finally(() => { hydrationPromise = null })
  return hydrationPromise
}

export function createApp() {
  const app = createSSRApp(App)
  installPermissionDirective(app)

  // 在入口点注册事件监听器，确保必然执行（不依赖 App.vue 的 onLaunch）
  if (!initialized) {
    initialized = true
    console.log('[main] createApp: initializing native bridge and listeners')

    nativeBridge.init()
    // 不依赖 native.ready，避免该事件先于 Vue 监听触发时漏掉已保存的后台日志开关。
    services.restoreLogUploadPolicyOnStartup().catch((error) => {
      console.warn('[main] initial log upload policy restore failed:', error)
    })
    installRouteGuard()
    services.clearLocalSessionOnStartup().catch((error) => {
      console.warn('[main] clear local session on startup failed:', error)
    })
    services.registerMqttBusinessHandlers().catch((error) => {
      console.warn('[main] register MQTT business handlers failed:', error)
    })
    services.schedulePendingCardEventFlush('startup')
    services.flushPendingDiagnosticEvents('startup').catch((error) => {
      console.warn('[main] flush pending diagnostic events on startup failed:', error)
    })

    const handleBackendConnected = (reason) => {
      services.registerMqttBusinessHandlers({ reason }).catch((error) => {
        console.warn('[main] register MQTT business handlers after connect failed:', error)
      })
      services.flushPendingMqttResponses(reason).catch((error) => {
        console.warn('[main] flush pending MQTT responses failed:', error)
      })
      services.flushPendingCardEvents(20, reason).catch((error) => {
        console.warn('[main] flush pending card events failed:', error)
      })
      services.flushPendingStatusReports(reason).catch((error) => {
        console.warn('[main] flush pending status reports failed:', error)
      })
      services.flushPendingDiagnosticEvents(reason).catch((error) => {
        console.warn('[main] flush pending diagnostic events failed:', error)
      })
    }

    // native.ready 由 MainActivity.onPageFinished() 发送
    // 但存在竞态：onPageFinished 可能在 createApp() 执行前就已触发
    // 因此立即发起 hydration，不依赖 native.ready
    nativeBridge.on('native.ready', () => {
      console.log('[main] native.ready received (may be late or duplicate)')
      appState.bridgeReady = true
      hydrateProjection()
      services.registerMqttBusinessHandlers({ reason: 'native.ready' }).catch((error) => {
        console.warn('[main] register MQTT business handlers on native.ready failed:', error)
      })
      services.flushPendingCardEvents(20, 'native.ready').catch((error) => {
        console.warn('[main] flush pending card events on native.ready failed:', error)
      })
      services.recoverPendingAppRestart().catch((error) => {
        console.warn('[main] recover pending app restart failed:', error)
      })
      services.recoverPendingAppUpdate().catch((error) => {
        console.warn('[main] recover pending APP update failed:', error)
      })
      services.restoreLogUploadPolicyOnStartup().catch((error) => {
        console.warn('[main] restore log upload policy on startup failed:', error)
      })
    })
    nativeBridge.on('serial.statusChanged', (data) => {
      if (!data) return
      appState.runtime.serial = data
      const detectedTotal = Number(data.detectedTotalSlots)
      if (data.slotCountSource === 'BROADCAST' && Number.isInteger(detectedTotal) && detectedTotal > 0) {
        replaceSettingsProjection({ ...appState.settings, totalSlots: detectedTotal, totalCount: detectedTotal })
      }
    })
    nativeBridge.on('settings.changed', (data) => { if (data) replaceSettingsProjection(data) })
    const slotProjectionScheduler = createSlotProjectionScheduler({
      applySnapshot(slots) {
        replaceSlotsProjection(slots, { fresh: true })
        services.queueSlotsSnapshot(slots, 'SERIAL', true)
      },
      applySlotUpdates(slots) {
        slots.forEach((slot) => {
          const slotNumber = slotNumberOf(slot)
          upsertSlotProjection(slot, { fresh: true })
          const cached = getSlotProjection(slotNumber)
          services.queueSlotSnapshot(cached || slot, 'SERIAL', true)
        })
      }
    })
    nativeBridge.on('cabinet.slotsSnapshot', (data) => {
      const slots = Array.isArray(data) ? data : data?.slots
      if (!Array.isArray(slots)) return
      slots.forEach((slot) => observeSlotBusinessChange(slot))
      slotProjectionScheduler.enqueueSnapshot(slots)
    })
    nativeBridge.on('slot.status', (data) => {
      const slots = Array.isArray(data?.slots)
        ? data.slots
        : (Array.isArray(data) ? data : [data])
      const validSlots = slots.filter(Boolean)
      validSlots.forEach((slot) => observeSlotBusinessChange(slot, { reportFault: true }))
      slotProjectionScheduler.enqueueSlotUpdates(validSlots)
      services.scheduleStatusReport('slot.status')
    })
    nativeBridge.on('socket.statusChanged', (data) => {
      if (!data) return
      appState.runtime.socket = data
      if (data.connected === true) {
        handleBackendConnected('socket.connected')
      }
    })
    nativeBridge.on('mqtt.connected', (data) => {
      appState.runtime.socket = { ...(data || {}), connected: true }
      services.refreshMqttConnectionProjection()
        .then((mqttOnline) => {
          if (!mqttOnline) return
          handleBackendConnected('mqtt.connected')
          // 通知 MQTT 发送队列恢复排空
          try { services.notifyMqttConnected() } catch (e) { /* silent */ }
        })
        .catch((error) => {
          appState.deviceInfo.mqttConnected = false
          console.warn('[main] refresh MQTT connection projection failed:', error)
        })
    })
    nativeBridge.on('mqtt.disconnected', (data) => {
      appState.runtime.socket = { ...(data || {}), connected: false }
      appState.deviceInfo.mqttConnected = false
    })
    nativeBridge.on('device.authorizationChanged', (data) => {
      if (!data) return
      const state = String(data.state || data.newStatus || '').trim().toUpperCase()
      appState.runtime.deviceAuthorization = { ...data, ...(state ? { state } : {}) }
    })
    nativeBridge.on('recognition.statusChanged', (data) => { if (data) appState.runtime.recognitionEngine = data })

    // totalSlots 缩小时，清理 SQLite 中超出范围的旧卡槽缓存
    onTotalSlotsChange((newTotal) => {
      console.log('[main] totalSlots decreased to %d, trimming stale slot cache', newTotal)
      services.trimStaleSlots(newTotal).catch((e) => {
        console.warn('[main] trimStaleSlots failed:', e)
      })
    })

    // 立即发起 settings hydration，解决 native.ready 竞态问题
    // hydrateProjection 内部有去重保护 (hydrationPromise)
    hydrateProjection()
    services.recoverPendingAppRestart().catch((error) => {
      console.warn('[main] initial app restart recovery failed:', error)
    })
    services.recoverPendingAppUpdate().catch((error) => {
      console.warn('[main] initial APP update recovery failed:', error)
    })

    console.log('[main] all listeners registered, hydration triggered')
  }

  return { app }
}
