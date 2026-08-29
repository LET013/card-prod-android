# JsBridge 重新设计方案（V2）

## 1. 设计目标

将 `JsBridge` 改造为**纯能力桥接层**，只暴露底层能力通道，**不包含任何业务逻辑**。业务编排全部留在 Vue 端。

### 1.1 六大能力通道

| # | 通道 | 职责 | 方向 |
|---|------|------|------|
| 1 | **启动流程** | Vue 调用一次 `bootstrap.start`，Java 自动跑完注册→激活→登录→心跳。进度、配置数据、激活码、成功/失败均通过事件回传 | Vue → Java → 事件回传 |
| 2 | **HTTP 通道** | `get(path)` / `post(path, body)` / `download(path, dir)` 原始请求，Token+Header 由 Java 自动管理，支持同步/异步两种模式 | Vue → Java → 响应 |
| 3 | **MQTT 通道** | `send(cmd, data)` 发送 + `loginStatus()` 查询 + `handleMessage(cmd)` 注册监听 + 消息事件推送 | 双向 |
| 4 | **串口通道** | `send(hex)` 发送指令 + `getLogs(count)` 读取通信日志 + `subscribe(cmd)` / `unsubscribe(cmd)` 订阅指令 | 双向 |
| 5 | **SQLite 持久化** | 原始 SQL 执行：`query(sql, params)` 查询 + `execute(sql, params)` 写操作，数据库由 Java 层管理 | Vue → Java → 结果 |
| 6 | **人脸识别** | 发起人脸识别（1:N 搜索）+ 人脸录入，异步事件回传结果（成功/失败/超时/取消） | Vue → Java → 事件回传 |

### 1.2 核心原则

```
Vue 是大脑（业务逻辑），JsBridge 是手脚（能力暴露）

Vue 决定：
  - 什么时机调什么 API
  - 请求参数和业务数据如何组装
  - 收到的事件和响应如何处理
  - 页面跳转、状态展示、用户交互

Java/JsBridge 只负责：
  - 管理 Token + HTTP Headers（Vue 只传 path+body，认证由 Java 自动处理）
  - 执行 HTTP 请求（同步/异步）并返回原始响应（含 401/403 等权限错误）
  - 收发 MQTT 消息并转发响应指令的消息
  - 执行串口指令并转发指定类型的返包
  - 提供 SQLite 数据库，接受 SQL 查询和执行
  - 托管启动流程并回传进度事件（含配置数据）
```

---

## 2. 当前状态

### 2.1 基础建设完成度

| 能力 | 现有组件 | 状态 |
|------|---------|------|
| HTTP | `HttpClientManager` — `get(path)` / `post(path, body)` / `download(path, target)` + sync/async 两种模式 | ✅ 可直接使用 |
| HTTP | Token 自动注入 + 401 自动刷新（`TokenProvider` 接口） | ✅ Java 层自动管理 |
| MQTT | `XMqttClient` — `sendSignedEnvelope(cmd, data)` / `isConnected` | ✅ 可直接使用 |
| MQTT | `MqttMessageEvent` (EventBus) — 所有下行消息通过 `{cmd, msgId, timestamp, data}` 分发 | ✅ 已就绪 |
| MQTT | `MqttConnectionEvent` (EventBus) — 连接/断开事件 | ✅ 已就绪 |
| 串口 | `DeviceSerialManager` — `openDoor` / `querySlot` / `sendSerial` / `readVersion` 等 | ✅ 可直接使用 |
| 串口 | `SlotStateManager` — 卡槽订阅 + 增量推送 | ✅ 可直接使用 |
| 串口 | `Listener.onDataReceived` / `onDataManualSent` / `onDataPollSent` — 实时通信事件 | ✅ 已就绪 |
| Bootstrap | `DeviceBootstrapManager` — 6 阶段启动编排（注册→激活→登录→心跳） | ✅ 可直接使用 |
| Bootstrap | `CredentialStore` — 凭证/配置持久化（与旧 `NativeSettingsRepository` 共享 SP） | ✅ 可直接使用 |
| SQLite | Android 内置 `SQLiteDatabase` — `rawQuery` / `execSQL`，无需额外依赖 | ✅ 可直接使用 |

### 2.2 当前架构问题（与 V1 分析一致）

```
Vue → JsBridge → DeviceApplicationFacade(old/) → DeviceDataLayer(old/) → 20+ 个 old/ 类
```

Facade 是巨型 switch，JsBridge 不是桥梁而是代理。无法删除 `old/`。

---

## 3. 通道设计

### 3.1 启动流程通道

**原则**：Vue 只需要知道「开始启动」和「结果/事件」。Java 托管全部流程。

```
Vue 调用:
  bridge.request("bootstrap.start", {
    serverUrl: "https://xxx.com",
    mqttHost: "mqtt.xxx.com",
    mqttPort: 1883
  })
  → 返回 {accepted: true}

Java 内部流程（DeviceBootstrapManager）:
  Phase 0a: 版本检查
  Phase 0b: 设备注册 → 获得 deviceToken + deviceCode
  Phase A:  激活
    ├─ Path A: valid=true → 直接获得 MQTT 凭证
    └─ Path B: valid=false → 返回 registerCode，进入等待
  Phase B:  等待管理员输入激活码（超时 5 分钟）
  Phase C:  获取配置（communicationMode）
  Phase D:  连接/登录
  Phase E:  启动心跳 → RUNNING

事件推送:
  bridge.emit("bootstrap.progress", {phase: "REGISTERING", message: "正在注册设备..."})
  bridge.emit("bootstrap.progress", {phase: "WAITING_ACTIVATION_CODE", registerCode: "ABC123", expireTime: "..."})
  bridge.emit("bootstrap.progress", {phase: "GETTING_CONFIG", message: "正在获取配置..."})
  bridge.emit("bootstrap.config", {
    communicationMode: "MQTT",             // "HTTP" 或 "MQTT"
    rawConfig: {...}                        // 服务端 /device/config 接口返回的完整原始配置
  })
  bridge.emit("bootstrap.progress", {phase: "LOGGED_IN", message: "登录成功"})
  bridge.emit("bootstrap.success",  {phase: "RUNNING", ...})
  bridge.emit("bootstrap.error",    {phase: "ERROR", code: "...", message: "..."})

Vue 输入激活码:
  bridge.request("bootstrap.provideCode", {code: "123456"})
  → Java verifyCode() → 继续流程
```

