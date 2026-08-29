# Vue 浏览器端独立开发方案

## 1. 目标

让 Vue (uni-app H5) 前端代码在**浏览器环境**中独立运行、调试和开发，无需每次修改都编译 APK 并安装到 Android 设备。

期望效果：

- 浏览器打开 `http://localhost:5173` 即看到完整 UI
- 所有页面可导航，HMR 热更新
- **HTTP 直连真实后端服务器**，不走 Android JsBridge 中转
- **Bootstrap 完整跑通**：注册→激活→拉配置→连接 MQTT→登录
- **串口轮询本地模拟**：定时器随机抛出卡槽状态变化事件
- **人脸识别**：FaceAI Node.js 本地服务（或降级弹窗模拟）
- **SQLite 持久化**：Web Worker 中运行 sql.js（SQLite WASM），刷新不丢
- **零代码侵入**：不改动任何现有 `.js`/`.vue` 源码，`npm run dev:h5` 行为完全不变

---

## 2. 核心原则：真后端 + 假硬件 + 零源码修改

```
                    ┌──────────────────────────────────┐
                    │          真实后端服务器              │
                    │     HTTP API  │  MQTT Broker      │
                    └──────┬──────────┬─────────────────┘
                           │ fetch()  │ WebSocket (mqtt.js)
                    ┌──────┴──────────┴─────────────────┐
                    │    Vue H5 (浏览器主线程, 5173)      │
                    │                                    │
                    │  services/index.js  ←── 不修改     │
                    │  main.js            ←── 不修改     │
                    │  App.vue / pages/*  ←── 不修改     │
                    │    │                               │
                    │    │ Vite alias 构建时替换          │
                    │    ▼                               │
                    │  mock/bridge.js (替换 nativeBridge) │
                    │    ├─ storage → postMessage ──────┐│
                    │    ├─ http → fetch() 真实后端      ││
                    │    ├─ mqtt → mqtt.js WebSocket    ││
                    │    ├─ serial → 定时器模拟           ││
                    │    └─ face → HTTP localhost:3456  │││
                    └──────────────────────────────────┼┼┘
                                                       ││
    ┌──────────────────────────────────────────────────┘│
    │  ┌────────────────────────────────────────────────┘
    │  │         Web Worker (后台线程)                Node.js 人脸服务
    │  │         ┌──────────────────┐                ┌──────────────┐
    │  │         │  sql.js (WASM)   │                │ FaceAI SDK   │
    └──┼────────→│  SQLite 引擎     │                │ Node.js 绑定  │
       │         │  schema_meta     │                │              │
       │         │  vue_local_config│                │ /api/face/   │
       │         │  slots_snapshot  │                │   enroll     │
       │         │  ... 8 张表      │                │   recognize  │
       │         └──────────────────┘                └──────────────┘
       │         ← postMessage 返回查询结果
       │
       └────────────── HTTP fetch → 返回识别结果 ────────────────→
```

### 注入机制：Vite 插件 `resolveId` 钩子（非 `resolve.alias`）

**不改动一行源码**。mock 模式通过 Vite 自定义插件的 `resolveId` 钩子将两个模块替换。

**为什么是插件 `resolveId` 而不是 `resolve.alias`：**

`services/index.js` 和 `main.js` 中 import 的是**相对路径**（`./nativeBridge.js`、`./services/nativeBridge.js`、`./mockService.js`），Vite 的 `resolve.alias` 匹配的是 import specifier 原始字符串，无法统一匹配多种相对路径写法。`resolveId` 钩子则拿到的是**解析后的绝对路径**，无论源码怎么写都能精确拦截。

