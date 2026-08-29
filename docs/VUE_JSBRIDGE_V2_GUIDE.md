# Vue 层 JsBridgeV2 通信指南

## 概述

JsBridgeV2 是 Android 与 Vue H5 之间的桥接通信层。它**将 Android 原生能力暴露为六大原始通道**，所有业务逻辑归还 Vue 层处理，JsBridge 不再承担业务编排职责。

```
Vue H5 (uni-app)                     Android (JsBridgeV2)
     │                                      │
     │  request({id, action, payload})      │
     │ ──────────────────────────────────→  │
     │                                      │
     │  response({requestId, success, ...}) │
     │ ←──────────────────────────────────  │
     │                                      │
     │  event({type:"event", event, data})  │
     │ ←───────── (主动推送) ────────────── │
```

## 六大通道

| 通道 | action 前缀 | 用途 |
|------|------------|------|
| **Bootstrap** | `bootstrap.` | 设备启动流程：注册、激活、配置、MQTT 连接 |
| **HTTP** | `http.` | REST API 调用（GET/POST/Download），支持同步/异步 |
| **MQTT** | `mqtt.` | MQTT 消息发送、登录态查询、下行消息监听注册 |
| **Serial** | `serial.` | 串口指令发送、日志查询、帧订阅 |
| **Storage** | `storage.` | SQLite 数据库查询与执行 |
| **Face** | `face.` | 人脸识别与录入 |

---

## 消息格式

### 1. Vue → Java（请求）

```json
{
  "id": "req_001",           // 请求唯一 ID，响应时回传
  "action": "http.get",      // 通道 action
  "payload": {               // 请求参数
    "path": "/device/config"
  }
}
```

### 2. Java → Vue（响应）

```json
{
  "type": "response",
  "requestId": "req_001",    // 与请求中的 id 对应
  "success": true,           // 是否成功
  "data": { ... }            // 成功时携带的数据
}
```

```json
{
  "type": "response",
  "requestId": "req_001",
  "success": false,
  "code": "HTTP_ERROR",      // 错误码
  "message": "..."
}
```

### 3. Java → Vue（事件/主动推送）

```json
{
  "type": "event",
  "event": "slot.status",    // 事件名
  "data": {                  // 事件数据
    "slotNumber": 1,
    "status": "FULL"
  }
}
```

---

## 入口文件与初始化

### Vue 侧文件结构

```
uniapp/src/
├── main.js                 ← createApp() 中统一初始化（推荐）
├── services/
│   ├── nativeBridge.js      ← 底层桥接层（request/on/init）
│   └── index.js             ← 业务服务层（封装好的 API）
└── state/
    └── appState.js          ← Vue 响应式状态投影
```

### 初始化（main.js 中）

```javascript
import nativeBridge from './services/nativeBridge.js'

export function createApp() {
  const app = createSSRApp(App)

  // 1. 初始化 bridge（注册 window.NativeBridge.receive 入口）
  nativeBridge.init()

  // 2. 注册事件监听器
  nativeBridge.on('slot.status', (data) => {
    console.log('卡槽状态:', data.slotNumber, data.status)
  })
  nativeBridge.on('mqtt.message', (data) => {
    console.log('MQTT 消息:', data.cmd, data.data)
  })
  // ... 其他监听器

  // 3. 发起请求（示例：加载设备配置）
  nativeBridge.request('http.get', { path: '/device/config' })
    .then(res => console.log(res))
    .catch(err => console.error(err))

  return { app }
}
```

**关键注意**：旧版 App.vue 的 `onLaunch` 在 uni-app H5 模式下可能不触发，建议在 `main.js` 的 `createApp()` 中统一初始化。

---

## nativeBridge 底层 API

所有方法来自 `uniapp/src/services/nativeBridge.js`。

### `nativeBridge.init()`

初始化桥接层，在 `window` 上注册 `NativeBridge.receive` 方法。**必须在任何请求之前调用。**

```javascript
nativeBridge.init()  // 幂等，重复调用安全
```

