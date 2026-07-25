# 工作卡柜设备端：Codex 项目指引

本仓库由 uni-app H5 前端、Android 客户端数据/业务层，以及由其他专业负责人维护的后端通信和设备适配能力组成。

本文件约束代理如何工作。当前架构事实见 `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`，外部契约见 `docs/CONTRACT_EVIDENCE_REGISTER.md` 和原始 Markdown，逐路径所有权见 `docs/CODE_OWNERSHIP.md`。

# 一、职责范围

本代理主要负责：

1. Vue 前端 UI、页面交互和展示投影；
2. `services/index.js → nativeBridge.js` 调用层；
3. `JsBridge / DeviceApplicationFacade`；
4. Android 客户端数据层、业务编排、Map、状态投影和客户端测试；
5. 按已确认文档消费后端与设备负责人提供的公开接口和回调。

本代理不负责后端服务端和串口底层实现。后端与串口由其他专业负责人维护。

不得因为“需要让功能跑起来”“只改一行”“为了通过编译”而突破所有权边界。

# 二、每个子项必须单独审计

一个需求中包含多个功能时，必须拆成独立子项。每个子项都必须重新执行：

```text
只读审计
→ 边界确认
→ 需求标准化
→ 数据流/时序/状态设计
→ 文件修改矩阵
→ 用户确认
→ 实现
→ 验证
→ 实现后二次审计
→ 提交
```

一个子项获得确认，不代表其他子项自动获得实现授权。

例如“MQTT 状态上报、员工同步、人脸同步、指纹同步”必须分别审计，不能作为一个大批次一次性开工。

# 三、设计确认前的硬门禁

除纯文字、静态资源或用户明确授权的低风险单文件修改外，未获得用户明确的“按此方案实现”“确认开工”或同等指令前，禁止修改运行代码。

设计确认前只允许：

- 读取源码、日志、提交历史和文档；
- 执行只读搜索、构建和测试；
- 修改 `docs/plans/**`、契约登记和所有权文档；
- 提出阻塞项和负责人交接项。

设计确认前禁止修改：

- `app/src/main/**`；
- `uniapp/src/**`；
- `serialport/**`、`serial-debug/**`；
- `app/libs/**`；
- Manifest、Gradle 运行依赖；
- 运行时状态、权限、持久化、协议和数据模型。

# 四、任务分级

符合任意一项即为高风险：

- 跨越两个及以上架构层；
- 修改 Bridge 动作、权限、接口调用或状态生命周期；
- 修改 Map、Repository、缓存、持久化或同步游标；
- 修改启动、注册、激活、权限、设置保存或生物识别流程；
- 修改 MQTT、HTTP、串口、FaceAISDK 或硬件行为；
- 新增前置条件、超时、重试、幂等、恢复或并发控制；
- 替换旧实现或删除已有流程；
- 影响三个以上运行代码文件。

高风险任务必须使用设计 PR 和实现 PR 两阶段交付。

中风险任务必须先输出完整设计并等待确认。

低风险任务也必须先给出简短审计和文件级计划，除非用户明确要求跳过单独确认。

# 五、每个子项必须输出的文档

每个子项使用独立目录：

```text
docs/plans/<task-id>/
├── BASELINE_AUDIT.md
├── REQUIREMENTS.md
├── DESIGN.md
├── DECISIONS.md
├── VALIDATION_REPORT.md
└── OWNERSHIP_BLOCKERS.md（有阻塞时）
```

## 5.1 基线审计必须包含

- 当前分支、HEAD、目标分支；
- 真实入口与完整调用链；
- 数据产生者、写入者、读取者和唯一真相；
- 正常、失败、离线、首次启动、重启和未同步路径；
- 当前测试覆盖；
- 必须保持不变的既有行为；
- 文件所有权；
- 文档证据、冲突和缺失项。

不得仅根据方法名、注释或路径推断真实行为，必须读取调用方和被调用方。

## 5.2 需求标准化必须包含

- 目标与非目标；
- 当前行为与目标行为；
- 允许修改范围与禁止修改范围；
- 不变量；
- 外部契约证据；
- 未知项；
- 可测试的 `AC-xxx` 验收标准。

禁止使用“正常处理”“兼容一下”“适当优化”等不可验证描述。

## 5.3 设计必须包含

- Mermaid 组件依赖图；
- 数据流向图；
- 正常、失败、离线、首次启动和重复触发时序图；
- 涉及状态时的状态生命周期；
- 修改前/修改后兼容矩阵；
- 逐文件修改矩阵；
- 备选方案与复杂度、回归风险比较；
- 明确的验证计划。

设计中未列出的运行代码文件不得修改。

# 六、新增前置条件专项门禁

新增以下判断前必须回答完整生命周期：

```java
if (!employeeExists) ...
if (!initialized) ...
if (!authorized) ...
if (!synced) ...
if (session == null) ...
```

必须说明：

1. 条件由谁写入；
2. 首次启动值；
3. 后端离线值；
4. 数据未同步值；
5. APP 重启值；
6. 旧版本迁移值；
7. 条件不满足时的用户恢复路径；
8. 会阻断哪些既有正常流程。

没有完整答案时禁止加入该前置条件。

# 七、代码所有权边界

## 7.1 本代理可修改

