# 智能工卡发卡机 Android 客户端接口文档

> 版本：V4.2 | 更新日期：2026-07-23
>
> **V4.2 主要变更：**
> - `cardEvent` 新增 `employeeId` 字段（取卡时必填，关联员工身份）
> - 旧版 HTTP 取还卡接口已废弃（`LegacyController`/`CardEventController`），卡事件统一走 MQTT `cardEvent` 通道
> - `login` 成功后终端需启动 60s 定时心跳（MQTT: `card/{deviceId}/heartbeat` 或 HTTP: `POST /api/v1/device/heartbeat`）
> - 新增 `FORCE_RETURN` 卡事件类型（状态同步异常时系统自动下发）
> - `biz_card_status` 字典语义修正：`IN_USE`=在卡柜中（原"使用中"），`AVAILABLE`=被取出（原"可用"）

---

## 目录

1. [概述](#1-概述)
2. [启动与激活流程](#2-启动与激活流程)
3. [MQTT 通信协议](#3-mqtt-通信协议)
4. [HTTP API 接口](#4-http-api-接口)
5. [MQTT 与 HTTP 指令对照表](#5-mqtt-与-http-指令对照表)
6. [通用错误码](#6-通用错误码)

---

## 1. 概述

### 1.1 两种通信模式

| 模式 | 特点 | 适用场景 |
|------|------|----------|
| **MQTT** | 双向实时推送，长连接，心跳保活 | 在线状态、指令下发、实时事件上报 |
| **HTTP** | 请求-响应，短连接，无状态 | 大数据传输（>100KB）、文件上传下载、MQTT 降级通道 |

> 原则: MQTT 和 HTTP **互补而非替代**。两者可同时使用。

### 1.2 鉴权体系

| 阶段 | 凭证 | 获取方式 | 生命周期 |
|------|------|----------|----------|
| 注册 | 无需凭证 | `POST /register` | - |
| 版本检测 | 无需凭证 | `POST /app-version/check` | - |
| HTTP 请求 | `deviceToken` | 注册返回 | 永久有效（注册时生成，不再变更） |
| MQTT 连接 | `mqttPassword` | 激活/验证返回 | 每次 activate/verify 都重新生成 |
| MQTT 连接 | `clientId` | 首次激活返回 | 首次激活时生成，后续复用（不重新生成） |
| 消息签名 | `signingKey` | 激活/验证返回 | 每次 activate/verify 都重新生成 |

### 1.3 通用 HTTP 响应格式

```json
{ "code": 200, "msg": "success", "data": { ... } }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 200=成功，500=失败 |
| `msg` | String | 消息描述 |
| `data` | Object | 业务数据 |

### 1.4 HTTP 请求头

所有请求（除注册接口）需携带：
```
Authorization: Bearer {deviceToken}
Content-Type: application/json
```

---

## 2. 启动与激活流程

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 阶段 0   │───→│ 阶段 A   │───→│ 阶段 B   │───→│ 阶段 C   │───→│ 阶段 D   │
│ 设备注册 │    │ 设备激活 │    │ 注册码验 │    │ 获取配置 │    │ 设备登录 │
│(Anonymous)│  (Bearer)   │    │(Bearer)  │    │(Bearer)  │    │ MQTT/HTTP│
└──────────┘    └────┬─────┘    └────┬─────┘    └──────────┘    └──────────┘
                     │               │
              ┌──────┘               │
              │路径A: 后台已激活       │
              │→ 直接返回MQTT密码      │
              │→ 跳过阶段B，直接进入阶段C│
              │                       │
              │路径B: 终端激活码        │
              │→ 返回registerCode ──→ │ 
              └──────────────────────┘
```

### 2.1 阶段 0: 设备注册

首次启动时调用，用机器指纹报到。

`POST /api/v1/device/register` (@Anonymous, 无需token，但必须要Android系统的唯一编码machineId) → 返回 `deviceToken` + `deviceCode`

### 2.2 阶段 A: 设备激活

携带 `deviceToken` 调用 `POST /api/v1/device/activate`。

| 路径 | 条件 | 返回 |
|------|------|------|
| 路径A | 后台已设为ACTIVATED | `mqttPassword` + `signingKey` + `clientId` |
| 路径B | 终端激活码方式 | `registerCode` → 进入阶段B |

### 2.3 阶段 B: 注册码验证（仅路径B）

终端展示 `registerCode`，管理员输入激活码后调用 `POST /api/v1/device/verify`。

成功返回 `mqttPassword` + `signingKey` + `clientId`。

### 2.4 阶段 C: 获取设备配置

`GET /api/v1/device/config` → 返回 `communicationMode` 决定后续通信方式。

### 2.5 阶段 D: 设备登录

- **MQTT模式**: 连接EMQX → 订阅 `card/{deviceCode}/down` 和 `card/{deviceCode}/down/response` → 发送 `login` 上行消息 → 收到 `loginResp`(code=0) 后**启动 60s 定时心跳**
- **HTTP模式**: `POST /api/v1/device/login` → 成功后**启动 60s 定时心跳** (`POST /api/v1/device/heartbeat`)

---

## 3. MQTT 通信协议

### 3.1 Topic 体系

| Topic | 方向 | QoS | 说明 |
|-------|------|-----|------|
| `card/{deviceCode}/up` | 设备→服务端 | 1 | 上行消息主通道 |
| `card/{deviceCode}/down` | 服务端→设备 | 1 | 下行指令（终端需订阅） |
| `card/{deviceCode}/down/response` | 服务端→设备 | 1 | 上行消息响应（终端需订阅） |
| `card/{deviceCode}/heartbeat` | 设备→服务端 | 0 | 心跳 |

**ACL限制**: 设备只能订阅 `card/{deviceCode}/down` 和 `card/{deviceCode}/down/response`，只能发布 `card/{deviceCode}/up` 和 `card/{deviceCode}/heartbeat`。且只能订阅自己的设备对应的deviceCode。

### 3.2 消息体结构

```json
{
  "msgId": "msg_1753001234567",
  "cmd": "login",
  "timestamp": 1753001234567,
  "deviceCode": "ABC12345",
  "sign": "xK8F3m...Base64...",
  "data": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `msgId` | String | 是 | 唯一ID，建议 `msg_{13位毫秒时间戳}` |
| `cmd` | String | 是 | 命令类型 |
| `timestamp` | Long | 是 | 13位毫秒时间戳 |
| `deviceCode` | String | 是 | 设备编码 |
| `sign` | String | 是 | HMAC-SHA256签名(Base64) |
| `data` | Object | 视命令 | 业务数据，无数据时传 `{}` |

### 3.3 签名计算

**算法**: `Base64(HMAC-SHA256(signingKey, input))`

**签名输入** (`:` 拼接):
```
msgId + ":" + cmd + ":" + timestamp + ":" + canonicalData
```

`canonicalData`: `data` 非空时 `JSON.stringify(data)`(保留null)，为空时 `"{}"`。

> 签名验证通过服务器启动参数配置`mqtt.signature.enabled=true` 控制开关（当前默认开启）。

### 3.4 上行指令（设备 → 服务端）

所有上行消息发到 `card/{deviceCode}/up`，响应在 `card/{deviceCode}/down/response`。

#### 3.4.1 `login` — 设备登录

| HTTP替代 | `POST /api/v1/device/login` |

**data**: `{ "version": "1.0.0", "ip": "192.168.1.100" }`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| version | String | 否 | 终端版本号 |
| ip | String | 否 | 终端IP |

**响应** (cmd=`loginResp`): `{ "code": 0, "msg": "success", "token": "uuid" }`

> **登录成功后**：终端需立即启动 60s 定时心跳（MQTT 模式走 `card/{deviceCode}/heartbeat` Topic QoS 0，HTTP 模式走 `POST /api/v1/device/heartbeat`）。心跳间隔固定 60 秒，不可调整。

---

#### 3.4.2 `heartbeat` — 心跳

| Topic | `card/{deviceCode}/heartbeat` (QoS 0) |
| HTTP替代 | `POST /api/v1/device/heartbeat` |
| 建议间隔 | 60秒（login 成功后自动开始，MQTT 模式走 `card/{deviceCode}/heartbeat` Topic） |

**data**: `{}`

**响应** (cmd=`heartbeatResp`): `{ "code": 0, "msg": "success", "serverTime": 1753001234567 }`

---

#### 3.4.3 `cardEvent` — 取还卡事件

| HTTP替代 | `POST /api/v1/card/event` |

**data**: `{ "cardNo": "CARD001", "eventType": "TAKE", "slotId": 1, "authType": "FACE", "employeeId": 1, "timestamp": 1753001234567 }`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| cardNo | String | 是 | 卡号 |
| eventType | String | 是 | `TAKE`(取卡) / `RETURN`(还卡) / `FORCE_RETURN`(系统强制还卡) |
| slotId | Integer | 是 | 卡槽编号 |
| authType | String | 是 | `FACE`(人脸) / `FINGER`(指纹) / `CARD`(刷卡) / `FINGERPRINT`(指静脉) / `ADMIN`(管理后台) / `SYSTEM`(系统自动) |
| employeeId | Long | 取卡时必填 | **V4.2新增**，关联员工ID（`authType=FACE`/`FINGER`/`CARD` 时从认证结果获取） |

> **重要（V4.2）**：旧版 HTTP 取还卡接口 `/api/takeCard`、`/api/takeSuccess`、`/api/saveCard` 及 `/api/v1/card/take`、`/api/v1/card/return` 已废弃。卡事件统一通过 MQTT `cardEvent` 通道上报。HTTP `/api/v1/card/event` 仅作为 MQTT 降级通道保留。
| authType | String | 是 | `CARD` / `FACE` / `FINGERPRINT` |
| timestamp | Long | 是 | 事件时间戳 |

**响应** (cmd=`cardEventResp`): `{ "code": 0, "msg": "success" }`

---

#### 3.4.4 `statusReport` — 卡槽状态上报

| HTTP替代 | `POST /api/v1/device/status` |

**data**: `{ "slots": [{ "slotId": 1, "status": "OCCUPIED", "cardNo": "CARD001", "voltage": 12.5, "current": 0.5, "chargeStatus": "FULL", "faultCode": 0 }] }`

| 字段(slot元素) | 类型 | 必填 | 说明 |
|------|------|------|------|
| slotId | Integer | 是 | 卡槽编号 |
| status | String | 是 | `EMPTY`/`OCCUPIED`/`CHARGING`/`FULL`/`FAULT` |
| cardNo | String | 否 | 卡号(占用时必填) |
| voltage | BigDecimal | 否 | 电压(V) |
| current | BigDecimal | 否 | 电流(A) |
| chargeStatus | String | 否 | `CHARGING`/`FULL`/`DISCHARGING`/`IDLE` |
| faultCode | Integer | 否 | 故障码，0=正常 |

**响应** (cmd=`statusReportResp`): `{ "code": 0, "msg": "success" }`

---

#### 3.4.5 `logReport` — 日志上报

| HTTP替代 | `POST /api/v1/log/report` |
| 前置条件 | 需开启日志上传(默认关闭) |

**data**: `{ "level": "INFO", "tag": "TEST", "content": "测试日志", "timestamp": 1753001234567 }`

**响应** (cmd=`logReportResp`): `{ "code": 0, "msg": "success" }`

> 服务端仅存储已开启上传的设备的日志，关闭后上报的日志将被丢弃。

---

#### 3.4.6 `hardwareFault` — 硬件故障上报

| HTTP替代 | `POST /api/v1/fault/report` |

**data**: `{ "slotId": 1, "faultCode": 100, "faultMsg": "电机驱动故障", "timestamp": 1753001234567 }`

**响应** (cmd=`hardwareFaultResp`): `{ "code": 0, "msg": "success" }` (自动生成工单)

---

#### 3.4.7 `statisticsReport` — 统计数据上报

| HTTP替代 | `POST /api/v1/statistics/report` |

**data**: `{ "statDate": "2026-07-20", "takeCount": 10, "returnCount": 8, "occupiedCount": 5, "emptyCount": 90, "faultCount": 1, "chargingCount": 2, "fullCount": 2 }`

**响应** (cmd=`statisticsReportResp`): `{ "code": 0, "msg": "success" }`

---

#### 3.4.8 `authStatusChange` — 授权状态变更

| HTTP替代 | `POST /api/v1/device/auth/change` |

**data**: `{ "newStatus": "AUTHORIZED" }`

---

#### 3.4.9 `selfCheckReport` — 设备自检结果

| HTTP替代 | `POST /api/v1/device/selfcheck` |

**data**: 无固定格式，服务端仅记录日志。

---

#### 3.4.10 `upgradeStatus` — 固件升级状态

| HTTP替代 | `POST /api/v1/upgrade/status` |

**data**: `{ "firmwareVersion": "1.2.3", "status": "DOWNLOADING", "progress": 50, "errorMsg": null }`

| 字段 | 类型 | 说明 |
|------|------|------|
| firmwareVersion | String | 当前固件版本 |
| status | String | 升级状态 |
| progress | Integer | 进度(0-100) |
| errorMsg | String | 错误信息 |

---

#### 3.4.11 `batchOperationResult` — 批量操作结果

| HTTP替代 | `POST /api/v1/device/batch-result` |

### 3.5 下行指令（服务端 → 设备）

由后台管理员触发，通过 `card/{deviceCode}/down` 发布。设备需监听并根据 `cmd` 处理，处理完成后在 `card/{deviceCode}/up` 发布对应的响应。

> 下行消息**不含** `sign` 字段。

#### 下行消息体格式

```json
{
  "msgId": "uuid-server-generated",
  "cmd": "remoteOpen",
  "timestamp": 1753001234567,
  "data": { "slotId": 1, "authType": "ADMIN", "operatorId": "admin" }
}
```

#### 下行指令列表

| cmd | data参数 | 说明 | 终端响应cmd |
|-----|----------|------|-------------|
| `remoteOpen` | `{ slotId: Integer, authType: "ADMIN", operatorId: String }` | 远程开门（指定卡槽） | `remoteOpenResp` |
| `remoteEjectAll` | `{ operatorId: String, confirm: true }` | 远程弹卡（所有卡槽） | `remoteEjectAllResp` |
| `restartApp` | `{ delayMs: 3000 }` | 重启App | `restartAppResp` |
| `syncUser` | `{ deviceCode: String }` | 同步用户数据 | `syncUserResp` |
| `syncConfig` | `{}` | 同步设备配置 | `syncConfigResp` |
| `firmwareUpgrade` | `{ firmwareVersion: String, downloadUrl: String }` | 固件升级 | `firmwareUpgradeResp` |
| `cancelUpgrade` | `{}` | 取消升级 | `cancelUpgradeResp` |
| `deviceSelfCheck` | `{}` | 触发设备自检 | `deviceSelfCheckResp` |
| `enableLogUpload` | `{ enabled: true, operatorId: String }` | 开启日志上传 | 无(改变设备行为) |
| `disableLogUpload` | `{ enabled: false, operatorId: String }` | 关闭日志上传 | 无(改变设备行为) |

#### 终端响应格式

处理完成后在 `card/{deviceCode}/up` 发布对应响应：

```json
{
  "msgId": "msg_xxx",
  "cmd": "remoteOpenResp",
  "timestamp": 1753001234567,
  "deviceCode": "ABC12345",
  "sign": "...",
  "data": { "code": 0, "msg": "success" }
}
```

### 3.6 同步指令（双向）

终端发起同步请求 → 服务端分页返回数据。

#### 3.6.1 `syncEmployeeData` — 员工资料同步

| HTTP替代 | `POST /api/v1/employee/sync` |

**请求 data**: `{ "lastSyncTime": 0, "page": 1, "pageSize": 50 }`

| 字段 | 类型 | 说明 |
|------|------|------|
| lastSyncTime | Long | 上次同步时间戳，0=全量同步 |
| page | int | 页码(1-based) |
| pageSize | int | 每页条数，默认50，最大100 |

**响应** (cmd=`syncEmployeeDataResp`):
```json
{
  "code": 0, "msg": "success",
  "syncVersion": 1753001234567,
  "employees": [{
    "employeeId": 1,
    "employeeCode": "EMP001",
    "employeeName": "张三",
    "cardNo": "CARD001",
    "deptId": 100,
    "phone": "13800138000",
    "email": "zhangsan@example.com",
    "department": "技术部",
    "position": "工程师",
    "status": "0",
    "faceRegistered": "1",
    "fingerRegistered": "0"
  }],
  "deletedEmployeeIds": [],
  "total": 500,
  "page": 1,
  "pageSize": 50,
  "hasMore": true
}
```

**EmployeeSyncItem 字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| employeeId | Long | 员工ID |
| employeeCode | String | 员工编码 |
| employeeName | String | 姓名 |
| cardNo | String | 工卡号 |
| deptId | Long | 部门ID |
| phone | String | 手机号 |
| email | String | 邮箱 |
| department | String | 部门名称 |
| position | String | 职位 |
| status | String | 0=正常 1=停用 |
| faceRegistered | String | 0=未注册 1=已注册 |
| fingerRegistered | String | 0=未注册 1=已注册 |

---

#### 3.6.2 `syncFaceData` — 人脸特征同步

| HTTP替代 | `POST /api/v1/employee/face/sync` |

**请求 data**: `{ "lastSyncTime": 0, "page": 1, "pageSize": 10, "includeFlags": 7 }`

| 字段 | 类型 | 说明 |
|------|------|------|
| lastSyncTime | Long | 上次同步时间戳，0=全量 |
| page | int | 页码(1-based) |
| pageSize | int | 每页条数，默认10，最大30 |
| includeFlags | int | 位掩码：1=特征值, 2=图片URL, 4=图片Base64 |

`includeFlags` 组合示例：`7`=三者全要，`2`=只要图片URL。

**响应** (cmd=`syncFaceDataResp`):
```json
{
  "code": 0, "msg": "success",
  "syncVersion": "1753001234567",
  "faceFeatures": [{
    "faceId": 1,
    "employeeId": 1,
    "faceFeature": "base64-encoded-feature",
    "faceImage": "/profile/face/2026/01/01/face.jpg",
    "faceImageBase64": "base64-encoded-image",
    "featureVersion": "1.0",
    "status": "0"
  }],
  "total": 200,
  "page": 1,
  "pageSize": 10,
  "hasMore": true
}
```

**FaceFeatureSyncItem 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| faceId | Long | 人脸ID |
| employeeId | Long | 员工ID |
| faceFeature | String | 人脸特征值(includeFlags&1时返回) |
| faceImage | String | 图片URL路径(includeFlags&2时返回) |
| faceImageBase64 | String | 图片Base64(includeFlags&4时返回) |
| featureVersion | String | 特征版本 |
| status | String | 0=正常 1=停用 |

---

#### 3.6.3 `syncFingerData` — 指纹特征同步

| HTTP替代 | `POST /api/v1/employee/finger/sync` |

**请求 data**: `{ "lastSyncTime": 0, "page": 1, "pageSize": 20 }`

| 字段 | 类型 | 说明 |
|------|------|------|
| lastSyncTime | Long | 上次同步时间戳，0=全量 |
| page | int | 页码(1-based) |
| pageSize | int | 每页条数，默认20，最大50 |

**响应** (cmd=`syncFingerDataResp`):
```json
{
  "code": 0, "msg": "success",
  "syncVersion": "1753001234567",
  "fingerFeatures": [{
    "fingerId": 1,
    "employeeId": 1,
    "fingerFeature": "base64-feature",
    "fingerIndex": 1,
    "featureVersion": "1.0",
    "status": "0"
  }],
  "total": 200,
  "page": 1,
  "pageSize": 20,
  "hasMore": true
}
```

**FingerFeatureSyncItem 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| fingerId | Long | 指纹ID |
| employeeId | Long | 员工ID |
| fingerFeature | String | 指纹特征值 |
| fingerIndex | Integer | 手指序号 |
| featureVersion | String | 特征版本 |
| status | String | 0=正常 1=停用 |

---

## 4. HTTP API 接口

### 4.1 设备生命周期 (`/api/v1/device`)

#### 4.1.1 设备注册

```
POST /api/v1/device/register
认证: @Anonymous（无需token）
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| machineId | String | 是 | 机器唯一标识(AndroidID/序列号，防克隆核心) |
| mac | String | 否 | MAC地址 |
| model | String | 否 | 设备型号 |
| osType | String | 否 | 操作系统类型(ANDROID等) |
| osVersion | String | 否 | 操作系统版本号 |
| version | String | 否 | APP版本号(字符串形式) |
| versionCode | Integer | 否 | APP版本号(整数，用于版本比较) |
| channelId | String | 否 | 渠道号 |

**响应 `data`**:

| 字段 | 类型 | 说明 |
|------|------|------|
| deviceToken | String | 设备身份令牌(后续所有HTTP接口鉴权) |
| deviceCode | String | 服务端分配的设备编码 |
| isNew | Boolean | 是否为新注册设备 |

> `machineId` 已存在时返回已有 token + `isNew=false`。

**失败响应（特别关注）**:

| HTTP状态码 | msg | code | 说明 |
|------|------|------|------|
| 200 | `"machineId 不能为空"` | 500 | 缺少必填参数 |
| 200 | `"versionCode 不能为空"` | 500 | 缺少版本号 |
| **200** | **`"当前APP版本存在强制更新，请先升级到 X.X.X 版本后再进行注册"`** | **500** | **强制升级拦截** |

> ⚠️ **强制升级响应体示例**（客户端必须处理该场景，禁止继续注册流程）:
> ```json
> {
>   "code": 500,
>   "msg": "当前APP版本存在强制更新，请先升级到 2.1.0 版本后再进行注册",
>   "forceUpdate": true,
>   "versionInfo": {
>     "hasUpdate": true,        // 是否有新版本
>     "forceUpdate": true,      // 是否强制升级
>     "versionId": 123,         // 最新版本ID
>     "versionName": "2.1.0",   // 版本名称（展示给用户）
>     "versionCode": 210,       // 版本号（整数，用于版本比较）
>     "apkUrl": "https://...",  // APK下载地址（如有）
>     "apkSize": 15728640,      // APK文件大小（字节）
>     "apkMd5": "a1b2c3...",    // APK文件MD5（校验完整性）
>     "releaseNotes": "1.修复...\n2.新增..."  // 更新日志
>   }
> }
> ```
>
> 客户端判断逻辑：
> 1. 收到 `forceUpdate: true` → 停止后续注册流程（不重试）
> 2. 使用 `versionInfo.apkUrl`（如有）或自行下载最新 APK 完成升级
> 3. 升级完成后重新调用注册接口

---

#### 4.1.2 设备激活

```
POST /api/v1/device/activate
认证: Bearer deviceToken
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deviceId | String | 是 | 设备编码(需与注册设备时返回的设备编码deviceCode一致) |
| mac | String | 否 | MAC地址 |
| model | String | 否 | 设备型号 |
| version | String | 否 | APP版本号 |
| machineId | String | 是 | 机器唯一标识(需与注册时一致) |
| osType | String | 否 | 操作系统类型 |
| osVersion | String | 否 | 操作系统版本号 |

**路径A响应 `data`**（后台已激活）:

| 字段 | 类型 | 说明 |
|------|------|------|
| valid | boolean | true |
| mqttPassword | String | MQTT连接密码(16位随机，每次 activate 重新生成) |
| signingKey | String | 签名密钥(32位UUID无横线，每次 activate 重新生成) |
| clientId | String | MQTT客户端ID(UUID，首次激活生成，后续复用) |
| expireTime | Long | 建议客户端重新激活的时间戳(now + 365天，仅作参考，目前无实际使用) |
| deviceName | String | 设备名称 |
| deviceCode | String | 设备编码 |

**路径B响应 `data`**（终端激活码）:

| 字段 | 类型 | 说明 |
|------|------|------|
| registerCode | String | 注册码 |
| status | String | 设备状态 |
| expireTime | Long | 注册码过期时间戳 |

---

#### 4.1.3 注册码验证

```
POST /api/v1/device/verify
认证: Bearer deviceToken
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| registerCode | String | 是 | 阶段A返回的注册码 |
| activeKey | String | 是 | 管理员输入的激活码 |

**响应 `data`**:

| 字段 | 类型 | 说明 |
|------|------|------|
| valid | boolean | true=成功 |
| msg | String | 失败原因 |
| mqttPassword | String | MQTT连接密码(每次 verify 重新生成) |
| signingKey | String | 签名密钥(每次 verify 重新生成) |
| clientId | String | MQTT客户端ID(复用已有，首次激活时生成) |
| expireTime | Long | 建议客户端重新激活的时间戳(now + 24小时，路径B) |
| deviceName | String | 设备名称 |
| deviceCode | String | 设备编码 |

---

#### 4.1.4 获取设备配置

```
GET /api/v1/device/config
认证: Bearer deviceToken(仅校验激活)
```

**响应 `data`**:

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| baudRate | Integer | 57600 | 波特率 |
| groupSize | Integer | 16 | 分组大小 |
| totalSlots | Integer | 100 | 卡槽总数 |
| pollingInterval | Integer | 5000 | 轮询间隔(毫秒) |
| serverIp | String | 127.0.0.1 | 服务器IP |
| tcpPort | Integer | 9009 | TCP端口 |
| httpPort | Integer | 8082 | HTTP端口 |
| faceThreshold | BigDecimal | 0.8 | 人脸识别阈值 |
| fingerThreshold | BigDecimal | 0.8 | 指纹识别阈值 |
| communicationMode | String | MQTT | 通信方式(MQTT/HTTP) |

---

#### 4.1.5 设备登录（HTTP）

```
POST /api/v1/device/login
认证: Bearer deviceToken(需已激活)
```

**请求体**: `{ "version": "1.0.0" }`

**响应 `data`**: `{ "code": 0, "msg": "success", "token": "uuid-session", "serverTime": 1753001234567 }`

---

#### 4.1.6 HTTP 心跳

```
POST /api/v1/device/heartbeat
认证: Bearer deviceToken + 运行时校验
```

**请求体**: `{ "seq": 1 }` (可选)

**响应 `data`**: `{ "serverTime": 1753001234567 }`

---

#### 4.1.7 卡槽状态上报（HTTP）

```
POST /api/v1/device/status
认证: Bearer deviceToken + 运行时校验
```

**请求体**: 与MQTT `statusReport` data格式一致，详见 [3.4.4](#344-statusreport--卡槽状态上报)。

---

#### 4.1.8 授权状态变更（HTTP）

```
POST /api/v1/device/auth/change
认证: Bearer deviceToken + 运行时校验
```

**请求体**: `{ "newStatus": "AUTHORIZED" }`

---

#### 4.1.9 设备自检结果（HTTP）

```
POST /api/v1/device/selfcheck
认证: Bearer deviceToken + 运行时校验
```

---

#### 4.1.10 批量操作结果（HTTP）

```
POST /api/v1/device/batch-result
认证: Bearer deviceToken + 运行时校验
```

---

#### 4.1.11 查询授权状态

```
GET /api/v1/device/auth/status
认证: Bearer deviceToken + 运行时校验
```

**响应 `data`**:

| 字段 | 类型 | 说明 |
|------|------|------|
| authorized | boolean | 是否已授权 |
| authorizedUntil | Long | 授权截止时间戳 |
| daysRemaining | Long | 剩余天数 |
| features | String[] | 可用功能列表 |

---

### 4.2 员工管理 (`/api/v1/employee`)

#### 4.2.1 员工资料同步

```
POST /api/v1/employee/sync
认证: Bearer deviceToken(仅校验激活)
```

请求/响应格式与MQTT `syncEmployeeData` 完全一致，详见 [3.6.1](#361-syncemployeedata--员工资料同步)。

#### 4.2.2 人脸特征同步

```
POST /api/v1/employee/face/sync
认证: Bearer deviceToken(仅校验激活)
```

请求/响应格式与MQTT `syncFaceData` 完全一致，详见 [3.6.2](#362-syncfacedata--人脸特征同步)。

#### 4.2.3 指纹特征同步

```
POST /api/v1/employee/finger/sync
认证: Bearer deviceToken(仅校验激活)
```

请求/响应格式与MQTT `syncFingerData` 完全一致，详见 [3.6.3](#363-syncfingerdata--指纹特征同步)。

#### 4.2.4 新增/更新员工

```
POST /api/v1/employee
认证: Bearer deviceToken + 运行时校验
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | String | 否 | `add`(新增) / `update`(更新)，默认add |
| employeeId | Long | 更新必填 | 员工ID |
| employeeCode | String | 新增必填 | 员工编码 |
| employeeName | String | 新增必填 | 姓名 |
| cardNo | String | 否 | 工卡号 |
| deptId | Long | 否 | 部门ID |
| phone | String | 否 | 手机号 |
| email | String | 否 | 邮箱 |
| department | String | 否 | 部门名称 |
| position | String | 否 | 职位 |
| status | String | 否 | 0=正常 1=停用，默认0 |

**响应 `data`**: `{ "employeeId": 1, "action": "add" }`

#### 4.2.5 上传人脸特征

```
POST /api/v1/employee/face
认证: Bearer deviceToken + 运行时校验
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| employeeId | Long | 是 | 员工ID |
| faceFeature | String | 是 | 人脸特征值 |
| faceImagePath | String | 否 | 人脸图片路径 |
| deviceId | Long | 否 | 设备ID |

#### 4.2.6 查询已注册人脸员工

```
GET /api/v1/employee/face/registered
认证: Bearer deviceToken(仅校验激活)
```

**响应 `data`**: `[1, 2, 3, ...]` — 已注册人脸的员工ID列表

---

### 4.3 卡片事件 (`/api/v1/card`)

#### 4.3.1 通用卡片事件

```
POST /api/v1/card/event
认证: Bearer deviceToken + 运行时校验
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| cardNo | String | 是 | 卡号 |
| eventType | String | 是 | `TAKE` / `RETURN` |
| slotId | Integer | 是 | 卡槽编号 |
| timestamp | Long | 是 | 事件时间戳 |
| authType | String | 是 | `CARD` / `FACE` / `FINGERPRINT` |

#### 4.3.2 取卡事件

```
POST /api/v1/card/take
认证: Bearer deviceToken + 运行时校验
```

**请求体**: `{ "cardNo": "CARD001", "slotId": 1, "authType": "FACE" }`

#### 4.3.3 还卡事件

```
POST /api/v1/card/return
认证: Bearer deviceToken + 运行时校验
```

**请求体**: `{ "cardNo": "CARD001", "slotId": 1, "authType": "FACE" }`

---

### 4.4 日志上报 (`/api/v1/log`)

```
POST /api/v1/log/report
认证: Bearer deviceToken + 运行时校验
前置条件: 设备需开启日志上传(默认关闭)
```

**请求体**: `{ "level": "INFO", "tag": "TEST", "content": "日志内容", "timestamp": 1753001234567 }`

> 未开启日志上传时返回 `"日志上传未开启"`。

---

### 4.5 统计上报 (`/api/v1/statistics`)

```
POST /api/v1/statistics/report
认证: Bearer deviceToken + 运行时校验
```

**请求体**: 与MQTT `statisticsReport` data一致，详见 [3.4.7](#347-statisticsreport--统计数据上报)。

---

### 4.6 故障上报 (`/api/v1/fault`)

```
POST /api/v1/fault/report
认证: Bearer deviceToken + 运行时校验
```

**请求体**: `{ "deviceId": "ABC12345", "slotId": 1, "faultCode": 100, "faultMsg": "故障描述", "timestamp": 1753001234567 }`

服务端自动生成 `PENDING` 状态工单。

---

### 4.7 固件升级状态 (`/api/v1/upgrade`)

```
POST /api/v1/upgrade/status
认证: Bearer deviceToken + 运行时校验
```

**请求体**: 与MQTT `upgradeStatus` data一致。上报固件升级过程中状态变化。

---

### 4.8 文件上传 / 下载 (`/api/v1`)

#### 4.8.1 固件文件下载

```
GET /api/v1/firmware/{firmwareId}/download
认证: Bearer deviceToken
响应: application/octet-stream
响应头: X-Firmware-Version, Content-Length, Content-Disposition
```

支持 HTTP Range 断点续传。

#### 4.8.2 人脸图片上传

```
POST /api/v1/face/upload
认证: Bearer deviceToken
Content-Type: multipart/form-data
```

**表单参数**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | String | 是 | 用户ID |
| file | File | 是 | 图片文件 |
| faceFeature | String | 否 | 人脸特征值 |

**响应 `data`**:

| 字段 | 类型 | 说明 |
|------|------|------|
| uploadId | String | 上传ID |
| faceUrl | String | 图片存储路径 |
| faceFeature | String | 人脸特征值 |

#### 4.8.3 指纹数据上传

```
POST /api/v1/fingerprint/upload
认证: Bearer deviceToken
Content-Type: application/json
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deviceId | String | 否 | 设备编码 |
| userId | String | 是 | 用户ID |
| fingerFeature | String | 是 | 指纹特征值 |
| fingerIndex | Integer | 是 | 手指序号 |

**响应 `data`**: `{ "uploadId": "abc123" }`

#### 4.8.4 批量日志补传

```
POST /api/v1/logs/batch
认证: Bearer deviceToken
```

**请求体**: `{ "deviceId": "ABC12345", "logs": [{ "level": "INFO", "tag": "TAG", "content": "msg", "timestamp": 1753001234567 }] }`

**响应 `data`**: `{ "receivedCount": 10, "failedCount": 0 }`

---

### 4.9 遗留接口 (`/api`)

兼容旧版客户端，不推荐新客户端使用：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/getToken` | (已注释) 获取token |
| POST | `/api/takeCard` | 远程取卡(REMOTE) |
| POST | `/api/takeSuccess` | 取卡成功(FACE) |
| POST | `/api/saveCard` | 还卡(FACE) |

---

### 4.10 APP版本检测 (`/api/v1/app-version`)

```
POST /api/v1/app-version/check
认证: @Anonymous（无需token）
```

终端启动时调用，检测是否有新版本可供升级。

**请求体**:

| 字段 | 类型 | 必填  | 说明 |
|------|------|-----|------|
| currentVersionCode | Integer | 是   | 当前APP版本号（整数） |
| channelId | String | 是   | 渠道号，如 `official` |
| deviceCode | String | 否   | 设备编码（用于灰度白名单判断） |

**响应 `data`**: 无更新时返回 `null`，有更新时返回：

| 字段 | 类型 | 说明 |
|------|------|------|
| hasUpdate | boolean | 是否有更新 |
| forceUpdate | boolean | 是否强制升级 |
| versionId | Long | 最新版本ID |
| versionName | String | 版本名称，如 `1.2.0` |
| versionCode | Integer | 版本号（整数） |
| apkUrl | String | APK 下载地址 |
| apkSize | Long | APK 文件大小（字节） |
| apkMd5 | String | APK 文件 MD5 校验值 |
| releaseNotes | String | 更新日志 |

**调用时机**: APP 启动时调用，无更新则无需后续下载流程。

**灰度检测逻辑**: 如果最新版本开启了灰度发布，且 `deviceCode` 不在该版本的灰度白名单中，则不会返回更新（`data` 为 `null`）。

**请求示例**:
```json
{
  "currentVersionCode": 1,
  "channelId": "official",
  "deviceCode": "DEV-ABC123"
}
```

**响应示例（有更新）**:
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "hasUpdate": true,
    "forceUpdate": false,
    "versionId": 5,
    "versionName": "1.2.0",
    "versionCode": 3,
    "apkUrl": "https://example.com/apk/app-v1.2.0.apk",
    "apkSize": 15728640,
    "apkMd5": "a1b2c3d4e5f6...",
    "releaseNotes": "1. 修复已知问题\\n2. 优化性能"
  }
}
```

**响应示例（无更新）**:
```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

---

## 5. MQTT 与 HTTP 指令对照表

| 功能 | MQTT cmd / Topic | HTTP 端点 |
|------|-----------------|-----------|
| 设备注册 | — | `POST /api/v1/device/register` |
| APP版本检测 | — | `POST /api/v1/app-version/check` |
| 设备激活 | — | `POST /api/v1/device/activate` |
| 注册码验证 | — | `POST /api/v1/device/verify` |
| 获取配置 | — | `GET /api/v1/device/config` |
| 设备登录 | `login` (up) | `POST /api/v1/device/login` |
| 心跳 | `heartbeat` (heartbeat) | `POST /api/v1/device/heartbeat` |
| 取还卡事件 | `cardEvent` (up) | `POST /api/v1/card/event` (仅降级，主通道为 MQTT) |
| 卡槽状态 | `statusReport` (up) | `POST /api/v1/device/status` |
| 日志上报 | `logReport` (up) | `POST /api/v1/log/report` |
| 硬件故障 | `hardwareFault` (up) | `POST /api/v1/fault/report` |
| 统计上报 | `statisticsReport` (up) | `POST /api/v1/statistics/report` |
| 授权变更 | `authStatusChange` (up) | `POST /api/v1/device/auth/change` |
| 设备自检 | `selfCheckReport` (up) | `POST /api/v1/device/selfcheck` |
| 升级状态 | `upgradeStatus` (up) | `POST /api/v1/upgrade/status` |
| 批量结果 | `batchOperationResult` (up) | `POST /api/v1/device/batch-result` |
| 员工同步 | `syncEmployeeData` (up) | `POST /api/v1/employee/sync` |
| 人脸同步 | `syncFaceData` (up) | `POST /api/v1/employee/face/sync` |
| 指纹同步 | `syncFingerData` (up) | `POST /api/v1/employee/finger/sync` |
| 远程开门 | `remoteOpen` (down) | — |
| 远程弹卡 | `remoteEjectAll` (down) | — |
| 重启App | `restartApp` (down) | — |
| 同步用户 | `syncUser` (down) | — |
| 同步配置 | `syncConfig` (down) | — |
| 固件升级 | `firmwareUpgrade` (down) | — |
| 取消升级 | `cancelUpgrade` (down) | — |
| 自检指令 | `deviceSelfCheck` (down) | — |
| 开启日志上传 | `enableLogUpload` (down) | — |
| 关闭日志上传 | `disableLogUpload` (down) | — |
| 固件下载 | — | `GET /api/v1/firmware/{id}/download` |
| 人脸上传 | — | `POST /api/v1/face/upload` |
| 指纹上传 | — | `POST /api/v1/fingerprint/upload` |
| 批量日志 | — | `POST /api/v1/logs/batch` |
| 新增/更新员工 | — | `POST /api/v1/employee` |
| 上载人脸特征 | — | `POST /api/v1/employee/face` |
| 已注册人脸查询 | — | `GET /api/v1/employee/face/registered` |
| 查询授权状态 | — | `GET /api/v1/device/auth/status` |

---

## 6. 通用错误码

| code | 说明 |
|------|------|
| 0 | 成功 |
| 500 | 服务端异常 |
| 1005 | 设备不存在 |

### 常见错误消息

| 场景 | msg 示例 |
|------|----------|
| Token缺失/无效 | `"设备认证失败，token无效或已过期"` |
| 设备未激活 | `"设备未激活，请先完成激活"` |
| machineId冲突 | `"该设备指纹已被其他设备绑定"` |
| deviceCode不匹配 | `"deviceCode 与注册信息不匹配"` |
| 注册码无效 | `"注册码无效或已过期"` |
| 激活码无效 | `"激活码错误或已用完"` |
| 日志上传未开启 | `"日志上传未开启"` |
| 设备不在线 | `"设备不在线，无法执行远程操作"` |

### 认证校验
运行时校验：会限制没有MQTT登陆中，且设备已激活状态。才可以访问。
需已激活：仅限制需要激活设备成功才可以访问。
需要token：activate/verify接口必须要存在token才可以访问。token在注册设备的请求中会产生
匿名：不限制。