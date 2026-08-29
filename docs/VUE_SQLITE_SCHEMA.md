# Vue SQLite Schema V2

本文定义 V2 下由 Vue 管理的本机 SQLite 缓存。Android 只执行
`storage.query` / `storage.execute`，不理解表结构和业务语义。

## 1. 设计原则

- SQLite 是本机缓存和离线 outbox，不是后端权威数据；
- schema、迁移和 SQL 调用由 Vue 层维护；
- 页面不得散落 SQL，后续统一收敛到 `uniapp/src/services/localStore.js`；
- 联网时以后端返回为准，解析后写入 SQLite；
- 断网时只读取已缓存且未过期的数据；
- 恢复联网后先补传 outbox，后端 ACK 后再标记完成；
- 不存真实密码、token、签名密钥、人脸特征、指纹特征或身份证敏感原文；人脸照片仅允许进入下述应用私有 `face_bindings` 表的 FACE 记录。

## 2. 迁移元数据

```sql
CREATE TABLE IF NOT EXISTS schema_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
```

固定 key：

| key | value |
|---|---|
| `schemaVersion` | 当前 schema 版本，当前为 `11` |
| `lastMigrationAt` | 最近一次迁移时间戳 |

迁移要求：

- 每次启动先读取 `schemaVersion`；
- 版本不存在时创建全部表并写入 `1`；
- 版本升级只能向前迁移，不做破坏式清库；
- 如果迁移失败，配置页和首页必须展示“本机缓存不可用”，不要假装离线可用。

## 3. 本机配置

当前代码已使用 `vue_local_config` 保存本机启动和配置草稿，继续保留。

```sql
CREATE TABLE IF NOT EXISTS vue_local_config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
```

建议 key：

| key | 说明 |
|---|---|
| `bootstrapConfig` | bootstrap 前可读取的 HTTP/MQTT 本机配置 JSON |
| `runtimeConfig` | 后端返回并合并后的运行配置 JSON |
| `configDraft` | 配置页未提交草稿 JSON，可选 |
| `initialAdminState` | 后台系统凭据同步状态；只保存两个系统凭据 ID、来源、管理方、哈希存在状态和缓存时间，不保存明文密码 |
| `offlineActivationState` | 未来离线版激活状态元数据；当前在线版固定不可用 |
| `offlineConfigMeta` | 未来离线配置文件元数据；不保存激活字符串、密钥或解密后的敏感配置 |

`runtimeConfig` 至少应覆盖：

- `cabinetNumber`
- `deviceCode`
- `activationStatus`
- `communicationMode`
- `serverUrl`
- `httpPort`
- `mqttHost`
- `mqttPort`
- `totalSlots`
- `groupSize`
- `slotSortDirection`
- `faceRecognitionThreshold`
- `offlineEnabled`
- `offlineMaxHours`

离线版预留规则：

- 当前在线版只允许保存 `{ available:false, status:"NOT_AVAILABLE" }` 这类非敏感状态；
- 未来离线版只有离线激活成功后才能使用 `OFFLINE_FILE` 配置来源；
- 激活字符串、解密密钥、人脸/指纹特征、解密后的敏感配置不进入 Vue SQLite；
- 离线配置文件格式、签名、加密算法、设备绑定和过期规则未确认前，不创建业务表或导入配置。

## 4. 本机用户与权限

本机管理员权限由 Vue SQLite 管理。Android 只提供 `storage.query/execute`，不解释角色和权限。

### 4.1 用户表

```sql
CREATE TABLE IF NOT EXISTS local_users (
  user_id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  display_name TEXT,
  role_id TEXT NOT NULL,
  password_hash TEXT,
  password_state TEXT NOT NULL DEFAULT 'INITIAL',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  raw_json TEXT,
  FOREIGN KEY(role_id) REFERENCES local_roles(role_id)
);

CREATE INDEX IF NOT EXISTS idx_local_users_role
ON local_users(role_id);
```

规则：

