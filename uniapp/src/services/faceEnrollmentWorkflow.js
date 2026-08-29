import { FACE_PHOTO_MAX_BYTES } from './localStore.js'

export const FACE_RECORD_CREATE_PATH = '/api/v1/employee/face'
export const FACE_IMAGE_UPLOAD_PATH = '/api/v1/employee/face/image'

const HEX_SHA256 = /^[a-f0-9]{64}$/
const BACKEND_ID_PATTERN = /^\d+$/

const workflowError = (code, message, data = null) => {
  const error = new Error(message)
  error.code = code
  error.data = data
  return error
}

const normalizeBackendId = (value, field) => {
  const normalized = String(value ?? '').trim()
  if (!BACKEND_ID_PATTERN.test(normalized)) {
    const errorField = field.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()
    throw workflowError(`INVALID_${errorField}`, `${field} 必须是后台数字 ID`)
  }
  return normalized
}

const normalizePhotoBase64 = (rawValue) => {
  let value = String(rawValue || '').trim()
  const commaIndex = value.indexOf(',')
  if (value.startsWith('data:') && commaIndex >= 0) value = value.slice(commaIndex + 1)
  value = value.replace(/\s+/g, '')
  if (!value || !/^[A-Za-z0-9+/]*={0,2}$/.test(value) || value.length % 4 === 1) {
    throw workflowError('INVALID_FACE_PHOTO_BASE64', '人脸照片内容无效')
  }
  const padding = value.endsWith('==') ? 2 : (value.endsWith('=') ? 1 : 0)
  const byteSize = Math.floor(value.length * 3 / 4) - padding
  if (byteSize <= 0) throw workflowError('EMPTY_FACE_PHOTO', '人脸照片为空')
  if (byteSize > FACE_PHOTO_MAX_BYTES) {
    throw workflowError('FACE_PHOTO_TOO_LARGE', '人脸照片不得超过 10 MB', {
      byteSize,
      maxBytes: FACE_PHOTO_MAX_BYTES
    })
  }
  return { photoBase64: value, byteSize }
}

const decodeBase64 = (value) => {
  if (typeof globalThis.atob !== 'function') {
    throw workflowError('WEB_CRYPTO_UNAVAILABLE', '当前 WebView 不支持人脸照片完整性校验')
  }
  const binary = globalThis.atob(value)
  return Uint8Array.from(binary, (character) => character.charCodeAt(0))
}

export function composeFaceAiId(employeeId, faceId) {
  return `${normalizeBackendId(employeeId, 'employeeId')}_${normalizeBackendId(faceId, 'faceId')}`
}

export function createTemporaryFaceAiId(employeeId, operationId) {
  const owner = normalizeBackendId(employeeId, 'employeeId')
  const suffix = String(operationId || '')
    .replace(/[^A-Za-z0-9]/g, '')
    .slice(-24)
  if (!suffix) throw workflowError('OPERATION_ID_REQUIRED', '缺少人脸录入操作编号')
  return `${owner}_pending_${suffix}`
}

