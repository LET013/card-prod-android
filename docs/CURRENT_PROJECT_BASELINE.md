# 当前项目基线与唯一文档入口

更新日期：2026-08-08

本文件是当前项目范围、文档优先级和责任边界的唯一入口。开始审计、规划、开发或验收前先阅读本文件，再按任务类型读取下列专项文档。任何已经删除的文档不得从 Git 历史、缓存、旧审计结论或 `old/**` 中恢复为当前依据。

## 2026-08-07 人脸识别超时专项授权

- 用户进一步明确当前缺陷处理“放开所有限制且不限制批次”：为完成已确认问题，可修改当前仓库内所有必要文件并分任意数量批次实施，不再受此前一次性文件授权约束；仍须保留真实失败语义并通过测试与发布门禁。仓库外后台资产仍由后台负责，客户端不得伪造成功；没有已录入职员真人出镜时，不得表述为真人识别已通过。
- 用户重新授权修改 `app/src/main/java/com/xingyao/card/face/FaceEnrollmentController.java`，范围仅限修复扫描框有效帧在匹配回调前被后续帧覆盖导致的识别超时，并让搜索扫描框坐标与 FaceAISDK 实际采用的 `ImageProxy` 旋转角保持一致。
- 对应测试必须覆盖 0/90/180/270 度的识别帧尺寸/坐标语义；不得修改识别阈值、活体检测、员工绑定、取卡业务、`FaceAiManager`、`MainActivity` 或后端协议。
- 本机 FaceAISDK 模板总数是跨员工全局数量，员工页面数量以 Vue SQLite 的有效 `face_bindings` 为准；录入页只提交当前 `employeeId + faceImage` 添加请求，服务器成功后在退出管理员模式时通过一次增量同步生成本机正式模板和绑定，客户端不得伪造成功或自动重放添加请求。

## 1. 当前阶段目标

