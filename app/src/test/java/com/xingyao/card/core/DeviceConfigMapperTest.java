package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeviceConfigMapperTest {
    @Test
    public void genericServerIpDoesNotOverwriteIndependentHosts() throws Exception {
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
                .put("mqttPort", 1883)
                .put("communicationMode", "MQTT")
                .put("baudRate", 57600)
                .put("groupSize", 16)
                .put("totalSlots", 100)
                .put("pollingInterval", 5000)
                .put("faceThreshold", 0.82);

        JSONObject result = DeviceConfigMapper.apply(local, remote);

        assertEquals("api.customer.com", result.getString("httpServerAddress"));
        assertEquals(8082, result.getInt("httpPort"));
        assertEquals("mqtt.customer.com", result.getString("mqttServerAddress"));
        assertEquals(1883, result.getInt("mqttPort"));
        assertEquals("legacy.customer.com", result.getString("tcpServerAddress"));
        assertEquals("10.0.0.5", result.getString("backendServerIp"));
        assertEquals("MQTT", result.getString("backendTransport"));
        assertEquals(100, result.getInt("totalCount"));
        assertEquals(16, result.getInt("singleGroupCount"));
    }

    @Test
    public void channelSpecificFieldsMayUpdateTheirOwnEndpointOnly() throws Exception {
        JSONObject result = DeviceConfigMapper.apply(new JSONObject()
                        .put("httpServerAddress", "old-api")
                        .put("mqttServerAddress", "old-mqtt")
                        .put("tcpServerAddress", "old-tcp"),
                new JSONObject()
                        .put("httpHost", "new-api")
                        .put("mqttHost", "new-mqtt")
                        .put("tcpHost", "new-tcp"));

        assertEquals("new-api", result.getString("httpServerAddress"));
        assertEquals("new-mqtt", result.getString("mqttServerAddress"));
        assertEquals("new-tcp", result.getString("tcpServerAddress"));
    }
}
