from pathlib import Path
import subprocess

SOURCE = "origin/fix/device-integration-architecture"

PATHS = [
    ".gitattributes",
    "AGENTS.md",
    ".agents/skills",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/xingyao/card/JsBridge.java",
    "app/src/main/java/com/xingyao/card/MainActivity.java",
    "app/src/main/java/com/xingyao/card/WebViewManager.java",
    "app/src/main/java/com/xingyao/card/LocalHttpServer.java",
    "app/src/main/java/com/xingyao/card/FaceEnrollmentController.java",
    "app/src/main/java/com/xingyao/card/core/BackendEndpointSettings.java",
    "app/src/main/java/com/xingyao/card/core/BackendHttpClient.java",
    "app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java",
    "app/src/main/java/com/xingyao/card/core/BackendTransportManager.java",
    "app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java",
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    "app/src/main/java/com/xingyao/card/core/DeviceConfigMapper.java",
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    "app/src/main/java/com/xingyao/card/core/DeviceDataRepository.java",
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    "app/src/main/java/com/xingyao/card/core/DeviceEventLogRepository.java",
    "app/src/main/java/com/xingyao/card/core/DeviceOperationEngine.java",
    "app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java",
    "app/src/main/java/com/xingyao/card/core/DeviceRuntimeRegistry.java",
    "app/src/main/java/com/xingyao/card/core/DeviceStateStore.java",
    "app/src/main/java/com/xingyao/card/core/DocumentedBackendService.java",
    "app/src/main/java/com/xingyao/card/core/FaceAiManager.java",
    "app/src/main/java/com/xingyao/card/core/InboundCommandRepository.java",
    "app/src/main/java/com/xingyao/card/core/JsonCanonicalizer.java",
    "app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java",
    "app/src/main/java/com/xingyao/card/core/NativeAuthManager.java",
    "app/src/main/java/com/xingyao/card/core/NativeSettingsRepository.java",
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    "app/src/main/java/com/xingyao/card/core/SlotStateRepository.java",
    "app/src/main/java/com/xingyao/card/core/WorkCardProtocol.java",
    "app/src/main/java/com/xingyao/card/serial/SerialManager.java",
    "app/src/main/java/com/xingyao/card/serial/SerialPort.java",
    "app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
    "app/src/test/java/com/xingyao/card/core",
    "uniapp/src/App.vue",
    "uniapp/src/mock/data.js",
    "uniapp/src/pages/config/config.vue",
    "uniapp/src/pages/feature/feature.vue",
    "uniapp/src/pages/serial-demo/serial-demo.vue",
    "uniapp/src/pages/splash/splash.vue",
    "uniapp/src/services/index.js",
    "uniapp/src/services/mockService.js",
    "uniapp/src/services/nativeBridge.js",
    "uniapp/src/state/appState.js",
    "docs/CODEX_PROJECT_GUIDE.md",
    "docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md",
    "docs/CONTRACT_EVIDENCE_REGISTER.md",
    "docs/DEVICE_CONFIGURATION_AND_INTERFACE_AUDIT.md",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradlew",
    "gradlew.bat",
]

subprocess.run(["git", "checkout", SOURCE, "--", *PATHS], check=True)

# Preserve reference generated-file exclusions and explicitly track the wrapper binary.
ignore = Path(".gitignore")
value = ignore.read_text(encoding="utf-8")
if "!gradle/wrapper/gradle-wrapper.jar" not in value:
    value = value.rstrip() + "\n\n# Keep the reproducible Gradle Wrapper binary\n!gradle/wrapper/gradle-wrapper.jar\n"
ignore.write_text(value, encoding="utf-8")

# Correct remaining historical names/statements in the project guide.
guide = Path("docs/CODEX_PROJECT_GUIDE.md")
text = guide.read_text(encoding="utf-8")
text = text.replace(
    "SerialConnectionManager / WebSocketConnectionManager /\nBackendHttpGateway / ArcFaceManager",
    "SerialConnectionManager / BackendTransportManager /\nBackendHttpGateway / FaceAiManager",
)
text = text.replace(
    "WebSocketConnectionManager\n→ Listener.onCommand",
    "BackendTransportManager\n→ Listener.onCommand",
)
text = text.replace(
    "→ 启动串口、MQTT、ArcFace",
    "→ 启动JNI串口、后端传输、FaceAISDK",
)
text = text.replace(
    "当前开门 ACK 仍不是最终物理取还卡成功。`cardEvent.physicalConfirmed=false` 必须保留到二阶段确认完成。",
    "当前开门ACK只保留为Android本地操作阶段。在物理确认规则和串口拓扑明确前，不发送`cardEvent`、TAKE或RETURN成功事件。",
)
text = text.replace(
    "5. 增量同步版本、删除和 tombstone 语义；\n6. 后台人脸特征与设备 SDK 版本兼容性；\n7. 外接员工级指纹模块协议。",
    "5. 外接员工级指纹模块协议；\n6. OTA安装、校验、重启恢复和回滚；\n7. 目标rk3568_r真机安装、串口、网络和FaceAISDK联调。",
)
guide.write_text(text, encoding="utf-8")

