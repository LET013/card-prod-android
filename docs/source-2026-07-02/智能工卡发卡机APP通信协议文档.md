# 智能工卡发卡机APP通信协议文档

## 文档版本信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 智能工卡发卡机APP通信协议文档 |
| 版本号 | V1.0 |
| 创建日期 | 2026-07-02 |
| 适用设备 | Android竖屏平板 |

---

## 一、概述

本协议文档定义了智能工卡发卡机系统中三个通信层面的协议规范：

1. **串口通信协议**：APP与硬件单板之间的通信，用于控制卡位、读取状态
2. **HTTP接口协议**：APP与后台服务器之间的短连接通信，用于业务上报、设备激活等
3. **Socket长连接协议**：APP与后台服务器之间的长连接通信，用于实时指令下发和状态上报

---

## 二、串口通信协议（APP ↔ 硬件单板）

### 2.1 协议基础参数

| 参数 | 规格 |
|------|------|
| 波特率 | 57600 |
| 数据位 | 8位 |
| 停止位 | 1位 |
| 校验位 | 无 |
| 通信模式 | 安卓屏为主机，充电单元板为从机 |
| 通信超时 | 100ms |

### 2.2 协议帧格式

```
┌──────────┬──────────┬──────────┬──────────┬──────────┬────────────┬──────────┐
│ 起始标记 │ 数据长度 │ 主机地址 │ 从机地址 │ 功能码   │ 数据域     │ CRC校验  │
│ DDCC     │ 2字节    │ F0       │ 1字节    │ 1字节    │ N字节      │ 2字节    │
└──────────┴──────────┴──────────┴──────────┴──────────┴────────────┴──────────┘
```

**字段说明：**
- **起始标记**：固定为 `DDCC`（16进制）
- **数据长度**：从该字节之后开始到CRC16之前的数据字节数（2字节）
- **主机地址**：固定为 `F0`
- **从机地址**：单板地址（0为广播模式，所有分机均解析）
- **功能码**：命令功能标识（1字节）
- **数据域**：业务数据
- **CRC校验**：高字节在前，低字节在后，计算范围从数据头到CRC之前

### 2.3 查询数据（功能码 0x01）

#### 2.3.1 查询命令（APP → 单板）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 07 |
| 主机地址 | F0 |
| 从机地址 | **（目标单板地址）** |
| 功能码 | 01 |
| 数据域 | 5A A5 5A A5 01（固定位 + 系统时间请求） |
| CRC校验 | **（计算值）** |

#### 2.3.2 查询返回（单板 → APP）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 1C |
| 主机地址 | F0 |
| 从机地址 | **（单板地址）** |
| 功能码 | 01 |
| 数据域 | 22字节（见下方字段说明） |
| CRC校验 | **（计算值）** |

**返回数据域字段说明：**

| 字节偏移 | 字段 | 说明 |
|----------|------|------|
| 0 | 工作状态 | 0无效、1待机、2充电、3充电结束、4故障、5授权到期、6通信超时 |
| 1 | 在位状态 | 1开门状态、2关门状态 |
| 2 | 卡状态 | 1有卡、2读卡错误（卡非法） |
| 3 | 卡状态变更 | 有卡↔无卡时为1，上位机轮询3次后清0 |
| 4-18 | 卡号 | 15字节卡号（ASCII） |
| 19 | 故障码 | 按位定义（见下方） |
| 20 | 电压 | 50mV/bit，换算公式：电压(V) = 值 × 0.05 |
| 21 | 电流 | 10mA/bit，换算公式：电流(A) = 值 × 0.01 |

**故障码按位定义：**

| 位 | 故障类型 |
|----|----------|
| bit0 | 插卡错误 |
| bit1 | 过流 |
| bit2 | 门控故障 |
| bit3 | 过压 |
| bit4 | 欠压 |

### 2.4 开门控制（功能码 0x51）

#### 2.4.1 开门命令（APP → 单板）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 07 |
| 主机地址 | F0 |
| 从机地址 | **（目标单板地址）** |
| 功能码 | 51 |
| 数据域 | 5A A5 5A A5 **（开门控制标识）** |
| CRC校验 | **（计算值）** |

