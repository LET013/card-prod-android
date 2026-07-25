# TASK-20260725：验证报告

状态：PLANNED / NOT EXECUTED

当前仅完成只读审计和设计文档，尚未修改运行代码，因此所有实现验证均标记为未执行。

## 1. 需求追踪矩阵

| 需求/验收 | 计划实现位置 | 计划测试 | 当前结果 |
|---|---|---|---|
| R-001 / AC-001 | `CabinetHeader.vue`、`index.vue` | 未登录管理员打开面板 | 未执行 |
| R-002 / AC-002 | `SyncPanel.vue`、Facade公开动作 | 五个按钮逐项触发 | 未执行 |
| R-003 / AC-003 | `services/index.js`、Bridge | 搜索Vue中MQTT/HTTP直连 | 未执行 |
| R-004 / AC-008 | `DeviceDataLayer.java` | 验证复用现有SyncManager | 未执行 |
| R-005 / AC-009 | `DeviceDataLayer.java` | known slots上报 | 未执行 |
| R-006 | `NativeActionPolicy.java` | 无会话授权测试 | 未执行 |
| R-007 | `App.vue`、`appState` | sync事件投影 | 未执行 |
| R-008 / AC-004 | `SyncPanel.vue` | 连续点击防重复 | 未执行 |
| R-009 / AC-012 | UI文案 | 不出现服务端ACK误报 | 未执行 |
| R-010 / AC-010 | `DeviceDataLayer.java` | 空Map上报 | 未执行 |
| R-011 / AC-013 | 现有启动/下行链 | 回归测试 | 未执行 |
| R-012 / AC-014 | CI/diff审计 | 保护路径检查 | 未执行 |

## 2. 必测场景

### 2.1 UI与权限

- 未登录管理员打开同步面板；
- 未登录管理员触发四种同步；
- 未登录管理员触发立即状态上报；
- 管理员登录状态不影响公开同步入口；
- 同步中按钮禁用；
- 失败后按钮恢复；
- 面板关闭后同步结果仍由全局投影接收。

### 2.2 同步

- 员工增量同步成功；
- 员工删除ID应用；
- 人脸特征同步成功；
- 人脸图片URL同步成功；
- 人脸模板部分失败时Applied游标不推进；
- 指纹同步只写缓存；
- 全部同步顺序和数量正确；
- HTTP离线/超时；
- 返回空集合；
- 分页超过安全上限；
- 连续点击；
- 启动自动同步与手动同步接近同时发生；
- MQTT下行`syncUser`与手动同步接近同时发生。

### 2.3 状态上报

- MQTT已认证且有已知卡槽；
- MQTT未认证；
- 无已知卡槽；
- 只有部分已知卡槽；
- 状态值映射为V4.1枚举；
- 本地发送失败；
- UI只显示本地可证明结果；
- 周期上报行为未回退。

### 2.4 重启与恢复

- APP重启后员工、人脸、指纹Map从Android持久化恢复；
- 进行中的UI状态不伪装为继续运行；
- 同步版本恢复；
- 后端重连后启动自动同步仍只执行一次。

## 3. 静态检查

计划运行：

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
```

并检查：

- Vue中不存在Paho、WebSocket、MQTT凭证或HTTP同步分页；
- 外部协议字段未新增；
- Bridge动作只存在于客户端内部；
- 没有修改只读所有权文件。

## 4. 构建检查

计划运行：

```bash
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
```

## 5. 文件所有权检查

最终diff不得包含：

```text
BackendTransportManager.java
BackendHttpGateway.java
BackendHttpClient.java
DeviceCommandCoordinator.java
SerialConnectionManager.java
WorkCardProtocol.java
serialport/**
serial-debug/**
app/libs/**
```

## 6. 真机验证

以下项目在没有设备证据前必须报告为“未验证”：

- rk3568_r安装；
- MQTT真实Broker登录；
- `statusReport`服务端接收；
- 后端三类同步真实数据；
- FaceAISDK真实模板导入；
- 串口真实卡槽状态进入Map。