```js
// vite.config.js
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * Vite 插件：mock 模式下用 mock 实现透明替换 nativeBridge / mockService。
 * 仅 dev server 生效（command === 'serve'），生产构建永不激活。
 *
 * @param {string} command - Vite 的 command 参数：'serve' | 'build'
 */
function mockAliasPlugin(command) {
  // 双重守卫：环境变量 + 命令模式，防止任何 mock 代码进入生产构建
  const isMockDev = process.env.MOCK_DEV === 'true' && command === 'serve'

  if (!isMockDev) {
    // 返回空插件（什么也不做），避免生产环境额外开销
    return { name: 'mock-alias-disabled' }
  }

  const srcDir = path.resolve(__dirname, 'src')
  const servicesDir = path.join(srcDir, 'services')
  const nativeBridgePath = path.join(servicesDir, 'nativeBridge.js')
  const mockServicePath = path.join(servicesDir, 'mockService.js')
  const mockBridgePath = path.join(srcDir, 'mock', 'bridge.js')
  const mockServiceImplPath = path.join(srcDir, 'mock', 'service.js')

  return {
    name: 'mock-alias',
    enforce: 'pre',  // 优先于其他解析器
    resolveId(source, importer) {
      // 不处理 node_modules 和 null importer（入口文件）
      if (!importer || importer.includes('node_modules')) return null

      // 将 import 的 source 解析为绝对路径
      const resolved = path.resolve(path.dirname(importer), source)

      if (resolved === nativeBridgePath) {
        return mockBridgePath
      }
      if (resolved === mockServicePath) {
        return mockServiceImplPath
      }
      return null  // 不匹配，走默认解析
    }
  }
}

export default defineConfig(({ command }) => ({
  plugins: [mockAliasPlugin(command), uni()],
  server: { host: '0.0.0.0', port: 5173 },
  resolve: {
    alias: { '@': '/src' },
  },
}))
```

**启动方式**：

```bash
# 正常 dev（与之前完全一致，不走 mock）
npm run dev:h5

# Mock dev（新增，注入 mock 模块）
npm run dev:mock        # → MOCK_DEV=true uni

# 生产构建（无论 MOCK_DEV 是否设置，resolveId 绝不激活）
npm run build:h5        # → uni build，安全
```

**`package.json` 修改**：

```json
{
  "scripts": {
    "dev:h5": "uni",
    "dev:mock": "MOCK_DEV=true uni",
    "build:h5": "uni build",
    "build:verify": "npm run build:h5 && node scripts/verify-no-mock.js",
    "dev:mp-weixin": "uni -p mp-weixin",
    "build:mp-weixin": "uni build -p mp-weixin"
  }
}
```

### 为什么这个方案是零侵入的

| 文件 | 是否修改 | 说明 |
|------|----------|------|
| `uniapp/src/**/*.vue` | **不修改** | 所有页面组件完全不碰 |
| `uniapp/src/services/index.js` | **不修改** | 已有的 `isRelease` 检测逻辑照旧工作 |
| `uniapp/src/services/nativeBridge.js` | **不修改** | 真实桥接代码原封不动 |
| `uniapp/src/services/mockService.js` | **不修改** | 旧 mock 代码保留不动 |
| `uniapp/src/main.js` | **不修改** | `import nativeBridge` 在 dev:mock 时被 resolveId 重定向 |
| `uniapp/vite.config.js` | **修改** | 新增 mockAliasPlugin（构建配置，不影响运行时逻辑） |
| `uniapp/package.json` | **修改** | 新增 `dev:mock`、`build:verify` 脚本 + devDependencies |

`services/index.js` 中已有的代码路径：

```js
// 这段代码不修改，但 dev:mock 时 resolveId 替换的是它 import 的模块
import nativeBridge from './nativeBridge.js'    // ← resolveId 重定向为 mock/bridge.js
import { createMockService } from './mockService.js' // ← resolveId 重定向为 mock/service.js

const isRelease = window.location.hostname === '127.0.0.1'
// 浏览器 dev server → isRelease=false → 走 mock 分支
// mock 分支调用的 createMockService 实际来自 mock/service.js
```

---

## 3. 当前问题分析

### 3.1 现有模式判断

`services/index.js` 第22-24行：

```js
const isRelease = window.location.hostname === '127.0.0.1'
```

- Android WebView → `127.0.0.1` → `isRelease=true` → 走 `nativeBridge.request()` → JsBridge
- 浏览器 dev server → `localhost:5173` → `isRelease=false` → 走 mock

### 3.2 当前 mock 的 4 个阻塞问题