**开门控制标识：**
- `01`：发卡开门
- `02`：管理员开门
- 其他值：无效

#### 2.4.2 开门返回（单板 → APP）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 07 |
| 主机地址 | F0 |
| 从机地址 | **（单板地址）** |
| 功能码 | 51 |
| 数据域 | 5A A5 5A A5 **（结果码）** |
| CRC校验 | **（计算值）** |

**结果码：**
- `11`：命令正常执行
- `12`：命令未执行

### 2.5 LED亮度控制（功能码 0x52）

#### 2.5.1 LED控制命令（APP → 单板）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 07 |
| 主机地址 | F0 |
| 从机地址 | **（目标单板地址）** |
| 功能码 | 52 |
| 数据域 | 5A A5 5A A5 **（占空比）** |
| CRC校验 | **（计算值）** |

**占空比范围：** 30~100（十进制）

#### 2.5.2 LED控制返回（单板 → APP）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 07 |
| 主机地址 | F0 |
| 从机地址 | **（单板地址）** |
| 功能码 | 52 |
| 数据域 | 5A A5 5A A5 **（结果码）** |
| CRC校验 | **（计算值）** |

**结果码：**
- `11`：命令正常执行
- `12`：命令未执行

### 2.6 读取版本号（功能码 0x53）

#### 2.6.1 读取版本命令（APP → 单板）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 08 |
| 主机地址 | F0 |
| 从机地址 | **（目标单板地址）** |
| 功能码 | 53 |
| 数据域 | 5A A5 5A A5 01 |
| CRC校验 | **（计算值）** |

#### 2.6.2 版本号返回（单板 → APP）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 0B |
| 主机地址 | F0 |
| 从机地址 | **（单板地址）** |
| 功能码 | 53 |
| 数据域 | 5A A5 5A A5 + 硬件主版本号 + 硬件从版本号 + 软件主版本号 + 软件次版本号 |
| CRC校验 | **（计算值）** |

**版本号范围：**
- 主版本号：1~100
- 从版本号：0~9

### 2.7 单板升级使能（功能码 0x80）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | 00 0A |
| 主机地址 | F0 |
| 从机地址 | 0（广播）或 **（单板地址）** |
| 功能码 | 80 |
| 数据域 | 5A 5A A5 A5 A5 A5 5A 5A |
| CRC校验 | **（计算值）** |

**说明：**
- 从机地址为0时：广播模式，所有分机均解析，无应答
- 从机地址非0时：单板地址模式

### 2.8 单板升级程序传输（功能码 0x81）

| 字段 | 内容 |
|------|------|
| 起始标记 | DDCC |
| 数据长度 | **（实际长度）** |
| 主机地址 | F0 |
| 从机地址 | 0（广播模式） |
| 功能码 | 81 |
| 数据域 | 数据长度标识 + 帧序号 + 帧序号反码 + 固件数据 |
| CRC校验 | **（计算值）** |

**数据长度标识：**
- `01`：128字节数据
- `02`：1024字节数据

### 2.9 串口命令汇总表

| 功能码 | 命令名称 | 方向 | 说明 |
|--------|----------|------|------|
| 0x01 | 查询数据 | APP→单板 | 查询卡位状态信息 |
| 0x01 | 返回查询结果 | 单板→APP | 返回工作状态、卡号、电压电流等 |
| 0x51 | 开门控制 | APP→单板 | 发卡开门/管理员开门 |
| 0x51 | 返回开门结果 | 单板→APP | 执行成功/失败 |
| 0x52 | LED亮度控制 | APP→单板 | 设置占空比30~100 |
| 0x52 | 返回控制结果 | 单板→APP | 执行成功/失败 |
| 0x53 | 读取版本号 | APP→单板 | 读取硬件/软件版本 |
| 0x53 | 返回版本号 | 单板→APP | 返回版本信息 |
| 0x80 | 升级使能 | APP→单板 | 使能单板升级模式 |
| 0x81 | 升级程序传输 | APP→单板 | 传输固件数据 |

---

## 三、HTTP接口协议（APP ↔ 后台服务器）

### 3.1 基础规范

