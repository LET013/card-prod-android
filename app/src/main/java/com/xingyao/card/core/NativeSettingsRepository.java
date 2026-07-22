package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Android-owned settings store. Vue only receives sanitized editable fields. */
public class NativeSettingsRepository {
    private static final String PREFS = "card_native_settings";
    private static final String KEY_SETTINGS = "settings_json";
    private static final int CURRENT_SCHEMA = 4;

    private static final Set<String> INTERNAL_ONLY_KEYS = new HashSet<>(Arrays.asList(
            "deviceToken", "mqttPassword", "signingKey", "machineId", "clientId",
            "mqttClientId", "mqttUsername", "registerCode", "registerCodeExpireTime",
            "provisionedAt", "apiBaseUrl", "mqttBrokerUrl", "serverAddress",
            "versionInfo", "forceUpdate"
    ));

    private final SharedPreferences preferences;

    public NativeSettingsRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public JSONObject load() throws JSONException {
        String raw = preferences.getString(KEY_SETTINGS, "{}");
        JSONObject loaded = new JSONObject(raw == null ? "{}" : raw);
        return mergeDefaults(loaded);
    }

    public JSONObject loadForUi() throws JSONException {
        return sanitizeForUi(load());
    }

    public JSONObject saveFromUi(JSONObject settings) throws JSONException {
        JSONObject merged = load();
        JSONObject source = settings == null ? new JSONObject() : settings;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!INTERNAL_ONLY_KEYS.contains(key)) merged.put(key, source.opt(key));
        }
        return sanitizeForUi(save(merged));
    }

    public boolean isInitialized() {
        try { return load().optBoolean("initialized", false); }
        catch (JSONException ignored) { return false; }
    }

    public JSONObject save(JSONObject settings) throws JSONException {
        JSONObject clean = mergeDefaults(settings == null ? new JSONObject() : new JSONObject(settings.toString()));
        clean.put("initialized", true)
                .put("settingsSchemaVersion", CURRENT_SCHEMA)
                .put("updatedAt", System.currentTimeMillis());
        if (!preferences.edit().putString(KEY_SETTINGS, clean.toString()).commit()) {
            throw new IllegalStateException("无法持久化设备配置");
        }
        return clean;
    }

    private static JSONObject sanitizeForUi(JSONObject source) throws JSONException {
        JSONObject result = new JSONObject(source == null ? "{}" : source.toString());
        for (String key : INTERNAL_ONLY_KEYS) result.remove(key);
        return result;
    }

    private static JSONObject mergeDefaults(JSONObject loaded) throws JSONException {
        JSONObject defaults = new JSONObject()
                .put("initialized", false)
                .put("settingsSchemaVersion", CURRENT_SCHEMA)
                .put("cabinetNumber", "")

                // Serial V1.5 uses fixed 8 data bits, 1 stop bit and no parity.
                .put("serialPort", "/dev/ttyS5")
                .put("baudRate", "57600")
                .put("serialDataBits", 8)
                .put("serialStopBits", 1)
                .put("serialParity", "NONE")
                .put("serialPollingEnabled", true)
                .put("serialResponseTimeoutMs", 1500)
                .put("serialCommandGapMs", 200)
                .put("serialPollingIntervalMs", 5000)
                .put("slotStatusReportIntervalMs", 10000)
                .put("pollingMode", "")

                // Slot/group configuration. Group size does not imply modulo address mapping.
                .put("singleGroupCount", 16)
                .put("totalCount", 100)
                .put("cardNumberMode", "VISIBLE")
                .put("cardParseMode", "可视卡号")

                // Provisioning identity.
                .put("deviceId", "")
                .put("deviceCode", "")
                .put("mac", "")
                .put("machineId", "")
                .put("channelId", "official")
                .put("activationCode", "")

                // Runtime channel selection. HTTP is always used for provisioning/sync.
                .put("backendTransport", BackendEndpointSettings.MODE_MQTT)
                .put("httpScheme", "http")
                .put("httpServerAddress", "card-test.quyohui.com")
                .put("httpPort", 80)
                .put("httpBasePath", "")
                .put("apiBaseUrl", "")
                .put("mqttScheme", "tcp")
                .put("mqttServerAddress", "119.146.88.108")
                .put("mqttPort", 48419)
                .put("mqttBrokerUrl", "")
                .put("tcpServerAddress", "")
                .put("tcpPort", 9009)
                .put("serverAddress", "")
                .put("httpHeartbeatIntervalMs", 30000)
                .put("mqttHeartbeatIntervalMs", 30000)

                // Server-issued MQTT fields are internal-only.
                .put("mqttUsername", "")
                .put("mqttPassword", "")
                .put("mqttClientId", "")
                .put("mqttCommandTopic", "")
                .put("mqttResponseTopic", "")
                .put("mqttEventTopic", "")

                // Recognition.
                .put("faceRecognitionThreshold", 0.8)
                .put("faceSyncIncludeFlags", 3)
                .put("startupDataSyncEnabled", true)
                .put("cameraRotation", 90)
                .put("fingerprintEnabled", false)
                .put("fingerRecognitionThreshold", "")
                .put("systemBiometricEnabled", true)

                // Retained but deliberately blank until a real contract/feature exists.
                .put("singleGroupPollingEnabled", false)
                .put("ignoreTokenFetch", "")
                .put("codeValueType", "")
                .put("cardSuccessResponseType", "")
                .put("toastDisplay", "")
                .put("boardUpgradeIntervalMs", "")
                .put("faceRegistrationResponseEnabled", "")
                .put("tcpDoorCommandResponseEnabled", "")
                .put("secondaryDoorEnabled", "")
                .put("usbCardReaderEnabled", "")
                .put("startCharacter", "")
                .put("endCharacter", "")
                .put("serialExtra", "")
                .put("baudExtra", "");

        if (loaded != null) {
            Iterator<String> keys = loaded.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                defaults.put(key, loaded.opt(key));
            }
        }

        int oldSchema = loaded == null ? 0 : loaded.optInt("settingsSchemaVersion", 0);
        if (oldSchema < CURRENT_SCHEMA) {
            migrateLegacy(defaults, oldSchema);
            defaults.put("settingsSchemaVersion", CURRENT_SCHEMA);
        }
        return BackendEndpointSettings.normalize(defaults);
    }

    private static void migrateLegacy(JSONObject settings, int oldSchema) throws JSONException {
        if (settings.optString("cardNumberMode", "").trim().isEmpty()) {
            String legacy = settings.optString("cardParseMode", "");
            settings.put("cardNumberMode", legacy.contains("十六") || legacy.contains("物理")
                    ? "PHYSICAL" : "VISIBLE");
        }
        String mode = settings.optString("cardNumberMode", "VISIBLE");
        settings.put("cardParseMode", "PHYSICAL".equalsIgnoreCase(mode) ? "物理卡号" : "可视卡号");

        if (oldSchema < 4) {
            // The old UI stored one serverAddress for unrelated transports. Preserve it only as a
            // migration hint; endpoint normalization separates HTTP/MQTT/TCP afterwards.
            String legacyServer = settings.optString("serverAddress", "").trim();
            if (settings.optString("httpServerAddress", "").trim().isEmpty()
                    && (legacyServer.startsWith("http://") || legacyServer.startsWith("https://"))) {
                settings.put("httpServerAddress", legacyServer);
            }
            if (settings.optString("tcpServerAddress", "").trim().isEmpty()
                    && !legacyServer.startsWith("http://") && !legacyServer.startsWith("https://")) {
                settings.put("tcpServerAddress", legacyServer);
            }
            if (settings.optString("pollingMode", "").trim().isEmpty()) settings.put("pollingMode", "");
            if (!settings.has("fingerRecognitionThreshold")) settings.put("fingerRecognitionThreshold", "");
            if (!settings.has("fingerprintEnabled")) settings.put("fingerprintEnabled", false);
            settings.put("ignoreTokenFetch", "");
        }
    }
}
