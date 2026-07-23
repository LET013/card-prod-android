# 工作卡柜严格三层架构

本项目固定为三层。类和目录可以细分，但数据所有权与依赖方向不得改变。

```text
UI 层（uni-app / Vue）
        ↓ NativeBridge 请求、Android 已提交状态事件
Android 数据/业务层（唯一真相、校验、编排、Map、通知）
        ↓ SerialPort / BackendPort / HTTP Transport / Face Adapter
通信与设备适配层（JNI串口、MQTT/TCP、HTTP、FaceAISDK）
```

## 1. 证据来源

- 设备侧已存在实现的主要参考：`reference/motone-current`。
- 当前后端 HTTP/MQTT 契约：`docs/source-2026-07-02/Android客户端接口文档.md` V4.1。
- 串口帧格式：`docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`。
- PDF 与旧版需求只作历史参考，不能覆盖当前 Markdown。
- 人脸引擎由用户明确确认为 **FaceAISDK**；ArcSoft 已废弃。

参考分支不是可直接合并的成品。迁移时只采用其已经存在的 CameraX/FaceAISDK、JNI串口和设备页面能力；旧超级 Service、测试地址、旧接口和越层调用不迁入。

## 2. UI 层

主要文件：

- `uniapp/src/pages/**`
- `uniapp/src/components/**`
- `uniapp/src/state/appState.js`
- `uniapp/src/services/index.js`
- `uniapp/src/services/nativeBridge.js`

职责：

- 页面渲染、弹窗、Tab、筛选、排序、动画、临时选中项；
- 未提交表单草稿；
- 经 `services/index.js → nativeBridge.js` 向 Android 表达意图；
- 接收 Android 数据层已经提交的快照或事件，替换内存投影。

禁止：

- 持久化或恢复卡槽、员工、运行状态、同步状态、操作状态；
- 自行生成员工ID、默认业务头像、设备归属、开门成功或生物登记结果；
- 页面直接调用 `window.android.postMessage`；
- 在 Vue 中实现串口、MQTT、HTTP、员工同步或设备状态机；
- Release 原生失败时静默切换 Mock。

`appState` 只是当前 WebView 生命周期内的展示投影。Android 返回空集合时，UI 必须显示空集合。

## 3. Android 数据/业务层

核心类：

- `DeviceApplicationFacade`：可信 WebView 的唯一 Android 门面与原生权限入口；
- `DeviceRuntimeRegistry`：Activity/Facade 到当前数据层实例的进程内连接；
- `DeviceDataLayer`：UI、人脸、远程命令、管理员动作的统一业务入口；
- `DeviceStateStore`：设备运行状态、操作状态和 UI 通知源；
- `SlotStateRepository`：逻辑卡槽 Map；
- `DeviceDataRepository`：员工、人脸、指纹 Map；
- `DeviceOperationEngine`：设备操作阶段；
- `DeviceCommandCoordinator`：V4.1 下行命令、幂等、响应和上报协调；
- `InboundCommandRepository`：远程 `msgId` 持久化幂等；
- `DeviceDataSyncManager`：员工、人脸、指纹分页同步与本地应用；
- `DeviceProvisioningManager`：版本检查、注册、激活、验证、配置和授权查询编排；
- `DocumentedBackendService`：Markdown 已明确 HTTP 请求体的业务入口；
- `NativeSettingsRepository` / `NativeAuthManager`：Android 配置和管理员会话。

职责：

- 维护本机业务唯一真相；
- 执行权限、参数、状态、幂等和业务校验；
- 决定何时调用串口、MQTT、HTTP 和 FaceAISDK；
- 通信结果先更新 Repository/Store，再通知 UI；
- 对外部接口只发送 Markdown 明确的字段；
- 把没有生产前提的接口保留为显式入口或禁用状态，不制造假结果。

固定状态链：

```text
通信回调 / Activity人脸结果 / UI意图
→ DeviceDataLayer
→ DeviceStateStore / Repository Map
→ 状态提交
→ UI事件或后端调用
```

## 4. 通信与设备适配层

核心类：

- `SerialConnectionManager`：串口连接、V1.5帧编解码、原始帧回调；
- `serial/SerialManager`、`serial/SerialPort`、`cpp/SerialPort.c`：JNI阻塞式串口I/O；
- `BackendTransportManager`：MQTT、HTTP运行会话和旧TCP连接、订阅、登录、心跳、Envelope、收发；
- `BackendHttpGateway`：V4.1 HTTP路径、JSON、multipart和文件下载适配；
- `BackendHttpClient`：HTTP连接、超时、响应解析、Range下载；
- `FaceAiManager`：FaceAISDK引擎和本地人脸库适配。

