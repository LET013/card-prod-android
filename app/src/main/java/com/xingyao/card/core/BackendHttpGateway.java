package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

/** HTTP communication adapter. It performs transport only and owns no business state. */
public final class BackendHttpGateway {
    private final NativeSettingsRepository settingsRepository;

    public BackendHttpGateway(NativeSettingsRepository settingsRepository) {
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        this.settingsRepository = settingsRepository;
    }

    public JSONObject get(String path) throws Exception {
        JSONObject settings = settingsRepository.load();
        return client(settings).get(path);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        JSONObject settings = settingsRepository.load();
        return client(settings).post(path, body == null ? new JSONObject() : body);
    }

    public JSONObject postData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(post(path, body));
    }

    public byte[] downloadBytes(String absoluteUrl) throws Exception {
        JSONObject settings = settingsRepository.load();
        return BackendHttpClient.downloadBytes(absoluteUrl, settings.optString("deviceToken", ""));
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
            JSONObject settings = settingsRepository.load();
            String base = baseUrl(settings);
            return base + (raw.startsWith("/") ? raw : "/" + raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    public JSONObject snapshot() throws JSONException {
        JSONObject settings = settingsRepository.load();
        boolean ready = !settings.optString("deviceToken", "").isEmpty();
        return new JSONObject()
                .put("state", ready ? "READY" : "PENDING")
                .put("message", ready ? "HTTP Bearer Token 已保存" : "等待设备注册")
                .put("apiBaseUrl", baseUrl(settings))
                .put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
    }

    private BackendHttpClient client(JSONObject settings) {
        return new BackendHttpClient(baseUrl(settings), settings.optString("deviceToken", ""));
    }

    public static String baseUrl(JSONObject settings) {
        String address = settings == null ? "" : settings.optString("apiBaseUrl",
                settings.optString("serverAddress", "")).trim();
        return BackendHttpClient.normalizeBaseUrl(address);
    }
}
