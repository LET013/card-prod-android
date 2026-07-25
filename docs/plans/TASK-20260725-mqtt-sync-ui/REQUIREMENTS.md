# TASK-20260725：需求标准化

状态：等待用户确认，禁止修改运行代码

## 1. 目标

在不修改后端服务端、MQTT底层传输和串口负责人代码的前提下，为现有客户端能力增加公开UI触发入口：

1. 立即请求一次MQTT卡槽状态上报；
2. 手动同步全部数据；
3. 单独同步员工；
4. 单独同步人脸；
5. 单独同步指纹；
6. 展示同步过程、数量、版本、错误和最后更新时间。

## 2. 当前行为

- 客户端认证成功后自动执行一次增量全同步。
- MQTT下行`syncUser`会触发一次增量全同步。
- 客户端每隔本地配置的时间尝试发送`statusReport`。
- Vue没有手动同步或立即状态上报入口。
- 员工页面位于管理员区域。
- UI没有完整投影`sync.statusChanged`和`sync.completed`。

## 3. 目标行为

### R-001 公开同步入口

首页必须存在不要求管理员登录的明确入口，用于打开数据同步面板。

禁止使用隐藏点击区域、连续点击次数或无法发现的调试手势。

### R-002 五种公开动作

同步面板必须提供：

- 立即上报卡槽状态；
- 同步全部；
- 同步员工；
- 同步人脸；
- 同步指纹。

### R-003 Vue仅触发

Vue只能通过：

```text
services/index.js → nativeBridge.js → Android Facade
```

触发动作。

Vue不得直接建立MQTT连接、发HTTP分页请求、保存同步游标或合并业务Map。

### R-004 复用已有同步实现

员工、人脸和指纹同步必须复用现有`DeviceDataSyncManager`：

- `syncAll(false)`
- `syncEmployees(false)`
- `syncFaces(false)`
- `syncFingers(false)`

第一阶段默认只提供增量同步，不在公开页面提供全量重置游标按钮。

### R-005 复用已有状态上报

立即状态上报必须复用现有`statusReport`协议和`DeviceCommandCoordinator.reportSlotSnapshot()`，不得在Vue或Facade重新组装MQTT报文。

### R-006 公开动作无需管理员会话

以下Bridge动作必须定义为公开动作：

```text
sync.all
sync.employees
sync.faces
sync.fingers
status.reportNow
```

公开不等于无约束：动作仍只能由可信、受来源限制的内置WebView调用。

### R-007 状态投影

Android数据层必须先更新`DeviceStateStore.sync`，再发UI事件。

Vue必须展示至少：

- 当前状态；
- 当前scope；
- 提示信息；
- employeeCount；
- faceCount；
- fingerCount；
- 各同步版本；
- startedAt；
- completedAt或failedAt；
- 最后错误。

### R-008 防止重复触发

一个同步动作执行期间，UI必须禁用全部同步按钮，避免重复点击。

Android端不得新增第二套同步队列。现有同步方法的串行能力继续作为底层保护。

### R-009 状态上报结果语义

第一阶段UI只能显示：

- 已请求；
- 已提交到客户端上报链；
- 无已知卡槽状态；
- 后端会话未认证；
- 本地发送失败。

在底层未公开`statusReportResp.code/msg`前，禁止显示“服务端已确认成功”。

### R-010 无已知卡槽状态

当卡槽Map没有`updatedAt > 0`的真实记录时，立即上报不得伪造EMPTY/OCCUPIED等状态；必须向UI返回“当前没有可上报的已确认卡槽状态”。

### R-011 保持现有自动行为

不得破坏：

- 启动后自动增量全同步；
- MQTT下行`syncUser`增量全同步；
- 现有周期`statusReport`；
- 员工、人脸、指纹Map和同步游标；
- 人脸模板Fetched/Applied游标语义。

### R-012 所有权边界

本任务禁止修改：

