package com.xingyao.card.core.entity.http;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设备配置实体，解析自 GET /api/v1/device/config 接口返回的完整配置 JSON。
 *
 * <h3>配置数据流</h3>
 * <pre>
 * Server Config JSON
 *   → DeviceApiService.getConfig()
 *   → DeviceConfigEntity.fromJson()
 *   → DeviceBootstrapManager (validate + persist)
 *   → CredentialStore
 *   → DeviceCoreService (wire to modules) + JsBridgeV2 (forward to Vue)
 * </pre>
 *
 * <h3>启动流程必须验证的字段</h3>
 * 缺失以下字段时，{@code DeviceConfigEntity.validate()} 抛出 {@link BootstrapConfigException}：
 * <ul>
 *   <li>{@code communicationMode} — 必须为 MQTT、HTTP 或 BOTH</li>
 *   <li>{@code serialPort} — 串口设备路径不能为空</li>
 *   <li>{@code baudRate} — 串口波特率必须大于 0</li>
 *   <li>{@code totalSlots} — 卡槽总数必须大于 0</li>
 *   <li>{@code httpHost} — 不能为空</li>
 *   <li>{@code mqttHost} — 不能为空</li>
 *   <li>{@code mqttPort} — 必须在 1-65535 范围</li>
 * </ul>
 *
 * <p>其余字段（摄像头、人脸、指纹、轮询等）均有合理默认值，缺失不阻塞启动。</p>
 *
 * @see "docs/config-接入指南.md" —— 完整 31 个参数定义
 */
public class DeviceConfigEntity {
    private static final String TAG = "DeviceConfigEntity";

    // ═══════════════════════════════════════════════════════════════
    // 配置内存态（解析后缓存，与 JSON 互转）
    // ═══════════════════════════════════════════════════════════════

    // ── 串口参数 ──
    /** 串口设备路径，服务端默认值: {@code "/dev/ttyS5"} */
    private String serialPort;

    /** 串口波特率，服务端默认值: {@code 57600} */
    private int baudRate;

    /** 串口轮询开关，服务端默认值: {@code true} */
    private boolean serialPollEnabled;

    /** 串口轮询间隔(ms)，服务端默认值: {@code 5000} */
    private int serialPollInterval;

    /** 串口单板响应超时(ms)，服务端默认值: {@code 3000} */
    private int serialResponseTimeout;

    // ── MQTT / 通信 ──
    /** 通信方式，服务端默认值: {@code "MQTT"}，仅允许 {@code "MQTT"} 或 {@code "HTTP"} */
    private String communicationMode;

    /** HTTP Host 地址，服务端未下发时为空字符串 */
    private String httpHost;

    /** HTTP 端口，服务端默认值: {@code 8082} */
    private int httpPort;

    /** MQTT Broker 地址，服务端未下发时为空字符串 */
    private String mqttHost;

    /** MQTT Broker 端口，服务端默认值: {@code 1883}（MQTT 标准端口） */
    private int mqttPort;

    /** MQTT 心跳间隔(ms)，服务端默认值: {@code 60000} */
    private int mqttHeartbeatInterval;

    /** MQTT 初次重连间隔(ms)，服务端默认值: {@code 1000} */
    private int mqttReconnectInitialInterval;

    /** MQTT 最大重连间隔(ms)，服务端默认值: {@code 60000} */
    private int mqttReconnectMaxInterval;

    // ── 卡槽 ──
    /** 卡槽总数，服务端默认值: {@code 100} */
    private int totalSlots;

    /** 单组卡槽数量，服务端默认值: {@code 16} */
    private int groupSize;

    /** 卡槽排序方向，服务端默认值: {@code "HORIZONTAL"}，允许 {@code "HORIZONTAL"} / {@code "VERTICAL"} */
    private String slotSortDirection;

    /** 卡槽轮询方式，服务端默认值: {@code "GROUP"} */
    private String pollingMode;

    // ── 摄像头 ──
    /** 摄像头方向，服务端默认值: {@code "front"}，允许 {@code "front"} / {@code "back"} */
    private String cameraFacing;

    /** 预览是否水平镜像，服务端默认值: {@code true}（前置摄像头需镜像） */
    private boolean cameraMirror;

    /** 传感器旋转补偿角度，服务端默认值: {@code 0}。取值: 0 / 90 / 180 / 270 */
    private int cameraRotation;

    /** 传给分析器的帧宽度，服务端默认值: {@code 640} */
    private int cameraFrameWidth;

    /** 传给分析器的帧高度，服务端默认值: {@code 480} */
    private int cameraFrameHeight;

