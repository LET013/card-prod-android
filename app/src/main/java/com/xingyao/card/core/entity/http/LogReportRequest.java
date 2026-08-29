package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 日志上报请求，对应 POST /api/v1/log/report。
 * <p>文档 V4.2 §4.6.1：单条日志上报。
 */
public class LogReportRequest {

    /** 日志级别：debug / info / warn / error */
    public String level;
    /** 日志标签（模块名） */
    public String tag;
    /** 日志内容 */
    public String content;
    /** 日志时间戳 (ms) */
    public long timestamp;

    public LogReportRequest() {}

    public LogReportRequest(String level, String tag, String content, long timestamp) {
        this.level = level;
        this.tag = tag;
        this.content = content;
        this.timestamp = timestamp;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("level", level);
        json.put("tag", tag);
        json.put("content", content);
        json.put("timestamp", timestamp);
        return json;
    }
}
