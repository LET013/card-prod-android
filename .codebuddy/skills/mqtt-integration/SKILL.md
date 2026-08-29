---
name: mqtt-integration
description: "本项目 MQTT 协议接入代码规范。当需要新增 MQTT 消息处理、创建 MQTT 服务类、订阅新 topic、定义 MQTT 上行/下行消息契约、或实现 MQTT 请求-应答模式时使用此 skill。触发场景包括：新增业务模块需要通过 MQTT 与服务端通信、创建新的 MqttService 子类、处理新的下行 cmd、或调用 sendSignedEnvelope / sendAndWaitReply 等模式。"
---

# MQTT 协议接入规范

本规范基于项目中已有的 `BaseMqttService` → `HeartbeatManager` 分层实践，
定义接入新 MQTT 消息的标准化步骤和代码模板。

---

## 1. 架构分层

```
业务模块（DeviceBootstrapManager / 业务层）
  → XxxMqttService extends BaseMqttService
    → BaseMqttService（EventBus 注册/反注册、签名信封构建、sendAndWaitReply）
      → XMqttClient（MQTT 连接/订阅/发布）
        → MQTT Broker
```

- **下行消息流**：`XMqttClient.handleMessage` → `MqttMessageEvent` → `EventBus.post` → `XxxMqttService.onMqttMessageEvent` → `handleMqttMessage`
- **上行消息流**：`XxxMqttService.sendSignedEnvelope` → `BaseMqttService` 构建签名 → `XMqttClient.publish`
- **请求-应答**：`sendAndWaitReply()` 在后台线程调用，用 `CountDownLatch` 等待指定 `cmd` 的响应

---

## 2. MQTT 消息信封格式

### 下行 Payload（服务端 → 设备端）

```json
{
  "cmd": "heartbeatResp",
  "msgId": "81dc3782-9bf6-4075-9cbd-27af22b24e78",
  "timestamp": 1784823489314,
  "data": {
    "serverTime": 1784823489314
  }
}
```

- 解析在 `XMqttClient.handleMessage()` 中完成，构造 `MqttMessageEvent(cmd, data, topic, msgId, timestamp)` 通过 EventBus 分发。
- `msgId` 可用于幂等去重和日志追踪。
- `timestamp` 为服务端毫秒时间戳。

### 上行 Payload（设备端 → 服务端）

```java
// 通过 BaseMqttService.sendSignedEnvelope() 自动构建
{
  "cmd": "xxxReq",
  "msgId": "xxxReq-1700000000123-uuid",    // cmd + timestamp + uuid
  "deviceCode": "DEV-001",
  "data": {
    // 业务数据
  },
  "sign": "md5(cmd + msgId + deviceCode + data + deviceToken)"
}
```

- 通过 `BaseMqttService.sendSignedEnvelope(cmd, data)` 或 `sendSignedEnvelope(cmd, msgIdPrefix, data)` 自动生成。

---

## 3. MQTT 服务类模板

```java
// core/mqtt/XxxMqttService.java
package com.xingyao.card.core.mqtt;

import android.util.Log;
import com.xingyao.card.core.bootstrap.CredentialStore;
import org.json.JSONObject;

public class XxxMqttService extends BaseMqttService {
    private static final String TAG = "XxxMqttService";

    public XxxMqttService(XMqttClient mqttClient, String deviceCode,
                          CredentialStore credentialStore) {
        super(mqttClient, deviceCode, credentialStore);
        // 注册 EventBus 以接收下行消息
        register();
        Log.d(TAG, "XxxMqttService created and registered");
    }

    // ---- 下行消息处理 ----

    @Override
    protected void handleMqttMessage(String cmd, JSONObject data, String topic) {
        switch (cmd) {
            case "someResponse":
                handleSomeResponse(data);
                break;
            case "somePush":
                handleSomePush(data);
                break;
            default:
                Log.d(TAG, "未处理的 cmd: " + cmd);
                break;
        }
    }

    private void handleSomeResponse(JSONObject data) {
        // 解析业务字段
        String status = data.optString("status", "");
        // 更新业务状态...
    }

    private void handleSomePush(JSONObject data) {
        // 处理服务端推送...
    }

    // ---- 上行消息发送 ----

    /**
     * 发送业务请求。对应 cmd: someReq → 响应 cmd: someResponse
     */
    public void sendSomeRequest(String param) throws Exception {
        JSONObject data = new JSONObject();
        data.put("param", param);
        sendSignedEnvelope("someReq", data);
        Log.d(TAG, "Sent someReq: param=" + param);
    }

    // ---- 生命周期 ----

    /**
     * 释放资源（Activity/Service 销毁时调用）
     */
    public void shutdown() {
        unregister();
        Log.d(TAG, "XxxMqttService shutdown");
    }
}
```

### 关键规则

1. **必须继承 `BaseMqttService`**：构造函数传入 `(XMqttClient, deviceCode, CredentialStore)`。
2. **构造函数中调用 `register()`**：开始接收 EventBus 分发。
3. **重写 `handleMqttMessage(cmd, data, topic)`**：按 `cmd` 字段路由到具体处理方法。
4. **上行使用 `sendSignedEnvelope(cmd, data)`**：自动生成 msgId、签名并发送。
5. **提供 `shutdown()` 方法**：调用 `unregister()` 移除 EventBus 监听。