- 后端服务端代码；
- `BackendTransportManager`；
- `BackendHttpGateway`/`BackendHttpClient`底层行为；
- `serialport/**`；
- `serial-debug/**`；
- `app/libs/serialport-release-1.0.aar`；
- 串口地址映射、轮询和帧解析。

## 4. 非范围

- 新增服务端接口；
- 修改MQTT Topic、签名算法、Envelope或QoS；
- 通过MQTT承载大批量员工/人脸/指纹数据；
- 修改串口卡槽映射；
- 员工级指纹硬件应用；
- 全量同步的公开UI入口；
- 后台ACK关联和重发队列；
- 修改人脸本地录入流程；
- 修复首次设置管理员会话回归；
- OTA、日志Outbox或真实统计上报。

## 5. 不变量

1. Android Map/Repository是员工、人脸、指纹和卡槽状态唯一真相。
2. Vue只持有当前WebView生命周期的展示投影。
3. 后端响应先进入Android数据层，再通知Vue。
4. 同步游标只在现有数据层规则允许时推进。
5. 人脸模板导入失败时不得推进Applied游标。
6. 指纹同步只表示数据已缓存，不表示外接指纹硬件已应用。
7. `statusReport`只包含真实已知卡槽。
8. 本地调用完成不等于服务端ACK成功。

## 6. 外部契约

### MQTT状态上报

命令：`statusReport`

```json
{
  "data": {
    "slots": [
      {
        "slotId": 1,
        "status": "OCCUPIED",
        "cardNo": "CARD001",
        "voltage": 12.5,
        "current": 0.5,
        "chargeStatus": "FULL",
        "faultCode": 0
      }
    ]
  }
}
```

外层Envelope继续由现有底层传输生成。

### 员工同步

`POST /api/v1/employee/sync`

请求字段：`lastSyncTime/page/pageSize`

### 人脸同步

`POST /api/v1/employee/face/sync`

请求字段：`lastSyncTime/page/pageSize/includeFlags`

### 指纹同步

`POST /api/v1/employee/finger/sync`

请求字段：`lastSyncTime/page/pageSize`

## 7. 验收标准

### AC-001

未登录管理员时，首页可打开同步面板。

### AC-002

未登录管理员时，可触发员工、人脸、指纹和全部增量同步。

### AC-003

同步按钮只调用Bridge，不出现Vue MQTT、HTTP同步请求或业务Map合并代码。

### AC-004

手动同步执行期间，面板显示`SYNCING`并禁用重复触发。

### AC-005

员工同步完成后，员工列表从Android Map重新投影到Vue。

### AC-006

人脸同步结果展示`faceFetchedVersion`、`faceAppliedVersion`和模板导入失败数。

### AC-007

指纹同步结果明确显示“数据已缓存；员工级外接指纹硬件未应用”。

### AC-008

全部同步继续复用现有`syncAll(false)`，不复制分页逻辑。

### AC-009

立即状态上报只发送`updatedAt > 0`的卡槽。

### AC-010

没有已知卡槽时，UI显示“无可上报状态”，且不生成虚假卡槽数据。

### AC-011

后端会话未认证时，立即状态上报显示明确错误，不静默显示成功。

### AC-012

UI不得显示“服务端确认成功”，除非未来存在可验证的`statusReportResp.code=0`回调。

### AC-013

启动自动同步和MQTT`syncUser`下行行为与修改前一致。

### AC-014

最终diff不包含后端底层传输、HTTP底层、串口和AAR文件。

### AC-015

前端语法、H5构建、Android单测和Debug APK构建通过。

## 8. 待确认项

### Q-001 UI方案

建议：在首页`CabinetHeader`管理员图标左侧增加公开“同步”图标，点击后打开同步状态面板。

### Q-002 默认同步模式

建议：公开入口只执行增量同步；全量同步不暴露给普通用户。

### Q-003 状态上报确认文案

建议：第一阶段使用“已提交上报请求”，不使用“上报成功”。

### Q-004 周期上报

建议：保留现有周期上报，不在本任务调整10000毫秒本地默认值，也不新增事件防抖间隔。
