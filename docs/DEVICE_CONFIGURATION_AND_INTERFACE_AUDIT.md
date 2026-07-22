# 设备配置与 V4.1 接口接入审计

更新时间：2026-07-23

本文以以下资料为准：

- `docs/source-2026-07-02/Android客户端接口文档.md`（V4.1，最新后台契约）；
- `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`；
- `docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`；
- `docs/source-2026-07-02/工作卡柜APP（JW-S30-100F人脸）使用说明 V-1.7.19.pdf`；
- 当前 Android、uni-app 和串口实现。

## 1. 核心结论

HTTP、MQTT 和兼容 TCP 必须分别配置，不能继续共用一个“服务器IP”。

```text
HTTP：注册、版本检查、激活、配置、授权状态、登录、心跳、同步、下载、补偿上报
MQTT：实时双向指令、响应、心跳、状态和事件
TCP：旧版兼容通道，不是新 V4.1 的默认方案
```

当前配置模型使用：

```text
httpScheme + httpServerAddress + httpPort + httpBasePath
mqttScheme + mqttServerAddress + mqttPort
tcpServerAddress + tcpPort
backendTransport = MQTT | HTTP | TCP
```

旧字段 `serverAddress`、`apiBaseUrl` 和 `mqttBrokerUrl` 只作为 Android 内部迁移/派生字段，不再显示为一个共用服务器配置。

## 2. 已接通并应保留的配置

| 配置项 | Android 字段 | 状态 | 实际作用 |
|---|---|---|---|
| 串口设备 | `serialPort` | 已接通 | 打开真实 `/dev/tty*` 设备 |
| 波特率 | `baudRate` | 已接通 | 串口通信；V1.5 默认 57600 |
| 数据位/停止位/校验 | `serialDataBits=8`、`serialStopBits=1`、`serialParity=NONE` | 固定生效 | 协议固定 8N1，不需要普通配置项 |
| 卡位总数 | `totalCount` | 已接通 | Android 卡槽 Map、轮询范围、UI 数量 |
| 分组大小 | `singleGroupCount` | 部分接通 | 仅用于 UI/批次分组，**不再用于取模映射串口地址** |
| 自动轮询 | `serialPollingEnabled` | 已接通 | 启停串口状态轮询 |
| 轮询间隔 | `serialPollingIntervalMs` | 已接通 | 串口轮询调度间隔 |
| 单板响应超时 | `serialResponseTimeoutMs` | 已接通，隐藏高级项 | 命令/轮询超时 |
| 串口命令间隔 | `serialCommandGapMs` | 已接通，隐藏高级项 | 批量指令间隔 |
| 卡号解析 | `cardNumberMode` | 已接通 | `VISIBLE` 读取 15 字节 ASCII；`PHYSICAL` 返回原始十六进制 |
| HTTP 协议 | `httpScheme` | 已接通 | HTTP/HTTPS |
| HTTP 域名/IP | `httpServerAddress` | 已接通 | 独立 HTTP 服务地址 |
| HTTP 端口 | `httpPort` | 已接通 | 独立 HTTP 端口 |
| HTTP 基础路径 | `httpBasePath` | 已接通 | 例如 `/prod`；通常留空 |
| 实时通信方式 | `backendTransport` | 已接通 | MQTT、HTTP、兼容 TCP 三选一 |
| MQTT 协议 | `mqttScheme` | 已接通 | TCP/SSL |
| MQTT 域名/IP | `mqttServerAddress` | 已接通 | 与 HTTP 完全独立 |
| MQTT 端口 | `mqttPort` | 已接通 | 与 HTTP 端口独立 |
| TCP 域名/IP | `tcpServerAddress` | 已接通，仅兼容模式 | 旧 TCP 部署使用 |
| TCP 端口 | `tcpPort` | 已接通，仅兼容模式 | 旧 TCP 部署使用 |
| 设备 ID | `deviceId` / `machineId` | 已接通 | 注册前本机标识；可由 AndroidID 生成 |
| 设备编码 | `deviceCode` | 已接通，只读 | 注册后服务端下发 |
| 激活码 | `activationCode` | 已接通 | 待激活设备执行 verify |
| 人脸阈值 | `faceRecognitionThreshold` | 已接通 | 虹软 1:N 比对阈值 |
| 摄像头旋转 | `cameraRotation` | 已接通 | 0/90/180/270 度预览方向 |
| 启动同步 | `startupDataSyncEnabled` | 已接通，隐藏高级项 | 后端认证后主动同步 |
| 人脸同步标志 | `faceSyncIncludeFlags` | 已接通，隐藏高级项 | 控制人脸同步返回内容 |
| Token 验证 | 无可关闭开关 | 固定启用 | V4.1 HTTP Bearer 和 MQTT HMAC 均为协议要求 |

## 3. 当前必须保留为空的配置

