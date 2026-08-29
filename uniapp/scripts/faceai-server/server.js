/**
 * FaceAI Local HTTP Service
 *
 * 基于 face-api.js + TensorFlow.js Node 后端，提供真实的人脸特征提取与比对。
 *
 * API:
 *   POST   /api/face/search               — 1:N 人脸搜索
 *   POST   /api/face/enroll               — 单条录入人脸特征
 *   POST   /api/face/enroll/batch         — 批量录入人脸特征
 *   DELETE /api/face/enroll/:employeeId   — 删除指定员工
 *   DELETE /api/face/clear                — 清空人脸库
 *   GET    /api/face/db/status            — 查看人脸库详情
 *   GET    /api/face/db/export            — 导出人脸库（JSON）
 *   POST   /api/face/db/import            — 导入人脸库（JSON）
 *   GET    /health                        — 健康检查
 *
 * 启动:  npm start  (默认端口 3456)
 */

// 注册 tfjs-node 原生后端（必须在 face-api.js 之前 loaded）
// 用 TF C++ 库替代纯 JS CPU 推理，速度提升 10-50x
require('@tensorflow/tfjs-node')

const express = require('express')
const faceapi = require('face-api.js')
const { Canvas, Image, ImageData, createCanvas, loadImage } = require('canvas')
const path = require('path')
const fs = require('fs')

// ── 将 face-api.js patch 到 Node.js canvas 环境 ──
faceapi.env.monkeyPatch({ Canvas, Image, ImageData })

const PORT = process.env.PORT || 3456
const MODEL_DIR = path.join(__dirname, 'models')

// ══════════════════════════════════════════
//  人脸库: Map<employeeId, Float32Array[]>
//  每人可存多张照片的特征（取平均距离）
// ══════════════════════════════════════════
const faceDB = new Map()

// ══════════════════════════════════════════
//  模型加载
// ══════════════════════════════════════════

async function loadModels() {
  const requiredModels = ['ssdMobilenetv1', 'faceLandmark68Net', 'faceRecognitionNet']

  for (const name of requiredModels) {
    const manifestPath = path.join(MODEL_DIR, `${getModelDir(name)}-weights_manifest.json`)
    if (!fs.existsSync(manifestPath)) {
      throw new Error(
        `模型文件缺失: ${manifestPath}\n` +
        `请先运行: cd uniapp/scripts/faceai-server && npm run download-models`
      )
    }
  }

  console.log('[FaceAI] 加载 SSD MobileNet v1（人脸检测）...')
  await faceapi.nets.ssdMobilenetv1.loadFromDisk(MODEL_DIR)

  console.log('[FaceAI] 加载 Face Landmark 68（关键点）...')
  await faceapi.nets.faceLandmark68Net.loadFromDisk(MODEL_DIR)

  console.log('[FaceAI] 加载 Face Recognition Net（特征提取）...')
  await faceapi.nets.faceRecognitionNet.loadFromDisk(MODEL_DIR)

  console.log('[FaceAI] 全部模型加载完成 ✓')
}

/** face-api.js 对不同模型的磁盘目录命名映射 */
function getModelDir(name) {
  const map = {
    ssdMobilenetv1: 'ssd_mobilenetv1_model',
    faceLandmark68Net: 'face_landmark_68_model',
    faceRecognitionNet: 'face_recognition_model'
  }
  return map[name] || name
}

// ══════════════════════════════════════════
//  图片处理
// ══════════════════════════════════════════

/**
 * Base64 (含 data:image/...;base64, 前缀) → face-api.js 可用的输入
 * 自动缩放到合适尺寸以加速推理
 */
function base64ToTensorInput(base64) {
  const img = loadImage(Buffer.from(stripBase64Prefix(base64), 'base64'))
  return img.then((loaded) => {
    // SSD MobileNet 输入为 300x300，超过 600px 的图缩放以加速
    const MAX_DIM = 640
    let w = loaded.width
    let h = loaded.height
    if (w > MAX_DIM || h > MAX_DIM) {
      const ratio = Math.min(MAX_DIM / w, MAX_DIM / h)
      w = Math.round(w * ratio)
      h = Math.round(h * ratio)
    }
    const canvas = createCanvas(w, h)
    const ctx = canvas.getContext('2d')
    ctx.drawImage(loaded, 0, 0, w, h)
    return canvas
  })
}

function stripBase64Prefix(dataUrl) {
  if (dataUrl.includes(',')) {
    return dataUrl.split(',')[1]
  }
  return dataUrl
}

