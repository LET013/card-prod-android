package com.xingyao.card.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.xingyao.card.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android data-layer coordinator for the exact V4.1 startup and activation sequence. */
public final class DeviceProvisioningManager {
    private final Context context;
    private final NativeSettingsRepository settingsRepository;
    private final BackendHttpGateway httpGateway;

    public DeviceProvisioningManager(Context context, NativeSettingsRepository settingsRepository,
                                     BackendHttpGateway httpGateway) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.context = context.getApplicationContext();
        this.settingsRepository = settingsRepository;
        this.httpGateway = httpGateway;
    }

    public synchronized JSONObject ensureProvisioned() throws Exception {
        return ensureProvisioned(false);
    }

    public synchronized JSONObject refreshCredentials() throws Exception {
        return ensureProvisioned(true);
    }

    public synchronized JSONObject refreshRemoteConfig() throws Exception {
        JSONObject settings = settingsRepository.load();
        httpGateway.configure(settings);
        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        JSONObject mapped = DeviceConfigMapper.apply(settings, remote);
        mapped.put("deviceAuthorization", queryAuthorization());
        mapped.put("provisionedAt", System.currentTimeMillis());
        JSONObject saved = settingsRepository.save(mapped);
        httpGateway.configure(saved);
        return saved;
    }

    private JSONObject ensureProvisioned(boolean forceCredentialRefresh) throws Exception {
        JSONObject settings = settingsRepository.load();
        settings.put("machineId", machineId(settings));
        httpGateway.configure(settings);
        checkAppVersion(settings);

        if (settings.optString("deviceToken", "").trim().isEmpty()
                || settings.optString("deviceCode", "").trim().isEmpty()) {
            JSONObject registered = register(settings);
            merge(settings, registered, "deviceToken", "deviceCode", "isNew");
            if (!settings.optString("deviceCode", "").trim().isEmpty()) {
                settings.put("deviceId", settings.optString("deviceCode"));
            }
            settings = settingsRepository.save(settings);
            httpGateway.configure(settings);
        }

        boolean mqttRequested = BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT));
        boolean activationRequired = forceCredentialRefresh
                || !"ACTIVATED".equalsIgnoreCase(settings.optString("activationStatus", ""))
                || (mqttRequested && !hasMqttCredentials(settings));
        if (activationRequired) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }

        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        settings = DeviceConfigMapper.apply(settings, remote);
        httpGateway.configure(settings);

        if (BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", "")) && !hasMqttCredentials(settings)) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }

        try {
            settings.put("deviceAuthorization", queryAuthorization());
        } catch (Exception error) {
            settings.put("deviceAuthorization", new JSONObject()
                    .put("state", "UNKNOWN")
                    .put("message", "授权状态查询失败：" + safeMessage(error)));
        }
        settings.put("provisionedAt", System.currentTimeMillis());
        JSONObject saved = settingsRepository.save(settings);
        httpGateway.configure(saved);
        return saved;
    }

    private JSONObject performActivation(JSONObject settings) throws Exception {
        JSONObject activated = httpGateway.postData(BackendHttpGateway.DEVICE_ACTIVATE,
                deviceBody(settings).put("deviceId", required(settings.optString("deviceCode"), "deviceCode")));
        if (hasMqttCredentials(activated)) {
            mergeCredentials(settings, activated);
            return settingsRepository.save(settings);
        }
        if (!activated.has("registerCode")) {
            throw new IllegalStateException("设备激活响应缺少mqtt凭证或registerCode");
        }
        settings.put("registerCode", activated.optString("registerCode", ""))
                .put("activationStatus", activated.optString("status", ""))
                .put("registerCodeExpireTime", activated.optLong("expireTime", 0L));
        String activeKey = settings.optString("activationCode", "").trim();
        if (activeKey.isEmpty()) {
            settingsRepository.save(settings);
            throw new IllegalStateException("设备待激活，请填写激活码；registerCode="
                    + settings.optString("registerCode"));
        }
        JSONObject verified = httpGateway.postData(BackendHttpGateway.DEVICE_VERIFY,
                new JSONObject().put("registerCode", required(settings.optString("registerCode"), "registerCode"))
                        .put("activeKey", activeKey));
        if (!verified.optBoolean("valid", false)) {
            settingsRepository.save(settings);
            throw new IllegalStateException(verified.optString("msg", "设备激活码验证失败"));
        }
        mergeCredentials(settings, verified);
        return settingsRepository.save(settings);
    }

    private JSONObject register(JSONObject settings) throws Exception {
        JSONObject body = deviceBody(settings)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("channelId", required(settings.optString("channelId", "official"), "channelId"));
        return httpGateway.anonymousPostData(BackendHttpGateway.DEVICE_REGISTER, body);
    }

    private void checkAppVersion(JSONObject settings) throws Exception {
        JSONObject body = new JSONObject()
                .put("currentVersionCode", BuildConfig.VERSION_CODE)
                .put("channelId", required(settings.optString("channelId", "official"), "channelId"));
        String deviceCode = settings.optString("deviceCode", "").trim();
        if (!deviceCode.isEmpty()) body.put("deviceCode", deviceCode);
        JSONObject response = httpGateway.anonymousPost(BackendHttpGateway.APP_VERSION_CHECK, body);
        Object data = BackendHttpClient.dataValue(response);
        if (!(data instanceof JSONObject)) {
            settings.put("forceUpdate", false).remove("versionInfo");
            settingsRepository.save(settings);
            return;
        }
        JSONObject version = (JSONObject) data;
        settings.put("forceUpdate", version.optBoolean("forceUpdate", false))
                .put("versionInfo", version);
        settingsRepository.save(settings);
        if (version.optBoolean("forceUpdate", false)) {
            throw new IllegalStateException("当前APP版本存在强制更新，请先升级到 "
                    + version.optString("versionName", "最新") + " 版本后再启动");
        }
    }

    private JSONObject queryAuthorization() throws Exception {
        JSONObject data = httpGateway.getData(BackendHttpGateway.DEVICE_AUTH_STATUS);
        boolean authorized = data.optBoolean("authorized", false);
        JSONArray features = data.optJSONArray("features");
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authorizedUntil", data.optLong("authorizedUntil", 0L))
                .put("daysRemaining", data.optLong("daysRemaining", 0L))
                .put("features", features == null ? new JSONArray() : features)
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");
    }

    private JSONObject deviceBody(JSONObject settings) throws JSONException {
        return new JSONObject()
                .put("machineId", required(settings.optString("machineId"), "machineId"))
                .put("mac", settings.optString("mac", ""))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("osType", "ANDROID")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_NAME);
    }

    private void mergeCredentials(JSONObject settings, JSONObject data) throws JSONException {
        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime",
                "deviceName", "deviceCode");
        settings.put("activationStatus", "ACTIVATED");
        if (!settings.optString("deviceCode", "").trim().isEmpty()) {
            settings.put("deviceId", settings.optString("deviceCode"));
        }
        if (!settings.optString("clientId", "").trim().isEmpty()) {
            settings.put("mqttClientId", settings.optString("clientId"));
        }
    }

    private static boolean hasMqttCredentials(JSONObject source) {
        return source != null
                && !source.optString("mqttPassword", "").trim().isEmpty()
                && !source.optString("signingKey", "").trim().isEmpty()
                && !source.optString("clientId", "").trim().isEmpty();
    }

    private static void merge(JSONObject target, JSONObject source, String... keys) throws JSONException {
        for (String key : keys) {
            if (source != null && source.has(key) && !source.isNull(key)) target.put(key, source.opt(key));
        }
    }

    private String machineId(JSONObject settings) {
        String configured = settings.optString("machineId", "").trim();
        if (!configured.isEmpty()) return configured;
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.trim().isEmpty()) return "ANDROID_" + androidId.trim();
        String serial = Build.SERIAL == null ? "" : Build.SERIAL.trim();
        if (!serial.isEmpty() && !"unknown".equalsIgnoreCase(serial)) return "SERIAL_" + serial;
        throw new IllegalStateException("无法获取AndroidID或设备序列号，不能执行设备注册");
    }

    private static String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName() : value;
    }
}