这些字段还没有足够的硬件或后台契约。当前保存为空，不允许通过默认值伪装成已接通。

| 配置项 | 当前字段 | 留空原因 |
|---|---|---|
| 轮询方式 | `pollingMode` | 文档只有“分组/逐个”名称，没有组选择命令、组上下文或响应归属规则 |
| 指纹识别阈值 | `fingerRecognitionThreshold` | Android 系统生物认证没有员工级分值；外接指纹 SDK 未提供 |
| 指纹识别开关 | `fingerprintEnabled` | 员工级指纹硬件和 SDK 未接入 |
| 忽略 Token 获取 | `ignoreTokenFetch` | 与 V4.1 强制 Token 验证冲突，保留空值待删除 |
| 码值类型 | `codeValueType` | 与 `cardNumberMode` 重复且无独立协议语义 |
| 成功响应类型 | `cardSuccessResponseType` | 后端响应契约固定，不应由本机随意选择 |
| 升级间隔 | `boardUpgradeIntervalMs` | OTA 下载、校验、安装状态机尚未实现 |
| 人脸注册响应开关 | `faceRegistrationResponseEnabled` | V4.1 没有该客户端配置项 |
| TCP 开门响应开关 | `tcpDoorCommandResponseEnabled` | 指令响应属于协议要求，不应由开关取消 |
| 次门开关 | `secondaryDoorEnabled` | 当前硬件协议没有第二门字段 |
| USB 读卡器 | `usbCardReaderEnabled` | 当前只接串口单板读卡，未提供 USB 读卡器协议 |
| 起始/结束字符 | `startCharacter` / `endCharacter` | V1.5 是固定 `DD CC + length + CRC` 二进制帧，不使用文本分隔符 |
| 串口/波特率扩展 | `serialExtra` / `baudExtra` | 与正式字段重复，无调用方 |
| Toast 展示策略 | `toastDisplay` | 属于纯 UI 行为，不应进入设备通信配置 |

## 4. 建议一次性删除的字段

在确认没有旧版后台或旧设备继续依赖后，可一次性删除：

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

`boardUpgradeIntervalMs` 建议等 OTA 设计完成后决定是删除还是改成正式的分片/重试参数。

以下旧字段不在 UI 展示，但暂时不能立刻删，因为需要完成一次存档迁移：

```text
serverAddress
apiBaseUrl
mqttBrokerUrl
cardParseMode
```

迁移稳定一个版本后，可以只保留新的规范字段。

## 5. 必须补充的外部配置/协议

### 5.1 后端 `/api/v1/device/config` 缺少独立端点字段

当前 V4.1 只给出通用 `serverIp`、`tcpPort`、`httpPort`。这不足以描述 HTTP 与 MQTT 位于不同服务器的部署。

后台响应应增加：

```json
{
  "httpScheme": "https",
  "httpServerAddress": "api.example.com",
  "httpPort": 443,
  "httpBasePath": "",
  "mqttScheme": "ssl",
  "mqttServerAddress": "mqtt.example.com",
  "mqttPort": 8883,
  "tcpServerAddress": "legacy.example.com",
  "tcpPort": 9009
}
```

Android 当前兼容以下别名：

```text
httpHost / httpBaseUrl
mqttHost / mqttBrokerUrl
tcpHost
```

只有服务端明确返回某个通道字段时，才覆盖该通道；通用 `serverIp` 不再同时覆盖 HTTP、MQTT 和 TCP。

### 5.2 MQTT username 契约缺失

激活接口明确给出 `mqttPassword`、`signingKey`、`clientId`，但没有定义 MQTT username。当前保留兼容尝试：

```text
已配置 mqttUsername → deviceCode → clientId → 空 username
```

后台应明确唯一 username 规则，并最好在激活响应中返回 `mqttUsername`。

### 5.3 HTTP 模式没有下行命令接口

V4.1 HTTP 定义了登录、心跳和上报，但没有 HTTP 长轮询、SSE、WebSocket 或“拉取待执行指令”接口。因此：

- HTTP 模式可登录、心跳、状态和业务上报；
- HTTP 模式不能接收实时 `remoteOpen` 等下行命令；
- 需要远程控制时必须选择 MQTT，或由后台新增明确的 HTTP 下行机制。

### 5.4 串口分组/切组协议缺失

V1.5 帧中只有一个字节的从机地址，没有提供：

```text
组选择命令
当前组上下文
重复地址如何归属到逻辑卡位
分组轮询的响应映射规则
```

当前安全实现按 1～255 直接从机地址轮询，`singleGroupCount` 只作 UI 分组。若实际硬件每组重复 1～16 地址，必须补充切组命令或显式 `slotId -> groupId + boardAddress` 映射后再启用分组轮询。

### 5.5 员工级指纹 SDK 缺失