- 后台超级管理员和开发人员密码只允许以 PBKDF2 哈希写入系统凭据，明文不得进入本表、`vue_local_config`、页面投影或日志；
- `password_state` 使用当前本机凭据状态：`INITIAL`、`ACTIVE`、`LOCKED`、`EXPIRED`；
- 在线启动配置加载和 MQTT `syncConfig` 成功时都以后台值整体覆盖 `builtin:DEVELOPER`、`builtin:SUPER_ADMIN` 并记录 `managedBy=BACKEND_CONFIG`；两个后台值可重新分配彼此此前使用的系统密码，但必须彼此不同且不得与自定义凭据冲突；任一字段缺失、无效、冲突或落库失败时保留原哈希并返回明确失败；
- 自定义管理员凭据和管理二级密码不接受后台密码字段覆盖；开发人员账号不可由普通界面修改，但其登录密码由后台管理；
- 登录、改密、权限生效的页面接入按后续批次逐步挂载。

### 4.2 角色表

```sql
CREATE TABLE IF NOT EXISTS local_roles (
  role_id TEXT PRIMARY KEY,
  role_name TEXT NOT NULL,
  role_level INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  raw_json TEXT
);
```

默认角色：

- `SYSTEM_ADMIN`
- `OPS`
- `DEVELOPER`

### 4.3 权限表与角色权限表

```sql
CREATE TABLE IF NOT EXISTS local_permissions (
  permission_code TEXT PRIMARY KEY,
  permission_name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  raw_json TEXT
);

CREATE TABLE IF NOT EXISTS local_role_permissions (
  role_id TEXT NOT NULL,
  permission_code TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(role_id, permission_code),
  FOREIGN KEY(role_id) REFERENCES local_roles(role_id),
  FOREIGN KEY(permission_code) REFERENCES local_permissions(permission_code)
);

CREATE INDEX IF NOT EXISTS idx_local_role_permissions_permission
ON local_role_permissions(permission_code);
```

规则：

- 默认权限来自 `uniapp/src/constants/app.js`；
- schema 初始化只写入缺失的默认项，不覆盖已有权限配置；
- 后续如果做后台权限同步，应以服务端返回覆盖本机默认项。

### 4.4 可操作内容表

```sql
CREATE TABLE IF NOT EXISTS local_operable_items (
  item_code TEXT PRIMARY KEY,
  permission_code TEXT NOT NULL,
  item_type TEXT NOT NULL,
  label TEXT NOT NULL,
  route TEXT,
  action TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  raw_json TEXT,
  FOREIGN KEY(permission_code) REFERENCES local_permissions(permission_code)
);

CREATE INDEX IF NOT EXISTS idx_local_operable_items_permission
ON local_operable_items(permission_code);
```

规则：

- `item_type` 建议值：`menu`、`page`、`action`；
- 页面菜单、按钮、危险操作都应通过 `permission_code` 关联；
- 本批只建表和默认种子，不改变现有页面鉴权入口。

## 5. 员工缓存

```sql
CREATE TABLE IF NOT EXISTS employees (
  employee_id TEXT PRIMARY KEY,
  employee_code TEXT,
  employee_name TEXT NOT NULL,
  department_name TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  auth_state TEXT,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER,
  raw_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_employees_enabled
ON employees(enabled);
```

规则：

- `employee_id` 必须来自后端；
- Vue 不自行生成员工；
- `expires_at` 用于离线权限过期判断；
- `raw_json` 保存后端可公开字段，禁止塞入密码、证件敏感原文或生物特征。

## 6. 生物绑定与人脸照片

按用户 2026-08-08 的最终口径，本机只保留一张物理表，表名继续使用 `face_bindings`。`FACE` 和 `FINGER` 是同表记录类型，不再创建 `face_photos`、`finger_bindings` 或 `biometric_records`。

