# 客户端接入接口文档

> 版本：V4.2 | 生成时间：2026-08-11

本文档面向客户端（Android 平板）开发，涵盖所有可用接口，包括 HTTP REST API 和 MQTT 消息通道。

---

## 一、接口概览

客户端与网关（komei-card-gateway）通过两种通道通信：


| 通道     | 用途                                           | 说明                                                          |
| -------- | ---------------------------------------------- | ------------------------------------------------------------- |
| **HTTP** | 设备注册/激活、配置管理、大数据传输、MQTT 降级 | 引导期核心通道，Bearer Token 鉴权                             |
| **MQTT** | 实时通信、指令下发、状态上报                   | Topic:`card/{deviceCode}/up`(上) `card/{deviceCode}/down`(下) |

HTTP 接口基础路径：`http://{gateway-host}:{port}/api/v1`

---

## 二、设备生命周期（HTTP）

### 2.1 设备注册

```
POST /api/v1/device/register
匿名接口，无需 Token
```

**请求体**：


| 字段        | 类型    | 必填   | 说明                                     |
| ----------- | ------- | ------ | ---------------------------------------- |
| machineId   | String  | **是** | 机器唯一标识（AndroidID 等），防克隆核心 |
| mac         | String  | 否     | MAC 地址                                 |
| model       | String  | 否     | 设备型号                                 |
| osType      | String  | 否     | 操作系统类型（ANDROID/LINUX）            |
| osVersion   | String  | 否     | 操作系统版本号                           |
| version     | String  | 否     | APP 版本号                               |
| versionCode | Integer | 否     | APP 版本号（整数，用于比较）             |
| channelId   | String  | 否     | 渠道号                                   |

**响应**：

```json
{
    "code": 200,
    "msg": "操作成功",
    "data": {
        "deviceToken": "token-xxx",
        "deviceCode": "2A45688C",
        "isNew": true
    }
}
```


| 字段        | 类型    | 说明                                            |
| ----------- | ------- | ----------------------------------------------- |
| deviceToken | String  | 设备身份令牌，后续所有 HTTP 接口的 Bearer Token |
| deviceCode  | String  | 服务端分配的设备编码                            |
| isNew       | Boolean | 是否为新注册设备                                |

---

### 2.2 设备激活

```
POST /api/v1/device/activate
Authorization: Bearer {deviceToken}
```

**请求体**：


| 字段      | 类型   | 必填   | 说明                              |
| --------- | ------ | ------ | --------------------------------- |
| deviceId  | String | **是** | 设备编码（注册返回的 deviceCode） |
| mac       | String | 否     | MAC 地址                          |
| model     | String | 否     | 设备型号                          |
| version   | String | 否     | APP 版本号                        |
| machineId | String | 否     | 机器唯一标识                      |
| osType    | String | 否     | 操作系统类型                      |
| osVersion | String | 否     | 操作系统版本号                    |

**响应（设备已激活）**：

```json
{
    "code": 200,
    "data": {
        "valid": true,
        "clientId": "card-2A45688C",
        "mqttPassword": "mqtt-pwd-xxx",
        "signingKey": "hmac-key-xxx",
        "expireTime": 1754736000000,
        "deviceName": "设备名称",
        "deviceCode": "2A45688C"
    }
}
```

**响应（需要激活码）**：

```json
{
    "code": 200,
    "data": {
        "registerCode": "reg-code-xxx",
        "status": "PENDING",
        "expireTime": 1754736000000
    }
}
```


| 字段         | 类型    | 说明                               |
| ------------ | ------- | ---------------------------------- |
| valid        | Boolean | 是否已激活                         |
| clientId     | String  | MQTT 客户端 ID                     |
| mqttPassword | String  | MQTT 连接密码                      |
| signingKey   | String  | 消息签名密钥（HMAC-SHA256）        |
| expireTime   | Long    | 凭证过期时间戳（毫秒）             |
| registerCode | String  | 注册码（未激活时，需展示给管理员） |

---

### 2.3 激活码验证

```
POST /api/v1/device/verify
Authorization: Bearer {deviceToken}
```

**请求体**：


| 字段         | 类型   | 必填   | 说明                     |
| ------------ | ------ | ------ | ------------------------ |
| registerCode | String | 否     | 注册码（激活阶段A 返回） |
| activeKey    | String | **是** | 激活码（管理员输入）     |

