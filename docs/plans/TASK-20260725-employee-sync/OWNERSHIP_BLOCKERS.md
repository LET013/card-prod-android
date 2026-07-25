# TASK-20260725：人员同步所有权与阻塞项

状态：已登记

## 1. 当前是否阻塞公开人员同步设计

否。

现有客户端已经具备完整人员分页同步入口，公开按钮可以只复用客户端能力，不修改后端、HTTP底层、MQTT底层、串口或人脸专业代码。

## 2. 人脸模板删除跨域风险

### 现象

人员同步接收 `deletedEmployeeIds` 后，现有 `DeviceDataSyncManager.deleteEmployeeTemplates()` 会逐项调用：

```java
FaceAiManager.deleteTemplate(employeeId)
```

随后才提交员工Map删除、关联人脸/指纹缓存删除和人员游标。

### 现有失败窗口

多个员工模板删除过程中，如果后续某一项抛出异常：

```text
前面的模板可能已删除
→ 员工Map尚未提交
→ employeeSyncVersion尚未推进
→ 同步整体返回失败
```

这可能造成短期的FaceAISDK模板与员工Map不一致。

### 所有权

- `DeviceDataSyncManager`：客户端范围；
- `FaceAiManager`：人脸负责人只读范围；
- FaceAISDK是否支持批量事务、幂等删除结果或可靠回滚：当前无明确契约。

### 本批处理

本批不：

- 修改 `FaceAiManager`；
- 设计批量模板事务；
- 创建模板删除重试队列；
- 吞掉模板删除失败；
- 把失败当成功推进游标。

本批只：

- 继续复用现有语义；
- 准确显示人员同步失败；
- 不清空Vue员工列表；
- 在交付中明确该路径未完成专项真机验证。

### 负责人后续需要确认

1. `deleteTemplate`对不存在模板是否幂等；
2. 删除失败是否抛异常；
3. 是否有批量删除接口；
4. 是否可获取逐项结果；
5. 是否需要先提交员工禁用，再异步清理模板；
6. 识别引擎遇到已删除员工残留模板时如何处置。

这些内容未确认前，不在客户端猜测补全。

## 3. 后端契约阻塞项

当前无阻塞。

V4.1 已定义：

- `POST /api/v1/employee/sync`；
- `lastSyncTime/page/pageSize`；
- `employees`；
- `deletedEmployeeIds`；
- `syncVersion/hasMore`。

本批不需要新增后端接口或字段。

## 4. HTTP底层阻塞项

当前无阻塞。

现有 `BackendHttpGateway.postData()` 已被人员同步使用。本批只调用现有边界，不修改：

- Bearer；
- 超时；
- 重试；
- 响应解析；
- 错误码。

## 5. MQTT阻塞项

当前无阻塞。

公开人员同步不通过MQTT发送数据，也不依赖MQTT认证。MQTT只保留现有服务端下行 `syncUser` 流程。

## 6. 串口阻塞项

无关。

人员同步不读取或修改串口状态、槽位映射、AAR或轮询。

## 7. 测试能力缺口

当前 JVM 测试依赖只有：

- JUnit；
- `org.json`。

没有：

- MockWebServer；
- Mockito；
- Robolectric。

因此在不扩大依赖和不修改专业文件的前提下，CI可以验证：

- 权限动作；
- 静态调用链；
- 编译；
- H5；
- APK。

但真实分页、删除ID和持久化恢复仍需要集成环境或真机。

本批不得为了“测试方便”擅自修改 `app/build.gradle` 中人脸专业依赖或创建假后端实现。

## 8. 结论

人员同步公开入口可以进入实现，但必须先由用户确认：

1. 复用当前面板并抽取组件；
2. 只做增量人员同步；
3. 现有同步任务运行时返回BUSY；
4. 保留deletedEmployeeIds当前清理语义；
5. 接受人脸模板批量删除事务问题作为已登记、未在本批修复的既有风险。
