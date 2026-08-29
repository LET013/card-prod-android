# Core 模块使用指南

## 目录结构

```
core/
├── biz/http/          ← 业务 HTTP API 服务（继承 BaseApiService）
│   ├── DeviceApiService.java      设备注册、激活、心跳、状态上报
│   ├── EmployeeApiService.java    员工/人脸/指纹同步
│   ├── CardApiService.java       卡片事件（HTTP 降级路径）
│   └── ReportApiService.java     日志、统计、故障、升级状态上报
├── biz/mqtt/          ← 业务 MQTT 服务（继承 BaseMqttService）
│   ├── CardEventMqttService.java      卡片事件上行 + 远程开门/弹卡下行
│   ├── DataSyncMqttService.java       员工/人脸/指纹分页同步（请求-应答）
│   ├── DeviceCommandMqttService.java  重启/同步用户/配置等下行命令
│   └── DeviceMonitorMqttService.java  状态/心跳/故障/统计等上行上报
├── http/              ← HTTP 基础设施
│   ├── BaseApiService.java       基类：apiGet/apiPost + 自动解包
│   ├── HttpClientManager.java    HTTP 客户端（设备级 Token 管理）
│   ├── ApiResponseUtil.java      信封解包：code 校验 + data 提取
│   └── DeviceTokenProvider.java  Token 提供者
├── mqtt/              ← MQTT 基础设施
│   ├── BaseMqttService.java      基类：sendSignedEnvelope / sendAndWaitReply
│   ├── XMqttClient.java          MQTT 客户端（连接、订阅、发布）
│   ├── MqttCmd.java              全部上行/下行 cmd 常量
│   ├── MqttEnvelope.java         报文信封
│   ├── MqttTopic.java            Topic 构建
│   └── MqttReplyResult.java      请求-应答结果
├── entity/http/       ← HTTP 请求/响应实体（POJO）
├── bootstrap/
│   └── DeviceBootstrapManager.java  Bootstrap 编排（已使用新基建）
└── utils/
    ├── CredentialStore.java      凭证持久化（SharedPreferences）
    └── ...
```

---

## 核心概念

### HTTP 调用链路

```
业务代码 → XXXApiService.apiPost(path, body)
  → BaseApiService.apiPost()
    → HttpClientManager (自动拼接 Base URL + Token Header)
      → ApiResponseUtil.unwrap() (校验 code==200, 提取 data)
        → 返回 JSONObject → 业务代码解析
```

### MQTT 调用链路

```
业务代码 → XXXMqttService.sendSignedEnvelope(cmd, data)
  → BaseMqttService.sendSignedEnvelope()
    → CredentialStore (签名: deviceCode + timestamp + nonce)
      → MqttEnvelope 组装
        → XMqttClient.publish(topic, json)
```

---

## 一、HTTP 服务

### 1.1 创建

每个 HTTP 服务只需要 `HttpClientManager`。该对象由 `DeviceBootstrapManager` 在 bootstrap 阶段创建并维护，可通过 `getHttpClient()` 获取。

```java
DeviceBootstrapManager bm = ...; // 已在 DeviceCoreService 中持有

DeviceApiService    deviceApi   = new DeviceApiService(bm.getHttpClient());
EmployeeApiService  empApi      = new EmployeeApiService(bm.getHttpClient());
CardApiService      cardApi     = new CardApiService(bm.getHttpClient());
ReportApiService    reportApi   = new ReportApiService(bm.getHttpClient());
```

### 1.2 调用示例

**所有 HTTP 方法同步阻塞，必须在后台线程调用。**