// ══════════════════════════════════════════
//  人脸检测 + 特征提取
// ══════════════════════════════════════════

/**
 * 从图片中检测人脸并提取 128 维特征描述符
 * @returns {{ descriptor: Float32Array, detection: object } | null}
 */
async function extractFaceDescriptor(input) {
  // SSD MobileNet: minConfidence=0.5 过滤低置信度检测
  const options = new faceapi.SsdMobilenetv1Options({ minConfidence: 0.5 })

  const result = await faceapi
    .detectSingleFace(input, options)
    .withFaceLandmarks()
    .withFaceDescriptor()

  if (!result) return null

  return {
    descriptor: result.descriptor,
    detection: {
      box: {
        x: result.detection.box.x,
        y: result.detection.box.y,
        width: result.detection.box.width,
        height: result.detection.box.height
      },
      score: result.detection.score
    }
  }
}

// ══════════════════════════════════════════
//  人脸搜索
// ══════════════════════════════════════════

/**
 * 1:N 搜索：将查询描述符与库中所有已有描述符比对
 *
 * 阈值说明:
 *   face-api.js FaceRecognitionNet 使用欧氏距离
 *   通常 0.6 是同一个人/不同人的分界线
 *   - distance < 0.6 → 同一个人（置信度较高）
 *   - distance >= 0.6 → 不同人
 *
 * 每人在库中可能有多张照片（多个描述符），取最小距离。
 *
 * @returns {{ matched: boolean, employeeId?: string, score?: number, distance?: number }}
 */
function searchInDB(queryDescriptor) {
  let bestMatch = { employeeId: null, distance: Infinity }

  for (const [employeeId, descriptors] of faceDB.entries()) {
    for (const storedDesc of descriptors) {
      const distance = faceapi.euclideanDistance(queryDescriptor, storedDesc)
      if (distance < bestMatch.distance) {
        bestMatch = { employeeId, distance }
      }
    }
  }

  const THRESHOLD = 0.6

  if (bestMatch.employeeId && bestMatch.distance < THRESHOLD) {
    // 将距离映射为 0~1 的置信度分数
    const score = Math.max(0, 1 - bestMatch.distance / THRESHOLD)
    return {
      matched: true,
      employeeId: bestMatch.employeeId,
      score: parseFloat(score.toFixed(4)),
      distance: parseFloat(bestMatch.distance.toFixed(4))
    }
  }

  return {
    matched: false,
    reason: bestMatch.employeeId ? 'below_threshold' : 'no_match',
    bestDistance: bestMatch.employeeId ? parseFloat(bestMatch.distance.toFixed(4)) : undefined
  }
}

// ══════════════════════════════════════════
//  Express 路由
// ══════════════════════════════════════════

const app = express()
app.use(express.json({ limit: '10mb' }))

// ── CORS（允许浏览器 dev server 跨域） ──
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*')
  res.header('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS')
  res.header('Access-Control-Allow-Headers', 'Content-Type')
  if (req.method === 'OPTIONS') return res.sendStatus(200)
  next()
})

/**
 * POST /api/face/search
 * Body: { image: "data:image/jpeg;base64,..." }
 * Response: { matched, employeeId, score, distance } 或 { matched: false, reason }
 */
app.post('/api/face/search', async (req, res) => {
  try {
    const { image } = req.body
    if (!image) {
      return res.status(400).json({ error: '缺少 image 字段' })
    }

    // 1. 提取查询图片的人脸特征
    const input = await base64ToTensorInput(image)
    const face = await extractFaceDescriptor(input)

    if (!face) {
      console.log('[FaceAI] search: 未检测到人脸')
      return res.json({ matched: false, reason: 'no_face_detected' })
    }

    console.log(`[FaceAI] search: 检测到人脸 (置信度=${face.detection.score.toFixed(2)})`)

    // 2. 在库中搜索
    const result = searchInDB(face.descriptor)

    if (result.matched) {
      console.log(`[FaceAI] search: 匹配成功 → ${result.employeeId} (score=${result.score})`)
    } else {
      console.log(`[FaceAI] search: 未匹配 (reason=${result.reason}, dbSize=${faceDB.size})`)
    }

    return res.json(result)
  } catch (err) {
    console.error('[FaceAI] search error:', err.message)
    return res.status(500).json({ error: err.message })
  }
})

