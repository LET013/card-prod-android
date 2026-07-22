# Codex 项目知识索引

本文是 Codex 在本仓库执行开发任务时的详细入口。根目录 `AGENTS.md` 只保留高优先级约束；本文负责解释模块边界、运行模型、外部契约和批次交付方式。

## 1. 项目目标

设备端需要在真实现场长期运行，核心目标不是“接口能调通”，而是：

- 断网、重连、重启、串口超时和后台重试时不产生重复副作用；
- UI 展示始终来自 Android 本地真实状态；
- 后台能够关联一条指令从接收、串口发送、单板应答到最终物理结果的全过程；
- 关键错误离线不丢失，网络恢复后能够补传；
- 任何未确认的硬件或后台协议都显式暴露为阻塞项，而不是用猜测维持“看起来可用”。

## 2. 技术结构

```text
uni-app / Vue 3 H5
        │ 受信 WebMessage Bridge
        ▼
JsBridge + NativeActionPolicy + NativeAuthManager
        │
        ▼
DeviceCoreService / DeviceOperationEngine
   ├─ WebSocketConnectionManager（MQTT）
   ├─ BackendHttpClient / Provisioning（HTTP）
   ├─ SerialConnectionManager（串口 V1.5）
   ├─ ArcFaceManager（人脸）
   ├─ DeviceDataSyncManager（人员模板同步）
   ├─ SlotStateRepository（卡槽状态）
   ├─ DeviceDataRepository（业务缓存）
   └─ DeviceEventLogRepository（当前诊断存储）
```

## 3. 数据真相来源

| 数据 | 唯一真相来源 | 禁止行为 |
|---|---|---|
| 卡槽状态 | `SlotStateRepository` | UI 根据按钮、颜色或上次操作自行推导 |
| 串口连接/轮询 | `SerialConnectionManager.snapshot()` | Vue 保存第二份状态机 |
| MQTT 认证状态 | `WebSocketConnectionManager` | 用 TCP connected 替代 authenticated |
| 操作生命周期 | `DeviceOperationEngine` | UI 与远程指令分别创建不同流程 |
| 员工/人脸/指纹缓存 | `DeviceDataRepository` | 增量结果直接覆盖完整集合 |
| 本地设置 | `NativeSettingsRepository` | 将密钥完整返回 UI 或日志 |
| 原生管理员权限 | `NativeAuthManager` + `NativeActionPolicy` | 只在页面隐藏按钮 |

## 4. 固定运行模型

### 4.1 启动编排

启动不是全串行阻塞，也不是无依赖并发：

```text
加载本地配置/状态/诊断队列
        ├─ 启动 UI 与本地快照
        ├─ 启动串口并进入已知/未知槽位管理
        ├─ 启动 ArcFace 并报告可用性
        └─ HTTP 注册/激活/拉配置
                 ↓
             MQTT 连接
                 ↓
          订阅 + LOGIN_SENT
                 ↓
           AUTHENTICATED
                 ↓
        员工/人脸/指纹同步
                 ↓
             READY / DEGRADED
```

网络不可用时允许受控本地降级，但不能静默伪装成后台在线；串口或人脸失败不应让整个 UI 永久卡在启动页。

### 4.2 操作链

```text
UI / MQTT / 人脸 / 管理员
        ↓
权限、参数、幂等校验
        ↓
创建 operationId
        ↓
串口单线程排队
        ↓
SERIAL_SENT → BOARD_ACKED
        ↓
轮询确认真实卡槽变化
        ↓
PHYSICAL_CONFIRMED
        ↓
本地 Outbox → MQTT/HTTP → 后台 ACK
```

当前第一批已建立 `operationId`、幂等和 `BOARD_ACKED` 语义，但 TAKE/RETURN 二阶段物理确认仍需后台契约冻结后完成。

## 5. 协议边界

### HTTP

用于注册、激活、配置、分页同步、文件下载、升级下载和实时通道失败后的补偿。

### MQTT

用于登录、心跳、远程指令、实时状态、业务事件和故障告警。MQTT 连接成功后必须继续完成订阅和业务登录，只有收到有效 `loginResp` 才进入 `AUTHENTICATED`。

### 串口 V1.5

功能码：

- `0x01` 查询状态
- `0x51` 开门
- `0x52` LED 亮度
- `0x53` 版本
- `0x80` 升级使能
- `0x81` 升级传输

所有命令必须串行化；自动轮询与人工命令互斥；应答必须匹配地址和功能码。

## 6. 尚未冻结的外部契约

以下内容在确认前不得自行设计成既定事实：

1. 100 个槽位是 1–100 直接地址，还是 1–10 重复地址加切组机制；若有切组，命令和上下文是什么。
2. TAKE/RETURN 最终以门开、卡状态变化还是后台业务确认为准。
3. `loginResp` 的标准字段、失败码和凭证失效处理。
4. 设备事件的 ACK 结构、去重键和批量补传接口。
5. 增量同步的版本游标、删除字段、全量快照和 tombstone 语义。
6. 后台人脸特征是否与设备虹软 SDK 版本兼容。
7. 员工级指纹是否使用外接模块；Android 系统指纹不能识别具体员工。

## 7. 推荐批次顺序

1. 安全边界、MQTT 认证、命令幂等和 operation 骨架（当前第一批）。
2. 冻结串口拓扑并实现显式地址映射。
3. TAKE/RETURN 二阶段物理确认和超时取消。
4. Room/SQLite 诊断 Outbox 与后台 ACK 补传。
5. 员工、人脸、指纹增量合并、删除和模板任务队列。
6. 运行状态编排、离线策略和运维自检。
7. 升级下载、校验、安装和串口固件升级。

不要将多个高风险批次合并成一次大重构。

## 8. Codex Skill 路由

| 任务 | 先用 Skill |
|---|---|
| 跨模块设计、重构、调用链分析 | `$card-cabinet-architecture-guardian` |
| Service、WebView、ArcFace、Android 生命周期 | `$android-device-integration` |
| 串口协议、轮询、地址、开门 | `$workcard-serial-v15` |
| HTTP、MQTT、登录、签名、同步触发 | `$backend-contract-mqtt-http` |
| 开门、取卡、还卡、批量操作状态 | `$device-operation-state-machine` |
| 错误上报、持久化、补传、脱敏 | `$diagnostics-outbox` |
| 提交、PR、构建和发布验收 | `$device-release-gate` |

跨模块任务先使用架构守卫，再使用一个或多个专项 Skill，最后使用发布门禁。

## 9. 标准任务模板

向 Codex 下发任务时推荐使用：

```text
先阅读 AGENTS.md、docs/CODEX_PROJECT_GUIDE.md 和相关 Skill。
本批目标：<唯一目标>。
明确不在范围：<不改内容>。
先输出：调用链、数据真相来源、状态变化、失败路径、兼容风险、文件级计划。
未经确认不得改变外部协议语义。
完成后运行专项测试和 device-release-gate，更新文档并保持 main 不变。
```

## 10. 测试与完成标准

基础检查：

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

高风险功能至少验证：

- 断网启动与网络恢复；
- MQTT 重复指令和设备重启后的去重；
- 串口超时、错误帧、粘包、分包和轮询恢复；
- 无可用槽位时拒绝开门；
- 管理员会话过期后原生调用被拒绝；
- 人脸模板导入失败后仍可重试；
- 关键事件离线保存、重启续传和后台 ACK 删除；
- 页面刷新后不覆盖原生状态。

“能编译”不是完成。只有业务语义、异常路径、测试、文档和可回滚交付都满足时才可以结束任务。