---

## 4. Cmd 命名约定

| 方向 | 命名模式 | 示例 |
|------|---------|------|
| 上行请求 | `{业务}Req` | `heartbeatReq`, `cardCollectReq` |
| 下行响应 | `{业务}Resp` | `heartbeatResp`, `cardCollectResp` |
| 下行推送 | `{业务}Push` | `deviceCmdPush`, `alertPush` |

- Cmd 常量统一定义在 `core/mqtt/MqttCmd.java` 中。

---

## 5. EventBus 分发模型

```
XMqttClient.handleMessage(topic, MqttMessage)
  → 解析 JSON → MqttMessageEvent(cmd, data, topic, msgId, timestamp)
  → EventBus.getDefault().post(event)
    → BaseMqttService.onMqttMessageEvent(event)          // @Subscribe(threadMode = MAIN)
      → handleMqttMessage(event.cmd, event.data, event.topic)  // 子类重写
```

- `MqttMessageEvent` 字段：`cmd`、`data`、`topic`、`msgId`、`timestamp`。
- EventBus 回调在 **主线程**（`threadMode = MAIN`），可直接更新 UI。
- 不需要的子类可不重写 `handleMqttMessage`（基类提供空实现）。

---

## 6. 请求-应答模式

当需要通过 MQTT 发送请求并同步等待特定响应 cmd 时：

```java
// 在后台线程中调用（不能在主线程，会阻塞）
try {
    JSONObject reply = sendAndWaitReply(
        "someRequest",      // 上行 cmd
        requestData,        // 业务数据
        30,                 // 超时秒数
        "someResponse"      // 预期的响应 cmd
    );
    // 处理 reply
    String result = reply.optString("result");
} catch (InterruptedException e) {
    // 超时或被中断
}
```

- 内部使用 `CountDownLatch` 实现。
- 必须在 **后台线程** 中调用，不得在主线程阻塞。
- 超时后 `sendAndWaitReply` 自动清理注册的临时监听器。

---

## 7. 心跳消息

继承 `BaseMqttService` 后可直接使用内置心跳方法：

```java
// 发送心跳（自动发送到 heartbeat topic，非 MqttEnvelope 格式）
public void sendHeartbeat() {
    JSONObject data = new JSONObject();
    data.put("deviceCode", deviceCode);
    data.put("timestamp", System.currentTimeMillis());
    sendHeartbeat(data);
}
```

- `sendHeartbeat(data)` 发送到 `topicPrefix + "/heartbeat"`，使用非签名格式。
- 下行心跳响应 cmd 为 `heartbeatResp`，通过 `handleMqttMessage` 处理。

---

## 8. Topic 结构

| Topic | 用途 |
|-------|------|
| `{topicPrefix}/up` | 设备上行消息（sendSignedEnvelope 发送的目标） |
| `{topicPrefix}/down` | 设备下行消息（订阅接收） |
| `{topicPrefix}/heartbeat` | 心跳消息（sendHeartbeat 发送的目标） |

- `topicPrefix` 由 `XMqttClient` 的配置决定（如 `card/{deviceCode}`）。

---

## 9. 生命周期管理

```java
// 创建
XxxMqttService mqttService = new XxxMqttService(
    mqttClient,
    credentialStore.getDeviceCode(),
    credentialStore
);
// constructor 中已调用 register()

// 销毁（Activity onDestroy / Service onDestroy）
mqttService.shutdown();  // 调用 unregister()
```

- **register()** 和 **unregister()** 必须成对调用。
- 服务销毁时不移除 EventBus 注册会导致内存泄漏和重复回调。

---

## 10. 新增 MQTT 接入 Checklist

- [ ] 在 `MqttCmd.java` 中定义上行/下行 cmd 常量
- [ ] 创建 `XxxMqttService extends BaseMqttService`
- [ ] 构造函数中调用 `register()`
- [ ] 重写 `handleMqttMessage()` 处理下行 cmd
- [ ] 上行方法使用 `sendSignedEnvelope(cmd, data)`
- [ ] 提供 `shutdown()` 方法调用 `unregister()`
- [ ] 如用请求-应答模式，确保在后台线程调用
- [ ] cmd 命名和字段必须能在接口文档中找到对应来源
- [ ] `./gradlew :app:compileDebugJavaWithJavac` 通过
- [ ] 未验证的 cmd 和字段标记 `// 待验证`

---

## 11. 现有参考实现

| 文件 | 说明 |
|------|------|
| `core/mqtt/BaseMqttService.java` | 基类：EventBus 管理、签名信封、sendAndWaitReply |
| `core/mqtt/MqttMessageEvent.java` | 下行消息事件：cmd/data/topic/msgId/timestamp |
| `core/mqtt/MqttEnvelope.java` | 上行信封：cmd/msgId/deviceCode/data/sign |
| `core/mqtt/MqttCmd.java` | Cmd 常量定义 |
| `core/mqtt/XMqttClient.java` | MQTT 底层客户端：连接/订阅/发布/handleMessage |
| `core/bootstrap/HeartbeatManager.java` | 范例：BaseMqttService 实战参考 |