/**
 * POST /api/face/enroll
 * Body: { employeeId: "E001", image: "data:image/jpeg;base64,..." }
 * Response: { success: true, faceId, feature, score }
 */
app.post('/api/face/enroll', async (req, res) => {
  try {
    const { employeeId, image } = req.body
    if (!employeeId || !image) {
      return res.status(400).json({ error: '缺少 employeeId 或 image 字段' })
    }

    // 1. 提取人脸特征
    const input = await base64ToTensorInput(image)
    const face = await extractFaceDescriptor(input)

    if (!face) {
      console.log(`[FaceAI] enroll: ${employeeId} - 未检测到人脸`)
      return res.json({ success: false, reason: 'no_face_detected' })
    }

    console.log(`[FaceAI] enroll: ${employeeId} 检测到人脸 (置信度=${face.detection.score.toFixed(2)})`)

    // 2. 存入人脸库（支持一人多张照片）
    if (!faceDB.has(employeeId)) {
      faceDB.set(employeeId, [])
    }
    faceDB.get(employeeId).push(face.descriptor)

    // 3. 将 Float32Array 转为 base64 字符串（与 mock 兼容）
    const featureBase64 = Buffer.from(face.descriptor.buffer).toString('base64')

    console.log(`[FaceAI] enroll: ${employeeId} 录入成功 (该员工共 ${faceDB.get(employeeId).length} 张照片, 库总人数 ${faceDB.size})`)

    return res.json({
      success: true,
      faceId: employeeId,
      feature: featureBase64,
      score: parseFloat(face.detection.score.toFixed(4))
    })
  } catch (err) {
    console.error('[FaceAI] enroll error:', err.message)
    return res.status(500).json({ error: err.message })
  }
})

/**
 * DELETE /api/face/clear
 * 清空所有人脸库数据
 */
app.delete('/api/face/clear', (req, res) => {
  const count = faceDB.size
  faceDB.clear()
  console.log(`[FaceAI] 人脸库已清空 (之前共 ${count} 人)`)
  return res.json({ success: true, cleared: count })
})

/**
 * DELETE /api/face/enroll/:employeeId
 * 删除指定员工的人脸数据
 * Response: { success, employeeId, removed }
 */
app.delete('/api/face/enroll/:employeeId', (req, res) => {
  const { employeeId } = req.params
  if (!faceDB.has(employeeId)) {
    return res.status(404).json({ success: false, error: '员工不存在' })
  }
  const count = faceDB.get(employeeId).length
  faceDB.delete(employeeId)
  console.log(`[FaceAI] 已删除 ${employeeId} (${count} 张照片, 库剩余 ${faceDB.size} 人)`)
  return res.json({ success: true, employeeId, removed: count })
})

/**
 * POST /api/face/enroll/batch
 * Body: { items: [{ employeeId, image }, ...] }
 * Response: { success: true, enrolled: N, failed: [{ employeeId, reason }], total: N }
 */
app.post('/api/face/enroll/batch', async (req, res) => {
  try {
    const { items } = req.body
    if (!items || !Array.isArray(items) || items.length === 0) {
      return res.status(400).json({ error: '缺少 items 数组' })
    }

    const results = { enrolled: 0, failed: [], total: items.length }

    for (const item of items) {
      const { employeeId, image } = item
      if (!employeeId || !image) {
        results.failed.push({ employeeId: employeeId || '(缺失)', reason: 'missing_fields' })
        continue
      }

      try {
        const input = await base64ToTensorInput(image)
        const face = await extractFaceDescriptor(input)

        if (!face) {
          results.failed.push({ employeeId, reason: 'no_face_detected' })
          continue
        }

        if (!faceDB.has(employeeId)) {
          faceDB.set(employeeId, [])
        }
        faceDB.get(employeeId).push(face.descriptor)
        results.enrolled++
      } catch (err) {
        results.failed.push({ employeeId, reason: err.message })
      }
    }

    console.log(`[FaceAI] batch enroll: 成功 ${results.enrolled}, 失败 ${results.failed.length}, 总人数 ${faceDB.size}`)

    return res.json({
      success: true,
      enrolled: results.enrolled,
      failed: results.failed,
      total: results.total,
      dbSize: faceDB.size
    })
  } catch (err) {
    console.error('[FaceAI] batch enroll error:', err.message)
    return res.status(500).json({ error: err.message })
  }
})

/**
 * GET /api/face/db/status
 * 查看人脸库详情（每人的照片数）
 * Response: { persons, totalDescriptors, list: [{ employeeId, photoCount }] }
 */