**响应**：同 2.2 已激活响应。

---

## 三、设备运行时接口

### 3.1 设备登录

```
HTTP: POST /api/v1/device/login (仅 MQTT 离线时降级使用)
MQTT: card/{deviceCode}/up, cmd: "login"
```

两种通道请求体相同，无响应 data。

**响应**：

```json
{
    "code": 0,
    "msg": "success",
    "token": "session-token-xxx"
}
```


| 字段  | 类型    | 说明       |
| ----- | ------- | ---------- |
| code  | Integer | 0=成功     |
| msg   | String  | 结果消息   |
| token | String  | 会话 Token |

---

### 3.2 心跳上报

```
HTTP: POST /api/v1/device/heartbeat
MQTT: card/{deviceCode}/up, cmd: "heartbeat"
```

**MQTT 请求 raw**：

```json
{
    "seq": 1754736123456
}
```


| 字段 | 类型 | 必填 | 说明                           |
| ---- | ---- | ---- | ------------------------------ |
| seq  | Long | 否   | 客户端毫秒时间戳，`Date.now()` |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"heartbeatResp"`

```json
{
    "code": 0,
    "serverTime": 1754736123456
}
```


| 字段       | 类型    | 说明                           |
| ---------- | ------- | ------------------------------ |
| code       | Integer | 0=成功                         |
| serverTime | Long    | 服务端毫秒时间戳（可用于校时） |

---

### 3.3 设备配置获取

```
HTTP: GET /api/v1/device/config
MQTT: card/{deviceCode}/up, cmd: "deviceConfig" (data 为空)
```

**响应**（`DeviceConfigResponse`）：

```json
{
    "code": 200,
    "data": {
        "communicationMode": "MQTT",
        "httpHost": "192.168.1.100",
        "httpPort": 8080,
        "mqttHost": "192.168.1.100",
        "mqttPort": 1883,
        "mqttHeartbeatInterval": 60000,
        "mqttReconnectInitialInterval": 1000,
        "mqttReconnectMaxInterval": 60000,
        "mqttStatusReportInterval": 300,
        "slotStatusPushInterval": 10000,
        "faceThreshold": 0.8,
        "fingerThreshold": 0.7,
        "fingerEnabled": "1",
        "faceRecognitionTimeout": 60,
        "searchTimeout": 120,
        "searchIntervalTime": 3,
        "needFaceLiveness": "1",
        "captureTimeout": 30,
        "cameraFacing": "FRONT",
        "cameraMirror": "1",
        "cameraRotation": 90,
        "cameraFrameWidth": 640,
        "cameraFrameHeight": 480,
        "serialPollEnabled": "1",
        "serialPollInterval": 500,
        "serialResponseTimeout": 3000,
        "pollingMode": "AUTO",
        "slotSortDirection": "ASC",
        "serialPort": "/dev/ttyS1",
        "baudRate": 115200,
        "groupSize": 10,
        "totalSlots": 10,
        "superAdminPassword": "123456"
    }
}
```


| 字段                         | 类型       | 单位 | 默认值     | 说明                         |
| ---------------------------- | ---------- | ---- | ---------- | ---------------------------- |
| communicationMode            | String     | -    | MQTT       | 通信模式：MQTT / HTTP        |
| httpHost                     | String     | -    | -          | HTTP 服务端地址              |
| httpPort                     | Integer    | -    | -          | HTTP 服务端端口              |
| mqttHost                     | String     | -    | -          | MQTT Broker 地址             |
| mqttPort                     | Integer    | -    | -          | MQTT Broker 端口             |
| mqttHeartbeatInterval        | Integer    | ms   | 60000      | MQTT 心跳发送间隔            |
| mqttReconnectInitialInterval | Integer    | ms   | 1000       | MQTT 初始重连间隔            |
| mqttReconnectMaxInterval     | Integer    | ms   | 60000      | MQTT 最大重连间隔            |
| mqttStatusReportInterval     | Integer    | 秒   | 300        | 状态上报间隔                 |
| slotStatusPushInterval       | Integer    | ms   | 10000      | 卡槽状态推送间隔             |
| faceThreshold                | BigDecimal | -    | 0.8        | 人脸识别阈值                 |
| fingerThreshold              | BigDecimal | -    | 0.7        | 指纹识别阈值                 |
| fingerEnabled                | String     | -    | 1          | 指纹功能开关：1=启用 0=禁用  |
| faceRecognitionTimeout       | Integer    | 秒   | 60         | 人脸识别超时                 |
| searchTimeout                | Integer    | 秒   | 120        | 搜索超时                     |
| searchIntervalTime           | Integer    | 秒   | 3          | 搜索间隔                     |
| needFaceLiveness             | String     | -    | 1          | 是否需要活体检测             |
| captureTimeout               | Integer    | 秒   | 30         | 拍照超时                     |
| cameraFacing                 | String     | -    | FRONT      | 摄像头朝向                   |
| cameraMirror                 | String     | -    | 1          | 摄像头镜像                   |
| cameraRotation               | Integer    | °   | 90         | 摄像头旋转角度               |
| cameraFrameWidth             | Integer    | px   | 640        | 摄像头帧宽                   |
| cameraFrameHeight            | Integer    | px   | 480        | 摄像头帧高                   |
| serialPollEnabled            | String     | -    | 1          | 串口轮询开关                 |
| serialPollInterval           | Integer    | ms   | 500        | 串口轮询间隔                 |
| serialResponseTimeout        | Integer    | ms   | 3000       | 串口响应超时                 |
| pollingMode                  | String     | -    | AUTO       | 轮询模式                     |
| slotSortDirection            | String     | -    | ASC        | 卡槽排序方向                 |
| serialPort                   | String     | -    | /dev/ttyS1 | 串口设备路径                 |
| baudRate                     | Integer    | -    | 115200     | 串口波特率                   |
| groupSize                    | Integer    | -    | 10         | 每页显示卡槽数               |
| totalSlots                   | Integer    | -    | 10         | 卡槽总数                     |
| superAdminPassword           | String     | -    | -          | 超级管理员密码（终端校验用） |

---

### 3.4 设备配置保存

```
HTTP: POST /api/v1/device/config
MQTT: card/{deviceCode}/up, cmd: "deviceConfigSet"
```

**请求体**（增量更新，仅传需要修改的字段）：

```json
{
    "mqttHeartbeatInterval": 30000,
    "faceThreshold": 0.85
}
```

支持的可修改字段：`mqttHeartbeatInterval`、`mqttReconnectInitialInterval`、`mqttReconnectMaxInterval`、`mqttStatusReportInterval`、`slotStatusPushInterval`、`faceThreshold`、`fingerThreshold`、`fingerEnabled`、`faceRecognitionTimeout`、`searchTimeout`、`searchIntervalTime`、`needFaceLiveness`、`captureTimeout`、`cameraFacing`、`cameraMirror`、`cameraRotation`、`cameraFrameWidth`、`cameraFrameHeight`、`serialPollEnabled`、`serialPollInterval`、`serialResponseTimeout`、`pollingMode`、`slotSortDirection`、`serialPort`、`baudRate`、`groupSize`、`totalSlots`、`communicationMode`、`httpHost`、`httpPort`、`mqttHost`、`mqttPort`、`superAdminPassword`

**响应**：同 3.3 完整配置。

---

### 3.5 修改管理员密码

```
HTTP: POST /api/v1/device/password (仅 MQTT 离线时使用)
MQTT: card/{deviceCode}/up, cmd: "changeSuperPassword"
```

**请求体/raw**：


| 字段        | 类型   | 必填   | 说明   |
| ----------- | ------ | ------ | ------ |
| oldPassword | String | **是** | 旧密码 |
| newPassword | String | **是** | 新密码 |

**响应**：

```json
{
    "code": 0,
    "msg": "密码修改成功"
}
```

---

### 3.6 卡槽状态上报

```
HTTP: POST /api/v1/device/status (仅 MQTT 离线时使用)
MQTT: card/{deviceCode}/up, cmd: "statusReport"
```

**MQTT 请求 raw**：

```json
{
    "slots": [
        {
            "slotId": 1,
            "status": "FULL",
            "cardNo": "868909073452875",
            "voltage": 5.0,
            "current": 0,
            "faultCode": 0
        },
        {
            "slotId": 2,
            "status": "EMPTY",
            "voltage": 4.95,
            "current": 0,
            "faultCode": 0
        }
    ]
}
```


| 字段      | 类型       | 必填   | 说明                                  |
| --------- | ---------- | ------ | ------------------------------------- |
| slotId    | Integer    | **是** | 卡槽编号                              |
| status    | String     | **是** | EMPTY / FULL / FAULT / IN_USE         |
| cardNo    | String     | 否     | 卡号（FULL 时必须提供，EMPTY 时忽略） |
| voltage   | BigDecimal | 否     | 电压值                                |
| current   | Integer    | 否     | 电流值                                |
| faultCode | Integer    | 否     | 故障码                                |

**响应（MQTT）**：`card/{deviceCode}/down`, cmd: `"statusReportResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

