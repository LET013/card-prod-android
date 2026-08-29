# 功能需求走查报告（2026-08-02 历史快照）

> 本文保留当时的走查过程，不再作为当前完成度或发布结论。当前唯一结论以 `docs/CURRENT_PROJECT_BASELINE.md` 和现行代码/测试为准；其中“一人一脸”“指纹为未完成”“还卡需要手工入口”“固件升级未接入”及 66.7% 完成度均已被后续用户决定或实现覆盖。

**基准文档**: `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`  
**功能清单**: `docs/source-2026-07-02/发卡机功能模块清单.xlsx`  
**走查日期**: 2026-08-02  
**走查范围**: Vue H5 前端 + Android 原生能力层  

---

## 一、功能模块总览

根据 `发卡机功能模块清单.xlsx` 整理的功能模块共 5 大类 18 个子模块：

| 序号 | 大类 | 子模块 | 实现状态 |
|------|------|--------|----------|
| 1 | 启动流程 | 品牌展示 & 服务器配置 | ✅ 已实现 |
| 2 | 启动流程 | 设备注册激活 | ✅ 已实现 |
| 3 | 启动流程 | 强制升级 | ✅ 已实现 |
| 4 | 启动流程 | MQTT 连接 & 数据同步 | ✅ 已实现 |
| 5 | 主界面 | 卡柜首页 | ✅ 已实现 |
| 6 | 主界面 | 管理员入口 | ✅ 已实现 |
| 7 | 主界面 | 身份验证（人脸/指纹） | ⚠️ 部分实现 |
| 8 | 主界面 | 取卡还卡操作 | ⚠️ 部分实现 |
| 9 | 人员管理 | 员工信息管理 | ✅ 已实现 |
| 10 | 人员管理 | 人脸注册 | ✅ 已实现 |
| 11 | 人员管理 | 指纹录入 | ❌ 未实现 |
| 12 | 系统管理 | 角色与权限管理 | ✅ 已实现 |
| 13 | 系统管理 | 用户与凭证管理 | ✅ 已实现 |
| 14 | 系统管理 | 系统设置 | ✅ 已实现 |
| 15 | 系统管理 | 历史管理 | ✅ 已实现 |
| 16 | 系统管理 | 串口调试台 | ⚠️ 部分实现 |
| 17 | 工程模式 | 一键弹卡/单元管理 | ✅ 已实现 |
| 18 | 工程模式 | OTA 升级/指令 | ⚠️ 部分实现 |

---

## 二、逐模块详细走查

### 模块 1-4: 启动流程

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 品牌 Logo 展示 | `splash.vue` Phase 1 (900ms) | ✅ | Logo 动画 + 渐变过渡 |
| 服务器地址配置 (首次) | `splash.vue:confirmUrl()` → `saveBootstrapConfig()` | ✅ | 域名/IP + 端口 + APP渠道，持久化 SQLite |
| 版本检查 | `splash.vue:startVersionCheck()` → `checkAppUpdate()` | ✅ | 对比服务端版本号 |
| 强制升级 (下载+安装) | `splash.vue:startAppUpdate()` → `downloadAppUpdate()` → `installAppUpdate()` | ✅ | 进度条展示 + 自动安装 APK |
| 设备注册码获取 | `splash.vue:startRegistration()` → `bootstrap()` | ✅ | 显示注册码 + 有效期 |
| 激活码输入 | `splash.vue:handleActivate()` → `bootstrapActivate()` | ✅ | 填写激活码 → 激活设备 |
| MQTT 连接 | `splash.vue:initMqttAndLogin()` → `mqttLoginStatus` watch | ✅ | 连接 MQTT broker |
| 人员数据同步 | `splash.vue:syncIdentityData()` | ✅ | 同步员工/人脸/指纹数据 |
| 离线启动 | `splash.vue` OFFLINE mode | ❌ | 仅提示"离线版启动流程已预留"，无实现 |
| 超时兜底 | `splash.vue` 15s bootstrap timeout | ✅ | `timeout` 后标记 `bootstrap_start_timeout` |

**结论**: ✅ 在线启动流程完整，离线启动预留但未实现。

---