```java
// ── DeviceApiService ──────────────────────────────

// 心跳
HeartbeatResponse hb = deviceApi.heartbeat(new HeartbeatRequest("running", System.currentTimeMillis()));

// 卡槽状态上报
StatusReportRequest report = new StatusReportRequest();
report.slots = Arrays.asList(new SlotStatusItem(1, "occupied", 123456L, "E001"));
deviceApi.statusReport(report);

// 授权变更上报
deviceApi.authChange(new AuthChangeRequest("E001", "granted", "admin"));

// 查询授权状态
AuthStatusResponse auth = deviceApi.authStatus();

// 自检上报
SelfCheckRequest check = new SelfCheckRequest();
check.items = Arrays.asList(new CheckItem("motor", "ok", null), new CheckItem("rfid", "fail", "读卡超时"));
deviceApi.selfCheck(check);

// ── EmployeeApiService ────────────────────────────

// 员工分页同步
EmployeeSyncRequest syncReq = new EmployeeSyncRequest(lastTimestamp, page, 100);
EmployeeSyncResponse resp = empApi.sync(syncReq);
for (EmployeeSyncItem item : resp.employees) {
    // 写入 DeviceDataRepository
}

// 人脸分页同步
FaceSyncResponse faceResp = empApi.syncFace(new FaceSyncRequest(lastTimestamp, page, 100));

// 指纹分页同步
FingerSyncResponse fingerResp = empApi.syncFinger(new FingerSyncRequest(lastTimestamp, page, 100));

// 新增 / 修改员工
empApi.upsert(new EmployeeUpsertRequest(/* employee fields */));

// 上传人脸特征
empApi.uploadFace(new FaceUploadRequest("E001", "base64feature...", 0));

// 查询平台已注册人脸列表
FaceRegisteredResponse registered = empApi.getFaceRegistered();

// ── CardApiService (HTTP 降级路径) ────────────────
cardApi.sendEvent(new CardEventRequest("E001", "take", 1, System.currentTimeMillis(), "face"));

// ── ReportApiService ──────────────────────────────

reportApi.logReport(new LogReportRequest(/* ... */));
reportApi.logsBatch(new LogsBatchRequest(/* ... */));
reportApi.statisticsReport(new StatisticsReportRequest(/* ... */));
reportApi.faultReport(new FaultReportRequest(/* ... */));
reportApi.upgradeStatus(new UpgradeStatusRequest(/* ... */));
```

### 1.3 异常处理

```java
try {
    EmployeeSyncResponse resp = empApi.sync(req);
    // 成功，处理 resp
} catch (IOException e) {
    // 网络异常、超时、或服务器返回非 200
    Log.e(TAG, "Sync failed: " + e.getMessage());
}
```

---

## 二、MQTT 服务

### 2.1 创建

MQTT 服务需要 `XMqttClient` + `deviceCode` + `CredentialStore`，同样从 `DeviceBootstrapManager` 获取：

```java
DeviceBootstrapManager bm = ...;

CardEventMqttService    cardEventMqtt  = new CardEventMqttService(
        bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
DataSyncMqttService     dataSyncMqtt   = new DataSyncMqttService(
        bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
DeviceCommandMqttService commandMqtt    = new DeviceCommandMqttService(
        bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
DeviceMonitorMqttService monitorMqtt    = new DeviceMonitorMqttService(
        bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
```

> **重要：MQTT 服务构造时即调用 `register()`（EventBus），创建即生效。销毁时必须调用 `onDestroy()` 释放。**

### 2.2 CardEventMqttService — 卡片事件

**卡片事件上行**（发后不管）：

```java
// 创建时需传入 Callback
cardEventMqttService = new CardEventMqttService(
        mqttClient, deviceCode, credentialStore, new CardEventMqttService.Callback() {
    @Override
    public JSONObject onRemoteOpen(int slotId, String cardNo) {
        // 处理远程开门 → 返回结果
        JSONObject result = new JSONObject();
        result.put("success", true);
        return result;
    }

    @Override
    public JSONObject onRemoteEjectAll() {
        // 处理一键弹卡 → 返回结果
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("ejectedCount", 10);
        return result;
    }

    @Override
    public void onCardEventAck(String msgId) {
        // 服务端已确认
    }
});

// 发送取卡事件
cardEventMqttService.sendCardEvent("CARD001", "take", 3, System.currentTimeMillis(), "face");
// 参数: cardNo, eventType("take"/"return"/"borrow"), slotId, timestamp, authType("face"/"finger"/"password")
```

### 2.3 DataSyncMqttService — 数据分页同步

**请求-应答模式**（阻塞，必须在后台线程调用）：

```java
// 员工数据同步
MqttReplyResult result = dataSyncMqtt.syncEmployeeData(lastTimestamp, 0, 100, 30_000);
if (result.success) {
    JSONObject data = result.rawData;
    // 解析 data: { "employees": [...], "totalPages": 3, ... }
    // 循环分页直到 localPage >= totalPages
} else {
    Log.e(TAG, "Sync failed: " + result.errorMessage);
}

// 人脸数据同步
MqttReplyResult faceResult = dataSyncMqtt.syncFaceData(lastTimestamp, 0, 100, 30_000);

// 指纹数据同步
MqttReplyResult fingerResult = dataSyncMqtt.syncFingerData(lastTimestamp, 0, 100, 30_000);
```

