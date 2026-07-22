# 设备对接架构改造：批次 1

更新时间：2026-07-22

## 本批次目标

本批次只建立安全边界、后台会话状态、远程命令幂等和设备操作追踪，不修改现有 UI 布局，也不猜测尚未确认的 100 槽串口拓扑。

已落地：

1. WebView 主页面限制为 `http://127.0.0.1:8088`；使用 AndroidX `WebMessageListener` 将原生桥接限制到精确来源和主框架。不支持该能力的旧 WebView 会关闭原生桥接，而不是回退到无来源隔离的 `JavascriptInterface`。
2. 原生 JSBridge 增加动作白名单与权限策略。页面隐藏菜单不再是唯一权限边界；首次未初始化设备仅允许一次配置引导保存。
3. 管理员原生会话增加 `sessionId`、闲置过期时间和原生权限复核；设备令牌、MQTT 密码、签名密钥等内部字段不再暴露给 H5，也不会被 UI 保存覆盖。
4. MQTT/TCP 后台状态区分传输连接与业务认证：`TRANSPORT_CONNECTED → SUBSCRIBED → LOGIN_SENT → AUTHENTICATED`，登录 15 秒无响应会进入 `AUTH_TIMEOUT` 并重连。
5. 只有 `AUTHENTICATED` 后才接受后台业务指令、启动人员同步和发送普通业务消息。
6. 新增后台指令持久化幂等门：校验 `msgId`、`timestamp`、`deviceCode`，重复消息不重复执行，并缓存原响应。
7. 新增 `DeviceOperationEngine`，开门和批量开门统一生成 `operationId` 并记录阶段。
8. 当前操作明确停在 `BOARD_ACKED`，结果带 `physicalConfirmationRequired=true`，不把单板 ACK 写成物理完成状态。
9. 无可用卡槽时返回 `NO_AVAILABLE_CARD`，不再默认打开 1 号门。
10. 正式 H5 构建禁止原生失败后静默切换 Mock；Mock 仅允许 Vite 开发模式或显式 `VITE_ENABLE_MOCK=true`。
11. 未实现的固件升级不再先返回 `accepted`，而是明确返回 `NOT_SUPPORTED`。

## 暂时保留的兼容行为

当前后台 `cardEvent` 仍在单板开门 ACK 后发送，以避免在后台 TAKE/RETURN 判定契约尚未冻结前直接中断现有联调；事件已增加：

- `operationId`
- `requestMsgId`
- `employeeId`
- `physicalConfirmed=false`

后台在批次 2 完成前不得把该事件当成最终物理取还卡确认。

## 后台下行消息最低要求

所有业务指令必须包含：

```json
{
  "msgId": "MSG-唯一值",
  "cmd": "remoteOpen",
  "timestamp": 0,
  "deviceCode": "设备编码",
  "data": {}
}
```

处理规则：

- 缺少 `msgId`：`MISSING_MSG_ID`
- 缺少 `timestamp`：`MISSING_TIMESTAMP`
- 时间戳与设备时间相差超过 10 分钟：`STALE_COMMAND`
- 缺少 `deviceCode`：`MISSING_DEVICE_CODE`
- `deviceCode` 不一致：`DEVICE_MISMATCH`
- 相同 `msgId` 携带不同内容：`MSG_ID_CONFLICT`
- 相同指令处理中：返回 `PROCESSING`，不再次执行
- 相同指令已完成：重发缓存响应，响应带 `duplicate=true`

## 仍需冻结的外部契约

以下内容未确认前，批次 2 不应直接实现：

1. 100 个槽位是否对应 100 个唯一从机地址；若只有 1–10 重复地址，切组命令或板/通道寻址方式是什么。
2. 后台最终 TAKE/RETURN 的判定时点：开门 ACK、门状态变化、卡状态变化，还是二阶段事件。
3. `loginResp` 的标准成功字段、失败码和超时策略。
4. 后台是否接受 `physicalConfirmed=false` 的开门阶段事件，最终确认事件名称是什么。
5. 远程事件 ACK 的正式消息结构。

## 后续批次

批次 2 将在串口拓扑冻结后实施：

```text
BOARD_ACKED
→ PHYSICAL_PENDING
→ 串口轮询状态边沿
→ PHYSICAL_CONFIRMED / PHYSICAL_TIMEOUT
→ TAKE/RETURN 最终上报
```

批次 3 再把本批次 SharedPreferences 形式的幂等记录迁移到 Room，并处理员工、人脸、指纹增量同步与模板任务队列。