Path("docs/REFERENCE_UNUSED_AND_MISSING.md").write_text(
    """# Motone基线：无用候选与缺失项登记

更新时间：2026-07-23

本文件只登记静态调用关系与Markdown证据。未得到用户确认前不删除候选代码；缺失内容不在客户端自行补全。

## 1. 已确认的参考分支保留能力

- FaceAISDK与CameraX常驻相机/覆盖层；
- JNI `SerialPort.c`、`serial/SerialPort`和阻塞读取线程；
- 现有布局、资源、页面和设备启动能力；
- V4.1 Markdown与串口Markdown原文。

## 2. 无用代码候选

### `core/WebSocketConnectionManager.java`

- 来自`reference/motone-current`；
- 新运行链由`BackendTransportManager`承担；
- `DeviceCoreService`不再实例化该类；
- 本批先保留，待用户统一确认后删除。

### `FaceEnrollmentActivity.java`

- 参考分支遗留的独立人脸Activity；
- 当前Manifest未声明该Activity；
- 当前MainActivity使用`FaceEnrollmentController`覆盖层；
- 本批先保留，待用户统一确认后删除。

## 3. 无消费方配置候选

```text
ignoreTokenFetch
codeValueType
cardSuccessResponseType
faceRegistrationResponseEnabled
tcpDoorCommandResponseEnabled
secondaryDoorEnabled
usbCardReaderEnabled
startCharacter
endCharacter
serialExtra
baudExtra
toastDisplay
```

这些字段保持空值，不进入运行逻辑。

## 4. 仅用于一个版本迁移的字段

```text
serverAddress
apiBaseUrl
mqttBrokerUrl
cardParseMode
```

不得重新成为正式配置真相。

## 5. 明确缺少的外部内容

1. 100卡槽的`slotId → 从机地址`关系、分组或切组协议；
2. TAKE/RETURN物理完成判定时点；
3. MQTT Broker需要username时的确切username字段；
4. HTTP登录返回token的后续用途；
5. 下行失败响应的正式错误码集合；
6. 后台远程下发独立HTTP/MQTT服务器字段；
7. 外接员工级指纹模块、SDK和特征生产方式；
8. OTA安装、校验、重启恢复和回滚协议；
9. 批量日志Outbox、ACK和删除规则；
10. 真实取还卡统计的来源和上报周期；
11. 目标rk3568_r真机安装、串口、网络、FaceAISDK与后端联调环境。

## 6. Markdown明确但受前提阻塞的接口

- `/api/v1/card/take`、`/api/v1/card/return`：已有Android入口，等待物理确认；
- `/api/v1/fingerprint/upload`：已有请求入口，等待外接指纹模块；
- `/api/v1/logs/batch`：已有请求入口，等待持久化Outbox；
- `/api/v1/firmware/{firmwareId}/download`：已有下载入口，不代表安装与回滚完成；
- `/api/v1/face/upload`：已有私有文件上传入口，当前相机流程默认不保存图片。
""",
    encoding="utf-8",
)

audit = Path("docs/REFERENCE_MERGE_AUDIT.md")
if audit.exists():
    report = audit.read_text(encoding="utf-8")
    marker = "\n## 本次选择性迁移结果\n"
    if marker not in report:
        report += marker + """
- 以reference分支为根，不执行无共同祖先的整树合并；
- 未复制`app/.cxx`、构建目录、生成assets、旧ArcSoft实现或旧批次说明；
- 保留参考分支FaceAISDK、CameraX、JNI串口、布局和资源；
- 迁入三层数据流、V4.1接口入口、契约证据、测试和发布门禁；
- 参考分支遗留但未使用的类单独登记，不在本批擅自删除。
"""
        audit.write_text(report, encoding="utf-8")

print("selected Motone integration applied")
