package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 激活码验证请求 body: POST /api/v1/device/verify
 */
public class VerifyCodeRequest {
    public final String registerCode;
    public final String activeKey;

    public VerifyCodeRequest(String registerCode, String activeKey) {
        this.registerCode = registerCode;
        this.activeKey = activeKey;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("registerCode", registerCode)
                .put("activeKey", activeKey);
    }
}