- 当前目标是完成设备 APP 的 Vue H5 / uni-app 前端和业务客户端逻辑，使其在正式 Mock 测试环境中形成可用业务闭环。
- 正式 Mock 服务端、Mock Broker、Mock 数据库及其服务端回归由后端负责，不作为客户端待开发功能。
- 客户端仍需接入当前文档定义的 HTTP/MQTT 接口，并正确处理请求、响应、失败、重复消息和本地持久化。
- 用户已确认正式 Mock 的员工、人脸和指纹分页从 `page=0` 开始；V4.2 文档中的 1-based 描述不适用于当前测试环境。
- 模拟串口通过即满足本轮客户端验收；真实打卡机上的单板动作、刷写结果和版本生效由后台/设备负责人继续验证。客户端不得猜测未定义的串口拓扑、地址映射、ACK 或硬件完成语义。
- 用户于 2026-08-13 明确串口调试台应提供真实串口开关，并允许调试人员输入已确认的卡槽号后复用现有开门、查询、版本、LED 与一键弹卡能力；串口未连接时按钮保持可点击并明确提示“串口未连接”。该人工调试入口不确认全局自动拓扑，不开放自动轮询或其他待确认指令，也不把命令入队或单板 ACK 当作物理动作完成。
- 人脸识别和录入由 Android FaceAISDK/CameraX 提供能力；Vue 客户端负责员工校验、照片持久化、faceId 绑定、服务器照片下发导入和识别后的业务编排。
- 用户于 2026-08-08 明确不存在“员工持有未归还工卡”这一客户端业务状态，覆盖 2026-08-02 的旧口径。客户端不得根据历史 `TAKE_CARD` / `RETURN_CARD` 记录阻止员工再次取卡；operation 记录继续保存物理完成证据、上报状态并支持非终态恢复，正在执行或待补传的同一操作仍按既有幂等规则去重。
- 用户于 2026-08-08 明确取卡必须从最新实时卡槽快照的全部有卡槽中选择，不按员工同步卡号或指定卡号定位。已充满或充电结束的卡优先；若全部有卡槽都在充电，按明确电量最高、缺少电量字段时按协议电压最高选择。卡号只取自最终选中卡槽并用于 operation、统计和上报，不建立员工、卡与卡槽之间的持有或定位真相。
- 用户于 2026-08-08 明确任何人取卡成功都必须立即上报一次完整 `statusReport`，不区分员工、管理员或远程操作。员工取卡以目标卡槽已开门/卡已弹出为成功；用户于 2026-08-14 最新明确管理员单槽/本机或后台批量弹卡以及 `remoteOpen` 以串口已接受开卡指令为成功，立即显示并播报“X号卡槽已开卡”，不再等待 `doorCode=1` 或空卡状态，MQTT 也返回成功。批量弹卡每接受一个卡槽指令就上报一次；不得为此在 Vue 新增轮询。上报失败进入既有 status outbox 等待补传，不回滚已经完成的开卡指令；并发触发必须串行发送，不能用旧的在途快照吞掉较新的状态。
- 用户于 2026-08-08 明确适配后端新增配置 `mqttStatusReportInterval`：它是 `scheduleStatusReport` 的首选调度间隔，默认 `300000ms`；旧 `slotStatusPushInterval` 继续作为兼容回退。该配置只控制已有状态事件的节流上报，不允许在 Vue 新增卡槽轮询。
- 用户于 2026-08-12 将日志上传收口为应用显式日志：默认只写 Logcat，上传开关开启时先通过 MQTT `logReport` 尝试上传再写 Logcat，不再抓取或转发整个进程 Logcat，也不通过 outbox 重试。
- 日志能力由 `AppLog.java` 统一脱敏和限长，Vue 只通过 `diagnostics.log.write/setUploadEnabled` 提交选定摘要；禁止记录请求体、密码、Token、签名、人脸或指纹数据，并防止 `logReport/logReportResp` 形成上传回路。
- 员工人脸为 1:N。终端用临时 ID 采集并向当前协议定义的人脸注册接口提交真实照片，只有服务器明确返回成功才显示“添加成功”；录入页不导入正式模板或写 SQLite 绑定。退出管理员模式时后台合并执行一次增量同步，再按服务器返回的 `faceId/faceAiId`、`ADD/0`、`DELETE/9` 更新 FaceAISDK 和 SQLite；`faceChanged` 与每 5 分钟增量同步继续保留。失败不保留本机先成功，也不自动重放添加请求。完整边界见 `docs/MULTI_FACE_CLIENT_CONTRACT.md`。
- 用户于 2026-08-08 明确本机只保留一张物理 `face_bindings` 生物绑定表：`biometric_type=FACE/FINGER` 区分人脸和指纹，人脸照片字段直接保存在 FACE 行。schema 10 必须把旧 `face_bindings`、`face_photos`、`finger_bindings` 在事务中合并并校验数量，成功后删除旧表；不得新增 `biometric_records`，不得持久化人脸或指纹原始特征。
- 用户于 2026-08-08 联调确认设备端所有 MQTT 上行 envelope 使用 `raw` 承载业务 JSON 原文字符串，不再把字符串放入 `data`；客户端上行只发送 `raw`，空 JSON 固定为字符串 `"{}"`，签名原文直接使用同一段 `raw`，不再排序、删减 `null` 或二次序列化。后端接收兼容 `data`/`raw` 二选一，同时存在时以 `raw` 覆盖 `data`；Vue 业务对象和 MQTT 下行解析不变，所有响应仍必须复用请求的原始 `msgId`。
- 用户于 2026-08-06 最终明确系统超级管理员和开发人员密码都由后台配置管理，后台优先级高于本机。在线启动进入主页前的配置加载和 MQTT `syncConfig` 都必须先校验完整 31 项设备配置，再同时读取 `developerPassword` 和超级管理员密码，并以哈希整体覆盖 `builtin:DEVELOPER`、`builtin:SUPER_ADMIN`；超级管理员首选 `superAdminPassword`，同时兼容现有激活链使用的 `initialAdminPassword`、`initAdminPassword`、`adminInitialPassword`、`adminPassword`、`initialPassword`。后台两项密码必须都是 6 位数字且彼此不同；可以重新分配两个系统账号此前使用过的密码，但不得与自定义凭据冲突。明文不得写入配置缓存、页面投影或日志。任一字段缺失、无效、冲突或写入失败时保留原凭据，启动显示缓存降级告警，MQTT 指令返回失败。本机系统密码只作为首次成功同步前的兜底；开发人员账号仍不可由普通界面修改。
- 现场录入照片和服务器返回照片都保存到 Vue 管理的应用私有 SQLite，不写入系统相册或公共媒体目录；单张照片不得超过 10 MB。
- 用户已授权仅在 `JsBridgeV2.java` 和 `HttpClientManager.java` 中补充录入照片结果、FaceAISDK 模板导入和通用 multipart 传输；用户于 2026-08-02 进一步要求识别成功后立即关闭原生摄像头并进入弹卡，以及让 Vue 能读取模拟串口当前内存卡槽快照。后两项只允许在 `JsBridgeV2.java` 内实现确定性人脸控制器收尾和通用只读 `serial.slotsSnapshot`，Android 不持有人脸照片、选卡或取还卡业务真相。
- 员工级指纹识别仍依赖外接指纹模块/SDK；Android 系统指纹只能完成本机认证，不能推断员工身份。
- 固件升级接口已确认存在：MQTT 下行 `firmwareUpgrade`/`cancelUpgrade`、响应 `firmwareUpgradeResp`/`cancelUpgradeResp`、状态上报 `upgradeStatus`，HTTP `POST /api/v1/upgrade/status` 和带认证的固件下载 URL。客户端已完成应用私有目录下载、取消、进度、重复消息/重启保护、状态持久化，以及串口协议定义的广播 `0x80/0x81` 传输；成功终态只能是 `TRANSMITTED`（字节已写入串口），固定 `hardwareVerified=false`，不得表述为主板刷写成功或版本生效。

