package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 授权状态变更上报请求，对应 POST /api/v1/device/auth/change。
 * <p>文档 V4.2 §4.2.4：设备端授权状态变更时通知服务端。
 */
public class AuthChangeRequest {

    /** 新的授权状态 */
    public String authStatus;
    /** 操作人 */
    public String operator;

    public AuthChangeRequest() {}

    public AuthChangeRequest(String authStatus, String operator) {
        this.authStatus = authStatus;
        this.operator = operator;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("authStatus", authStatus);
        if (operator != null) json.put("operator", operator);
        return json;
    }
}
