# TASK-20260725：验证报告

状态：第一实现子项自动验证通过；真机验证未执行

本报告只覆盖用户已确认开工的第一子项：公开触发一次现有 MQTT `statusReport` 状态上报。员工、人脸、指纹同步仍处于未开工状态。

## 1. 实现提交

- 分支：`feature/mqtt-status-report-now`
- 干净实现PR：#5
- 自动验证PR：#6，仅用于触发永久CI，不合并
- 已验证提交：`d7a3cd78f5511b83c87e101f8230dc52e8332c36`
- CI运行：`30174885461`
- CI结果：`success`

## 2. 需求追踪矩阵

| 需求/验收 | 实现位置 | 验证 | 结果 |
|---|---|---|---|
| D-005 / AC-001 | `CabinetHeader.vue`、`index.vue` | 首页存在公开入口，不依赖管理员页面 | 通过静态审计与H5构建 |
| D-007 | `DeviceCommandCoordinator.reportSlotSnapshotNow()` | 返回`BLOCKED/NO_DATA/SUBMITTED/FAILED` | 通过编译；真机分支未验证 |
| R-003 | `services/index.js` | Vue只调用`status.reportNow` Bridge | 通过静态审计 |
| R-005 / AC-009 | `DeviceDataLayer.reportStatusNow()` | 复用现有`statusReport`组装与发送链 | 通过静态审计 |
| R-006 | `NativeActionPolicy.java` | `status.reportNow`为公开动作 | 单元测试通过 |
| R-009 / AC-012 | `index.vue` | 明确“已提交不等于服务端ACK” | 通过静态审计与H5构建 |
| R-010 / AC-010 | `DeviceCommandCoordinator` | 只收集`updatedAt > 0`卡槽；空Map返回`NO_DATA` | 通过静态审计 |
| R-011 / AC-013 | 现有周期调用`reportSlotSnapshot()` | 周期入口继续存在并复用同一内部方法 | 通过diff审计 |
| R-012 / AC-014 | 最终diff | 未修改传输、HTTP底层、串口、AAR | 通过diff审计与永久CI |
| AC-015 | 永久CI | H5、单测、Debug APK | 全部通过 |

## 3. 实际修改文件

```text
app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java
app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java
app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java
app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java
app/src/test/java/com/xingyao/card/core/NativeActionPolicyTest.java
uniapp/src/components/CabinetHeader.vue
uniapp/src/pages/index/index.vue
uniapp/src/services/index.js
docs/plans/TASK-20260725-mqtt-sync-ui/IMPLEMENTATION_SCOPE.md
docs/plans/TASK-20260725-mqtt-sync-ui/VALIDATION_REPORT.md
```

`DeviceCommandCoordinator.java`属于Android客户端业务协调层。本次仅将现有状态上报方法拆为周期入口和可返回本地结果的手动入口，没有修改MQTT连接、Topic、签名、Envelope、QoS或响应解析。

## 4. 所有权审计

未修改：

```text
BackendTransportManager.java
BackendHttpGateway.java
BackendHttpClient.java
SerialConnectionManager.java
WorkCardProtocol.java
serialport/**
serial-debug/**
app/libs/**
```

未新增第二套MQTT实现、HTTP同步实现、卡槽映射或串口实现。

## 5. 自动验证

永久CI运行 `30174885461` 已通过：

- 严格三层、契约与仓库污染检查；
- 前端JavaScript语法检查；
- uni-app H5真实构建；
- H5写入Android assets；
- Android JVM单元测试；
- Debug APK构建与内容检查。

## 6. 结果语义审计

### `BLOCKED`

条件：后端业务会话未认证。

UI文案不会显示发送成功。

### `NO_DATA`

条件：Android卡槽Map中不存在`updatedAt > 0`的真实卡槽。

不生成默认`EMPTY/OCCUPIED`状态。

### `SUBMITTED`

条件：现有客户端发送链已接受`statusReport`请求。

返回`ackTracked=false`，UI明确说明不等于服务端ACK。

### `FAILED`

条件：客户端本地组装或发送调用抛出异常。

只返回本地失败，不推断服务端状态。

## 7. 未验证

以下项目没有设备或后端证据，仍标记为未验证：

- rk3568_r安装；
- 真实MQTT Broker连接与登录；
- 服务端是否收到`statusReport`；
- `statusReportResp.code/msg`；
- 真实串口卡槽状态是否进入Android Map；
- 公开按钮在目标竖屏设备上的触控尺寸与视觉效果。

## 8. 后续子项

员工同步、人脸同步和指纹同步没有进入本批。每一项必须重新执行专项审计、更新设计并获得用户确认后才能开工。