### 1.1 当前允许修改的范围

当前任务以 `uniapp/**` 的 Vue 业务客户端为主体，并包含已明确授权的 Android 通用能力和固件传输适配。

- 可修改：`uniapp/src/pages/**`、`uniapp/src/components/**`、`uniapp/src/services/**`、`uniapp/src/state/**`、`uniapp/src/constants/**`、`uniapp/src/styles/**`、相关前端入口/配置和 `uniapp/tests/**`。
- 可为维护当前边界和前端契约修改：`docs/**`、`.agents/**`、`AGENTS.md`。
- 可修改：`app/src/main/java/com/xingyao/card/webview/JsBridgeV2.java` 和 `app/src/main/java/com/xingyao/card/core/http/HttpClientManager.java`，且只能实现本次授权的通用能力。`JsBridgeV2.java` 可移除识别成功后的人工最短停留、停止当前人脸控制器并隐藏容器，以及返回 `DeviceSerialManager` 当前内存卡槽快照；不得在 Android 中选卡或判断取卡业务完成。
- 本次 MQTT `raw` 字符串联调另可修改 `app/src/main/java/com/xingyao/card/core/mqtt/MqttEnvelope.java`、`app/src/main/java/com/xingyao/card/core/utils/MqttSignUtil.java` 及对应单元测试；只把上行 envelope 的业务 JSON 改由 `raw` 字符串承载并作为签名原文，不改变 Vue 业务对象、业务字段、topic、QoS、下行解析或响应相关性规则。
- 本次固件升级另可修改 `DeviceSerialManager.java`、`WorkCardProtocol.java`、串口 transport/connection、`serialport` 的 JNI Java 发送封装、`NativeActionPolicy.java` 及对应测试；仅实现应用私有固件文件与文档定义的 `0x80/0x81` 通用传输和取消，不解释 MQTT/HTTP 业务，也不推断硬件刷写结果。
- 用户于 2026-08-02 明确授权修改 `app/src/main/java/com/xingyao/card/face/FaceEnrollmentController.java` 的识别成功提示：移除 Face ID/分数，并删除会遮挡 Vue 取卡进度的“人脸识别成功”Toast。必须保留识别回调、调试日志、录入提示和错误提示；完成该提示调整后文件恢复只读。
- 用户于 2026-08-02 明确授权在 `FaceEnrollmentController.java` 中增加扫描框区域约束：送入 FaceAISDK 的录入/识别画面只能来自虚线脸框区域，脸未完整进入、贴边或过小时不得触发识别成功；不得借此修改匹配阈值、员工绑定或取卡业务。
- 用户于 2026-08-02 明确授权稳定 `FaceEnrollmentController.java` 的识别提示词：过滤 FaceAISDK 提示与扫描框位置提示之间的短时抖动，并保证提示最短可读时长；不得修改识别阈值、活体检测、匹配结果或 Vue 取卡业务。
- 用户于 2026-08-02 明确授权一次性调整原生人脸扫描界面，范围仅限 `app/src/main/res/layout/activity_main.xml`、人脸扫描专用 drawable/dimen 资源，以及新增的纯视觉 `app/src/main/java/com/xingyao/card/face/FaceScanOverlayView.java`。该批只实现居中相机卡片、脸型虚线引导框、扫描动画、提示排版和手机/平板适配，不改变 FaceAISDK、CameraX 分析、回调和 Vue 业务链；完成后这些原生界面文件恢复只读。
- 用户于 2026-08-02 明确授权一次性修复 `app/src/main/java/com/xingyao/card/MainActivity.java` 启动时重复调用 CameraX `configureInstance` 导致的闪退。修改仅限进程内幂等配置保护和复用既有 `ProcessCameraProvider`，不得改变相机参数、FaceAISDK 回调或 Vue 业务；完成后该文件恢复只读。

### 1.2 当前禁止修改的范围

