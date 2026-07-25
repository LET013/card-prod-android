# TASK-20260725：第一实现子项范围锁定

状态：用户已确认开工

确认来源：用户回复“开始”。

## 本批唯一目标

在首页提供无需管理员会话的公开入口，触发一次现有 MQTT `statusReport` 卡槽状态上报，并展示客户端本地能够证明的结果。

## 允许修改

- `uniapp/src/components/CabinetHeader.vue`
- `uniapp/src/pages/index/index.vue`
- `uniapp/src/services/index.js`
- `app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java`
- `app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java`
- `app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java`
- `app/src/test/java/com/xingyao/card/core/NativeActionPolicyTest.java`

实现中只有在无法准确返回本地发送失败时，才允许最小修改客户端业务协调器 `DeviceCommandCoordinator.java`；不得修改 MQTT 连接、Topic、签名、Envelope 或响应解析。

## 明确禁止修改

- `BackendTransportManager.java`
- `BackendHttpGateway.java`
- `BackendHttpClient.java`
- 后端服务端代码与接口 Markdown 原文
- `serialport/**`
- `serial-debug/**`
- `app/libs/**`
- 串口地址映射、轮询、帧解析
- 员工、人脸、指纹同步动作

## 本地结果语义

- `BLOCKED`：后端业务会话未认证；
- `NO_DATA`：没有 `updatedAt > 0` 的真实卡槽；
- `SUBMITTED`：现有客户端发送链已接受请求；
- `FAILED`：客户端本地发送失败。

`SUBMITTED` 不等于服务端 `statusReportResp.code=0`，UI 禁止显示“服务端确认成功”。

## 不变量

1. 只发送 Android 卡槽 Map 中真实已知状态；
2. 不生成默认 EMPTY/OCCUPIED 数据；
3. 不新增 MQTT 字段、Topic、连接或签名逻辑；
4. 保留现有周期上报；
5. Vue 只触发 Bridge 并展示结果；
6. 不要求管理员登录；
7. 不触碰人员、人脸、指纹同步。
