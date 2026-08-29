package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

/**
 * 设备配置响应: GET /api/v1/device/config 的 data 字段
 */
public class DeviceConfigResponse {
    public final String communicationMode;
    /** 原始 JSON 字符串，用于持久化完整配置 */
    public final String rawJson;

    private DeviceConfigResponse(String communicationMode, String rawJson) {
        this.communicationMode = communicationMode;
        this.rawJson = rawJson;
    }

    public static DeviceConfigResponse fromJson(JSONObject data) {
        String mode = data.optString("communicationMode",
                data.optString("backendTransport", "MQTT"));
        return new DeviceConfigResponse(mode, data.toString());
    }
}
