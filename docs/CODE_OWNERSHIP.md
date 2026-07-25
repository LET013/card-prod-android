# 代码所有权清单

更新时间：2026-07-25

未在本表登记的路径默认：

```text
UNKNOWN_OWNER → READ_ONLY
```

| 路径 | 负责人 | 本代理权限 | 说明 |
|---|---|---|---|
| `uniapp/src/**` | 前端/客户端 | 可修改 | 页面、交互、展示投影、Bridge调用 |
| `app/src/main/java/com/xingyao/card/JsBridge.java` | 客户端 | 可修改 | 只允许进入Facade |
| `app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java` | 客户端 | 可修改 | WebView唯一Android门面 |
| `app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java` | 客户端 | 可修改 | 客户端Bridge动作与权限 |
| `app/src/main/java/com/xingyao/card/core/DeviceRuntimeRegistry.java` | 客户端 | 可修改 | 当前数据层引用 |
| `app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java` | 客户端 | 可修改 | 客户端业务编排 |
| `app/src/main/java/com/xingyao/card/core/DeviceStateStore.java` | 客户端 | 可修改 | 客户端状态唯一投影源 |
| `app/src/main/java/com/xingyao/card/core/DeviceDataRepository.java` | 客户端 | 可修改 | 员工/人脸/指纹Map |
| `app/src/main/java/com/xingyao/card/core/SlotStateRepository.java` | 客户端 | 可修改 | 逻辑卡槽Map，不负责串口地址映射 |
| `app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java` | 客户端业务协调 | 可修改 | 只允许复用已确认协议，不得修改MQTT底层 |
| `app/src/test/**` | 客户端 | 可修改 | 客户端单元测试 |
| `docs/plans/**` | 客户端设计 | 可修改 | 审计、需求、设计、决策、验证 |
| `docs/source-2026-07-02/**` | 契约源 | 只读 | 原始Markdown/PDF/XLSX证据，不改写 |
| `app/src/main/java/com/xingyao/card/core/BackendTransportManager.java` | 后端通信负责人 | 只读 | MQTT连接、Topic、Envelope、签名、QoS、响应解析 |
| `app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java` | 后端通信负责人 | 只读 | HTTP通信边界 |
| `app/src/main/java/com/xingyao/card/core/BackendHttpClient.java` | 后端通信负责人 | 只读 | HTTP底层 |
| `serialport/**` | 串口负责人 | 只读 | JNI、C/C++、Java封装、线程、协议 |
| `serial-debug/**` | 串口负责人 | 只读 | 串口调试实现 |
| `app/libs/serialport-release-1.0.aar` | 串口负责人 | 只读 | 禁止替换、反编译、重新打包 |
| `app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java` | 串口负责人 | 只读 | 串口连接与公开回调 |
| `app/src/main/java/com/xingyao/card/core/WorkCardProtocol.java` | 串口负责人 | 只读 | 帧、CRC、功能码 |
| `app/src/main/java/com/xingyao/card/core/FaceAiManager.java` | 人脸负责人 | 只读 | FaceAISDK适配 |
| `app/src/main/java/com/xingyao/card/FaceEnrollmentController.java` | 人脸负责人 | 只读 | CameraX/录入流程 |
| `app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java` | 人脸负责人 | 只读 | 旧独立Activity |
| `app/src/main/java/com/xingyao/card/MainActivity.java`中的CameraX/FaceAISDK部分 | 人脸负责人 | 只读 | 只能消费公开结果 |
| `app/build.gradle`中的FaceAISDK/CameraX依赖 | 人脸负责人 | 只读 | 禁止替换识别方案 |

## 越权处理规则

发现后端、串口或人脸负责人能力无法满足客户端需求时：

1. 停止修改负责人文件；
2. 记录调用入口、输入、实际输出、期望输出和文档证据；
3. 输出对应 `BACKEND_HANDOFF.md`、`SERIAL_HANDOFF.md` 或负责人交接文档；
4. 客户端保持明确失败、禁用或只读状态；
5. 不创建第二套底层实现。

突破只读边界必须同时取得用户和对应负责人针对具体文件、具体修改内容的明确授权，并写入任务 `DECISIONS.md`。