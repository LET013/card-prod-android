package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

/** HTTP V4.1 communication adapter. It owns transport only, not business state. */
public final class BackendHttpGateway {
    public static final String DEVICE_LOGIN = "/api/v1/device/login";
    public static final String DEVICE_HEARTBEAT = "/api/v1/device/heartbeat";
    public static final String DEVICE_STATUS = "/api/v1/device/status";
    public static final String DEVICE_AUTH_CHANGE = "/api/v1/device/auth/change";
    public static final String DEVICE_AUTH_STATUS = "/api/v1/device/auth/status";
    public static final String DEVICE_SELF_CHECK = "/api/v1/device/selfcheck";
    public static final String DEVICE_BATCH_RESULT = "/api/v1/device/batch-result";
    public static final String DEVICE_CONFIG = "/api/v1/device/config";
    public static final String CARD_EVENT = "/api/v1/card/event";
    public static final String LOG_REPORT = "/api/v1/log/report";
    public static final String STATISTICS_REPORT = "/api/v1/statistics/report";
    public static final String FAULT_REPORT = "/api/v1/fault/report";
    public static final String UPGRADE_STATUS = "/api/v1/upgrade/status";
    public static final String EMPLOYEE_SYNC = "/api/v1/employee/sync";
    public static final String FACE_SYNC = "/api/v1/employee/face/sync";
    public static final String FINGER_SYNC = "/api/v1/employee/finger/sync";

    private final NativeSettingsRepository settingsRepository;

    public BackendHttpGateway(NativeSettingsRepository settingsRepository) {
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        this.settingsRepository = settingsRepository;
    }

    public JSONObject get(String path) throws Exception {
        JSONObject settings = settingsRepository.load();
        return client(settings).get(path);
    }

    public JSONObject getData(String path) throws Exception {
        return BackendHttpClient.dataObject(get(path));
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        JSONObject settings = settingsRepository.load();
        return client(settings).post(path, body == null ? new JSONObject() : body);
    }

    public JSONObject postData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(post(path, body));
    }

    /** Sends one documented device-originated V4.1 command through its HTTP equivalent. */
    public JSONObject sendCommand(JSONObject payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("HTTP消息不能为空");
        String cmd = payload.optString("cmd", "").trim();
        JSONObject data = payload.optJSONObject("data");
        if (data == null) data = copyWithoutEnvelope(payload);
        switch (cmd) {
            case "login": return postData(DEVICE_LOGIN, data);
            case "heartbeat": return postData(DEVICE_HEARTBEAT, data);
            case "cardEvent": return postData(CARD_EVENT, data);
            case "statusReport": return postData(DEVICE_STATUS, data);
            case "logReport": return postData(LOG_REPORT, data);
            case "hardwareFault": return postData(FAULT_REPORT, data);
            case "statisticsReport": return postData(STATISTICS_REPORT, data);
            case "authStatusChange": return postData(DEVICE_AUTH_CHANGE, data);
            case "selfCheckReport": return postData(DEVICE_SELF_CHECK, data);
            case "upgradeStatus": return postData(UPGRADE_STATUS, data);
            case "batchOperationResult": return postData(DEVICE_BATCH_RESULT, data);
            default:
                throw new IllegalArgumentException("HTTP模式没有对应端点：" + cmd);
        }
    }

    public byte[] downloadBytes(String absoluteUrl, boolean withToken) throws Exception {
        JSONObject settings = settingsRepository.load();
        return BackendHttpClient.downloadBytes(absoluteUrl,
                withToken ? settings.optString("deviceToken", "") : "");
    }

    public String absoluteUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return raw;
        try {
            String base = baseUrl(settingsRepository.load());
            return base + (raw.startsWith("/") ? raw : "/" + raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    public JSONObject snapshot() throws JSONException {
        JSONObject settings = settingsRepository.load();
        String base = baseUrl(settings);
        boolean endpointReady = !base.isEmpty();
        boolean tokenReady = !settings.optString("deviceToken", "").isEmpty();
        return new JSONObject()
                .put("state", endpointReady ? tokenReady ? "READY" : "PENDING_AUTH" : "NOT_CONFIGURED")
                .put("message", !endpointReady ? "HTTP域名/IP未配置"
                        : tokenReady ? "HTTP端点与设备Token已就绪" : "HTTP端点已配置，等待设备注册")
                .put("apiBaseUrl", base)
                .put("httpServerAddress", settings.optString("httpServerAddress", ""))
                .put("httpPort", settings.optInt("httpPort", 0))
                .put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
    }

    private BackendHttpClient client(JSONObject settings) {
        String base = baseUrl(settings);
        if (base.isEmpty()) throw new IllegalStateException("HTTP域名/IP尚未配置");
        String token = settings.optString("runtimeToken", "").trim();
        if (token.isEmpty()) token = settings.optString("deviceToken", "");
        return new BackendHttpClient(base, token);
    }

    public static String baseUrl(JSONObject settings) {
        return BackendEndpointSettings.httpBaseUrl(settings);
    }

    private static JSONObject copyWithoutEnvelope(JSONObject payload) throws JSONException {
        JSONObject result = new JSONObject(payload.toString());
        result.remove("cmd");
        result.remove("msgId");
        result.remove("timestamp");
        result.remove("deviceCode");
        result.remove("sign");
        return result;
    }
}