### 3.7 设备授权状态变更上报

```
HTTP: POST /api/v1/device/auth/change
MQTT: card/{deviceCode}/up, cmd: "authStatusChange"
```

> 此接口用于设备自身的授权状态变化，不用于员工启用/停用；客户端人员管理不发起该接口，也不构造其请求字段。

**响应**：

```json
{
    "code": 0,
    "msg": "success"
}
```

---

### 3.8 获取授权状态

```
HTTP: GET /api/v1/device/auth/status
MQTT: card/{deviceCode}/up, cmd: "authRequest"
```

**MQTT 请求 raw**：


| 字段       | 类型 | 必填 | 说明                              |
| ---------- | ---- | ---- | --------------------------------- |
| employeeId | Long | 否   | 员工 ID（不传则返回全部授权状态） |

**响应（MQTT）**：`card/{deviceCode}/down`, cmd: `"authRequestResp"`

```json
{
    "code": 0,
    "data": {
        "authorizedEmployees": [
            {"employeeId": 1001, "employeeName": "张三"}
        ]
    }
}
```

---

### 3.9 自检结果上报

```
HTTP: POST /api/v1/device/selfcheck
MQTT: card/{deviceCode}/up, cmd: "selfCheck"
```

**请求体**：


| 字段          | 类型 | 必填 | 说明             |
| ------------- | ---- | ---- | ---------------- |
| selfCheckTime | Long | 否   | 自检时间（毫秒） |

