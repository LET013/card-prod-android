const fail = (code, message) => {
  const error = new Error(message)
  error.code = code
  throw error
}

const APP_UPDATE_FILE_URL_PREFIX = 'https://card-test.quyohui.com/profile/'

function resolveAppUpdateUrl(value) {
  const apkFilePath = String(value?.apkFilePath || '').trim()
  if (!apkFilePath) return String(value?.apkUrl || '').trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(apkFilePath) || apkFilePath.includes('..')) {
    fail('APP_APK_FILE_PATH_INVALID', 'APK 文件路径无效')
  }
  return `${APP_UPDATE_FILE_URL_PREFIX}${apkFilePath}`
}

export function extractAppVersionCheckData(response) {
  const status = Number(response?.status || 0)
  if (status && (status < 200 || status >= 300)) {
    const body = response?.body
    fail(`HTTP_${status}`, body?.msg || body?.message || response?.error || `版本检查失败(${status})`)
  }
  const envelope = response?.body ?? response
  if (!envelope || typeof envelope !== 'object') {
    fail('APP_VERSION_RESPONSE_INVALID', '版本检查响应格式无效')
  }
  const code = envelope.code
  if (![200, '200'].includes(code)) {
    fail(`BACKEND_${code ?? 'UNKNOWN'}`, envelope.msg || envelope.message || '版本检查失败')
  }
  // 成功响应缺少 data 与 data:null 都表示当前没有可用更新。
  if (!Object.prototype.hasOwnProperty.call(envelope, 'data')) return null
  return envelope.data == null ? null : envelope.data
}

export function normalizeAppVersionInfo(value, options = {}) {
  if (value == null) return null
  if (!value || typeof value !== 'object') {
    fail('APP_VERSION_INFO_INVALID', 'APP 版本信息格式无效')
  }
  if (value.hasUpdate === false) return null
  if (value.hasUpdate !== true) {
    fail('APP_VERSION_FLAG_INVALID', 'APP 版本信息缺少 hasUpdate')
  }
  const versionCode = Number(value.versionCode)
  const versionId = Number(value.versionId || 0)
  const apkSize = Number(value.apkSize || 0)
  const versionName = String(value.versionName || '').trim()
  const apkUrl = resolveAppUpdateUrl(value)
  const apkMd5 = String(value.apkMd5 || '').trim().toLowerCase()
  const currentVersionCode = Number(options.currentVersionCode || 0)
  if (!Number.isInteger(versionCode) || versionCode < 1) {
    fail('APP_VERSION_CODE_INVALID', 'APP 目标版本号无效')
  }
  if (currentVersionCode > 0 && versionCode <= currentVersionCode) {
    fail('APP_VERSION_NOT_NEWER', '目标版本不高于当前版本')
  }
  if (!versionName) fail('APP_VERSION_NAME_MISSING', 'APP 版本名称为空')
  if (!/^https?:\/\//i.test(apkUrl)) fail('APP_APK_URL_INVALID', 'APK 下载地址无效')
  if (!/^[0-9a-f]{32}$/.test(apkMd5)) fail('APP_APK_MD5_INVALID', 'APK MD5 校验值无效')
  if (!Number.isFinite(apkSize) || apkSize < 0) fail('APP_APK_SIZE_INVALID', 'APK 文件大小无效')
  return {
    hasUpdate: true,
    forceUpdate: value.forceUpdate === true,
    versionId: Number.isFinite(versionId) ? versionId : 0,
    versionName,
    versionCode,
    apkUrl,
    apkSize,
    apkMd5,
    releaseNotes: String(value.releaseNotes || '')
  }
}

export function formatAppSize(bytes) {
  const size = Number(bytes || 0)
  if (!Number.isFinite(size) || size <= 0) return '大小未知'
  if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
