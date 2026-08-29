import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('root layout keeps using the full WebView width on wide displays', async () => {
  const source = await readSource('src/styles/global.scss')

  assert.match(source, /\.page-root\s*\{[\s\S]*?width:\s*100%/)
  assert.doesNotMatch(source, /\.page-root\s*\{\s*max-width:/)
  assert.doesNotMatch(source, /@media\s*\(min-width:\s*768px\)[\s\S]*?\.page-root\s*\{\s*max-width:/)
})

test('admin shell uses the whole landscape viewport with consistent wide-screen gutters', async () => {
  const [adminSource, headerSource] = await Promise.all([
    readSource('src/pages/admin/admin.vue'),
    readSource('src/components/AdminHeader.vue')
  ])

  assert.match(adminSource, /\.admin-dashboard\s*\{[^}]*height:100dvh/)
  assert.match(adminSource, /\.tab-bar\s*\{[^}]*min\([^}]*1520px/)
  assert.match(adminSource, /\.tab-content\s*\{[^}]*min\([^}]*1520px/)
  assert.match(headerSource, /\.admin-header-inner\s*\{[^}]*max-width:\s*1520px/)
})

test('admin actions use larger touch targets on the cabinet landscape screen only', async () => {
  const source = await readSource('src/styles/global.scss')

  assert.match(source, /@media \(min-width: 960px\) and \(orientation: landscape\)/)
  assert.match(source, /\.page-root \.btn-grad,[\s\S]*?height: 50px/)
  assert.match(source, /\.page-root \.btn-sec,[\s\S]*?\.page-root \.role-toggle,[\s\S]*?height: 42px/)
  assert.match(source, /\.page-root \.drawer-close \{ width: 40px; height: 40px; \}/)
})

test('empty slot details hide ambiguous voltage and current without rewriting raw telemetry', async () => {
  const source = await readSource('src/components/SlotDetailModal.vue')

  assert.match(source, /const status=String\(props\.slot\.status\|\|''\)\.trim\(\)\.toUpperCase\(\)/)
  assert.match(source, /if\(status!==\'EMPTY\'\)\{[\s\S]*label:'电压'[\s\S]*label:'电流'/)
  assert.doesNotMatch(source, /props\.slot\.(voltage|current)\s*=(?!=)/)
})

test('unit management expands grouped slots with summaries and no internal implementation wording', async () => {
  const source = await readSource('src/pages/feature/feature.vue')

  assert.doesNotMatch(source, /按本机 SQLite 卡槽快照分组展示|单板版本尚无客户端数据来源|板卡版本：未接入/)
  assert.match(source, /@click="toggleUnit\(unit\.id\)"/)
  assert.match(source, /class="unit-summary"[\s\S]*卡槽总数[\s\S]*有卡[\s\S]*空卡[\s\S]*充电中/)
  assert.match(source, /v-if="expandedUnitIds\.has\(unit\.id\)" class="unit-slot-list"/)
  assert.match(source, /v-for="slot in unit\.slots"/)
  assert.match(source, /暂无单元数据/)
})

test('face enrollment history uses a non-slot target and exposes its own filter', async () => {
  const source = await readSource('src/pages/feature/feature.vue')

  assert.match(source, /\{label:'人脸录入',value:'FACE_ENROLLMENT'\}/)
  assert.match(source, /FACE_ENROLLMENT:'人脸信息'/)
})

test('status modals use the full width of their modal cards', async () => {
  const [authorizationSource, adminSource] = await Promise.all([
    readSource('src/components/AuthorizationPanel.vue'),
    readSource('src/pages/admin/admin.vue')
  ])

  assert.match(authorizationSource, /\.authorization-panel\s*\{\s*width:100%/)
  assert.doesNotMatch(authorizationSource, /\.authorization-panel\s*\{\s*width:calc\(100vw/)
  assert.match(adminSource, /\.mqtt-status-panel\s*\{\s*width:100%/)
})

test('history transient and empty states align with the full filter width', async () => {
  const source = await readSource('src/pages/feature/feature.vue')

  assert.equal((source.match(/class="status-detail history-status"/g) || []).length, 3)
  assert.match(source, /\.history-status\s*\{\s*width:100%/)
})

test('card status fault legend occupies the reserved right footer column', async () => {
  const source = await readSource('src/pages/card-status/card-status.vue')

  assert.match(source, /\.footer-legend\.right\s*\{\s*grid-column:\s*3;/)
  assert.match(source, /if \(status === SLOT_STATUS\.LOADING\) return null/)
  assert.match(source, /legendItem\('full', SLOT_STATUS\.FULL\)/)
  assert.match(source, /legendItem\('unknown', SLOT_STATUS\.UNKNOWN\)/)
  assert.match(source, /--legend-font-size:\s*clamp\(8px, 2vw, 16px\)/)
})

test('global notices float over the cabinet without moving its grid', async () => {
  const [homeSource, noticeSource] = await Promise.all([
    readSource('src/pages/index/index.vue'),
    readSource('src/components/GlobalNoticeBar.vue')
  ])

  assert.match(homeSource, /<GlobalNoticeBar\s*\/>/)
  assert.doesNotMatch(homeSource, /cache-warning|cachedSnapshotNotice/)
  assert.match(homeSource, /上次缓存状态/)
  assert.match(noticeSource, /position:\s*fixed/)
  assert.match(noticeSource, /z-index:\s*2000/)
  assert.match(noticeSource, /setTimeout\(/)
  assert.doesNotMatch(noticeSource, /margin:\s*0\s+0\s+8px/)
})

test('startup hydrates cached settings while home leaves configuration to the completed bootstrap flow', async () => {
  const [mainSource, homeSource, serviceSource] = await Promise.all([
    readSource('src/main.js'),
    readSource('src/pages/index/index.vue'),
    readSource('src/services/index.js')
  ])

  assert.match(mainSource, /services\.loadSettings\(\{ remote: false \}\)/)
  assert.doesNotMatch(homeSource, /services\.loadSettings\(/)
  assert.match(homeSource, /restoreCachedSlotsWhenSerialUnavailable/)
  assert.match(serviceSource, /if \(options\.remote === false\) \{[\s\S]*return cachedSettings/)
})

test('cabinet grids render immediately while cached slots hydrate in the background', async () => {
  const [homeSource, statusSource] = await Promise.all([
    readSource('src/pages/index/index.vue'),
    readSource('src/pages/card-status/card-status.vue')
  ])

  assert.match(homeSource, /<CabinetSlotGrid/)
  assert.doesNotMatch(homeSource, /CabinetSlotSkeleton|slotGridVisible|slotSkeletonVisible|revealSlotGrid/)
  assert.match(homeSource, /restoreCachedSlotsWhenSerialUnavailable/)
  assert.doesNotMatch(statusSource, /await services\.loadCachedSlots\(\)/)
  assert.match(statusSource, /services\.loadCachedSlots\(\)\.then\(/)
})

test('home turns only unresolved first-screen slots from loading into unknown without changing serial behavior', async () => {
  const source = await readSource('src/pages/index/index.vue')

  assert.match(source, /const INITIAL_SLOT_STATUS_TIMEOUT_MS = 8000/)
  assert.match(source, /const markUnresolvedSlotsUnknown = \(\) => \{[\s\S]*?slot\?\.status[\s\S]*?SLOT_STATUS\.LOADING[\s\S]*?upsertSlotProjection\(\{ \.\.\.slot, status: SLOT_STATUS\.UNKNOWN \}\)/)
  assert.match(source, /initialSlotStatusTimer = setTimeout\(markUnresolvedSlotsUnknown, INITIAL_SLOT_STATUS_TIMEOUT_MS\)/)
  assert.match(source, /\(\) => appState\.slots\.length,[\s\S]*?scheduleInitialSlotStatusTimeout,[\s\S]*?immediate: true/)
  assert.match(source, /clearInitialSlotStatusTimer\(\)[\s\S]*?stopInitialSlotStatusWatch\?\.\(\)/)
  assert.doesNotMatch(source, /serial\.startPolling|serial\.reconnect|cacheSlotsSnapshot\(appState\.slots/)
})

test('cabinet pages reuse the stable slot projection instead of re-sorting every status batch', async () => {
  const [homeSource, statusSource, gridSource] = await Promise.all([
    readSource('src/pages/index/index.vue'),
    readSource('src/pages/card-status/card-status.vue'),
    readSource('src/components/CabinetSlotGrid.vue')
  ])

  for (const source of [homeSource, statusSource]) {
    assert.match(source, /const displaySlots = computed\(\(\) => appState\.slots\)/)
    assert.match(source, /summarizeSlotStatuses/)
    assert.doesNotMatch(source, /normalizeSlotsProjection/)
  }
  assert.doesNotMatch(gridSource, /v-memo=/)
  assert.match(gridSource, /const orderedSlots = computed\(\(\) => \[\.\.\.props\.slots\]\)/)
  assert.doesNotMatch(gridSource, /\[\.\.\.props\.slots\]\.sort/)
})

test('shared cabinet grid paints with a remembered viewport before navigation measurement completes', async () => {
  const gridSource = await readSource('src/components/CabinetSlotGrid.vue')

  assert.match(gridSource, /let lastMeasuredViewport = \{ width: 0, height: 0 \}/)
  assert.match(gridSource, /const viewport = ref\(initialViewport\(\)\)/)
  assert.match(gridSource, /lastMeasuredViewport = \{ width: nextWidth, height: nextHeight \}/)
  assert.match(gridSource, /const loadWindowViewport = \(\) => \{\s*setViewport\(readWindowViewport\(\)\)/)
})

test('tablet admin pages keep readable role and user drawers', async () => {
  const [roleSource, credentialSource] = await Promise.all([
    readSource('src/pages/admin/role-manage.vue'),
    readSource('src/pages/admin/credential-manage.vue')
  ])

  assert.match(roleSource, /\.role-panel\s*\{[^}]*width:min\(100%,1180px\)/)
  assert.match(roleSource, /\.role-drawer\s*\{[^}]*width:min\(480px,88vw\)/)
  assert.match(roleSource, /\.permission-view-list\s*\{[^}]*grid-template-columns:1fr 1fr/)
  assert.match(roleSource, /@media \(max-width:760px\)[\s\S]*\.permission-view-list,[\s\S]*grid-template-columns:1fr/)
  assert.match(credentialSource, /\.credential-panel\s*\{[^}]*width:min\(100%,1180px\)/)
  assert.match(credentialSource, /\.credential-drawer\s*\{[^}]*width:min\(480px,88vw\)[^}]*height:100%/)
  assert.match(credentialSource, /\.list-row-sub\s*\{[^}]*flex-wrap:wrap/)
  assert.match(credentialSource, /\.drawer-actions\s*\{[^}]*align-items:center/)
  assert.match(credentialSource, /\.detail-grid\s*\{[^}]*grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/)
  assert.match(credentialSource, /@media \(max-width:760px\)[\s\S]*\.detail-grid\s*\{[^}]*grid-template-columns:1fr/)
  assert.match(credentialSource, /\.role-chips\s*\{[^}]*flex:1 1 100%/)
})

test('device settings uses one touch-friendly bounded scroll area on tablet and phone', async () => {
  const [shellSource, configSource, adminSource] = await Promise.all([
    readSource('src/components/ModalShell.vue'),
    readSource('src/components/DeviceConfigPanel.vue'),
    readSource('src/pages/admin/admin.vue')
  ])

  assert.match(shellSource, /\.modal-card\.modal-full\s*\{[^}]*1180px[^}]*height:min\(820px,calc\(100vh - 40px\)\)[^}]*overflow:hidden/)
  assert.match(configSource, /<view class="config-scroll">/)
  assert.doesNotMatch(configSource, /<scroll-view class="config-scroll"/)
  assert.match(configSource, /\.config-scroll\s*\{[^}]*height:\s*min\(88vh, 820px\)[^}]*overflow-y:\s*auto[^}]*touch-action:\s*pan-y/)
  assert.match(adminSource, /<DeviceConfigPanel class="device-config-panel-modal"/)
  assert.match(configSource, /\.config-scroll\.device-config-panel-modal\s*\{[^}]*height:\s*100%[^}]*max-height:\s*100%/)
  assert.match(configSource, /@media\(max-width:640px\)[\s\S]*\.config-scroll\s*\{[^}]*height:calc\(100vh - 40px\)[^}]*\}[\s\S]*\.action-bar\s*\{[^}]*position:\s*static/)
  assert.match(configSource, /@supports \(height: 100dvh\)/)
  assert.match(configSource, /\.config-switcher\s*\{[^}]*repeat\(4, minmax\(0, 1fr\)\)/)
})

test('employee editor keeps close and actions visible while fields scroll on compact screens', async () => {
  const source = await readSource('src/pages/employees/employees.vue')

  assert.match(source, /size-class="modal-wide employee-editor-modal"/)
  assert.match(source, /:deep\(\.employee-editor-modal\)\s*\{[^}]*height:min\(760px,calc\(100vh - 40px\)\)[^}]*overflow:hidden/)
  assert.match(source, /\.employee-editor\s*\{[^}]*height:100%[^}]*max-height:100%[^}]*display:flex[^}]*overflow:hidden/)
  assert.match(source, /\.editor-grid\s*\{[^}]*flex:1 1 auto[^}]*min-height:0[^}]*overflow-y:auto/)
  assert.match(source, /\.editor-actions\s*\{[^}]*flex:0 0 auto/)
})

test('real-device management and cabinet controls keep enlarged readable sizing', async () => {
  const [roleSource, credentialSource, employeeSource, slotSource, gridSource, headerSource, passwordSource, adminSource, legendSource, menuCardSource] = await Promise.all([
    readSource('src/pages/admin/role-manage.vue'),
    readSource('src/pages/admin/credential-manage.vue'),
    readSource('src/pages/employees/employees.vue'),
    readSource('src/components/SlotCard.vue'),
    readSource('src/components/CabinetSlotGrid.vue'),
    readSource('src/components/CabinetHeader.vue'),
    readSource('src/components/PasswordModal.vue'),
    readSource('src/pages/admin/admin.vue'),
    readSource('src/components/StatusLegend.vue'),
    readSource('src/components/AdminMenuCard.vue')
  ])

  assert.match(roleSource, /\.list-row\s*\{[^}]*min-height:\s*92px/)
  assert.match(roleSource, /\.drawer-actions > button\s*\{[^}]*min-height:\s*54px[^}]*font-size:\s*17px/)
  assert.match(roleSource, /\.section-title\s*\{[^}]*font-size:\s*18px/)
  assert.match(credentialSource, /\.list-row\s*\{[^}]*min-height:\s*80px/)
  assert.match(credentialSource, /\.drawer-actions \.btn-grad\s*\{[^}]*height:\s*54px[^}]*font-size:\s*17px/)
  assert.match(credentialSource, /\.section-title-bar text\s*\{[^}]*font-size:\s*17px/)
  assert.match(employeeSource, /\.editor-field input\s*\{[^}]*height:\s*54px[^}]*font-size:\s*16px/)
  assert.doesNotMatch(slotSource, /卡号 \{\{ cardNumber \}\}/)
  assert.doesNotMatch(slotSource, /\.slot-card::after/)
  assert.match(slotSource, /\.slot-summary-label\s*\{[^}]*font-size:var\(--slot-status-size/)
  assert.match(gridSource, /gridTemplateRows: cardMetrics\.value\.isCabinetHalfSplit/)
  assert.match(gridSource, /: `repeat\(\$\{cardMetrics\.value\.rows\}, minmax\(0, 1fr\)\)`/)
  assert.match(headerSource, /\.user-button\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/)
  assert.match(passwordSource, /输入您的密码进行登陆，设备管理页面/)
  assert.match(passwordSource, /\.password-help\s*\{[^}]*font-size:\s*clamp\(18px, 2\.5vw, 23px\)[^}]*font-weight:\s*400/)
  assert.match(legendSource, /--legend-font-size:\s*clamp\(14px, 2\.35vw, 21px\)/)
  assert.match(menuCardSource, /@media \(min-width:\s*900px\)[\s\S]*?\.size-dashboard\.layout-tile \.menu-label\s*\{[^}]*font-size:\s*25px/)
  assert.match(adminSource, /\.tab-item\s*\{[^}]*height:\s*60px/)
  assert.match(adminSource, /\.tab-icon\s*\{[^}]*width:\s*24px[^}]*height:\s*24px/)
  assert.match(adminSource, /\.tab-label\s*\{[^}]*font-size:\s*16px/)
})