| 参数 | 规格 |
|------|------|
| 字符集 | UTF-8 |
| 数据格式 | JSON |
| 时间格式 | 毫秒级Unix时间戳（13位数字） |
| 请求头 | `Content-Type: application/json` |

### 3.2 统一响应格式

```json
{
  "code": 0,
  "msg": "success",
  "data": {}
}
```

**字段说明：**
- `code`：状态码（0表示成功，其他为错误码）
- `msg`：提示信息
- `data`：业务数据（成功时返回）

### 3.3 设备激活接口

#### 3.3.1 获取注册码

**请求：**
```
POST /api/v1/device/activate
```

**请求体：**
```json
{
  "deviceId": "DEV001",
  "mac": "AA:BB:CC:DD:EE:FF",
  "model": "FJ-100",
  "version": "1.0.0"
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "registerCode": "ABC123XYZ",
    "status": "PENDING_APPROVAL",
    "expireTime": 1735689600000
  }
}
```

**字段说明：**
- `status`：`ACTIVATED`（已激活）或 `PENDING_APPROVAL`（待审核）
- `expireTime`：注册码过期时间戳

#### 3.3.2 激活验证

**请求：**
```
POST /api/v1/device/verify
```

**请求体：**
```json
{
  "deviceId": "DEV001",
  "registerCode": "ABC123XYZ"
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "valid": true,
    "token": "session_token_string",
    "expireTime": 1735689600000,
    "deviceName": "一楼大厅发卡机"
  }
}
```

#### 3.3.3 设备授权状态查询

**请求：**
```
GET /api/v1/device/auth/status?deviceId=DEV001
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "isAuthorized": true,
    "authorizedUntil": 1735689600000,
    "daysRemaining": 365,
    "features": ["FACE", "FINGER", "REMOTE_OPEN"]
  }
}
```

**字段说明：**
- `features`：可用功能列表
  - `FACE`：人脸识别
  - `FINGER`：指纹识别
  - `REMOTE_OPEN`：远程开锁

#### 3.3.4 在线授权续期

**请求：**
```
POST /api/v1/device/auth/renew
```

**请求体：**
```json
{
  "deviceId": "DEV001",
  "token": "session_token_string"
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "newExpireTime": 1767225600000,
    "daysAdded": 365
  }
}
```

### 3.4 取卡/还卡上报

**请求：**
```
POST /api/v1/card/event
```

**请求体：**
```json
{
  "deviceId": "DEV001",
  "cardNo": "123456",
  "eventType": "TAKE",
  "slotId": 1,
  "timestamp": 1700000000000,
  "authType": "FACE"
}
```

**字段说明：**
- `eventType`：`TAKE`（取卡）或 `RETURN`（还卡）
- `authType`：`FACE`（人脸识别）、`FINGER`（指纹识别）、`ADMIN`（管理员操作）、`REMOTE`（远程指令）

**响应：**
```json
{
  "code": 0,
  "msg": "success"
}
```

### 3.5 设备配置拉取

**请求：**
```
GET /api/v1/device/config?deviceId=DEV001
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "baudRate": 57600,
    "groupSize": 10,
    "totalSlots": 100,
    "pollingInterval": 1000,
    "serverIp": "192.168.1.100",
    "tcpPort": 9009,
    "httpPort": 8081,
    "faceThreshold": 0.9,
    "fingerThreshold": 0.8
  }
}
```

### 3.6 Token获取（兼容旧接口）

**请求：**
```
POST /api/getToken
```

**请求体：**
```json
{
  "deviceId": "DEV001",
  "registerCode": "ABC123"
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "session_token_string"
  }
}
```

### 3.7 HTTP接口汇总表

| 接口路径 | 方法 | 说明 |
|----------|------|------|
| `/api/v1/device/activate` | POST | 获取注册码 |
| `/api/v1/device/verify` | POST | 激活验证 |
| `/api/v1/device/auth/status` | GET | 查询授权状态 |
| `/api/v1/device/auth/renew` | POST | 在线授权续期 |
| `/api/v1/card/event` | POST | 取卡/还卡上报 |
| `/api/v1/device/config` | GET | 设备配置拉取 |
| `/api/getToken` | POST | 获取Token（兼容旧接口） |
| `/api/takeCard` | POST | 取卡请求（兼容旧接口） |
| `/api/takeSuccess` | POST | 取卡成功上报（兼容旧接口） |
| `/api/saveCard` | POST | 还卡上报（兼容旧接口） |