| 问题 | 影响 |
|------|------|
| **1. SQLite 无持久化** — `storage.query/execute` 返回空，`loadBootstrapConfig()` 始终返回 null | splash 每次都弹输入框 |
| **2. `loadSettings()` 返回假数据** — `httpGet('/device/config')` 返回 `{mock:true}`，无真实 config 字段 | 首页配置错误 |
| **3. Bootstrap 事件断裂** — mock 模拟进度但不触发 `bootstrap.success`，且不连接 MQTT | splash 卡住 |
| **4. `nativeBridge.on()` 和 `services.on()` 不同总线** — `main.js` 注册的事件收不到 | 卡槽/后端状态事件丢失 |

---

## 4. 方案设计

### 4.1 Bootstrap 流程（完整真实跑通）

依据 `docs/source-2026-07-23/Android客户端接口文档.md` V4.1 第 88-116 行：

```
用户输入 host:port (默认 card-test.quyohui.com)
  → saveBootstrapConfig(serverUrl)  → Worker sql.js 写入 vue_local_config
  → services.bootstrap({serverUrl})

  阶段0: 设备注册
    → 真实 HTTP: POST /api/v1/device/register  (@Anonymous, 无需token)
      → body: {machineId: generatedBrowserId}
      → 返回: {deviceToken, deviceCode}
      → emit bootstrap.progress {phase:REGISTERING}
    → 持久化 deviceToken + deviceCode → Worker sql.js

  阶段A: 设备激活
    → 真实 HTTP: POST /api/v1/device/activate
      → headers: {Authorization: Bearer <deviceToken>}
      → 路径A: 后台已设为ACTIVATED
        → 返回: {mqttPassword, signingKey, clientId}
        → emit bootstrap.progress {phase:ACTIVATED}
      → 路径B: 终端激活码方式
        → 返回: {registerCode}
        → emit bootstrap.progress {phase:WAITING_ACTIVATION_CODE}
        → 用户输入激活码

  阶段B: 注册码验证（仅路径B）
    → 真实 HTTP: POST /api/v1/device/verify
      → body: {activationCode}
      → 返回: {mqttPassword, signingKey, clientId}

  阶段C: 获取设备配置
    → 真实 HTTP: GET /api/v1/device/config
      → headers: {Authorization: Bearer <deviceToken>}
      → 返回: {communicationMode, totalSlots, ...}
      → emit bootstrap.progress {phase:GETTING_CONFIG}
    → 持久化 runtime config → Worker sql.js

  阶段D: MQTT 连接 + 登录
    → mqtt.js 连接 ws://card-test.quyohui.com/mqtt
      → 使用 deviceCode + mqttPassword 登录
      → emit bootstrap.progress {phase:CONNECTING_MQTT}
    → 订阅 card/{deviceCode}/down 和 card/{deviceCode}/down/response
      → emit bootstrap.progress {phase:MQTT_CONNECTED}
    → MQTT 发送 login 上行消息
      → emit bootstrap.progress {phase:LOGGING_IN}
    → 收到 loginResp (code=0)
      → emit bootstrap.progress {phase:LOGGED_IN}
      → 启动 60s 定时心跳
    → emit bootstrap.success

  → navigateToMain()
    → loadSettings() = 已缓存的真实 config
    → uni.reLaunch('/pages/index/index')
```

**mock 模式特殊处理**：
- `machineId`：生成浏览器指纹（`navigator.userAgent + screen` 的 hash），替代 Android `Settings.Secure.ANDROID_ID`
- MQTT `clientId`：serverUrl 中的 host 硬编码时从注册响应取，不依赖 config 接口（config 不返回 ws 地址）

### 4.2 HTTP 直连

mock 模式下，`mock/service.js` 的 `httpGet/httpPost` 直接用 `fetch()` 调用真实后端：

```js
async function httpGet(path) {
  const baseUrl = getServerUrl()  // 来自 sql.js 的 bootstrapConfig
  const res = await fetch(`${baseUrl}${path}`, {
    headers: { 'Authorization': `Bearer ${token}` }
  })
  return res.json()
}
```