**与 `DeviceBootstrapManager` 的集成**：

```java
// JsBridge 构造时或 onStart 中调用
DeviceBootstrapManager bootstrap = new DeviceBootstrapManager(context, httpClient, credentialStore);
bootstrap.addCallback(new BootstrapCallback() {
    @Override public void onPhase(Phase phase, String message, JSONObject extra) {
        activity.sendBridgeEvent("bootstrap.progress", mapPhase(phase, message, extra));
    }
    @Override public void onComplete() {
        activity.sendBridgeEvent("bootstrap.success", new JSONObject());
    }
    @Override public void onError(String code, String message) {
        activity.sendBridgeEvent("bootstrap.error", Map.of("code", code, "message", message));
    }
});
bootstrap.start();
```

**关键点**：
- `DeviceBootstrapManager` 的 Phase B（等待激活码）内部使用了 `CountDownLatch`（5 分钟超时），不会阻塞启动线程——它作为独立线程运行
- 如果 Vue 无需管理注册码输入（纯触摸屏设备），Path A 自动完成，Phase B 跳过
- 启动流程失败后 Vue 可调用 `bootstrap.retry` 重试

---

### 3.2 HTTP 通道

**原则**：只提供 `get` / `post` / `download` 三种原始方法。Java 层自动管理 Token 和 HTTP Headers，Vue 只需指定 path、body 和模式（同步/异步）。不封装业务 ApiService。

**Token 与 Header 管理**：全部由 `HttpClientManager` + `TokenProvider(CredentialStore)` 自动处理：
- Authorization Header 自动注入
- Token 过期时自动刷新
- baseUrl 拼接（`resolveUrl(path)`），Vue 只传相对 path
- Content-Type 根据 body 类型自动设置

**同步 / 异步**：
- **同步**（默认）：Vue 调用后阻塞等待 Http 响应，直接返回结果（在 `ioExecutor` 子线程执行，不阻塞 WebView 主线程）
- **异步**：Vue 调用只传 `requestId`，响应到达后通过 `http.result.{requestId}` 事件推送

```javascript
// ========== 同步模式 ==========
// GET
bridge.request("http.get", {
  path: "/api/v1/device/config"
})
→ {status: 200, body: {communicationMode: "MQTT", ...}}

→ {status: 401, body: {msg: "UNAUTHORIZED"}, error: "TOKEN_EXPIRED"}
→ {status: 403, body: {msg: "FORBIDDEN"}, error: "ACCESS_DENIED"}
→ {status: 500, body: null, error: "SERVER_ERROR"}

// POST
bridge.request("http.post", {
  path: "/api/v1/employee/search",
  body: {keyword: "张三"}
})
→ {status: 200, body: {employees: [...]}}

// DOWNLOAD
bridge.request("http.download", {
  path: "/firmware/v1.2.3.bin",
  targetDir: "/sdcard/firmware/"   // 可选，默认缓存目录
})
→ {status: 200, filePath: "/sdcard/firmware/v1.2.3.bin", size: 1024000}
→ {status: 404, error: "FILE_NOT_FOUND"}


// ========== 异步模式（指定 mode: "async"）==========
bridge.request("http.get", {
  path: "/api/v1/employee/sync",
  mode: "async",
  requestId: "sync_001"           // 用于匹配结果事件
})
→ {accepted: true, requestId: "sync_001"}     // 立即返回

// 稍后事件推送:
bridge.emit("http.result.sync_001", {
  status: 200,
  body: {employees: [...]}
})

// 或失败:
bridge.emit("http.result.sync_001", {
  status: 401,
  error: "TOKEN_EXPIRED"
})
```

**401/403 权限错误处理**：
- 401/403 不抛异常，作为正常的失败响应返回给 Vue
- 响应中包含 `status` 和 `error` 字段，Vue 据此决定 UI 反应（如跳转登录页）
- Java 层的 Token 自动刷新仅在后台透明进行（对已过期的 Token 预先刷新），刷新失败时才透出 401

**实现**：

```java
private final HttpClientManager http;

// ========== 同步 GET ==========
private void handleHttpGet(JSONObject payload, String requestId) {
    ioExecutor.execute(() -> {
        try {
            String path = payload.getString("path");
            JSONObject result = http.get(path);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "HTTP_ERROR", e.getMessage());
        }
    });
}

// ========== 同步 POST ==========
private void handleHttpPost(JSONObject payload, String requestId) {
    ioExecutor.execute(() -> {
        try {
            String path = payload.getString("path");
            JSONObject body = payload.optJSONObject("body");
            JSONObject result = http.post(path, body);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "HTTP_ERROR", e.getMessage());
        }
    });
}

// ========== 同步 DOWNLOAD ==========
private void handleHttpDownload(JSONObject payload, String requestId) {
    ioExecutor.execute(() -> {
        try {
            String path = payload.getString("path");
            String targetDir = payload.optString("targetDir",
                context.getExternalFilesDir(null).getAbsolutePath());
            JSONObject result = http.download(path, targetDir);
            // result: {filePath: "/...", size: 1024000, status: 200}
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "DOWNLOAD_ERROR", e.getMessage());
        }
    });
}

// ========== 异步 GET ==========
private void handleHttpGetAsync(JSONObject payload, String requestId) {
    String asyncId = payload.optString("requestId", requestId);
    // 立即确认
    JSONObject ack = new JSONObject();
    ack.put("accepted", true);
    ack.put("requestId", asyncId);
    sendSuccess(requestId, ack);

    ioExecutor.execute(() -> {
        try {
            String path = payload.getString("path");
            JSONObject result = http.get(path);
            activity.sendBridgeEvent("http.result." + asyncId, result);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            err.put("status", 0);
            err.put("error", e.getMessage());
            activity.sendBridgeEvent("http.result." + asyncId, err);
        }
    });
}
```

