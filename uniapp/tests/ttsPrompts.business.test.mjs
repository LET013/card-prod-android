import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildAdminCardOpenedPrompt,
  buildAdminWelcomePrompt,
  buildTakeCardFailurePrompt,
  buildTakeCardSuccessPrompt
} from '../src/services/ttsPrompts.js'

test('take-card success prompt uses the physical slot number without UI zero padding', () => {
  assert.equal(buildTakeCardSuccessPrompt(3), '取卡成功！3号卡槽，请取走您的卡')
})

test('take-card success prompt is not sent when no physical slot number is available', () => {
  assert.equal(buildTakeCardSuccessPrompt('*'), '')
  assert.equal(buildTakeCardSuccessPrompt(0), '')
})

test('take-card failure prompt remains a clear generic recovery instruction', () => {
  assert.equal(buildTakeCardFailurePrompt(), '取卡失败，请联系工作人员处理')
})

test('administrator card-open prompt uses the accepted physical slot number', () => {
  assert.equal(buildAdminCardOpenedPrompt(6), '6号卡槽已开卡')
  assert.equal(buildAdminCardOpenedPrompt('*'), '')
})

test('administrator welcome prompt uses the logged-in credential label with a safe fallback', () => {
  assert.equal(buildAdminWelcomePrompt('本地管理员'), '欢迎本地管理员进入管理员模式')
  assert.equal(buildAdminWelcomePrompt(''), '欢迎管理员进入管理员模式')
})

test('prompt templates can be supplied centrally without changing flow logic', () => {
  assert.equal(buildTakeCardSuccessPrompt(12, { takeCardSuccess: '请从{slotNumber}号卡槽取卡' }), '请从12号卡槽取卡')
})
