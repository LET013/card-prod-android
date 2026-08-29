# `app/src/main/java/com/xingyao/card/old/` 目录删除影响分析

**分析日期**: 2026-08-02  
**分析范围**: 22 个 Java 文件 / 2 个 Kotlin 文件  
**目标**: 评估删除 `old/` 包全部内容的可行性与影响面  

---

## 一、old/ 目录文件清单

```
old/
├── BackendEndpointSettings.java      - 后端地址配置
├── BackendHttpClient.java            - HTTP 客户端封装
├── BackendHttpGateway.java           - HTTP 网关（API 调用入口）
├── BackendTransportManager.java      - 后端通信管理（HTTP+MQTT 调度）
├── DeviceApplicationFacade.java      - 应用门面（对外暴露的统一 API）
├── DeviceCommandCoordinator.java     - 命令协调器
├── DeviceConfigMapper.java           - 设备配置映射
├── DeviceDataLayer.java              - 数据层（员工/卡槽/操作 Map 缓存）
├── DeviceDataRepository.java         - 数据仓库（SP 持久化）
├── DeviceDataSyncManager.java        - 数据同步管理
├── DeviceEventLogRepository.java     - 设备事件日志仓库
├── DeviceOperationEngine.java        - 设备操作引擎（取还卡业务状态机）
├── DeviceProvisioningManager.java    - 设备配置下发管理
├── DeviceRuntimeRegistry.java        - 运行时注册表
├── DeviceStateStore.java             - 设备状态存储
├── DocumentedBackendService.java     - 后端服务接口文档标记
├── InboundCommandRepository.java     - 入站命令仓库
├── JsonCanonicalizer.java            - JSON 规范化
├── NativeSettingsRepository.java     - 原生设置仓库（SharedPreferences）
├── ProvisioningRetryPolicy.java      - 配置下发重试策略
├── SerialDebugWindow.java            - 串口调试窗口
├── SlotStateRepository.java         - 卡槽状态仓库
```

---

## 二、外部引用清单（按影响程度分级）

### 🔴 2.1 强依赖引用（删除前必须迁移/替换）

| # | 引用文件 | 引用类 | 引用方式 | 说明 |
|---|----------|--------|----------|------|
| 1 | `FaceEnrollmentController.java:567` | `DeviceRuntimeRegistry` | **运行时方法调用** `DeviceRuntimeRegistry.require().extractFaceFeature()` | 人脸特征提取的**唯一 fallback 路径**。当 `featureExtractor` 为 null 时依赖此调用。 |
| 2 | `DeviceCoreService.java:94,242` | `DeviceRuntimeRegistry` | **字段声明 + 方法调用** | `runtimeRegistry` 字段用于注入到 Service 生命周期。`onCreate()` 中调用 `runtimeRegistry.bind(...)`、`onDestroy()` 中调用 `unbind()`。 |
| 3 | `MainActivity.java:41` | `DeviceRuntimeRegistry` | **import** | 当前的 import 仍然引用，但 MainActivity 已迁移到新架构，实际调用链需要确认。 |
| 4 | `JsBridgeV2.java:1803` | `NativeSettingsRepository` / SharedPreferences | **方法调用** | `old.NativeSettingsRepository` 被用于关闭 SharedPreferences 引用。 |

### 🟡 2.2 弱依赖引用（可一并移除）

| # | 引用文件 | 引用类 | 引用方式 | 说明 |
|---|----------|--------|----------|------|
| 5 | `DeviceCoreService.java:33` | `DeviceRuntimeRegistry` | import | Android Service 层导入，与 #2 相同 |
| 6 | 所有 Java/Kotlin 源文件 | 无 | 无引用 | 其余文件已不依赖 `old.*` 包 |

### 🟢 2.3 Vue 前端层引用（Mock/兼容层）

| # | 引用文件 | 引用方式 | 说明 |
|---|----------|----------|------|
| 7 | `uniapp/src/services/index.js` | 38 个旧兼容层方法 | `takeCard`, `returnCard`, `querySlot`, `getHistory`, `getRuntime`, `unlockDoor`, `unlockAllDoors`, `restartApp`, `listSerialPorts`, `syncEmployees`, `searchEmployees`, `saveEmployee`, `deleteEmployee`, `activateDevice` 等 |
| 8 | `uniapp/src/services/mockService.js` | 对应的 Mock 实现 | 同上 38 个方法的 mock 版本 |

