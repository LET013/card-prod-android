package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量日志上报请求，对应 POST /api/v1/logs/batch。
 * <p>文档 V4.2 §4.6.2。
 */
public class LogsBatchRequest {

    /** 日志条目列表 */
    public List<LogEntry> logs;

    public LogsBatchRequest() {}

    public LogsBatchRequest(List<LogEntry> logs) {
        this.logs = logs;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (logs != null) {
            JSONArray arr = new JSONArray();
            for (LogEntry e : logs) {
                arr.put(e.toJson());
            }
            json.put("logs", arr);
        }
        return json;
    }

    /** 单条日志条目 */
    public static class LogEntry {
        public String level;
        public String tag;
        public String content;
        public long timestamp;

        public LogEntry() {}

        public LogEntry(String level, String tag, String content, long timestamp) {
            this.level = level;
            this.tag = tag;
            this.content = content;
            this.timestamp = timestamp;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("level", level);
            json.put("tag", tag);
            json.put("content", content);
            json.put("timestamp", timestamp);
            return json;
        }

        public static LogEntry fromJson(JSONObject json) throws JSONException {
            LogEntry e = new LogEntry();
            e.level = json.optString("level");
            e.tag = json.optString("tag");
            e.content = json.optString("content");
            e.timestamp = json.optLong("timestamp");
            return e;
        }
    }
}