**关键点**：
- Token + Header 完全由 Java 层自动管理，Vue 不感知 Authorization 和 baseUrl
- 同步模式：Vue 调用后阻塞等待结果（在 `ioExecutor` 执行，不阻塞 WebView）
- 异步模式：Vue 获得 `{accepted: true}` 后立即返回，结果通过 `http.result.{requestId}` 事件接收
- 401/403 作为正常失败响应返回，不抛异常；Vue 据此决定 UI 反应（跳转登录、提示重新登录等）
- Download 文件保存到指定目录（默认外部存储缓存目录）
- 超时由 OkHttp 控制（默认 connect 10s / read 30s / download 60s）

### 3.3 MQTT 通道

**原则**：只提供 4 个能力 — 发送、查询状态、注册消息处理器、事件推送。

```
Vue 调用:
  bridge.request("mqtt.send", {
    cmd: "device_status",
    data: {deviceId: "xxx"}
  })
  → 成功: {sent: true, msgId: "xxx"}
  → 失败: {sent: false, error: "未连接"}

  bridge.request("mqtt.status")
  → {connected: true, broker: "tcp://mqtt.xxx.com:1883", clientId: "device_xxx"}

  bridge.request("mqtt.handleMessage", {
    cmd: "open_door"
  })
  → {registered: true}

事件推送（Java → Vue）:
  bridge.emit("mqtt.message", {
    cmd: "open_door",
    msgId: "xxx",
    timestamp: 1234567890,
    data: {slot: 5, action: "open"}
  })
  // 只有当 Vue 已经通过 mqtt.handleMessage("open_door") 注册后才转发此事件

  bridge.emit("mqtt.connected", {
    broker: "tcp://mqtt.xxx.com:1883",
    clientId: "device_xxx"
  })

  bridge.emit("mqtt.disconnected", {
    reason: "连接超时"
  })
```

**实现**：

```java
// 已注册的 cmd 集合（线程安全）
private final Set<String> mqttHandlerCmds = ConcurrentHashMap.newKeySet();

// 注册 handler
private void handleMqttHandleMessage(JSONObject payload, String requestId) {
    String cmd = payload.optString("cmd", "");
    if (cmd.isEmpty()) {
        sendError(requestId, "INVALID_CMD", "cmd is required");
        return;
    }
    mqttHandlerCmds.add(cmd);
    // 也可支持取消: mqttHandlerCmds.remove(cmd);
    sendSuccess(requestId, new JSONObject().put("registered", true));
}

// EventBus 接收 MQTT 消息
@Subscribe(threadMode = ThreadMode.MAIN)
public void onMqttMessage(MqttMessageEvent event) {
    if (mqttHandlerCmds.contains(event.getCmd())) {
        JSONObject data = new JSONObject();
        data.put("cmd", event.getCmd());
        data.put("msgId", event.getMsgId());
        data.put("timestamp", event.getTimestamp());
        data.put("data", event.getData());
        activity.sendBridgeEvent("mqtt.message", data);
    }
}

// EventBus 接收连接事件
@Subscribe(threadMode = ThreadMode.MAIN)
public void onMqttConnected(MqttConnectionEvent event) {
    if (event.isConnected()) {
        activity.sendBridgeEvent("mqtt.connected", new JSONObject()
            .put("broker", event.getBrokerUrl())
            .put("clientId", event.getClientId()));
    } else {
        activity.sendBridgeEvent("mqtt.disconnected", new JSONObject()
            .put("reason", event.getReason()));
    }
}
```

**关键点**：
- `handleMessage` 注册是**增量**的：每个 cmd 独立注册，匹配到才转发
- **不过滤已注册之外的消息**：未注册的 cmd 不传 Vue，避免 WebView 被无关消息淹没
- MQTT 连接/断开事件始终推送（无需注册）
- `send` 方法调用 `XMqttClient.sendMessage(cmd, data)`，内部自动处理 `msgId` + 签名

### 3.4 串口通道

**原则**：提供 4 个能力 — 通信日志读取、发送指令、订阅响应、取消订阅。

```
Vue 调用:
  bridge.request("serial.send", {
    hex: "A5 5A 01 02 03"      // 原始十六进制指令
  })
  → {sent: true, timestamp: ..., hex: "..."}

  bridge.request("serial.getLogs", {
    count: 50                    // 最近 50 条
  })
  → {logs: [{type, timestamp, hex, text}, ...]}

  bridge.request("serial.subscribe", {
    cmd: "0x31"                  // 订阅指定功能码响应 (可选，null 则全收)
  })
  → {subscribed: true}

  bridge.request("serial.unsubscribe", {
    cmd: "0x31"
  })
  → {unsubscribed: true}

事件推送（Java → Vue）:
  // 全量 log 事件（始终推送，供调试页实时展示）
  bridge.emit("serial.log", {
    type: "rx",                  // "rx" | "tx"
    timestamp: 1234567890,
    hex: "A5 5A ...",
    text: "�..."
  })

  // 匹配订阅 cmd 的解析帧
  bridge.emit("serial.frame", {
    cmd: "0x31",
    timestamp: 1234567890,
    data: {...}                  // 解析后的帧字段
  })

  // 卡槽状态增量推送
  bridge.emit("slots.changed", {
    slots: [{addr, status, door, presence, ...}],
    timestamp: 1234567890
  })
```

**实现**：

