# 服务端接口变更说明（客户端需要适配）

> 生成时间：2026-08-09

本文档列出服务端接口变更，按对客户端的影响程度排序。无影响的部分不列出。

---

## 一、必须适配（接口返回格式变化）

### 1. HTTP `GET /api/v1/employee/face/registered` 返回格式变化

获取已注册人脸列表接口，返回格式调整，与 MQTT 通道对齐。

**旧格式**：
```json
{
    "code": 200,
    "msg": "操作成功",
    "data": [1001, 1002, 1003]
}
```

**新格式**：
```json
{
    "code": 200,
    "msg": "操作成功",
    "data": {
        "employeeIds": [1001, 1002, 1003],
        "count": 3
    }
}
```

**影响**：客户端解析 `data` 的方式从数组改为对象，需要获取 `data.employeeIds`。

---

### 2. HTTP `POST /api/v1/device/login` 返回去掉 `serverTime`

设备登录接口，内层 `data` 中去掉 `serverTime` 字段，与 MQTT 登录响应完全对齐。

**旧格式**：
```json
{
    "code": 200,
    "msg": "操作成功",
    "data": {
        "code": 0,
        "msg": "success",
        "token": "xxx",
        "serverTime": 1754736000000
    }
}
```

**新格式**：
```json
{
    "code": 200,
    "msg": "操作成功",
    "data": {
        "code": 0,
        "msg": "success",
        "token": "xxx"
    }
}
```

**影响**：客户端如有依赖 `data.serverTime` 做校时逻辑，请改用 MQTT 心跳的 `serverTime` 或从 HTTP 响应头 `Date` 获取。

---

## 二、建议适配（新增可选字段）

### 3. MQTT `heartbeat` 支持 `seq` 字段

心跳消息现在支持传入客户端毫秒时间戳 `seq`，用于后续故障排查和延迟分析。

**Topic**：`card/{deviceId}/up`，`cmd: "heartbeat"`

**新增请求格式**（`raw` 字段）：
```json
{
    "seq": 1754736123456
}
```

- `seq`：Long 类型，客户端发出心跳时的 `Date.now()`（毫秒），可选
- 不传 `seq` 完全兼容旧版本，服务端不做降级处理
- 响应格式不变：`{"code":0, "serverTime": 1754736123456}`

---

### 4. MQTT `saveEmployeeResp` 新增 `action` 字段

保存员工响应新增 `action` 字段，指示本次操作类型。

**Topic**：`card/{deviceId}/down`，`cmd: "saveEmployeeResp"`

**旧格式**：
```json
{
    "code": 0,
    "msg": "员工保存成功",
    "employeeId": 1001
}
```

**新格式**：
```json
{
    "code": 0,
    "msg": "员工保存成功",
    "employeeId": 1001,
    "action": "add"
}
```

- `action`：`"add"` 表示新增，`"update"` 表示更新，`"enable"` 表示启用，`"disable"` 表示停用
- 人员启用/停用由客户端上行 `saveEmployee`，请求只携带 `employeeId` 与 `action=enable|disable`。
- 客户端可据此做不同 UI 行为（如新增跳列表、更新留在原地）
- 不解析 `action` 不影响功能

---

## 三、可以同步清理（无破坏性）

### 5. 8 个 HTTP 接口的请求体 `deviceId` 字段已废弃

以下接口的服务端已不再读取请求体中的 `deviceId` 字段（改用 Token 自动识别设备），客户端可以不再发送：

| HTTP 方法 | 接口路径 | 废弃字段 |
|-----------|----------|----------|
| `POST` | `/api/v1/card/event` | `deviceId` (String) |
| `POST` | `/api/v1/employee/face` | `deviceId` (Long) |
| `POST` | `/api/v1/fault/report` | `deviceId` (String) |
| `POST` | `/api/v1/log/report` | `deviceId` (String) |
| `POST` | `/api/v1/device/status` | `deviceId` (String) |
| `POST` | `/api/v1/fingerprint/upload` | `deviceId` (String) |
| `POST` | `/api/v1/log/toggle` | `deviceId` (Long) |
| `POST` | `/api/v1/card/action` | `deviceId` (String) |

- **继续发送也完全兼容**（服务端 Jackson 会忽略未知字段），不会报错
- 建议客户端在下次发版时移除这些冗余字段，减少请求体大小

---

## 四、新增配置字段

### 6. 设备配置新增 `mqttStatusReportInterval`

`GET/POST /api/v1/device/config` 接口新增 `mqttStatusReportInterval` 字段。

```json
{
    "mqttStatusReportInterval": 300
}
```

- 类型：Integer，单位：秒，默认值：300
- 含义：MQTT 状态上报（statusReport）间隔时间
- 客户端读取后按此间隔定时上报卡槽状态

---

## 变更汇总

| 序号 | 影响等级 | 接口 | 变更内容 |
|------|---------|------|---------|
| 1 | **必须改** | HTTP 获取已注册人脸 | `data` 从数组变为对象 `{employeeIds, count}` |
| 2 | **必须改** | HTTP 登录 | 去掉 `data.serverTime` |
| 3 | 可选 | MQTT 心跳 | 支持 `seq` 时间戳参数 |
| 4 | 可选 | MQTT 保存员工响应 | 新增 `action` 字段 |
| 5 | 可清理 | 8个 HTTP 接口 | `deviceId` 字段可不再发送 |
| 6 | 新增 | 设备配置 | 新增 `mqttStatusReportInterval` |