    // ── 人脸识别 ──
    /** 人脸识别阈值（1:N 搜索匹配分数下限），服务端默认值: {@code 0.8}。范围: 0.0-1.0 */
    private double faceThreshold;

    /** 人脸搜索超时(ms)（FaceAISDK 单次搜索引擎超时），服务端默认值: {@code 15000} */
    private int searchTimeout;

    /** 搜索结果上报间隔(ms)（两次匹配结果的最小时间间隔），服务端默认值: {@code 3000} */
    private int searchIntervalTime;

    /** 静默活体检测开关，服务端默认值: {@code false} */
    private boolean needFaceLiveness;

    /** 人脸录入超时(ms)，服务端默认值: {@code 8000} */
    private int captureTimeout;

    /** 人脸识别整体超时(ms)（会话层超时），服务端默认值: {@code 30000} */
    private int faceRecognitionTimeout;

    // ── 指纹 ──
    /** 指纹识别开关，服务端默认值: {@code false} */
    private boolean fingerEnabled;

    /** 指纹识别阈值，服务端默认值: {@code 0.8}。范围: 0.0-1.0 */
    private double fingerThreshold;

    // ── 卡槽状态推送 ──
    /** 卡槽实时状态 MQTT 推送间隔(ms)，服务端默认值: {@code 60000} */
    private int slotStatusPushInterval;

    // ═══════════════════════════════════════════════════════════════
    // 构造函数
    // ═══════════════════════════════════════════════════════════════

    private DeviceConfigEntity() { }

    // ═══════════════════════════════════════════════════════════════
    // 工厂方法：从 JSON 解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从服务端返回的 config JSON 中解析所有字段。
     * 缺失字段自动使用服务端默认值，不抛异常。
     *
     * @param json 服务端 GET /api/v1/device/config 的 data 对象
     * @return 填充好默认值的配置实体
     */
    public static DeviceConfigEntity fromJson(JSONObject json) {
        DeviceConfigEntity entity = new DeviceConfigEntity();

        // ── 串口参数 ──
        entity.serialPort = json.optString("serialPort", "/dev/ttyS5");
        entity.baudRate = json.optInt("baudRate", 57600);
        entity.serialPollEnabled = json.optBoolean("serialPollEnabled", true);
        entity.serialPollInterval = json.optInt("serialPollInterval", 5000);
        entity.serialResponseTimeout = json.optInt("serialResponseTimeout", 3000);

        // ── MQTT / 通信 ──
        entity.communicationMode = json.optString("communicationMode",
                json.optString("backendTransport", "MQTT")).toUpperCase();
        entity.httpHost = json.optString("httpHost", "");
        entity.httpPort = json.optInt("httpPort", 8082);
        entity.mqttHost = json.optString("mqttHost", "");
        entity.mqttPort = json.optInt("mqttPort", 1883);
        entity.mqttHeartbeatInterval = json.optInt("mqttHeartbeatInterval", 60000);
        entity.mqttReconnectInitialInterval = json.optInt("mqttReconnectInitialInterval", 1000);
        entity.mqttReconnectMaxInterval = json.optInt("mqttReconnectMaxInterval", 60000);

        // ── 卡槽 ──
        entity.totalSlots = json.optInt("totalSlots",
                json.optInt("totalCount", 100));
        entity.groupSize = json.optInt("groupSize",
                json.optInt("singleGroupCount", 16));
        entity.slotSortDirection = json.optString("slotSortDirection", "HORIZONTAL").toUpperCase();
        entity.pollingMode = json.optString("pollingMode", "GROUP").toUpperCase();

        // ── 摄像头 ──
        entity.cameraFacing = json.optString("cameraFacing", "front").toLowerCase();
        entity.cameraMirror = json.optBoolean("cameraMirror", true);
        entity.cameraRotation = json.optInt("cameraRotation", 0);
        entity.cameraFrameWidth = json.optInt("cameraFrameWidth", 640);
        entity.cameraFrameHeight = json.optInt("cameraFrameHeight", 480);

        // ── 人脸识别 ──
        entity.faceThreshold = json.optDouble("faceThreshold",
                json.optDouble("recognitionThreshold", 0.8));
        entity.searchTimeout = json.optInt("searchTimeout", 15000);
        entity.searchIntervalTime = json.optInt("searchIntervalTime", 3000);
        entity.needFaceLiveness = json.optBoolean("needFaceLiveness", false);
        entity.captureTimeout = json.optInt("captureTimeout", 8000);
        entity.faceRecognitionTimeout = json.optInt("faceRecognitionTimeout", 30000);

        // ── 指纹 ──
        entity.fingerEnabled = json.optBoolean("fingerEnabled", false);
        entity.fingerThreshold = json.optDouble("fingerThreshold", 0.8);

        // ── 卡槽状态推送 ──
        entity.slotStatusPushInterval = json.optInt("slotStatusPushInterval", 60000);

        return entity;
    }

