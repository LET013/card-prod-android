package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

/**
 * 激活/验证通过后返回的 MQTT 凭证等公共字段。
 * 被 ActivateResponse 和 VerifyCodeResponse 共用。
 */
public class ActivationResult {
    public final String mqttPassword;
    public final String signingKey;
    public final String clientId;
    public final String deviceName;
    public final String deviceCode;
    public final long expireTime;
    public final String initialAdminPassword;

    private ActivationResult(Builder builder) {
        this.mqttPassword = builder.mqttPassword;
        this.signingKey = builder.signingKey;
        this.clientId = builder.clientId;
        this.deviceName = builder.deviceName;
        this.deviceCode = builder.deviceCode;
        this.expireTime = builder.expireTime;
        this.initialAdminPassword = builder.initialAdminPassword;
    }

    public static Builder parse(JSONObject data) {
        return new Builder()
                .mqttPassword(data.optString("mqttPassword", ""))
                .signingKey(data.optString("signingKey", ""))
                .clientId(data.optString("clientId", ""))
                .deviceName(data.optString("deviceName", ""))
                .deviceCode(data.optString("deviceCode", ""))
                .expireTime(data.optLong("expireTime", 0L))
                .initialAdminPassword(parseInitialAdminPassword(data));
    }

    public static String parseInitialAdminPassword(JSONObject data) {
        if (data == null) return "";
        String value = data.optString("initialAdminPassword", "");
        if (!value.isEmpty()) return value;
        value = data.optString("initAdminPassword", "");
        if (!value.isEmpty()) return value;
        value = data.optString("adminInitialPassword", "");
        if (!value.isEmpty()) return value;
        value = data.optString("adminPassword", "");
        if (!value.isEmpty()) return value;
        return data.optString("initialPassword", "");
    }

    public void validate() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (mqttPassword.isEmpty()) missing.add("mqttPassword");
        if (signingKey.isEmpty()) missing.add("signingKey");
        if (clientId.isEmpty()) missing.add("clientId");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ActivationResult 缺少必要字段: " + missing);
        }
    }

    public static class Builder {
        private String mqttPassword = "";
        private String signingKey = "";
        private String clientId = "";
        private String deviceName = "";
        private String deviceCode = "";
        private long expireTime;
        private String initialAdminPassword = "";

        public Builder mqttPassword(String v) { this.mqttPassword = v; return this; }
        public Builder signingKey(String v) { this.signingKey = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder deviceName(String v) { this.deviceName = v; return this; }
        public Builder deviceCode(String v) { this.deviceCode = v; return this; }
        public Builder expireTime(long v) { this.expireTime = v; return this; }
        public Builder initialAdminPassword(String v) { this.initialAdminPassword = v; return this; }
        public ActivationResult build() { return new ActivationResult(this); }
    }
}
