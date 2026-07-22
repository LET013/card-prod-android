# Vue 单一数据源与 Android Facade 重构批次

## 范围

本批只处理两项架构问题：

1. 消除 Vue 与 Android 同时持有、恢复和持久化业务真相的双数据源；
2. 建立 `JsBridge → DeviceApplicationFacade → Android Repository/Operation/Communication` 的统一入口。

本批没有新增后台接口、MQTT 命令、HTTP 端点、串口功能码或新业务功能。

## 最终依赖方向

```text
Vue 页面
  → services/index.js
  → nativeBridge.js
  → JsBridge
  → DeviceApplicationFacade
  → Repository / Operation / Coordinator
  → Serial / MQTT / HTTP / ArcFace
```

## Vue 数据原则

Vue 的 `appState` 只保留当前 WebView 生命周期内的展示投影：

- 不从 H5 Storage 恢复卡槽、员工、运行状态和同步状态；
- 不把上述状态写入 H5 Storage；
- Android 返回空数组时必须清空投影；
- 人脸/指纹/员工删除完成后重新读取 Android 数据；
- Vue 不自行创建员工、不自行删除员工、不自行标记人脸或指纹已注册；
- 纯 UI 状态仍由 Vue 自主管理，包括弹窗、Tab、筛选、动画、临时选中项和未提交表单草稿。

## Android Facade 原则

`DeviceApplicationFacade` 是受信 WebView 可调用的唯一 Android 应用/数据门面，负责：

- NativeActionPolicy 动作注册与权限校验；
- NativeAuthManager 管理员会话；
- 脱敏设置读写；
- Android Repository 查询；
- 设备操作分发；
- 需要 Activity 承载的人脸/指纹异步流程发起。

`JsBridge` 仅负责：

- JSON 请求解析；
- 调用 Facade；
- 同步/异步结果识别；
- JSON 成功或错误响应序列化。

## 启动水合

收到 `native.ready` 后，Vue 只自动读取公开 Android 数据：

- 脱敏设置；
- 卡槽快照。

员工、完整运行状态等受权限保护的数据在管理员登录并进入相应页面后读取，不为了启动水合绕过原生权限。

## 自动门禁

CI 会拒绝以下回归：

- `JsBridge` 再次直接依赖 Service、Repository 或通信 Manager；
- `appState.js` 再次使用 H5 Storage 持久化业务真相；
- `services/index.js` 自行增删员工或持久化卡槽/运行状态；
- 任意页面绕过 `nativeBridge.js` 直接调用 `window.android.postMessage`；
- Facade 或必需架构文件缺失。

## 验证

- Codex guidance/Skill 校验；
- 三层边界静态校验；
- 前端 JavaScript 语法校验；
- uni-app H5 真实构建；
- Android 单元测试；
- 使用本次 H5 产物执行 Debug APK 构建。

## 未在本批处理

- 100 槽串口真实拓扑；
- TAKE/RETURN 二阶段物理确认；
- Room/SQLite 诊断 Outbox；
- 增量同步 upsert/delete/tombstone 契约；
- 人脸模板导入任务持久化；
- 员工级外接指纹模块。