**CORS**：已确认后端开发/测试环境开放 `Access-Control-Allow-Origin`，无需 Vite proxy。

### 4.3 MQTT 连接

浏览器 MQTT 必须走 **WebSocket**（TCP 在浏览器中不可用）。

**已确认**：后端 MQTT Broker 已开启 WebSocket 端口，mock 模式直接硬编码地址：

```js
// mock/config.js
export const MQTT_WS_URL = 'ws://card-test.quyohui.com/mqtt'
```

> 说明：`GET /api/v1/device/config` 返回的 `communicationMode` 不包含 ws 地址，mock 模式直接使用硬编码。MQTT 登录使用 deviceCode + mqttPassword（注册/激活阶段获取）。

mock 模式下，activation 完成后通过 `mqtt.js` 创建连接：

```bash
npm install mqtt@^5.6.0  # 新增依赖
```

```js
import mqtt from 'mqtt'

// 连接
const client = mqtt.connect(MQTT_WS_URL, {
  username: deviceCode,
  password: mqttPassword,
})

client.on('connect', () => {
  // 订阅下行 topic
  client.subscribe(`card/${deviceCode}/down`)
  client.subscribe(`card/${deviceCode}/down/response`)
  // 发送 login 上行消息
})
```

**心跳**：loginResp 成功后启动 60s 定时 `sendHeartbeat()`。

### 4.4 串口轮询模拟

mock bridge 内部用定时器模拟串口轮询，每隔 5000ms 随机更新卡槽状态：

```
setInterval(() => {
  // 随机选 1-3 个槽位，变更状态：EMPTY → OCCUPIED / CHARGING 等
  // 生成模拟电压/电流值
  // 通过内部事件总线触发 'cabinet.slotsSnapshot'
  // 同时写入 Worker sql.js 的 slots_snapshot 表
}, 5000)
```

取卡/还卡操作也模拟：
- `takeCard(address)`: 1 秒后设置对应槽位 `status=EMPTY`
- `returnCard(address)`: 1 秒后设置对应槽位 `status=OCCUPIED`

### 4.5 人脸识别：FaceAI Node.js 本地服务

浏览器没有 CameraX，用**本地 Node.js 进程运行 FaceAI SDK** 提供真实人脸识别。

**参考仓库**：<https://github.com/shawon100/FaceAI-Nodejs.git>

#### 搭建

```bash
git clone https://github.com/shawon100/FaceAI-Nodejs.git face-service
cd face-service
npm install
npm start   # → 启动在 localhost:3456
```

#### 架构

```
浏览器 (Vue H5)
  │  navigator.mediaDevices.getUserMedia() 采集摄像头
  │
  │  POST /api/face/enroll     {image: base64, employeeId: "E001"}
  │  POST /api/face/recognize  {image: base64}
  │  POST /api/face/search     {image: base64}
  │  DELETE /api/face/clear
  │
  ▼
localhost:3456 (Node.js 人脸服务)
  │  FaceAI SDK Node.js 绑定
  │  - 特征提取
  │  - 1:N 人脸库管理
  │  - 1:N 搜索
  │  - 活体检测
  │
  ▼
本地人脸库 (Node.js 内存)
```

#### 摄像头采集

浏览器 `navigator.mediaDevices.getUserMedia()` 直接调用设备摄像头拍照，无需额外硬件。

#### 降级方案

如果 FaceAI 服务不可用，降级为纯前端弹窗模拟（2-3 秒随机匹配员工）。

通过 `mock/config.js` 配置切换：

```js
// mock/config.js
export const FACE_MODE = 'nodejs'       // 'nodejs' | 'overlay'
export const FACE_SERVICE_URL = 'http://localhost:3456'
```

### 4.6 SQLite 持久化：Web Worker + sql.js

用 **Web Worker + sql.js**（SQLite 编译为 WebAssembly）提供完整的 SQLite 模拟。

#### 为什么用 Web Worker + sql.js

