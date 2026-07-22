# 设备配置与接口证据登记

更新时间：2026-07-23

本文件只记录已经有明确来源的事实，以及尚未确认的契约。它不把建议字段、推断状态或兼容尝试写成后端既有能力。

## 1. 证据优先级

1. `docs/source-2026-07-02/Android客户端接口文档.md` V4.1：当前后台接口和 MQTT 契约。
2. `docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`：串口帧和旧通信说明。
3. `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`：产品页面和旧版配置需求。
4. 用户明确要求：本部署的 HTTP 与 MQTT 服务器不在一起，必须分别配置。

存在冲突时，不自行选择解释；运行代码采用不覆盖、不发送、不执行，并把冲突留在本文。

## 2. 文档明确的后台事实

- 所有 HTTP 请求除注册、版本检查外使用 `Authorization: Bearer deviceToken`。
- HTTP 登录返回 `token`，但文档没有说明该 token 应放入哪个后续请求头；客户端只保存，不把它猜成 Bearer。
- MQTT 激活/验证返回 `mqttPassword`、`signingKey`、`clientId`；文档没有 MQTT username。
- MQTT 上行包含 `msgId/cmd/timestamp/deviceCode/sign/data`。
- MQTT 下行包含 `msgId/cmd/timestamp/data`，明确不含 `sign`。
- 下行命令列表仅包含：`remoteOpen`、`remoteEjectAll`、`restartApp`、`syncUser`、`syncConfig`、`firmwareUpgrade`、`cancelUpgrade`、`deviceSelfCheck`、`enableLogUpload`、`disableLogUpload`。
- `enableLogUpload` 和 `disableLogUpload` 明确无终端响应。
- 员工同步包含 `deletedEmployeeIds`；人脸、指纹特征项使用 `faceId/fingerId/employeeId/status`。
- `/api/v1/device/config` 只定义 `baudRate/groupSize/totalSlots/pollingInterval/serverIp/tcpPort/httpPort/faceThreshold/fingerThreshold/communicationMode`。
- `communicationMode` 只定义 MQTT/HTTP。

## 3. 用户明确的本地配置需求

HTTP 与 MQTT 在本部署中是不同服务器，因此 Android 本地保存：

```text
httpScheme + httpServerAddress + httpPort + httpBasePath
mqttScheme + mqttServerAddress + mqttPort
tcpServerAddress + tcpPort（旧版兼容）
```

这些是本机连接配置，不是 `/api/v1/device/config` 已定义的返回字段。服务端通用 `serverIp` 只记录为 `backendServerIp`，不自动覆盖任何本机地址。

## 4. 当前禁用或留空，等待确切资料

### 4.1 MQTT username

V4.1 没有定义。客户端只使用显式配置值；未配置时按“无 username”连接，不再尝试 `deviceCode/clientId`。

### 4.2 MQTT 地址、端口和 TLS

V4.1 配置响应没有 MQTT host、port、scheme。新安装默认留空，必须由实际部署配置；不会填入测试 IP、1883、48419、TCP 或 SSL 默认值。

### 4.3 HTTP/MQTT 协议 scheme

这是本机建立连接必需的本地字段，但后台没有下发字段。新安装留空，由实际部署选择，不推断 HTTP/HTTPS 或 TCP/SSL。

### 4.4 串口拓扑

串口文档只说明“从机地址=目标单板地址”，没有说明：

- `slotId` 是否等于从机地址；
- 100 个卡位是否有 100 个唯一地址；
- 分组是否重复 1～16 地址；
- 切组命令和响应归属。

因此自动轮询、逻辑卡位开门和一键弹卡当前禁用。不会恢复取模，也不会采用直接映射。

### 4.5 卡号解析

串口文档明确卡号为 15 字节 ASCII。当前只按 ASCII 解析；原始十六进制可用于本地诊断，但不称为“物理卡号”。

### 4.6 TAKE/RETURN 确认时点

后台文档定义了 TAKE/RETURN 事件字段，但没有说明单板开门 ACK 是否等于实际取还卡。当前开门 ACK 只记录为本地操作阶段，不发送 `cardEvent`，直到硬件状态转换规则得到确认。

### 4.7 下行时间窗口和崩溃恢复期限

文档要求 timestamp，但没有定义允许偏差、10分钟窗口或5分钟恢复期限。客户端不再按自定时间窗拒绝指令；仍使用 `msgId` 防止重复副作用。PROCESSING 的人工恢复协议待补。

## 5. 当前严格按文档接入的 HTTP 路径

```text
POST /api/v1/app-version/check
POST /api/v1/device/register
POST /api/v1/device/activate
POST /api/v1/device/verify
GET  /api/v1/device/config
GET  /api/v1/device/auth/status
POST /api/v1/device/login
POST /api/v1/device/heartbeat
POST /api/v1/device/status
POST /api/v1/employee/sync
POST /api/v1/employee/face/sync
POST /api/v1/employee/finger/sync
POST /api/v1/card/event
POST /api/v1/log/report
POST /api/v1/fault/report
POST /api/v1/statistics/report
POST /api/v1/device/selfcheck
POST /api/v1/device/batch-result
POST /api/v1/upgrade/status
POST /api/v1/employee
POST /api/v1/employee/face
GET  /api/v1/employee/face/registered
GET  /api/v1/firmware/{firmwareId}/download
POST /api/v1/face/upload
POST /api/v1/fingerprint/upload
POST /api/v1/logs/batch
```

“路径有文档”不等于“业务闭环已实现”。OTA、multipart 上传、批量日志、真实统计、员工双向编辑仍未完成。

## 6. 明确未作为接口发送的本地字段

以下字段只允许存在于 Android 本地操作/诊断，不进入 V4.1 wire payload：

```text
operationId
requestMsgId
physicalConfirmed
recovered
BOARD_ACKED
PHYSICAL_PENDING
```

除非后端文档正式增加这些字段。

## 7. 配置删除候选

以下字段在当前文档体系没有可执行调用方，继续留空，等待统一删除确认：

```text
ignoreTokenFetch
codeValueType
cardSuccessResponseType
faceRegistrationResponseEnabled
tcpDoorCommandResponseEnabled
secondaryDoorEnabled
usbCardReaderEnabled
startCharacter
endCharacter
serialExtra
baudExtra
toastDisplay
```

`serverAddress/apiBaseUrl/mqttBrokerUrl/cardParseMode` 仅保留一个迁移版本，不能作为新配置真相。
