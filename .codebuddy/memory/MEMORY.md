# 项目记忆

## FaceAISDK 依赖配置
- **Maven 坐标**: `io.github.faceaisdk:Android:2026.06.25`（注意：group ID 全小写 `faceaisdk`，非 `FaceAISDK`）
- **托管仓库**: Maven Central（非 JitPack）
- **settings.gradle** 中已配置 `mavenCentral()`
- **用途**: 替换虹软 ArcSoft SDK，提供离线人脸识别、活体检测
- **依赖版本**: `2026.06.25` 是 Maven Central 上最新的纯 Android 版本（非 flutter）
- **配套依赖**: CameraX 1.3.4 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)

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
- CameraX 由 `MainActivity` 统一管理，使用「常驻相机」策略：`onCreate()` 中后台线程 `prewarmCameraX()`（P0 类预加载 + P2 `ProcessCameraProvider.getInstance`）→ `bindPersistentCamera()` 绑定到全屏 PreviewView，摄像头始终运行
- 人脸录入/核验使用 `FaceEnrollmentDialog`（透明覆盖层 Dialog），通过 `MainActivity.setFaceAnalyzer()/clearFaceAnalyzer()` 切换帧处理逻辑，无需重新 `bindToLifecycle`，首帧零延迟
- 预绑定方案已弃用：`Preview.setSurfaceProvider()` 会触发完整 CaptureSession 重建（约 1.9s），与重新绑定的开销相当
- 录入模式使用 `AddFaceDispose.dispose(Bitmap)`，需要 NV21→Bitmap 转换
- 搜索模式使用 `FaceSearchEngine.runSearchWithImageProxy(ImageProxy, 0)` 直接喂帧
- AAR 不包含演示 Activity（需自行构建 CameraX UI）
- 设备常接电源供电，无需考虑相机常驻功耗问题
