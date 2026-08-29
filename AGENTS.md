# 工作卡柜设备端：Codex 项目指引

本仓库当前以 V2 为基准：Vue H5 / uni-app 负责页面、业务流程、业务 HTTP/MQTT 协议、SQLite 本机缓存和离线 outbox；Android 负责 WebView、启动注册激活、HTTP/MQTT/串口/SQLite/FaceAISDK 等原生能力通道。`docs/CURRENT_PROJECT_BASELINE.md` 继续作为架构与历史背景入口，当前任务范围和协议以用户最新指令及本文件的最高执行口径为准。

旧 Android Map 三层重构不再作为目标架构推进。`old/**` 只作为反面样本和历史字段参考，不是兼容层。

## 最终收尾阶段最高执行口径（覆盖旧限制）

- 项目已进入最终收尾阶段。为修复已确认缺陷、完成最新协议适配和必要联动闭环，可以修改仓库内任意必要文件，包括 `uniapp/**`、`app/**`、`serialport/**`、测试、构建配置和文档，不再设置路径白名单、文件数上限或批次数量上限。
- 本文件、`docs/CURRENT_PROJECT_BASELINE.md` 和专项 skill 中旧的只读路径、一次性授权、历史专项文件清单和批次限制不再作为当前修改边界；其中 V2 架构职责、真实失败语义、安全规则、协议证据和验证要求继续有效。
- 放开修改范围不等于扩大任务范围。每个修改文件和每个 hunk 都必须能直接指向当前缺陷、最新协议或不可分割的调用链/测试依赖；不能明确说明必要性的改动不得保留。
- 严禁大范围重写或架构重构。优先在现有实现上做最小根因修复，禁止借机迁移架构、批量改名、重排目录、替换技术栈、抽象第二套实现、无关格式化或顺手清理历史代码。
- 注释是实现的一部分，只在不直观的业务边界补充一句话说明，不使用长篇 `Contract`、`Ownership`、`Failure` 模板注释。
- 仓库外后端、正式 Mock、部署环境和外部资产仍默认只读；除非用户明确要求，不直接修改或伪造其结果。

## 全项目强制技能

- 在本仓库目录中处理任何后续任务时，必须先加载并遵循 `$audit-feature-change`。该要求适用于审计、分析、规划、答疑、编码、修复、重构、文档、测试、构建、Git 和交付任务。
- `$audit-feature-change` 是所有任务的基础流程；专项 skill 只能按任务类型叠加使用，不能替代或跳过它。
- 只读任务也必须使用该 skill 进行范围和证据检查，但不得因此制造无关写入、测试或构建。
- 该规则不扩大修改权限。所有写入仍受当前用户指令、当前修改边界、路径级 `AGENTS.md` 和专项所有权限制约束。

## 需求理解与修改确认门禁

- 用户可能使用业务口语、大白话、现场观察或结果描述来指出问题；这些内容首先视为问题线索和期望结果，不自动等同于已经确认的代码实现方案。
- 发现问题后必须先独立思考并检查当前真实实现、调用链、状态来源、数据流向、失败路径和已有文档/测试，不能把用户原话直接逐字翻译成代码。
- 如果问题原因和目标修改只有一种合理解释、证据完整且不会改变业务口径，可以在明确复述验收结果后直接修改。
- 如果存在任何关键逻辑考虑不清楚、用户表述可能有多种解释、现有代码与用户认知不一致，或者不同改法会改变业务状态、数据归属、接口、持久化、硬件动作或失败语义，必须在写代码前停止修改并先向用户整理当前逻辑。
- 当前逻辑说明必须使用用户容易判断的业务语言，至少讲清楚：入口是什么、系统现在按什么顺序处理、每个判断依据来自哪里、数据保存或上报到哪里、成功与失败如何判定，以及本次发现的问题实际发生在哪一步。需要技术字段时同时解释其业务含义，不要求用户先理解代码。
- 对不确定项必须明确指出“哪里不确定”和“不同理解会造成什么结果”；可以给出候选方向及影响帮助用户判断，但不得把候选方向当成用户已经确认的方案。
- 整理完当前逻辑后等待用户看懂、判断并给出或确认新的修改方案。得到确认前只允许继续只读核对和回答问题，不得修改代码、数据库 schema、接口报文、配置、文档口径或测试来提前固化某一种猜测。
- 用户确认修改方案后，先用简明业务语言复述最终规则、验收标准和明确不改的范围；确认后的实现必须以这份口径为准，并按 `$audit-feature-change` 分批修改、复查和验证。
- “开放所有可修改”“继续”“修复这个问题”等授权只扩大执行范围或表示继续处理，不代表用户自动选择了尚未讲清楚的业务方案；仍须通过本门禁确认存在歧义的关键逻辑。