### 2.4 DeviceCommandMqttService — 下行命令

注册回调后，服务端推送命令自动触发：

```java
DeviceCommandMqttService.Callback callback = new DeviceCommandMqttService.Callback() {
    @Override public JSONObject onRestartApp() {
        // 重启 App
        return null;
    }
    @Override public JSONObject onSyncUser(String lastTimestamp) {
        // 触发用户同步
        return null;
    }
    @Override public JSONObject onSyncConfig(String configJson) {
        // 应用配置更新
        return null;
    }
    @Override public JSONObject onRemoteLock(int slotId) {
        // 锁定指定卡槽
        return null;
    }
    @Override public JSONObject onRemoteUnlock(int slotId) {
        // 解锁指定卡槽
        return null;
    }
    @Override public JSONObject onQuerySlotStatus(int slotId) {
        // 查询卡槽状态
        return null;
    }
    @Override public JSONObject onQueryDeviceStatus() {
        // 查询设备整体状态
        return null;
    }
    @Override public JSONObject onForceLogUpload(String startTime, String endTime) {
        // 强制上传日志
        return null;
    }
};

deviceCommandMqttService.setCallback(callback);
```

### 2.5 DeviceMonitorMqttService — 设备监控上报

所有方法**发后不管**，可在任意线程调用：

```java
// 心跳（比 HTTP 心跳更轻量）
monitorMqtt.sendHeartbeat(extras);

// 状态上报
monitorMqtt.sendStatusReport(slots);

// 硬件故障
monitorMqtt.sendHardwareFault("motor", "电机1卡死");

// 统计上报
monitorMqtt.sendStatistics(stats);

// 日志上报
monitorMqtt.sendLogReport(logs);

// 卡片事件
monitorMqtt.sendCardEvent(event);

// 自检报告
monitorMqtt.sendSelfCheckReport(items);

// 升级状态
monitorMqtt.sendUpgradeStatus(status);
```

### 2.6 MQTT 生命周期

```java
// 销毁 — 自动 unregister EventBus
cardEventMqttService.onDestroy();
dataSyncMqttService.onDestroy();
deviceCommandMqttService.onDestroy();
monitorMqttService.onDestroy();
```

---

## 三、集成到 DeviceCoreService

`DeviceBootstrapManager` 在 bootstrap 完成后暴露了所有必需依赖。在 `DeviceCoreService` 中完成装配：

```java
// DeviceCoreService.java 伪代码

private CardEventMqttService cardEventMqttService;
private DataSyncMqttService dataSyncMqttService;
private DeviceCommandMqttService deviceCommandMqttService;
private DeviceMonitorMqttService deviceMonitorMqttService;

// HTTP 服务（无状态，可按需创建或缓存）
private DeviceApiService deviceApiService;
private EmployeeApiService employeeApiService;
private CardApiService cardApiService;
private ReportApiService reportApiService;

@Override
public void onCreate() {
    super.onCreate();
    // ... 现有初始化 ...
    DeviceBootstrapManager bm = bootstrapManager; // 已在别处初始化

    if (bm.isBootstrapComplete()) {
        initRuntimeServices(bm);
    } else {
        // 监听 bootstrap 完成事件，再初始化
    }
}

private void initRuntimeServices(DeviceBootstrapManager bm) {
    // HTTP 服务
    deviceApiService = new DeviceApiService(bm.getHttpClient());
    employeeApiService = new EmployeeApiService(bm.getHttpClient());
    cardApiService = new CardApiService(bm.getHttpClient());
    reportApiService = new ReportApiService(bm.getHttpClient());

    // MQTT 服务
    cardEventMqttService = new CardEventMqttService(
            bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore(), cardEventCallback);
    dataSyncMqttService = new DataSyncMqttService(
            bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
    deviceCommandMqttService = new DeviceCommandMqttService(
            bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());
    deviceMonitorMqttService = new DeviceMonitorMqttService(
            bm.getMqttClient(), bm.getDeviceCode(), bm.getCredentialStore());

    // 注册下行命令回调
    deviceCommandMqttService.setCallback(myCommandCallback);
}

@Override
public void onDestroy() {
    super.onDestroy();
    if (cardEventMqttService != null) cardEventMqttService.onDestroy();
    if (dataSyncMqttService != null) dataSyncMqttService.onDestroy();
    if (deviceCommandMqttService != null) deviceCommandMqttService.onDestroy();
    if (deviceMonitorMqttService != null) deviceMonitorMqttService.onDestroy();
    // BootstrapManager 的 shutdown() 负责 httpClient/mqttClient/credentialStore 的回收
    if (bootstrapManager != null) bootstrapManager.shutdown();
}
```