| 对比维度 | localStorage key-value | Web Worker + sql.js |
|----------|------------------------|---------------------|
| SQL 兼容 | 需手动映射 | SQL 直接执行，零改动 |
| 与 Android 对齐 | 完全不同的存储模型 | 同是 SQLite 引擎 |
| 线程模型 | 主线程同步 | Worker 后台线程（同 Android Room） |
| Schema | 无 | 完整 DDL/DML |
| 体积 | 0 | ~1.5MB WASM（仅 dev 加载） |

#### 注入路径

```
services/index.js (不修改)
  └→ storageQuery('SELECT ...', [params])
      └→ nativeBridge.request('storage.query', {sql, params})
          └→ alias 替换后 → mock/bridge.js
              └→ workerAdapter.query(sql, params)
                  └→ postMessage → sqlite.worker.js → sql.js
```

`services/index.js` 中的 `storageQuery/storageExecute` 内部调用 `nativeBridge.request('storage.query', ...)`，mock bridge 的 `request()` 方法识别 `storage.*` action，路由到 Worker。

```js
// mock/bridge.js 内部
async function request(action, payload) {
  switch (action) {
    case 'storage.query':
      return workerAdapter.query(payload.sql, payload.params)
    case 'storage.execute':
      return workerAdapter.execute(payload.sql, payload.params)
    case 'http.get':
      return mockService.httpGet(payload.path)
    case 'http.post':
      return mockService.httpPost(payload.path, payload.body)
    // ... 其他通道
  }
}
```

#### 需支持的 8 张表

| 表名 | 用途 | Vue 层是否有 CRUD |
|------|------|-------------------|
| `schema_meta` | Schema 版本 + 迁移时间戳 | ✅ `initializeSchema()` |
| `vue_local_config` | JSON 配置（bootstrap/runtime/draft） | ✅ 频繁读写 |
| `slots_snapshot` | 卡槽状态缓存 | ✅ upsert/load/delete |
| `employees` | 员工信息 | ⬜ 仅建表 |
| `face_bindings` | 人脸绑定 | ⬜ 仅建表 |
| `operations` | 操作记录 | ⬜ 仅建表 |
| `outbox_events` | 发件箱 | ⬜ 仅建表 |
| `sync_cursors` | 同步游标 | ⬜ 仅建表 |

#### 持久化策略

```
每次页面加载:
  1. Worker 启动 → 从 IndexedDB 读取上次保存的 .db 二进制
  2. 如有 → sql.js 加载已有数据库
  3. 如无 → 创建新数据库 → initializeSchema()

每次写操作后:
  1. sql.js db.export() → Uint8Array
  2. 存入 IndexedDB（Worker 内可直接访问 indexedDB API）
```

---

## 5. 文件变更

### 5.1 不修改的文件（零侵入）

以下文件**一个字符都不改**：

```
uniapp/src/services/index.js        ← 不改
uniapp/src/services/nativeBridge.js ← 不改
uniapp/src/services/mockService.js  ← 不改
uniapp/src/services/localStore.js   ← 不改
uniapp/src/main.js                  ← 不改
uniapp/src/App.vue                  ← 不改
uniapp/src/pages/**/*.vue           ← 不改
uniapp/src/state/**/*.js            ← 不改
uniapp/index.html                   ← 不改
uniapp/pages.json                   ← 不改
```

### 5.2 仅新增的文件

| 文件 | 说明 |
|------|------|
| `uniapp/src/mock/bridge.js` | Mock 版 nativeBridge 替换，实现 `request/on/off/init`，内部路由 6 通道 |
| `uniapp/src/mock/service.js` | Mock 版 createMockService，导出同名工厂函数 |
| `uniapp/src/mock/sqlite.worker.js` | Web Worker，运行 sql.js WASM，处理 `{type, id, sql, params}` |
| `uniapp/src/mock/worker-adapter.js` | 主线程侧 Worker 适配器，postMessage → Promise |
| `uniapp/src/mock/events.js` | 统一事件总线（桥接 nativeBridge.on 和 mock 内部事件） |
| `uniapp/src/mock/serial-sim.js` | 串口模拟：定时器 + 卡槽状态随机变更逻辑 |
| `uniapp/src/mock/face-service.js` | FaceAI Node.js HTTP 客户端 + face overlay DOM 管理（降级） |
| `uniapp/src/mock/config.js` | Mock 全局配置（默认 `card-test.quyohui.com`、MQTT `ws://card-test.quyohui.com/mqtt`、FaceAI `http://localhost:3456`、轮询间隔等） |