职责仅限：

- 建立、关闭、恢复连接或设备引擎；
- 编码、解析、组装和发送协议；
- 返回传输级状态、帧、响应和SDK结果；
- 接收 Android 数据层提供的配置和发送内容。

禁止：

- 引用 `MainActivity`、`JsBridge`、`DeviceApplicationFacade`、`DeviceDataLayer`、Store或业务Repository；
- 执行注册/激活业务编排；
- 修改员工、卡槽、取还卡等业务真相；
- 把网络连接、MQTT订阅、串口写入或单板ACK当成业务完成；
- 自行读取或保存 Android 配置Repository。

## 5. Service 与 Activity

`DeviceCoreService` 是运行容器，只允许：

- 前台 Service 生命周期；
- 创建并注入三层组件；
- 启动和停止数据层、JNI串口、后端传输和FaceAISDK；
- 安装和清除 `DeviceRuntimeRegistry`。

它不得提供静态业务API、处理MQTT命令、组装业务事件、执行HTTP上报或直接通知Vue。

`MainActivity` 只负责：

- WebView与可信来源Bridge容器；
- 启动前台Service；
- CameraX常驻相机与FaceAISDK录入/识别覆盖层；
- 系统指纹认证UI；
- 把Activity结果交回 `DeviceDataLayer`。

人脸录入、识别和系统指纹结果必须先进入 `DeviceDataLayer`，才能更新Map或返回Vue。

## 6. 固定调用链

### UI查询/命令

```text
Vue
→ nativeBridge
→ JsBridge
→ DeviceApplicationFacade
→ DeviceDataLayer
→ Repository / StateStore / DocumentedBackendService / DeviceOperationEngine
```

### 后端启动

```text
DeviceDataLayer
→ DeviceProvisioningManager
→ BackendHttpGateway
→ 注册/激活/配置/授权结果
→ Settings与Store提交
→ BackendTransportManager.configure/start
```

### MQTT下行

```text
BackendTransportManager
→ Listener.onCommand
→ DeviceDataLayer
→ DeviceCommandCoordinator
→ InboundCommandRepository
→ 统一业务入口
→ 持久化响应
→ BackendTransportManager发送原msgId响应
```

### 串口状态

```text
JNI ReadThread
→ SerialConnectionManager解帧
→ 未映射board frame
→ DeviceDataLayer
```

当前缺少 `slotId → 从机地址/切组协议`，所以不得把板地址写成逻辑卡位，也不得启用自动轮询、逻辑开门或一键弹卡。

### FaceAISDK

```text
CameraX / FaceEnrollmentController
→ FaceAISDK结果
→ DeviceDataLayer
→ 后台人脸特征接口（录入时）
→ FaceAISDK本地库
→ Employee Map
→ Vue结果
```

## 7. 接口完成口径

接口状态必须区分：

- **运行闭环**：已有触发源、请求、响应处理和本地状态提交；
- **业务入口已接**：请求体和通信已实现，但需要页面、硬件或业务生产者调用；
- **仅映射**：路径存在，但没有足够业务数据；
- **阻塞**：缺少外部协议或硬件，不执行。

路径常量存在不等于功能完成。详见 `docs/DEVICE_CONFIGURATION_AND_INTERFACE_AUDIT.md`。

## 8. 架构完成标准

以下条件全部满足才算三层完成：

1. Vue无业务状态持久化、恢复或业务字段伪造；
2. `window.android.postMessage` 只存在于 `nativeBridge.js`；
3. `JsBridge` 只调用Facade；
4. Facade不调用Service或通信实现；
5. Activity不调用Service静态业务API；
6. Service只做生命周期和装配；
7. Android运行状态、卡槽、员工和操作由Store/Repository持有；
8. 通信类不引用UI、数据Store、业务Coordinator或配置Repository；
9. Provisioning与接口业务调用统一经过HTTP Gateway；
10. H5、Android单测和包含FaceAISDK、JNI串口、H5资源的APK构建全部通过。

## 9. 当前外部阻塞

- 100槽真实串口寻址/切组协议；
- TAKE/RETURN物理完成判定；
- 外接员工级指纹模块与特征生产；
- OTA安装、校验、重启恢复和回滚；
- Room/SQLite日志Outbox；
- PROCESSING远程指令人工恢复协议；
- 目标rk3568_r真机安装、串口、网络和FaceAISDK联调。
