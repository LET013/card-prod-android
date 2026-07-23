# 工作卡柜设备端：Codex 项目指引

本仓库由uni-app H5 UI、Android数据/业务层、通信与设备适配层组成。完整规范见`docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`。

## 开始任务前

1. 阅读`docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`、`docs/CONTRACT_EVIDENCE_REGISTER.md`和相关原始Markdown。
2. 阅读对应`.agents/skills/*/SKILL.md`；跨层任务先用`$card-cabinet-architecture-guardian`。
3. 明确入口、唯一真相、依赖方向、状态变化、失败路径、文档证据和文件级计划。
4. 外部协议不明确时停止猜测并登记阻塞项。

## 证据来源

- 当前HTTP/MQTT契约：`docs/source-2026-07-02/Android客户端接口文档.md` V4.1。
- 串口帧：`docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`。
- 设备实现参考：`reference/motone-current`，只参考已存在设备能力，不直接迁入旧架构和旧接口。
- PDF与旧Markdown只作历史参考。
- 用户已明确：使用FaceAISDK，ArcSoft废弃；HTTP与MQTT独立配置。

## 三层固定结构

```text
UI层（Vue）
→ JsBridge / DeviceApplicationFacade
→ Android数据/业务层（Map唯一真相）
→ 通信与设备适配层（JNI串口、MQTT/TCP、HTTP、FaceAISDK）
```

依赖只能向下。通信或Activity结果必须先进入Android数据层并提交状态，之后才能通知UI。

## UI层不变量

- Vue只负责页面、弹窗、筛选、排序、动画、临时选中项和未提交草稿。
- `appState`只是WebView生命周期内的展示投影。
- 禁止从H5 Storage持久化或恢复卡槽、员工、运行、同步和操作状态。
- 禁止自行生成员工ID、默认业务头像、设备归属或硬件成功结果。
- Android返回空集合时必须清空UI投影。
- 页面只能经`services/index.js → nativeBridge.js`调用Android。
- Release原生失败时禁止切换Mock。

## Android数据/业务层不变量

- `DeviceApplicationFacade`是可信WebView唯一Android门面。
- `DeviceDataLayer`是UI、人脸、远程命令和管理员动作的统一业务入口。
- `DeviceStateStore`、`SlotStateRepository`、`DeviceDataRepository`的Map是运行时唯一真相。
- `DeviceProvisioningManager`负责版本、注册、激活、配置和授权编排。
- `DocumentedBackendService`负责Markdown明确HTTP请求体的业务校验。
- `DeviceOperationEngine`维护操作阶段；`DeviceCommandCoordinator`处理V4.1下行命令和响应。
- 通信回调先写Store/Repository，再发UI事件。
- 所有远程副作用使用`msgId`幂等；本地操作可有`operationId`，但未经文档确认不得进入外部报文。
- 单板ACK不等于物理取还卡完成。

## 通信与设备适配层不变量

- `SerialConnectionManager`与`serial/SerialManager`只负责JNI串口、V1.5帧和原始回调。
- `BackendTransportManager`只负责MQTT/HTTP运行会话和旧TCP连接、订阅、登录、心跳、Envelope、收发。
- `BackendHttpGateway`/`BackendHttpClient`只负责明确HTTP请求、multipart和下载。
- `FaceAiManager`只负责FaceAISDK引擎和本地人脸库适配。
- 通信类不得读取`NativeSettingsRepository`，不得引用Activity、Bridge、Facade、Store或业务Coordinator。
- 通信层不得执行注册/激活业务编排、修改业务Repository或决定取还卡结果。

## Service与Activity

- `DeviceCoreService`只允许前台Service生命周期、组件创建/注入和启动/停止。
- 禁止在Service中保留静态业务API、下行命令处理、业务事件组装、HTTP上报或UI通知。
- `MainActivity`负责WebView、Service启动、CameraX/FaceAISDK覆盖层和系统指纹UI。
- Activity人脸/指纹结果必须回到`DeviceDataLayer`后才能更新业务Map。

## 契约证据规则

- 每个后端字段、路径、方法、枚举、错误码和响应语义必须能指向Markdown或用户明确确认。
- 文档未定义时只能留空、禁用或写入`docs/CONTRACT_EVIDENCE_REGISTER.md`。
- 不得用测试IP、常见端口、猜测username、时间窗口、状态别名或slot映射制造可用。
- 文档冲突时记录冲突，不自行选择解释。
- 路径常量存在不等于业务闭环完成；必须区分运行闭环、业务入口、仅映射和阻塞。

## 绝对禁止

- Vue自行创建、删除、恢复或持久化员工/卡槽业务记录。
- `JsBridge`绕过`DeviceApplicationFacade`。
- Facade绕过`DeviceDataLayer`调用Service或通信实现。
- Activity调用`DeviceCoreService`静态业务方法。
- 通信Manager直接引用UI或修改业务Repository。
- 业务类直接`new BackendHttpClient`绕过`BackendHttpGateway`。
- 恢复ArcSoft、`arcsoft_face.jar`、`ARCSOFT_*`或`xmaihh`串口依赖。
- 未确认拓扑前用直接映射或取模映射逻辑卡位。
- 把MQTT连接、订阅或串口写入当作业务成功。
- 上传或记录真实密码、Token、签名密钥、人脸图片、特征、指纹特征或身份资料。
- 修改`main`、自动合并PR、绕过测试或提交临时迁移文件。

## 主要模块

- `core/DeviceApplicationFacade.java`：WebView唯一Android门面。
- `core/DeviceRuntimeRegistry.java`：UI/Activity到当前数据层的连接。
- `core/DeviceDataLayer.java`：统一业务入口。
- `core/DeviceStateStore.java`：运行状态和UI通知源。
- `core/DeviceProvisioningManager.java`：生命周期接口编排。
- `core/DocumentedBackendService.java`：Markdown接口业务入口。
- `core/DeviceCommandCoordinator.java`：下行命令和幂等响应。
- `core/DeviceDataRepository.java`：员工/人脸/指纹Map。
- `core/SlotStateRepository.java`：逻辑卡槽Map。
- `core/BackendTransportManager.java`：MQTT/HTTP运行会话和旧TCP。
- `core/BackendHttpGateway.java`：HTTP通信。
- `core/SerialConnectionManager.java`：V1.5串口通信。
- `core/FaceAiManager.java`：FaceAISDK适配。
- `service/DeviceCoreService.java`：生命周期和依赖装配。

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

永久CI还必须确认APK包含`lib/arm64-v8a/libSerialPort.so`和最新H5 assets。

## Git与交付

- 只在独立`fix/`或`feature/`分支工作。
- 默认Draft PR，不自动合并。
- 不提交临时脚本、临时workflow、诊断、APK、密钥或本地配置。
- 构建通过不等于目标rk3568_r真机安装和联调通过；没有设备证据时必须明确写“未验证”。