### 模块 5-6: 主界面

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 卡柜状态概览 | `index.vue:StatusLegend` | ✅ | 8种状态: 空/占用/充电/满/非法卡/充电故障/通信故障/未知 |
| 卡槽网格展示 | `index.vue:CabinetSlotGrid` | ✅ | 分组/排序/交互点击 |
| 设备信息展示 | `index.vue:CabinetHeader` | ✅ | 设备编码 + MQTT 在线状态 + 激活状态 |
| 管理员入口 | `index.vue:onAdminClick()` → PasswordModal | ✅ | 密码验证 → admin dashboard |
| 实时状态推送 | `index.vue onMounted`: `on('slot.status', ...)` `on('device.info', ...)` | ✅ | EventBus 自动刷新卡槽和设备状态 |

**结论**: ✅ 主界面功能完整。

---

### 模块 7-8: 身份验证 & 取卡还卡

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 人脸识别取卡 | `index.vue`: FACE mode → `faceRecognitionStart()` → 取卡 → 开门 | ✅ | 完整闭环实现（RecognitionModal 多阶段） |
| 指纹识别取卡 | `index.vue` verify-method-list | ⚠️ | 代码中 FINGERPRINT 逻辑就绪，但 UI 面板**未展示指纹选项** |
| 取卡进度展示 | `RecognitionModal.vue` | ✅ | PREPARING→DETECTING→COLLECTING→MATCHING→TAKING→SUCCESS→CANCEL→TIMEOUT→ERROR |
| 取卡开门确认 | `index.vue:onConfirmOpen()` | ✅ | 弹窗确认 + `unlockDoor()` 调用 |
| 还卡操作 | - | ❌ | 首页无还卡入口，但服务层 `returnCard()` API 已就绪 |
| 人脸识别超时兜底 | `JsBridgeV2.java:faceTimeoutHandler` | ✅ | 20s 超时自动取消 + `face.recognition.timeout` 事件 |

**结论**: ⚠️ 人脸取卡完整闭环，指纹取卡 UI 入口被隐藏（提示"暂不能完成员工级取卡闭环"），还卡无前端入口。

---

### 模块 9-10: 人员管理 & 人脸注册

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 员工列表 | `employees.vue` | ✅ | 搜索 + 分页 + 增删改 |
| 员工同步 | `employees.vue:syncEmployees()` → `syncIdentityData()` | ✅ | 从服务端拉取最新员工数据 |
| 人脸注册页面 | `face.vue` + `FaceRegisterPanel.vue` | ✅ | 输入 faceId → 调用 CameraX 拍照 → 提取特征 |
| 人脸特征保存到 FaceAI 库 | `JsBridgeV2:onFaceEnrolled()` → `FaceAiManager.enrollFeature()` | ✅ | 已修复（原 bug: 特征提取成功但未写入 FaceSearchFeatureManger） |
| 一人多照片 | - | ⚠️ | 待设计方案，当前每人仅存一个人脸特征 (faceID = employeeId) |
| 人脸模板导入/移除 | `services/index.js`: `faceTemplateImport()` / `faceTemplateRemove()` | ✅ | Service API 就绪 |
| 人脸库数量查询 | `services/index.js`: `faceCount()` → `FaceAiManager.getFaceCount()` | ✅ | 已实现 |
| 录入时图片缓存 | `JsBridgeV2:onFaceEnrolled()` → `faceImageBase64` | ✅ | 图片 Base64 通过 `face.enrolled` 事件传给 Vue 层 |

**结论**: ✅ 人员管理 & 人脸注册功能完整，录入特征保存 bug 已修复。

---

### 模块 11: 指纹录入

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 指纹录入页面 | `fingerprint.vue` | ❌ | 页面文件存在但**路由未注册** (pages.json 中无此路由) |
| 指纹注册面板 | `FingerprintRegisterPanel.vue` | ❌ | 组件存在但显示"等待外接指纹模块与 SDK" |
| 指纹取卡 | `index.vue` + `RecognitionModal` | ❌ | 取卡面板中指纹选项被隐藏，成功提示为"暂不能完成员工级取卡闭环" |

**结论**: ❌ 等待外部指纹硬件模块和 SDK 就绪。

---

