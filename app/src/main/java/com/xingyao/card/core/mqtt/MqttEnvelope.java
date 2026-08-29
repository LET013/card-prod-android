package com.xingyao.card.core.mqtt;

import com.xingyao.card.core.utils.MqttSignUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MQTT 消息 Envelope 构建器（带签名）。
 *
 * <p>结构: {@code {"msgId": "...", "cmd": "...", "timestamp": 123, "deviceCode": "...", "sign": "...", "raw": "{...}"}}
 *
 * <p>使用方式：
 * <pre>{@code
 * // 带签名
 * byte[] payload = new MqttEnvelope.Builder(MqttCmd.HEARTBEAT, deviceCode)
 *         .signingKey(signingKey)
 *         .data(new JSONObject())
 *         .build();
 * // 无签名（deviceCode 后置注入）
 * byte[] payload2 = new MqttEnvelope.Builder(MqttCmd.PING)
 *         .deviceCode(deviceCode)
 *         .data(new JSONObject())
 *         .build();
 * }</pre>
 */
public final class MqttEnvelope {
    public final String msgId;
    public final String cmd;
    public final long timestamp;
    public final String deviceCode;
    public final String sign;
    public final String raw;

    private MqttEnvelope(Builder builder) throws JSONException {
        this.msgId = builder.msgId != null ? builder.msgId
                : "mq_" + UUID.randomUUID().toString().substring(0, 8);
        this.cmd = builder.cmd;
        this.timestamp = System.currentTimeMillis();
        this.deviceCode = builder.deviceCode;
        JSONObject businessData = builder.data != null ? builder.data : new JSONObject();
        this.raw = businessData.toString();

        if (builder.signingKey != null && !builder.signingKey.isEmpty()) {
            this.sign = MqttSignUtil.sign(builder.signingKey, msgId, cmd, timestamp, this.raw);
        } else {
            this.sign = null;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put("msgId", msgId);
        envelope.put("cmd", cmd);
        envelope.put("timestamp", timestamp);
        if (deviceCode != null) envelope.put("deviceCode", deviceCode);
        if (sign != null) envelope.put("sign", sign);
        envelope.put("raw", raw);
        return envelope;
    }

    /** 构建完成直接输出为 UTF-8 字节数组 */
    public byte[] toBytes() throws JSONException {
        return toJson().toString().getBytes(StandardCharsets.UTF_8);
    }

    public static class Builder {
        private final String cmd;
        private String deviceCode;
        private String msgId;
        private String signingKey;
        private JSONObject data;

        /** 无 deviceCode 构造，适合凭证后置注入的场景 */
        public Builder(String cmd) {
            this.cmd = cmd;
            this.deviceCode = null;
        }

        public Builder(String cmd, String deviceCode) {
            this.cmd = cmd;
            this.deviceCode = deviceCode;
        }

        public Builder msgId(String v) { this.msgId = v; return this; }
        public Builder deviceCode(String v) { this.deviceCode = v; return this; }
        public Builder signingKey(String v) { this.signingKey = v; return this; }
        public Builder data(JSONObject v) { this.data = v; return this; }

        public MqttEnvelope build() throws JSONException {
            return new MqttEnvelope(this);
        }
    }
}
