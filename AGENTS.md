# 工作卡柜设备端：Codex 项目指引

本仓库是 Android 原生设备层与 uni-app H5 UI 组合的工作卡柜终端。任何修改都必须优先保证真实设备状态、串口安全、后台协议兼容和离线可恢复性。

## 开始任务前

1. 先阅读 `docs/CODEX_PROJECT_GUIDE.md`。
2. 阅读与任务对应的 `.agents/skills/*/SKILL.md`；复杂任务先使用 `$card-cabinet-architecture-guardian`。
3. 先输出调用链、数据真相来源、状态变化、失败路径和文件级计划，再改代码。
4. 外部协议不明确时停止猜测，记录阻塞项，不用“临时映射”或静默回退掩盖问题。

## 架构不变量

- Vue/uni-app 只负责交互与展示，不维护硬件或后台业务真相。
- Vue 中的 `appState` 只能是当前 WebView 生命周期内的展示投影；禁止持久化或从 H5 Storage 恢复卡槽、员工、运行状态和同步状态。
- Android Repository 维护本机真实状态；UI 和后台只读取快照或订阅事件。
- `JsBridge` 只负责可信消息解析和响应序列化；所有原生动作必须进入 `DeviceApplicationFacade`。
- UI、MQTT、人脸和管理员动作必须进入同一套 Android 业务入口。
- HTTP/MQTT/串口只负责收发，不在通信类中复制业务决策。
- 卡槽状态只以 `SlotStateRepository` 为准，未确认状态必须显示 UNKNOWN/STALE/FAULT。
- 单板开门 ACK 只表示 `BOARD_ACKED`，不等于卡已取走或已归还。
- 所有远程副作用指令必须具备 `msgId` 幂等；所有设备操作必须具备 `operationId`。
- 关键错误必须先本地持久化，再异步上报；不得依赖在线即时发送作为唯一记录。

## 绝对禁止

- 禁止 Vue 根据按钮点击、页面缓存、Mock 或上一次操作自行创建、删除或修改员工/卡槽业务记录。
- 禁止 `JsBridge` 直接调用 Repository、`DeviceCoreService`、通信 Manager 或自行实现业务分支。
- 未确认硬件拓扑前，禁止用取模把 100 个 `slotId` 映射到 10 个串口地址。
- 禁止假设存在未写入协议文档的切组、广播、一键开门或状态码。
- 禁止把 MQTT `CONNECTED` 当作业务登录成功；必须等待 `AUTHENTICATED`。
- 禁止在 Release 中因原生失败而静默切换 Mock。
- 禁止仅在 UI 隐藏按钮来实现权限；Android 执行入口必须再次鉴权。
- 禁止上传人脸图片、虹软特征、密码、密钥、身份证号或完整敏感请求体到诊断平台。
- 禁止直接修改 `main`、自动合并 PR、绕过失败测试或提交临时补丁载荷。

## 主要模块

- `app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java`：WebView 可调用的唯一 Android 应用/数据门面。
- `app/src/main/java/com/xingyao/card/service/DeviceCoreService.java`：设备生命周期与业务组件装配。
- `app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java`：MQTT 传输和认证状态。
- `app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java`：串口单线程调度与 V1.5 帧处理。
- `app/src/main/java/com/xingyao/card/core/SlotStateRepository.java`：卡槽状态唯一真相来源。
- `app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java`：员工、人脸、指纹同步。
- `app/src/main/java/com/xingyao/card/core/DeviceOperationEngine.java`：操作生命周期。
- `app/src/main/java/com/xingyao/card/JsBridge.java`：受信 WebMessage 与 Facade 的薄适配器。
- `uniapp/src/state/appState.js`：不持久化的 UI 内存投影。
- `uniapp/src/services/index.js`：UI 对 Native Facade 的调用封装和投影替换。

## 修改流程

1. 明确本批唯一目标和不在范围内的内容。
2. 画出入口 → Facade/业务层 → 通信层 → Repository → UI/后台上报的调用链。
3. 列出兼容性、重启、断网、超时、重复投递和并发场景。
4. 优先最小修改；不要在同一批同时重构串口、同步、UI 和数据库。
5. 新行为必须有测试或可重复验证步骤。
6. 更新相关文档，特别是协议语义、状态机和外部阻塞项。
7. 审查 `git diff`，确认没有凭证、生成物、临时补丁或无关 UI 变化。

## 必须运行的检查

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

涉及 UI 时还要构建 H5 并检查目标平板分辨率；涉及串口、MQTT、同步或诊断时，运行对应 Skill 规定的专项测试矩阵。

## Git 与交付

- 从 `main` 创建独立 `fix/` 或 `feature/` 分支。
- 每批只提交已验证的真实源码；不提交 `.batch*`、临时日志、APK、密钥或本地配置。
- 默认创建 Draft PR，PR 中说明根因、修改、兼容影响、验证和未解决外部契约。
- 完成不等于代码能编译；必须满足 `$device-release-gate` 的完成条件。

## Code Review Rules

- 标记任何把传输成功误当成业务成功的代码。
- 标记任何绕过 Repository、直接让 UI 推导或持久化业务状态的代码。
- 标记任何绕过 `DeviceApplicationFacade` 的 WebView 原生调用。
- 标记任何没有幂等、超时、取消或重启恢复语义的副作用操作。
- 标记任何吞掉异常、只写 Logcat、静默 Mock、无限重试或无限队列的实现。
- 标记任何未经确认的硬件地址映射、协议字段或后台响应结构。
