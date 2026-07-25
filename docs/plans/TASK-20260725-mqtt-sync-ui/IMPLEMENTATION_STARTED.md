# ITEM-01 实现门禁记录

- 专项审计：`ITEM-01-MQTT_STATUS_REPORT_AUDIT.md`
- 用户确认：`DECISIONS.md` D-005、D-009
- 允许实现：公开首页入口、状态面板、`status.reportNow`客户端动作、复用现有`statusReport`链、本地状态投影
- 禁止实现：员工同步、人脸同步、指纹同步、MQTT底层修改、串口修改、人脸专业代码修改
- 实现分支：`feature/mqtt-status-report-ui`