**响应**：

```json
{
    "code": 0,
    "msg": "success"
}
```

---

### 3.10 批量操作结果上报

```
HTTP: POST /api/v1/device/batch-result
MQTT: card/{deviceCode}/up, cmd: "batchResult"
```

**请求体**：


| 字段          | 类型    | 必填 | 说明     |
| ------------- | ------- | ---- | -------- |
| operationType | String  | 否   | 操作类型 |
| totalCount    | Integer | 否   | 总数     |
| successCount  | Integer | 否   | 成功数   |
| failCount     | Integer | 否   | 失败数   |
| message       | String  | 否   | 结果消息 |

**响应**：

```json
{
    "code": 0,
    "msg": "success"
}
```

---

## 四、取还卡

### 4.1 取还卡事件上报

```
HTTP: POST /api/v1/card/event
MQTT: card/{deviceCode}/up, cmd: "cardEvent"（主通道）
```

**MQTT 请求 raw**：

```json
{
    "cardNo": "868909073452875",
    "action": "TAKE",
    "timestamp": 1754736123456,
    "employeeId": 1001
}
```


| 字段       | 类型   | 必填   | 说明                         |
| ---------- | ------ | ------ | ---------------------------- |
| cardNo     | String | **是** | 卡号                         |
| action     | String | **是** | TAKE（取卡）/ RETURN（还卡） |
| timestamp  | Long   | **是** | 事件发生毫秒时间戳           |
| employeeId | Long   | 否     | 员工 ID（取卡时必填）        |

**响应（MQTT）**：`card/{deviceCode}/down`, cmd: `"cardEventResp"`

```json
{
    "code": 0,
    "msg": "success",
    "employeeName": "张三",
    "departmentName": "技术部"
}
```


| 字段           | 类型    | 说明                   |
| -------------- | ------- | ---------------------- |
| code           | Integer | 0=成功, 1=失败         |
| msg            | String  | 结果消息               |
| employeeName   | String  | 员工姓名（取卡时返回） |
| departmentName | String  | 部门名称（取卡时返回） |