## 当前修改边界（最高优先级）

- 当前仓库内不存在预设只读代码路径；完成用户当前明确任务所必需的生产代码、测试和文档均可修改。
- 修改必须沿真实调用链收敛，只处理当前缺陷或协议差异，不重新审计、重写或清理与任务无关的模块。
- 涉及 Android、Vue、MQTT、HTTP、串口、人脸或 SQLite 时，继续遵守各层职责，不因开放路径而恢复旧 Android Map 业务架构或制造第二套协议实现。
- 若闭环依赖仓库外后端字段、数据、部署或硬件结果，明确报告外部阻塞，不在客户端伪造成功。

## 开始任务前

1. 先阅读 `docs/CURRENT_PROJECT_BASELINE.md` 了解 V2 架构和历史背景；修改范围以本文件的最终收尾阶段最高执行口径和用户最新指令为准。
2. 前端/业务客户端实现先用 `$vue-business-client`；跨 Vue、Android、MQTT、串口、人脸或 SQLite 的任务再用 `$card-cabinet-architecture-guardian` 做只读依赖审计。
3. 明确入口、唯一真相、依赖方向、状态变化、失败路径、文档证据和文件级计划。
4. 外部协议不明确时停止猜测，并在当前审计、计划或 PR 中登记具体阻塞项和责任方。

## 证据来源

- 当前服务器接口的最高级协议文件是 `docs/client-api-reference(1).md` 和 `docs/client-interface-changes-20260809.md`，二者共同覆盖旧接口文档、历史专项文档、Mock 示例和现有旧代码。
- `client-api-reference` 是当前完整接口与参数基准；`client-interface-changes-20260809` 是本轮必须逐项检查和处理的协议问题清单。增量清单只覆盖其明确列出的变化，其余字段、Topic、签名和响应语义服从完整协议。
- 用户当前明确勘误和实机日志高于文档中的已知错误：MQTT login 的 raw 继续携带 `version`、`ip`，签名输入保留 `msgId:cmd:timestamp:raw`，响应从 `card/{deviceCode}/down/response` 返回并使用服务端生成的 msgId；HTTP 登录响应改为与 MQTT 业务 data 一致且不再依赖 `serverTime`，其他 MQTT 指令仍按最新统一协议处理。
- `docs/CURRENT_PROJECT_BASELINE.md`、`docs/source-2026-07-23/Android客户端接口文档.md`、`docs/MULTI_FACE_CLIENT_CONTRACT.md` 和 `docs/config-接入指南.md` 只作为架构或历史背景，不能覆盖上述两份最高级协议文件。
- 串口帧：`docs/source-2026-07-02/智能工卡发卡机APP通信协议文档.md`。
- Vue SQLite 缓存：`docs/VUE_SQLITE_SCHEMA.md`。
- 设备实现参考：`reference/motone-current`，只参考已存在设备能力，不直接迁入旧架构和旧接口。
- 用户已明确：启动流程由 Vue 发起和显示；业务接口调用、请求组装、响应解析在 Vue；MQTT 连接和心跳在 Android；串口、人脸、SQLite 通过 JsBridgeV2 能力通道开放；Android Map 缓存迁到 Vue 管理的 SQLite 缓存；老三层重构不继续推进。

## V2 固定结构

```text
Vue H5 / uni-app
  - 启动、页面、业务流程
  - HTTP/MQTT 业务对象
  - SQLite 缓存、权限、离线判断、outbox
        ↓
uniapp/src/services/index.js
        ↓
uniapp/src/services/nativeBridge.js
        ↓
JsBridgeV2 六通道
        ↓
Android 原生能力层
  - bootstrap.*
  - http.*
  - mqtt.*
  - serial.*
  - storage.*
  - face.*
```

依赖方向是 Vue 调用 Android 能力，Android 通过 response/event 回传能力结果。Android 不再作为员工、卡槽、同步、操作和离线业务的 Map 唯一真相。

## Vue 层不变量

