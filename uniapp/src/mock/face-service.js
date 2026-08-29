/**
 * Face Service — 人脸识别/录入模拟
 *
 * 支持两种模式：
 *  - 'nodejs': 调用本地 FaceAI Node.js HTTP 服务
 *  - 'overlay': 纯前端 DOM 弹窗模拟（降级）
 */

import { emit } from './events.js'
import { FACE_MODE, FACE_SERVICE_URL, FACE_TIMEOUT, log, warn, error } from './config.js'

const TAG = 'FaceService'

let overlayEl = null
let recognitionTimer = null
let enrollmentTimer = null
let cameraStream = null

// ══════════════════════════════════════════
//  模式：Node.js FaceAI 服务
// ══════════════════════════════════════════

async function nodejsEnroll(faceId, imageBase64) {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/enroll`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ employeeId: faceId, image: imageBase64 })
    })
    if (!res.ok) {
      warn(TAG, 'FaceAI enroll HTTP', res.status)
      return { success: false, reason: 'server_error' }
    }
    return res.json()
  } catch (e) {
    const msg = (e && (e.message || String(e))) || ''
    warn(TAG, 'FaceAI enroll error:', msg)
    return { success: false, reason: 'server_unreachable' }
  }
}

async function nodejsRecognize(imageBase64) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort('FaceAI server timeout'), FACE_TIMEOUT)

  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ image: imageBase64 }),
      signal: controller.signal
    })
    if (!res.ok) {
      warn(TAG, 'FaceAI search HTTP', res.status)
      return { matched: false, reason: 'server_error' }
    }
    return res.json()
  } catch (e) {
    if (e && e.name === 'AbortError') {
      warn(TAG, 'FaceAI search timeout')
      return { matched: false, reason: 'server_timeout' }
    }
    const msg = (e && (e.message || String(e))) || ''
    if (msg.includes('Failed to fetch') || msg.includes('NetworkError')) {
      warn(TAG, 'FaceAI server unreachable')
      return { matched: false, reason: 'server_unreachable' }
    }
    warn(TAG, 'FaceAI search error:', msg)
    return { matched: false, reason: 'recognition_error' }
  } finally {
    clearTimeout(timer)
  }
}

async function nodejsClear() {
  await fetch(`${FACE_SERVICE_URL}/api/face/clear`, { method: 'DELETE' })
}

async function nodejsDeleteEmployee(employeeId) {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/enroll/${encodeURIComponent(employeeId)}`, {
      method: 'DELETE'
    })
    if (!res.ok) {
      warn(TAG, 'FaceAI delete HTTP', res.status)
      return { success: false, error: 'http_error' }
    }
    return res.json()
  } catch (e) {
    warn(TAG, 'FaceAI delete error:', (e && e.message) || e)
    return { success: false, error: 'server_unreachable' }
  }
}

async function nodejsBatchEnroll(items) {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/enroll/batch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items })
    })
    if (!res.ok) {
      warn(TAG, 'FaceAI batch enroll HTTP', res.status)
      return { success: false, reason: 'server_error' }
    }
    return res.json()
  } catch (e) {
    warn(TAG, 'FaceAI batch enroll error:', (e && e.message) || e)
    return { success: false, reason: 'server_unreachable' }
  }
}

async function nodejsGetDbStatus() {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/db/status`)
    if (!res.ok) {
      warn(TAG, 'FaceAI db status HTTP', res.status)
      return { persons: 0, totalDescriptors: 0, list: [] }
    }
    return res.json()
  } catch (e) {
    warn(TAG, 'FaceAI db status error:', (e && e.message) || e)
    return { persons: 0, totalDescriptors: 0, list: [] }
  }
}

async function nodejsExportDb() {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/db/export`)
    if (!res.ok) {
      warn(TAG, 'FaceAI db export HTTP', res.status)
      return null
    }
    return res.json()
  } catch (e) {
    warn(TAG, 'FaceAI db export error:', (e && e.message) || e)
    return null
  }
}

