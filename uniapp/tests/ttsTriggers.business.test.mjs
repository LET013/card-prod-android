import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('home page announces exactly once when the target card slot is presented', async () => {
  const source = await readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8')
  assert.match(source, /state === 'CARD_PRESENTED_ANNOUNCEMENT'/)
  assert.match(source, /hasPhysicalSlotNumber/)
  assert.match(source, /!takeCardSuccessAnnounced/)
  assert.match(source, /takeCardSuccessAnnounced = true[\s\S]*?services\.announceTakeCardSuccess\(slotNumber\)/)
})

test('take-card workflow sends the speech trigger before durable reporting work', async () => {
  const source = await readFile(new URL('../src/services/takeCardWorkflow.js', import.meta.url), 'utf8')
  assert.match(source, /progress\(\{ \.\.\.cardPresentedProgress, state: 'CARD_PRESENTED_ANNOUNCEMENT' \}\)[\s\S]*?await persist\(operationId, operationIdentity, \{[\s\S]*?progress\(cardPresentedProgress\)/)
})

test('home page only announces take failure before a success announcement', async () => {
  const source = await readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8')
  assert.match(source, /presentation\.status === 'TAKE_ERROR' \|\| presentation\.status === 'NO_CARD'/)
  assert.match(source, /&& !takeCardSuccessAnnounced\) \{[\s\S]*?services\.announceTakeCardFailure\(\)/)
})

test('administrator and remote open paths announce after serial command acceptance', async () => {
  const source = await readFile(new URL('../src/services/index.js', import.meta.url), 'utf8')
  assert.match(source, /function announceAdminCardOpened\(slotNumber, \{ flush = true \} = \{\}\)/)
  assert.match(source, /await sendDoorCommandAndWaitAck\(address, true\)[\s\S]*?announceAdminCardOpened\(address\)/)
  assert.match(source, /await sendDoorCommandAndWaitAck\(slotNumber, false\)[\s\S]*?announceAdminCardOpened\(slotNumber, \{ flush: parent\.ttsFlush !== false \}\)/)
  assert.match(source, /ttsFlush: false/)
})

test('administrator welcome waits for successful navigation to the management home', async () => {
  const source = await readFile(new URL('../src/pages/index/index.vue', import.meta.url), 'utf8')
  assert.match(source, /url: target,[\s\S]*?success: \(\) => \{[\s\S]*?target === '\/pages\/admin\/admin'[\s\S]*?announceAdminWelcome\(session\?\.credentialLabel\)/)
})

test('forced initial-password change announces when its page is entered, without replay after saving', async () => {
  const source = await readFile(new URL('../src/pages/admin/change-password.vue', import.meta.url), 'utf8')
  assert.match(source, /onMounted\(\(\) => \{[\s\S]*?if \(forceMode\.value\) services\.announceAdminWelcome\(appState\.session\?\.credentialLabel\)/)
  assert.doesNotMatch(source, /url: '\/pages\/admin\/admin',[\s\S]*?announceAdminWelcome/)
})