- Vue 负责页面、弹窗、筛选、排序、动画、临时选中项和表单草稿。
- Vue 发起在线启动流程，并展示注册、激活、配置、登录和心跳状态。
- Vue 组装业务 HTTP 请求体，解析业务响应，处理 MQTT 下行业务命令。
- Vue 通过 `storage.*` 管理 SQLite schema、配置缓存、员工/faceId 绑定、人脸照片、卡槽快照、权限、操作记录和 outbox。
- 人脸照片只保存到 Vue 管理的应用私有 SQLite，不写系统相册或公共媒体目录；单张照片不得超过 10 MB。
- 员工人脸为 1:N。本机先用临时 ID 采集照片，服务器明确返回添加成功后页面才显示“添加成功”；录入页不导入正式模板或写本地绑定，退出管理员模式时合并触发一次增量同步，再按服务器 `ADD/0`、`DELETE/9` 数据更新 FaceAISDK 和私有 SQLite。失败不保留本机先成功，不自动重放添加请求。图片按 SHA-256 全局去重，每人上限由服务端管理，不得加入设备 config 或在客户端硬编码。
- 用户于 2026-08-08 明确 Vue SQLite 只保留一张物理 `face_bindings` 生物绑定表：通过 `biometric_type=FACE/FINGER` 区分人脸和指纹，人脸照片字段直接随 FACE 行保存；不得再并存 `face_photos`、`finger_bindings` 或新增 `biometric_records`。旧三表数据必须在同一事务中迁移、核对 FACE/FINGER 行数后再删除旧表；人脸和指纹特征仍不得持久化。
- `appState` 只是 WebView 生命周期内的展示投影；跨重启、断网仍要使用的数据必须写入 SQLite。
- 断网时只能读取已同步且未过期的 SQLite 缓存，不向后端取数。
- `faceId` 到员工的映射由 Vue SQLite 管理，Android 人脸能力只返回识别/录入结果。
- 用户于 2026-08-08 明确：不存在“员工持有未归还工卡”这一客户端业务状态，不得根据历史 `TAKE_CARD` / `RETURN_CARD` 记录阻止员工再次取卡。operation 记录仍用于物理完成证据、上报与非终态恢复；正在执行或待补传的同一操作继续按既有幂等规则去重。
- 用户于 2026-08-08 明确：取卡不得按员工同步卡号或指定卡号定位卡槽，必须从最新实时卡槽快照的全部有卡槽中选取。已充满或充电结束的卡优先；只有全部有卡槽都在充电时，才选择明确电量最高的卡，快照没有电量字段时用协议电压值排序。卡号只是最终选中卡槽的状态元数据，只在 operation、统计和上报中携带，不建立员工、卡与卡槽之间的持有或定位真相。
- 页面只能经 `services/index.js → nativeBridge.js` 调用 Android。
- Release 原生失败时禁止切换 Mock。

## Android 原生能力不变量

- `JsBridgeV2` 是可信 WebView 的能力桥，不新增业务型 bridge 方法。
- `DeviceBootstrapManager` 负责在线版注册、激活、配置、登录和心跳编排；离线激活只保留预留入口。
- `HttpClientManager` / HTTP 能力只负责 GET、POST、download 和必要 header/token，不解释业务对象。
- `XMqttClient` / MQTT 能力负责连接、订阅、发布、重连、心跳和消息事件投递；业务 cmd 解析在 Vue。
- `DeviceSerialManager` / 串口能力负责连接、轮询、发送 HEX、最近日志和结构化事件；不推断取还卡业务成功。
- `SqliteManager` / `storage.*` 只执行 SQL，不理解 Vue 业务表。
- `FaceAiManager` 只负责 FaceAISDK 引擎、CameraX 和本地人脸库适配，不持有员工业务真相。
- Android 不恢复员工、卡槽、操作、同步业务 Map 作为唯一真相。
- 通信结果、串口事件、人脸结果必须作为能力结果返回 Vue，由 Vue 决定是否写 SQLite 和更新页面投影。

## Service 与 Activity

- `DeviceCoreService` 只允许前台 Service 生命周期、组件创建/注入和长生命周期能力启动/停止。
- 禁止在 Service 中新增业务事件组装、员工/卡槽 Map 缓存、取还卡业务状态机或 outbox。
- `MainActivity` 负责 WebView、Service 启动、CameraX/FaceAISDK 覆盖层和系统指纹 UI。
- Activity 人脸/指纹结果只能返回能力结果；员工归属、权限和业务动作由 Vue 层处理。

