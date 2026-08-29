package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * HTTP 心跳响应，对应 POST /api/v1/device/heartbeat。
 * <p>文档 V4.2 §4.2.1：data 部分含 serverTime。
 */
public class HeartbeatResponse {

    /** 服务端时间戳 (ms) */
    public long serverTime;

    public static HeartbeatResponse fromJson(JSONObject data) throws JSONException {
        HeartbeatResponse resp = new HeartbeatResponse();
        resp.serverTime = data.optLong("serverTime");
        return resp;
    }
}
