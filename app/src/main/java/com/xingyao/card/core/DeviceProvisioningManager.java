package com.xingyao.card.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.xingyao.card.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

/** Runs the backend V4.1 startup flow before MQTT/HTTP runtime communication. */
public final class DeviceProvisioningManager {
    private static final String API_REGISTER = "/api/v1/device/register";
    private static final String API_APP_VERSION_CHECK = "/api/v1/app-version/check";
    private static final String API_ACTIVATE = "/api/v1/device/activate";
    private static final String API_VERIFY = "/api/v1/device/verify";
    private static final String API_CONFIG = "/api/v1/device/config";
    private static final long CREDENTIAL_REFRESH_SKEW_MS = 5 * 60 * 1000L;

    private final Context context;
    private final NativeSettingsRepository settingsRepository;

    public DeviceProvisioningManager(Context context, NativeSettingsRepository settingsRepository) {
        this.context = context.getApplicationContext();
        this.settingsRepository = settingsRepository;
    }

    public synchronized JSONObject ensureProvisioned() throws Exception {
        return ensureProvisioned(false);
    }

    public synchronized JSONObject refreshCredentials() throws Exception {
        return ensureProvisioned(true);
    }

    private JSONObject ensureProvisioned(boolean forceCredentialRefresh) throws Exception {
        JSONObject settings = settingsRepository.load();
        settings.put("machineId", machineId(settings));
        String apiBaseUrl = settings.optString("apiBaseUrl", settings.optString("serverAddress", ""));
        checkAppVersion(apiBaseUrl, settings);

        if (settings.optString("deviceToken", "").trim().isEmpty()
                || settings.optString("deviceCode", "").trim().isEmpty()) {
            JSONObject registered = register(apiBaseUrl, settings);
            merge(settings, registered, "deviceToken", "deviceCode", "isNew");
            if (!settings.optString("deviceCode", "").trim().isEmpty()) {
                settings.put("deviceId", settings.optString("deviceCode"));
            }
            settingsRepository.save(settings);
        }

        if (forceCredentialRefresh || !hasMqttCredentials(settings) || credentialsExpired(settings)) {
            JSONObject activated = activate(apiBaseUrl, settings);
            if (hasMqttCredentials(activated)) {
                mergeCredentials(settings, activated);
            } else if (activated.has("registerCode")) {
                settings.put("registerCode", activated.optString("registerCode", ""));
                settings.put("activationStatus", activated.optString("status", ""));
                settings.put("registerCodeExpireTime", activated.optLong("expireTime", 0L));
                String activeKey = settings.optString("activationCode", "").trim();
                if (activeKey.isEmpty()) {
                    settingsRepository.save(settings);
                    throw new IllegalStateException("设备待激活，请在配置中填写激活码；registerCode=" + settings.optString("registerCode"));
                }
                JSONObject verified = verify(apiBaseUrl, settings, activeKey);
                if (!verified.optBoolean("valid", true)) {
                    settingsRepository.save(settings);
                    throw new IllegalStateException(verified.optString("msg", "设备激活码验证失败"));
                }
                mergeCredentials(settings, verified);
            }
        }

        JSONObject config = config(apiBaseUrl, settings);
        applyConfig(settings, config);
        settings.put("provisionedAt", System.currentTimeMillis());
        return settingsRepository.save(settings);
    }

