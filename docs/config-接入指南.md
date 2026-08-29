# Config 接入指南

> 版本：V1.4 | 更新日期：2026-07-25
>
> 本文档面向客户端（Android）接入开发，详细说明 `GET /api/v1/device/config` 返回的全部 31 个配置字段的使用场景、默认值及选项定义。

---

## 目录

1. [默认值填充机制](#1-默认值填充机制)
2. [串口/硬件参数](#2-串口硬件参数)
3. [通信参数](#3-通信参数)
4. [人脸/指纹识别参数](#4-人脸指纹识别参数)
5. [摄像头参数](#5-摄像头参数)
6. [可修改参数清单（App 端）](#6-可修改参数清单app-端)
7. [废弃字段](#7-废弃字段)

---

## 1. 默认值填充机制

### 1.1 默认模板记录

系统以数据库中 `device_id = 0` 的记录作为**全局默认配置模板**：

- 首次调用时，如果 `device_id = 0` 的记录不存在，系统会自动创建一条，所有字段使用代码硬编码的默认值。
- 后续可通过管理后台修改 `device_id = 0` 的记录来**统一调整全局默认值**，无需改代码。
- 每个设备的配置获取/保存时，系统会以模板中的值填充目标配置中为 `null` 的字段。

### 1.2 字段优先级

```
DB 中设备专属配置（非 null） > deviceId=0 模板配置 > 代码硬编码默认值
```

---

## 2. 串口/硬件参数

### 2.1 `baudRate` — 串口波特率

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `57600` |
| **可选项** | 标准波特率：`9600`、`19200`、`38400`、`57600`、`115200` |
| **App 可修改** | 是 |
| **使用场景** | 终端与硬件单板之间的串口通信速率。需与单板固件配置一致，否则通信失败。 |

---

### 2.2 `groupSize` — 单组卡槽数量

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `16` |
| **可选项** | 任意正整数，常见为 `8`、`16` |
| **App 可修改** | 是 |
| **使用场景** | 卡槽物理分组大小。终端按此值对卡槽进行分组轮询，影响 `pollingMode=GROUP` 时的轮询粒度。 |

---

### 2.3 `totalSlots` — 卡槽总数

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `100` |
| **可选项** | 任意正整数 |
| **App 可修改** | 是 |
| **使用场景** | 卡柜物理卡槽总数。终端据此渲染卡槽网格 UI、计算轮询范围。实际值取决于硬件型号（如 100 位、120 位等）。 |

---

### 2.4 `serialPort` — 串口设备路径

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `/dev/ttyS5` |
| **可选项** | Linux: `/dev/ttyS0`~`/dev/ttyS5`、`/dev/ttyUSB0` 等；Android: `/dev/ttyAMA0`、`/dev/ttyS1` 等 |
| **App 可修改** | 是 |
| **使用场景** | 终端打开该串口设备文件与硬件单板通信。不同主板的串口映射路径不同，需根据实际硬件调整。 |

---

### 2.5 `serialPollEnabled` — 串口轮询开关

| 属性 | 值 |
|------|-----|
| **类型** | Boolean |
| **默认值** | `true` |
| **App 可修改** | 是 |
| **使用场景** | 控制终端是否周期性通过串口轮询查询每个卡槽的实时状态（有卡/无卡）。关闭后终端不再主动轮询硬件，但仍可通过单次指令查询。调试或维护场景下可临时关闭以降低串口负载。 |

---

### 2.6 `serialPollInterval` — 串口轮询间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `5000`（5 秒） |
| **可选项** | 建议范围 `1000` ~ `30000` |
| **App 可修改** | 是 |
| **使用场景** | 终端通过串口轮序查询每个卡槽状态的间隔时间。值越小，卡槽状态变更感知越及时，但串口负载和 CPU 占用越高。**替代了旧字段 `pollingInterval`。** |

---

### 2.7 `serialResponseTimeout` — 串口单板响应超时（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `700` |
| **可选项** | 建议范围 `200` ~ `2000` |
| **App 可修改** | 是 |
| **使用场景** | 终端通过串口发送指令后，等待单板回复的最大时间。超时后判定该次轮询失败，跳过当前卡槽继续下一个。值过小会导致正常响应被误判超时；值过大会导致整体轮询变慢。 |

---

### 2.8 `slotStatusPushInterval` — 卡槽状态 MQTT 推送间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `60000`（1 分钟） |
| **可选项** | 建议范围 `10000` ~ `300000` |
| **App 可修改** | 是 |
| **使用场景** | 终端通过 MQTT `statusReport` 上报卡槽状态变更的最小间隔。与 `serialPollInterval`（控制串口查询频率）不同，此字段控制终端向服务端**推送**状态的频率。两者配合：轮询发现变更 → 按此间隔节流推送。 |

---

### 2.8.1 `mqttStatusReportInterval` — MQTT 卡状态上报调度间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `300000`（5 分钟） |
| **可选项** | 正整数，客户端最小按 `1000` 执行 |
| **App 可修改** | 是 |
| **使用场景** | 后端新增的 `statusReport` 调度主字段。客户端 `scheduleStatusReport` 优先使用此值；仅当字段不可用时回退 `slotStatusPushInterval`。该间隔只节流已有卡槽状态事件触发的上报，不会在 Vue 新建硬件轮询。完整配置响应继续同时携带两个字段以兼容旧版本客户端。 |

---

### 2.9 `pollingMode` — 卡槽轮询方式

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"GROUP"` |
| **App 可修改** | 是 |

**选项定义**：

| 值 | 含义 | 说明 |
|----|------|------|
| `GROUP` | 分组轮询 | 将卡槽按 `groupSize` 分组，终端一次指令查询整组卡槽状态。适用于单板支持分组查询的硬件。效率高，推荐。 |
| `SINGLE` | 单槽轮询 | 逐槽查询，一次只查一个卡槽。兼容性好，但速度慢。适用于不支持分组查询的旧版硬件或调试场景。 |

---

### 2.10 `slotSortDirection` — 卡槽分组排序规则

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"HORIZONTAL"` |
| **App 可修改** | 是 |

**选项定义**：

| 值 | 含义 | 说明 |
|----|------|------|
| `HORIZONTAL` | 水平排序 | 卡槽先排同行再换行。即 1→左, 2→右, 3→下一行左… 对应卡槽物理排列为横向阅读顺序。 |
| `VERTICAL` | 垂直排序 | 卡槽先排同列再换列。即 1→上, 2→下, 3→下一列上… 对应卡槽物理排列为纵向阅读顺序。 |

---

## 3. 通信参数

### 3.1 `communicationMode` — 通信方式

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"MQTT"` |
| **App 可修改** | 是 |

**选项定义**：

| 值 | 含义 | 说明 |
|----|------|------|
| `MQTT` | MQTT 协议 | 长连接，双向推送。当前主通信通道，服务端推送指令、终端上报事件均走 MQTT。**推荐。** |
| `HTTP` | HTTP 协议 | 短连接，请求-响应。用作 MQTT 的降级通道，或大数据量传输场景。 |
| `BOTH` | 双通道 | MQTT + HTTP 同时可用。实时指令走 MQTT，大文件传输走 HTTP。 |

---

### 3.2 `httpHost` — Gateway HTTP 服务地址

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"127.0.0.1"`（本地） |
| **可选项** | IP 地址或域名，如 `"192.168.1.100"`、`"card-gateway.example.com"` |
| **App 可修改** | 是 |
| **使用场景** | 终端发起 HTTP 请求时使用的 Gateway 服务地址。配合 `httpPort` 拼接为完整 HTTP Base URL。**替代了旧字段 `serverIp` 的部分功能。** |

---

### 3.3 `httpPort` — HTTP 端口

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `8082` |
| **可选项** | 任意合法端口号，常见 `8080`、`8082`、`9090` |
| **App 可修改** | 是 |
| **使用场景** | Gateway HTTP 服务的监听端口。终端拼接 `http://{httpHost}:{httpPort}` 作为所有 HTTP API 请求的 Base URL。 |

---

### 3.4 `mqttHost` — EMQX MQTT Broker 地址

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"127.0.0.1"`（本地） |
| **可选项** | IP 地址或域名 |
| **App 可修改** | 是 |
| **使用场景** | 终端建立 MQTT 连接的目标 Broker 地址。配合 `mqttPort` 拼接为完整的 MQTT 连接 URL。**替代了旧字段 `serverIp` 的部分功能 + `tcpPort`。** |

---

### 3.5 `mqttPort` — MQTT Broker 端口

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `1883` |
| **可选项** | TCP: `1883`；WebSocket: `8083`；TLS: `8883` |
| **App 可修改** | 是 |
| **使用场景** | EMQX MQTT Broker 监听端口。终端据此建立 MQTT 连接。**替代了旧字段 `tcpPort`。** |

---

### 3.6 `mqttHeartbeatInterval` — MQTT 心跳发送间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `60000`（60 秒） |
| **可选项** | 建议范围 `30000` ~ `300000` |
| **App 可修改** | 是 |
| **使用场景** | 终端 login 成功后，按此周期通过 MQTT `card/{deviceId}/heartbeat` 或 HTTP `POST /api/v1/device/heartbeat` 发送心跳。服务端通过心跳判定设备在线状态。间隔过短增加服务端负载，过长影响离线检测及时性。 |

---

### 3.7 `mqttReconnectInitialInterval` — MQTT 初次重连间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `1000`（1 秒） |
| **可选项** | 建议范围 `500` ~ `5000` |
| **App 可修改** | 是 |
| **使用场景** | MQTT 断线后首次重连的等待时间。与 `mqttReconnectMaxInterval` 配合使用指数退避算法：第 1 次等 1s，第 2 次等 2s，第 3 次等 4s… 直到达到最大值。 |

---

### 3.8 `mqttReconnectMaxInterval` — MQTT 最大重连间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `60000`（60 秒） |
| **可选项** | 建议范围 `30000` ~ `300000` |
| **App 可修改** | 是 |
| **使用场景** | MQTT 指数退避重连的上限。无论经过多少次重试，单次等待时间不会超过此值。避免在网络长期不可用时无限等待。 |

---

## 4. 人脸/指纹识别参数

### 4.1 `faceThreshold` — 人脸识别阈值

| 属性 | 值 |
|------|-----|
| **类型** | BigDecimal |
| **默认值** | `0.8` |
| **可选项** | `0.0` ~ `1.0`，值越高匹配要求越严格 |
| **App 可修改** | 是 |
| **使用场景** | 1:N 人脸搜索时，匹配分数低于此阈值的结果将被过滤，不视为匹配。降低阈值可提高识别通过率（但误识别风险增加）；提高阈值可减少误识别（但拒真率增加）。推荐范围 `0.6` ~ `0.9`。 |

---

### 4.2 `fingerThreshold` — 指纹识别阈值

| 属性 | 值 |
|------|-----|
| **类型** | BigDecimal |
| **默认值** | `0.8` |
| **可选项** | `0.0` ~ `1.0` |
| **App 可修改** | 是 |
| **使用场景** | 指纹 1:N 匹配分数下限，低于此阈值的匹配结果会被拒绝。逻辑与 `faceThreshold` 类似。推荐范围 `0.7` ~ `0.9`。 |

---

### 4.3 `fingerEnabled` — 指纹识别开关

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"0"`（关闭，"1" 开启） |
| **App 可修改** | 是 |
| **使用场景** | 控制是否启用指纹识别功能。部分型号硬件没有指纹模块，应关闭此开关。启用后终端在取/还卡流程中增加指纹核验步骤。 |

---

### 4.4 `faceRecognitionTimeout` — 人脸识别超时（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `30000`（30 秒） |
| **可选项** | 建议范围 `10000` ~ `60000` |
| **App 可修改** | 是 |
| **使用场景** | 单次人脸识别操作的最长持续时间。从打开摄像头开始计时，超时后提示用户重试。需考虑用户走到设备前、对准摄像头的时间。 |

---

### 4.5 `searchTimeout` — 人脸搜索超时（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `15000`（15 秒） |
| **可选项** | 建议范围 `5000` ~ `30000` |
| **App 可修改** | 是 |
| **使用场景** | 1:N 人脸搜索（匹配数据库中人脸库）的最长持续时间。与 `faceRecognitionTimeout` 的关系：`searchTimeout` 是识别流程中"匹配搜索"这个子阶段的上限，`faceRecognitionTimeout` 是整个识别流程的总上限。`searchTimeout` 应 ≤ `faceRecognitionTimeout`。 |

---

### 4.6 `searchIntervalTime` — 搜索结果间隔（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `3000`（3 秒） |
| **可选项** | 建议范围 `1000` ~ `10000` |
| **App 可修改** | 是 |
| **使用场景** | 连续两次匹配结果上报之间的最小时间间隔。防止人脸搜索过程中同一张脸被重复识别并频繁上报，起到去抖作用。 |

---

### 4.7 `needFaceLiveness` — 静默活体检测开关

| 属性 | 值 |
|------|-----|
| **类型** | Boolean |
| **默认值** | `false` |
| **App 可修改** | 是 |
| **使用场景** | 是否在人脸识别过程中启用静默活体检测（无需用户做动作，如眨眼、张嘴）。开启后可以防止照片/视频攻击，但会增加识别耗时和 CPU 占用。推荐在生产环境开启。 |

---

### 4.8 `captureTimeout` — 人脸录入超时（ms）

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `8000`（8 秒） |
| **可选项** | 建议范围 `5000` ~ `15000` |
| **App 可修改** | 是 |
| **使用场景** | 人脸录入（注册新面孔）时，拍照后等待检测结果的超时。与 `faceRecognitionTimeout` 区分：`captureTimeout` 用于**录入**（1 次拍摄），`faceRecognitionTimeout` 用于**识别**（1:N 搜索）。 |

---

## 5. 摄像头参数

### 5.1 `cameraFacing` — 摄像头方向

| 属性 | 值 |
|------|-----|
| **类型** | String |
| **默认值** | `"front"` |
| **App 可修改** | 是 |

**选项定义**：

| 值 | 含义 | CameraX / Camera2 常量 |
|----|------|------------------------|
| `"front"` | 前置摄像头 | `CameraSelector.LENS_FACING_FRONT` |
| `"back"` | 后置摄像头 | `CameraSelector.LENS_FACING_BACK` |

---

### 5.2 `cameraMirror` — 预览镜像

| 属性 | 值 |
|------|-----|
| **类型** | Boolean |
| **默认值** | `true`（开启） |
| **App 可修改** | 是 |
| **使用场景** | 前置摄像头预览时是否做水平镜像翻转。开启后用户看到的是"照镜子"效果（左右对调），符合自拍习惯。后置摄像头通常设为 `false`。 |

---

### 5.3 `cameraRotation` — 传感器旋转补偿角度

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `0` |
| **可选项** | `0`、`90`、`180`、`270` |
| **App 可修改** | 是 |
| **使用场景** | 部分 Android 设备的摄像头传感器物理安装方向与屏幕方向不一致，需要旋转补偿。值为传感器需要顺时针旋转的度数，使预览画面与屏幕方向对齐。大多数现代平板为 `0` 或 `270`。 |

---

### 5.4 `cameraFrameWidth` — 帧分辨率宽

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `640` |
| **可选项** | 常见值：`640`、`1280`、`1920` |
| **App 可修改** | 是 |
| **使用场景** | 传给人脸分析器（Analyzer）的帧宽度。分辨率越高识别效果越好，但 CPU/GPU 负载越大。推荐在性能允许的情况下使用 `1280`。 |

---

### 5.5 `cameraFrameHeight` — 帧分辨率高

| 属性 | 值 |
|------|-----|
| **类型** | Integer |
| **默认值** | `480` |
| **可选项** | 常见值：`480`、`720`、`1080` |
| **App 可修改** | 是 |
| **使用场景** | 与 `cameraFrameWidth` 配对使用，决定传给分析器的帧尺寸。配合典型配置：`640x480`（低负载）、`1280x720`（均衡）、`1920x1080`（高精度）。 |

---

## 6. 全部 31 个可配置参数

全部参数均支持通过 `POST /api/v1/device/config` 和 `PUT /system/deviceConfig` 两方修改，不限 App 或管理后台。

**增量更新规则**：请求体中为 `null` 的字段不覆盖已有值；非 `null` 字段覆盖对应的数据库列。

**更新者记录**：
- App 修改：`update_by` = 设备号（`deviceCode`）
- 管理后台修改：`update_by` = 登录用户名
- 每次修改均记录 `update_time` 为当前时间

| # | 字段 | 类型 | 默认值 | 所属类别 |
|---|------|------|--------|----------|
| 1 | `cameraFacing` | String | `"front"` | 摄像头 |
| 2 | `cameraMirror` | Boolean | `true` | 摄像头 |
| 3 | `cameraRotation` | Integer | `0` | 摄像头 |
| 4 | `cameraFrameWidth` | Integer | `640` | 摄像头 |
| 5 | `cameraFrameHeight` | Integer | `480` | 摄像头 |
| 6 | `faceThreshold` | BigDecimal | `0.8` | 识别 |
| 7 | `fingerThreshold` | BigDecimal | `0.8` | 识别 |
| 8 | `faceRecognitionTimeout` | Integer | `30000` | 识别 |
| 9 | `searchTimeout` | Integer | `15000` | 识别 |
| 10 | `searchIntervalTime` | Integer | `3000` | 识别 |
| 11 | `needFaceLiveness` | Boolean | `false` | 识别 |
| 12 | `captureTimeout` | Integer | `8000` | 识别 |
| 13 | `fingerEnabled` | String | `"0"` | 识别 |
| 14 | `serialPollEnabled` | Boolean | `true` | 串口 |
| 15 | `serialPollInterval` | Integer | `5000` | 串口 |
| 16 | `serialResponseTimeout` | Integer | `700` | 串口 |
| 17 | `pollingMode` | String | `"GROUP"` | 串口 |
| 18 | `slotStatusPushInterval` | Integer | `60000` | 其他 |
| 19 | `mqttHeartbeatInterval` | Integer | `60000` | 其他 |
| 20 | `slotSortDirection` | String | `"HORIZONTAL"` | 其他 |
| 21 | `serialPort` | String | `"/dev/ttyS5"` | 串口 |
| 22 | `baudRate` | Integer | `57600` | 串口 |
| 23 | `groupSize` | Integer | `16` | 串口 |
| 24 | `totalSlots` | Integer | `100` | 串口 |
| 25 | `communicationMode` | String | `"MQTT"` | 通信 |
| 26 | `httpHost` | String | `"127.0.0.1"` | 通信 |
| 27 | `httpPort` | Integer | `8082` | 通信 |
| 28 | `mqttHost` | String | `"127.0.0.1"` | 通信 |
| 29 | `mqttPort` | Integer | `1883` | 通信 |
| 30 | `mqttReconnectInitialInterval` | Integer | `1000` | 通信 |
| 31 | `mqttReconnectMaxInterval` | Integer | `60000` | 通信 |
| 32 | `mqttStatusReportInterval` | Integer | `300000` | 其他 |

---

## 7. 废弃字段

以下字段已从数据库表、Java 实体、Mapper XML 和 API 响应中**完全移除**，客户端不应再依赖：

| 旧字段 | 替代方案 | 说明 |
|--------|----------|------|
| `pollingInterval` | `serialPollInterval` | 旧字段语义模糊，实际控制串口轮询间隔 |
| `serverIp` | 拆分为 `httpHost` + `mqttHost` | 旧字段无法区分 HTTP 和 MQTT 地址 |
| `tcpPort` | `mqttPort` | 旧字段名称有歧义，实际就是 MQTT 端口 |
| `tokenEnabled` | — | 已移除，不再使用 |

> **Migration SQL**: `sql/biz_device_config_v14_drop_deprecated.sql` 用于在已有数据库中删除这 3 列。

---

## 附录 A：完整字段速查表

| # | 字段 | 类型 | 默认值 | 类别 |
|---|------|------|--------|------|
| 1 | `baudRate` | Integer | `57600` | 串口/硬件 |
| 2 | `groupSize` | Integer | `16` | 串口/硬件 |
| 3 | `totalSlots` | Integer | `100` | 串口/硬件 |
| 4 | `serialPort` | String | `"/dev/ttyS5"` | 串口/硬件 |
| 5 | `serialPollEnabled` | Boolean | `true` | 串口/硬件 |
| 6 | `serialPollInterval` | Integer | `5000` | 串口/硬件 |
| 7 | `serialResponseTimeout` | Integer | `700` | 串口/硬件 |
| 8 | `slotStatusPushInterval` | Integer | `60000` | 串口/硬件 |
| 9 | `pollingMode` | String | `"GROUP"` | 串口/硬件 |
| 10 | `slotSortDirection` | String | `"HORIZONTAL"` | 串口/硬件 |
| 11 | `communicationMode` | String | `"MQTT"` | 通信 |
| 12 | `httpHost` | String | `"127.0.0.1"` | 通信 |
| 13 | `httpPort` | Integer | `8082` | 通信 |
| 14 | `mqttHost` | String | `"127.0.0.1"` | 通信 |
| 15 | `mqttPort` | Integer | `1883` | 通信 |
| 16 | `mqttHeartbeatInterval` | Integer | `60000` | 通信 |
| 17 | `mqttReconnectInitialInterval` | Integer | `1000` | 通信 |
| 18 | `mqttReconnectMaxInterval` | Integer | `60000` | 通信 |
| 19 | `faceThreshold` | BigDecimal | `0.8` | 识别 |
| 20 | `fingerThreshold` | BigDecimal | `0.8` | 识别 |
| 21 | `fingerEnabled` | String | `"0"` | 识别 |
| 22 | `faceRecognitionTimeout` | Integer | `30000` | 识别 |
| 23 | `searchTimeout` | Integer | `15000` | 识别 |
| 24 | `searchIntervalTime` | Integer | `3000` | 识别 |
| 25 | `needFaceLiveness` | Boolean | `false` | 识别 |
| 26 | `captureTimeout` | Integer | `8000` | 识别 |
| 27 | `cameraFacing` | String | `"front"` | 摄像头 |
| 28 | `cameraMirror` | Boolean | `true` | 摄像头 |
| 29 | `cameraRotation` | Integer | `0` | 摄像头 |
| 30 | `cameraFrameWidth` | Integer | `640` | 摄像头 |
| 31 | `cameraFrameHeight` | Integer | `480` | 摄像头 |
| 32 | `mqttStatusReportInterval` | Integer | `300000` | 通信 |