```sql
CREATE TABLE IF NOT EXISTS face_bindings (
  biometric_type TEXT NOT NULL,
  binding_id TEXT NOT NULL,
  employee_id TEXT NOT NULL,
  native_id TEXT,
  biometric_index INTEGER,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER,
  mime_type TEXT,
  byte_size INTEGER,
  photo_base64 TEXT,
  source TEXT,
  upload_state TEXT,
  upload_id TEXT,
  server_path TEXT,
  server_url TEXT,
  file_hash TEXT,
  last_error TEXT,
  synced_at INTEGER,
  raw_json TEXT,
  PRIMARY KEY (biometric_type, binding_id),
  FOREIGN KEY(employee_id) REFERENCES employees(employee_id)
);

CREATE INDEX IF NOT EXISTS idx_face_bindings_employee
ON face_bindings(employee_id, biometric_type);

CREATE UNIQUE INDEX IF NOT EXISTS idx_face_bindings_native_id
ON face_bindings(biometric_type, native_id);

CREATE INDEX IF NOT EXISTS idx_face_bindings_upload_state
ON face_bindings(biometric_type, upload_state, updated_at);

CREATE INDEX IF NOT EXISTS idx_face_bindings_file_hash
ON face_bindings(biometric_type, file_hash);
```

规则：

- `biometric_type=FACE` 时，`binding_id=faceId`、`native_id=faceAiId`、`biometric_index=faceIndex`；同一员工可存在多行，最终 `faceAiId` 严格为 `${employeeId}_${faceId}`；
- `biometric_type=FINGER` 时，`binding_id=fingerId`、`biometric_index=fingerIndex`，当前不保存原始指纹特征，也不代表员工级指纹认证能力已开放；
- 组合主键允许 FACE 与 FINGER 使用相同的服务端 ID 而不冲突；查询人脸或指纹必须始终带 `biometric_type`；
- 照片保存在应用私有 SQLite，不写系统相册或公共媒体目录；
- 单张照片按 Base64 解码后的字节数计算，不得超过 10 MB；
- 照片字段只对 FACE 行有意义，FINGER 行的 `mime_type`、`photo_base64`、`upload_state`、`file_hash` 等照片字段保持空值；
- 单独保存照片只更新照片字段，不能创建或启用有效人脸绑定；只有绑定写入成功后该 FACE 行才计入员工已录入数量，后写绑定不得覆盖同一行已有照片；
- `source` 区分 `LOCAL_ENROLLMENT` 与 `SERVER_SYNC`；
- `file_hash` 保存经本机与服务端共同确认的 SHA-256；保存新记录前进行全局重复检查；
- `LOCAL_ENROLLMENT` 只有在 `POST /api/v1/employee/face/image`、`POST /api/v1/employee/face`、最终 FaceAISDK 模板和 SQLite 全部完成后才写入，状态为 `UPLOADED`；
- 任一步失败都不保存本机先成功记录，也不进入旧 `RETRY_WAIT` 人工重试流程；两步写请求不得自动重放；
- `SERVER_SYNC` 写 `SYNCED`，表示该照片来自服务器下发并已成功导入本机；`DISABLED` 只用于员工不存在、停用、过期或明确禁用的本机照片；
- `PENDING`、`RETRY_WAIT` 仅为 schema 兼容枚举，不再由当前多脸录入流程产生；
- `raw_json` 只保存元数据，不重复保存照片、人脸特征或指纹特征；
- 人脸特征仅在 FaceAISDK 和服务器下发导入的当前内存调用中短暂流转；`fingerFeature` 收到后直接丢弃；
- FACE 或 FINGER 的禁用都只更新同表行，不以物理删除伪造不存在；
- schema 10 升级在同一事务中合并旧 `face_bindings`、`face_photos` 和 `finger_bindings`，分别核对 FACE/FINGER 行数后才替换表并删除旧表；数量不一致必须回滚并保留旧数据。只有旧照片而没有绑定的记录会保留照片但保持禁用，且不伪造 `native_id`。

## 8. 卡槽快照