- 除 1.1 节列出的通用能力、MQTT `raw` 字符串联调文件、固件专项文件和一次性授权外，`app/**` 全部只读，包括 `FaceAiManager`、其他 MQTT/SQLite manager、bootstrap、Gradle 和 Manifest；`MainActivity.java` 在本次 CameraX 闪退修复完成后恢复只读。
- 后端、正式 Mock 服务端、Mock Broker、Mock 数据库和服务端测试全部只读。
- 客户端仓库内的 Mock 模拟实现 `uniapp/src/mock/**`、`uniapp/src/services/mockService.js`、`uniapp/scripts/dev-mock.js`、`uniapp/scripts/faceai-server/**` 当前也只读，不用于替代正式 Mock。
- 除 1.1 节列出的 `serialport/**/SerialManager.java` 固件发送异常上抛外，`serialport/**`、`serial-debug/**`、`old/**`、`reference/**` 全部只读。
- 功能依赖剩余只读层缺失能力时，记录准确缺口和责任方，并将对应闭环标记为未完成；不得继续扩大 Android 改动或返回固定成功。

## 2. 文档优先级

发生冲突时按以下顺序处理，低优先级文档不得覆盖高优先级文档：

1. 用户在当前任务中的最新明确决定。
2. 本文件定义的当前范围和责任边界。
3. 当前专项契约和设计文档。
4. 2026-07-02 原始需求、模块清单、Excel 和使用说明中的功能范围。
5. 代码中的历史实现、注释、未接线类和 `reference/**`，只能作为参考，不能单独证明功能完成。

## 3. 必读文档

### 3.1 所有任务

- `docs/CURRENT_PROJECT_BASELINE.md`
- `AGENTS.md`
- 与任务对应的 `.agents/skills/*/SKILL.md`

### 3.2 后端接口、MQTT、HTTP、启动或配置

- `docs/MULTI_FACE_CLIENT_CONTRACT.md`：2026-08-06 当前 1:N 人脸专项契约；其人脸段优先于旧 V4.2 人脸上传和同步描述。
- `docs/source-2026-07-23/Android客户端接口文档.md`：当前 HTTP/MQTT 契约，版本 V4.2。
- `docs/config-接入指南.md`：当前 31 个配置字段及废弃字段。
- `docs/VUE_JSBRIDGE_V2_GUIDE.md`：Vue 到 Android 能力通道。
- `docs/VUE_SQLITE_SCHEMA.md`：员工、faceId、卡槽、operation、outbox 和同步游标。

### 3.3 管理界面和本机权限

- `docs/本地管理员模式设计.md`
- `docs/管理界面页面层级分析-2026-07-28.md`

`docs/本地管理员模式实现审查-2026-07-26.md` 是历史时点审查，只能用于定位当时问题；完成状态必须以当前代码和最新设计重新验证。

### 3.4 APP 功能范围

- `docs/source-2026-07-02/智能工卡发卡机APP功能模块清单.md`
- `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`
- `docs/source-2026-07-02/智能工卡发卡机系统完整需求文档.md` 中的终端 APP 部分
- `docs/source-2026-07-02/发卡机功能模块清单.xlsx` 中的 APP 功能页
- `docs/source-2026-07-02/工作卡柜APP（JW-S30-100F人脸）使用说明(1).pdf`

这些原始资料用于确认功能范围和用户操作，不用于覆盖 V4.2 接口、当前 config 字段、FaceAISDK 选型或 V2 所有权边界。

## 4. 说明性文档的用途

- `docs/MOCK_DEV_BROWSER.md` 描述正式 Mock 浏览器开发环境，不是客户端功能需求清单。Mock 服务端内部实现和完整回归不应列入客户端工时。
- `docs/JSBRIDGE_REDESIGN_ANALYSIS.md` 是重构分析，当前调用方式以 `docs/VUE_JSBRIDGE_V2_GUIDE.md` 和实际 `JsBridgeV2` 为准。
- `docs/SERIAL_POLLING_REDESIGN.md` 及串口协议文件用于串口证据核对；当前客户端只实现已定义的模拟串口能力和固件广播传输，未定义的真实出站拓扑继续禁用。
- `docs/source-2026-07-02/智能工卡发卡机APP开发工作量评估.md` 是早期人工估算，不用于计算 Codex 当前执行时间。

## 5. 已废除或不得作为当前依据

以下内容已经废除，即使能从 Git 历史、旧分支或缓存中找到，也不得读取为当前方案：