### 5.3 修改的配置文件（非源码） + 新增验证脚本

| 文件 | 变更 | 说明 |
|------|------|------|
| `uniapp/vite.config.js` | 新增 `mockAliasPlugin(command)` + `defineConfig(({command}) => ...)` | `MOCK_DEV=true` + `command==='serve'` 双重守卫 |
| `uniapp/package.json` | 新增 `dev:mock` / `build:verify` 脚本；`mqtt`/`sql.js` 加到 `devDependencies` | |
| `uniapp/scripts/verify-no-mock.js` | **新增** | 生产构建后扫描 `dist/` 确认无 mock/WASM/mqtt 泄漏 |

### 5.4 npm 依赖新增（全部 devDependencies）

```bash
cd uniapp
npm install --save-dev sql.js mqtt@^5.6.0
```

`package.json` 最终新增在 `devDependencies`（**不在 `dependencies`**）：

```json
{
  "devDependencies": {
    "@dcloudio/uni-automator": "3.0.0-3090920231225001",
    "@dcloudio/uni-cli-shared": "3.0.0-3090920231225001",
    "@dcloudio/uni-stacktracey": "3.0.0-3090920231225001",
    "@dcloudio/vite-plugin-uni": "3.0.0-3090920231225001",
    "mqtt": "^5.6.0",
    "sass": "^1.69.7",
    "sql.js": "^1.11.0",
    "vite": "^4.5.2"
  }
}
```

> **关键**：`sql.js` 和 `mqtt` 放在 `devDependencies`，不在 `dependencies`。Vite dev server 可以解析 devDependencies 中的 import，但语义上明确这些包仅开发用途。

> FaceAI Node.js 服务独立部署在项目根 `face-service/` 目录，不进入 `uniapp/package.json`。

### 5.5 sql.js Worker 打包

Vite 对 Web Worker 原生支持：

```js
// worker-adapter.js
const worker = new Worker(
  new URL('./sqlite.worker.js', import.meta.url),
  { type: 'module' }
)
```

Vite 自动处理 Worker 独立打包和 WASM 路径解析。

### 5.6 零泄漏保证（Mock 代码不进入 APK）

**问题背景**：Android APK 通过 `app/build.gradle` 的 `assets.srcDirs = [uniappDistDirectory]` 将 `uniapp/dist/build/h5/` 整个目录原样打入 APK，无任何过滤。必须确保 mock 代码、sql.js WASM、mqtt.js 绝不进入 APK。

#### 三层隔离机制

```
Layer 1: Vite 插件 resolveId 守卫
  └─ isMockDev = MOCK_DEV==='true' AND command==='serve'
     └─ build:h5 (command='build') → resolveId 永不激活
        → mock/bridge.js、mock/service.js 永远不被 import

Layer 2: Vite Tree-shaking
  └─ mock/ 目录下 8 个文件从未被入口链路 import
     └─ 不出现在 dist/build/h5/assets/*.js 中

Layer 3: devDependencies 隔离
  └─ sql.js、mqtt 在 devDependencies，不在 dependencies
     └─ 语义上明确仅开发用途；即使路径意外引用，Vite 依赖解析也会按 dev 范围处理
```

#### 验证命令

```bash
# 生产构建后，检查是否有 mock 泄露
cd uniapp
npm run build:h5

# 1. 检查 JS bundle 中是否包含 mock 模块引用
grep -r 'mock/bridge\|mock/service\|sqlite.worker\|mock-alias' dist/build/h5/ && echo "FAIL: mock leaked!" || echo "PASS: no mock code"

# 2. 检查是否包含 sql.js WASM（.wasm 文件）
find dist/build/h5/ -name '*.wasm' | grep . && echo "FAIL: sql.js WASM leaked!" || echo "PASS: no sql.js WASM"

# 3. 检查 mqtt.js 是否被打入 bundle
grep -r 'mqtt\.js\|paho-mqtt\|mqttws31' dist/build/h5/ && echo "FAIL: mqtt leaked!" || echo "PASS: no mqtt code"

# 一键验证（可在 CI 中执行）
npm run build:verify
```