### `nativeBridge.request(action, payload, timeout?)`

向 Android 发送请求并等待响应。返回 Promise。

```javascript
// 基本用法
const data = await nativeBridge.request('http.get', { path: '/device/config' })

// 自定义超时（默认 30000ms）
const data = await nativeBridge.request('http.get', { path: '/device/config' }, 5000)

// 错误处理
try {
  const data = await nativeBridge.request('http.get', { path: '/api/no-exist' })
} catch (err) {
  console.error(err.code, err.message)
  // 常见错误码: TIMEOUT, CHANNEL_UNAVAILABLE, HTTP_ERROR, ...
}
```

### `nativeBridge.requestAsync(action, payload?)`

发送请求但不等待响应（fire-and-forget）。

```javascript
nativeBridge.requestAsync('serial.send', { hex: 'AABBCC' })
```

### `nativeBridge.on(eventName, callback)`

注册事件监听器。返回取消监听的函数。

```javascript
const unsubscribe = nativeBridge.on('slot.status', (data, eventName) => {
  console.log(`事件: ${eventName}`, data)
})

// 取消监听
unsubscribe()
// 或者
nativeBridge.off('slot.status', callback)
```

### `nativeBridge.off(eventName, callback)`

移除特定事件监听器。

### `nativeBridge.waitForChannel(maxWaitMs?)`

等待 Android 消息通道就绪（默认最多等 5s）。

```javascript
const ready = await nativeBridge.waitForChannel()
if (!ready) {
  console.error('Android 消息通道未就绪')
}
```

### `nativeBridge.isChannelReady()`

同步检查消息通道是否就绪。

### `nativeBridge.destroy()`

清理所有未完成的请求和监听器。

---

## 六大通道详细 API

### 1. Bootstrap 通道 — 设备启动

#### `bootstrap.start`

启动设备引导流程（注册 + 激活 + 获取配置 + MQTT 连接）。

**请求**：
```javascript
nativeBridge.request('bootstrap.start', {
  serverUrl: 'http://192.168.1.100:8800'
})
```

**响应**：`{ accepted: true }`

**相关事件**（由启动流程自动推送）：

| 事件名 | 触发时机 | payload |
|--------|---------|---------|
| `bootstrap.progress` | 各阶段进度 | `{ phase: "VERSION_CHECK"\|"REGISTERING"\|..., message: "..." }` |
| `bootstrap.error` | 启动出错 | `{ phase: "ERROR", code: "BOOTSTRAP_ERROR", message: "..." }` |
| `bootstrap.config` | RUNNING 阶段 | `{ communicationMode: "...", rawConfig: {...} }` |
| `bootstrap.success` | 启动完成 | `{ phase: "RUNNING", deviceCode: "..." }` |

**phase 枚举**：`VERSION_CHECK` → `REGISTERING` → `REGISTERED` → `WAITING_ACTIVATION_CODE` → `ACTIVATING` → `ACTIVATED` → `GETTING_CONFIG` → `CONNECTING_MQTT` → `MQTT_CONNECTED` → `LOGGING_IN` → `LOGGED_IN` → `RUNNING`

#### `bootstrap.activate`

输入激活码验证。

```javascript
nativeBridge.request('bootstrap.activate', { code: '123456' })
```

#### `bootstrap.retry`

重试启动流程。

```javascript
nativeBridge.request('bootstrap.retry')
```

#### `bootstrap.cancel`

取消启动流程。

```javascript
nativeBridge.request('bootstrap.cancel')
```

---

### 2. HTTP 通道 — REST API

**路径自动补全**：不带 `http://` / `https://` 前缀的路径会自动加上 `/api/v1` 前缀。

#### `http.get`

GET 请求（同步模式，等待 Java 层 IO 线程执行完毕）。

```javascript
// 路径: /api/v1/device/config
const res = await nativeBridge.request('http.get', {
  path: '/device/config'
})
// res = { status: 200, body: {...} }
```

