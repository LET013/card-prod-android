package com.xingyao.card.core.entity.http;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DeviceConfigEntityTransportTest {

    @Test
    public void httpModeDoesNotRequireMqttEndpoint() throws Exception {
        DeviceConfigEntity config = DeviceConfigEntity.fromJson(baseConfig("HTTP")
                .put("mqttHost", "")
                .put("mqttPort", 0));

        config.validate();
    }

    @Test
    public void bothModeUsesMqttAsPrimaryTransport() throws Exception {
        DeviceConfigEntity config = DeviceConfigEntity.fromJson(baseConfig("BOTH"));

        config.validate();

        assertTrue(DeviceConfigEntity.usesMqttTransport(config.getCommunicationMode()));
    }

    @Test(expected = DeviceConfigEntity.BootstrapConfigException.class)
    public void mqttModeRequiresMqttEndpoint() throws Exception {
        DeviceConfigEntity config = DeviceConfigEntity.fromJson(baseConfig("MQTT")
                .put("mqttHost", ""));

        config.validate();
    }

    private static JSONObject baseConfig(String mode) throws Exception {
        return new JSONObject()
                .put("communicationMode", mode)
                .put("serialPort", "/dev/ttyS5")
                .put("baudRate", 57600)
                .put("totalSlots", 100)
                .put("httpHost", "card-test.quyohui.com")
                .put("httpPort", 80)
                .put("mqttHost", "119.146.88.108")
                .put("mqttPort", 48419);
    }
}
