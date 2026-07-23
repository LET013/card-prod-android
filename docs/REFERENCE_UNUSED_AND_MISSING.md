# Motone基线：无用候选与缺失项登记

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
- 为保持参考源码可编译，仅将旧`insertFaceFeature`调用适配到现有`enrollFeature`；
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
