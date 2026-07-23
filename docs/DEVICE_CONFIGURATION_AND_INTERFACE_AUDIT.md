# 设备配置与接口证据登记

更新时间：2026-07-23

本文件只记录有明确来源的事实、当前源码真实实现状态和仍缺少的外部契约。路径常量存在不等于业务完成。

## 1. 证据优先级

1. `docs/source-2026-07-02/Android客户端接口文档.md` V4.1：当前HTTP/MQTT接口依据。
2. `docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`：串口V1.5帧格式。
3. 用户明确决定：HTTP与MQTT服务器独立配置；人脸方案使用FaceAISDK，ArcSoft废弃；以`reference/motone-current`的设备实现为迁移主源。
4. `reference/motone-current`：CameraX/FaceAISDK、JNI串口和早期设备页面的实现参考，不作为后端契约。
5. 旧Markdown和PDF仅作历史参考，不能覆盖V4.1。

文档冲突或缺失时不自行解释：对应字段留空、动作禁用，并登记在`CONTRACT_EVIDENCE_REGISTER.md`。

## 2. 当前严格三层

```text
Vue UI
→ JsBridge / DeviceApplicationFacade
→ DeviceDataLayer / Store / Repository / Provisioning / DocumentedBackendService
→ BackendTransportManager / BackendHttpGateway / SerialConnectionManager / FaceAiManager
```

- Vue不持久化业务数据，也不生成员工ID、默认业务头像或设备归属。
- `DeviceCoreService`只创建、注入、启动和停止组件。
- `BackendTransportManager`、`BackendHttpGateway`和`SerialConnectionManager`不读取Android配置Repository，不执行业务编排。
- 设备注册、激活、配置和授权由`DeviceProvisioningManager`在Android数据/业务层编排。
- Markdown已定义的请求体由`DocumentedBackendService`校验并提交给HTTP Gateway。

## 3. 本机有效配置

### 串口

| 字段 | 当前用途 |
|---|---|
| `serialPort` | JNI串口节点，默认参考设备节点`/dev/ttyS5` |
| `baudRate` | V1.5串口波特率 |
| `totalCount` | 逻辑卡槽展示数量 |
| `singleGroupCount` | UI分组数量，不作为板地址 |
| `serialResponseTimeoutMs` | 已保留；逻辑地址未确认前不用于轮询 |
| `serialCommandGapMs` | 原始串口调试发送间隔配置 |
| `serialPollingIntervalMs` | 已保留；自动轮询当前禁用 |

### HTTP

```text
httpScheme + httpServerAddress + httpPort + httpBasePath
```

必须显式填写`http://`或`https://`对应scheme。没有地址或scheme时保持未配置，不补测试服务器。

### MQTT

```text
mqttScheme + mqttServerAddress + mqttPort
```

V4.1不下发MQTT host、port、scheme和username：

- host/port/scheme由本机部署配置；
- username只使用明确配置值，否则空username；
- `mqttPassword/signingKey/clientId`来自activate/verify响应。

### 旧TCP

```text
tcpServerAddress + tcpPort
```

仅保留旧部署兼容。V4.1的`communicationMode`只认MQTT/HTTP，服务端远程配置不能把模式改成TCP。

### FaceAISDK

- 使用`io.github.faceaisdk:Android:2026.06.25`；
- CameraX使用参考分支已有常驻相机与覆盖层流程；
- ArcSoft依赖、类、权限、密钥配置和jar已删除；
- 人脸阈值来自`faceThreshold/faceRecognitionThreshold`；
- 本机录入成功后先提交文档明确的`POST /api/v1/employee/face`，再写入FaceAISDK本地库和员工Map。

## 4. HTTP接口实现状态

### 4.1 已形成运行闭环

| 接口 | 当前触发与处理 |
|---|---|
| `POST /api/v1/app-version/check` | 启动Provisioning检查版本；强制更新时停止注册 |
| `POST /api/v1/device/register` | 无deviceToken/deviceCode时自动注册并保存 |
| `POST /api/v1/device/activate` | 未激活时调用；HTTP和MQTT模式都要求完成激活 |
| `POST /api/v1/device/verify` | activate返回registerCode且本机有激活码时验证 |
| `GET /api/v1/device/config` | 获取并只映射V4.1明确字段 |
| `GET /api/v1/device/auth/status` | 保存authorized/authorizedUntil/daysRemaining/features到Store |
| `POST /api/v1/device/login` | HTTP模式登录；返回token仅保存，不作为Bearer |
| `POST /api/v1/device/heartbeat` | HTTP模式定时心跳 |
| `POST /api/v1/employee/sync` | 分页、增量合并、deletedEmployeeIds |
| `POST /api/v1/employee/face/sync` | 分页、fetched/applied游标、FaceAISDK模板应用 |
| `POST /api/v1/employee/finger/sync` | 分页、fingerId主键增量缓存；没有员工级指纹硬件应用 |
| `POST /api/v1/device/status` | 仅上报已有真实更新时间的卡槽；串口拓扑未确认时无伪状态 |
| `POST /api/v1/fault/report` | 有真实卡槽故障变化时上报 |
| `POST /api/v1/device/selfcheck` | deviceSelfCheck下行触发自检上报 |
| `POST /api/v1/employee/face` | FaceAISDK本机录入自动提交；也有Facade显式入口 |

