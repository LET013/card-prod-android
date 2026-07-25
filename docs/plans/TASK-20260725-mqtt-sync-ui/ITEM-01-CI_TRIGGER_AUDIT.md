# ITEM-01：两阶段PR的CI触发审计

状态：允许实施，仅修改永久CI触发范围。

## 1. 现象

`.github/workflows/device-integration-ci.yml`当前只在PR目标分支为以下两项时运行：

```text
main
reference/motone-current
```

本任务遵循AGENTS的两阶段流程：

```text
feature/mqtt-sync-ui-plan
→ feature/mqtt-status-report-ui
```

因此实现PR #4不会自动运行永久CI，不能完成编译和门禁验证。

## 2. 边界

允许修改：

- `.github/workflows/device-integration-ci.yml`中的`pull_request.branches`触发范围。

禁止修改：

- 任何静态门禁断言；
- H5、单测、APK和JNI检查步骤；
- 后端、串口、人脸负责人代码；
- 测试通过条件；
- 使用临时workflow替代永久CI。

## 3. 最小方案

在现有目标分支基础上增加：

```yaml
- 'fix/**'
- 'feature/**'
```

使设计PR和实现PR均能使用同一永久门禁。

## 4. 风险

- CI运行次数增加；
- 不改变构建产物、协议、运行逻辑或测试标准；
- 比创建一次性workflow更符合仓库卫生规则。

## 5. 验收

- PR #4自动出现`Device integration CI`；
- 原静态门禁、前端构建、Android单测和APK检查全部保留；
- 最终仓库不存在临时workflow。