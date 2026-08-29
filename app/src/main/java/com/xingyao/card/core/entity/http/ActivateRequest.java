package com.xingyao.card.core.entity.http;

import com.xingyao.card.core.utils.DeviceInfoUtil;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设备激活请求 body: POST /api/v1/device/activate
 */
public class ActivateRequest {
    public final String machineId;
    public final String mac;
    public final String model;
    public final String osType;
    public final String osVersion;
    public final String version;
    public final String deviceId;

    private ActivateRequest(Builder builder) {
        this.machineId = builder.machineId;
        this.mac = builder.mac;
        this.model = builder.model;
        this.osType = builder.osType;
        this.osVersion = builder.osVersion;
        this.version = builder.version;
        this.deviceId = builder.deviceId;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("machineId", machineId)
                .put("mac", mac)
                .put("model", model)
                .put("osType", osType)
                .put("osVersion", osVersion)
                .put("version", version)
                .put("deviceId", deviceId);
    }

    public static class Builder {
        private String machineId;
        private String mac = "";
        private String model;
        private String osType = "ANDROID";
        private String osVersion;
        private String version;
        private String deviceId;

        public Builder fromDevice(String machineId) throws JSONException {
            this.machineId = machineId;
            JSONObject dev = DeviceInfoUtil.deviceBody(machineId);
            this.model = dev.optString("model", "");
            this.osVersion = dev.optString("osVersion", "");
            this.version = DeviceInfoUtil.versionName();
            return this;
        }

        public Builder deviceId(String v) { this.deviceId = v; return this; }
        public ActivateRequest build() { return new ActivateRequest(this); }
    }
}
