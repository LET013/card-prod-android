package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public class NativeSettingsRepository {
    private static final String PREFS = "card_native_settings";
    private static final String KEY_SETTINGS = "settings_json";
    private static final String DEFAULT_API_BASE_URL = "http://card-test.quyohui.com";
    private static final String DEFAULT_MQTT_BROKER = "tcp://119.146.88.108:48419";
    private static final String DEFAULT_ACTIVATION_CODE = "123123";
    private static final String DEFAULT_MAC = "AA:BB:CC:DD:EE:FF";
    private final SharedPreferences preferences;

    public NativeSettingsRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public JSONObject load() throws JSONException {
        String raw = preferences.getString(KEY_SETTINGS, "{}");
        JSONObject loaded = new JSONObject(raw == null ? "{}" : raw);
        return mergeDefaults(loaded);
    }

    public JSONObject save(JSONObject settings) throws JSONException {
        JSONObject clean = mergeDefaults(settings == null ? new JSONObject() : new JSONObject(settings.toString()));
        clean.put("initialized", true);
        clean.put("updatedAt", System.currentTimeMillis());
        preferences.edit().putString(KEY_SETTINGS, clean.toString()).apply();
        return clean;
    }

    private static JSONObject mergeDefaults(JSONObject loaded) throws JSONException {
        JSONObject defaults = new JSONObject()
                .put("initialized", false)
                .put("settingsSchemaVersion", 3)
                .put("cabinetNumber", "8652566615555520")
                .put("serialPort", "/dev/ttyS5")
                .put("serialExtra", "")
                .put("baudRate", "57600")
                .put("baudExtra", "")
                .put("singleGroupCount", 10)
                .put("totalCount", 100)
                .put("cardParseMode", "转可见符")
                .put("singleGroupPollingEnabled", true)
                .put("serialPollingEnabled", true)
                .put("serialResponseTimeoutMs", 1500)
                .put("serialCommandGapMs", 200)
                .put("serialPollingIntervalMs", 1200)
                .put("slotStatusReportIntervalMs", 10000)
                .put("deviceId", "336633")
                .put("deviceCode", "")
                .put("mac", DEFAULT_MAC)
                .put("machineId", "")
                .put("channelId", "official")
                .put("activationCode", DEFAULT_ACTIVATION_CODE)
                .put("apiBaseUrl", DEFAULT_API_BASE_URL)
                .put("serverAddress", DEFAULT_API_BASE_URL)
                .put("backendTransport", "MQTT")
                .put("tcpPort", 9009)
                .put("mqttPort", 48419)
                .put("mqttBrokerUrl", DEFAULT_MQTT_BROKER)
                .put("mqttUsername", "")
                .put("mqttPassword", "")
                .put("mqttClientId", "")
                .put("mqttCommandTopic", "")
                .put("mqttResponseTopic", "")
                .put("mqttEventTopic", "")
                .put("httpPort", 80)
                .put("faceRecognitionThreshold", 0.7)
                .put("faceSyncIncludeFlags", 3)
                .put("startupDataSyncEnabled", true)
                .put("cameraRotation", 90)
                .put("codeValueType", "字符")
                .put("cardSuccessResponseType", "短链接")
                .put("toastDisplay", "显示")
                .put("boardUpgradeIntervalMs", 800)
                .put("ignoreTokenFetch", false)
                .put("faceRegistrationResponseEnabled", false)
                .put("tcpDoorCommandResponseEnabled", true)
                .put("secondaryDoorEnabled", false)
                .put("usbCardReaderEnabled", false)
                .put("startCharacter", "")
                .put("endCharacter", "");
        if (loaded == null) return defaults;
        java.util.Iterator<String> keys = loaded.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            defaults.put(key, loaded.opt(key));
        }
        int schemaVersion = loaded.optInt("settingsSchemaVersion", 0);
        if (schemaVersion < 3) {
            defaults.put("settingsSchemaVersion", 3);
            defaults.put("singleGroupPollingEnabled", true);
            defaults.put("serialPollingEnabled", true);
            defaults.put("serialResponseTimeoutMs", 1500);
            defaults.put("serialCommandGapMs", 200);
            defaults.put("serialPollingIntervalMs", 1200);
            defaults.put("slotStatusReportIntervalMs", 10000);
            defaults.put("startupDataSyncEnabled", true);
            if (!defaults.has("faceSyncIncludeFlags")) defaults.put("faceSyncIncludeFlags", 3);
        }
        if (defaults.optString("apiBaseUrl", "").trim().isEmpty()) defaults.put("apiBaseUrl", DEFAULT_API_BASE_URL);
        if (defaults.optString("serverAddress", "").trim().isEmpty()) defaults.put("serverAddress", DEFAULT_API_BASE_URL);
        if (defaults.optString("mqttBrokerUrl", "").trim().isEmpty()) defaults.put("mqttBrokerUrl", DEFAULT_MQTT_BROKER);
        if (defaults.optString("activationCode", "").trim().isEmpty()) defaults.put("activationCode", DEFAULT_ACTIVATION_CODE);
        return defaults;
    }
}
