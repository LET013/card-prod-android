# Codex 项目知识索引

本文件是开发任务入口。完整依赖规则以 `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md` 为准。

## 1. 项目目标

设备端必须长期运行并满足：

- UI 始终展示 Android 本地真实状态；
- 断网、重连、重启和后台重投不产生重复副作用；
- UI、MQTT、人脸和管理员动作复用同一业务入口；
- 未确认的硬件或后台协议显式阻塞，不用猜测制造“可用”；
- 通信、数据和 UI 互不复制状态机。

## 2. 当前完整三层

```text
uni-app / Vue UI
        │ NativeBridge
        ▼
JsBridge（薄适配器）
        ▼
DeviceApplicationFacade（鉴权和唯一门面）
        ▼
DeviceDataLayer（统一业务入口）
   ├─ DeviceStateStore（运行状态/操作 Map、UI 通知）
   ├─ SlotStateRepository（卡槽 Map）
   ├─ DeviceDataRepository（员工/人脸/指纹 Map）
   ├─ DeviceOperationEngine（操作状态机）
   ├─ DeviceCommandCoordinator（MQTT 命令/幂等/上报）
   └─ DeviceDataSyncManager（同步业务协调）
        │ Ports / Gateway
        ▼
SerialConnectionManager / WebSocketConnectionManager /
BackendHttpGateway / ArcFaceManager
```

`DeviceCoreService` 只是 Android 前台运行容器，创建并注入以上对象，不是业务层。

## 3. 数据真相来源

| 数据 | 唯一真相 | UI 行为 |
|---|---|---|
| 卡槽 | `SlotStateRepository` Map | 读取快照/订阅 `cabinet.slotStatus` |
| 串口/MQTT/HTTP/人脸状态 | `DeviceStateStore.sections` | 只替换投影 |
| 操作生命周期 | `DeviceOperationEngine` + `DeviceStateStore.operations` | 不自行推进状态 |
| 员工/人脸/指纹 | `DeviceDataRepository` Map | 查询、展示，不自行增删 |
| MQTT 指令幂等 | `InboundCommandRepository` | UI 不参与 |
| 设置 | `NativeSettingsRepository` | 只显示脱敏值；保存后以 Android 返回值为准 |
| 管理会话 | `NativeAuthManager` | Vue 只持有当前页面会话投影 |

SharedPreferences 是 Android 重启备份，不是 Vue 可读取的数据源。

## 4. 固定数据流

### 串口状态

```text
SerialConnectionManager
→ Listener.onSlotStatus
→ DeviceDataLayer
→ DeviceStateStore.updateSlot
→ SlotStateRepository Map
→ UI event
```

### UI 命令

```text
Vue
→ services/index.js
→ nativeBridge.js
→ JsBridge
→ DeviceApplicationFacade
→ DeviceDataLayer
→ DeviceOperationEngine
→ SerialPort
```

### MQTT 命令

```text
WebSocketConnectionManager
→ Listener.onCommand
→ DeviceDataLayer
→ DeviceCommandCoordinator
→ InboundCommandRepository
→ 与 UI 相同的 DeviceOperationEngine
```

### HTTP 同步

```text
DeviceDataSyncManager
→ BackendHttpGateway
→ HTTP response
→ DeviceDataRepository Map
→ DeviceStateStore
→ UI event
```

业务类不得直接创建 `BackendHttpClient`。

## 5. Service 与 Activity

### `DeviceCoreService`

允许：

- 前台通知和 Service 生命周期；
- 创建 Repository、数据层和通信适配器；
- 把通信 Listener 接到 `DeviceDataLayer`；
- 启动/停止组件；
- 安装/清除 `DeviceRuntimeRegistry`。

禁止：

- 静态开门、查询、同步、员工或上报 API；
- MQTT 命令分派；
- 业务事件组装；
- HTTP 请求；
- 直接通知 WebView。

### `MainActivity`

只负责 WebView、启动 Service、系统权限、相机/系统指纹 UI，以及把 `DeviceStateStore` 事件转交 WebView。系统交互结果必须调用 `DeviceDataLayer` 更新 Android Map。

## 6. 启动模型

```text
Service 创建 Android Repository/Map
→ 创建通信适配器和 DeviceDataLayer
→ 安装 DeviceRuntimeRegistry
→ 启动串口、MQTT、ArcFace
→ 通信状态回写 DeviceStateStore
→ WebView 通过 Facade 读取公开快照
→ MQTT AUTHENTICATED 后由数据层触发同步
```

断网时 UI 仍可读取 Android Map；不能用 Mock 假装在线。

## 7. 操作链

```text
UI / MQTT / 人脸 / 管理员
→ 原生权限/参数/幂等
→ operationId
→ 串口队列
→ SERIAL_DISPATCHED
→ BOARD_ACKED
→ 后续批次：PHYSICAL_CONFIRMED
→ 后台上报
```

当前开门 ACK 仍不是最终物理取还卡成功。`cardEvent.physicalConfirmed=false` 必须保留到二阶段确认完成。

## 8. 外部契约阻塞项

以下内容不得猜测：

1. 100 槽直接寻址还是 1–10 地址加切组；
2. TAKE/RETURN 最终确认条件；
3. `loginResp` 完整字段和凭证失效规则；
4. 事件 ACK、去重和补传接口；
5. 增量同步版本、删除和 tombstone 语义；
6. 后台人脸特征与设备 SDK 版本兼容性；
7. 外接员工级指纹模块协议。

这些是功能契约问题，不允许破坏三层架构来规避。

## 9. Skill 路由

| 任务 | Skill |
|---|---|
| 跨层设计/审计 | `$card-cabinet-architecture-guardian` |
| Android 生命周期/WebView/ArcFace | `$android-device-integration` |
| 串口 | `$workcard-serial-v15` |
| MQTT/HTTP/后台契约 | `$backend-contract-mqtt-http` |
| 操作状态机 | `$device-operation-state-machine` |
| 诊断/补传 | `$diagnostics-outbox` |
| 最终验收 | `$device-release-gate` |

## 10. 标准任务模板

```text
先阅读 AGENTS.md、docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md、
docs/CODEX_PROJECT_GUIDE.md 和相关 Skill。
本批唯一目标：<目标>。
不在范围：<内容>。
先输出入口、唯一真相、依赖方向、状态变化、失败路径和文件计划。
禁止改变 UI → 数据层 → 通信层的依赖方向。
完成后运行永久 CI 等价检查并更新文档。
```

## 11. 完成标准

必须全部通过：

- 完整三层静态门禁；
- 前端 JavaScript 语法；
- uni-app H5 构建；
- Android 单元测试；
- 将本次 H5 复制到 assets 后构建 Debug APK；
- 无 `.refactor`、`.batch*` 或一次性工作流；
- `main` 未修改，Draft PR 未自动合并。
