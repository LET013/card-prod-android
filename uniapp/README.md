# 工作卡柜 uni-app UI

Vue 3 + Vite + uni-app H5 界面，作为 Android 原生 WebView 中的交互层。

## 当前职责

- 页面与弹窗 UI
- 响应式布局
- 页面路由
- 表单和角色权限交互
- 本地 Mock Service
- 本地演示数据与持久化
- 统一 Native Bridge 请求、响应与事件监听

串口、WebSocket、人脸/指纹 SDK、开门和 OTA 的长期生命周期均由 Android 原生层负责，WebView 页面不持有这些连接。

## 运行

```bash
npm ci
npm run dev:h5
```

## 构建

```bash
npm run build:h5
```

构建产物位于：

```text
dist/build/h5
```

项目根目录的 `build.sh` 会完成 H5 构建，并同步到 `app/src/main/assets`。

## Mock 登录密码

- 系统管理员：`111111`
- 运维人员：`222222`
- 开发人员：`333333`

仅用于当前 UI/桥接联调。

## Mock 开发模式

脱离 Android WebView，在浏览器中独立开发和调试 UI 层。

```bash
# 1. 安装依赖（首次）
npm ci

# 2. 启动 Mock 开发服务器
npm run dev:mock
```

### Mock 模拟了哪些能力

| 模块 | 模拟范围 | 说明 |
|------|---------|------|
| **串口** | 卡槽状态随机推送 | 12 个卡槽，每 3 秒轮询，15% 概率随机变更（EMPTY/OCCUPIED/CHARGING/ERROR）。UI 实时响应 `cabinet.slotsSnapshot` 事件 |
| **HTTP** | 后端 REST API | 模拟登录、注册、激活、心跳、配置下发等接口（基于文档 V4.1 契约） |
| **MQTT** | WebSocket 双向通信 | 模拟连接、心跳、下行命令推送 |
| **SQLite** | Web Worker + sql.js | 浏览器内存数据库，模拟员工/卡槽持久化 |
| **人脸识别** | 浏览器摄像头 + 后端服务 | 见下方「人脸识别服务」章节 |
| **Native Bridge** | 全局事件总线 | 模拟 `postMessage` / `receive` 通信，统一 `request` / `on` API |

### 关键文件

```text
uniapp/src/mock/
  ├── config.js         # 全局配置（服务器地址、超时、模式切换）
  ├── bridge.js         # Native Bridge 模拟（替换原生 JsBridge）
  ├── service.js        # HTTP API 模拟
  ├── serial-sim.js     # 串口卡槽状态模拟
  ├── mqtt-sim.js       # MQTT 连接与消息模拟
  ├── sqlite.worker.js  # Web Worker 内 sql.js 数据库
  ├── worker-adapter.js # 多表 SQLite 操作封装
  └── face-service.js   # 人脸识别前端逻辑
```

---

## 人脸识别服务

Mock 支持两种人脸识别模式，在 `src/mock/config.js` 中通过 `FACE_MODE` 切换：

- **`'nodejs'`**（默认）：通过本地 HTTP 服务做真实人脸特征提取与比对
- **`'overlay'`**：纯前端弹出层，随机返回识别结果（无需额外服务）

### 启动真实人脸识别（nodejs 模式）

需要额外启动本地 FaceAI 后端服务：

```bash
# 终端 1：启动人脸识别服务
cd uniapp/scripts/faceai-server && node server.js
# 等待约 10 秒，出现 "running on :3456" 后即可
```

```bash
# 终端 2：启动 Mock 开发服务器
cd uniapp && npm run dev:mock
```

### 人脸服务 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/face/search` | 1:N 搜索，传入 base64 图片，返回 `{matched, employeeId, score}` |
| `POST` | `/api/face/enroll` | 录入人脸，传入 `{employeeId, image}`，返回 `{success, faceId}` |
| `DELETE` | `/api/face/clear` | 清空内存人脸库 |
| `GET` | `/health` | 返回 `{status, persons, totalDescriptors}` |

### 技术栈与注意事项

- **模型**：SSD MobileNet V1 + Landmark68 + FaceRecognitionNet（128 维特征）
- **推理**：纯 CPU，每帧约 2-3 秒（无 GPU 加速）
- **识别阈值**：欧氏距离 0.6（`score = 1 - distance / 0.6`，score ≥ 0.6 才匹配）
- **依赖安装**：首次启动前需 `npm ci`（已在 `faceai-server/` 目录下安装过）
- **系统依赖**：macOS 需 `brew install cairo pango libpng jpeg giflib`（`canvas` 原生编译依赖）
- **兼容性**：face-api.js 硬编码依赖 `tfjs-core@1.7.0`，不可升级 TFJS 版本
- **降级**：服务未启动或超时时，自动降级到 overlay 模拟，不阻塞 UI

---

## 配置文件参考

`src/mock/config.js` 中的关键配置：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `SERVER_URL` | `http://card-test.quyohui.com` | 后端 API 服务器 |
| `MQTT_WS_URL` | `ws://card-test.quyohui.com/mqtt` | MQTT WebSocket 地址 |
| `FACE_MODE` | `'nodejs'` | 人脸模式：`'nodejs'` 或 `'overlay'` |
| `FACE_SERVICE_URL` | `http://localhost:3456` | 本地人脸服务地址 |
| `FACE_TIMEOUT` | `10000` | 人脸请求超时（ms） |
| `SERIAL_POLL_INTERVAL` | `3000` | 串口轮询间隔（ms） |
| `SERIAL_RANDOM_CHANGE_CHANCE` | `0.15` | 每次轮询状态变更概率 |
| `HEARTBEAT_INTERVAL` | `60000` | 心跳间隔（ms） |
| `DEBUG` | `true` | 控制台调试日志开关 |

---

## 构建与验证

```bash
# 标准 H5 构建
npm run build:h5

# 构建 + 验证（检查 Mock 代码不会泄漏到生产产物）
npm run build:verify
```

`build:verify` 会执行以下检查：
1. 构建产物中无 `src/mock/` 路径引用
2. `vite.config.js` 中未注入 mock-alias 插件
3. 产物中无 `mqtt.js` / `sql.js` 引用（仅 devDependencies）
4. `node_modules` 未泄漏到 `dist/`

产物始终输出到 `dist/build/h5/`，最终 Android 构建由项目根目录的 `build.sh` 同步到 `app/src/main/assets/`。

---

## Native Bridge

前端统一发送：

```js
window.android.postMessage(JSON.stringify({
  requestId: 'web-uuid',
  action: 'settings.load',
  payload: {}
}))
```

原生统一调用：

```js
window.NativeBridge.receive(message)
```

浏览器预览时，Service Provider 自动使用本地 Mock 实现。