    /**
     * 从已存储的原始 JSON 字符串解析配置实体（用于从 CredentialStore 恢复）。
     */
    public static DeviceConfigEntity fromRawJson(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        try {
            return fromJson(new JSONObject(rawJson));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse stored device config", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 序列化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 序列化回 JSON（用于存储到 CredentialStore / 转发给 Vue）。
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            // ── 串口 ──
            json.put("serialPort", serialPort);
            json.put("baudRate", baudRate);
            json.put("serialPollEnabled", serialPollEnabled);
            json.put("serialPollInterval", serialPollInterval);
            json.put("serialResponseTimeout", serialResponseTimeout);

            // ── MQTT / 通信 ──
            json.put("communicationMode", communicationMode);
            json.put("httpHost", httpHost);
            json.put("httpPort", httpPort);
            json.put("mqttHost", mqttHost);
            json.put("mqttPort", mqttPort);
            json.put("mqttHeartbeatInterval", mqttHeartbeatInterval);
            json.put("mqttReconnectInitialInterval", mqttReconnectInitialInterval);
            json.put("mqttReconnectMaxInterval", mqttReconnectMaxInterval);

            // ── 卡槽 ──
            json.put("totalSlots", totalSlots);
            json.put("groupSize", groupSize);
            json.put("slotSortDirection", slotSortDirection);
            json.put("pollingMode", pollingMode);

            // ── 摄像头 ──
            json.put("cameraFacing", cameraFacing);
            json.put("cameraMirror", cameraMirror);
            json.put("cameraRotation", cameraRotation);
            json.put("cameraFrameWidth", cameraFrameWidth);
            json.put("cameraFrameHeight", cameraFrameHeight);

            // ── 人脸识别 ──
            json.put("faceThreshold", faceThreshold);
            json.put("searchTimeout", searchTimeout);
            json.put("searchIntervalTime", searchIntervalTime);
            json.put("needFaceLiveness", needFaceLiveness);
            json.put("captureTimeout", captureTimeout);
            json.put("faceRecognitionTimeout", faceRecognitionTimeout);

            // ── 指纹 ──
            json.put("fingerEnabled", fingerEnabled);
            json.put("fingerThreshold", fingerThreshold);

            // ── 卡槽推送 ──
            json.put("slotStatusPushInterval", slotStatusPushInterval);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize config to JSON", e);
        }
        return json;
    }

    // ═══════════════════════════════════════════════════════════════
    // 验证
    // ═══════════════════════════════════════════════════════════════

    /**
     * 验证启动必填项。缺失必填字段时抛出 {@link BootstrapConfigException}。
     *
     * @throws BootstrapConfigException 如果必填字段缺失或无效
     */
    public void validate() throws BootstrapConfigException {
        // communicationMode：MQTT 为主通道，HTTP 为降级通道，BOTH 使用 MQTT 登录并保留 HTTP 能力
        if (communicationMode == null || communicationMode.isEmpty()
                || (!"MQTT".equals(communicationMode)
                && !"HTTP".equals(communicationMode)
                && !"BOTH".equals(communicationMode))) {
            throw new BootstrapConfigException("communicationMode 缺失或无效: " + communicationMode);
        }

        // 串口路径
        if (serialPort == null || serialPort.isEmpty()) {
            throw new BootstrapConfigException("serialPort 不能为空");
        }

        // 波特率
        if (baudRate <= 0) {
            throw new BootstrapConfigException("baudRate 必须大于 0，当前值: " + baudRate);
        }

        // 卡槽总数
        if (totalSlots <= 0) {
            throw new BootstrapConfigException("totalSlots 必须大于 0，当前值: " + totalSlots);
        }

        // HTTP Host
        if (httpHost == null || httpHost.isEmpty()) {
            throw new BootstrapConfigException("httpHost 不能为空");
        }
        if (isLoopback(httpHost)) {
            throw new BootstrapConfigException("httpHost 为回环地址 (" + httpHost + ")，无法连接远程服务，请检查服务端 getConfig 下发");
        }

        if (usesMqttTransport(communicationMode)) {
            if (mqttHost == null || mqttHost.isEmpty()) {
                throw new BootstrapConfigException("mqttHost 不能为空");
            }
            if (isLoopback(mqttHost)) {
                throw new BootstrapConfigException("mqttHost 为回环地址 (" + mqttHost + ")，无法连接远程 MQTT，请检查服务端 getConfig 下发");
            }
            if (mqttPort < 1 || mqttPort > 65535) {
                throw new BootstrapConfigException("mqttPort 无效: " + mqttPort + "，需在 1-65535 范围");
            }
        }

        // 非必填值域校验（带默认值降级，不阻塞启动）
        validateNonFatal();
    }

