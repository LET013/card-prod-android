package com.xingyao.card.core.bootstrap;

import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.MqttTopics;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceBootstrapHeartbeatPolicyTest {

    @Test
    public void mqttAndBothUseMqttLoginWhileHttpUsesHttpLogin() {
        assertTrue(DeviceBootstrapManager.usesMqttTransport("MQTT"));
        assertTrue(DeviceBootstrapManager.usesMqttTransport("BOTH"));
        assertFalse(DeviceBootstrapManager.usesMqttTransport("HTTP"));
    }

    @Test
    public void reconnectLoginDelayUsesConfiguredExponentialBackoff() {
        assertEquals(1000L, DeviceBootstrapManager.nextMqttReloginDelay(1000, 60000, 0));
        assertEquals(2000L, DeviceBootstrapManager.nextMqttReloginDelay(1000, 60000, 1));
        assertEquals(60000L, DeviceBootstrapManager.nextMqttReloginDelay(1000, 60000, 20));
    }

    @Test
    public void loginResponseRequiresPendingRequestAndServerResponseTopic() {
        assertTrue(DeviceBootstrapManager.isExpectedLoginResponse(
                "lg_001", MqttTopics.downResponse("DEVICE001"), "DEVICE001"));
        assertFalse(DeviceBootstrapManager.isExpectedLoginResponse(
                null, MqttTopics.downResponse("DEVICE001"), "DEVICE001"));
        assertFalse(DeviceBootstrapManager.isExpectedLoginResponse(
                "", MqttTopics.downResponse("DEVICE001"), "DEVICE001"));
        assertFalse(DeviceBootstrapManager.isExpectedLoginResponse(
                "lg_001", MqttTopics.down("DEVICE001"), "DEVICE001"));
        assertFalse(DeviceBootstrapManager.isExpectedLoginResponse(
                "lg_001", MqttTopics.downResponse("DEVICE002"), "DEVICE001"));
    }

    @Test
    public void onlyExplicitDeviceNotLoggedInResponseRequiresRelogin() throws Exception {
        assertTrue(DeviceBootstrapManager.requiresMqttRelogin("heartbeatResp",
                new JSONObject().put("code", -1).put("msg", "设备未登录，请先发送 login 指令")));
        assertTrue(DeviceBootstrapManager.requiresMqttRelogin("getDepartmentResp",
                new JSONObject().put("code", -1).put("msg", "设备未登录，请先发送登录指令")));
        assertFalse(DeviceBootstrapManager.requiresMqttRelogin("logReportResp",
                new JSONObject().put("code", -1).put("msg", "消息签名验证失败")));
        assertFalse(DeviceBootstrapManager.requiresMqttRelogin("heartbeatResp",
                new JSONObject().put("code", 0).put("msg", "成功")));
        assertFalse(DeviceBootstrapManager.requiresMqttRelogin(MqttCmd.LOGIN_RESP,
                new JSONObject().put("code", -1).put("msg", "设备未登录，请先发送 login 指令")));
    }
}
