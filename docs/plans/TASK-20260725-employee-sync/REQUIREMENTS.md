# TASK-20260725：人员同步需求标准化

状态：等待用户确认，禁止修改运行代码

## 1. 目标

在不修改后端、HTTP底层、MQTT底层、串口和人脸专业实现的前提下，为现有 Android 人员增量同步能力增加一个公开 UI 触发入口，并展示本地可证明的同步状态和结果。

## 2. 当前行为

1. 后端业务登录进入 `AUTHENTICATED` 后，默认执行一次 `syncAll(false)`；
2. MQTT 下行 `syncUser` 会执行一次 `syncAll(false)`；
3. 两个入口都同时同步人员、人脸和指纹；
4. `DeviceDataSyncManager.syncEmployees(false)` 已实现独立人员增量同步；
5. 人员页面只读取 Android 员工 Map，不主动拉取后端；
6. 当前没有公开人员同步按钮和 `sync.employees` Bridge 动作；
7. 当前 Vue 只在 `sync.completed` 时刷新员工投影，没有稳定展示人员同步状态。

## 3. 目标行为

### R-001：公开人员同步入口

现有首页公开“设备状态上报”面板扩展为“设备状态与数据同步”面板，并增加一个清晰可见的“同步人员”按钮。

该入口不要求进入管理员模式。

### R-002：仅同步人员

点击“同步人员”只调用：

```java
DeviceDataSyncManager.syncEmployees(false)
```

不得调用：

- `syncAll(false)`；
- `syncFaces(false)`；
- `syncFingers(false)`。

### R-003：增量同步

公开入口只执行增量同步：

```text
full = false
lastSyncTime = employeeSyncVersion
```

不提供全量同步按钮，不允许用户在 Vue 重置游标。

### R-004：Vue 只触发和展示

Vue 调用链必须是：

```text
DeviceSyncPanel
→ services.syncEmployees()
→ nativeBridge
→ DeviceApplicationFacade
→ DeviceDataLayer
→ DeviceDataSyncManager.syncEmployees(false)
```

Vue 不得：

- 直接请求 `/api/v1/employee/sync`；
- 保存 `employeeSyncVersion`；
- 合并员工数组；
- 解释 `deletedEmployeeIds`；
- 修改人脸或指纹缓存。

### R-005：公共 Bridge 动作

新增内部动作：

```text
sync.employees
```

该动作属于可信内置 WebView 公共动作，不要求管理员会话。

公共不等于新增外部协议。不得把 `sync.employees` 发送到 MQTT 或后端。

### R-006：Android 状态先行

同步开始前，Android `DeviceStateStore.sync` 必须先写入：

```json
{
  "state": "SYNCING",
  "scope": "employees",
  "source": "UI",
  "message": "正在同步人员数据",
  "startedAt": 0
}
```

同步成功后写入 `SUCCESS`；失败后写入 `ERROR`；Vue 只消费投影。

`startedAt/completedAt/failedAt` 使用 Android 本地毫秒时间戳，只用于客户端状态，不进入外部报文。

### R-007：成功结果

成功结果至少展示：

- `employeeCount`：本次返回的人员数量；
- `deletedEmployeeCount`：本次删除ID数量；
- `employeeSyncVersion`：成功提交后的人员游标；
- 当前员工 Map 总数；
- `completedAt`；
- “人员同步完成”文案。

### R-008：失败语义

失败时必须：

1. 写入 `sync.state=ERROR`；
2. 保存 `scope=employees`；
3. 保存可显示错误信息和 `failedAt`；
4. 不清空 Vue 员工列表；
5. 不清空 Android 员工 Map；
6. 不显示“同步成功”；
7. 允许用户重试。

### R-009：BUSY 防重复

当 Android 当前 `sync.state=SYNCING` 时：

- 本地人员同步立即返回 `BUSY`；
- 不排队执行第二次同步；
- 不覆盖正在运行同步的状态；
- UI 显示“已有同步任务正在执行”。

UI 请求期间同时禁用“同步人员”按钮。

不得增加任意秒数冷却或后台轮询。

### R-010：不要求 MQTT 认证

人员同步使用现有 HTTP API，因此 MQTT 断开不构成人员同步前置条件。

不得因为：

```text
socket.state != AUTHENTICATED
```

而拒绝人员同步。

### R-011：首次启动和空 Map

员工 Map 为空、人员游标为 0 时允许同步。

不得增加“必须已有员工”前置条件。

### R-012：deletedEmployeeIds 保持现有语义

必须复用现有人员同步删除流程：

- 删除对应员工；
- 删除对应本地人脸/指纹缓存；
- 调用现有人脸负责人公开模板删除能力；
- 展示 `deletedEmployeeCount`。

不得在 Vue 再实现一遍删除逻辑。

### R-013：员工投影刷新

同步成功后继续通过现有 `sync.completed.snapshot.employees` 刷新 `appState.employees`。

员工页面以后打开或当前已打开时，读取的都应是 Android Map 新快照。

### R-014：保持现有自动同步

