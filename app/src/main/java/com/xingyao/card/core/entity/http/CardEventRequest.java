package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 卡片事件上报请求（HTTP 降级），对应 POST /api/v1/card/event。
 * <p>文档 V4.2 §4.4.3：MQTT 不可用时通过 HTTP 上报卡片事件。
 * 主路径应使用 MQTT cardEvent。
 */
public class CardEventRequest {

    /** 卡号 */
    public String cardNo;
    /** 事件类型：take / return */
    public String eventType;
    /** 卡槽编号 */
    public int slotId;
    /** 事件时间戳 (ms) */
    public long timestamp;
    /** 验证方式：face / finger / card / manual */
    public String authType;

    public CardEventRequest() {}

    public CardEventRequest(String cardNo, String eventType, int slotId, long timestamp, String authType) {
        this.cardNo = cardNo;
        this.eventType = eventType;
        this.slotId = slotId;
        this.timestamp = timestamp;
        this.authType = authType;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("cardNo", cardNo);
        json.put("eventType", eventType);
        json.put("slotId", slotId);
        json.put("timestamp", timestamp);
        if (authType != null) json.put("authType", authType);
        return json;
    }
}
