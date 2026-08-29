import assert from 'node:assert/strict'
import test from 'node:test'

import {
  extractAppVersionCheckData,
  formatAppSize,
  normalizeAppVersionInfo
} from '../src/services/appUpdateWorkflow.js'
import {
  normalizeAppChannelId,
  resolveAppChannelLabel
} from '../src/constants/appChannel.js'

const versionInfo = {
  hasUpdate: true,
  forceUpdate: false,
  versionId: 5,
  versionName: '1.2.0',
  versionCode: 3,
  apkUrl: 'https://example.com/app.apk',
  apkSize: 15728640,
  apkMd5: 'a1b2c3d4e5f678901234567890abcdef',
  releaseNotes: '修复问题'
}

test('accepts V4.2 version data and preserves every documented field', () => {
  const raw = extractAppVersionCheckData({ status: 200, body: { code: 200, msg: 'success', data: versionInfo } })
  assert.deepEqual(normalizeAppVersionInfo(raw, { currentVersionCode: 1 }), versionInfo)
})

test('builds the APK download URL from the backend apkFilePath', () => {
  const result = normalizeAppVersionInfo({
    ...versionInfo,
    apkUrl: 'https://stale.example.com/old.apk',
    apkFilePath: 'app-version/apk_affc281e_card-cabinet-v1.0.2-debug.apk'
  }, { currentVersionCode: 1 })

  assert.equal(
    result.apkUrl,
    'https://card-test.quyohui.com/profile/app-version/apk_affc281e_card-cabinet-v1.0.2-debug.apk'
  )
})

test('treats a null V4.2 data field as no update', () => {
  assert.equal(extractAppVersionCheckData({ status: 200, body: { code: 200, data: null } }), null)
  assert.equal(normalizeAppVersionInfo(null), null)
})

test('treats a successful response without data as no update', () => {
  assert.equal(extractAppVersionCheckData({ status: 200, body: { code: 200, msg: '操作成功' } }), null)
})

test('rejects incomplete or unsafe APK metadata before native download', () => {
  assert.throws(() => normalizeAppVersionInfo({ ...versionInfo, apkMd5: '' }), /MD5/)
  assert.throws(() => normalizeAppVersionInfo({ ...versionInfo, apkUrl: 'file:///tmp/app.apk' }), /下载地址/)
  assert.throws(() => normalizeAppVersionInfo({ ...versionInfo, apkFilePath: '../app.apk' }), /文件路径/)
  assert.throws(() => normalizeAppVersionInfo(versionInfo, { currentVersionCode: 3 }), /不高于当前版本/)
})

test('leaves the APP channel to Android package metadata', () => {
  assert.equal(normalizeAppChannelId(''), '')
  assert.equal(resolveAppChannelLabel(''), '未读取')
  assert.equal(resolveAppChannelLabel('official'), 'official')
  assert.equal(formatAppSize(15728640), '15.0 MB')
})