    private JSONObject register(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject body = deviceBody(settings)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("channelId", settings.optString("channelId", "official"));
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl, "").post(API_REGISTER, body));
    }

    private void checkAppVersion(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject body = new JSONObject()
                .put("currentVersionCode", BuildConfig.VERSION_CODE)
                .put("channelId", settings.optString("channelId", "official"))
                .put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
        JSONObject response = new BackendHttpClient(apiBaseUrl, "").post(API_APP_VERSION_CHECK, body);
        Object data = response.opt("data");
        if (!(data instanceof JSONObject)) return;
        JSONObject versionInfo = (JSONObject) data;
        if (versionInfo.optBoolean("forceUpdate", false)) {
            settings.put("forceUpdate", true).put("versionInfo", versionInfo);
            settingsRepository.save(settings);
            throw new IllegalStateException("当前APP版本存在强制更新，请先升级到 "
                    + versionInfo.optString("versionName", "最新") + " 版本后再启动");
        }
        settings.put("forceUpdate", false).put("versionInfo", versionInfo);
        settingsRepository.save(settings);
    }

    private JSONObject activate(String apiBaseUrl, JSONObject settings) throws Exception {
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl, settings.optString("deviceToken"))
                .post(API_ACTIVATE, deviceBody(settings).put("deviceId", settings.optString("deviceCode", settings.optString("deviceId")))));
    }

    private JSONObject verify(String apiBaseUrl, JSONObject settings, String activeKey) throws Exception {
        JSONObject body = new JSONObject()
                .put("registerCode", settings.optString("registerCode"))
                .put("activeKey", activeKey);
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl, settings.optString("deviceToken")).post(API_VERIFY, body));
    }

    private JSONObject config(String apiBaseUrl, JSONObject settings) throws Exception {
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl, settings.optString("deviceToken")).get(API_CONFIG));
    }

    private JSONObject deviceBody(JSONObject settings) throws JSONException {
        return new JSONObject()
                .put("mac", settings.optString("mac", "AA:BB:CC:DD:EE:FF"))
                .put("machineId", settings.optString("machineId"))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("osType", "ANDROID")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_NAME);
    }

    private void mergeCredentials(JSONObject settings, JSONObject data) throws JSONException {
        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime", "deviceName", "deviceCode");
        if (!settings.optString("deviceCode", "").trim().isEmpty()) settings.put("deviceId", settings.optString("deviceCode"));
        if (!settings.optString("clientId", "").trim().isEmpty()) settings.put("mqttClientId", settings.optString("clientId"));
        settings.put("activationStatus", "ACTIVATED");
    }

    private void applyConfig(JSONObject settings, JSONObject config) throws JSONException {
        if (config == null) return;
        if (config.has("baudRate")) settings.put("baudRate", String.valueOf(config.optInt("baudRate", 57600)));
        if (config.has("groupSize")) settings.put("singleGroupCount", config.optInt("groupSize", settings.optInt("singleGroupCount", 10)));
        if (config.has("totalSlots")) settings.put("totalCount", config.optInt("totalSlots", settings.optInt("totalCount", 100)));
        if (config.has("tcpPort")) settings.put("tcpPort", config.optInt("tcpPort", settings.optInt("tcpPort", 9009)));
        if (config.has("httpPort")) settings.put("httpPort", config.optInt("httpPort", settings.optInt("httpPort", 80)));
        if (config.has("faceThreshold")) settings.put("faceRecognitionThreshold", config.optDouble("faceThreshold", settings.optDouble("faceRecognitionThreshold", 0.7)));
        if (config.has("communicationMode")) settings.put("backendTransport", config.optString("communicationMode", "MQTT"));
        if (config.has("serverIp")) settings.put("backendServerIp", config.optString("serverIp", ""));
        if (config.has("pollingInterval")) {
            int pollingInterval = config.optInt("pollingInterval", 5000);
            settings.put("backendPollingIntervalMs", pollingInterval);
            settings.put("serialPollingIntervalMs", pollingInterval);
        }
    }

    private static boolean hasMqttCredentials(JSONObject data) {
        return data != null
                && !data.optString("mqttPassword", "").trim().isEmpty()
                && !data.optString("signingKey", "").trim().isEmpty()
                && !data.optString("clientId", "").trim().isEmpty();
    }

    private static boolean credentialsExpired(JSONObject settings) {
        long expireTime = settings == null ? 0L : settings.optLong("expireTime", 0L);
        return expireTime > 0L && expireTime <= System.currentTimeMillis() + CREDENTIAL_REFRESH_SKEW_MS;
    }

    private static void merge(JSONObject target, JSONObject source, String... keys) throws JSONException {
        if (source == null) return;
        for (String key : keys) {
            if (source.has(key) && !source.isNull(key)) target.put(key, source.opt(key));
        }
    }

    private String machineId(JSONObject settings) {
        String configured = settings.optString("machineId", "").trim();
        if (!configured.isEmpty()) return configured;
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.trim().isEmpty()) return "ANDROID_" + androidId.trim();
        return "ANDROID_" + Build.MANUFACTURER + "_" + Build.MODEL + "_" + Build.SERIAL;
    }
}
