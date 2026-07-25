# TASK-20260725：人员同步验证计划

状态：PLANNED / NOT EXECUTED

当前只完成审计与设计，未修改运行代码。以下验证均等待设计确认和实现后执行。

## 1. 需求追踪矩阵

| 需求/验收 | 计划实现位置 | 验证方式 | 当前结果 |
|---|---|---|---|
| R-001 / AC-001 | `DeviceSyncPanel.vue`、`index.vue` | 未登录打开公开面板 | 未执行 |
| R-002 / AC-005 | `DeviceDataLayer.java` | 代码审计只调用`syncEmployees(false)` | 未执行 |
| R-003 | `DeviceDataLayer.java` | 检查固定`full=false` | 未执行 |
| R-004 / AC-003 | `services/index.js` | Vue无HTTP分页和游标代码 | 未执行 |
| R-005 / AC-004 | `NativeActionPolicy`、Facade | 公共动作测试 | 未执行 |
| R-006 / AC-006 | `DeviceDataLayer`、Store | SYNCING事件 | 未执行 |
| R-007 / AC-007 | 面板与同步结果 | 数量/删除数/游标/时间 | 未执行 |
| R-008 / AC-009 | 失败路径 | 旧Map不清空 | 未执行 |
| R-009 / AC-010 | BUSY路径 | 已有任务不排队 | 未执行 |
| R-010 / AC-011 | 前置条件审计 | MQTT离线不预拒绝 | 未执行 |
| R-011 / AC-012 | 首次同步 | 空Map/游标0允许 | 未执行 |
| R-012 / AC-013 | 现有SyncManager/Repository | deletedIds语义 | 未执行 |
| R-013 / AC-008 | `services.init` | snapshot刷新员工投影 | 未执行 |
| R-014 / AC-014 | 启动与syncUser链 | 回归审计 | 未执行 |
| R-015 | UI组件拆分 | index职责审计 | 未执行 |
| R-016 / AC-015 | 最终diff | 所有权检查 | 未执行 |
| AC-016 | CI | H5/单测/APK/门禁 | 未执行 |
| AC-017 | 交付报告 | 未验证项明确 | 未执行 |

## 2. 静态检查

计划检查：

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
```

代码审计：

- Vue中不得出现 `/api/v1/employee/sync`；
- Vue中不得出现 `lastSyncTime/page/pageSize/employeeSyncVersion` 的同步算法；
- `sync.employees` 不得进入外部MQTT/HTTP报文；
- Facade不得绕过DeviceDataLayer；
- DeviceDataLayer不得复制分页和Map合并；
- 不得新增 `employeeExists`、管理员会话或MQTT认证前置条件；
- 不得修改保护文件。

## 3. UI验证

### 3.1 公开入口

- 未登录管理员可打开面板；
- 管理员登录状态不影响入口；
- 面板标题为“设备状态与数据同步”；
- 原“立即上报卡槽状态”仍可使用；
- 新增“同步人员”；
- 未出现人脸、指纹或同步全部按钮。

### 3.2 请求状态

- 点击后按钮禁用；
- 显示“正在同步人员”；
- 成功后按钮恢复；
- 失败后按钮恢复；
- BUSY后按钮恢复；
- 关闭面板不取消Android同步，也不伪造结果。

### 3.3 结果展示

成功显示：

- 本次人员数量；
- 删除人员数量；
- 当前员工总数；
- 人员游标；
- 完成时间。

失败显示：

- 准确错误；
- 不显示成功；
- 不清空旧人数摘要。

## 4. Android客户端验证

### 4.1 权限

`NativeActionPolicyTest`：

```java
assertTrue(isPublicAction("sync.employees"));
assertNull(requiredPermission("sync.employees"));
```

### 4.2 调用边界

确认：

```text
Facade sync.employees
→ DeviceDataLayer.syncEmployeesNow
→ DeviceDataSyncManager.syncEmployees(false)
```

不得调用 `syncAll/syncFaces/syncFingers`。

### 4.3 状态

正常：

```text
READY → SYNCING → SUCCESS
```

失败：

```text
READY → SYNCING → ERROR
```

已有任务：

```text
Store保持原SYNCING
当前调用返回BUSY
```

### 4.4 既有流程回归

- 启动自动 `syncAll(false)` 不变；
- MQTT `syncUser` 不变；
- 人脸Fetched/Applied游标不变；
- 指纹游标不变；
- 状态上报功能不变。

## 5. 构建验证

```bash
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
```

永久CI还需通过：

- 三层；
- 契约；
- 仓库污染；
- H5安装到assets；
- APK中H5和JNI库检查。

## 6. 所有权验证

最终diff不得包含：

```text
app/src/main/java/com/xingyao/card/core/BackendTransportManager.java
app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java
app/src/main/java/com/xingyao/card/core/BackendHttpClient.java
app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java
app/src/main/java/com/xingyao/card/core/WorkCardProtocol.java
app/src/main/java/com/xingyao/card/core/FaceAiManager.java
app/src/main/java/com/xingyao/card/FaceEnrollmentController.java
app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java
serialport/**
serial-debug/**
app/libs/**
```

`DeviceDataSyncManager.java`和`DeviceDataRepository.java`也计划保持不变；若实施时发现必须修改，应停止并返回设计确认。

## 7. 真实后端场景

需要后端或集成环境才能验证：

1. 单页人员同步；
2. 多页人员同步；
3. `hasMore=true`；
4. `deletedEmployeeIds`；
5. `syncVersion`推进；
6. token错误；
7. HTTP超时；
8. 服务端空集合；
9. 200页安全上限；
10. MQTT断线但HTTP可用。

没有环境证据时均标记“未验证”。

## 8. 真机场景

需要rk3568_r：

- 安装；
- 首页触控；
- 面板滚动和尺寸；
- APP重启后人员Map与游标恢复；
- 同步过程中切后台/回前台；
- 状态上报回归；
- 人员页面读取新Map。

没有设备证据时均标记“未验证”。

## 9. 人脸模板删除风险

需专项验证或负责人协作：

- 删除一个员工模板；
- 删除多个员工模板；
- 第二个模板删除失败；
- FaceAISDK未就绪；
- 模板删除成功但Map持久化失败。

本批不宣称解决这些既有风险。

## 10. 交付口径

必须分别报告：

- 已实现；
- 未实现；
- 已通过静态检查；
- 已通过H5构建；
- 已通过Android单测；
- 已生成APK；
- 是否安装；
- 是否真实后端联调；
- 是否验证deletedEmployeeIds；
- 是否验证重启恢复；
- 是否验证人脸模板删除失败路径。
