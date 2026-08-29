# 项目记忆

## 不可违反的真实性与交付规则
- 绝不根据常识、旧实现、示例值或个人推断补全不存在的协议、字段、地址映射、状态语义、配置或业务流程。
- 任何外部行为必须能指向当前有效文档、明确的用户决定或已核验的真实设备输出；证据不足时保持空值或禁用，并明确提出待确认项。
- 推断只能标记为“推断/待验证”，不得直接写入生产协议或描述成已经完成。
- 编译通过、APK生成、APK安装、真机功能通过是四种不同结论，不得相互替代。
- 交付代码前必须运行仓库规定的语法检查、H5构建、Android单测和APK构建；有兼容设备时还必须实际安装。未执行或无设备时必须明确写“未验证”，不得声称功能不会出现问题。
- 提交或推送前必须核对目标分支、提交父节点、相对目标分支的实际diff和将被带入的历史；不得再用零文件差异的合并提交冒充功能提交，也不得未经用户允许重写远端历史。
- 发现不确定性、文档冲突、测试失败或硬件缺失时立即说明，不隐瞒、不用模拟结果代替真实结果。

## 后端所有权边界
- 永远不修改、重构、格式化、重新生成或替换已经明确由后端负责的源码、协议实现和二进制产物。
- 串口专业人员只读资产：`serialport/**`、`serial-debug/**`、`app/libs/serialport-release-1.0.aar`、`core/SerialConnectionManager.java`和`core/WorkCardProtocol.java`。
- 人脸专业人员只读资产：`core/FaceAiManager.java`、`FaceEnrollmentActivity.java`、`app/build.gradle`中的FaceAISDK/CameraX依赖。
- `FaceEnrollmentController.java`：2026-08-09 用户授权重构为混合自动/手动录入状态机，完成后恢复只读。
- 当前主要工作范围是Vue前端、Facade、Android数据/业务层Map和后端HTTP/MQTT接口；只能消费专业模块已有回调，不能修改其实现。
- 如果专业模块接口无法满足集成要求，记录具体问题并交给对应负责人，不能跨越所有权边界代改。
- 拉取、合并或解决冲突时，后端只读资产无条件保留后端版本；发现两边不同必须停止并报告，不能自行选择或拼接。
- 只有同时获得用户和后端对具体文件、具体改动的明确授权，才能临时修改后端所有内容；授权不得从模糊要求中推断。

## FaceAISDK 依赖配置
- **Maven 坐标**: `io.github.faceaisdk:Android:2026.06.25`（注意：group ID 全小写 `faceaisdk`，非 `FaceAISDK`）
- **托管仓库**: Maven Central（非 JitPack）
- **settings.gradle** 中已配置 `mavenCentral()`
- **用途**: 替换虹软 ArcSoft SDK，提供离线人脸识别、活体检测
- **当前固定版本**: `2026.06.25`；是否为上游最新版本未核验，不得自行升级
- **当前配套依赖**: CameraX 1.4.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)

## FaceAISDK 核心 API (com.ai.face.*)

### 引擎
- `FaceAISDKEngine.getInstance(Context)` - 单例引擎
  - `croppedBitmap2Feature(Bitmap)` → String (1024维 Base64 特征)
  - `release()` → void

### 人脸库管理
- `FaceSearchFeatureManger.getInstance(Context)` - 1:N 人脸库
  - `insertFaceFeature(faceID, feature, timestamp, tag, group)`
  - `deleteFaceFaceFeature(faceID)`
  - `clearAllFaceFaceFeature()`
  - `getFaceSearchLibCount()` → int
  - `queryAllFaceFaceFeature()` → List<FaceSearchFeature>
  - `queryFaceFeatureByID(faceID)` → FaceSearchFeature

### 人脸录入
- `AddFaceDispose(Context, performanceMode, needConfirm, AddFaceCallBack)`
  - `dispose(Bitmap)` - 喂入帧
  - `release()` - 释放
  - 回调: `AddFaceCallBack.onCompleted(Bitmap cropped, float liveScore)` / `onProcessTips(int)`

### 1:N 搜索
- `FaceSearchEngine.getInstance()` - 搜索单例
  - `initSearchParams(SearchProcessBuilder)`
  - `runSearchWithImageProxy(ImageProxy, rotation)`
  - `stopSearchProcess()`
- `SearchProcessCallBack` (非抽象方法，可选重写):
  - `onMostSimilar(String faceID, float score, Bitmap, float liveness)` 
  - `onFaceMatched(List<FaceSearchResult>, Bitmap, float)`
  - `onFaceDetected(List<FaceSearchResult>)`
  - `onProcessTips(int)` - **唯一的抽象方法**
  - `onLog(String)`

