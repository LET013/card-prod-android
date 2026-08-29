# 终端多张人脸客户端契约

更新日期：2026-08-06

## 1. 证据与优先级

本文件整理自用户于 2026-08-06 提供的《客户端接口调整指南-多张人脸录入.md》，是当前终端 1:N 人脸实现的专项契约。它只覆盖旧 V4.2 文档和 2026-08-04 决定中的人脸上传、录入、同步和 FaceAI ID 规则；员工、配置、指纹及其他 HTTP/MQTT 契约仍以原文档为准。

## 2. 唯一标识与数量边界

- 一个员工可有多条平等的人脸记录，不区分主图和扩展图。
- 服务端 `faceId` 是人脸记录唯一标识；FaceAISDK 使用严格的 `${employeeId}_${faceId}` 作为 `faceAiId`。
- Vue SQLite 的唯一物理生物绑定表为 `face_bindings`；FACE 行同时保存 `binding_id=faceId`、`native_id=faceAiId`、`employee_id` 和应用私有人脸照片字段，识别结果按完整 `faceAiId` 查询绑定。指纹兼容元数据使用同表 FINGER 行，不另建 `face_photos`、`finger_bindings` 或 `biometric_records`。
- 同一图片按 SHA-256 全局去重。
- 每名员工的人脸上限由服务端 `system.face.maxPerEmployee` 管理，默认 3；它不是设备 config 字段，客户端不增加配置项或硬编码拦截，只展示服务端返回的上限错误。

## 3. 终端录入顺序

1. Vue 校验员工存在、启用、未过期，并创建可恢复的 `FACE_ENROLLMENT` 操作记录。
2. Android 用临时 FaceAI ID 完成采集并返回私有照片；此时只代表本机采集成功，不代表添加人脸成功。
3. Vue 校验照片不超过 10 MB，计算 SHA-256，并先检查本机全局重复哈希。
4. Vue 先通过 HTTP `POST /api/v1/employee/face/image` 以 `multipart/form-data` 提交 `employeeId + file`，取得 `faceImagePath + fileHash` 并校验 SHA-256。
5. Vue 再调用人脸注册接口提交 `employeeId + fileHash + faceImagePath + faceFeature`，期间保持 Loading；只有服务器明确返回成功业务码和匹配的 `employeeId` 才显示“添加成功”。
6. 服务器失败、超时或响应不完整时移除临时模板并提示“添加人脸失败”，不得把本机采集成功显示为添加成功。
7. 服务器成功后同样移除临时模板；录入页不导入正式 FaceAISDK 模板，也不写本地照片或绑定。
8. 退出管理员模式时先合并触发一次人脸增量同步；再调用 `GET /api/v1/employee/face/registered` 对账后台已登记员工与本机有效 FACE 绑定。仅发现缺失时以 `lastSyncTime=0` 补一次全量人脸同步；模板、照片和绑定全部成功后才显示同步完成。全量后仍缺失时显示后台登记与同步数据不一致，不能伪造本机人脸。

添加请求失败时移除临时模板，不保留“本机先成功”状态。添加请求没有幂等键证据，启动、联网恢复、定时任务和 outbox 均不得自动重放；服务器已成功添加的数据只通过后续增量同步进入本机正式模板和绑定。

## 4. 增量同步

- 接口：`POST /api/v1/employee/face/sync`，分页从 0 开始，`pageSize=10`，最大 30，当前终端请求 `includeFlags=7`。
- `ADD + status="0"`：验证 `faceAiId` 精确等于 `${employeeId}_${faceId}`，导入/更新 FaceAISDK，保存照片和绑定。
- `DELETE + status="9"`：按 `faceAiId` 移除 FaceAISDK 模板并停用本机绑定/照片。
- 其他 `syncAction/status` 组合视为契约错误，不推进游标。
- 所有分页和本机应用步骤成功后才保存最终 `syncVersion`；中途失败保留旧游标。
- MQTT 下行 `faceChanged` 只作为触发信号，终端立即增量拉取；另每 5 分钟执行一次 HTTP 增量拉取，以补齐离线期间变更。
- 部门树过滤完全由服务端负责，客户端不自行扩大或缩小员工范围。

## 5. 数据与安全边界

- 人脸照片只保存在 Vue 管理的应用私有 SQLite，不写公共媒体目录。
- 人脸特征不写 Vue SQLite、日志、诊断或操作记录，只在采集和 FaceAISDK 导入调用中短暂流转。
- 本次实现复用既有 `face.enrollment.*` 和 `face.template.import/remove` 通用能力，不需要修改 Android。
- 管理后台的 `/system/employee/{employeeId}/faces` 和删除接口不属于终端调用范围；终端通过同步感知后台软删除。