```java
// 串口日志环形缓冲区（保留最近 500 条）
private final Deque<JSONObject> serialLogBuffer = new ConcurrentLinkedDeque<>();
private static final int MAX_LOG_ENTRIES = 500;

// 订阅的功能码集合
private final Set<String> serialSubscribeCmds = ConcurrentHashMap.newKeySet();

// DeviceSerialManager Listener — 记录日志
@Override
public void onDataReceived(JSONObject data) {
    appendLog("rx", data);
    // 匹配订阅 cmd 时推送
    String cmd = data.optString("cmd", "");
    if (!cmd.isEmpty() && serialSubscribeCmds.contains(cmd)) {
        activity.sendBridgeEvent("serial.frame", data);
    }
}

@Override
public void onDataManualSent(JSONObject data) {
    appendLog("tx", data);
}

@Override
public void onDataPollSent(JSONObject data) {
    appendLog("tx", data);  // 轮询也记录（调试用途）
}

private void appendLog(String type, JSONObject data) {
    JSONObject entry = new JSONObject();
    entry.put("type", type);
    entry.put("timestamp", data.optLong("timestamp", System.currentTimeMillis()));
    entry.put("hex", data.optString("hex", ""));
    entry.put("text", data.optString("text", ""));
    serialLogBuffer.addLast(entry);
    // 限制大小
    while (serialLogBuffer.size() > MAX_LOG_ENTRIES) {
        serialLogBuffer.pollFirst();
    }
    // 全量 log 事件始终推送
    activity.sendBridgeEvent("serial.log", entry);
}

// 获取最近 N 条日志
private void handleSerialGetLogs(JSONObject payload, String requestId) {
    int count = payload.optInt("count", 50);
    List<JSONObject> logs = new ArrayList<>();
    for (JSONObject entry : serialLogBuffer) {
        logs.add(entry);
        if (logs.size() >= count) break;
    }
    sendSuccess(requestId, new JSONObject().put("logs", new JSONArray(logs)));
}

// 发送串口指令
private void handleSerialSend(JSONObject payload, String requestId) {
    ioExecutor.execute(() -> {
        try {
            String hex = payload.getString("hex");
            deviceSerialManager.sendSerial(hex, "hex");
            sendSuccess(requestId, new JSONObject()
                .put("sent", true)
                .put("timestamp", System.currentTimeMillis()));
        } catch (Exception e) {
            sendError(requestId, "SERIAL_ERROR", e.getMessage());
        }
    });
}
```

**关键点**：
- 串口通信日志用 `ConcurrentLinkedDeque` 内存环形缓冲区（500 条上限）
- **全量 log 事件始终推送**（`serial.log`），供调试页面实时展示
- **解析帧只在已订阅 cmd 时推送**（`serial.frame`），避免不关心的数据冲击 Vue
- `serial.send` 发送原始 HEX 指令，在 `ioExecutor` 执行（不阻塞 WebView）
- 卡槽状态通过 `SlotStateManager.subscribe` 推送，走 `slots.changed` 事件
- **不在此通道暴露 `openDoor` / `querySlot` 等业务方法** — 这些由 Vue 组合 `serial.send` + `serial.subscribe` 自行实现

### 3.5 SQLite 持久化通道

**原则**：Vue 可直接传入 SQL 语句执行查询或写操作。Java 层管理数据库生命周期（创建、升级、关闭），使用参数化查询防止注入。

```
Vue 调用:
  // ========== 查询 (SELECT) ==========
  bridge.request("storage.query", {
    sql: "SELECT * FROM employees WHERE name LIKE ?",
    params: ["%张三%"]          // ? 占位符参数数组，防 SQL 注入
  })
  → {rows: [{id: 1, name: "张三", ...}, ...], count: 1}

  bridge.request("storage.query", {
    sql: "SELECT COUNT(*) AS cnt FROM employees"
  })
  → {rows: [{cnt: 5}], count: 1}

  // ========== 执行 (INSERT/UPDATE/DELETE/CREATE/DROP) ==========
  bridge.request("storage.execute", {
    sql: "INSERT INTO employees (id, name, phone) VALUES (?, ?, ?)",
    params: ["E001", "张三", "13800138000"]
  })
  → {affectedRows: 1, lastInsertId: 1}

  bridge.request("storage.execute", {
    sql: "CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER)"
  })
  → {affectedRows: 0}

  bridge.request("storage.execute", {
    sql: "UPDATE employees SET name = ? WHERE id = ?",
    params: ["李四", "E001"]
  })
  → {affectedRows: 1}
```

**数据库管理**：
- 数据库文件：`card_vue.db`（位于应用私有目录，不与其他模块共享）
- 数据库在 JsBridge 构造时自动创建（`SQLiteDatabase.openOrCreateDatabase`）
- 使用参数化查询（`?` 占位符 + `selectionArgs`）严格防止 SQL 注入
- 不需要 ORM（Room），直接用 `SQLiteDatabase.rawQuery` 和 `execSQL`
- 不提供数据库版本迁移（Vue 自己通过 `CREATE TABLE IF NOT EXISTS` 管理 schema）

**实现**：