Android `BiometricPrompt` 只能证明“本设备某一枚系统指纹通过”，无法识别具体员工。需要提供：

```text
外接指纹模块型号
串口/USB 协议
模板注册接口
模板ID
1:N匹配接口
阈值含义
删除/同步接口
```

在此之前，UI 不得把系统指纹成功显示为员工 `fingerprintRegistered=true`。

## 6. V4.1 HTTP 接口接入状态

| 接口 | 当前状态 | 说明 |
|---|---|---|
| `POST /api/v1/app-version/check` | 已接通 | 启动前版本检查，支持强制更新阻断 |
| `POST /api/v1/device/register` | 已接通 | 获取 `deviceToken/deviceCode` |
| `POST /api/v1/device/activate` | 已接通 | 激活并获取 MQTT 凭证 |
| `POST /api/v1/device/verify` | 已接通 | 激活码验证 |
| `GET /api/v1/device/config` | 已接通 | 启动和 `syncConfig` 拉取并写回 Android 配置 |
| `GET /api/v1/device/auth/status` | 已接通 | 写入 `deviceAuthorization` 状态 |
| `POST /api/v1/device/login` | 已接通 | HTTP 模式登录，设备 Token 与运行 Token 分离 |
| `POST /api/v1/device/heartbeat` | 已接通 | HTTP 模式定时心跳 |
| `POST /api/v1/device/status` | 已接通 | HTTP 模式状态上报；MQTT失败时可补偿 |
| `POST /api/v1/card/event` | 已接通但未最终确认 | 当前仍带 `physicalConfirmed=false`，等待物理确认契约 |
| `POST /api/v1/employee/sync` | 已接通 | 增量 upsert + `deletedEmployeeIds`，不再覆盖全表 |
| `POST /api/v1/employee/face/sync` | 已接通 | fetched/applied 游标分离，模板失败不推进 applied |
| `POST /api/v1/employee/finger/sync` | 数据缓存已接通 | 外接指纹硬件应用未接通 |
| `POST /api/v1/fault/report` | 已接通 | 相同故障去重并发送恢复边沿 |
| `POST /api/v1/log/report` | 基础单条上报已接通 | 可靠批量 Outbox 未完成 |
| `POST /api/v1/device/selfcheck` | 已接通 | 发送脱敏运行快照 |
| `POST /api/v1/device/batch-result` | HTTP 映射已接通 | 真实持久化批次状态仍需业务实现 |
| `POST /api/v1/upgrade/status` | HTTP 映射已接通 | OTA 本体未实现 |
| `POST /api/v1/employee` | 端点已登记，缺业务入口 | 当前员工以后台同步为主 |
| `POST /api/v1/employee/face` | 端点已登记，缺业务入口 | 需要确定本机录入后是否必须反向上传 |
| `POST /api/v1/employee/face/registered` | 端点已登记，缺业务入口 | 需要确认字段和调用时点 |
| `POST /api/v1/logs/batch` | 未接通 | 需要 Room Outbox、批量 ACK 与重试契约 |
| `POST /api/v1/face/upload` | 未接通 | multipart 上传与隐私策略未实现 |
| `POST /api/v1/fingerprint/upload` | 未接通 | 外接指纹硬件和 multipart 契约缺失 |
| `GET /api/v1/firmware/download/{id}` | 下载工具可用，OTA未接通 | 缺少 hash/签名/安装/回滚状态机 |
| `POST /api/v1/statistics/report` | 暂不发送假数据 | 需要真实取还卡持久化计数后接入 |

## 7. MQTT 接入状态

已接通：

```text
HTTP获取激活凭证
→ MQTT连接
→ 订阅下行/响应Topic
→ login
→ 明确 code=0/200 的 loginResp
→ AUTHENTICATED
→ heartbeat
→ 下行指令进入 msgId 幂等和统一业务入口
```

V4.1 下行格式不要求 `sign` 和 `deviceCode`，Android 不再因此拒绝合法命令；如果旧服务额外发送 `deviceCode`，存在但不匹配时仍会拒绝。

上行 MQTT 继续按照文档生成：

```text
msgId + cmd + timestamp + deviceCode + data + HMAC-SHA256 sign
```

## 8. 仍需独立完成的功能闭环

这些不是配置字段问题，不能通过增加开关解决：

1. `BOARD_ACKED -> PHYSICAL_PENDING -> PHYSICAL_CONFIRMED` 取还卡二阶段确认；
2. Room/SQLite 诊断和业务 Outbox、后台 ACK、断网补传；
3. 真实取卡/还卡统计；
4. OTA 下载、hash/签名验证、安装、回滚和重启恢复；
5. 员工级外接指纹；
6. 若硬件地址重复，补充明确分组/切组串口协议。

在上述契约补齐前，相关配置继续留空或显示为“未接入”，不得用默认值制造已完成功能的假象。