export async function sha256FacePhoto(photoBase64) {
  if (!globalThis.crypto?.subtle) {
    throw workflowError('WEB_CRYPTO_UNAVAILABLE', '当前 WebView 不支持人脸照片完整性校验')
  }
  const normalized = normalizePhotoBase64(photoBase64)
  const digest = await globalThis.crypto.subtle.digest('SHA-256', decodeBase64(normalized.photoBase64))
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

export function buildFaceRecordRequest({ employeeId, fileHash, faceImagePath, faceFeature } = {}) {
  const owner = normalizeBackendId(employeeId, 'employeeId')
  const numericOwner = Number(owner)
  if (!Number.isSafeInteger(numericOwner) || numericOwner <= 0) {
    throw workflowError('INVALID_EMPLOYEE_ID', 'employeeId 超出客户端可安全提交的数字范围')
  }
  const normalizedFileHash = String(fileHash || '').trim().toLowerCase()
  if (!HEX_SHA256.test(normalizedFileHash)) {
    throw workflowError('INVALID_FACE_IMAGE_HASH', '上传返回的人脸图片 SHA-256 无效')
  }
  const normalizedPath = String(faceImagePath || '').trim()
  if (!normalizedPath) throw workflowError('FACE_IMAGE_PATH_REQUIRED', '上传响应缺少 faceImagePath')
  const normalizedFeature = String(faceFeature || '').trim()
  if (!normalizedFeature) throw workflowError('FACE_FEATURE_REQUIRED', '原生录入结果缺少 faceFeature')
  return {
    employeeId: numericOwner,
    fileHash: normalizedFileHash,
    faceImagePath: normalizedPath,
    faceFeature: normalizedFeature
  }
}

export function buildFaceImageUploadRequest({ employeeId, faceImageBase64, faceImageMimeType } = {}) {
  const owner = normalizeBackendId(employeeId, 'employeeId')
  const normalized = normalizePhotoBase64(faceImageBase64)
  return {
    employeeId: Number(owner),
    fields: { employeeId: Number(owner) },
    file: {
      fieldName: 'file',
      fileName: 'face.jpg',
      mimeType: String(faceImageMimeType || 'image/jpeg').trim() || 'image/jpeg',
      base64: normalized.photoBase64
    }
  }
}

export function parseFaceImageUploadResponse(response, employeeId, expectedFileHash) {
  const status = Number(response?.status || 0)
  if (status && (status < 200 || status >= 300)) {
    throw workflowError(`HTTP_${status}`, response?.body?.msg || response?.error || `人脸图片上传 HTTP 失败(${status})`)
  }
  const envelope = response?.body || response || {}
  const payload = envelope?.data && typeof envelope.data === 'object' ? envelope.data : envelope
  const code = payload.code ?? envelope.code
  if (code != null && ![0, '0', 200, '200'].includes(code)) {
    throw workflowError(`BACKEND_${code}`, payload.msg || envelope.msg || '人脸图片上传失败')
  }
  const fileHash = String(payload.fileHash || '').trim().toLowerCase()
  // 上传接口返回 fileName 作为存储路径；注册接口仍使用 faceImagePath 字段承载该值。
  const faceImagePath = String(payload.faceImagePath || payload.fileName || '').trim()
  if (!HEX_SHA256.test(fileHash) || !faceImagePath) {
    throw workflowError('FACE_UPLOAD_RESPONSE_INVALID', '上传响应缺少有效的 faceImagePath 或 fileHash')
  }
  if (expectedFileHash && fileHash !== String(expectedFileHash).trim().toLowerCase()) {
    throw workflowError('FACE_UPLOAD_HASH_MISMATCH', '上传返回的人脸图片哈希与本机校验结果不一致')
  }
  return { employeeId: normalizeBackendId(employeeId, 'employeeId'), fileHash, faceImagePath }
}

export function parseFaceRecordResponse(response, employeeId) {
  const status = Number(response?.status || 0)
  if (status && (status < 200 || status >= 300)) {
    throw workflowError(`HTTP_${status}`, response?.body?.msg || response?.error || `人脸注册 HTTP 失败(${status})`)
  }
  const envelope = response?.body || response || {}
  let payload = envelope
  let code = envelope.code
  if ([200, '200'].includes(code) && envelope.data && typeof envelope.data === 'object') {
    payload = envelope.data
    code = payload.code ?? 0
  }
  if (code == null) throw workflowError('BACKEND_CODE_MISSING', '人脸注册响应缺少业务状态码')
  if (![0, '0'].includes(code)) {
    throw workflowError(`BACKEND_${code}`, payload.msg || envelope.msg || '人脸注册失败')
  }
  const owner = normalizeBackendId(employeeId, 'employeeId')
  const returnedEmployeeId = normalizeBackendId(payload.employeeId, 'employeeId')
  if (returnedEmployeeId !== owner) {
    throw workflowError('FACE_RESPONSE_EMPLOYEE_MISMATCH', '人脸注册响应 employeeId 与请求不一致')
  }
  return { employeeId: owner }
}

export function normalizeFaceSyncItem(item = {}) {
  const faceId = normalizeBackendId(item.faceId, 'faceId')
  const employeeId = normalizeBackendId(item.employeeId, 'employeeId')
  // 文档4.2节：faceAiId 由服务端返回（格式 ${employeeId}_${faceId}），兜底合成
  const expectedFaceAiId = composeFaceAiId(employeeId, faceId)
  const faceAiId = String(item.faceAiId || '').trim() || expectedFaceAiId
  if (faceAiId !== expectedFaceAiId) {
    throw workflowError('FACE_AI_ID_MISMATCH', '后台返回的 FaceAI 注册编号不符合 employeeId_faceId 规则')
  }
  // 文档4.2/4.5节：syncAction 由服务端返回 ADD/DELETE
  const syncAction = String(item.syncAction || '').trim().toUpperCase()
  const status = String(item.status ?? '').trim()
  // status='1' 为待处理状态，不进入本地底库，静默跳过
  if (status === '1') return null
  const isAdd = syncAction === 'ADD' && status === '0'
  const isDelete = syncAction === 'DELETE' && status === '9'
  if (!isAdd && !isDelete) {
    throw workflowError('INVALID_FACE_SYNC_STATE', `人脸记录 ${faceId} 的 syncAction=${syncAction} status=${status} 组合无效`)
  }
  return {
    ...item,
    faceId,
    employeeId,
    faceAiId,
    syncAction,
    status,
    enabled: isAdd
  }
}

export function createMultiFaceEnrollmentWorkflow({
  uploadFaceImage,
  sendFaceRecord,
  findPhotoByFileHash,
  removeTemplate,
  recordStage = async () => {},
  hashPhoto = sha256FacePhoto
} = {}) {
  const requiredDependencies = {
    uploadFaceImage,
    sendFaceRecord,
    findPhotoByFileHash,
    removeTemplate
  }
  const missing = Object.entries(requiredDependencies)
    .filter(([, dependency]) => typeof dependency !== 'function')
    .map(([name]) => name)
  if (missing.length) throw new Error(`multi-face enrollment dependencies missing: ${missing.join(', ')}`)

  return async function finalizeEnrollment({
    operationId,
    temporaryFaceAiId,
    employeeId,
    employeeName,
    enrolled
  } = {}) {
    const owner = normalizeBackendId(employeeId, 'employeeId')
    const temporaryId = String(temporaryFaceAiId || '').trim()
    const normalizedPhoto = normalizePhotoBase64(enrolled?.faceImageBase64)
    if (!temporaryId) throw workflowError('TEMPORARY_FACE_AI_ID_REQUIRED', '缺少临时 FaceAI 注册编号')

    let temporaryRemoved = false
    try {
      await recordStage('CAPTURED', { operationId, employeeId: owner, temporaryFaceAiId: temporaryId })
      const fileHash = String(await hashPhoto(normalizedPhoto.photoBase64)).trim().toLowerCase()
      if (!HEX_SHA256.test(fileHash)) {
        throw workflowError('INVALID_FACE_IMAGE_HASH', '本机人脸图片 SHA-256 无效')
      }
      const duplicate = await findPhotoByFileHash(fileHash)
      if (duplicate) {
        throw workflowError('FACE_IMAGE_DUPLICATE', '该人脸图片已在本机录入，不能重复添加', {
          faceId: duplicate.faceId,
          employeeId: duplicate.employeeId
        })
      }

      await recordStage('UPLOADING', { operationId, employeeId: owner, fileHash })
      const uploadResponse = await uploadFaceImage(buildFaceImageUploadRequest({
        employeeId: owner,
        faceImageBase64: normalizedPhoto.photoBase64,
        faceImageMimeType: enrolled?.faceImageMimeType
      }))
      const uploaded = parseFaceImageUploadResponse(uploadResponse, owner, fileHash)
      await recordStage('REGISTERING', {
        operationId,
        employeeId: owner,
        fileHash: uploaded.fileHash,
        faceImagePath: uploaded.faceImagePath
      })
      const recordRequest = buildFaceRecordRequest({
        employeeId: owner,
        fileHash: uploaded.fileHash,
        faceImagePath: uploaded.faceImagePath,
        faceFeature: enrolled?.faceFeature
      })
      const recordResponse = await sendFaceRecord(recordRequest)
      parseFaceRecordResponse(recordResponse, owner)
      await recordStage('SERVER_ACCEPTED', {
        operationId,
        employeeId: owner,
        fileHash: uploaded.fileHash,
        faceImagePath: uploaded.faceImagePath
      })

      // 录入页只提交服务器添加请求，本机正式模板和绑定由退出管理模式后的增量同步生成。
      await removeTemplate(temporaryId)
      temporaryRemoved = true
      await recordStage('COMPLETED', { operationId, employeeId: owner, fileHash: uploaded.fileHash, syncPending: true })
      return {
        operationId,
        employeeId: owner,
        employeeName: String(employeeName || ''),
        fileHash: uploaded.fileHash,
        accepted: true,
        serverAccepted: true,
        pendingIncrementalSync: true
      }
    } finally {
      if (!temporaryRemoved) await removeTemplate(temporaryId).catch(() => {})
    }
  }
}