```java
// 数据库在 JsBridge 构造时创建
private final SQLiteDatabase db;

// 构造函数中:
db = context.openOrCreateDatabase("card_vue.db", Context.MODE_PRIVATE, null);
// 启用 WAL 模式提升并发读性能
db.execSQL("PRAGMA journal_mode=WAL");

// ========== 查询 ==========
private void handleStorageQuery(JSONObject payload, String requestId) {
    try {
        String sql = payload.getString("sql");
        JSONArray paramsArray = payload.optJSONArray("params");
        String[] params = toParamsArray(paramsArray);

        Cursor cursor = db.rawQuery(sql, params);
        try {
            JSONArray rows = new JSONArray();
            String[] columnNames = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String col : columnNames) {
                    int index = cursor.getColumnIndex(col);
                    switch (cursor.getType(index)) {
                        case Cursor.FIELD_TYPE_NULL:
                            row.put(col, JSONObject.NULL); break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.put(col, cursor.getLong(index)); break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row.put(col, cursor.getDouble(index)); break;
                        case Cursor.FIELD_TYPE_STRING:
                            row.put(col, cursor.getString(index)); break;
                        case Cursor.FIELD_TYPE_BLOB:
                            row.put(col, "BLOB"); break;   // BLOB 不直接传出
                    }
                }
                rows.put(row);
            }
            JSONObject result = new JSONObject();
            result.put("rows", rows);
            result.put("count", rows.length());
            sendSuccess(requestId, result);
        } finally {
            cursor.close();
        }
    } catch (Exception e) {
        sendError(requestId, "SQL_ERROR", e.getMessage());
    }
}

// ========== 执行写操作 ==========
private void handleStorageExecute(JSONObject payload, String requestId) {
    try {
        String sql = payload.getString("sql");
        JSONArray paramsArray = payload.optJSONArray("params");
        Object[] bindArgs = toBindArgs(paramsArray);

        db.beginTransaction();
        try {
            // 判断是 INSERT/REPLACE 还是其他写操作
            String sqlUp = sql.trim().toUpperCase();
            if (sqlUp.startsWith("INSERT") || sqlUp.startsWith("REPLACE")) {
                // INSERT 使用 insert 方法获取 lastInsertRowId
                String table = extractTableName(sql);
                ContentValues cv = buildContentValues(sql, bindArgs);
                long id = db.insertOrThrow(table, null, cv);
                JSONObject result = new JSONObject();
                result.put("affectedRows", id >= 0 ? 1 : 0);
                result.put("lastInsertId", id);
                sendSuccess(requestId, result);
            } else {
                db.execSQL(sql, bindArgs);
                JSONObject result = new JSONObject();
                result.put("affectedRows", 1);  // execSQL 无返回值
                sendSuccess(requestId, result);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    } catch (Exception e) {
        sendError(requestId, "SQL_EXECUTE_ERROR", e.getMessage());
    }
}
```

**关键点**：
- Vue 传入原始 SQL + 参数数组，Java 用参数化查询防 SQL 注入（不使用字符串拼接 SQL）
- 数据库生命周期由 Java 管理（`openOrCreateDatabase` → WAL 模式 → `close()` on JsBridge.close()）
- Schema 由 Vue 管理：首次使用时通过 `CREATE TABLE IF NOT EXISTS` 建表
- 查询结果返回 `{rows: [...], count: N}`，每行是 JSONObject
- BLOB 字段不直接传出（标记为 `"BLOB"`），如需存取二进制数据应走文件系统
- 写操作在事务中执行，保证原子性
- 不提供 ORM、自动迁移、外键级联 — 这些由 Vue 通过 SQL 语句自行控制
- 如需大数据量支持，可后续替换为 Room 但对外接口不变

### 3.6 人脸识别通道

**原则**：发起操作后，所有结果（成功/失败/超时/取消）均通过事件异步回传。faceId 的组合规则由 Vue 负责，Java 只透传。

**现有基础设施**：`FaceEnrollmentController`（复用 MainActivity 常驻相机 + `face_overlay` 容器），支持录入模式和 1:N 搜索模式。

```
Vue 调用:
  bridge.request("face.recognition.start", {
    threshold: 0.8,              // 可选，匹配阈值，默认 0.8
  })
  → {accepted: true}             // 立即返回，实际结果通过事件回传

  bridge.request("face.recognition.cancel")
  → {cancelled: true}

  bridge.request("face.enrollment.start", {
    faceId: "E001_0"             // faceId 的组合规则由 Vue 决定（如员工ID + 照片序号）
  })
  → {accepted: true}

  bridge.request("face.enrollment.cancel")
  → {cancelled: true}

事件推送（Java → Vue）:
  // 人脸识别结果
  bridge.emit("face.recognized", {
    faceId: "E001",
    score: 0.92
  })
  bridge.emit("face.recognition.timeout", {
    message: "识别超时（15秒）"
  })
  bridge.emit("face.recognition.error", {
    code: "FACE_SEARCH_ERROR",
    message: "..."
  })
  bridge.emit("face.recognition.cancelled", {})

  // 人脸录入结果
  bridge.emit("face.enrolled", {
    faceId: "E001_0",
    faceFeature: "base64...",     // 1024 维 Base64 特征
    score: 0.96                    // 活体分数
  })
  bridge.emit("face.enrollment.timeout", {
    message: "录入超时（8秒未检测到人脸）"
  })
  bridge.emit("face.enrollment.error", {
    code: "FACE_FEATURE_EXTRACTION_FAILED",
    message: "..."
  })
  bridge.emit("face.enrollment.cancelled", {})
```

**实现**：