- 已删除的 `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`
- 已删除的 `docs/CODEX_PROJECT_GUIDE.md`
- 已删除的 `docs/DEVICE_CONFIGURATION_AND_INTERFACE_AUDIT.md`
- 已删除的 `docs/CONTRACT_EVIDENCE_REGISTER.md`
- 已删除的 `docs/REFERENCE_MERGE_AUDIT.md`
- 已删除的 `docs/REFERENCE_UNUSED_AND_MISSING.md`
- 已删除的 2026-07-02 旧版 `Android客户端接口文档.md`
- `old/**` 中的 Android Map 三层业务架构
- 旧 HTTP `/api/takeCard`、`/api/takeSuccess`、`/api/saveCard`
- 旧 TCP 长连接协议；当前业务通信以 V4.2 HTTP/MQTT 为准
- ArcSoft/虹软实现和相关密钥字段；当前人脸能力使用 FaceAISDK

## 6. 当前 V2 所有权边界

```text
Vue H5 / uni-app
  页面、业务流程、HTTP/MQTT 业务对象、SQLite、权限、operation、outbox
        ↓
uniapp/src/services/index.js
        ↓
uniapp/src/services/nativeBridge.js
        ↓
JsBridgeV2 能力通道
        ↓
Android
  WebView、bootstrap、HTTP/MQTT 传输、SQLite 执行、FaceAISDK/CameraX、串口能力
```

- Vue 是员工、faceId 绑定、卡槽业务投影、操作记录和 outbox 的客户端真相。
- Android 只提供原生能力，不在 Android Map 中恢复另一套员工、卡槽或取还卡业务真相。
- 页面只能通过 `services/index.js` 调用能力，不能直接调用 Android 或正式 Mock 内部实现。
- 正式 Mock 只替换环境能力，不能改变客户端业务完成标准。
- 本节描述运行架构，不代表所有层均可修改；当前实现权限以 1.1 和 1.2 节为准。

## 7. 功能完成判定

页面存在、按钮可点、接口常量存在、Mock 返回成功、MQTT 已发送或人脸 SDK 已识别，都不能单独判定业务完成。

功能至少需要验证：

1. 用户入口存在且输入经过校验。
2. 实际业务服务被调用，而不是占位返回或固定成功提示。
3. 成功结果更新 Vue 投影和需要持久化的 SQLite 数据。
4. 需要上报的结果进入 HTTP/MQTT 或稳定 outbox。
5. 失败、超时、取消、重复消息和重启后的状态不会显示为成功。
6. Mock 环境可见成功必须对应同一条客户端业务链，不能只验证 Mock 服务端或原生能力。

## 8. 未定义事项处理

- 外部字段、路径、枚举、删除语义、ACK 或硬件规则没有当前证据时，保持禁用或返回明确的未支持状态。
- 在当前任务审计、计划或 PR 中列出具体待确认项和责任方，不再写入已经删除的证据登记文档。
- 后端或正式 Mock 缺少客户端调用所需数据时，只登记接口缺口并交给后端，不在客户端伪造返回值。

## 9. 当前闭环结论与外部待验证项

### 9.1 客户端已闭环

- 员工 1:N 人脸录入、全量人工修复同步、`faceChanged` 和每 5 分钟增量同步均走同一套员工/人脸/FaceAISDK/SQLite 链路。
- Release 不再回退开发 Mock；设备配置保存只在后端业务成功和本机落库成功后提示成功，并明确需要重启的配置项。
- 串口端口扫描、重连和模拟器快照已接线；重连会创建新的写线程，不复用已经结束的线程。
- 系统授权状态变化使用 `POST /api/v1/device/auth/change` 的精确 `{newStatus}` 报文，经本机 outbox 重试，后端业务成功后才标记完成。
- 固件升级从 MQTT/人工入口共用同一执行器，具备下载、校验、取消、进度、持久化、重复消息和重启保护；模拟串口验证 `0x80/0x81` 帧、序号及每帧 128 字节。

### 9.2 必须由后台/设备负责人验证或补充

- 真实打卡机需验证固件文件经广播传输后是否实际刷写、何时生效及如何读取新版本；客户端当前只报告 `TRANSMITTED`。后台提供的固件文件必须非空且大小为 128 字节整数倍。
- 协议只定义通用单板广播升级，未定义“工作卡/主板”等升级目标对应的功能码、地址、结果与版本语义；这些目标保持禁用。
- 真实串口出站的逻辑槽号到板地址/通道映射仍无当前证据；自动轮询、开门、查询、版本读取和一键弹卡不得据此猜测实现。
- `statisticsReport` 的报文结构已有文档，但上报周期、触发时刻、`statDate` 时区/自然日边界未定义，客户端不擅自调度。
- `batchOperationResult` 路径存在，但 V4.2 未给出请求数据结构和成功语义，客户端不构造猜测报文。