> 建议：在 Git pre-push hook 或 CI pipeline 中加入 `npm run build:verify` 步骤，防止 mock 代码意外进入 APK。

---

## 6. 分阶段实现

### Phase 1: 打通 Bootstrap（直连后端 + SQLite Worker）

1. `npm install --save-dev sql.js mqtt@^5.6.0` — 安装 devDependencies
2. 创建 `mock/config.js` — 配置项（serverUrl、MQTT ws 地址等）
3. 创建 `mock/events.js` — 统一事件总线
4. 创建 `mock/sqlite.worker.js` — Worker 初始化 sql.js，处理 query/execute + schema 初始化 + IndexedDB 持久化
5. 创建 `mock/worker-adapter.js` — Promise 封装
6. 创建 `mock/bridge.js` — 实现 `request/on/off/init`，storage action 路由到 Worker
7. 创建 `mock/service.js` — 导出 `createMockService()`，bootstrap 调用真实 API
8. 修改 `vite.config.js` — 新增 `mockAliasPlugin(command)` 插件函数
9. 修改 `package.json` — 新增 `dev:mock` 和 `build:verify` 脚本
10. 创建 `scripts/verify-no-mock.js` — 构建后扫描脚本

**验证点**：
- `npm run dev:mock` → 输入地址 → 真实注册/激活 → config 存入 sql.js → 跳转首页 → 刷新不丢
- `npm run build:h5` → dist 静默成功 → `npm run build:verify` → 三项全 PASS

### Phase 2: 首页有数据 + MQTT 连接

1. `mock/bridge.js` 中 `http.get/http.post` 实现 — 直连后端 fetch
2. `mock/bridge.js` 中 mqtt 通道 — mqtt.js WebSocket 连接
3. 创建 `mock/serial-sim.js` — 定时器随机变更卡槽 + 写入 sql.js

**验证点**：首页真实数据；MQTT 连接成功；卡槽状态定时变化

### Phase 3: 人脸 + 交互完整

1. **Plan A（优先）**：搭建 FaceAI Node.js 本地服务 + 创建 `mock/face-service.js`
2. **Plan B（降级）**：纯前端 DOM 弹窗模拟（在 `mock/face-service.js` 中实现）
3. 取卡/还卡模拟 — `mock/serial-sim.js` 中处理

**验证点**：人脸按钮 → 摄像头拍照 → FaceAI 识别 / 降级弹窗可用

### Phase 4: 完善（可选）

1. 多后端地址支持（URL 参数 `?server=xxx:port`）
2. 串口模拟参数可调
3. 清理缓存按钮（重置 sql.js 数据库）

---

## 7. 启动方式

```bash
cd uniapp

# 正常 dev（与之前完全一致）
npm run dev:h5          # → http://localhost:5173，走原有 mock

# Mock dev（新增）
npm run dev:mock        # → http://localhost:5173，走增强 mock

# 直连特定后端（跳过输入框）
# http://localhost:5173/#/?server=card-test.quyohui.com:80
```

---

## 8. 已确认事项

| # | 问题 | 结论 |
|---|------|------|
| **8.1** | 后端 CORS | ✅ 后端支持，无需 Vite proxy |
| **8.2** | MQTT WebSocket | ✅ 已开放，mock 硬编码 `ws://card-test.quyohui.com/mqtt` |
| **8.3** | Bootstrap API 路径 | ✅ 见 `source-2026-07-23/Android客户端接口文档.md` §2.1-§2.5（注册→激活→验证→配置→登录） |
| **8.4** | FaceAI Node.js | ✅ 参考 <https://github.com/shawon100/FaceAI-Nodejs.git>，独立部署在 `face-service/` |
| **8.5** | 零侵入方案 | ✅ 通过，不改任何 `.js`/`.vue`，仅 vite alias + 新文件 |
| **8.6** | mqtt 版本 | ✅ 使用 `"mqtt": "^5.6.0"` |
