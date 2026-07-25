<script>
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'
import { services, nativeBridge } from '@/services/index.js'
import { appState, applySlotStatus } from '@/state/appState.js'

let hydrationPromise = null

const hydratePublicProjection = () => {
  if (hydrationPromise) return hydrationPromise
  hydrationPromise = Promise.allSettled([
    services.loadSettings(),
    services.getSlots()
  ]).then((results) => {
    const failed = results.find((item) => item.status === 'rejected')
    appState.lastError = failed?.reason?.message || ''
  }).finally(() => {
    hydrationPromise = null
  })
  return hydrationPromise
}

onLaunch(() => {
  services.init()
  nativeBridge.on('native.ready', () => {
    appState.bridgeReady = true
    hydratePublicProjection()
  })
  nativeBridge.on('serial.statusChanged', (data) => { if (data) appState.runtime.serial = data })
  nativeBridge.on('cabinet.slotStatus', (data) => {
    applySlotStatus(data)
  })
  nativeBridge.on('socket.statusChanged', (data) => { if (data) appState.runtime.socket = data })
  nativeBridge.on('status.reportChanged', (data) => { if (data) appState.runtime.statusReport = data })
  nativeBridge.on('device.authorizationChanged', (data) => { if (data) appState.runtime.deviceAuthorization = data })
  nativeBridge.on('recognition.statusChanged', (data) => { if (data) appState.runtime.recognitionEngine = data })
})

onShow(() => {})
onHide(() => {})
</script>

<style lang="scss">
@import './styles/global.scss';
</style>