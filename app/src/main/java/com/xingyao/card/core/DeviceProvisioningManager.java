package com.xingyao.card.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.xingyao.card.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

/** Runs the V4.1 registration, activation, verification and remote-config flow. */
public final class DeviceProvisioningManager {
    private static final String API_REGISTER = "/api/v1/device/register";
    private static final String API_APP_VERSION_CHECK = "/api/v1/app-version/check";
    private static final String API_ACTIVATE = "/api/v1/device/activate";
    private static final String API_VERIFY = "/api/v1/device/verify";
    private static final String API_CONFIG = "/api/v1/device/config";
    private static final String API_AUTH_STATUS = "/api/v1/device/auth/status";
    private static final long CREDENTIAL_REFRESH_SKEW_MS = 5L * 60L * 1000L;

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

    public synchronized JSONObject refreshRemoteConfig() throws Exception {
        JSONObject settings = settingsRepository.load();
        String apiBaseUrl = requireHttpBaseUrl(settings);
        JSONObject remote = config(apiBaseUrl, settings);
        JSONObject mapped = DeviceConfigMapper.apply(settings, remote);
        try {
            mapped.put("deviceAuthorization", authStatus(apiBaseUrl, mapped));
        } catch (Exception authorizationError) {
            mapped.put("deviceAuthorization", new JSONObject()
                    .put("state", "UNKNOWN")
                    .put("message", "授权状态查询失败：" + authorizationError.getMessage()));
        }
        mapped.put("provisionedAt", System.currentTimeMillis());
        return settingsRepository.save(mapped);
    }