---

### 4.2 卡操作（同步执行，已废弃）

```
POST /api/v1/card/action
```

> 已废弃，请使用 4.1 卡事件上报替代。

---

## 五、员工管理

### 5.1 获取员工信息

```
MQTT: card/{deviceCode}/up, cmd: "getEmpInfo"
```

**MQTT 请求 raw**：


| 字段       | 类型 | 必填   | 说明    |
| ---------- | ---- | ------ | ------- |
| employeeId | Long | **是** | 员工 ID |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"getEmpInfoResp"`

```json
{
    "code": 0,
    "data": {
        "employeeId": 1001,
        "employeeName": "张三",
        "employeeNo": "EMP001",
        "departmentId": 101,
        "departmentName": "技术部",
        "phoneNumber": "13800138000",
        "faceRegistered": true,
        "fingerprintRegistered": true
    }
}
```

---

### 5.2 分页获取员工列表

```
MQTT: card/{deviceCode}/up, cmd: "getEmpInfoByPage"
```

**MQTT 请求 raw**：


| 字段         | 类型    | 必填 | 说明                 |
| ------------ | ------- | ---- | -------------------- |
| pageNo       | Integer | 否   | 页码（默认 1）       |
| pageSize     | Integer | 否   | 每页条数（默认 10）  |
| employeeName | String  | 否   | 员工姓名（模糊搜索） |
| employeeNo   | String  | 否   | 员工编号（精确搜索） |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"getEmpInfoByPageResp"`

```json
{
    "code": 0,
    "data": {
        "records": [
            {
                "employeeId": 1001,
                "employeeName": "张三",
                "employeeNo": "EMP001",
                "departmentName": "技术部",
                "faceRegistered": true
            }
        ],
        "total": 100,
        "pageNo": 1,
        "pageSize": 10
    }
}
```

---

### 5.3 保存员工

```
MQTT: card/{deviceCode}/up, cmd: "saveEmployee"
```

**MQTT 请求 raw**：


| 字段         | 类型    | 必填   | 说明                           |
| ------------ | ------- | ------ | ------------------------------ |
| action       | String | **是** | `add`=新增、`update`=修改、`delete`=删除、`enable`=启用、`disable`=停用 |
| employeeId   | Long   | 否     | 员工 ID（修改/删除/启用/停用时使用） |
| employeeCode | String | 否     | 员工编号 |
| employeeName | String | 条件必填 | 员工姓名（仅客户端新增/修改时提交） |
| cardNo       | String | 否     | 可为空，预留字段；不用于客户端选取卡槽 |
| deptId       | Long   | 否     | 部门 ID |
| department   | String | 否     | 部门名称 |
| email        | String | 否     | 邮箱 |
| phone        | String | 否     | 手机号 |
| status       | String | 否     | `0`=正常，`1`=停用；仅新增/修改时使用 |
| position     | String | 否     | 职位 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"saveEmployeeResp"`

```json
{
    "code": 0,
    "msg": "员工保存成功",
    "employeeId": 1001,
    "action": "add"
}
```


| 字段       | 类型   | 说明                  |
| ---------- | ------ | --------------------- |
| employeeId | Long   | 员工 ID               |
| action     | String | add=新增, update=更新, enable=启用, disable=停用 |

人员启用/停用由客户端上行 `saveEmployee`：`{"employeeId": 1001, "action": "enable"}` 或 `{"employeeId": 1001, "action": "disable"}`。两种操作只提交 `employeeId` 与 `action`，等待同一 `msgId` 的 `saveEmployeeResp` 成功后才更新本机员工状态。

**注意**：员工所属部门必须在设备的授权范围内（设备部门及其子部门），可通过 5.9 部门树查询获取可选部门。若 `deptId` 超出授权范围，将返回错误。

---

### 5.4 获取已注册人脸列表

```
HTTP: GET /api/v1/employee/face/registered
MQTT: card/{deviceCode}/up, cmd: "getFaceRegistered"（data 为空）
```

> ⚠️ 2026-08-09 变更：HTTP 返回格式从数组改为对象。

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"getFaceRegisteredResp"`

```json
{
    "code": 0,
    "data": {
        "employeeIds": [1001, 1002, 1003],
        "count": 3
    }
}
```

