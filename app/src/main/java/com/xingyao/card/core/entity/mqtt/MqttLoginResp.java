package com.xingyao.card.core.entity.mqtt;

import org.json.JSONObject;

/**
 * MQTT loginResp 响应数据。
 */
public class MqttLoginResp {
    public final int code;
    public final String message;
    public final String token;

    private MqttLoginResp(int code, String message, String token) {
        this.code = code;
        this.message = message;
        this.token = token;
    }

    public boolean isSuccess() {
        return code == 0;
    }

    public static MqttLoginResp fromJson(JSONObject data) {
        int code = data.optInt("code", -1);
        String message = data.optString("message", data.optString("msg", ""));
        String token = data.optString("token", "");
        return new MqttLoginResp(code, message, token);
    }
}
