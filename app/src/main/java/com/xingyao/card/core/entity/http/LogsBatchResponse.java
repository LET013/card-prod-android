package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 批量日志上报响应，对应 POST /api/v1/logs/batch。
 * <p>文档 V4.2 §4.6.2。
 */
public class LogsBatchResponse {

    /** 成功接收条数 */
    public int receivedCount;
    /** 失败条数 */
    public int failedCount;

    public static LogsBatchResponse fromJson(JSONObject data) throws JSONException {
        LogsBatchResponse resp = new LogsBatchResponse();
        resp.receivedCount = data.optInt("receivedCount");
        resp.failedCount = data.optInt("failedCount");
        return resp;
    }
}