---

## 四、Socket长连接协议（APP ↔ 后台服务器）

### 4.1 基础规范

| 参数 | 规格 |
|------|------|
| 通信方式 | TCP长连接 |
| 字符集 | UTF-8 |
| 数据格式 | JSON |
| 时间格式 | 毫秒级Unix时间戳（13位数字） |
| 心跳间隔 | 30秒 |

### 4.2 基础协议

#### 4.2.1 登录请求（APP → 服务器）

```json
{
  "cmd": "login",
  "deviceId": "DEV001",
  "registerCode": "ABC123",
  "version": "1.0.0"
}
```

#### 4.2.2 登录响应（服务器 → APP）

```json
{
  "cmd": "loginResp",
  "code": 0,
  "msg": "success",
  "token": "session_token_string"
}
```

#### 4.2.3 心跳（APP → 服务器）

```json
{
  "cmd": "heartbeat",
  "deviceId": "DEV001",
  "timestamp": 1700000000000
}
```

**响应：**
```json
{
  "cmd": "heartbeatResp",
  "code": 0,
  "serverTime": 1700000000000
}
```

#### 4.2.4 登出（APP → 服务器）

```json
{
  "cmd": "logout",
  "deviceId": "DEV001"
}
```

### 4.3 指令下发协议（服务器 → APP）

#### 4.3.1 同步人员与生物特征

```json
{
  "cmd": "syncUser",
  "data": {
    "userId": "U001",
    "name": "张三",
    "faceFeature": "base64_encoded_face_data",
    "fingerFeature": "base64_encoded_finger_data",
    "action": "ADD"
  }
}
```

**字段说明：**
- `action`：`ADD`（新增）、`DELETE`（删除）、`UPDATE`（更新）

**响应：**
```json
{
  "cmd": "syncUserResp",
  "code": 0,
  "msg": "success"
}
```

#### 4.3.2 远程发卡/开门指令

```json
{
  "cmd": "remoteOpen",
  "slotId": 5,
  "authType": "ADMIN",
  "operatorId": "admin001"
}
```

**响应：**
```json
{
  "cmd": "remoteOpenResp",
  "code": 0,
  "slotId": 5,
  "status": "OPENED"
}
```

**状态值：**
- `OPENED`：已开门
- `FAILED`：开门失败
- `OCCUPIED`：卡位被占用
- `EMPTY`：卡位已空闲

#### 4.3.3 查询实时状态

```json
{
  "cmd": "queryStatus",
  "slotId": -1
}
```

**字段说明：**
- `slotId`：卡位编号（-1表示查询所有）

**响应：**
```json
{
  "cmd": "statusResp",
  "data": [
    {
      "slotId": 1,
      "status": "OCCUPIED",
      "cardNo": "123456",
      "voltage": 5.0,
      "current": 0.5
    }
  ]
}
```

**状态枚举：**
- `EMPTY`：空位
- `OCCUPIED`：有卡（未充电）
- `CHARGING`：充电中
- `FULL`：已充满
- `FAULT`：故障

#### 4.3.4 远程一键弹卡

```json
{
  "cmd": "remoteEjectAll",
  "operatorId": "admin001",
  "confirm": true
}
```

**响应：**
```json
{
  "cmd": "remoteEjectAllResp",
  "code": 0,
  "msg": "success",
  "ejectedCount": 10
}
```

#### 4.3.5 重启APP

```json
{
  "cmd": "restartApp",
  "delayMs": 3000
}
```

**响应：**
```json
{
  "cmd": "restartAppResp",
  "code": 0,
  "msg": "restarting"
}
```

### 4.4 上报协议（APP → 服务器）

#### 4.4.1 实时状态上报（事件触发或定时）

