package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackendEndpointSettingsTest {
    @Test
    public void explicitlyConfiguredLocalEndpointsRemainIndependent() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("httpScheme", "https")
                .put("httpServerAddress", "api.example.com")
                .put("httpPort", 8443)
                .put("httpBasePath", "/device")
                .put("mqttScheme", "ssl")
                .put("mqttServerAddress", "mqtt.example.com")
                .put("mqttPort", 8883)
                .put("tcpServerAddress", "legacy.example.com")
                .put("tcpPort", 9009));
        assertEquals("https://api.example.com:8443/device", result.getString("apiBaseUrl"));
        assertEquals("ssl://mqtt.example.com:8883", result.getString("mqttBrokerUrl"));
        assertEquals("legacy.example.com", result.getString("tcpServerAddress"));
    }

    @Test
    public void missingSchemeOrMqttPortDoesNotCreateAConnectionEndpoint() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("httpServerAddress", "api.example.com")
                .put("mqttServerAddress", "mqtt.example.com"));
        assertEquals("", result.getString("apiBaseUrl"));
        assertEquals("", result.getString("mqttBrokerUrl"));
        assertEquals(0, result.getInt("mqttPort"));
    }

    @Test
    public void explicitLegacyUrlsCanBeMigratedWithoutCrossingChannels() throws Exception {
        JSONObject result = BackendEndpointSettings.normalize(new JSONObject()
                .put("apiBaseUrl", "https://old-api.example.com:9443/prod")
                .put("mqttBrokerUrl", "tcp://old-mqtt.example.com:1883"));
        assertEquals("https://old-api.example.com:9443/prod", result.getString("apiBaseUrl"));
        assertEquals("tcp://old-mqtt.example.com:1883", result.getString("mqttBrokerUrl"));
        assertEquals("", result.getString("tcpServerAddress"));
    }
}