#### `http.post`

POST 请求。

```javascript
const res = await nativeBridge.request('http.post', {
  path: '/employee/search',
  body: { keyword: '张三' }
})
// res = { status: 200, body: {...} }
```

#### `http.multipart`

通用 `multipart/form-data` POST。当前人脸照片上传会在 Vue 校验并由桥再次强制限制解码后不超过 10 MB。

```javascript
const res = await nativeBridge.request('http.multipart', {
  path: '/api/v1/employee/face/image',
  fields: { employeeId: '1001' },
  file: {
    fieldName: 'file',
    fileName: 'face.jpg',
    mimeType: 'image/jpeg',
    base64: '...'
  }
})
```

当前 1:N 录入先用临时 FaceAI ID 采集，再用上面的通用能力上传真实照片并校验 `fileHash`，随后由 Vue 通过 `http.post` 调用 `/api/v1/employee/face` 上报 `employeeId + faceFeature + faceImagePath + fileHash`。服务端返回 `faceId` 后，Vue 才计算 `${employeeId}_${faceId}` 并替换最终模板、保存 SQLite。任一步失败不宣告本机成功；两步写请求不自动重放。

#### `http.download`

文件下载（同步等待下载完成，最长 60s）。

```javascript
const res = await nativeBridge.request('http.download', {
  path: '/firmware/v1.0.0.bin',
  targetDir: '/sdcard/Download/'  // 可选，默认外部存储目录
})
// res = { status: 200, filePath: "/sdcard/Download/v1.0.0.bin", size: 12345 }
```

#### 异步模式

设置 `mode: "async"` 后，请求立即返回 `{ accepted: true }`，实际结果通过事件推送。

```javascript
// 发送异步请求
const ack = await nativeBridge.request('http.get', {
  path: '/firmware/list',
  mode: 'async',
  requestId: 'my_async_001'   // 用于匹配结果事件
})
// ack = { accepted: true, requestId: "my_async_001" }

// 监听异步结果
nativeBridge.on('http.result.my_async_001', (data) => {
  console.log('异步结果:', data)
  // data = { status: 200, body: {...}, requestId: "my_async_001" }
})
```

#### HTTP 错误响应格式

```javascript
// HTTP 层错误（非 2xx）
{ status: 401, body: {...}, error: "HTTP 401: Unauthorized" }
```

---

### 3. MQTT 通道 — 消息收发

#### `mqtt.send`

发送 MQTT 消息。

```javascript
await nativeBridge.request('mqtt.send', {
  cmd: 'device.heartbeat',
  data: { timestamp: Date.now() }
})
// → { sent: true }
```

#### `mqtt.loginStatus`

查询 MQTT 登录状态。

```javascript
const status = await nativeBridge.request('mqtt.loginStatus')
// status = { connected: true }
```

#### `mqtt.handleMessage`

注册对特定 cmd 的 MQTT 下行消息监听。**必须先注册才能收到对应 cmd 的消息。**

```javascript
await nativeBridge.request('mqtt.handleMessage', {
  cmd: 'employee.sync'
})
// → { registered: true, cmd: "employee.sync" }
```

#### MQTT 相关事件

| 事件名 | 触发时机 | payload |
|--------|---------|---------|
| `mqtt.message` | 收到已注册 cmd 的下行消息 | `{ cmd: "employee.sync", msgId: "...", timestamp: ..., data: {...} }` |
| `mqtt.connected` | MQTT 连接成功 | `{ broker: "tcp://...", timestamp: ... }` |
| `mqtt.disconnected` | MQTT 断开 | `{ broker: "tcp://...", timestamp: ... }` |

**使用示例**：
```javascript
// 注册感兴趣的 cmd
await nativeBridge.request('mqtt.handleMessage', { cmd: 'employee.sync' })
await nativeBridge.request('mqtt.handleMessage', { cmd: 'cabinet.command' })

// 监听下行消息
nativeBridge.on('mqtt.message', (data) => {
  switch (data.cmd) {
    case 'employee.sync':
      console.log('员工同步:', data.data)
      break
    case 'cabinet.command':
      console.log('柜子指令:', data.data)
      break
  }
})
```

