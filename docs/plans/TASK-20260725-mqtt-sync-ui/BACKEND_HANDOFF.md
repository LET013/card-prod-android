# TASK-20260725：后端/传输交接事项

状态：记录问题，不修改负责人代码

## 1. 问题

客户端现有MQTT传输能够发布`statusReport`，但收到`statusReportResp`后只向上层公开：

```text
source
cmd
msgId
timestamp
```

响应中的`code/msg`没有进入Android数据层公开状态。

## 2. 当前影响

前端可以可靠展示：

- 用户已点击；
- Android已接受请求；
- 当前有/没有可上报卡槽；
- MQTT业务会话是否认证；
- 本地发送调用是否抛错。

前端不能可靠展示：

- 服务端是否返回`code=0`；
- 服务端是否拒绝报文；
- `statusReportResp.msg`；
- 特定`msgId`的最终确认状态。

## 3. 文档依据

V4.1规定：

```json
{
  "cmd": "statusReportResp",
  "data": {
    "code": 0,
    "msg": "success"
  }
}
```

## 4. 第一阶段客户端处理

本任务第一阶段不修改MQTT底层传输。

UI文案必须使用：

```text
已提交上报请求
```

不得使用：

```text
服务端上报成功
服务端已确认
```

## 5. 需要负责人提供的可选能力

只有产品要求展示服务端确认时，传输负责人再提供以下任一公开边界：

### 方案A：最小响应事件

```text
onResponse(msgId, cmd, code, msg, receivedAt)
```

### 方案B：查询接口

```text
getLastResponse(cmd)
```

要求：

- 只公开协议已定义字段；
- 不向Vue泄露Token、signingKey或完整敏感报文；
- 通过Android数据层写Store后再通知Vue；
- 必须按msgId关联，不能把任意Resp当成本次请求成功。

## 6. 非要求

本交接不要求：

- 修改后端服务端；
- 修改Topic；
- 修改签名；
- 增加重试窗口；
- 增加客户端猜测超时；
- 在Vue直接订阅MQTT。
