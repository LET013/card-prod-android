/**
 * 从 face-api.js 官方 GitHub 下载人脸识别所需模型文件
 *
 * 模型文件 (~10MB 总计，需要网络连接):
 *   - SSD MobileNet v1 (人脸检测)        ~5.4 MB
 *   - Face Landmark 68 (关键点)          ~350 KB
 *   - Face Recognition Net (特征提取)     ~6.2 MB
 *
 * 使用:  node download-models.js
 *
 * 模型来源: https://github.com/justadudewhohacks/face-api.js/tree/master/weights
 */

const https = require('https')
const fs = require('fs')
const path = require('path')

const BASE_URL = 'https://raw.githubusercontent.com/justadudewhohacks/face-api.js/master/weights'
const MODELS_DIR = path.join(__dirname, 'models')

const FILES = [
  // SSD MobileNet v1 — 人脸检测
  'ssd_mobilenetv1_model-shard1',
  'ssd_mobilenetv1_model-shard2',
  'ssd_mobilenetv1_model-weights_manifest.json',

  // Face Landmark 68 — 68点关键点定位
  'face_landmark_68_model-shard1',
  'face_landmark_68_model-weights_manifest.json',

  // Face Recognition Net — 128维特征向量提取
  'face_recognition_model-shard1',
  'face_recognition_model-shard2',
  'face_recognition_model-weights_manifest.json'
]

function downloadFile(filename) {
  return new Promise((resolve, reject) => {
    const url = `${BASE_URL}/${filename}`
    const filePath = path.join(MODELS_DIR, filename)

    // 跳过已存在的文件
    if (fs.existsSync(filePath)) {
      const stat = fs.statSync(filePath)
      if (stat.size > 0) {
        console.log(`  ✓ ${filename} (已存在, ${(stat.size / 1024).toFixed(1)} KB)`)
        return resolve()
      }
    }

    console.log(`  ↓ 下载 ${filename} ...`)
    const file = fs.createWriteStream(filePath)

    https.get(url, (res) => {
      // 处理重定向
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        https.get(res.headers.location, (redirectRes) => {
          redirectRes.pipe(file)
          file.on('finish', () => {
            file.close()
            const size = fs.statSync(filePath).size
            console.log(`  ✓ ${filename} (${(size / 1024).toFixed(1)} KB)`)
            resolve()
          })
        }).on('error', reject)
        return
      }

      if (res.statusCode !== 200) {
        file.close()
        fs.unlinkSync(filePath)
        return reject(new Error(`HTTP ${res.statusCode} for ${url}`))
      }

      res.pipe(file)
      file.on('finish', () => {
        file.close()
        const size = fs.statSync(filePath).size
        console.log(`  ✓ ${filename} (${(size / 1024).toFixed(1)} KB)`)
        resolve()
      })
    }).on('error', (err) => {
      file.close()
      if (fs.existsSync(filePath)) fs.unlinkSync(filePath)
      reject(err)
    })
  })
}

async function main() {
  console.log('\n  下载 face-api.js 模型文件...')
  console.log(`  目标目录: ${MODELS_DIR}\n`)

  // 确保目录存在
  if (!fs.existsSync(MODELS_DIR)) {
    fs.mkdirSync(MODELS_DIR, { recursive: true })
  }

  let successCount = 0
  let failCount = 0

  for (const file of FILES) {
    try {
      await downloadFile(file)
      successCount++
    } catch (err) {
      console.error(`  ✗ ${file}: ${err.message}`)
      failCount++
    }
  }

  console.log(`\n  完成: ${successCount} 个文件, ${failCount} 个失败`)

  if (failCount > 0) {
    console.log('\n  部分文件下载失败，你可以手动下载:')
    console.log(`  访问 ${BASE_URL}/`)
    console.log(`  将以下文件放入 ${MODELS_DIR}/ :`)
    FILES.filter(f => !fs.existsSync(path.join(MODELS_DIR, f))).forEach(f => console.log(`    - ${f}`))
  } else {
    console.log('  模型文件全部就绪，可以启动服务了: npm start\n')
  }
}

main().catch(console.error)