不得改变：

- 启动 `syncAll(false)`；
- MQTT `syncUser → syncAll(false)`；
- 三类同步游标；
- 人脸 Fetched/Applied 游标；
- 指纹缓存行为。

### R-015：UI 结构整理

为避免继续把状态上报和后续同步逻辑堆入首页，计划将现有状态上报面板抽取为：

```text
uniapp/src/components/DeviceSyncPanel.vue
```

该组件本批只包含：

- 现有立即状态上报；
- 新增人员同步。

人脸和指纹按钮不得提前生成。

### R-016：所有权边界

本任务禁止修改：

```text
BackendTransportManager.java
BackendHttpGateway.java
BackendHttpClient.java
SerialConnectionManager.java
WorkCardProtocol.java
serialport/**
serial-debug/**
app/libs/**
FaceAiManager.java
FaceEnrollmentController.java
FaceEnrollmentActivity.java
MainActivity中的CameraX/FaceAISDK逻辑
```

## 4. 非范围

- 人脸同步；
- 指纹同步；
- “同步全部”；
- 全量人员同步 UI；
- 新增自动同步周期；
- 修改 `syncUser`；
- 修改后端接口字段；
- 修改 HTTP/MQTT 传输；
- 修改员工新增、编辑、删除页面；
- 修复本机人脸录入回归；
- 修改 FaceAISDK 模板删除实现；
- 创建模板删除重试队列；
- 将人员页面改为公开页面。

## 5. 不变量

1. Android `DeviceDataRepository` 是员工唯一真相；
2. Vue 员工列表只是展示投影；
3. 人员同步继续使用 V4.1 `POST /api/v1/employee/sync`；
4. 增量同步只根据 `deletedEmployeeIds` 删除；
5. 所有分页成功后才提交员工 Map 和游标；
6. HTTP 失败不能清空旧员工 Map；
7. `sync.employees` 不进入外部报文；
8. 人员同步不要求管理员或 MQTT 会话；
9. 不修改人脸、指纹和串口专业实现；
10. 一个子项确认不授权下一子项。

## 6. 外部契约

### 请求

```http
POST /api/v1/employee/sync
Authorization: Bearer {deviceToken}
Content-Type: application/json
```

```json
{
  "lastSyncTime": 0,
  "page": 1,
  "pageSize": 50
}
```

### 响应关键字段

```json
{
  "code": 0,
  "msg": "success",
  "syncVersion": 1753001234567,
  "employees": [],
  "deletedEmployeeIds": [],
  "hasMore": false
}
```

不增加字段，不改变成功码解释，不在 Vue 解析分页。

## 7. 验收标准

### AC-001

未登录管理员时，首页可打开“设备状态与数据同步”面板。

### AC-002

未登录管理员时，可以点击“同步人员”。

### AC-003

Vue 中不存在 `/api/v1/employee/sync`、分页、游标或员工合并实现。

### AC-004

Bridge 动作 `sync.employees` 为公共动作，且只进入 `DeviceDataLayer`。

### AC-005

人员同步调用 `syncEmployees(false)`，不调用 `syncAll/syncFaces/syncFingers`。

### AC-006

同步开始后 Store 先进入 `SYNCING(scope=employees)`，UI 显示同步中。

### AC-007

同步成功后显示人员数量、删除数量、人员游标和完成时间。

### AC-008

同步成功后 `appState.employees` 来自 `sync.completed.snapshot.employees`。

### AC-009

HTTP 分页失败时旧员工 Map 和旧 Vue 列表不被清空。

### AC-010

已有同步任务运行时返回 `BUSY`，不排队执行第二次人员同步。

### AC-011

MQTT 未认证但 HTTP 可用时，不因 MQTT 状态拒绝人员同步。

### AC-012

空员工 Map 和游标 0 时允许同步。

### AC-013

`deletedEmployeeIds` 继续执行现有清理语义，并展示删除数量。

### AC-014

启动同步和 MQTT `syncUser` 行为与修改前一致。

### AC-015

最终 diff 不包含后端底层、MQTT底层、串口或人脸专业文件。

### AC-016

前端语法、H5构建、Android单测、Debug APK与永久门禁通过。

### AC-017

没有真实后端和真机证据时，交付明确标记为“未验证”。

## 8. 待确认项

### Q-001：UI归属

建议复用首页 Header 的公开同步入口，将现有面板改名为“设备状态与数据同步”，在状态上报下增加“同步人员”。

### Q-002：组件拆分

建议把当前首页中的状态面板抽取为 `DeviceSyncPanel.vue`，避免后续每个同步子项继续膨胀 `index.vue`。

### Q-003：同步模式

建议公开入口只允许增量人员同步，不提供全量按钮。

### Q-004：BUSY语义

建议任何现有同步状态为 `SYNCING` 时，人员同步立即返回 BUSY，不排队。

### Q-005：删除语义

建议完整保留现有 `deletedEmployeeIds` 清理行为；本批只展示删除数量，不改模板删除事务逻辑。