```json
{
  "cmd": "statusReport",
  "deviceId": "DEV001",
  "timestamp": 1700000000000,
  "slots": [
    {
      "slotId": 1,
      "status": "CHARGING",
      "cardNo": "123456",
      "voltage": 5.0,
      "current": 0.5,
      "faultCode": 0
    }
  ]
}
```

**响应：**
```json
{
  "cmd": "statusReportResp",
  "code": 0
}
```

#### 4.4.2 取还卡上报

```json
{
  "cmd": "cardEvent",
  "deviceId": "DEV001",
  "cardNo": "123456",
  "eventType": "TAKE",
  "slotId": 1,
  "timestamp": 1700000000000,
  "authType": "FACE"
}
```

**响应：**
```json
{
  "cmd": "cardEventResp",
  "code": 0
}
```

#### 4.4.3 终端日志上报

```json
{
  "cmd": "logReport",
  "deviceId": "DEV001",
  "level": "ERROR",
  "tag": "FaceRecognition",
  "content": "人脸识别失败，相似度0.65低于阈值0.8",
  "timestamp": 1700000000000
}
```

**日志级别：**
- `DEBUG`：调试信息
- `INFO`：一般信息
- `WARN`：警告信息
- `ERROR`：错误信息

**响应：**
```json
{
  "cmd": "logReportResp",
  "code": 0
}
```

#### 4.4.4 设备激活状态变更上报

```json
{
  "cmd": "authStatusChange",
  "deviceId": "DEV001",
  "oldStatus": "ACTIVATED",
  "newStatus": "EXPIRED",
  "reason": "授权已过期",
  "timestamp": 1700000000000
}
```

**状态值：**
- `ACTIVATED`：已激活
- `EXPIRED`：已过期
- `DISABLED`：已禁用
- `PENDING_APPROVAL`：待审核

**响应：**
```json
{
  "cmd": "authStatusChangeResp",
  "code": 0
}
```

#### 4.4.5 硬件故障上报

```json
{
  "cmd": "hardwareFault",
  "deviceId": "DEV001",
  "slotId": 3,
  "faultCode": 0x01,
  "faultMsg": "卡位电机卡死",
  "timestamp": 1700000000000
}
```

**响应：**
```json
{
  "cmd": "hardwareFaultResp",
  "code": 0
}
```

### 4.5 Socket命令汇总表

| 命令 | 方向 | 说明 |
|------|------|------|
| `login` | APP→服务器 | 设备登录 |
| `loginResp` | 服务器→APP | 登录响应 |
| `heartbeat` | APP→服务器 | 心跳 |
| `heartbeatResp` | 服务器→APP | 心跳响应 |
| `logout` | APP→服务器 | 设备登出 |
| `syncUser` | 服务器→APP | 同步人员与生物特征 |
| `syncUserResp` | APP→服务器 | 人员同步响应 |
| `remoteOpen` | 服务器→APP | 远程发卡/开门 |
| `remoteOpenResp` | APP→服务器 | 远程开门响应 |
| `queryStatus` | 服务器→APP | 查询实时状态 |
| `statusResp` | APP→服务器 | 状态响应 |
| `remoteEjectAll` | 服务器→APP | 远程一键弹卡 |
| `remoteEjectAllResp` | APP→服务器 | 弹卡响应 |
| `restartApp` | 服务器→APP | 重启APP |
| `restartAppResp` | APP→服务器 | 重启响应 |
| `statusReport` | APP→服务器 | 实时状态上报 |
| `statusReportResp` | 服务器→APP | 状态上报响应 |
| `cardEvent` | APP→服务器 | 取还卡上报 |
| `cardEventResp` | 服务器→APP | 取还卡响应 |
| `logReport` | APP→服务器 | 终端日志上报 |
| `logReportResp` | 服务器→APP | 日志上报响应 |
| `authStatusChange` | APP→服务器 | 授权状态变更上报 |
| `authStatusChangeResp` | 服务器→APP | 授权变更响应 |
| `hardwareFault` | APP→服务器 | 硬件故障上报 |
| `hardwareFaultResp` | 服务器→APP | 故障上报响应 |

---

## 五、错误码定义

### 5.1 通用错误码

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 9000 | 系统内部错误 |

