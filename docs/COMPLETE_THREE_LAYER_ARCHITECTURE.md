# 工作卡柜完整三层架构

本项目固定分为且只分为以下三层。目录和类可以细分，但依赖方向不得改变。

```text
UI 层（uni-app / Vue）
        ↓ NativeBridge 请求、Android 状态事件
Android 数据/业务层（唯一真相、Map、校验、操作、通知）
        ↓ SerialPort / BackendPort / BackendHttpGateway
通信层（串口、MQTT/TCP、HTTP、ArcFace 硬件适配）
```

## 1. UI 层

主要文件：

- `uniapp/src/pages/**`
- `uniapp/src/components/**`
- `uniapp/src/state/appState.js`
- `uniapp/src/services/index.js`
- `uniapp/src/services/nativeBridge.js`

职责：

- 页面渲染、弹窗、Tab、筛选、排序、动画、临时选中项；
- 未提交表单草稿和当前页面交互状态；
- 通过 `nativeBridge.request(action, payload)` 向 Android 表达意图；
- 接收 Android 数据层已经提交后的快照或状态事件，并替换内存投影。

禁止：

- 使用 H5 Storage 持久化或恢复卡槽、员工、运行状态、同步状态、操作状态；
- 点击按钮后自行推导门已开、卡已取、员工已删除或生物信息已登记；
- 页面直接调用 `window.android.postMessage`；
- 在 Release 中因原生失败切换 Mock；
- 在 Vue 中实现串口、MQTT、HTTP 或业务状态机。

`appState` 不是数据层，只是当前 WebView 生命周期内的展示投影。Android 返回空集合时必须显示空集合。

## 2. Android 数据/业务层

核心类：

- `DeviceApplicationFacade`：WebView 唯一 Android 入口和原生鉴权；
- `DeviceRuntimeRegistry`：Activity/WebView 到当前数据层实例的进程内连接；
- `DeviceDataLayer`：UI、人脸和 MQTT 共用业务入口；
- `DeviceStateStore`：设备运行状态、操作状态和 UI 通知源；
- `SlotStateRepository`：卡槽 Map；
- `DeviceDataRepository`：员工、人脸、指纹 Map，SharedPreferences 仅作重启备份；
- `DeviceOperationEngine`：设备操作状态机；
- `DeviceCommandCoordinator`：MQTT 指令、幂等、业务响应和上报协调；
- `InboundCommandRepository`：远程指令持久化幂等；
- `DeviceDataSyncManager`：同步业务协调，通过 HTTP Gateway 获取数据。

职责：

- 维护本机唯一业务真相；
- 权限、参数、幂等和状态转换；
- 决定何时调用串口、MQTT、HTTP 和 ArcFace；
- 通信回调到达后先更新 Map/Repository，再向 UI 发事件；
- 从 Repository 读取数据，交给通信层组装发送；
- UI、MQTT、人脸、管理员操作复用相同业务入口。

所有 UI 可见状态必须遵守：

```text
通信回调 / 业务结果
→ DeviceDataLayer
→ DeviceStateStore / Repository Map
→ 状态提交
→ UI event
```

绝不能：

```text
通信 Manager
→ MainActivity / JsBridge / Vue
```

## 3. 通信层

核心类：

- `SerialConnectionManager`：串口连接、命令排队、帧编码/解码、应答匹配；
- `WebSocketConnectionManager`：MQTT/TCP 连接、订阅、登录、心跳、Envelope、收发；
- `BackendHttpGateway` / `BackendHttpClient`：HTTP 请求和下载；
- `DeviceProvisioningManager`：注册、激活和通信凭证获取；
- `ArcFaceManager`：虹软硬件/SDK 适配。

职责仅限：

- 建立、关闭、恢复连接；
- 编码、解析、组装和发送协议；
- 传输级状态和应答相关；
- 通过 Listener/Port 返回结构化结果；
- 接收数据层提供的发送内容。

禁止：

- 引用 `MainActivity`、`JsBridge`、`DeviceApplicationFacade` 或 Vue；
- 决定员工、卡槽、取还卡等业务结果；
- 自行修改 Android Repository；
- 把传输连接或单板 ACK 当成业务完成；
- 自己生成第二套业务操作状态机。

## 4. Service 与 Activity

`DeviceCoreService` 不属于额外业务层。它是 Android 运行容器，只允许：

- 前台 Service 生命周期；
- 创建和注入三层组件；
- 启动、停止通信适配器和数据层；
- 注册/清除 `DeviceRuntimeRegistry`。

它不得提供静态业务 API、处理 MQTT 命令、组装业务事件、执行 HTTP 上报或直接通知 UI。

`MainActivity` 只负责：

- WebView 和本地页面容器；
- 启动 `DeviceCoreService`；
- 将数据层事件转交 WebView；
- 相机、系统指纹等必须依赖 Activity 的系统 UI 流程。

人脸和指纹结果必须回到 `DeviceDataLayer` 后，才能更新员工 Map 和通知 Vue。

## 5. 固定调用链

### UI 查询

```text
Vue
→ nativeBridge
→ JsBridge
→ DeviceApplicationFacade
→ DeviceDataLayer
→ DeviceStateStore / Repository Map
→ snapshot
→ Vue 内存投影
```

### UI 设备命令

```text
Vue button
→ Facade
→ DeviceDataLayer
→ DeviceOperationEngine
→ SerialPort
→ SerialConnectionManager
→ board ACK
→ DeviceDataLayer / Store
→ UI event + backend report
```

### MQTT 命令

```text
WebSocketConnectionManager
→ Listener
→ DeviceDataLayer
→ DeviceCommandCoordinator
→ InboundCommandRepository
→ 与 UI 相同的 DeviceOperationEngine
→ communication port
```

### 串口状态

```text
SerialConnectionManager
→ Listener.onSlotStatus
→ DeviceDataLayer
→ DeviceStateStore.updateSlot
→ SlotStateRepository Map
→ cabinet.slotStatus event
→ Vue
```

## 6. 架构完成标准

以下条件全部满足才算三层架构完成：

1. Vue 无业务状态持久化和恢复；
2. `window.android.postMessage` 只存在于 `nativeBridge.js`；
3. `JsBridge` 只依赖 Facade；
4. Facade 只调用统一数据层，不调用 Service 或通信实现；
5. Activity 不调用 Service 静态业务方法；
6. Service 无业务 API 和命令处理；
7. Android 运行状态、卡槽、员工、操作均由 Map/Repository 持有；
8. 通信类不引用 UI、Facade、数据 Store 或业务 Coordinator；
9. HTTP 业务调用统一经过 Gateway；
10. H5 构建、Android 单测和包含本次 H5 的 APK 构建全部通过。

这些条件由 `.github/workflows/device-integration-ci.yml` 自动验证。

## 7. 架构之外的独立事项

以下事项会影响功能正确性，但不改变三层依赖方向，应在后续独立批次实现：

- 100 槽真实串口寻址/切组协议；
- TAKE/RETURN 二阶段物理确认；
- Room/SQLite 诊断 Outbox；
- 增量同步 upsert/delete/tombstone 契约；
- 人脸模板导入任务持久化；
- 外接员工级指纹模块。