```java
// ========== 人脸控制器状态 ==========
private FaceEnrollmentController currentFaceController;
private String currentFaceAction;    // "recognition" | "enrollment" | null
private boolean faceActive = false;

// ========== 发起人脸识别 ==========
private void handleFaceRecognitionStart(JSONObject payload, String requestId) {
    if (faceActive) {
        sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
        return;
    }
    float threshold = (float) payload.optDouble("threshold", 0.8);
    currentFaceAction = "recognition";
    faceActive = true;
    sendSuccess(requestId, new JSONObject().put("accepted", true));

    // 必须在主线程操作 UI
    activity.runOnUiThread(() -> {
        // face_overlay 浮到最前
        activity.showFaceContainerWithoutButtons();  // 搜索模式不显示拍照按钮
        currentFaceController = new FaceEnrollmentController(
            activity,
            false,                   // isEnroll = false → 搜索模式
            "", "",                  // faceId, faceName 不适用
            null, null, null,        // 不传 UI 组件，参数透传标记走纯回调模式
            createFaceCallback()
        );
        currentFaceController.start();
    });
}

// ========== 发起人脸录入 ==========
private void handleFaceEnrollmentStart(JSONObject payload, String requestId) {
    if (faceActive) {
        sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
        return;
    }
    String faceId = payload.optString("faceId", "");
    if (faceId.isEmpty()) {
        sendError(requestId, "INVALID_FACE_ID", "faceId is required");
        return;
    }
    currentFaceAction = "enrollment";
    faceActive = true;
    sendSuccess(requestId, new JSONObject().put("accepted", true));

    String faceIdForDisplay = faceId; // 捕获到 lambda
    activity.runOnUiThread(() -> {
        activity.showFaceContainerWithButtons(faceIdForDisplay);
        currentFaceController = new FaceEnrollmentController(
            activity,
            true,                    // isEnroll = true → 录入模式
            faceIdForDisplay,        // 透传 Vue 提供的 faceId
            "",                      // faceName 暂不暴露
            null, null, null,
            createFaceCallback()
        );
        currentFaceController.start();
    });
}

// ========== 取消 ==========
private void handleFaceRecognitionCancel(JSONObject payload, String requestId) {
    cancelCurrentFaceOperation();
    sendSuccess(requestId, new JSONObject().put("cancelled", true));
}

private void handleFaceEnrollmentCancel(JSONObject payload, String requestId) {
    cancelCurrentFaceOperation();
    sendSuccess(requestId, new JSONObject().put("cancelled", true));
}

private void cancelCurrentFaceOperation() {
    if (currentFaceController != null) {
        currentFaceController.stop();
        currentFaceController = null;
    }
    faceActive = false;
    currentFaceAction = null;
    activity.hideFaceContainer();
}

// ========== 人脸结果回调 — 统一转发为事件 ==========
private FaceEnrollmentController.FaceResultCallback createFaceCallback() {
    return new FaceEnrollmentController.FaceResultCallback() {
        @Override
        public void onFaceEnrolled(String faceId, String faceFeature, float score) {
            currentFaceController = null;
            faceActive = false;
            currentFaceAction = null;
            activity.hideFaceContainer();
            JSONObject data = new JSONObject();
            try {
                data.put("faceId", faceId);
                data.put("faceFeature", faceFeature);
                data.put("score", (double) score);
            } catch (JSONException ignored) {}
            emitToVue("face.enrolled", data);
        }

        @Override
        public void onFaceVerified(String faceId, float score) {
            currentFaceController = null;
            faceActive = false;
            currentFaceAction = null;
            activity.hideFaceContainer();
            JSONObject data = new JSONObject();
            try {
                data.put("faceId", faceId);
                data.put("score", (double) score);
            } catch (JSONException ignored) {}
            emitToVue("face.recognized", data);
        }

        @Override
        public void onCancelled() {
            String action = currentFaceAction;
            currentFaceController = null;
            faceActive = false;
            currentFaceAction = null;
            activity.hideFaceContainer();
            emitToVue(action != null ? "face." + action + ".cancelled" : "face.cancelled", new JSONObject());
        }
    };
}
```

**依赖问题处理**：

`FaceEnrollmentController` 当前依赖 `old/DeviceRuntimeRegistry` 做两件事：
1. 录入时提取特征 → 可直接调用 `FaceAiManager.getInstance().extractFaceFeature(bitmap)`
2. 搜索时读取阈值 → 可改为构造参数注入，默认 0.8f

在 JsBridge V2 中，通过以下方式切断 old/ 依赖：
- 将 `FaceEnrollmentController` 的 `DeviceRuntimeRegistry` 调用替换为参数注入 + 直接调用
- 新增构造函数重载或 Builder 模式，接受 `FaceFeatureExtractor` 接口和 `threshold` 参数
- 修改范围：仅 `FaceEnrollmentController` 自身的依赖获取方式，不改动 FaceAISDK 层

```
FaceEnrollmentController 改造：
  旧: DeviceRuntimeRegistry.require().extractFaceFeature(bitmap)
  新: FeatureExtractor extractor = (bitmap) -> FaceAiManager.getInstance().extractFaceFeature(bitmap)
      controller.setFeatureExtractor(extractor)

  旧: DeviceRuntimeRegistry.require().faceRecognitionThreshold()
  新: controller.setRecognitionThreshold(threshold)  // 由 JsBridge 从配置或默认值传入
```

**时序说明**：

```
Vue                           Java                              CameraX/FaceAISDK
 │                             │                                    │
 ├─ face.recognition.start ──→│                                    │
 │←── {accepted: true} ──────┤                                    │
 │                             ├─ face_overlay bringToFront ──→    │
 │                             ├─ new FaceEnrollmentController     │
 │                             ├─ setFaceAnalyzer() ──────────────→│
 │                             │                                    ├─ 持续分析帧
 │                             │                                    ├─ runSearchWithImageProxy()
 │                             │                                    ├─ onMostSimilar("E001", 0.92)
 │                             │←──── callback.onFaceVerified() ───┤
 │←── emit("face.recognized",  │                                    │
 │     {faceId:"E001",0.92}) ──┤                                    │
 │                             ├─ clearFaceAnalyzer() ────────────→│
 │                             ├─ hideFaceContainer()               │
 │                             │                                    │
 │  （超时）                     │                                    │
 │                             │                                    ├─ 15s 无匹配
 │←── emit("face.recognition.  │                                    │
 │     timeout", ...) ────────┤                                    │
```

**关键点**：
- 人脸操作是**互斥**的：同一时间只能有一个识别或录入在进行
- **faceId 组合规则完全由 Vue 决定**（如 `E001_0` 表示员工 E001 的第 0 张照片），Java 只透传不解析
- 特征提取结果 (`faceFeature`) 原样传给 Vue，Vue 决定是否通过 HTTP 通道上报服务端
- 录入/识别**结果始终通过事件推送**，不走 request-response 模式，因为操作时长不确定（秒级）
- 取消操作立即清理状态，旧 task 的后续回调因 `faceActive=false` 被丢弃
- `FaceEnrollmentController` 需小幅改造以移除对 `DeviceRuntimeRegistry` 的依赖（注入特征提取器 + 阈值参数）
- 搜索超时由 `FaceSearchEngine` 内部处理（15s），录入超时由 `AddFaceDispose` 超时定时器处理（8s）

