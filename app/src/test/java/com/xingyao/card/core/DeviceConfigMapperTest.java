package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeviceConfigMapperTest {
    @Test
    public void appliesOnlyFieldsDocumentedByV41() throws Exception {
        JSONObject local = new JSONObject()
                .put("httpScheme", "https")
                .put("httpServerAddress", "api.customer.com")
                .put("httpPort", 443)
                .put("mqttScheme", "ssl")
                .put("mqttServerAddress", "mqtt.customer.com")
                .put("mqttPort", 8883)
                .put("tcpServerAddress", "legacy.customer.com")
                .put("tcpPort", 9009);
        JSONObject remote = new JSONObject()
                .put("serverIp", "10.0.0.5")
                .put("httpPort", 8082)
                .put("tcpPort", 9010)
                .put("communicationMode", "MQTT")
                .put("baudRate", 57600)
                .put("groupSize", 16)
                .put("totalSlots", 100)
                .put("pollingInterval", 5000)
                .put("faceThreshold", 0.82)
                .put("fingerThreshold", 0.81);

        JSONObject result = DeviceConfigMapper.apply(local, remote);
        assertEquals("api.customer.com", result.getString("httpServerAddress"));
        assertEquals(8082, result.getInt("httpPort"));
        assertEquals("mqtt.customer.com", result.getString("mqttServerAddress"));
        assertEquals(8883, result.getInt("mqttPort"));
        assertEquals("legacy.customer.com", result.getString("tcpServerAddress"));
        assertEquals(9010, result.getInt("tcpPort"));
        assertEquals("10.0.0.5", result.getString("backendServerIp"));
        assertEquals("MQTT", result.getString("backendTransport"));
    }

    @Test
    public void undocumentedEndpointAliasesAndMqttPortAreIgnored() throws Exception {
        JSONObject result = DeviceConfigMapper.apply(new JSONObject()
                        .put("httpScheme", "https")
                        .put("httpServerAddress", "old-api")
                        .put("httpPort", 443)
                        .put("mqttScheme", "ssl")
                        .put("mqttServerAddress", "old-mqtt")
                        .put("mqttPort", 8883),
                new JSONObject()
                        .put("httpHost", "invented-api")
                        .put("httpBaseUrl", "https://invented-api")
                        .put("mqttHost", "invented-mqtt")
                        .put("mqttBrokerUrl", "ssl://invented-mqtt:1883")
                        .put("mqttPort", 1883)
                        .put("communicationMode", "TCP"));
        assertEquals("old-api", result.getString("httpServerAddress"));
        assertEquals("old-mqtt", result.getString("mqttServerAddress"));
        assertEquals(8883, result.getInt("mqttPort"));
        assertEquals("MQTT", result.getString("backendTransport"));
    }
}