---

### 5.5 人脸注册

```
HTTP: POST /api/v1/employee/face/image（必须先上传图片）
HTTP: POST /api/v1/employee/face
MQTT: card/{deviceCode}/up, cmd: "faceRegister"
```

**图片上传请求**：`multipart/form-data`，字段为 `employeeId` 和文件字段 `file`。业务成功响应返回 `fileHash` 与 `fileName`（图片存储路径）；客户端将 `fileName` 作为后续人脸注册的 `faceImagePath`，并校验 `fileHash` 与本机图片 SHA-256 一致。

**MQTT 请求 raw**：


| 字段       | 类型   | 必填   | 说明                  |
| ---------- | ------ | ------ | --------------------- |
| employeeId    | Long   | **是** | 员工 ID |
| fileHash      | String | **是** | 图片上传接口返回的 SHA-256 |
| faceImagePath | String | **是** | 图片上传接口返回的存储路径 |
| faceFeature   | String | **是** | 原生人脸录入得到的特征 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"faceRegisterResp"`

```json
{
    "code": 0,
    "msg": "人脸注册成功",
    "employeeId": 1001
}
```

---

### 5.6 删除人脸

```
MQTT: card/{deviceCode}/up, cmd: "faceDelete"
```

**MQTT 请求 raw**：


| 字段       | 类型 | 必填   | 说明    |
| ---------- | ---- | ------ | ------- |
| employeeId | Long | **是** | 员工 ID |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"faceDeleteResp"`

```json
{
    "code": 0,
    "msg": "人脸删除成功",
    "employeeId": 1001
}
```

---

### 5.7 指纹上传

```
HTTP: POST /api/v1/fingerprint/upload
MQTT: card/{deviceCode}/up, cmd: "fingerprintUpload"
```

**MQTT 请求 raw**：


| 字段            | 类型   | 必填   | 说明                  |
| --------------- | ------ | ------ | --------------------- |
| employeeId      | Long   | **是** | 员工 ID               |
| fingerprintData | String | **是** | Base64 编码的指纹数据 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"fingerprintUploadResp"`

```json
{
    "code": 0,
    "msg": "指纹上传成功",
    "fingerprintId": 5001
}
```

---

### 5.8 删除指纹

```
MQTT: card/{deviceCode}/up, cmd: "fingerprintDelete"
```

**MQTT 请求 raw**：


| 字段       | 类型 | 必填   | 说明    |
| ---------- | ---- | ------ | ------- |
| employeeId | Long | **是** | 员工 ID |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"fingerprintDeleteResp"`

```json
{
    "code": 0,
    "msg": "指纹删除成功"
}
```

---

### 5.9 部门树查询

```
HTTP: GET /api/v1/department/tree
MQTT: card/{deviceCode}/up, cmd: "getDepartment"（data 为空）
```

> 返回当前设备授权部门及其子部门的树结构，用于员工新增/编辑时选择部门。

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"getDepartmentResp"`

```json
{
    "code": 0,
    "msg": "success",
    "dept": {
        "deptId": 100,
        "deptName": "总公司",
        "parentId": 0,
        "orderNum": 1,
        "ancestors": "0,100",
        "children": [
            {
                "deptId": 101,
                "deptName": "技术部",
                "parentId": 100,
                "orderNum": 1,
                "ancestors": "0,100,101",
                "children": []
            }
        ]
    }
}
```


| 字段      | 类型    | 说明               |
| --------- | ------- | ------------------ |
| deptId    | Long    | 部门 ID            |
| deptName  | String  | 部门名称           |
| parentId  | Long    | 父部门 ID          |
| orderNum  | Integer | 显示顺序           |
| ancestors | String  | 祖级列表           |
| children  | List    | 子部门（递归嵌套） |

**注意**：

- 设备只能看到其绑定部门及子部门，超出范围的部门不会出现在树中
- 员工新增/变更部门时，目标部门必须在树范围内，否则会收到 `"员工部门超出设备授权范围"` 错误

---

### 5.10 批量指纹操作

```
MQTT: card/{deviceCode}/up, cmd: "fingerprintBatch"
```

**MQTT 请求 raw**：


| 字段           | 类型         | 必填   | 说明         |
| -------------- | ------------ | ------ | ------------ |
| action         | String       | **是** | 操作类型     |
| fingerprintIds | List\<Long\> | 否     | 指纹 ID 列表 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"fingerprintBatchResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

## 六、异常上报

### 6.1 硬件故障上报

```
HTTP: POST /api/v1/fault/report
MQTT: card/{deviceCode}/up, cmd: "faultReport"
```

**MQTT 请求 raw**：


| 字段             | 类型    | 必填   | 说明                 |
| ---------------- | ------- | ------ | -------------------- |
| slotId           | Integer | 否     | 卡槽 ID              |
| faultType        | String  | **是** | 故障类型             |
| faultDescription | String  | 否     | 故障描述             |
| faultTime        | Long    | 否     | 故障发生时间（毫秒） |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"faultReportResp"`

