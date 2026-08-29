package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 授权状态查询响应，对应 GET /api/v1/device/auth/status。
 * <p>文档 V4.2 §4.2.5。
 */
public class AuthStatusResponse {

    /** 是否已授权 */
    public boolean authorized;
    /** 授权到期时间戳 (ms)，可能为空 */
    public Long authorizedUntil;
    /** 剩余天数，可能为空（永久授权） */
    public Integer daysRemaining;
    /** 已授权的功能列表 */
    public List<String> features;

    public static AuthStatusResponse fromJson(JSONObject data) throws JSONException {
        AuthStatusResponse resp = new AuthStatusResponse();
        resp.authorized = data.optBoolean("authorized");
        resp.authorizedUntil = data.has("authorizedUntil") && !data.isNull("authorizedUntil")
                ? data.optLong("authorizedUntil") : null;
        resp.daysRemaining = data.has("daysRemaining") && !data.isNull("daysRemaining")
                ? data.optInt("daysRemaining") : null;
        if (data.has("features")) {
            JSONArray arr = data.getJSONArray("features");
            resp.features = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                resp.features.add(arr.getString(i));
            }
        }
        return resp;
    }
}