## 契约证据规则

- 每个后端字段、路径、方法、枚举、错误码和响应语义必须能指向 V4.2/config 文档或用户明确确认。
- 禁止根据常识、旧实现、示例值或个人推断补全不存在的协议、字段、地址映射、状态语义、配置或业务流程。
- 推断必须显式标记为待验证，不能进入生产协议；证据不足时保持空值、禁用并提出待确认项。
- 文档未定义时只能留空或禁用，并在当前审计、计划或 PR 中列出待确认项；不得恢复已删除的证据登记文档。
- 文档冲突时记录冲突，不自行选择解释。
- 路径常量存在不等于业务闭环完成；必须区分运行闭环、业务入口、仅映射和阻塞。

## 代码所有权边界

- 路径已开放，但运行时职责不变：Vue 负责页面、业务协议、SQLite 和 outbox；Android 负责启动与原生能力通道；串口、人脸和通信层不得越权决定业务成功。
- 修改跨层接口时只修实际责任层和必要调用方，不把同一业务事实复制到多个层，也不新增平行业务处理器。
- 后端资产无法满足调用要求时，登记具体问题并交给后端处理；禁止在客户端仓库内伪造后端结果。
- 合并冲突必须基于当前有效实现逐处处理，不得用整文件覆盖丢失用户修改。

## 绝对禁止

- 未经用户明确要求修改仓库外后端、正式 Mock、部署环境或外部资产。
- 对现有模块进行大范围重写、架构重构、批量改名、目录迁移、无关格式化或顺手清理。
- 恢复 Android 业务 Map 缓存作为员工、卡槽、操作、同步或离线唯一真相。
- 在 `old/**` 中新增当前功能，或把旧 `DeviceDataLayer` / Repository / Facade 接回 V2 主流程。
- `JsBridgeV2` 新增 `openDoorForEmployee`、`syncEmployeeBusiness`、`takeCardFlow` 这类业务方法。
- Android 直接解释 Vue SQLite 业务表。
- Vue 绕过 `services/index.js → nativeBridge.js` 直接调用原生。
- 通信 Manager 直接引用 UI 或决定业务成功。
- 恢复 ArcSoft、`arcsoft_face.jar`、`ARCSOFT_*` 或 `xmaihh` 串口依赖。
- 未确认出站拓扑前，用直接映射或取模映射执行逻辑轮询、开门、查询或一键弹卡；入站状态只允许证据登记过的受限映射。
- 把 MQTT 连接、订阅、HTTP 发送、串口写入或单板 ACK 当作取还卡业务完成。
- 在日志、诊断或公共存储中记录真实密码、Token、签名密钥、人脸图片、特征、指纹特征或身份资料。录入页只允许按当前协议向 `POST /api/v1/employee/face` 或 MQTT `faceRegister` 提交 `employeeId + faceImage`；请求没有幂等键证据，禁止在启动、联网恢复、定时任务或 outbox 中自动重放，本机照片、正式模板和绑定只能由后续增量同步写入。
- 修改 `main`、自动合并 PR、绕过测试或提交临时迁移文件。

## 当前可修改范围

- 仓库内所有模块均可在当前任务确有必要时修改，不再维护静态文件白名单或 Android 只读清单。
- 范围开放只解决权限阻塞；具体任务仍须先列出最小文件计划，并在 diff 自审中逐文件证明必要性。
- `old/**` 和 `reference/**` 仍只作为历史参考，不作为新增当前功能或恢复旧架构的目标路径。

## 当前客户端改动必须运行

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/localStore.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
cd uniapp && npm run test:local-store
cd uniapp && npm run build:h5
```

涉及 Android 源码时运行相关单元测试和 `compileDebugJavaWithJavac`；仅在用户明确要求 APK 打包或发布验证时运行 assemble，并确认 APK 包含 `lib/arm64-v8a/libSerialPort.so` 与最新 H5 assets。

## Git 与交付

- 使用用户指定的当前工作分支；未明确要求时不切分支。
- 不提交临时脚本、临时 workflow、诊断、APK、密钥或本地配置。
- 提交或推送前核对父节点、相对目标分支的实际 diff 和将被带入的提交历史。
- 编译、APK 生成、APK 安装、真机功能验证必须分别报告；未执行的项目明确写“未验证”。
- 构建通过不等于目标 rk3568_r 真机安装和联调通过；没有设备证据时必须明确写“未验证”。
