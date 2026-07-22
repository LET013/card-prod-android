package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Applies /api/v1/device/config without collapsing HTTP, MQTT and TCP into one host. */
public final class DeviceConfigMapper {
    private DeviceConfigMapper() { }

    public static JSONObject apply(JSONObject localSettings, JSONObject remoteConfig) throws JSONException {
        JSONObject settings = localSettings == null ? new JSONObject() : new JSONObject(localSettings.toString());
        JSONObject config = remoteConfig == null ? new JSONObject() : remoteConfig;

        if (config.has("baudRate")) settings.put("baudRate", String.valueOf(config.optInt("baudRate", 57600)));
        if (config.has("groupSize")) settings.put("singleGroupCount",
                positive(config.optInt("groupSize"), settings.optInt("singleGroupCount", 10)));
        if (config.has("totalSlots")) settings.put("totalCount",
                positive(config.optInt("totalSlots"), settings.optInt("totalCount", 100)));
        if (config.has("pollingInterval")) settings.put("serialPollingIntervalMs",
                positive(config.optInt("pollingInterval"), settings.optInt("serialPollingIntervalMs", 5000)));
        if (config.has("tcpPort")) settings.put("tcpPort",
                validPort(config.optInt("tcpPort"), settings.optInt("tcpPort", 9009)));
        if (config.has("httpPort")) settings.put("httpPort",
                validPort(config.optInt("httpPort"), settings.optInt("httpPort", 80)));
        if (config.has("mqttPort")) settings.put("mqttPort",
                validPort(config.optInt("mqttPort"), settings.optInt("mqttPort", 48419)));
        if (config.has("faceThreshold")) settings.put("faceRecognitionThreshold",
                bounded(config.optDouble("faceThreshold"), settings.optDouble("faceRecognitionThreshold", 0.8)));
        if (config.has("fingerThreshold")) settings.put("fingerRecognitionThreshold",
                bounded(config.optDouble("fingerThreshold"), 0.8));

        String communicationMode = config.optString("communicationMode", "").trim().toUpperCase();
        if (BackendEndpointSettings.MODE_MQTT.equals(communicationMode)
                || BackendEndpointSettings.MODE_HTTP.equals(communicationMode)
                || BackendEndpointSettings.MODE_TCP.equals(communicationMode)) {
            settings.put("backendTransport", communicationMode);
        }

        // V4.1 serverIp is retained as a backend-provided reference only. It must not overwrite
        // separately configured HTTP/MQTT/TCP hosts unless the server explicitly supplies a
        // channel-specific field.
        if (config.has("serverIp")) settings.put("backendServerIp", config.optString("serverIp", ""));
        putIfPresent(settings, config, "httpServerAddress", "httpServerAddress");
        putIfPresent(settings, config, "httpHost", "httpServerAddress");
        putIfPresent(settings, config, "httpBaseUrl", "apiBaseUrl");
        putIfPresent(settings, config, "mqttServerAddress", "mqttServerAddress");
        putIfPresent(settings, config, "mqttHost", "mqttServerAddress");
        putIfPresent(settings, config, "mqttBrokerUrl", "mqttBrokerUrl");
        putIfPresent(settings, config, "tcpServerAddress", "tcpServerAddress");
        putIfPresent(settings, config, "tcpHost", "tcpServerAddress");

        settings.put("remoteConfigUpdatedAt", System.currentTimeMillis());
        return BackendEndpointSettings.normalize(settings);
    }

    private static void putIfPresent(JSONObject target, JSONObject source, String sourceKey,
                                     String targetKey) throws JSONException {
        if (!source.has(sourceKey) || source.isNull(sourceKey)) return;
        String value = source.optString(sourceKey, "").trim();
        if (!value.isEmpty()) target.put(targetKey, value);
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