### 架构决策
- CameraX 由 `MainActivity` 统一管理：`prewarmCameraX()` → `bindPersistentCamera()`，绑定到`face_overlay`内的PreviewView
- 当前布局中`face_overlay`为340×540dp，PreviewView为340×480dp；这些是当前代码值，不是设备协议
- 人脸录入/核验使用 `FaceEnrollmentController` 和 `face_overlay`，通过`bringToFront`切换WebView/人脸UI，并通过 `MainActivity.setFaceAnalyzer()/clearFaceAnalyzer()` 切换帧处理逻辑
- 预绑定方案已弃用：`Preview.setSurfaceProvider()` 会触发完整 CaptureSession 重建（约 1.9s），与重新绑定的开销相当
- 录入模式使用 `AddFaceDispose.dispose(Bitmap)`，需要 NV21→Bitmap 转换
- 搜索模式使用 `FaceSearchEngine.runSearchWithImageProxy(ImageProxy, 0)` 直接喂帧
- AAR 不包含演示 Activity（需自行构建 CameraX UI）
- 设备常接电源供电，无需考虑相机常驻功耗问题

## serialport模块当前状态
- 源码模块位于`serialport/`，包名`com.xingyao.serialport`，包含Java阻塞I/O封装和JNI C源码。
- `settings.gradle`包含`:serialport`，但当前`app/build.gradle`通过`app/libs/*.aar`消费预编译AAR，不是`implementation project(':serialport')`。
- 当前AAR为`app/libs/serialport-release-1.0.aar`；修改`serialport/`源码后必须重新构建并替换AAR，再检查APK内`lib/arm64-v8a/libSerialPort.so`。
- 支持ABI由`serialport/build.gradle`明确为`arm64-v8a`和`armeabi-v7a`；x86/x86_64模拟器不能据此验证真实串口。
- 所有权：串口源码、AAR、`SerialConnectionManager`和`WorkCardProtocol`均由串口专业人员负责，当前任务只读、只调用。

## HTTP/MQTT 抽象层规范（2026-07-24）

所有 HTTP 和 MQTT 通信模块应遵循以下规范，使用统一的抽象基类：

### HTTP 模块规范
- 后端接口统一信封格式：`{"msg":"...","code":200,"data":{...}}`
- **解包**：使用 `ApiResponseUtil.unwrap(envelope, apiName)` 校验 code 并提取 data
- **API 服务类**：继承 `BaseApiService`，构造函数接收 `HttpClientManager` + 可选 apiPrefix
  - 使用 `apiGet(path)` / `apiPost(path, body)` 发起请求，自动解包
  - 业务实体在子类中完成组装与解析
- **Token 管理**：使用 `DeviceTokenProvider(credentialStore)` 注入 `HttpClientManager`

### MQTT 模块规范
- 继承 `BaseMqttService(mqttClient, deviceCode, credentialStore)`
- **上行消息**：使用 `sendSignedEnvelope(cmd, data)` 或 `sendSignedEnvelope(cmd, msgIdPrefix, data)` 自动签名+构建+MqttEnvelope
- **心跳**：使用 `sendHeartbeat(data)` 发送到 heartbeat topic
- **下行消息**：重写 `handleMqttMessage(cmd, data, topic)` 处理
- **EventBus**：在构造函数/onCreate 中调用 `register()`，在 onDestroy/shutdown 中调用 `unregister()`
- **请求-应答**：使用 `sendAndWaitReply(cmd, data, timeoutMs, replyCmd)` 在后台线程同步等待响应

### 已使用场景
- `HeartbeatManager` 继承 `BaseMqttService`（范例）
- `DeviceBootstrapManager` 组合使用 `ApiResponseUtil` + `DeviceTokenProvider`（范例）

## 人脸录入：一人多照片 — 待定设计方案 (2026-07-23)
- **当前限制**: FaceAISDK `insertFaceFeature` 以 `faceID` 为唯一键，代码中 `faceID = employeeId`，因此每人只能存一个人脸特征。
- **讨论中方案**: 
  - 当前特征作为主人脸，`faceID = employeeId`（维持不变）
  - 扩展人脸 `faceID = employeeId + "_" + imgIndex`（如 `E001_1`, `E001_2`）
  - 1:N 搜索返回 `faceID` 后通过前缀匹配还原 `employeeId`
  - 需要后台确认是否需要新增字段存储扩展人脸，以及 API 契约变更
- **涉及文件**: `FaceAiManager.enrollFeature()`, `DeviceDataLayer.completeFaceEnrollment()`, `DeviceDataSyncManager.importFaceTemplates()`, 以及搜索/核验结果解析
- **状态**: 待与后台讨论确认