---

## 三、影响链路分析

### 3.1 关键链路 #1: 人脸特征提取 fallback

```
FaceEnrollmentController.onFaceCaptured()
  → featureExtractor != null ?
    YES: featureExtractor.extract(croppedBitmap)
    NO:  DeviceRuntimeRegistry.require().extractFaceFeature(croppedBitmap)  ← 依赖 old/
```

**当前状态**: `featureExtractor` 在 JsBridgeV2 `createFeatureExtractor()` 中被注入，走新架构。Fallback 路径仅在 `featureExtractor == null` 时被调用。

**风险评估**: 🟢 低 — fallback 路径当前未被触发，但删除前应确认 `featureExtractor` 始终非 null。

### 3.2 关键链路 #2: DeviceCoreService 运行时绑定

```
DeviceCoreService.onCreate()
  → runtimeRegistry.bind(httpClient, mqttClient, sqliteManager, serialManager, faceAiManager)
  
DeviceCoreService.onDestroy()
  → runtimeRegistry.unbind()
```

**当前状态**: `DeviceRuntimeRegistry.bind()` 将新架构的各个 Manager 实例注册到旧兼容层，使得 `old/` 中的代码可以通过 `require()` 访问这些服务。

**风险评估**: 🟢 低 — 如果无其他 `old/` 代码调用 `require()`，这些 bind/unbind 是无操作，可以安全移除。

### 3.3 关键链路 #3: Vue 旧兼容层 (38 个方法)

```
Vue page → services/index.js (old compat methods)
  → nativeBridge.request('takeCard') 等
    → JsBridgeV2 旧 action handler (如 'takeCard', 'returnCard')
      → DeviceCommandCoordinator 等 old/ 类
```

**当前状态**: Vue 前端多个页面仍通过这 38 个旧兼容方法调用 Android。删除 `old/` 意味着这些方法必须全部迁移到新六通道 API。

**风险评估**: 🔴 高 — 涉及 38 个前端调用点，需要逐一定位调用方并替换。

---

## 四、删除可行性评估

### 4.1 可行性结论: ⚠️ 有条件可行，但需要大量迁移工作

### 4.2 前置条件

| # | 条件 | 状态 | 预估工时 |
|---|------|------|----------|
| 1 | 确认 `featureExtractor` 始终非 null（移除 fallback 路径） | 待验证 | 1h |
| 2 | 从 `DeviceCoreService` 移除 `DeviceRuntimeRegistry` 绑定代码 | 待执行 | 1h |
| 3 | 从 `MainActivity` 移除 `DeviceRuntimeRegistry` import | 待执行 | 0.5h |
| 4 | 从 `JsBridgeV2` 移除 `NativeSettingsRepository` 引用 | 待执行 | 0.5h |
| 5 | 逐一定位 Vue 38 个旧兼容方法的调用方 | 待执行 | 4h |
| 6 | 将旧方法迁移到对应新六通道 API | 待执行 | 8h |
| 7 | 更新 `uniapp/src/services/index.js` 移除 38 个旧方法 | 待执行 | 1h |
| 8 | 更新 `uniapp/src/services/mockService.js` 移除对应 mock | 待执行 | 1h |
| 9 | 更新项目指引文档 (AGENTS.md, docs/) | 待执行 | 1h |
| 10 | 回归测试 (真机) | 待执行 | 4h |
| **总计** | | | **~22h** |

### 4.3 Vue 旧兼容层迁移对照表