```sql
CREATE TABLE IF NOT EXISTS slots_snapshot (
  slot_number INTEGER PRIMARY KEY,
  status TEXT NOT NULL,
  card_id TEXT,
  employee_id TEXT,
  source TEXT NOT NULL,
  fresh INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  raw_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_slots_snapshot_status
ON slots_snapshot(status);
```

规则：

- `slot_number` 是 Vue 显示和业务使用的逻辑槽号；
- `card_id` 是实时卡槽快照的卡号元数据，只随最终选槽写入 operation、统计和上报；不得把它建模为员工持卡关系或用指定卡号反查并决定取卡卡槽；
- `source` 可为 `BACKEND`、`SERIAL`、`LOCAL_OPERATION`；
- 重启后从 SQLite 读出的卡槽必须视为缓存快照，`fresh=0`；
- 收到串口或后端新状态后再置为 `fresh=1`；
- 没有可靠串口拓扑前，不用取模或猜测地址执行开门。

## 9. 操作记录

```sql
CREATE TABLE IF NOT EXISTS operations (
  operation_id TEXT PRIMARY KEY,
  operation_type TEXT NOT NULL,
  employee_id TEXT,
  face_id TEXT,
  slot_number INTEGER,
  card_no TEXT,
  physical_confirmed_at INTEGER,
  state TEXT NOT NULL,
  offline INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  finished_at INTEGER,
  raw_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_operations_state
ON operations(state, updated_at);
```

建议 `operation_type`：

- `FACE_OPEN`
- `REMOTE_OPEN`
- `TAKE_CARD`
- `RETURN_CARD`
- `CONFIG_SAVE`
- `DIAGNOSTIC`

建议 `state`：

- `CREATED`
- `VALIDATED`
- `SERIAL_SENT`
- `SERIAL_ACKED`
- `PHYSICAL_PENDING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

规则：

- MQTT 连接成功、串口发送成功、单板 ACK 都不等于业务完成；
- 取还卡是否 `COMPLETED` 必须等物理完成规则确认；
- `card_no` 保存实际发出的实体卡号，不能用员工同步卡号替代；
- `physical_confirmed_at` 在员工取卡收到目标卡槽已开门/卡已弹出事件及还卡确认时写入；管理员和后台开卡以串口接受命令为成功，仅记录 `commandAcceptedAt` 与 `SERIAL_COMMAND_ACCEPTED`，不伪造物理确认时间；
- `TAKE_CARD` / `RETURN_CARD` 记录是物理证据和上报历史，不推导“员工持有未归还工卡”状态，也不据此阻止员工再次取卡；
- 同一操作仍处于非终态或待补传时继续按既有 operation/outbox 幂等规则去重；
- 离线产生的操作必须同步写 outbox。

## 10. Outbox 补传

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
  event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  operation_id TEXT,
  payload TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  acked_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_outbox_state
ON outbox_events(state, next_attempt_at);
```

建议 `state`：