app.get('/api/face/db/status', (req, res) => {
  let totalDescriptors = 0
  const list = []
  for (const [employeeId, descriptors] of faceDB.entries()) {
    totalDescriptors += descriptors.length
    list.push({ employeeId, photoCount: descriptors.length })
  }
  return res.json({
    persons: faceDB.size,
    totalDescriptors,
    list
  })
})

/**
 * GET /api/face/db/export
 * 导出人脸库为 JSON（特征以 base64 存储）
 * Response: { version: 1, exportedAt, persons: N, data: [{ employeeId, features: [base64, ...] }] }
 */
app.get('/api/face/db/export', (req, res) => {
  const data = []
  for (const [employeeId, descriptors] of faceDB.entries()) {
    const features = descriptors.map(d => Buffer.from(d.buffer).toString('base64'))
    data.push({ employeeId, features })
  }
  console.log(`[FaceAI] 导出人脸库: ${data.length} 人`)
  return res.json({
    version: 1,
    exportedAt: new Date().toISOString(),
    persons: data.length,
    data
  })
})

/**
 * POST /api/face/db/import
 * Body: 来自 export 接口的 JSON
 * Response: { success: true, imported: N, skipped: [{ employeeId, reason }] }
 */
app.post('/api/face/db/import', (req, res) => {
  try {
    const { data } = req.body
    if (!data || !Array.isArray(data)) {
      return res.status(400).json({ error: '缺少 data 数组' })
    }

    let imported = 0
    const skipped = []

    for (const entry of data) {
      const { employeeId, features } = entry
      if (!employeeId || !features || !Array.isArray(features) || features.length === 0) {
        skipped.push({ employeeId: employeeId || '(缺失)', reason: 'invalid_entry' })
        continue
      }

      const descriptors = []
      for (const f of features) {
        try {
          const buf = Buffer.from(f, 'base64')
          if (buf.length !== 512) {
            skipped.push({ employeeId, reason: `feature_size_mismatch: ${buf.length}` })
            continue
          }
          descriptors.push(new Float32Array(buf.buffer))
        } catch (err) {
          skipped.push({ employeeId, reason: `decode_error: ${err.message}` })
        }
      }

      if (descriptors.length > 0) {
        if (!faceDB.has(employeeId)) {
          faceDB.set(employeeId, [])
        }
        faceDB.get(employeeId).push(...descriptors)
        imported++
      }
    }

    console.log(`[FaceAI] 导入人脸库: ${imported} 人 (跳过 ${skipped.length}), 库总人数 ${faceDB.size}`)

    return res.json({
      success: true,
      imported,
      skipped,
      dbSize: faceDB.size
    })
  } catch (err) {
    console.error('[FaceAI] import error:', err.message)
    return res.status(500).json({ error: err.message })
  }
})

/**
 * GET /health
 * 健康检查（含库状态）
 */
app.get('/health', (req, res) => {
  let totalDescriptors = 0
  for (const descs of faceDB.values()) {
    totalDescriptors += descs.length
  }
  return res.json({
    status: 'ok',
    modelsLoaded: true,
    persons: faceDB.size,
    totalDescriptors
  })
})

// ══════════════════════════════════════════
//  启动
// ══════════════════════════════════════════

loadModels()
  .then(() => {
    app.listen(PORT, () => {
      console.log(`\n  FaceAI 本地服务已启动: http://localhost:${PORT}`)
      console.log(`  接口:`)
      console.log(`    POST   /api/face/search               — 人脸搜索`)
      console.log(`    POST   /api/face/enroll               — 单条录入`)
      console.log(`    POST   /api/face/enroll/batch         — 批量录入`)
      console.log(`    DELETE /api/face/enroll/:employeeId   — 删除指定员工`)
      console.log(`    DELETE /api/face/clear                — 清空人脸库`)
      console.log(`    GET    /api/face/db/status            — 查看人脸库`)
      console.log(`    GET    /api/face/db/export            — 导出人脸库`)
      console.log(`    POST   /api/face/db/import            — 导入人脸库`)
      console.log(`    GET    /health                        — 健康检查`)
      console.log(`  人脸库: 0 人 (录入后即可识别)\n`)
    })
  })
  .catch((err) => {
    console.error('\n✗ 模型加载失败:', err.message)
    console.error('请确保已下载模型文件:\n  cd uniapp/scripts/faceai-server && npm run download-models\n')
    process.exit(1)
  })
