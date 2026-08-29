package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/** HTTP/MQTT 共用的设备登录业务参数。 */
public class LoginRequest {
    public final String version;
    public final String ip;

    public LoginRequest(String version, String ip) {
        this.version = version == null ? "" : version.trim();
        this.ip = ip == null ? "" : ip.trim();
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("version", version)
                .put("ip", ip);
    }
}