---

## 4. 事件总览

所有 Java → Vue 的主动推送：

| 事件 | data | 触发时机 |
|------|------|---------|
| `bootstrap.progress` | `{phase, message, extra?}` | 启动流程每个阶段 |
| `bootstrap.config` | `{communicationMode, rawConfig: {...}}` | Phase C 获取配置后，通知 Vue 配置数据 |
| `bootstrap.success` | `{phase: "RUNNING", ...}` | 启动完成 |
| `bootstrap.error` | `{code, message}` | 启动失败 |
| `http.result.{requestId}` | `{status, body}` 或 `{status, error}` | 异步 HTTP 请求完成 |
| `mqtt.connected` | `{broker, clientId}` | MQTT 连接成功 |
| `mqtt.disconnected` | `{reason}` | MQTT 断开 |
| `mqtt.message` | `{cmd, msgId, timestamp, data}` | 已注册 cmd 的下行消息 |
| `serial.log` | `{type, timestamp, hex, text}` | 每次串口收发（全量，实时） |
| `serial.frame` | `{cmd, timestamp, data}` | 已订阅 cmd 的解析帧 |
| `slots.changed` | `{slots, timestamp}` | 卡槽状态变更（1s 增量/60s 全量） |
| `face.recognized` | `{faceId, score}` | 人脸识别成功匹配 |
| `face.recognition.timeout` | `{message}` | 人脸识别超时（15s） |
| `face.recognition.error` | `{code, message}` | 人脸识别异常 |
| `face.recognition.cancelled` | `{}` | 用户取消人脸识别 |
| `face.enrolled` | `{faceId, faceFeature, score}` | 人脸录入成功 |
| `face.enrollment.timeout` | `{message}` | 人脸录入超时（8s） |
| `face.enrollment.error` | `{code, message}` | 人脸录入异常 |
| `face.enrollment.cancelled` | `{}` | 用户取消人脸录入 |

---

## 5. 目标架构

```
Vue (uniapp/)
  │ window.android.postMessage(JSON)
  ▼
WebViewManager                        ← origin + main-frame 验证（不变）
  │
  ▼
新 JsBridge (webview/)               ← ~300 行，纯能力桥接
  │
  ├─ 启动流程 ─────→ DeviceBootstrapManager          ← 注册→激活→登录→心跳
  │                    │ EventBus.BootstrapEvent
  │                    └─→ sendBridgeEvent("bootstrap.*") 
  │
  ├─ HTTP ──────────→ HttpClientManager               ← get/post/download (sync/async)
  │                    (Token + Header 自动管理，401 正常返回)  http.result.{id} 事件
  │
  ├─ MQTT ──────────→ XMqttClient                     ← sendMessage(cmd, data) / isConnected
  │                    EventBus 订阅:
  │                    ├─ MqttMessageEvent  → sendBridgeEvent("mqtt.message")  (已注册 cmd)
  │                    └─ MqttConnectionEvent → sendBridgeEvent("mqtt.connected/disconnected")
  │
  ├─ 串口 ──────────→ DeviceSerialManager             ← sendSerial(hex) / Listener
  │                    ├─ 环形缓冲区 (500条)  → getLogs(count)
  │                    ├─ 订阅过滤            → serial.frame (已订阅 cmd)
  │                    └─ 全量转发            → serial.log (实时 debug)
  │                    SlotStateManager.subscribe → sendBridgeEvent("slots.changed")
  │
  ├─ 存储 ──────────→ SQLiteDatabase("card_vue.db")            ← storage.query(sql, params)
  │                    │                                         ← storage.execute(sql, params)
  │                    └─ WAL 模式 / 参数化查询 / 事务
  │
  └─ 人脸 ──────────→ FaceEnrollmentController                ← MainActivity 常驻相机 + face_overlay
                       │ extractFaceFeature(bitmap) → FaceAiManager
                       │ 录入: AddFaceDispose  → face.enrolled 事件
                       │ 搜索: FaceSearchEngine → face.recognized 事件
                       │ 超时/取消/错误         → face.* 事件
```

---

## 6. JsBridge 接口定义（完整）

```java
package com.xingyao.card.webview;

public class JsBridgeV2 {
    
    // ========== 构造 & 生命周期 ==========
    public JsBridgeV2(MainActivity activity,
                      HttpClientManager httpClient,
                      XMqttClient mqttClient,
                      DeviceSerialManager serialManager,
                      SlotStateManager slotStateManager,
                      DeviceBootstrapManager bootstrapManager) { ... }
    
    // WebView → Java（与现有 WebViewManager 接口兼容）
    public void handleTrustedMessage(String rawJson);
    
    // 销毁
    public void close();  // 取消 EventBus 注册、取消 SlotStateManager 订阅、关闭 ioExecutor
    
    // ========== 内部 ==========
    // 请求/响应模式
    private void sendSuccess(String requestId, JSONObject data);
    private void sendError(String requestId, String code, String message);
    
    // 事件推送模式
    private void emitToVue(String event, JSONObject data);  // → activity.sendBridgeEvent()
    
    // 路由分发
    private void dispatch(String action, JSONObject payload, String requestId);
    
    // IO 线程池（串口阻塞调用 + HTTP 同步调用）
    private final ExecutorService ioExecutor;
    
    // 串口日志环形缓冲
    private final Deque<JSONObject> serialLogs;
    private final Set<String> serialSubscribedCmds;
    
    // MQTT handler 注册
    private final Set<String> mqttHandledCmds;
    
    // 存储 (SQLite)
    private final SQLiteDatabase db;
    
    // 人脸
    private FaceEnrollmentController currentFaceController;
    private String currentFaceAction;   // "recognition" | "enrollment" | null
    private boolean faceActive;
    
    // MainActivity 引用（用于获取 face_overlay 容器、setFaceAnalyzer 权限）
    private final MainActivity activity;
}
```