    private JSONObject ensureProvisioned(boolean forceCredentialRefresh) throws Exception {
        JSONObject settings = settingsRepository.load();
        settings.put("machineId", machineId(settings));
        String apiBaseUrl = requireHttpBaseUrl(settings);
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

        boolean mqttRequested = BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT));
        boolean activationRequired = forceCredentialRefresh
                || !"ACTIVATED".equalsIgnoreCase(settings.optString("activationStatus", ""))
                || credentialsExpired(settings)
                || (mqttRequested && !hasMqttCredentials(settings));
        if (activationRequired) settings = performActivation(apiBaseUrl, settings);

        JSONObject remoteConfig = config(apiBaseUrl, settings);
        settings = DeviceConfigMapper.apply(settings, remoteConfig);

        // A server-side switch to MQTT requires MQTT credentials even if the local pre-registration
        // form previously selected HTTP.
        if (BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(settings.optString("backendTransport"))
                && !hasMqttCredentials(settings)) {
            settings = performActivation(apiBaseUrl, settings);
        }

        try {
            JSONObject authorization = authStatus(apiBaseUrl, settings);
            settings.put("deviceAuthorization", authorization);
        } catch (Exception authorizationError) {
            settings.put("deviceAuthorization", new JSONObject()
                    .put("state", "UNKNOWN")
                    .put("message", "授权状态查询失败：" + authorizationError.getMessage()));
        }
        settings.put("provisionedAt", System.currentTimeMillis());
        return settingsRepository.save(settings);
    }

    private JSONObject performActivation(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject activated = activate(apiBaseUrl, settings);
        if (hasMqttCredentials(activated)) {
            mergeCredentials(settings, activated);
            return settingsRepository.save(settings);
        }

        String status = activated.optString("status", "");
        if ("ACTIVATED".equalsIgnoreCase(status)) {
            settings.put("activationStatus", "ACTIVATED");
            merge(settings, activated, "expireTime", "deviceName", "deviceCode", "clientId");
            return settingsRepository.save(settings);
        }

        if (activated.has("registerCode")) {
            settings.put("registerCode", activated.optString("registerCode", ""))
                    .put("activationStatus", status)
                    .put("registerCodeExpireTime", activated.optLong("expireTime", 0L));
            String activeKey = settings.optString("activationCode", "").trim();
            if (activeKey.isEmpty()) {
                settingsRepository.save(settings);
                throw new IllegalStateException("设备待激活，请填写激活码；registerCode="
                        + settings.optString("registerCode"));
            }
            JSONObject verified = verify(apiBaseUrl, settings, activeKey);
            if (!verified.optBoolean("valid", false)) {
                settingsRepository.save(settings);
                throw new IllegalStateException(verified.optString("msg", "设备激活码验证失败"));
            }
            mergeCredentials(settings, verified);
            return settingsRepository.save(settings);
        }

        throw new IllegalStateException(activated.optString("msg", "设备激活响应缺少有效状态或注册码"));
    }

    private JSONObject register(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject body = deviceBody(settings)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("channelId", settings.optString("channelId", "official"));
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl, "")
                .post(API_REGISTER, body));
    }

    private void checkAppVersion(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject body = new JSONObject()
                .put("currentVersionCode", BuildConfig.VERSION_CODE)
                .put("channelId", settings.optString("channelId", "official"))
                .put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
        JSONObject response = new BackendHttpClient(apiBaseUrl, "").post(API_APP_VERSION_CHECK, body);
        Object data = response.opt("data");
        if (!(data instanceof JSONObject)) {
            settings.put("forceUpdate", false).remove("versionInfo");
            settingsRepository.save(settings);
            return;
        }
        JSONObject versionInfo = (JSONObject) data;
        settings.put("forceUpdate", versionInfo.optBoolean("forceUpdate", false))
                .put("versionInfo", versionInfo);
        settingsRepository.save(settings);
        if (versionInfo.optBoolean("forceUpdate", false)) {
            throw new IllegalStateException("当前APP版本存在强制更新，请先升级到 "
                    + versionInfo.optString("versionName", "最新") + " 版本后再启动");
        }
    }

    private JSONObject activate(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject body = deviceBody(settings)
                .put("deviceId", settings.optString("deviceCode", settings.optString("deviceId")));
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).post(API_ACTIVATE, body));
    }

    private JSONObject verify(String apiBaseUrl, JSONObject settings, String activeKey) throws Exception {
        JSONObject body = new JSONObject()
                .put("registerCode", settings.optString("registerCode"))
                .put("activeKey", activeKey);
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).post(API_VERIFY, body));
    }

    private JSONObject config(String apiBaseUrl, JSONObject settings) throws Exception {
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).get(API_CONFIG));
    }

    private JSONObject authStatus(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject data = BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).get(API_AUTH_STATUS));
        boolean authorized = data.optBoolean("authorized", false);
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authExpireTime", data.optLong("authExpireTime", 0L))
                .put("authType", data.optString("authType", ""))
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");
    }

    private JSONObject deviceBody(JSONObject settings) throws JSONException {
        return new JSONObject()
                .put("mac", settings.optString("mac", ""))
                .put("machineId", settings.optString("machineId"))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("osType", "ANDROID")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_NAME);
    }

    private void mergeCredentials(JSONObject settings, JSONObject data) throws JSONException {
        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime",
                "deviceName", "deviceCode", "mqttUsername");
        if (!settings.optString("deviceCode", "").trim().isEmpty()) {
            settings.put("deviceId", settings.optString("deviceCode"));
        }
        if (!settings.optString("clientId", "").trim().isEmpty()) {
            settings.put("mqttClientId", settings.optString("clientId"));
        }
        settings.put("activationStatus", "ACTIVATED");
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

    private static String requireHttpBaseUrl(JSONObject settings) {
        String value = BackendEndpointSettings.httpBaseUrl(settings);
        if (value.isEmpty()) throw new IllegalStateException("HTTP域名/IP尚未配置");
        return value;
    }

    private String machineId(JSONObject settings) {
        String configured = settings.optString("machineId", "").trim();
        if (!configured.isEmpty()) return configured;
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.trim().isEmpty()) return "ANDROID_" + androidId.trim();
        return "ANDROID_" + Build.MANUFACTURER + "_" + Build.MODEL + "_" + Build.SERIAL;
    }
}
