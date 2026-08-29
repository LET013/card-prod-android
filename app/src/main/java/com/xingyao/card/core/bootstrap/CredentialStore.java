package com.xingyao.card.core.bootstrap;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;

import com.xingyao.card.core.entity.http.DeviceConfigEntity;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设备凭证和配置持久化存储。
 * 与旧版 NativeSettingsRepository 使用同一 SharedPreferences 文件（"card_native_settings"），
 * 通过 settings_json key 读写 JSON，保证新旧代码共存时数据一致。
 */
public class CredentialStore {
    public static final String DEFAULT_CHANNEL_ID = "test";
    private static final String APP_CHANNEL_METADATA_KEY = "com.xingyao.card.APP_CHANNEL_ID";
    static final String PREFS_FILE = "card_native_settings";
    static final String KEY_SETTINGS = "settings_json";

    private final SharedPreferences prefs;
    private final Context appContext;

    public CredentialStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public JSONObject load() throws JSONException {
        String raw = prefs.getString(KEY_SETTINGS, "{}");
        return new JSONObject(raw == null ? "{}" : raw);
    }

    public JSONObject save(JSONObject settings) throws JSONException {
        JSONObject copy = new JSONObject(settings.toString());
        copy.put("updatedAt", System.currentTimeMillis());
        if (!prefs.edit().putString(KEY_SETTINGS, copy.toString()).commit()) {
            throw new IllegalStateException("Failed to persist credentials");
        }
        return copy;
    }

    public String getString(String key) throws JSONException {
        return load().optString(key, "");
    }

    public long getLong(String key) throws JSONException {
        return load().optLong(key, 0L);
    }

    public boolean getBoolean(String key) throws JSONException {
        return load().optBoolean(key, false);
    }

    public void putAndSave(String key, Object value) throws JSONException {
        JSONObject settings = load();
        settings.put(key, value);
        save(settings);
    }

    /**
     * 原子保存设备注册返回的完整凭证，避免进程在多次写入之间退出后只留下 token。
     */
    public void saveRegistration(String deviceToken, String deviceCode, boolean isNew) throws JSONException {
        String normalizedToken = deviceToken == null ? "" : deviceToken.trim();
        String normalizedCode = deviceCode == null ? "" : deviceCode.trim();
        if (normalizedToken.isEmpty() || normalizedCode.isEmpty()) {
            throw new IllegalArgumentException("Registration credentials must include deviceToken and deviceCode");
        }
        JSONObject settings = load();
        settings.put("deviceToken", normalizedToken);
        settings.put("deviceCode", normalizedCode);
        settings.put("isNew", isNew);
        save(settings);
    }

    public String getDeviceToken() throws JSONException {
        return getString("deviceToken");
    }

    public String getDeviceCode() throws JSONException {
        return getString("deviceCode");
    }

    public String getChannelId() throws JSONException {
        try {
            ApplicationInfo appInfo = appContext.getPackageManager().getApplicationInfo(
                    appContext.getPackageName(), PackageManager.GET_META_DATA);
            String channelId = appInfo.metaData == null
                    ? "" : appInfo.metaData.getString(APP_CHANNEL_METADATA_KEY, "").trim();
            return channelId.isEmpty() ? DEFAULT_CHANNEL_ID : channelId;
        } catch (PackageManager.NameNotFoundException e) {
            return DEFAULT_CHANNEL_ID;
        }
    }

    public String getMachineId() throws JSONException {
        return getString("machineId");
    }

    public String getMqttPassword() throws JSONException {
        return getString("mqttPassword");
    }

    public String getSigningKey() throws JSONException {
        return getString("signingKey");
    }

    public String getClientId() throws JSONException {
        String cid = getString("clientId");
        return cid.isEmpty() ? getString("mqttClientId") : cid;
    }

    public boolean isActivated() throws JSONException {
        return "ACTIVATED".equalsIgnoreCase(getString("activationStatus"));
    }

    public boolean hasMqttCredentials() throws JSONException {
        return !getMqttPassword().isEmpty()
                && !getSigningKey().isEmpty()
                && !getClientId().isEmpty();
    }

    public String getRegisterCode() throws JSONException {
        return getString("registerCode");
    }

    public long getRegisterCodeExpireTime() throws JSONException {
        return getLong("registerCodeExpireTime");
    }

    public String getCommunicationMode() throws JSONException {
        String mode = getString("communicationMode");
        return mode.isEmpty() ? getString("backendTransport") : mode;
    }

    /**
     * 读取已存储的设备配置实体（从 Bootstrap getConfig 阶段保存）。
     * 服务未完成启动或从未配置时返回 null。
     */
    public DeviceConfigEntity getDeviceConfigEntity() throws JSONException {
        String rawJson = getString("deviceConfig_v2");
        if (rawJson == null || rawJson.isEmpty()) {
            android.util.Log.w("CredentialStore", "deviceConfig is empty, device may not have completed bootstrap");
            return null;
        }
        return DeviceConfigEntity.fromRawJson(rawJson);
    }

    /**
     * 初始化启动所需的服务器配置（幂等：已存在则跳过）。
     * 若写入后 serverUrl 仍为空，说明未提供有效配置，调用方应拒绝启动。
     */
    public JSONObject initializeBootstrapConfig(String serverUrl, String mqttHost, int mqttPort)
            throws JSONException {
        JSONObject settings = load();
        boolean changed = false;
        if (serverUrl != null && !serverUrl.isEmpty()
                && settings.optString("serverUrl", "").isEmpty()) {
            settings.put("serverUrl", serverUrl);
            changed = true;
        }
        if (mqttHost != null && !mqttHost.isEmpty()
                && settings.optString("mqttHost", "").isEmpty()) {
            settings.put("mqttHost", mqttHost);
            settings.put("mqttTcpPort", mqttPort);
            changed = true;
        }
        if (changed) {
            return save(settings);
        }
        return settings;
    }

    /** 读取已配置的 serverUrl（用户首次启动时输入）。未配置时返回空字符串 */
    public String getServerUrl() throws JSONException {
        JSONObject settings = load();
        // 优先从新 key serverUrl 读取
        String url = settings.optString("serverUrl", "");
        if (!url.isEmpty()) return url;
        // 兼容旧版 backendUrl/backendHost 路径（逐步淘汰）
        url = settings.optString("backendUrl", "");
        if (!url.isEmpty()) return url;
        if (settings.has("backendHost")) {
            String host = settings.optString("backendHost", "");
            int port = settings.optInt("backendHttpPort", 0);
            return "http://" + host + (port > 0 ? ":" + port : "");
        }
        return "";
    }

    /** 读取已配置的 MQTT broker URL（host/port 由 bootstrap getConfig 步骤下发）。未配置时返回 null */
    public String getMqttBrokerUrl() throws JSONException {
        JSONObject settings = load();
        if (settings.has("mqttHost")) {
            String host = settings.optString("mqttHost", "");
            int port = settings.optInt("mqttTcpPort", 0);
            if (host.isEmpty()) return null;
            return "tcp://" + host + ":" + (port > 0 ? port : 1883);
        }
        return null;
    }
}
