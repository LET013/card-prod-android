package com.xingyao.card.core.entity.http;

import com.xingyao.card.core.utils.DeviceInfoUtil;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设备注册请求 body: POST /api/v1/device/register
 */
public class RegisterRequest {
    public final String machineId;
    public final String mac;
    public final String model;
    public final String osType;
    public final String osVersion;
    public final String version;
    public final int versionCode;
    public final String channelId;

    private RegisterRequest(Builder builder) {
        this.machineId = builder.machineId;
        this.mac = builder.mac;
        this.model = builder.model;
        this.osType = builder.osType;
        this.osVersion = builder.osVersion;
        this.version = builder.version;
        this.versionCode = builder.versionCode;
        this.channelId = builder.channelId;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("machineId", machineId)
                .put("mac", mac)
                .put("model", model)
                .put("osType", osType)
                .put("osVersion", osVersion)
                .put("version", version)
                .put("versionCode", versionCode)
                .put("channelId", channelId);
    }

    public static class Builder {
        private String machineId;
        private String mac = "";
        private String model;
        private String osType = "ANDROID";
        private String osVersion;
        private String version;
        private int versionCode;
        private String channelId = "";

        /** 从 machineId 自动填充设备信息 */
        public Builder fromDevice(String machineId) throws JSONException {
            this.machineId = machineId;
            JSONObject dev = DeviceInfoUtil.deviceBody(machineId);
            this.model = dev.optString("model", "");
            this.osVersion = dev.optString("osVersion", "");
            this.version = DeviceInfoUtil.versionName();
            this.versionCode = DeviceInfoUtil.versionCode();
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId == null ? "" : channelId.trim();
            return this;
        }

        public RegisterRequest build() {
            return new RegisterRequest(this);
        }
    }
}