---

## 四、线程模型速查

| 操作类型 | 线程要求 | 说明 |
|---|---|---|
| HTTP ApiService 调用 | 后台线程 | `IOException` 同步抛出 |
| MQTT sendCardEvent | 任意线程 | 发后不管 |
| MQTT monitor 上报 | 任意线程 | 发后不管 |
| MQTT sendAndWaitReply | 后台线程 | 阻塞等待响应（最长 timeoutMs） |
| MQTT Callback 回调 | MQTT 接收线程 | 不要做耗时操作 |

---

## 五、MqttCmd 常量速查

| 常量 | cmd 值 | 方向 | 说明 |
|---|---|---|---|
| `DEVICE_HEARTBEAT` | `deviceHeartbeat` | 上行 | 设备心跳 |
| `STATUS_REPORT` | `statusReport` | 上行 | 卡槽状态上报 |
| `CARD_EVENT` | `cardEvent` | 上行 | 卡片操作事件 |
| `AUTH_STATUS_CHANGE` | `authStatusChange` | 上行 | 授权状态变更 |
| `SELF_CHECK_REPORT` | `selfCheckReport` | 上行 | 自检报告 |
| `LOG_REPORT` | `logReport` | 上行 | 日志上报 |
| `STATISTICS_REPORT` | `statisticsReport` | 上行 | 统计上报 |
| `FAULT_REPORT` | `faultReport` | 上行 | 故障上报 |
| `UPGRADE_STATUS` | `upgradeStatus` | 上行 | 升级状态 |
| `SYNC_EMPLOYEE_DATA` | `syncEmployeeData` | 上行 | 请求同步员工 |
| `SYNC_FACE_DATA` | `syncFaceData` | 上行 | 请求同步人脸 |
| `SYNC_FINGER_DATA` | `syncFingerData` | 上行 | 请求同步指纹 |
| `BATCH_OPERATION_RESULT` | `batchOperationResult` | 上行 | 批量操作结果 |
| `REMOTE_OPEN` | `remoteOpen` | 下行 | 远程开门 |
| `REMOTE_EJECT_ALL` | `remoteEjectAll` | 下行 | 一键弹卡 |
| `RESTART_APP` | `restartApp` | 下行 | 重启 App |
| `SYNC_USER` | `syncUser` | 下行 | 同步用户数据 |
| `SYNC_CONFIG` | `syncConfig` | 下行 | 同步配置 |
| `REMOTE_LOCK` | `remoteLock` | 下行 | 锁定卡槽 |
| `REMOTE_UNLOCK` | `remoteUnlock` | 下行 | 解锁卡槽 |
| `QUERY_SLOT_STATUS` | `querySlotStatus` | 下行 | 查询卡槽状态 |
| `QUERY_DEVICE_STATUS` | `queryDeviceStatus` | 下行 | 查询设备状态 |
| `FORCE_LOG_UPLOAD` | `forceLogUpload` | 下行 | 强制日志上传 |

---

## 六、调用封装建议

业务层不建议直接持有 ApiService/MqttService 实例。推荐创建一个 `DeviceRuntimeManager`（类比 `DeviceBootstrapManager` 管理 bootstrap 流程），隔离开通信细节：

```java
public class DeviceRuntimeManager {
    private final EmployeeApiService empApi;
    private final DataSyncMqttService dataSyncMqtt;
    // ... 其他服务

    /**
     * 同步全部员工数据（HTTP + MQTT 双路径，优先 MQTT）
     */
    public List<EmployeeSyncItem> syncAllEmployees() {
        // 内部处理分页、去重、错误重试
    }

    public void reportCardEvent(String cardNo, String eventType, int slotId, String authType) {
        // 优先 MQTT，失败降级 HTTP
    }

    public void shutdown() { /* 释放 MQTT 服务 */ }
}
```

Facade 只调用 `DeviceRuntimeManager` 的业务方法，不接触 ApiService/MqttService。