---

### 4. Serial 通道 — 串口通信

#### `serial.status`

查询当前 Android 串口能力状态。

```javascript
const status = await nativeBridge.request('serial.status')
// status = {
//   state: 'CONNECTED',
//   port: 'simulator://10',
//   protocol: 'WORK_CARD_V1.5',
//   simulator: true,
//   totalSlots: 10
// }
```

`simulator` 仅用于显式开启的调试构建；正式构建固定为 `false`。客户端不得因真实串口失败自动切换模拟器。

#### 串口调试控制

调试台通过以下通用串口能力复用 `DeviceSerialManager` 的既有协议编码和发送队列。除管理员取卡、一键弹卡等由 Vue 业务流程另行等待物理状态确认的操作外，返回 `queued: true` 只表示命令已进入发送队列，不代表卡门、卡片或单板动作完成。

```javascript
await nativeBridge.request('serial.reconnect')
await nativeBridge.request('serial.disconnect')
await nativeBridge.request('serial.openDoor', { slotNumber: 1, administrator: false })
await nativeBridge.request('serial.querySlot', { slotNumber: 1 })
await nativeBridge.request('serial.readVersion', { slotNumber: 1 })
await nativeBridge.request('serial.setLedDutyCycle', { slotNumber: 1, dutyCycle: 60 })
```

`serial.disconnect` 仅关闭当前串口并清空待发送队列，保留原生串口服务，之后可以再次调用 `serial.reconnect`。LED 占空比服从串口 V1.5 协议的 30–100 范围。

#### `serial.slotsSnapshot`

读取 Android 串口层当前已解析的内存卡槽快照，不触发额外串口命令，也不执行选卡或取卡业务判断。

```javascript
const result = await nativeBridge.request('serial.slotsSnapshot')
// result = {
//   capturedAt: 1785630000000,
//   slots: [{ slotNumber: 1, status: 'OCCUPIED', cardNo: '...', updatedAt: 1785629999900 }]
// }
```

#### `serial.send`

发送串口 HEX 指令。

```javascript
await nativeBridge.request('serial.send', {
  hex: 'A55A0102...'   // HEX 字符串
})
// → { sent: true }
```

#### `serial.getLogs`

获取最近的串口日志（环行缓冲区，最多保留 500 条）。

```javascript
const result = await nativeBridge.request('serial.getLogs', {
  count: 50   // 默认 100
})
// result = { logs: [{ type: "frame", cmd: 1, hex: "...", text: "...", timestamp: ... }, ...] }
```

#### `serial.subscribe` / `serial.unsubscribe`

订阅/取消订阅特定串口功能码的帧推送。

```javascript
// 订阅 cmd=0x03 的帧
await nativeBridge.request('serial.subscribe', { cmd: 3 })
// → { subscribed: true, cmd: 3 }

// 取消订阅
await nativeBridge.request('serial.unsubscribe', { cmd: 3 })
// → { unsubscribed: true, cmd: 3 }
```

#### 串口相关事件

| 事件名 | 触发时机 | payload |
|--------|---------|---------|
| `serial.log` | 任何串口帧（始终推送） | `{ type: "frame", cmd: 3, hex: "...", text: "...", timestamp: ... }` |
| `serial.frame` | 已订阅的 cmd 帧 | `{ cmd: 3, timestamp: ..., data: "..." }` |
| `slot.status` | 卡槽状态变化（自动推送，无需 subscribe） | `{ slotNumber: 1, status: "EMPTY\|FULL\|ILLEGAL_CARD", cardId?: "...", employeeId?: "..." }` |

**注意**：`slot.status` 事件由串口轮询自动触发，**无需显式 subscribe**。

---

### TTS 通道 — 系统语音播报