    /**
     * 非必填字段的值域校验。无效值降级为默认值，打印警告日志但不抛异常。
     */
    private void validateNonFatal() {
        if (httpPort < 1 || httpPort > 65535) {
            Log.w(TAG, "httpPort 无效: " + httpPort + "，降级为默认 8082");
            httpPort = 8082;
        }
        if (mqttHeartbeatInterval < 1000) {
            Log.w(TAG, "mqttHeartbeatInterval 过小: " + mqttHeartbeatInterval + "ms，降级为默认 60000ms");
            mqttHeartbeatInterval = 60000;
        }
        if (mqttReconnectInitialInterval < 100) {
            Log.w(TAG, "mqttReconnectInitialInterval 过小: " + mqttReconnectInitialInterval + "ms，降级为默认 1000ms");
            mqttReconnectInitialInterval = 1000;
        }
        if (mqttReconnectMaxInterval < mqttReconnectInitialInterval) {
            Log.w(TAG, "mqttReconnectMaxInterval(" + mqttReconnectMaxInterval
                    + ") < mqttReconnectInitialInterval(" + mqttReconnectInitialInterval + ")，降级为默认 60000ms");
            mqttReconnectMaxInterval = 60000;
        }
        if (faceThreshold < 0.0 || faceThreshold > 1.0) {
            Log.w(TAG, "faceThreshold 越界: " + faceThreshold + "，降级为默认 0.8");
            faceThreshold = 0.8;
        }
        if (fingerThreshold < 0.0 || fingerThreshold > 1.0) {
            Log.w(TAG, "fingerThreshold 越界: " + fingerThreshold + "，降级为默认 0.8");
            fingerThreshold = 0.8;
        }
        if (!"front".equals(cameraFacing) && !"back".equals(cameraFacing)) {
            Log.w(TAG, "cameraFacing 非法: " + cameraFacing + "，降级为默认 front");
            cameraFacing = "front";
        }
        if (cameraRotation != 0 && cameraRotation != 90 && cameraRotation != 180 && cameraRotation != 270) {
            Log.w(TAG, "cameraRotation 非法: " + cameraRotation + "，降级为默认 0");
            cameraRotation = 0;
        }
        if (!"HORIZONTAL".equals(slotSortDirection) && !"VERTICAL".equals(slotSortDirection)) {
            Log.w(TAG, "slotSortDirection 非法: " + slotSortDirection + "，降级为默认 HORIZONTAL");
            slotSortDirection = "HORIZONTAL";
        }
    }

    public static boolean usesMqttTransport(String communicationMode) {
        return "MQTT".equalsIgnoreCase(communicationMode)
                || "BOTH".equalsIgnoreCase(communicationMode);
    }

    /**
     * 判断 host 是否为回环地址或无效地址（不可用于远程连接）。
     */
    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String lower = host.trim().toLowerCase();
        return lower.equals("127.0.0.1")
                || lower.equals("localhost")
                || lower.equals("::1")
                || lower.equals("0.0.0.0");
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters（带注释说明每个字段的用途和默认值）
    // ═══════════════════════════════════════════════════════════════

    /** 串口设备路径。默认值: {@code /dev/ttyS5} */
    public String getSerialPort() { return serialPort; }

    /** 串口波特率。默认值: {@code 57600} */
    public int getBaudRate() { return baudRate; }

    /** 串口轮询开关（true=轮询，false=停止轮询）。默认值: {@code true} */
    public boolean isSerialPollEnabled() { return serialPollEnabled; }

    /** 串口轮询间隔(ms)。默认值: {@code 5000} */
    public int getSerialPollInterval() { return serialPollInterval; }

    /** 串口单板响应超时(ms)。默认值: {@code 3000} */
    public int getSerialResponseTimeout() { return serialResponseTimeout; }

    /** 通信方式，{@code "MQTT"} 或 {@code "HTTP"}。默认值: {@code "MQTT"} */
    public String getCommunicationMode() { return communicationMode; }

    /** HTTP Host 地址。服务端未下发时为空字符串，validate() 会拦截 */
    public String getHttpHost() { return httpHost; }

