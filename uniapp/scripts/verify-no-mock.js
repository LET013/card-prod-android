#!/usr/bin/env node

/**
 * verify-no-mock.js — 生产构建隔离验证
 *
 * 在 npm run build:h5 后运行，检查 dist/ 目录中是否包含
 * mock 代码、sql.js WASM 或 mqtt.js，防止泄漏到 Android APK。
 *
 * 用法: node scripts/verify-no-mock.js [distDir]
 */

const fs = require('fs')
const path = require('path')
const distDir = process.argv[2] || path.resolve(__dirname, '../dist/build/h5')

let pass = 0
let fail = 0
const errors = []

function check(name, predicate, detail) {
  if (predicate) {
    console.log(`  ✅ ${name}`)
    pass++
  } else {
    console.log(`  ❌ ${name} — ${detail}`)
    errors.push({ name, detail })
    fail++
  }
}

// 递归扫描目录中的所有文件
function scanDir(dir, pattern) {
  const results = []
  if (!fs.existsSync(dir)) return results

  const entries = fs.readdirSync(dir, { withFullPath: true }) // Node 22+
  // compat with older Node
  const compatEntries = fs.readdirSync(dir)
  for (const entry of compatEntries) {
    const fullPath = path.join(dir, entry)
    if (pattern.test(entry)) {
      results.push(fullPath)
    }
    if (fs.statSync(fullPath).isDirectory()) {
      results.push(...scanDir(fullPath, pattern))
    }
  }
  return results
}

// 检查 JS 文件内容
function grepFiles(dir, regex) {
  const results = []
  if (!fs.existsSync(dir)) return results

  const entries = fs.readdirSync(dir)
  for (const entry of entries) {
    const fullPath = path.join(dir, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      results.push(...grepFiles(fullPath, regex))
    } else if (stat.isFile() && (entry.endsWith('.js') || entry.endsWith('.mjs') || entry.endsWith('.css'))) {
      try {
        const content = fs.readFileSync(fullPath, 'utf-8')
        if (regex.test(content)) {
          results.push(fullPath)
        }
      } catch (e) {
        // ignore binary files
      }
    }
  }
  return results
}

console.log(`\n🔍 Verifying production build isolation...`)
console.log(`   Dist directory: ${distDir}\n`)

if (!fs.existsSync(distDir)) {
  console.error(`   ❌ Dist directory not found: ${distDir}`)
  console.error(`   Run 'npm run build:h5' first.\n`)
  process.exit(1)
}

// Check 1: No .wasm files (sql.js)
const wasmFiles = scanDir(distDir, /\.wasm$/)
check('No .wasm files (sql.js)', wasmFiles.length === 0,
  `Found: ${wasmFiles.join(', ')}`)

// Check 2: No mock source references in JS bundles
const mockRefs = grepFiles(distDir, /mock[/-](bridge|service|data|sqlite\.worker|face-service|serial-sim|mqtt-sim|events|config|worker-adapter)|mockService|MOCK_DEVICE|Mock service creation failed/)
check('No mock module references in bundle', mockRefs.length === 0,
  `Found in: ${mockRefs.join(', ')}`)

// Check 3: No mock alias plugin references
const mockAlias = grepFiles(distDir, /mock-alias(-disabled)?/)
check('No mock-alias plugin in bundle', mockAlias.length === 0,
  `Found in: ${mockAlias.join(', ')}`)

// Check 4: No mqtt.js reference
const mqttRefs = grepFiles(distDir, /mqtt\.js|paho-mqtt|mqttws31|require\("mqtt"\)|from\s+['"]mqtt['"]/)
check('No mqtt.js references in bundle', mqttRefs.length === 0,
  `Found in: ${mqttRefs.join(', ')}`)

// Check 5: No sql.js reference
const sqliteRefs = grepFiles(distDir, /sql\.js|require\("sql\.js"|from\s+['"]sql\.js['"]|initSqlJs/)
check('No sql.js references in bundle', sqliteRefs.length === 0,
  `Found in: ${sqliteRefs.join(', ')}`)

// Check 6: node_modules not bundled
const nodeModulesDir = path.join(distDir, 'node_modules')
check('No node_modules leaked into dist', !fs.existsSync(nodeModulesDir),
  `Found: ${nodeModulesDir}`)

// Summary
console.log(`\n──────────────────────────────────`)
console.log(`  PASS: ${pass}  |  FAIL: ${fail}`)
console.log(`──────────────────────────────────\n`)

if (fail > 0) {
  console.error('❌ BUILD VERIFICATION FAILED — mock code may have leaked into production APK!\n')
  process.exit(1)
} else {
  console.log('✅ BUILD VERIFICATION PASSED — no mock code in production build.\n')
  process.exit(0)
}