### 模块 12-14: 系统管理

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 管理员仪表盘 | `admin.vue` (4 Tab) | ✅ | 账号权限 / 系统管理 / 设备维护 / 实时状态 |
| 角色管理 | `role-manage.vue` | ✅ | 增删改 + 启用/禁用 |
| 用户/凭证管理 | `credential-manage.vue` | ✅ | 增删改 + 解锁账户 |
| 修改密码 | `change-password.vue` | ✅ | 旧密码验证 + 新密码确认 |
| 系统设置（配置页） | `config.vue` → `DeviceConfigPanel.vue` (32KB) | ✅ | 服务器/MQTT/设备编码/卡槽/串口/人脸/指纹/轮询 配置 |
| 配置保存 | `DeviceConfigPanel` → `saveBootstrapConfig()` + 远程同步 | ✅ | 本地 SQLite + 后端双写 |

**结论**: ✅ 系统管理功能完整。

---

### 模块 15: 历史管理

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 历史记录列表 | `feature.vue?type=history` | ✅ | 按操作类型筛选 (管理员开门/一键弹卡/取卡/还卡/后台开柜/重启应用) |
| 执行结果筛选 | `feature.vue` filter bar | ✅ | 成功/处理中/部分完成/失败 |
| 详情弹窗 | `feature.vue` dialog | ✅ | 操作编号/对象/人员/时间/批量结果/失败卡槽 |
| 权限控制 | `feature.vue` permission check | ✅ | 需要 `history.view` 权限 |

**结论**: ✅ 历史管理完整实现。

---

### 模块 16: 串口调试台

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 串口调试台页面 | `serial-demo.vue` | ⚠️ | 手动发送 HEX 可用 |
| 自动轮询/开门/查询 | `serial-demo.vue` buttons | ❌ | 所有自动功能按钮 disabled，提示"等待设备负责人确认出站映射后开放" |
| OTA 发卡机/卡片升级 | `serial-demo.vue` OTA buttons | ❌ | 全部 disabled |
| 门锁状态/LED 控制 | `serial-demo.vue` | ❌ | 同 disabled |
| 串口日志 | `serial-demo.vue` log panel | ✅ | 实时日志展示 |

**结论**: ⚠️ 手动调试可用，自动功能因串口出站拓扑未确认而全部禁用。

---

### 模块 17-18: 工程模式

| 需求项 | 代码位置 | 状态 | 证据 |
|--------|----------|------|------|
| 一键弹卡 | `engineering.vue:unlockAllDoors()` | ✅ | 确认弹窗 → 调用 API → 显示成功/失败计数 (拓扑未确认时阻止) |
| 单元管理 | `feature.vue?type=units` | ✅ | 按卡槽快照分组显示，提示"单板版本尚无客户端数据来源" |
| 重启应用 | `feature.vue?type=restart` | ✅ | 确认弹窗 → `restartApp()` |
| 系统授权查看 | `feature.vue?type=authorization` | ✅ | 显示授权信息 |
| APP 升级 | `engineering.vue` → `startUpgrade()` | ✅ | 手动触发升级 |
| 硬件版本号 | `engineering.vue` | ❌ | 仅显示提示"需由 Android 串口版本读取返回" |

**结论**: ⚠️ 大部分功能已实现，硬件版本号读取未完成。

---

## 三、完成度统计

| 状态 | 数量 | 占比 |
|------|------|------|
| ✅ 已完整实现 | 12 | 66.7% |
| ⚠️ 部分实现 | 4 | 22.2% |
| ❌ 未实现 | 2 | 11.1% |
| **合计** | **18** | **100%** |

### 未完成项详情

| 模块 | 阻塞原因 | 后续动作 |
|------|----------|----------|
| 指纹录入 & 指纹取卡 | 等待外接指纹模块与 SDK | 后端/硬件团队提供指纹 SDK 后接入 |
| 还卡操作 | 前端无还卡入口 | 需求确认还卡交互流程后开发 UI |
| 离线启动 | 离线版架构待定 | 架构明确后实现离线注册激活流程 |
| 串口自动功能 | 出站拓扑未确认 | 串口负责人确认后开放 |
| 硬件版本号 | 串口读取未完成 | 串口负责人实现读取接口 |

---

## 四、整体评估

**功能完成度: 66.7%**（12/18 完整实现 + 4 部分实现）

核心在线业务流（启动 → 人员管理 → 人脸录入 → 人脸识别取卡）已完整闭环，支持真实设备运行。主要阻塞项集中在硬件依赖（指纹模块、串口拓扑）和离线架构待定。

---

*参考文档: `docs/source-2026-07-02/智能工卡发卡机设备APP需求文档.md`*  
*功能清单: `docs/source-2026-07-02/发卡机功能模块清单.xlsx`*
