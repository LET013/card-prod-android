package com.xingyao.card.core.utils;

import com.xingyao.card.core.mqtt.MqttEnvelope;
import com.xingyao.card.core.entity.http.FaceRegisteredResponse;
import com.xingyao.card.core.entity.http.LoginRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MqttEnvelopeRawTest {
    @Test
    public void statusReportUsesOnlyRawAndSignsEnvelopeFieldsPlusTheExactRawString() throws Exception {
        JSONObject slot = new JSONObject()
                .put("slotId", 1)
                .put("status", "OCCUPIED")
                .put("cardNo", "CARD001")
                .put("voltage", 12.5)
                .put("chargeStatus", JSONObject.NULL);
        JSONObject businessData = new JSONObject()
                .put("slots", new JSONArray().put(slot));
        String dataString = businessData.toString();

        MqttEnvelope envelope = new MqttEnvelope.Builder("statusReport", "DEVICE001")
                .msgId("status_001")
                .data(businessData)
                .build();

        Object transmittedRaw = envelope.toJson().get("raw");
        assertTrue(transmittedRaw instanceof String);
        assertEquals(dataString, transmittedRaw);
        assertFalse(envelope.toJson().has("data"));
        assertEquals("status_001:statusReport:1786188181646:" + dataString,
                MqttSignUtil.signingInput(
                "status_001", "statusReport", 1786188181646L, envelope.raw));
    }

    @Test
    public void loginKeepsParametersAndUsesTheSameSignatureRuleAsEveryUplink() throws Exception {
        LoginRequest request = new LoginRequest("1.2.3", "192.168.1.8");
        MqttEnvelope envelope = new MqttEnvelope.Builder("login", "DEVICE001")
                .msgId("login_001")
                .signingKey("test-signing-key")
                .data(request.toJson())
                .build();

        assertEquals("1.2.3", new JSONObject(envelope.raw).getString("version"));
        assertEquals("192.168.1.8", new JSONObject(envelope.raw).getString("ip"));
        assertFalse(envelope.toJson().has("data"));
        assertEquals(
                "login_001:login:" + envelope.timestamp + ":" + envelope.raw,
                MqttSignUtil.signingInput(
                        envelope.msgId, envelope.cmd, envelope.timestamp, envelope.raw));
        assertEquals(
                MqttSignUtil.sign("test-signing-key", envelope.msgId,
                        envelope.cmd, envelope.timestamp, envelope.raw),
                envelope.sign);
    }

    @Test
    public void signatureUsesTheConfirmedEnvelopeInput() throws Exception {
        String input = MqttSignUtil.signingInput("login_001", "login", 1786188181646L,
                "{\"version\":\"1.2.3\",\"ip\":\"192.168.1.8\"}");
        assertEquals("login_001:login:1786188181646:{\"version\":\"1.2.3\",\"ip\":\"192.168.1.8\"}", input);
        assertEquals("lcSueM80kMMy2kyzu5OcudEreiuV2mpNGewVwLwELXY=",
                hmacSha256Base64("test-signing-key", input));
    }

    private static String hmacSha256Base64(String signingKey, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void faceRegisteredReadsOnlyTheCurrentObjectShape() throws Exception {
        FaceRegisteredResponse response = FaceRegisteredResponse.fromJson(new JSONObject()
                .put("employeeIds", new JSONArray().put(1001).put(1002))
                .put("count", 2));

        assertEquals(2, response.employeeIds.size());
        assertEquals("1001", response.employeeIds.get(0));
        assertEquals(2, response.count);
    }
}