`tts.speak` 仅播放 Vue 已确定的提示文案，不接收员工、卡槽或取卡结果等业务字段。调用为异步受理，不能等待播放完成或影响取卡流程；Android 优先使用系统可用的中文离线 Voice，缺失时才使用系统默认 Voice。

```javascript
nativeBridge.requestAsync('tts.speak', {
  text: '取卡成功！3号卡槽，请取走您的卡',
  flush: true
})
```

### 5. Storage 通道 — SQLite 持久化

数据库文件：`card_vue.db`（WAL 模式）。

#### `storage.query`

执行 SELECT 查询。

```javascript
const result = await nativeBridge.request('storage.query', {
  sql: 'SELECT * FROM cache WHERE key = ?',
  params: ['user_settings']
})
// result = { rows: [{ key: "user_settings", value: "..." }], count: 1 }
```

返回字段类型自动映射：NULL → `null`，INTEGER → `number`，FLOAT → `number`，STRING → `string`，BLOB → `"BLOB"`。

#### `storage.execute`

执行 INSERT / UPDATE / DELETE / CREATE TABLE（自动包装事务）。

```javascript
const result = await nativeBridge.request('storage.execute', {
  sql: 'INSERT INTO cache (key, value, updated_at) VALUES (?, ?, ?)',
  params: ['user_settings', '{"lang":"zh"}', Date.now()]
})
// result = { affectedRows: 1 }
```

**安全注意**：参数化查询使用 `?` 占位符，防止 SQL 注入。

---

### 6. Face 通道 — 人脸识别与录入

人脸操作互斥：同一时间只能有一个正在进行的人脸操作。

#### `face.recognition.start`

启动人脸识别（1:N 搜索）。

```javascript
await nativeBridge.request('face.recognition.start', {
  threshold: 0.8   // 识别阈值 (0~1)，默认 0.8
})
// → { accepted: true }
```

识别成功后，Android 先停止 FaceAISDK 搜索与 CameraX 分析器并隐藏原生摄像头容器，随后立即发送 `face.recognized`，不再保留人工最短展示时间，也不显示会遮挡 Vue 取卡进度的原生成功 Toast。识别后的选卡、取卡状态机和取卡提示统一由 Vue 管理。

`searchTimeout` 保留后端 config 的原始配置语义；Android 在调用 FaceAISDK 时会将其限制到 SDK 接受的 `3000～6000ms`。`faceRecognitionTimeout` 继续作为客户端外层识别会话的取消时限。

#### `face.enrollment.start`

启动人脸录入。

```javascript
await nativeBridge.request('face.enrollment.start', {
  faceId: '10001_pending_operation123'   // 仅用于本次采集的临时 ID
})
// → { accepted: true }
```

#### `face.recognition.cancel` / `face.enrollment.cancel`

取消当前人脸操作。

```javascript
await nativeBridge.request('face.recognition.cancel')
// → { cancelled: true }
```

#### `face.template.import` / `face.template.remove`

导入或移除 FaceAISDK 本机模板。导入可使用后端特征，或由不超过 10 MB 的照片提取特征；员工归属仍由 Vue SQLite 管理。

```javascript
const imported = await nativeBridge.request('face.template.import', {
  faceId: '10001_52', // 服务端返回 faceId=52 后生成的最终 FaceAI ID
  faceFeature: 'Base64...',
  imageBase64: '...',
  sourceUrl: '/profile/face/E001.jpg'
})
// imported = { faceId, faceFeature, count }

await nativeBridge.request('face.template.remove', { faceId: '10001_52' })
```

#### 人脸相关事件

| 事件名 | 触发时机 | payload |
|--------|---------|---------|
| `face.recognized` | 识别到匹配人脸 | `{ faceId: "E001", score: 0.92 }` |
| `face.enrolled` | FaceAISDK 模板写入完成 | `{ faceId, faceFeature, faceImageBase64, faceImageSize, faceImageMimeType, score }` |
| `face.enrollment.failed` | 照片或 FaceAISDK 模板写入失败 | `{ code, message }` |
| `face.recognition.cancelled` | 识别被取消 | `{}` |
| `face.enrollment.cancelled` | 录入被取消 | `{}` |