---

## 7. 与旧架构对比

| 维度 | 旧 JsBridge | 新 JsBridge |
|------|------------|------------|
| **行数** | ~90 行（桥） + ~270 行（Facade） + 数千行（DataLayer） | ~300 行（自包含） |
| **依赖** | old/ 下 20+ 个类 | 0 个 old/ 类 |
| **业务逻辑** | 30+ action 路由 switch，分散在 Facade + DataLayer | **0 行**，全部在 Vue |
| **HTTP** | 映射到具体 ApiService 业务方法 | `get(url)` / `post(url, body)` 原始通道 |
| **MQTT** | 无暴露 | `send` + `status` + `handleMessage` 过滤转发 |
| **串口** | 无暴露（通过 Facade 间接） | `send` + `getLogs` + `subscribe/unsubscribe` |
| **启动流程** | Vue 调多个 action 拼凑流程 | Vue 调一个 `bootstrap.start`，事件回传 |
| **持久化** | 无 | `query(sql, params)` + `execute(sql, params)` 原始 SQL 执行 |
| **人脸** | Facade 路由到 `face.verify`/`face.enroll`，结果走 request-response | `face.recognition.start`/`face.enrollment.start`，结果全部走事件推送 |
| **删除 old/** | 阻塞 | 零依赖，可删除 |

---

## 8. 实现计划

### 阶段一：串口日志 + 存储（低风险，无破坏性变更）
1. 在 `DeviceSerialManager` 的 Listener 回调中添加日志缓冲逻辑（或直接在 JsBridge 中缓冲）
2. 创建 `card_vue.db` SQLiteDatabase 的 `query(sql, params)` / `execute(sql, params)` 实现
3. 编译验证

### 阶段二：新建 JsBridgeV2（无破坏性变更）
1. 新建 `JsBridgeV2.java` 在 `webview/` 包
2. 实现 `handleTrustedMessage` → 解析 JSON → `dispatch(action, payload, requestId)`
3. 实现 6 个通道：HTTP、MQTT、Serial、Bootstrap、Storage、Face
4. 改造 `FaceEnrollmentController`：注入 `FeatureExtractor` + threshold 参数，移除 `DeviceRuntimeRegistry` 依赖
5. 在 `WebViewManager` 中支持切换新旧 Bridge（通过 Feature Flag）
6. 编译 + 构建验证

### 阶段三：Vue 端适配
1. 更新 `uniapp/src/services/nativeBridge.js` — 增加 6 通道所有 action + emit 监听
2. 更新 `uniapp/src/services/index.js` — 业务逻辑用新 action 重写
3. 人脸页面改造：录入/识别改为调 `face.recognition.start` / `face.enrollment.start`，结果使用 `bridge.on("face.*", callback)` 监听事件
4. H5 构建 + 验证

### 阶段四：切换 & 清理
1. 在 `MainActivity` 中切换为 `JsBridgeV2`
2. 真机验证
3. 删除 `old/DeviceApplicationFacade.java`
4. 删除 `old/` 下其他无外部引用的类

---

## 9. 风险

| 风险 | 缓解 |
|------|------|
| 串口日志 500 条环形缓冲内存占用 | 每条约 200 bytes，500 条约 100KB，可接受 |
| HTTP 同步阻塞在 ioExecutor 可能导致请求堆积 | 单线程队列，Vue 应避免同时发起大量同步请求 |
| `sendBridgeEvent` 高频调用（serial.log 每帧推送） | 串口正常轮询 35s/轮/100槽，log 频率可控；调试模式下可加 throttle |
| `DeviceBootstrapManager` 使用 CountDownLatch 等待激活码 | 5 分钟超时，独立线程运行，不阻塞 Bridge |
| MQTT 消息 cmds 注册后遗漏注销 | `close()` 时清空所有 set，Vue 在页面卸载时主动调 `mqtt.handleMessage` 清空 |
| SQL 注入风险（Vue 直接传 SQL 给 Java） | 强制参数化查询（`?` 占位符 + `selectionArgs`），拒绝字符串拼接 SQL |
| SQLite 多线程写锁冲突 | WAL 模式 + Java 端单线程执行 `storage.*`；Vue 避免并发大量写操作 |
| `FaceEnrollmentController` 依赖 `old/DeviceRuntimeRegistry` | 小幅改造：注入 `FeatureExtractor` 接口 + 阈值参数，不改动 FaceAISDK 层 |
| 人脸操作互斥防重入 | `faceActive` 布尔标记 + 收到请求立即检查，拒绝第二个请求 |
| 人脸超时后 WebView 可能已销毁 | `close()` 时主动调用 `cancelCurrentFaceOperation()` 清理 |

---

## 10. 总结

| 维度 | 结论 |
|------|------|
| **HTTP 通道** | ✅ `HttpClientManager.get/post` 已就绪 |
| **MQTT 通道** | ✅ `XMqttClient` + EventBus 已就绪 |
| **串口通道** | ✅ `DeviceSerialManager.Listener` + 环形缓冲 + 订阅过滤 |
| **启动流程** | ✅ `DeviceBootstrapManager` 6 阶段已实现 |
| **SQLite 持久化** | ✅ Android 内置 `SQLiteDatabase`，参数化查询防注入 |
| **人脸识别** | ✅ `FaceEnrollmentController` + MainActivity 常驻相机，需小幅改造移除 old/ 依赖 |
| **删除 old/** | ✅ 新 JsBridge 不依赖任何 old/ 类 |
| **Vue 端变更** | ⚠️ `services/index.js` 需重写（但架构更清晰） |

**核心结论：完全可行。** 新 JsBridge 以 6 个原始能力通道替代旧 Facade 的 30+ action 路由，将全部业务逻辑归还 Vue。自身代码量约 500 行，零业务依赖。