以 `docs/CODE_OWNERSHIP.md` 为准，主要包括：

- `uniapp/src/**`；
- `JsBridge.java`；
- `core/DeviceApplicationFacade.java`；
- `core/NativeActionPolicy.java`；
- `core/DeviceRuntimeRegistry.java`；
- `core/DeviceDataLayer.java`；
- `core/DeviceStateStore.java`；
- `core/DeviceDataRepository.java`；
- `core/SlotStateRepository.java`；
- 客户端业务协调器和客户端测试。

## 7.2 后端负责人范围：默认只读

包括：

- 后端服务端仓库和数据库、消息队列、部署代码；
- 后端接口契约源；
- 后端负责人交付的 SDK、AAR、JAR 和生成物；
- `core/BackendTransportManager.java`；
- `core/BackendHttpGateway.java`；
- `core/BackendHttpClient.java`；
- MQTT Topic、Envelope、签名、QoS、登录和响应解析底层实现。

本代理只允许调用、校验、消费结果和输出交接文档，不得修改其底层行为。

## 7.3 串口负责人范围：默认只读

```text
serialport/**
serial-debug/**
app/libs/serialport-release-1.0.aar
core/SerialConnectionManager.java
core/WorkCardProtocol.java
```

包括 JNI、C/C++、串口线程、CRC、帧、地址、轮询、超时、单板命令、响应解析和固件升级。

客户端只能消费负责人公开回调，不得创建第二套串口实现。

## 7.4 人脸专业范围：默认只读

```text
core/FaceAiManager.java
FaceEnrollmentController.java
FaceEnrollmentActivity.java
MainActivity 中 CameraX/FaceAISDK 处理
app/build.gradle 中 FaceAISDK/CameraX 依赖
```

客户端只能消费其公开结果。确需修改时必须取得用户和对应负责人的具体授权。

## 7.5 所有权不明确

未在 `docs/CODE_OWNERSHIP.md` 登记的文件默认：

```text
UNKNOWN_OWNER → READ_ONLY
```

发现负责人能力不足时，输出 `BACKEND_HANDOFF.md` 或 `SERIAL_HANDOFF.md`，不得代改负责人代码。

# 八、固定三层结构

```text
Vue UI
→ services/index.js / nativeBridge.js
→ JsBridge / DeviceApplicationFacade
→ Android 数据/业务层（Store/Repository/Map 唯一真相）
→ 负责人提供的通信与设备公开边界
```

依赖只能向下。通信或 Activity 结果必须先进入 Android 数据层并提交状态，之后才能通知 UI。

## UI 不变量

- Vue 只负责页面、弹窗、筛选、排序、动画、临时选中项和未提交草稿；
- `appState` 只是 WebView 生命周期内的展示投影；
- 禁止从 H5 Storage 恢复员工、卡槽、运行、同步和操作真相；
- 禁止生成员工 ID、硬件状态、服务端 ACK 或成功结果；
- Release 原生失败时禁止切换 Mock。

## Android 客户端不变量

- `DeviceApplicationFacade` 是 WebView 唯一 Android 门面；
- `DeviceDataLayer` 是统一客户端业务入口；
- Store/Repository/Map 是唯一真相；
- 通信结果先写数据层，再发 UI 事件；
- 连接、订阅、发送成功不等于服务端业务确认；
- 单板 ACK 不等于物理取还卡完成。

# 九、契约证据规则

- 每个后端路径、字段、方法、枚举、错误码和响应语义必须能指向当前 Markdown 或用户明确确认；
- PDF 和旧 Markdown 只作历史参考；
- 文档缺失时只能留空、禁用或登记；
- 禁止猜测测试 IP、常见端口、username、时间窗、状态别名、地址映射或 ACK 语义；
- 文档冲突时记录冲突，不自行选择；
- 路径常量存在不等于业务闭环完成；
- Vue 触发动作不等于协议应在 Vue 实现。

# 十、实现规则

获得确认后：

1. 只修改设计矩阵内文件；
2. 不顺手重构无关代码；
3. 不新增设计外状态、字段和兼容逻辑；
4. 先改客户端数据/业务层，再改 Facade 和 UI；
5. 保持既有正常流程；
6. 新发现冲突立即停止并返回设计阶段；
7. 不用 UI 兜底掩盖数据层或负责人模块错误；
8. 一个提交只包含一个可解释的子项。

# 十一、验证与二次审计

至少执行：

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
```

提交前必须再次审计完整 diff，确认：

- 没有覆盖既有正常流程；
- 没有新增未经设计的前置条件；
- 没有第二份业务真相；
- 没有虚报服务端 ACK；
- 没有异常路径写成功；
- 没有越权文件；
- 实际 diff 与设计一致。

构建通过不等于 APK 已安装、真机已验证或后端已联调。必须分别报告。

# 十二、Git 与交付

- 只在独立 `fix/`、`feature/` 或 `docs/` 分支工作；
- 高风险任务使用设计 PR → 实现 PR；
- 默认 Draft PR，不自动合并；
- 禁止修改 `main`；
- 不提交临时脚本、临时 workflow、诊断、APK、密钥或本地配置；
- 交付必须分别列出已实现、未实现、已测试、未测试、已编译、已安装、已真机验证和外部阻塞；
- 禁止使用“全部正常”“无问题”等无法由证据支持的结论。