async function nodejsImportDb(exportData) {
  try {
    const res = await fetch(`${FACE_SERVICE_URL}/api/face/db/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(exportData)
    })
    if (!res.ok) {
      warn(TAG, 'FaceAI db import HTTP', res.status)
      return { success: false, reason: 'server_error' }
    }
    return res.json()
  } catch (e) {
    warn(TAG, 'FaceAI db import error:', (e && e.message) || e)
    return { success: false, reason: 'server_unreachable' }
  }
}

// ══════════════════════════════════════════
//  模式：纯前端 Overlay 降级弹窗
// ══════════════════════════════════════════

function createOverlay(title, statusText, showCamera = false) {
  if (overlayEl) removeOverlay()

  overlayEl = document.createElement('div')
  overlayEl.id = 'mock-face-overlay'
  overlayEl.innerHTML = `
    <div style="
      position: fixed; top: 0; left: 0; width: 100%; height: 100%;
      background: rgba(0,0,0,0.7); z-index: 99999;
      display: flex; align-items: center; justify-content: center;
    ">
      <div style="
        background: #1a1a2e; border-radius: 16px; padding: 32px 24px;
        text-align: center; min-width: 280px; max-width: 380px;
        box-shadow: 0 8px 32px rgba(0,0,0,0.5);
      ">
        <div id="mock-face-camera-box" style="
          width: 240px; height: 240px; border-radius: 12px;
          margin: 0 auto 20px; overflow: hidden;
          display: flex; align-items: center; justify-content: center;
          background: #0a0a1a;
        ">
          ${showCamera
            ? '<video id="mock-face-video" style="width:100%;height:100%;object-fit:cover;" autoplay playsinline muted></video>'
            : '<span style="font-size:48px;">📷</span>'
          }
        </div>
        <div style="
          color: #fff; font-size: 18px; font-weight: 600; margin-bottom: 8px;
        ">${title}</div>
        <div id="mock-face-status" style="
          color: #aaa; font-size: 14px; margin-bottom: 24px;
        ">${statusText}</div>
        <div style="
          width: 100%; height: 4px; background: rgba(255,255,255,0.1);
          border-radius: 2px; overflow: hidden;
        ">
          <div id="mock-face-progress" style="
            width: 0%; height: 100%; background: linear-gradient(90deg, #667eea, #764ba2);
            border-radius: 2px; transition: width 0.3s ease;
          "></div>
        </div>
        <button id="mock-face-cancel" style="
          margin-top: 24px; background: none; border: 1px solid rgba(255,255,255,0.2);
          color: #aaa; padding: 8px 24px; border-radius: 8px; cursor: pointer;
          font-size: 14px;
        ">取消</button>
      </div>
    </div>
  `

  document.body.appendChild(overlayEl)

  // 取消按钮
  document.getElementById('mock-face-cancel').onclick = () => {
    removeOverlay()
    emit('face.recognition.cancelled', { reason: 'user_cancelled' })
  }

  // 进度动画
  const progressEl = document.getElementById('mock-face-progress')
  let progress = 0
  const animateInterval = setInterval(() => {
    progress += 3
    if (progress <= 90 && progressEl) {
      progressEl.style.width = progress + '%'
    }
  }, 60)

  // 存储清理函数
  overlayEl._cleanup = () => clearInterval(animateInterval)

  return overlayEl
}

function removeOverlay() {
  // 停止摄像头流
  if (cameraStream) {
    cameraStream.getTracks().forEach(track => track.stop())
    cameraStream = null
  }
  if (overlayEl) {
    if (overlayEl._cleanup) overlayEl._cleanup()
    overlayEl.remove()
    overlayEl = null
  }
}

function updateStatus(text) {
  const el = document.getElementById('mock-face-status')
  if (el) el.textContent = text
}

async function startCamera() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }
    })
    cameraStream = stream

    // 等待 DOM 中 video 元素就绪后挂载流
    const video = await waitForElement('mock-face-video', 3000)
    if (!video) {
      warn(TAG, 'Video element not found in overlay after 3s')
      return false
    }
    video.srcObject = stream

    // 等待 video 真正开始播放（videoWidth > 0）
    const ready = await waitForVideoReady(video, 3000)
    if (!ready) {
      warn(TAG, 'Video stream did not start playing after 3s')
      return false
    }
    return true
  } catch (e) {
    warn(TAG, 'Camera start failed:', e.message)
    return false
  }
}

function waitForElement(id, timeout) {
  return new Promise((resolve) => {
    const start = Date.now()
    const check = () => {
      const el = document.getElementById(id)
      if (el) return resolve(el)
      if (Date.now() - start >= timeout) return resolve(null)
      setTimeout(check, 100)
    }
    check()
  })
}

function waitForVideoReady(video, timeout) {
  if (video.videoWidth > 0) return Promise.resolve(true)
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), timeout)
    video.addEventListener('loadedmetadata', () => {
      clearTimeout(timer)
      resolve(true)
    }, { once: true })
  })
}

// ══════════════════════════════════════════
//  公开 API
// ══════════════════════════════════════════

/**
 * 启动人脸识别
 */
export async function faceRecognitionStart(options = {}) {
  if (FACE_MODE === 'nodejs') {
    return startNodejsRecognition(options)
  }
  return startOverlayRecognition(options)
}

/**
 * 取消人脸识别
 */
export function faceRecognitionCancel() {
  // 清理定时器
  if (recognitionTimer) {
    clearTimeout(recognitionTimer)
    recognitionTimer = null
  }
  removeOverlay()
  emit('face.recognition.cancelled', { reason: 'cancelled' })
}

/**
 * 启动人脸录入
 */
export async function faceEnrollmentStart(faceId, options = {}) {
  if (FACE_MODE === 'nodejs') {
    return startNodejsEnrollment(faceId, options)
  }
  return startOverlayEnrollment(faceId, options)
}

/**
 * 取消人脸录入
 */
export function faceEnrollmentCancel() {
  if (enrollmentTimer) {
    clearTimeout(enrollmentTimer)
    enrollmentTimer = null
  }
  removeOverlay()
  emit('face.enrollment.cancelled', { reason: 'cancelled' })
}

/**
 * 批量录入人脸特征
 * @param {Array<{employeeId: string, image: string}>} items
 * @returns {{ success: boolean, enrolled: number, failed: Array, total: number, dbSize: number }}
 */
export async function faceBatchEnroll(items) {
  if (FACE_MODE === 'nodejs') {
    return nodejsBatchEnroll(items)
  }
  warn(TAG, 'batchEnroll not supported in overlay mode')
  return { success: false, reason: 'not_supported_in_overlay' }
}

/**
 * 删除指定员工的人脸数据
 * @param {string} employeeId
 * @returns {{ success: boolean, employeeId?: string, removed?: number, error?: string }}
 */
export async function faceDeleteEmployee(employeeId) {
  if (FACE_MODE === 'nodejs') {
    return nodejsDeleteEmployee(employeeId)
  }
  warn(TAG, 'deleteEmployee not supported in overlay mode')
  return { success: false, error: 'not_supported_in_overlay' }
}

/**
 * 查看人脸库状态
 * @returns {{ persons: number, totalDescriptors: number, list: Array }}
 */
export async function faceGetDbStatus() {
  if (FACE_MODE === 'nodejs') {
    return nodejsGetDbStatus()
  }
  // overlay 模式返回空库
  return { persons: 0, totalDescriptors: 0, list: [] }
}

/**
 * 导出人脸库（含所有特征，base64 编码）
 * @returns {Object|null} 导出的 JSON 数据，失败返回 null
 */
export async function faceExportDb() {
  if (FACE_MODE === 'nodejs') {
    return nodejsExportDb()
  }
  warn(TAG, 'exportDb not supported in overlay mode')
  return null
}

/**
 * 导入人脸库（从 export 接口的 JSON 恢复）
 * @param {Object} exportData — export 接口返回的完整 JSON
 * @returns {{ success: boolean, imported?: number, skipped?: Array, dbSize?: number }}
 */
export async function faceImportDb(exportData) {
  if (FACE_MODE === 'nodejs') {
    return nodejsImportDb(exportData)
  }
  warn(TAG, 'importDb not supported in overlay mode')
  return { success: false, reason: 'not_supported_in_overlay' }
}

// ══════════════════════════════════════════
//  Node.js 模式实现
// ══════════════════════════════════════════

async function startNodejsRecognition(options) {
  createOverlay('人脸识别', '正在启动摄像头...', true)

  // 启动摄像头到 overlay 中
  const cameraOk = await startCamera()
  if (!cameraOk) {
    warn(TAG, 'Camera not available, falling back to overlay')
    removeOverlay()
    return startOverlayRecognition(options)
  }

  updateStatus('正在采集画面...')

  try {
    // 等 1s 让摄像头稳定曝光
    await sleep(1000)

    // 从 overlay 中的 video 元素截图
    const imageBase64 = await captureFromVideo()
    if (!imageBase64) {
      throw new Error('Camera capture failed')
    }

    updateStatus('正在识别...')
    const result = await nodejsRecognize(imageBase64)

    removeOverlay()

    if (result.matched) {
      emit('face.recognized', {
        faceId: result.employeeId,
        employeeId: result.employeeId,
        score: result.score || 0,
        matched: true
      })
    } else {
      emit('face.recognition.timeout', { reason: result.reason || 'no_match' })
    }
  } catch (e) {
    warn(TAG, 'Camera capture failed, falling back to overlay:', (e && e.message) || e)
    removeOverlay()
    return startOverlayRecognition(options)
  }

  return { accepted: true }
}

async function startNodejsEnrollment(faceId, options) {
  createOverlay('人脸录入', `正在启动摄像头...`, true)

  const cameraOk = await startCamera()
  if (!cameraOk) {
    warn(TAG, 'Camera not available for enrollment')
    removeOverlay()
    return startOverlayEnrollment(faceId, options)
  }

  updateStatus('正在采集画面...')

  try {
    await sleep(1000)
    const imageBase64 = await captureFromVideo()
    if (!imageBase64) {
      throw new Error('Camera capture failed')
    }

    updateStatus('正在提取特征...')
    const result = await nodejsEnroll(faceId, imageBase64)

    removeOverlay()

    if (result.success) {
      emit('face.enrolled', {
        faceId: result.faceId || faceId,
        faceFeature: result.feature || '',
        score: result.score || 0.95
      })
    } else {
      emit('face.enrollment.timeout', { reason: result.reason || 'enroll_failed' })
    }
  } catch (e) {
    warn(TAG, 'Camera capture failed for enrollment, falling back to overlay:', (e && e.message) || e)
    removeOverlay()
    return startOverlayEnrollment(faceId, options)
  }

  return { accepted: true }
}

/**
 * 从 overlay 中已打开的 video 元素截图
 */
function captureFromVideo() {
  const video = document.getElementById('mock-face-video')
  if (!video || !video.videoWidth) return null

  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const ctx = canvas.getContext('2d')
  ctx.drawImage(video, 0, 0)

  return canvas.toDataURL('image/jpeg', 0.85)
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ══════════════════════════════════════════
//  Overlay 降级模式实现
// ══════════════════════════════════════════

function startOverlayRecognition(options = {}) {
  createOverlay('人脸识别', '正在识别中...', true)

  // 启动摄像头
  startCamera()

  // 清除之前的定时器
  if (recognitionTimer) clearTimeout(recognitionTimer)

  // 模拟 2-3 秒后随机返回结果
  const delay = 2000 + Math.random() * 1000
  recognitionTimer = setTimeout(() => {
    removeOverlay()
    const matched = Math.random() > 0.2

    if (matched) {
      const employeeId = 'E' + String(Math.floor(Math.random() * 20) + 1).padStart(3, '0')
      emit('face.recognized', {
        faceId: employeeId,
        employeeId: employeeId,
        score: 0.85 + Math.random() * 0.15,
        matched: true
      })
    } else {
      emit('face.recognition.timeout', { reason: 'no_match' })
    }

    recognitionTimer = null
  }, delay)

  return Promise.resolve({ accepted: true })
}

function startOverlayEnrollment(faceId, options = {}) {
  createOverlay('人脸录入', `正在录入 ${faceId}...`, true)

  // 启动摄像头
  startCamera()

  if (enrollmentTimer) clearTimeout(enrollmentTimer)

  const delay = 2000 + Math.random() * 1000
  enrollmentTimer = setTimeout(() => {
    removeOverlay()
    emit('face.enrolled', {
      faceId: faceId,
      faceFeature: 'MOCK_BASE64_FEATURE_' + faceId + '==',
      score: 0.95
    })
    enrollmentTimer = null
  }, delay)

  return Promise.resolve({ accepted: true })
}

/**
 * 销毁
 */
export function destroy() {
  faceRecognitionCancel()
  faceEnrollmentCancel()
}