### 4.2 已有Android数据层业务入口

请求路径、方法、字段类型和必填校验已经按V4.1实现，但是否执行取决于页面、硬件或业务生产者：

| 接口 | Android入口 | 当前限制 |
|---|---|---|
| `POST /api/v1/employee` | `employee.upsert`；本机删除用update+status=1 | 当前员工页面尚未补完整新增/编辑表单 |
| `GET /api/v1/employee/face/registered` | `employee.face.registered` | 由需要对账的页面/任务调用 |
| `POST /api/v1/face/upload` | `face.uploadImage` | 只允许APP私有files/cache中的真实图片文件；当前相机流程默认不保存图片 |
| `POST /api/v1/fingerprint/upload` | `fingerprint.uploadFeature` | 缺外接指纹模块产生`fingerFeature/fingerIndex` |
| `POST /api/v1/logs/batch` | `logs.uploadBatch` | 缺Room/SQLite Outbox生产和ACK删除闭环 |
| `GET /api/v1/firmware/{firmwareId}/download` | `firmware.download` | 支持私有目录和Range续传；不代表已实现安装、校验或回滚 |
| `POST /api/v1/card/take` | `DeviceDataLayer.reportConfirmedTake` | 等待物理取卡确认规则后调用 |
| `POST /api/v1/card/return` | `DeviceDataLayer.reportConfirmedReturn` | 等待物理还卡确认规则后调用 |

### 4.3 仅有通信映射，缺真实业务生产者

| 接口 | 缺少内容 |
|---|---|
| `POST /api/v1/card/event` | TAKE/RETURN物理完成时点 |
| `POST /api/v1/log/report` | 明确日志上传选择和持久化队列 |
| `POST /api/v1/statistics/report` | 真实取卡/还卡统计来源 |
| `POST /api/v1/device/auth/change` | 授权变更的本机业务触发规则 |
| `POST /api/v1/upgrade/status` | OTA状态机 |
| `POST /api/v1/device/batch-result` | 批量操作任务与子结果模型 |

### 4.4 明确未完成

- `firmwareUpgrade/cancelUpgrade`能识别并按失败响应返回，但没有下载→校验→安装→重启恢复→回滚状态机；
- 旧`/api/getToken`、`/api/takeCard`、`/api/takeSuccess`、`/api/saveCard`不接入新客户端；
- HTTP模式没有Markdown定义的下行接口，所以不能接收远程开门。

## 5. MQTT状态

- 上行统一封装`msgId/cmd/timestamp/deviceCode/sign/data`；
- 下行只读取`msgId/cmd/timestamp/data`，不要求`sign/deviceCode`；
- 响应复用服务端原始`msgId`；
- 只有明确`loginResp code=0`后进入`AUTHENTICATED`；
- loginResp的token保存为运行Token记录，但不用于HTTP Bearer；
- 下行只处理V4.1列出的十种命令；
- 日志开关命令无终端响应；
- 未定义timestamp容差，不按自定窗口拒绝消息。

## 6. 串口状态

已迁入参考分支的：

- JNI `SerialPort.c`；
- 阻塞式ReadThread；
- `/dev`串口节点枚举；
- 设备权限检查与诊断；
- V1.5 CRC帧解码；
- 15字节ASCII卡号解析。

仍然禁用：

- 自动轮询；
- `slotId`逻辑开门/查询/读版本；
- 一键弹卡；
- boardAddress写入卡槽Map。

原因是Markdown和参考分支都没有证明100卡槽的实际从机地址/分组/切组关系。参考分支曾直接使用1～100地址，但这不能作为硬件契约。

## 7. 不进入外部报文的本地字段

```text
operationId
requestMsgId
BOARD_ACKED
PHYSICAL_PENDING
physicalConfirmed
recovered
```

它们可用于Android本地状态和诊断，未经后端文档确认不得进入HTTP/MQTT payload。

## 8. 配置删除候选

以下字段在当前Markdown与源码中没有有效消费方，继续留空，等待用户统一确认删除：

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

迁移字段：

```text
serverAddress
apiBaseUrl
mqttBrokerUrl
cardParseMode
```

只允许用于一个版本的旧配置迁移，不能重新成为正式真相。

## 9. 验证口径

自动验证包括：

- 三层依赖门禁；
- FaceAISDK和JNI串口依赖检查；
- H5真实构建；
- Android JVM单元测试；
- Debug APK构建；
- APK内`libSerialPort.so`和H5 assets检查。

当前没有连接rk3568_r真机，所以不能把“APK生成成功”写成“已在目标设备安装和功能联调成功”。