**使用示例**：
```javascript
// 开始人脸识别
await nativeBridge.request('face.recognition.start', { threshold: 0.85 })

// 监听识别结果
nativeBridge.on('face.recognized', (data) => {
  console.log(`识别到: ${data.faceId}, 分数: ${data.score}`)
  // 这里可以调用后端 API 验证身份等
})
```

---

## 业务服务层（services/index.js）

`services/index.js` 对 nativeBridge 做了业务封装，提供更简洁的 API。**推荐在 Vue 页面中通过 services 调用，而非直接使用 nativeBridge。**

### 导入

```javascript
import { services, nativeBridge } from '@/services/index.js'
```

### 常用业务 API

```javascript
// ── 初始化 ──
await services.init({ serverUrl: '...' })
const settings = await services.loadSettings()

// ── HTTP ──
const config = await services.httpGet('/device/config')
const result = await services.httpPost('/employee/search', { keyword: '张三' })
const fileInfo = await services.httpDownload('/firmware/v1.bin')

// ── MQTT ──
await services.mqttSend('heartbeat', { ts: Date.now() })
const status = await services.mqttLoginStatus()
await services.mqttRegisterCmd('employee.sync')

// ── 人脸 ──
await services.faceRecognitionStart({ threshold: 0.85 })
await services.faceEnrollmentStart('10001_pending_operation123')
await services.faceRecognitionCancel()

// ── 串口 ──
await services.serialSend('A55A0102...')
const logs = await services.serialGetLogs(50)
await services.serialSubscribe(3)

// ── 事件 ──
services.on('slot.status', (data) => { /* ... */ })
services.on('mqtt.message', (data) => { /* ... */ })

// ── 事件取消 ──
services.off(eventName, callback)
```

### 事件监听完整示例

```javascript
import { services } from '@/services/index.js'

// 监听卡槽状态（串口实时推送）
services.on('slot.status', (data) => {
  console.log(`卡槽 ${data.slotNumber}: ${data.status}`)
  // data = { slotNumber: 1, status: "FULL", cardId: "...", employeeId?: "..." }
})

// 监听 MQTT 下行消息
services.on('mqtt.message', (data) => {
  switch (data.cmd) {
    case 'employee.sync': handleEmployeeSync(data.data); break
    case 'cabinet.open_door': handleOpenDoor(data.data); break
  }
})

// 监听人脸识别结果
services.on('face.recognized', (data) => {
  console.log(`识别: ${data.faceId}, 置信度: ${data.score}`)
})

// 监听启动进度
services.on('bootstrap.progress', (data) => {
  console.log(`启动: ${data.phase} - ${data.message}`)
})
```

---

## 完整交互示例

### 场景 1：设备启动流程

```javascript
import { services } from '@/services/index.js'

// 监听启动进度
services.on('bootstrap.progress', (data) => {
  // 更新 UI 进度条
  updateProgress(data.phase, data.message)
})

// 监听启动完成
services.on('bootstrap.success', (data) => {
  console.log('设备启动完成:', data.deviceCode)
  // 加载设置
  services.loadSettings().then(settings => {
    console.log('设备配置:', settings)
  })
})

// 发起启动
await services.bootstrap({
  serverUrl: 'http://192.168.1.100:8800'
})
```

### 场景 2：卡槽实时监控

```javascript
import { appState, upsertSlotProjection } from '@/state/appState.js'
import { services } from '@/services/index.js'

// 在 main.js createApp() 中注册
services.on('slot.status', (data) => {
  upsertSlotProjection(data)  // 更新 Vue 响应式状态，UI 自动响应
})
```

### 场景 3：人脸识别 + API 调用