### 5.2 设备相关错误码

| 错误码 | 说明 |
|--------|------|
| 1001 | 注册码无效或已过期 |
| 1002 | Token过期或无效 |
| 1003 | 设备未授权 |
| 1004 | 设备已被禁用 |
| 1005 | 设备不存在 |
| 1006 | 激活码已被其他设备使用 |

### 5.3 人员相关错误码

| 错误码 | 说明 |
|--------|------|
| 2001 | 人员已存在 |
| 2002 | 特征数据错误或为空 |
| 2003 | 人员不存在 |

### 5.4 卡位相关错误码

| 错误码 | 说明 |
|--------|------|
| 3001 | 卡位不存在 |
| 3002 | 卡位被占用 |
| 3003 | 卡位已空闲 |

### 5.5 硬件相关错误码

| 错误码 | 说明 |
|--------|------|
| 4001 | 硬件操作超时 |
| 4002 | 硬件未响应 |
| 4003 | 串口通信失败 |

### 5.6 引擎相关错误码

| 错误码 | 说明 |
|--------|------|
| 5001 | 人脸引擎未激活 |
| 5002 | 指纹引擎未初始化 |

### 5.7 数据库错误码

| 错误码 | 说明 |
|--------|------|
| 6001 | 数据库操作失败 |

---

## 六、通用数据格式说明

### 6.1 卡位状态枚举

| 状态值 | 说明 |
|--------|------|
| EMPTY | 空位 |
| OCCUPIED | 有卡（未充电） |
| CHARGING | 充电中 |
| FULL | 已充满 |
| FAULT | 故障 |

### 6.2 认证类型枚举

| 类型值 | 说明 |
|--------|------|
| FACE | 人脸识别 |
| FINGER | 指纹识别 |
| ADMIN | 管理员操作 |
| REMOTE | 远程指令 |

### 6.3 时间格式

所有时间戳统一使用**毫秒级Unix时间戳**（13位数字）。

**示例：** `1700000000000` 表示 2023-11-14 22:13:20 UTC。

---

## 七、设备激活流程时序图

```
┌────────┐                    ┌────────┐                    ┌────────┐
│  APP   │                    │  后台  │                    │ 管理员 │
└───┬────┘                    └───┬────┘                    └───┬────┘
    │                             │                             │
    │ 1. 设备启动，检测未激活      │                             │
    │─────────────────────────────│                             │
    │                             │                             │
    │ 2. POST /device/activate    │                             │
    │    (deviceId, mac, model)   │                             │
    │────────────────────────────>│                             │
    │                             │ 3. 生成注册码，状态PENDING  │
    │<────────────────────────────│                             │
    │   返回registerCode          │                             │
    │                             │                             │
    │ 4. 界面显示注册码，提示激活  │                             │
    │                             │                             │
    │                             │ 5. 管理员在后台录入注册码   │
    │                             │<────────────────────────────│
    │                             │    绑定设备与注册码         │
    │                             │                             │
    │ 6. 用户输入注册码           │                             │
    │────────────────────────────>│                             │
    │ POST /device/verify         │                             │
    │                             │ 7. 验证注册码有效性         │
    │<────────────────────────────│                             │
    │   返回valid=true + token    │                             │
    │                             │                             │
    │ 8. 保存token，状态变为已激活│                             │
    │                             │                             │
    │ 9. Socket登录               │                             │
    │────────────────────────────>│                             │
    │                             │                             │
    │10. 正常业务流程             │                             │
    │────────────────────────────>│                             │
    │                             │                             │
```

---

## 八、文档说明

1. 本协议文档包含三个通信层面：
   - 串口通信协议（APP ↔ 硬件单板）
   - HTTP接口协议（APP ↔ 后台服务器）
   - Socket长连接协议（APP ↔ 后台服务器）

2. 所有接口统一使用JSON格式，字符集UTF-8

3. 时间戳统一使用毫秒级Unix时间戳（13位数字）

4. 本协议文档基于以下文档整理：
   - 需求文档初稿.txt
   - 智能工卡发卡机设备APP需求文档 V1.1
   - 工作卡单板与安卓屏通信协议 V1.5
