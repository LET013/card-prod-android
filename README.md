# 工作卡柜 Android 客户端

Android 原生 WebView 壳 + uni-app（Vue 3 + Vite）客户端。

## 当前完成度

本版本已完成设计稿对应的客户端 UI、页面交互、本地 Mock 数据、三角色权限、Android JS Bridge 协议与常驻设备服务骨架。真实后台、WebSocket 业务协议、串口协议、人脸/指纹 SDK、真实开门与 OTA 留待下一阶段接入。

详见：

- [当前阶段实现说明](docs/CURRENT_STAGE_IMPLEMENTATION.md)
- [交付验收报告](docs/IMPLEMENTATION_REPORT.md)
- [2026-07-02 UI 适配修订（第一轮）](docs/REVISION_2026-07-02.md)
- [2026-07-02 UI 精修（第二轮，当前）](docs/REVISION_2026-07-02-R2.md)

## 设计文档

- [串口轮询方案重设计](docs/SERIAL_POLLING_REDESIGN.md) — 发送队列 + 独立工作线程，解耦轮询与手动命令
- [Core 模块 HTTP / MQTT 服务使用指南](app/src/main/java/com/xingyao/card/core/README.md)

## 技术架构

```text
Android Activity / WebView
├─ uni-app（Vue 3 + Vite）H5 UI
├─ JsBridge：统一 JSON 请求、响应与事件协议
└─ DeviceCoreService（START_STICKY）
   ├─ core/http/     HTTP 基础设施（BaseApiService、HttpClientManager）
   ├─ core/biz/http/ 业务 HTTP 服务（Device、Employee、Card、Report）
   ├─ core/mqtt/     MQTT 基础设施（BaseMqttService、XMqttClient）
   ├─ core/biz/mqtt/ 业务 MQTT 服务（CardEvent、DataSync、Command、Monitor）
   ├─ SerialConnectionManager（串口通信）
   └─ FaceAiManager（人脸识别）
```

## 构建 H5 并复制到 Android assets

首次安装依赖：

```bash
cd uniapp
npm ci
cd ..
```

构建并同步到 Android：

```bash
./build.sh
```

## Android 构建

项目已经包含 Gradle Wrapper 文件。使用 Android Studio 导入项目后，等待 Gradle 依赖同步，再构建 APK。

命令行构建：

```bash
./gradlew assembleDebug
```

构建 Android 需要本机已配置 Android SDK，并能访问 Gradle 与 Maven 依赖源。

## 当前 Mock 登录密码

仅供当前 UI 与桥接联调：

- 系统管理员：`111111`
- 运维人员：`222222`
- 开发人员：`333333`

原生端默认密码以 SHA-256 摘要初始化，不在 SharedPreferences 中保存明文。生产使用前必须调整首次启用与密码管理策略。

## UI 适配

800×1200 仅作为设计参考。运行时不固定根画布、不强制 2:3 比例、不整体 `transform: scale()`；页面根据 WebView 实际宽高、系统栏、键盘和可用空间响应式布局。
