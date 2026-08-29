package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 设备注册响应: POST /api/v1/device/register 的 data 字段
 */
public class RegisterResponse {
    public final String deviceToken;
    public final String deviceCode;
    public final boolean isNew;

    private RegisterResponse(String deviceToken, String deviceCode, boolean isNew) {
        this.deviceToken = deviceToken;
        this.deviceCode = deviceCode;
        this.isNew = isNew;
    }

    public static RegisterResponse fromJson(JSONObject data) throws IOException {
        String deviceToken = data.optString("deviceToken", "");
        if (deviceToken.isEmpty()) throw new IOException("register: missing deviceToken");
        String deviceCode = data.optString("deviceCode", "");
        if (deviceCode.isEmpty()) throw new IOException("register: missing deviceCode");
        boolean isNew = data.optBoolean("isNew", true);
        return new RegisterResponse(deviceToken, deviceCode, isNew);
    }
}