    /** HTTP 端口。默认值: {@code 8082} */
    public int getHttpPort() { return httpPort; }

    /** MQTT Broker 地址。服务端未下发时为空字符串，validate() 会拦截 */
    public String getMqttHost() { return mqttHost; }

    /** MQTT Broker 端口。默认值: {@code 1883} */
    public int getMqttPort() { return mqttPort; }

    /** MQTT 心跳发送间隔(ms)。默认值: {@code 60000} */
    public int getMqttHeartbeatInterval() { return mqttHeartbeatInterval; }

    /** MQTT 初次重连间隔(ms)。默认值: {@code 1000} */
    public int getMqttReconnectInitialInterval() { return mqttReconnectInitialInterval; }

    /** MQTT 最大重连间隔(ms)。默认值: {@code 60000} */
    public int getMqttReconnectMaxInterval() { return mqttReconnectMaxInterval; }

    /** 卡槽总数。默认值: {@code 100} */
    public int getTotalSlots() { return totalSlots; }

    /** 单组卡槽数量。默认值: {@code 16} */
    public int getGroupSize() { return groupSize; }

    /** 卡槽排序方向。默认值: {@code "HORIZONTAL"}。可选: {@code "HORIZONTAL"} / {@code "VERTICAL"} */
    public String getSlotSortDirection() { return slotSortDirection; }

    /** 卡槽轮询方式。默认值: {@code "GROUP"} */
    public String getPollingMode() { return pollingMode; }

    /** 摄像头方向。默认值: {@code "front"}。可选: {@code "front"} / {@code "back"} */
    public String getCameraFacing() { return cameraFacing; }

    /** 预览是否水平镜像。默认值: {@code true}（前置摄像头需要镜像） */
    public boolean isCameraMirror() { return cameraMirror; }

    /** 传感器旋转补偿角度。默认值: {@code 0}。取值: 0 / 90 / 180 / 270 */
    public int getCameraRotation() { return cameraRotation; }

    /** 传给分析器的帧宽度。默认值: {@code 640} */
    public int getCameraFrameWidth() { return cameraFrameWidth; }

    /** 传给分析器的帧高度。默认值: {@code 480} */
    public int getCameraFrameHeight() { return cameraFrameHeight; }

    /** 人脸识别阈值（1:N 搜索匹配分数下限）。默认值: {@code 0.8}。范围: 0.0-1.0 */
    public double getFaceThreshold() { return faceThreshold; }

    /** 人脸搜索超时(ms)（FaceAISDK 单次搜索引擎超时）。默认值: {@code 15000} */
    public int getSearchTimeout() { return searchTimeout; }

    /** 搜索结果上报间隔(ms)。默认值: {@code 3000} */
    public int getSearchIntervalTime() { return searchIntervalTime; }

    /** 静默活体检测开关。默认值: {@code false} */
    public boolean isNeedFaceLiveness() { return needFaceLiveness; }

    /** 人脸录入超时(ms)。默认值: {@code 8000} */
    public int getCaptureTimeout() { return captureTimeout; }

    /** 人脸识别整体超时(ms)（会话层超时）。默认值: {@code 30000} */
    public int getFaceRecognitionTimeout() { return faceRecognitionTimeout; }

    /** 指纹识别开关。默认值: {@code false} */
    public boolean isFingerEnabled() { return fingerEnabled; }

    /** 指纹识别阈值。默认值: {@code 0.8}。范围: 0.0-1.0 */
    public double getFingerThreshold() { return fingerThreshold; }

    /** 卡槽实时状态 MQTT 推送间隔(ms)。默认值: {@code 60000} */
    public int getSlotStatusPushInterval() { return slotStatusPushInterval; }

    // ═══════════════════════════════════════════════════════════════
    // MQTT Broker URL 构建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建 MQTT broker URL。
     * @return 如 {@code "tcp://10.0.0.1:1883"}
     */
    public String buildMqttBrokerUrl() {
        return "tcp://" + mqttHost + ":" + mqttPort;
    }

    /**
     * 构建 HTTP base URL。
     * @return 如 {@code "http://127.0.0.1:8082"}
     */
    public String buildHttpBaseUrl() {
        return "http://" + httpHost + ":" + httpPort;
    }

    // ═══════════════════════════════════════════════════════════════
    // 启动配置异常（内部类）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动流程中配置验证失败的异常。
     * 在 DeviceBootstrapManager 中被捕获，转换为 {@code BootstrapEvent.Phase.ERROR}。
     */
    public static class BootstrapConfigException extends Exception {
        public BootstrapConfigException(String message) {
            super(message);
        }
    }
}
