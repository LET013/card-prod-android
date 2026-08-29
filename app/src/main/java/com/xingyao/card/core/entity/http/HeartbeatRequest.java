package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * HTTP 心跳请求，对应 POST /api/v1/device/heartbeat。
 * <p>根据文档 V4.2 §4.2.1，除了 sn 和 timestamp 无需额外字段；
 * seq 为可选字段用于服务端防重，客户端可传 null。
 */
public class HeartbeatRequest {

    /** 心跳序号（可选，服务端用于防重） */
    public Integer seq;

    public HeartbeatRequest() {}

    public HeartbeatRequest(Integer seq) {
        this.seq = seq;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (seq != null) {
            json.put("seq", seq);
        }
        return json;
    }
}