```json
{
    "code": 0,
    "msg": "故障上报成功"
}
```

---

### 6.2 日志上报

```
HTTP: POST /api/v1/log/report
MQTT: card/{deviceCode}/up, cmd: "logReport"
```

**MQTT 请求 raw**：


| 字段      | 类型   | 必填   | 说明                |
| --------- | ------ | ------ | ------------------- |
| level     | String | **是** | INFO / WARN / ERROR |
| message   | String | **是** | 日志内容            |
| timestamp | Long   | 否     | 日志时间（毫秒）    |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"logReportResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

### 6.3 日志上传开关

```
HTTP: POST /api/v1/log/toggle
MQTT: card/{deviceCode}/up, cmd: "toggleLogUpload"
```

**MQTT 请求 raw**：


| 字段    | 类型    | 必填   | 说明                  |
| ------- | ------- | ------ | --------------------- |
| enabled | Boolean | **是** | true=开启，false=关闭 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"toggleLogUploadResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

## 七、升级

### 7.1 升级状态上报

```
HTTP: POST /api/v1/upgrade/status
MQTT: card/{deviceCode}/up, cmd: "upgradeStatusReport"
```

**MQTT 请求 raw**：


| 字段    | 类型   | 必填   | 说明                         |
| ------- | ------ | ------ | ---------------------------- |
| version | String | 否     | 当前版本号                   |
| status  | String | **是** | UPGRADING / SUCCESS / FAILED |
| message | String | 否     | 升级结果描述                 |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"upgradeStatusReportResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

## 八、统计

### 8.1 统计上报

```
HTTP: POST /api/v1/statistics/report
MQTT: card/{deviceCode}/up, cmd: "statisticsReport"
```

**MQTT 请求 raw**：


| 字段      | 类型   | 必填   | 说明             |
| --------- | ------ | ------ | ---------------- |
| statType  | String | **是** | 统计类型         |
| statValue | Long   | 否     | 统计数值         |
| statTime  | Long   | 否     | 统计时间（毫秒） |

**MQTT 响应**：`card/{deviceCode}/down`, cmd: `"statisticsReportResp"`

```json
{
    "code": 0,
    "msg": "success"
}
```

---

## 九、通用说明

### 9.1 HTTP 鉴权

- 注册（`/api/v1/device/register`）为匿名接口
- 激活相关接口（`/api/v1/device/activate`、`/api/v1/device/verify`）使用注册返回的 `deviceToken`
- 其他所有接口使用 Token Header 鉴权：`Authorization: Bearer {deviceToken}`
- 部分接口带有 `@DisableWhenMqttOnline` 标记，表示仅 MQTT 离线时可用 HTTP 降级

### 9.2 MQTT 消息格式

**上行消息**（`card/{deviceCode}/up`）：

```json
{
    "msgId": "status_1786191735185_9627d9a7da3601f3_000012",
    "cmd": "statusReport",
    "timestamp": 1786191735225,
    "deviceCode": "2A45688C",
    "sign": "gGLD5AyBYbNKAJHDgTB7jqb7INUx4iUqf9THs1CW4gY=",
    "raw": "{...}"
}
```


