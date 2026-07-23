package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Applies only fields explicitly defined by V4.1 GET /api/v1/device/config. */
public final class DeviceConfigMapper {
    private DeviceConfigMapper() { }

    public static JSONObject apply(JSONObject localSettings, JSONObject remoteConfig)
            throws JSONException {
        JSONObject settings = localSettings == null ? new JSONObject()
                : new JSONObject(localSettings.toString());
        JSONObject config = remoteConfig == null ? new JSONObject() : remoteConfig;

        if (config.has("baudRate")) settings.put("baudRate",
                String.valueOf(config.optInt("baudRate", 57600)));
        if (config.has("groupSize")) settings.put("singleGroupCount",
                positive(config.optInt("groupSize"), settings.optInt("singleGroupCount", 16)));
        if (config.has("totalSlots")) settings.put("totalCount",
                positive(config.optInt("totalSlots"), settings.optInt("totalCount", 100)));
        if (config.has("pollingInterval")) settings.put("serialPollingIntervalMs",
                positive(config.optInt("pollingInterval"),
                        settings.optInt("serialPollingIntervalMs", 5000)));
        if (config.has("tcpPort")) settings.put("tcpPort",
                validPort(config.optInt("tcpPort"), settings.optInt("tcpPort", 9009)));
        if (config.has("httpPort")) settings.put("httpPort",
                validPort(config.optInt("httpPort"), settings.optInt("httpPort", 8082)));
        if (config.has("faceThreshold")) settings.put("faceRecognitionThreshold",
                bounded(config.optDouble("faceThreshold"),
                        settings.optDouble("faceRecognitionThreshold", 0.8)));
        if (config.has("fingerThreshold")) settings.put("fingerRecognitionThreshold",
                bounded(config.optDouble("fingerThreshold"), 0.8));

        String mode = config.optString("communicationMode", "").trim().toUpperCase();
        if (BackendEndpointSettings.MODE_MQTT.equals(mode)
                || BackendEndpointSettings.MODE_HTTP.equals(mode)) {
            settings.put("backendTransport", mode);
        }

        // V4.1 defines only one generic serverIp while this deployment requires different HTTP
        // and MQTT servers. Store it for diagnostics only; do not guess which local endpoint it owns.
        if (config.has("serverIp")) {
            settings.put("backendServerIp", config.optString("serverIp", ""));
        }
        settings.put("remoteConfigUpdatedAt", System.currentTimeMillis());
        return BackendEndpointSettings.normalize(settings);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int validPort(int value, int fallback) {
        return value >= 1 && value <= 65535 ? value : fallback;
    }

    private static double bounded(double value, double fallback) {
        return value >= 0D && value <= 1D ? value : fallback;
    }
}