```javascript
async function verifyFaceAndOperate(slotNumber) {
  // 1. 启动人脸识别
  await services.faceRecognitionStart({ threshold: 0.85 })

  // 2. 等待识别结果
  return new Promise((resolve, reject) => {
    const unsubscribe = services.on('face.recognized', async (data) => {
      unsubscribe()

      // 3. 调用后端验证
      try {
        const verifyResult = await services.httpPost('/auth/faceVerify', {
          faceId: data.faceId,
          score: data.score,
          slotNumber: slotNumber
        })
        resolve(verifyResult)
      } catch (e) {
        reject(e)
      }
    })

    // 超时处理
    setTimeout(() => {
      unsubscribe()
      services.faceRecognitionCancel()
      reject(new Error('人脸识别超时'))
    }, 30000)
  })
}
```

### 场景 4：MQTT 消息监听

```javascript
// 注册需要监听的 cmd
await services.mqttRegisterCmd('employee.sync')
await services.mqttRegisterCmd('cabinet.remote_open')

// 统一监听
services.on('mqtt.message', (data) => {
  console.log(`收到 MQTT [${data.cmd}]:`, data.data)
  if (data.cmd === 'employee.sync') {
    // 触发员工数据同步
  }
})

// 发送上行消息
await services.mqttSend('device.status', {
  temperature: 35.2,
  online: true
})
```

---

## 错误处理

### 错误码

| 错误码 | 含义 |
|--------|------|
| `TIMEOUT` | 请求超时（默认 30s） |
| `CHANNEL_UNAVAILABLE` | 消息通道未就绪 |
| `INVALID_ACTION` | action 为空 |
| `PARSE_ERROR` | JSON 解析失败 |
| `UNKNOWN_ACTION` | 未知 action |
| `HTTP_ERROR` | HTTP 请求失败 |
| `MQTT_NOT_CONNECTED` | MQTT 未连接时尝试发送 |
| `SERIAL_NOT_READY` | 串口未就绪 |
| `FACE_BUSY` | 已有正在进行的人脸操作 |
| `SQL_ERROR` | SQL 执行错误 |
| `BOOTSTRAP_NOT_READY` | 启动管理器未就绪 |

### 错误处理模式

```javascript
try {
  const data = await services.httpGet('/device/config')
  // 处理成功
} catch (err) {
  switch (err.code) {
    case 'TIMEOUT':
      console.error('请求超时，请检查网络')
      break
    case 'HTTP_ERROR':
      console.error('HTTP 错误:', err.message)
      break
    case 'CHANNEL_UNAVAILABLE':
      console.error('Android 通道未就绪')
      break
    default:
      console.error('未知错误:', err.code, err.message)
  }
}
```

---

## 模式判断

`services/index.js` 根据 `window.location.hostname` 自动判断 release/mock 模式：

- `127.0.0.1`（本地 HTTP 服务器）→ `release` 模式，走真实 Android 通道
- 其他 → `mock` 模式，使用 mockService 模拟数据

在 release 模式下**禁止切换 mock**，确保真机行为一致性。

---

## 调试

### Logcat 过滤

```bash
# 查看 Vue console 日志（WebView Tag）
adb logcat -s WebView

# 查看 JsBridgeV2 日志
adb logcat -s JsBridgeV2

# 综合查看
adb logcat WebView:I JsBridgeV2:D *:S
```

### Vue 侧日志规范

```javascript
console.log('[main] all listeners registered, hydration triggered')
console.log('[initSlots] totalSlots=10, initialized 10')
console.log('[main] slot.status received, slotNumber: 1, status: EMPTY')
```

建议使用 `[模块名]` 前缀，方便 logcat 过滤。

---

## 架构约束

1. **Vue 不直接操作 Android 数据层**：所有数据通过事件和请求-响应流转
2. **禁止绕过 services**：Vue 页面应通过 `services/index.js` 调用，不直接使用 `nativeBridge.request`
3. **事件监听在 main.js 注册**：确保 App.vue 生命周期不可靠时监听器依然生效
4. **卡槽数据由串口推送**：不要通过 HTTP 拉取卡槽状态，`slot.status` 事件会自动推送
5. **MQTT 消息需先注册 cmd**：通过 `mqtt.handleMessage` 注册后才能收到对应 cmd 的下行消息
6. **人脸操作互斥**：同时只能有一个识别或录入操作

