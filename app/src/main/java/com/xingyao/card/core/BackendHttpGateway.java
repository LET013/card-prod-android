package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Pure V4.1 HTTP communication adapter. Configuration is injected by the Android data layer. */
public final class BackendHttpGateway implements DocumentedBackendService.Transport {
    public static final String APP_VERSION_CHECK = "/api/v1/app-version/check";
    public static final String DEVICE_REGISTER = "/api/v1/device/register";
    public static final String DEVICE_ACTIVATE = "/api/v1/device/activate";
    public static final String DEVICE_VERIFY = "/api/v1/device/verify";
    public static final String DEVICE_CONFIG = "/api/v1/device/config";
    public static final String DEVICE_LOGIN = "/api/v1/device/login";
    public static final String DEVICE_HEARTBEAT = "/api/v1/device/heartbeat";
    public static final String DEVICE_STATUS = "/api/v1/device/status";
    public static final String DEVICE_AUTH_CHANGE = "/api/v1/device/auth/change";
    public static final String DEVICE_AUTH_STATUS = "/api/v1/device/auth/status";
    public static final String DEVICE_SELF_CHECK = "/api/v1/device/selfcheck";
    public static final String DEVICE_BATCH_RESULT = "/api/v1/device/batch-result";
    public static final String EMPLOYEE_SYNC = "/api/v1/employee/sync";
    public static final String FACE_SYNC = "/api/v1/employee/face/sync";
    public static final String FINGER_SYNC = "/api/v1/employee/finger/sync";
    public static final String EMPLOYEE_UPSERT = "/api/v1/employee";
    public static final String EMPLOYEE_FACE_UPSERT = "/api/v1/employee/face";
    public static final String FACE_REGISTERED = "/api/v1/employee/face/registered";
    public static final String CARD_EVENT = "/api/v1/card/event";
    public static final String CARD_TAKE = "/api/v1/card/take";
    public static final String CARD_RETURN = "/api/v1/card/return";
    public static final String LOG_REPORT = "/api/v1/log/report";
    public static final String STATISTICS_REPORT = "/api/v1/statistics/report";
    public static final String FAULT_REPORT = "/api/v1/fault/report";
    public static final String UPGRADE_STATUS = "/api/v1/upgrade/status";
    public static final String FACE_UPLOAD = "/api/v1/face/upload";
    public static final String FINGERPRINT_UPLOAD = "/api/v1/fingerprint/upload";
    public static final String LOGS_BATCH = "/api/v1/logs/batch";

    private String baseUrl = "";
    private String deviceToken = "";
    private String deviceCode = "";
    private String httpServerAddress = "";
    private int httpPort;

    public synchronized void configure(JSONObject settings) {
        JSONObject safe = settings == null ? new JSONObject() : settings;
        baseUrl = BackendEndpointSettings.httpBaseUrl(safe);
        deviceToken = safe.optString("deviceToken", "").trim();
        deviceCode = safe.optString("deviceCode", "").trim();
        httpServerAddress = safe.optString("httpServerAddress", "").trim();
        httpPort = safe.optInt("httpPort", 0);
    }

    public synchronized JSONObject anonymousPost(String path, JSONObject body) throws Exception {
        return anonymousClient().post(path, body == null ? new JSONObject() : body);
    }

    public synchronized JSONObject anonymousPostData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(anonymousPost(path, body));
    }

    public synchronized JSONObject get(String path) throws Exception {
        return deviceClient().get(path);
    }

    public synchronized JSONObject getData(String path) throws Exception {
        return BackendHttpClient.dataObject(get(path));
    }

    public synchronized JSONArray getDataArray(String path) throws Exception {
        return BackendHttpClient.dataArray(get(path));
    }

    public synchronized JSONObject post(String path, JSONObject body) throws Exception {
        return deviceClient().post(path, body == null ? new JSONObject() : body);
    }

    @Override
    public synchronized JSONObject postData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(post(path, body));
    }

    public synchronized JSONObject postDeviceData(String path, JSONObject body) throws Exception {
        return postData(path, body);
    }

    @Override
    public synchronized JSONArray fetchArray(String path) throws Exception {
        return getDataArray(path);
    }

    @Override
    public synchronized JSONObject uploadFaceImage(String userId, File file,
                                                   String faceFeature) throws Exception {
        JSONObject fields = new JSONObject().put("userId", required(userId, "userId"));
        if (faceFeature != null && !faceFeature.trim().isEmpty()) {
            fields.put("faceFeature", faceFeature.trim());
        }
        return BackendHttpClient.dataObject(deviceClient()
                .uploadMultipart(FACE_UPLOAD, "file", file, fields));
    }

    @Override
    public synchronized JSONObject downloadFirmware(String firmwareId, File target,
                                                    long offset) throws Exception {
        String id = required(firmwareId, "firmwareId");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("firmwareId包含非法字符");
        }
        return deviceClient().downloadToFile("/api/v1/firmware/" + id + "/download",
                target, offset);
    }

    public synchronized byte[] downloadBytes(String absoluteUrl, boolean withToken) throws Exception {
        return BackendHttpClient.downloadBytes(absoluteUrl, withToken ? deviceToken : "");
    }

    public synchronized String absoluteUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.matches("^https?://.*")) return raw;
        requireConfigured();
        return baseUrl + (raw.startsWith("/") ? raw : "/" + raw);
    }

    public synchronized JSONObject sendCommand(JSONObject payload) throws Exception {
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
            default: throw new IllegalArgumentException("HTTP模式没有对应端点：" + cmd);
        }
    }

    public synchronized JSONObject snapshot() throws JSONException {
        boolean endpointReady = !baseUrl.isEmpty();
        boolean tokenReady = !deviceToken.isEmpty();
        return new JSONObject()
                .put("state", endpointReady ? tokenReady ? "READY" : "PENDING_AUTH" : "NOT_CONFIGURED")
                .put("message", !endpointReady ? "HTTP域名/IP或协议未配置"
                        : tokenReady ? "HTTP端点与deviceToken已就绪" : "HTTP端点已配置，等待设备注册")
                .put("apiBaseUrl", baseUrl)
                .put("httpServerAddress", httpServerAddress)
                .put("httpPort", httpPort)
                .put("deviceTokenReady", tokenReady)
                .put("deviceCode", deviceCode);
    }

    public static String baseUrl(JSONObject settings) {
        return BackendEndpointSettings.httpBaseUrl(settings);
    }

    private BackendHttpClient anonymousClient() {
        requireConfigured();
        return new BackendHttpClient(baseUrl, "");
    }

    private BackendHttpClient deviceClient() {
        requireConfigured();
        if (deviceToken.isEmpty()) throw new IllegalStateException("deviceToken尚未由注册接口返回");
        return new BackendHttpClient(baseUrl, deviceToken);
    }

    private void requireConfigured() {
        if (baseUrl.isEmpty()) throw new IllegalStateException("HTTP域名/IP和协议尚未配置");
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

    private static String required(String value, String name) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return result;
    }
}