| 字段       | 类型   | 必填   | 说明                                                             |
| ---------- | ------ | ------ | ---------------------------------------------------------------- |
| msgId      | String | **是** | 消息唯一 ID，建议格式：`{cmd}_{timestamp}_{random}`              |
| cmd        | String | **是** | 命令名称                                                         |
| timestamp  | Long   | **是** | 客户端毫秒时间戳                                                 |
| deviceCode | String | **是** | 设备编码                                                         |
| sign       | String | **是** | HMAC-SHA256 签名                                                 |
| raw        | String | **是** | 业务数据的原始 JSON 字符串（避免反序列化再序列化导致签名不匹配） |

**下行响应**（`card/{deviceCode}/down`）：

```json
{
    "msgId": "status_1786191735185_9627d9a7da3601f3_000012",
    "cmd": "statusReportResp",
    "timestamp": 1786191735500,
    "serverTime": 1786191735500,
    "deviceCode": "2A45688C",
    "code": 0,
    "msg": "success",
    "...": "..."
}
```


| 字段       | 类型    | 说明                        |
| ---------- | ------- | --------------------------- |
| msgId      | String  | 与上行消息相同的 msgId      |
| cmd        | String  | 响应命令名（指令名 + Resp） |
| timestamp  | Long    | 服务端时间戳                |
| serverTime | Long    | 服务端毫秒时间戳            |
| code       | Integer | 0=成功，其他=错误码         |
| msg        | String  | 响应消息                    |
| ...        | -       | 其他业务字段                |

### 9.3 签名算法

```
sign = Base64(HMAC-SHA256(signingKey, raw))
```

- `signingKey`：激活时从服务端获取
- `raw`：业务数据的原始 JSON 字符串
- 签名对大小写敏感

### 9.4 HTTP 统一响应格式

```json
{
    "code": 200,
    "msg": "操作成功",
    "data": { ... }
}
```


| 字段 | 类型    | 说明                                              |
| ---- | ------- | ------------------------------------------------- |
| code | Integer | HTTP 状态码，200=成功                             |
| msg  | String  | 提示消息                                          |
| data | Object  | 业务数据（MQTT 下行响应中业务字段直接平铺在顶层） |

---

## 十、接口索引（按 CMD）


| CMD                 | 方向     | 通道      | 说明           |
| ------------------- | -------- | --------- | -------------- |
| login               | UP       | MQTT/HTTP | 设备登录       |
| heartbeat           | UP       | MQTT/HTTP | 心跳           |
| statusReport        | UP       | MQTT/HTTP | 卡槽状态上报   |
| cardEvent           | UP       | MQTT/HTTP | 取还卡事件     |
| authStatusChange    | UP       | MQTT/HTTP | 授权状态变更   |
| authRequest         | UP       | MQTT/HTTP | 查询授权状态   |
| selfCheck           | UP       | MQTT/HTTP | 自检结果       |
| batchResult         | UP       | MQTT/HTTP | 批量结果       |
| deviceConfig        | UP→DOWN | MQTT/HTTP | 获取配置       |
| deviceConfigSet     | UP→DOWN | MQTT/HTTP | 保存配置       |
| changeSuperPassword | UP→DOWN | MQTT/HTTP | 修改密码       |
| faultReport         | UP→DOWN | MQTT/HTTP | 故障上报       |
| logReport           | UP→DOWN | MQTT/HTTP | 日志上报       |
| toggleLogUpload     | UP→DOWN | MQTT/HTTP | 日志开关       |
| statisticsReport    | UP→DOWN | MQTT/HTTP | 统计上报       |
| upgradeStatusReport | UP→DOWN | MQTT/HTTP | 升级状态       |
| getEmpInfo          | UP→DOWN | MQTT      | 获取员工信息   |
| getEmpInfoByPage    | UP→DOWN | MQTT      | 分页获取员工   |
| saveEmployee        | UP→DOWN | MQTT      | 保存员工       |
| getDepartment       | UP→DOWN | MQTT/HTTP | 部门树查询     |
| getFaceRegistered   | UP→DOWN | MQTT/HTTP | 获取已注册人脸 |
| faceRegister        | UP→DOWN | MQTT/HTTP | 人脸注册       |
| faceDelete          | UP→DOWN | MQTT      | 删除人脸       |
| fingerprintUpload   | UP→DOWN | MQTT/HTTP | 指纹上传       |
| fingerprintDelete   | UP→DOWN | MQTT      | 删除指纹       |
| fingerprintBatch    | UP→DOWN | MQTT      | 批量指纹操作   |