| 旧方法 | 新通道 API | 迁移复杂度 |
|--------|------------|------------|
| `takeCard` | `serial.send` + Vue 业务组装 | 🔴 复杂（需了解取卡串口帧协议） |
| `returnCard` | `serial.send` + Vue 业务组装 | 🔴 复杂（需了解还卡串口帧协议） |
| `querySlot` | `serial.send` + Vue 业务组装 | 🔴 复杂 |
| `unlockDoor` | `serial.send` + Vue 业务组装 | 🔴 复杂 |
| `unlockAllDoors` | `serial.send` + Vue 业务组装 | 🔴 复杂 |
| `getHistory` | 历史数据迁移到 Vue SQLite 表 | 🟡 中等 |
| `getRuntime` | 运行时状态从 bootstrap.deviceInfo 获取 | 🟢 简单 |
| `restartApp` | 无对应新 API（Android 系统级操作） | 🟡 中等 |
| `syncEmployees` | 已在 splash.vue 中直接调用新 API | ✅ 已迁移 |
| `searchEmployees/saveEmployee/deleteEmployee` | HTTP CRUD → Vue service 层直接调用 | 🟡 中等 |
| `activateDevice` | `bootstrap.activate` | ✅ 已迁移 |
| `listSerialPorts` | 无对应新通道（需新增） | 🟡 中等 |
| `saveSettings` | `storage.execute` | 🟢 简单 |
| `savePassword` | `storage.execute` | 🟢 简单 |
| 其他 25 个方法 | 对应 HTTP/MQTT/Storage 通道 | 🟢 简单-中等 |

---

## 五、分阶段删除方案

### 阶段一: 移除内部单向依赖（可立即执行，~3h）

```
1. 确认 FaceEnrollmentController featureExtractor 始终非 null
2. 移除 FaceEnrollmentController 的 fallback DeviceRuntimeRegistry 调用
3. 移除 DeviceCoreService 的 runtimeRegistry bind/unbind
4. 移除 MainActivity 的 DeviceRuntimeRegistry import
5. 移除 JsBridgeV2 的 NativeSettingsRepository 引用
6. 删除 old/ 目录
7. 编译验证
```

### 阶段二: 迁移 Vue 旧兼容层（逐步执行，~15h）

优先级顺序:
1. **P0**: 已在 splash.vue 迁移完成的方法（`syncEmployees`, `activateDevice`, `saveSettings`）→ 直接移除旧接口
2. **P1**: 简单迁移（HTTP/MQTT/Storage）→ 迁移到对应新通道
3. **P2**: 串口操作（`takeCard`, `returnCard`, `unlockDoor`）→ 等待串口出站拓扑确认后迁移
4. **P3**: 历史记录 `getHistory` → 迁移到 Vue SQLite 历史表

### 阶段三: 清理（~2h）

```
1. 更新 AGENTS.md 移除 old/ 相关约定
2. 更新 docs/ 相关文档
3. 代码格式化 & 最终编译验证
```

---

## 六、风险评估

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| 串口操作（取还卡）旧接口无法简单映射到新通道 | 🔴 高 | 阶段二 P2 延迟处理，等待串口出站拓扑确认 |
| 删除后遗留 URL/常量引用 | 🟡 中 | 全局搜索确认后再删除 |
| 未知隐式依赖（反射/SP key 共享） | 🟡 中 | 阶段一编译后全功能回归测试 |
| Vue mock 模式与 release 模式行为不一致 | 🟢 低 | Mock 层同步修改 |

---

## 七、结论

**可以删除，但建议分阶段执行。**

阶段一（移除 Android 内部单向依赖）风险低，可立即执行。阶段二（Vue 旧兼容层迁移）工作量大（~15h），且串口相关操作需要串口负责人配合确认协议。建议在串口出站拓扑确认、取还卡流程稳定的时间窗口集中处理阶段二。

**立即可删除的类**（无外部引用）：
- `BackendEndpointSettings`、`BackendHttpClient`、`BackendHttpGateway`、`BackendTransportManager`
- `DeviceApplicationFacade`、`DeviceCommandCoordinator`、`DeviceConfigMapper`
- `DeviceDataLayer`、`DeviceDataRepository`、`DeviceDataSyncManager`
- `DeviceEventLogRepository`、`DeviceOperationEngine`、`DeviceProvisioningManager`
- `DeviceStateStore`、`DocumentedBackendService`、`InboundCommandRepository`
- `JsonCanonicalizer`、`ProvisioningRetryPolicy`、`SerialDebugWindow`、`SlotStateRepository`

**需要迁移后才能删除的类**（有外部引用）：
- `DeviceRuntimeRegistry` → 3 处引用需先解除
- `NativeSettingsRepository` → 1 处引用需先解除

---

*分析基于 `serial-debug-20260720` 分支当前代码状态。*