test('cabinet connection dots and administrator avatar remain visible on older WebViews', async () => {
  const [headerSource, passwordSource] = await Promise.all([
    readSource('src/components/CabinetHeader.vue'),
    readSource('src/components/PasswordModal.vue')
  ])

  assert.match(headerSource, /\.brand-title-row\s*\{[^}]*gap:\s*3px/)
  assert.match(headerSource, /\.status-dot \+ \.status-dot\s*\{\s*margin-left:\s*3px/)
  assert.match(headerSource, /@media \(min-width:900px\) and \(orientation:landscape\)[\s\S]*?\.status-dot \+ \.status-dot\s*\{\s*margin-left:3px/)
  assert.match(passwordSource, /\.person-icon \.head\s*\{[^}]*width:\s*32%[^}]*height:\s*32%/)
  assert.doesNotMatch(passwordSource, /\.person-icon \.head\s*\{[^}]*aspect-ratio/)
})

test('admin actions use semantic Figma-style icons with filled circular badges', async () => {
  const [adminSource, menuCardSource, iconSource] = await Promise.all([
    readSource('src/pages/admin/admin.vue'),
    readSource('src/components/AdminMenuCard.vue'),
    readSource('src/components/IconGlyph.vue')
  ])

  assert.match(menuCardSource, /\.icon-circle\s*\{[^}]*border-radius:\s*50%/)
  assert.doesNotMatch(adminSource, /variant="line"/)
  assert.doesNotMatch(adminSource, /tab-overview|activeTabMeta|layout="row"/)
  assert.match(menuCardSource, /showDesc\s*&&\s*desc/)
  assert.match(menuCardSource, /\.menu-card\.size-dashboard\.layout-tile\s*\{[^}]*aspect-ratio:\s*1\.1/)
  assert.match(menuCardSource, /@media \(min-width:\s*900px\)\s*\{[\s\S]*?\.menu-card\.size-dashboard\.layout-tile\s*\{[^}]*aspect-ratio:\s*1\.38/)
  assert.match(menuCardSource, /@media \(min-width:\s*900px\)\s*\{[\s\S]*?\.size-dashboard\.layout-tile \.icon-circle\s*\{[^}]*width:\s*56px;[^}]*height:\s*56px;[^}]*padding:\s*6px/)
  assert.match(adminSource, /\.sys-grid\s*\{[^}]*grid-template-columns:repeat\(3,minmax\(0,1fr\)\)/)
  assert.doesNotMatch(adminSource, /\.status-grid\s*\{[^}]*grid-template-columns/)
  assert.equal((adminSource.match(/size="dashboard"/g) || []).length, 9)
  assert.equal((adminSource.match(/:show-desc="false"/g) || []).length, 9)

  const iconNames = [
    'role-manage',
    'user-manage',
    'password-change',
    'face-register',
    'employee-manage',
    'unit-manage',
    'history-manage',
    'device-settings',
    'restart-app',
    'authorization',
    'board-upgrade',
    'app-upgrade',
    'eject',
    'hardware',
    'work-card-upgrade',
    'command-check',
    'main-board',
    'tab-status',
    'card-slot-status',
    'mqtt-status'
  ]
  for (const iconName of iconNames) {
    assert.match(adminSource, new RegExp(`icon: \'${iconName}\'|icon="${iconName}"`))
    assert.match(iconSource, new RegExp(`name===\'${iconName}\'`))
  }

  assert.doesNotMatch(adminSource, /icon="lock"|icon="cabinet-status"|icon:\s*'face'/)
  assert.match(iconSource, /name==='role-manage'[\s\S]*?<circle[^>]*cx="6"[^>]*cy="12"[\s\S]*?<circle[^>]*cx="17"[^>]*cy="6"[\s\S]*?<circle[^>]*cx="17"[^>]*cy="18"/)
  assert.match(iconSource, /name==='eject'[\s\S]*?<rect[^>]*y="5"[^>]*height="5\.5"[\s\S]*?<rect[^>]*y="13\.5"[^>]*height="5\.5"[\s\S]*?M7\.5 16\.25h3M15\.5 16\.25h1/)
  assert.match(iconSource, /name==='board-upgrade'[\s\S]*?M9 5H7\.5[\s\S]*?M12 8v7M9 12l3 3 3-3/)
  assert.match(iconSource, /name==='app-upgrade'[\s\S]*?<circle cx="18\.5" cy="17\.5" r="2\.5"/)
  assert.match(iconSource, /name==='hardware'[\s\S]*?<rect x="13" y="6" width="4\.5" height="2\.5"/)
  assert.match(iconSource, /name==='work-card-upgrade'[\s\S]*?<rect x="18\.5" y="7\.5" width="2\.5" height="8"/)
  assert.match(iconSource, /name==='command-check'[\s\S]*?<circle cx="18\.5" cy="17\.5" r="2\.5"/)
  assert.match(iconSource, /name==='main-board'[\s\S]*?M2\.5 8h3M2\.5 12h3M2\.5 16h3M18\.5 8h3/)
  assert.match(iconSource, /name==='history-manage'[\s\S]*?M15 7c3 1 5 3\.8 5 7v4M17 15\.5l3 3 3-3/)
  assert.match(iconSource, /name==='card-slot-status'[\s\S]*?M20 7\.5c0 3\.4-4 6\.8-4 6\.8s-4-3\.4-4-6\.8[\s\S]*?<circle cx="16" cy="7\.5"/)
  assert.match(iconSource, /name==='mqtt-status'[\s\S]*?<circle cx="14\.5" cy="10" r="1" fill="currentColor"[\s\S]*?M17 4a7 7/)
  assert.match(iconSource, /name==='board-upgrade'[\s\S]*?scale\(1\.074 \.967\)/)
  assert.match(iconSource, /name==='hardware'[\s\S]*?scale\(1\.225 1\.012\)/)
  assert.match(iconSource, /name==='restart-app'[\s\S]*?scale\(\.822\)/)
  const restartIcon = iconSource.match(/name==='restart-app'([\s\S]*?)<\/template>/)?.[1] || ''
  assert.match(restartIcon, /M12 3v3M12 18v3/)
  assert.doesNotMatch(restartIcon, /<circle/)
  assert.match(iconSource, /name==='tab-status'[\s\S]*?name==='card-slot-status'/)
  assert.doesNotMatch(adminSource, /指纹注册|icon:\s*'fingerprint'/)
})