- `PENDING`
- `SENDING`
- `ACKED`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`

规则：

- `payload` 保存待补传业务报文 JSON；
- 后端没有 ACK 语义前，不能把发送成功当作 `ACKED`；
- 重启后把长时间停留在 `SENDING` 的记录恢复为 `PENDING`；
- `event_id` 必须稳定，避免断网/重启后重复副作用。

## 11. 操作审计

本机管理员操作审计，独立于业务 `operations` 表。

```sql
CREATE TABLE IF NOT EXISTS audit_events (
  event_id TEXT PRIMARY KEY,
  session_ref TEXT,
  actor_credential_id TEXT NOT NULL,
  actor_label TEXT,
  role_ids_json TEXT,
  event_type TEXT NOT NULL,
  feature_code TEXT,
  feature_label TEXT,
  route TEXT,
  action_code TEXT,
  action_label TEXT,
  source TEXT NOT NULL DEFAULT 'LOCAL_UI',
  occurred_at INTEGER NOT NULL,
  metadata_json TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_events_occurred
ON audit_events(occurred_at);

CREATE INDEX IF NOT EXISTS idx_audit_events_actor_time
ON audit_events(actor_credential_id, occurred_at);
```

`event_type` 枚举：

- `LOGIN`：登录成功
- `LOGOUT`：登出
- `FEATURE_ENTER`：进入管理页面/功能
- `BUTTON_CLICK`：管理按钮点击

规则：

- 只在已登录管理账号的有效 session 下记录；
- `session_ref` = `credentialId_loginAt` 形成稳定会话引用；
- `actor_credential_id`、`actor_label`、`role_ids_json` 为操作当时快照；
- `route` 只保存标准路由路径，不含员工 ID 等敏感参数；
- `metadata_json` 仅允许白名单字段，不保存密码、Token、人脸/指纹数据、MQTT 密钥或请求正文；
- 公共员工取还卡继续使用 `operations`，不重复写入审计表；
- 审计写入失败不阻止已授权的页面访问或按钮操作，但打印明确 warning；
- 滚动 30 天清理：每次 `insertAuditEvent` 和 schema 初始化时清理 `occurred_at < now - 30d` 的记录；
- 不对 `operations` 现有保留期做任何缩短。

## 12. 同步游标

```sql
CREATE TABLE IF NOT EXISTS sync_cursors (
  scope TEXT PRIMARY KEY,
  fetched_version INTEGER NOT NULL DEFAULT 0,
  applied_version INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  raw_json TEXT
);
```

建议 `scope`：

- `config`
- `employees`
- `face_bindings`
- `finger_bindings`（仅为后端指纹数据集的同步游标名，不是 SQLite 表名）
- `slots`
- `outbox`

规则：

- 后端明确版本字段前，版本只可为 `0` 或保存后端原始游标；
- 已拉取不等于已应用，批量写入成功后才能推进 `applied_version`；
- 游标冲突或字段缺失时停止增量同步，退回全量同步或登记阻塞。

## 13. HTTP 同步闭环

已接入 V4.2 HTTP 分页同步：

| 数据 | 接口 | SQLite 表 | 游标 |
|---|---|---|---|
| 员工 | `POST /api/v1/employee/sync` | `employees` | `employees` |
| 人脸绑定与照片 | `POST /api/v1/employee/face/sync` | `face_bindings`（`FACE` 行） | `face_bindings` |
| 指纹绑定 | `POST /api/v1/employee/finger/sync` | `face_bindings`（`FINGER` 行） | `finger_bindings`（后端数据集游标） |

落库规则：

- 只在全部分页完成后推进对应 `sync_cursors.applied_version`；
- 中途失败不推进游标，下次仍从旧版本继续；
- 人脸注册页的“刷新员工”属于人工对账动作，固定使用 `lastSyncTime=0` 全量同步；退出管理员模式后先增量同步，再以已注册员工列表对账，只有本机缺失时才补一次全量同步，避免旧增量游标锁死；
- HTTP 非 2xx、响应缺少 `code` 或缺少对应列表字段时视为同步失败，不推进游标；
- 正式 Mock 的分页从 `page=0` 开始；这是用户对当前测试环境的明确确认，优先于 V4.2 文档中的 1-based 描述；
- 人脸同步固定请求 `includeFlags=7`；`faceFeature` 只用于导入 FaceAISDK，`faceImageBase64` 校验后写 `face_bindings` 的 FACE 行，`faceImage` 只保存为服务器路径元数据；
- 人脸同步只接受 `ADD/status=0` 与 `DELETE/status=9`，并验证 `faceAiId=${employeeId}_${faceId}`；
- MQTT `faceChanged` 只触发增量拉取，不直接携带或应用人脸数据；每 5 分钟 HTTP 增量拉取作为离线补偿；
- 网络连接恢复、启动恢复、定时任务和 outbox 不自动重放图片上传或人脸建档；
- 当前页的人脸模板导入、照片写入或绑定写入任一步失败，都不得推进 `face_bindings` 游标；
- 指纹响应中的 `fingerFeature` 必须丢弃；
- `hasMore=true` 但当前页无任何可处理数据时停止并报错，避免循环。

## 14. 业务查询边界

`uniapp/src/services/localStore.js` 已收敛以下业务查询：

- `getEmployeeById(employeeId)`：按员工 ID 读取本机投影，以独立列为准，`raw_json` 只补充展示字段；
- `getFaceBindingById(faceId)`：读取人脸绑定元数据，返回前再次丢弃人脸特征和图片字段；
- `saveLocalFaceBinding(binding)`：只允许为存在且启用的员工写入本机绑定，不存生物特征和图片；
- `getFacePhotoByFaceId(faceId)` / `saveFacePhoto(photo)`：读取或保存应用私有照片，并强制 10 MB 上限；
- `updateFacePhotoUploadState(faceId, update)` / `listPendingFacePhotos(limit)`：维护本机上传状态并为模板恢复或用户显式重试提供照片；不得在启动或联网恢复时据此自动重放请求；
- `resolveEmployeeByFaceId(faceId, atMs)`：显式区分绑定不存在、绑定停用、绑定过期、员工不存在、员工停用和员工过期；
- `getSlotSnapshot(slotNumber)` / `listSlotSnapshots()`：读取卡槽快照并可要求 `fresh=1`；取卡必须在全部最新快照上按状态和电量/电压选槽，不提供按卡号定位卡槽的业务查询；
- `getOperationRecord(operationId)` 和 `listRecoverableOperations(limit)`：用于后续业务状态机续接单条操作和非终态操作。

这些方法只返回本地查询和有效性结果，不执行开门、不上报后端、不把快照命中当作物理业务完成。

## 15. 后续落地顺序

已落地：

1. `uniapp/src/services/localStore.js` 已集中封装 `storage.execute/query`。
2. 启动和配置读取会执行 schema 初始化。
3. 配置页保存会写 `configDraft` 和 `bootstrapConfig`。
4. 远端 config 成功返回后写 `runtimeConfig`。
5. 首页可读取 `runtimeConfig/configDraft` 和 `slots_snapshot` 恢复展示投影。
6. 首页收到 `slot.status` 后写入 `slots_snapshot`。
7. 已创建本机用户、角色、权限、角色权限、可操作内容表。
8. 后台系统超级管理员和开发人员密码会在启动加载和 MQTT `syncConfig` 时整体同步到 `builtin:SUPER_ADMIN`、`builtin:DEVELOPER`，只保存 PBKDF2 哈希；明文不展示、不缓存、不记录日志。
9. 已补齐人脸到员工、实时卡槽快照、单条本地人脸绑定和可恢复操作的查询基础；卡号只作为卡槽状态元数据，不建立工卡定位关系。
10. schema 10 已把人脸绑定、人脸照片和指纹绑定合并到唯一 `face_bindings` 表，并按当前 1:N 契约完成图片上传、后台建档、最终 FaceAISDK 模板、SQLite 照片/绑定和服务器照片下发导入；任一步失败均不保留本机先成功，也不自动重放两步写请求。
11. schema 11 新增 `audit_events` 表用于管理员操作审计（登录/登出/功能入口/按钮点击），按 `occurred_at` 滚动 30 天清理；独立于业务 `operations` 表，不影响取还卡历史和恢复逻辑。
11. 登录、菜单和按钮已接入本机角色/权限校验；用户明确删除的可见权限管理页不属于待完成项。
12. 取还卡、批量操作、固件升级和授权状态等已有明确契约的关键操作会写 `operations`/`outbox_events`，只在后端明确业务成功后标记投递完成。

仍需外部契约确认：

1. 新增 outbox 事件只有在对应 HTTP/MQTT 请求结构、幂等/重复语义和业务成功响应明确后才能接入；不能用“发送成功”代替 ACK。
2. 离线激活的文件格式、签名、设备绑定和过期规则仍为预留，不创建猜测数据。
3. 员工级指纹功能已按用户决定暂停并隐藏，不计为当前未完成项；只有用户重新开放且外接模块/SDK 契约明确后再接入。
