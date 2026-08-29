import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  SLOT_OPERATION_EFFECT,
  isTerminalSlotOperationEffect,
  resolveSlotOperationEffect
} from '../src/state/slotOperationEffect.js'
import {
  createTakeCardResultPresentation,
  TAKE_CARD_RESULT
} from '../src/state/takeCardResult.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

test('keeps selection and serial execution silent and maps only final outcomes', () => {
  assert.equal(resolveSlotOperationEffect('VALIDATED'), '')
  assert.equal(resolveSlotOperationEffect('QUEUED'), '')
  assert.equal(resolveSlotOperationEffect('SERIAL_SENT'), '')
  assert.equal(resolveSlotOperationEffect('BOARD_ACKED'), '')
  assert.equal(resolveSlotOperationEffect('PHYSICAL_PENDING'), '')
  assert.equal(resolveSlotOperationEffect('PHYSICAL_CONFIRMED'), SLOT_OPERATION_EFFECT.SUCCESS)
  assert.equal(resolveSlotOperationEffect('FAILED'), SLOT_OPERATION_EFFECT.FAILURE)
  assert.equal(resolveSlotOperationEffect('TIMED_OUT'), SLOT_OPERATION_EFFECT.FAILURE)
  assert.equal(resolveSlotOperationEffect('SELECTING'), '')
})

test('keeps board ACK non-terminal and only final physical/failure effects eligible for auto-clear', () => {
  assert.equal(isTerminalSlotOperationEffect(resolveSlotOperationEffect('BOARD_ACKED')), false)
  assert.equal(isTerminalSlotOperationEffect(resolveSlotOperationEffect('PHYSICAL_CONFIRMED')), true)
  assert.equal(isTerminalSlotOperationEffect(resolveSlotOperationEffect('FAILED')), true)
  assert.equal(resolveSlotOperationEffect('REPORT_PENDING'), '')
  assert.equal(resolveSlotOperationEffect('COMPLETED'), '')
  assert.equal(resolveSlotOperationEffect('UNKNOWN_STATE'), '')
})

test('formats the four customer-approved take-card results without an intermediate prompt', () => {
  assert.deepEqual(createTakeCardResultPresentation({
    outcome: TAKE_CARD_RESULT.SUCCESS,
    slotNumber: 2
  }), {
    status: 'SUCCESS',
    slotNumber: 2,
    effect: 'success',
    message: '卡柜02取卡成功，请及时取走'
  })
  assert.equal(createTakeCardResultPresentation({
    outcome: TAKE_CARD_RESULT.SUCCESS,
    slotNumber: 2,
    batteryPercent: 28
  }).message, '卡柜02取卡成功，当前电量28%，建议使用后及时归还充电')
  assert.equal(createTakeCardResultPresentation({
    outcome: TAKE_CARD_RESULT.FAILURE,
    slotNumber: 2
  }).message, '卡柜02出卡失败，请稍后重试或联系管理员')
  assert.deepEqual(createTakeCardResultPresentation({ outcome: TAKE_CARD_RESULT.NO_CARD }), {
    status: 'NO_CARD',
    slotNumber: null,
    effect: '',
    message: '当前暂无可用工作卡，请联系管理员'
  })
})

test('keeps the cabinet visible and shows the target slot promptly after door-open confirmation', async () => {
  const [homeSource, recognitionSource, shellSource, slotCardSource] = await Promise.all([
    readFile(path.join(projectRoot, 'src/pages/index/index.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/components/RecognitionModal.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/components/ModalShell.vue'), 'utf8'),
    readFile(path.join(projectRoot, 'src/components/SlotCard.vue'), 'utf8')
  ])

  assert.match(homeSource, /:operation-mode="recognition\.operationMode"/)
  assert.match(homeSource, /:slot-number="recognition\.slotNumber"/)
  assert.match(homeSource, /recognition\.visible = true[\s\S]*recognition\.status = 'FACE_VERIFIED'[\s\S]*services\.takeCard\(result, applyTakeProgress\)/)
  assert.match(homeSource, /state === 'CARD_PRESENTED'[\s\S]*presentCardPresented/)
  assert.match(homeSource, /state === 'PHYSICAL_CONFIRMED'[\s\S]*TAKE_CARD_RESULT\.SUCCESS/)
  assert.match(homeSource, /TAKE_FAILURE_STATES\.has\(state\)[\s\S]*TAKE_CARD_RESULT\.FAILURE/)
  assert.match(homeSource, /scheduleRecognitionClose\(3000\)/)
  assert.doesNotMatch(homeSource, /正在评估卡槽|已选定.*取卡指令|正在执行取卡/)
  assert.doesNotMatch(homeSource, /等待卡槽变为空卡/)
  assert.match(recognitionSource, /operationMode \? 'modal-operation-mask' : ''/)
  assert.match(recognitionSource, /v-if="operationMode" class="operation-progress"/)
  assert.match(recognitionSource, /props\.status==='SUCCESS'\) return '取卡成功'/)
  assert.match(recognitionSource, /props\.status==='NO_CARD'\) return '暂无可用工作卡'/)
  assert.match(recognitionSource, /overflow:visible; text-overflow:clip; white-space:normal; overflow-wrap:anywhere; word-break:break-word; -webkit-line-clamp:unset/)
  assert.match(shellSource, /modal-operation-card[^}]*width:min\(calc\(100vw - 24px\),520px\)[^}]*max-height:none[^}]*overflow:visible/)
  assert.match(shellSource, /\.modal-mask\.modal-operation-mask[^}]*background:transparent[^}]*pointer-events:none/)
  assert.match(slotCardSource, /props\.operationEffect === 'success'[\s\S]*'#20c878'/)
  assert.match(slotCardSource, /props\.operationEffect === 'failure'[\s\S]*'#ef4059'/)
  assert.doesNotMatch(slotCardSource, /slot-operation--targeted|slot-operation--dispatched/)
  assert.doesNotMatch(slotCardSource, /@keyframes slot-(?:success|failure)-pulse[\s\S]*?transform:\s*scale/)
  assert.doesNotMatch(slotCardSource, /slot-operation-beacon/)
})