---

## API 速查表

### 请求 actions

| action | payload 关键字段 | 响应 |
|--------|-----------------|------|
| `bootstrap.start` | `serverUrl` | `{accepted}` |
| `bootstrap.activate` | `code` | `{accepted}` |
| `bootstrap.retry` | — | `{accepted}` |
| `bootstrap.cancel` | — | `{cancelled}` |
| `http.get` | `path`[, `mode`:"async", `requestId`] | `{status, body}` |
| `http.post` | `path`, `body`[, `mode`:"async", `requestId`] | `{status, body}` |
| `http.multipart` | `path`, `fields`, `file` | `{status, body}` |
| `http.download` | `path`[, `targetDir`] | `{status, filePath, size}` |
| `mqtt.send` | `cmd`, `data` | `{sent}` |
| `mqtt.loginStatus` | — | `{connected}` |
| `mqtt.handleMessage` | `cmd` | `{registered, cmd}` |
| `serial.send` | `hex` | `{sent}` |
| `serial.reconnect` | — | 串口状态快照 |
| `serial.disconnect` | — | 串口状态快照 |
| `serial.openDoor` | `slotNumber`, `administrator` | `{success, slotNumber, mode, queued}` |
| `serial.querySlot` | `slotNumber` | `{success, slotNumber, category, queued}` |
| `serial.readVersion` | `slotNumber` | `{success, slotNumber, category, queued}` |
| `serial.setLedDutyCycle` | `slotNumber`, `dutyCycle` | `{success, slotNumber, dutyCycle, category, queued}` |
| `serial.slotsSnapshot` | — | `{capturedAt, slots}` |
| `serial.getLogs` | `count` | `{logs}` |
| `serial.subscribe` | `cmd` | `{subscribed, cmd}` |
| `serial.unsubscribe` | `cmd` | `{unsubscribed, cmd}` |
| `tts.speak` | `text`, `flush?` | `{accepted, queuedForInitialization, voiceMode}` |
| `storage.query` | `sql`, `params` | `{rows, count}` |
| `storage.execute` | `sql`, `params` | `{affectedRows}` |
| `face.recognition.start` | `threshold` | `{accepted}` |
| `face.recognition.cancel` | — | `{cancelled}` |
| `face.enrollment.start` | `faceId` | `{accepted}` |
| `face.enrollment.cancel` | — | `{cancelled}` |
| `face.template.import` | `faceId`, `faceFeature` 或 `imageBase64` | `{faceId, faceFeature, count}` |
| `face.template.remove` | `faceId` | `{removed}` |

### 事件列表

| 事件名 | 来源通道 | 说明 |
|--------|---------|------|
| `bootstrap.progress` | Bootstrap | 启动各阶段进度 |
| `bootstrap.config` | Bootstrap | 设备配置（RUNNING 阶段） |
| `bootstrap.success` | Bootstrap | 启动完成 |
| `bootstrap.error` | Bootstrap | 启动错误 |
| `mqtt.message` | MQTT | 已注册 cmd 的下行消息 |
| `mqtt.connected` | MQTT | MQTT 已连接 |
| `mqtt.disconnected` | MQTT | MQTT 已断开 |
| `serial.log` | Serial | 串口帧日志（始终推送） |
| `serial.frame` | Serial | 已订阅的 cmd 帧 |
| `slot.status` | Serial | 卡槽状态变化 |
| `http.result.{requestId}` | HTTP | 异步 HTTP 结果 |
| `face.recognized` | Face | 人脸识别成功 |
| `face.enrolled` | Face | 人脸录入完成 |
| `face.enrollment.failed` | Face | 人脸录入失败 |
| `face.recognition.cancelled` | Face | 识别被取消 |
| `face.enrollment.cancelled` | Face | 录入被取消 |
