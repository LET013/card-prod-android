package com.xingyao.card.core.biz.http;

import com.xingyao.card.core.entity.http.CardEventRequest;
import com.xingyao.card.core.http.BaseApiService;
import com.xingyao.card.core.http.HttpClientManager;

import org.json.JSONException;

import java.io.IOException;

/**
 * 卡片事件相关 HTTP API 服务（HTTP 降级路径）。
 *
 * <p>文档 V4.2 §4.4.3：MQTT 不可用时通过 HTTP 上报卡片事件。
 * 主路径应使用 MQTT cardEvent。
 */
public class CardApiService extends BaseApiService {

    public CardApiService(HttpClientManager http) {
        super(http, "/api/v1");
    }

    /**
     * 卡片事件上报（HTTP 降级）。POST /api/v1/card/event
     *
     * @param req 卡片事件请求
     */
    public void sendEvent(CardEventRequest req) throws IOException {
        try {
            apiPost("/card/event", req.toJson());
        } catch (JSONException e) {
            throw new IOException("CardApiService.sendEvent JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
