import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('face enrollment waits for server success and defers local identity import to exit sync', async () => {
  const serviceSource = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const registrationStart = serviceSource.indexOf('async function registerBiometric')
  const registrationEnd = serviceSource.indexOf('async function searchEmployees', registrationStart)
  const registrationSource = serviceSource.slice(registrationStart, registrationEnd)

  assert.match(registrationSource, /createTemporaryFaceAiId\(employeeId, operationId\)/)
  assert.match(registrationSource, /createMultiFaceEnrollmentWorkflow\(/)
  assert.match(registrationSource, /progressCallback\('UPLOADING'\)/)
  assert.match(registrationSource, /appState\.faceSyncPending\.push/)
  assert.match(registrationSource, /appState\.faceSyncPending[\s\S]*item\?\.fileHash === fileHash/)
  assert.match(registrationSource, /await assertFaceOrganizationAuthorized\(\)[\s\S]*const operationId/)
  assert.doesNotMatch(registrationSource, /savePhoto: localStore\.saveFacePhoto/)
  assert.doesNotMatch(registrationSource, /saveBinding: localStore\.saveLocalFaceBinding/)
  assert.doesNotMatch(registrationSource, /faceIndex|localOnly|RETRY_WAIT|retryFacePhotoUpload/)
})

test('face sync applies only documented actions to the server and FaceAI identities', async () => {
  const serviceSource = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const syncStart = serviceSource.indexOf('async function applyFaceSyncItems')
  const syncEnd = serviceSource.indexOf('async function recoverInterruptedFaceEnrollments', syncStart)
  const syncSource = serviceSource.slice(syncStart, syncEnd)

  assert.match(syncSource, /normalizeFaceSyncItem\(rawItem\)/)
  assert.match(syncSource, /faceTemplateRemove\(faceAiId\)/)
  assert.match(syncSource, /faceTemplateImport\(\{[\s\S]*faceId: faceAiId/)
  assert.match(syncSource, /source: 'SERVER_SYNC'/)
})

test('unregistered recognition and face photo persistence distinguish an unbound organization', async () => {
  const [serviceSource, homeSource] = await Promise.all([
    readFile(new URL('../src/services/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8')
  ])
  const recognitionStart = serviceSource.indexOf('async function runRecognition')
  const recognitionEnd = serviceSource.indexOf('async function cancelRecognition', recognitionStart)
  const recognitionSource = serviceSource.slice(recognitionStart, recognitionEnd)
  const syncStart = serviceSource.indexOf('async function applyFaceSyncItems')
  const syncEnd = serviceSource.indexOf('async function recoverInterruptedFaceEnrollments', syncStart)
  const syncSource = serviceSource.slice(syncStart, syncEnd)

  assert.match(recognitionSource, /getDeviceOrganizationAuthorization\(\)/)
  assert.match(recognitionSource, /status: 'ORGANIZATION_UNAUTHORIZED'/)
  assert.match(recognitionSource, /message: ORGANIZATION_UNAUTHORIZED_MESSAGE/)
  assert.match(syncSource, /getDeviceOrganizationAuthorization\(\)\.catch\(\(\) => null\)/)
  assert.match(syncSource, /FACE_ENROLLMENT_ORGANIZATION_UNAUTHORIZED_MESSAGE/)
  assert.match(homeSource, /result\?\.status === 'ORGANIZATION_UNAUTHORIZED'/)
  assert.match(homeSource, /title: '未授权组织'/)
  assert.doesNotMatch(homeSource.slice(homeSource.indexOf("result?.status === 'ORGANIZATION_UNAUTHORIZED'"), homeSource.indexOf("result?.status === 'UNREGISTERED'")), /去录入/)
})

test('face sync skips disabled employees before reading a photo or importing a template', async () => {
  const serviceSource = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  const syncStart = serviceSource.indexOf('async function applyFaceSyncItems')
  const syncEnd = serviceSource.indexOf('async function recoverInterruptedFaceEnrollments', syncStart)
  const syncSource = serviceSource.slice(syncStart, syncEnd)
  const disabledStart = syncSource.indexOf('if (!employee.enabled)')
  const photoStart = syncSource.indexOf('const photoBase64')

  assert.ok(disabledStart >= 0)
  assert.ok(photoStart > disabledStart)
  assert.match(syncSource.slice(disabledStart, photoStart), /faceTemplateRemove\(faceAiId\)/)
  assert.match(syncSource.slice(disabledStart, photoStart), /skipped \+= 1/)
})

test('MQTT notification and five-minute fallback both trigger incremental pull', async () => {
  const serviceSource = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')

  assert.match(serviceSource, /'faceChanged'/)
  assert.match(serviceSource, /triggerFaceIncrementalSync\('mqtt:faceChanged'\)/)
  assert.match(serviceSource, /FACE_SYNC_INTERVAL_MS = 5 \* 60 \* 1000/)
  assert.match(serviceSource, /setInterval\([\s\S]*triggerFaceIncrementalSync\('periodic'\)/)
})

test('face registration UI reports server-confirmed success and defers local sync to admin exit', async () => {
  const panelSource = await readFile(new URL('../src/components/FaceRegisterPanel.vue', import.meta.url), 'utf8')

  assert.match(panelSource, /successText: '添加成功'/)
  assert.match(panelSource, /退出管理员模式后同步到本机/)
  assert.match(panelSource, /serverAccepted !== true/)
  assert.match(panelSource, /uni\.showToast\(\{ title: '添加人脸失败'/)
  assert.match(panelSource, /services\.listEmployeeFaces\(targetId\)/)
  assert.match(panelSource, /appState\.faceSyncPending/)
  assert.match(panelSource, /已添加 \$\{displayedFaceCount\.value\} 张（\$\{pendingFaces\.value\.length\} 张待退出后同步）/)
  assert.match(panelSource, /syncedHashes\.has\(String\(item\.fileHash\)\)/)
  assert.doesNotMatch(panelSource, /retryFacePhotoUpload|重试上传|已储存到本地|主图|扩展图/)
})

test('employee details read persisted face photos instead of binding metadata only', async () => {
  const [serviceSource, pageSource, modalSource] = await Promise.all([
    readFile(new URL('../src/services/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/pages/employees/employees.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/ModalShell.vue', import.meta.url), 'utf8')
  ])
  const listStart = serviceSource.indexOf('async function listEmployeeFaces')
  const listEnd = serviceSource.indexOf('async function sendEmployeeMutation', listStart)
  const listSource = serviceSource.slice(listStart, listEnd)

  assert.match(listSource, /localStore\.listFaceBindingsByEmployee\(employeeId\)/)
  assert.match(listSource, /localStore\.getFacePhotoByFaceId\(binding\.faceId\)/)
  assert.match(listSource, /return photos\.filter\(Boolean\)/)
  assert.match(pageSource, /暂未获取到人脸照片/)
  assert.match(pageSource, /data:\$\{mimeType\};base64,\$\{source\}/)
  assert.match(pageSource, /:src="facePhotoSource\(photo\)"/)
  assert.match(pageSource, /uni\.previewImage\(\{ current, urls \}\)/)
  assert.match(modalSource, /\.modal-mask \{[^}]*z-index:900/)
})
