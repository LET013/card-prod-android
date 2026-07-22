# 工作卡柜设备端：Codex 项目指引

本仓库是 Android 原生设备数据层、设备通信层与 uni-app H5 UI 组成的工作卡柜终端。完整分层规范见 `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`。

## 开始任务前

1. 阅读 `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md` 和 `docs/CODEX_PROJECT_GUIDE.md`。
2. 阅读对应 `.agents/skills/*/SKILL.md`；跨层任务先用 `$card-cabinet-architecture-guardian`。
3. 先列入口、唯一真相、依赖方向、状态变化、失败路径和文件级计划，再改代码。
4. 外部协议不明确时停止猜测，记录阻塞项。

## 三层固定结构

```text
UI层（Vue）
→ JsBridge / DeviceApplicationFacade
→ Android数据/业务层（Map唯一真相）
→ 通信层（串口、MQTT/TCP、HTTP、ArcFace适配）
```

依赖只能向下。通信结果必须先回到 Android 数据层并提交状态，之后才能通知 UI。

## UI 层不变量

- Vue 只负责页面、弹窗、Tab、筛选、排序、动画、临时选中项和未提交表单草稿。
- `appState` 只是当前 WebView 生命周期的展示投影，不是数据源。
- 卡槽、员工、运行状态、同步状态和操作状态禁止写入或从 H5 Storage 恢复。
- Android 返回空集合时必须清空 UI 投影。
- 页面只能经 `services/index.js → nativeBridge.js` 调用 Android。
- Release 原生失败时禁止静默切换 Mock。

## Android 数据/业务层不变量

- `DeviceApplicationFacade` 是 WebView 唯一 Android 入口。
- `DeviceDataLayer` 是 UI、人脸、MQTT 和管理员动作共用的业务入口。
- `DeviceStateStore`、`SlotStateRepository`、`DeviceDataRepository` 的 Map 是运行时唯一真相。
- `DeviceOperationEngine` 维护操作状态；`DeviceCommandCoordinator` 处理远程命令和业务上报。
- 通信回调必须先写 Store/Repository，再发 UI 事件。
- 所有远程副作用必须有 `msgId` 幂等；所有设备操作必须有 `operationId`。
- 单板 ACK 只表示 `BOARD_ACKED`，不等于物理取还卡完成。

## 通信层不变量

- `SerialConnectionManager` 只负责串口连接、排队、帧编解码和应答匹配。
- `WebSocketConnectionManager` 只负责 MQTT/TCP 连接、订阅、登录、心跳、Envelope 和收发。
- `BackendHttpGateway`/`BackendHttpClient` 只负责 HTTP 请求和下载。
- 通信类只通过 Listener/Port 返回结构化结果，不得引用 Activity、Bridge、Facade、Store 或业务 Coordinator。
- 通信层不得决定员工、卡槽、取还卡等业务结果，也不得直接通知 Vue。

## Service 与 Activity

- `DeviceCoreService` 只允许前台 Service 生命周期、创建/注入组件和启动/停止组件。
- 禁止在 Service 中保留静态业务 API、MQTT 命令处理、业务事件组装、HTTP 上报或 UI 通知。
- `MainActivity` 只负责 WebView、Service 启动和必须依赖 Activity 的相机/系统指纹 UI。
- Activity 的人脸/指纹结果必须回到 `DeviceDataLayer` 后才能更新业务 Map。

## 契约证据规则

- 每个后端字段、路径、请求方法、枚举和响应语义必须能指向仓库原始文档或用户明确确认。
- 文档未定义时只能留空、禁用或写入 `docs/CONTRACT_EVIDENCE_REGISTER.md`，禁止兼容猜测。
- 本地工程状态可以有 `operationId` 等内部字段，但未经文档确认不得进入 HTTP/MQTT payload。
- 不得用测试 IP、常见端口、猜测 username、时间窗口或 slot 映射制造“可用”。
- 文档冲突时停止执行相关功能，不自行选择解释。

## 绝对禁止

- Vue 自行创建、删除、恢复或持久化员工/卡槽业务记录。
- `JsBridge` 绕过 `DeviceApplicationFacade`。
- Facade 绕过 `DeviceDataLayer` 调 Service、Repository 或通信实现。
- Activity 调用 `DeviceCoreService` 静态业务方法。
- 通信 Manager 直接引用 UI 或修改业务 Repository。
- 业务类直接 `new BackendHttpClient` 绕过 `BackendHttpGateway`。
- 未确认拓扑前用取模把 100 个 `slotId` 映射到 10 个地址。
- 把 MQTT connected 当作 `AUTHENTICATED`。
- 仅隐藏 UI 按钮实现权限。
- 上传人脸图片、特征、密码、密钥或身份证号到诊断平台。
- 修改 `main`、自动合并 PR、绕过测试或提交临时迁移文件。

## 主要模块

- `core/DeviceApplicationFacade.java`：WebView 唯一 Android 门面。
- `core/DeviceRuntimeRegistry.java`：UI 到当前数据层实例的连接。
- `core/DeviceDataLayer.java`：统一业务入口。
- `core/DeviceStateStore.java`：运行状态与 UI 通知源。
- `core/DeviceCommandCoordinator.java`：MQTT 命令、幂等和业务上报。
- `core/SlotStateRepository.java`：卡槽 Map。
- `core/DeviceDataRepository.java`：员工/人脸/指纹 Map。
- `core/SerialConnectionManager.java`：串口通信。
- `core/WebSocketConnectionManager.java`：MQTT/TCP 通信。
- `core/BackendHttpGateway.java`：HTTP 通信。
- `service/DeviceCoreService.java`：生命周期和依赖装配。
- `uniapp/src/state/appState.js`：UI 内存投影。

## 必须运行

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
```

永久 CI 还会检查完整三层依赖方向、临时文件、H5 产物和 APK。

## Git 与交付

- 只在独立 `fix/` 或 `feature/` 分支工作。
- 默认 Draft PR，不自动合并。
- 不提交 `.refactor`、`.batch*`、临时工作流、APK、密钥或本地配置。
- 只有业务语义、异常路径、分层门禁、测试、H5 和 APK 全部通过才可称为完成。
