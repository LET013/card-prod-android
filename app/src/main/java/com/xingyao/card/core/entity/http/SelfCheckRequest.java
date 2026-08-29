package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 设备自检结果上报请求，对应 POST /api/v1/device/selfcheck。
 * <p>文档 V4.2 §4.2.6。
 */
public class SelfCheckRequest {

    /** 自检结果：pass / fail */
    public String result;
    /** 检查项详情 */
    public List<CheckItem> details;

    public SelfCheckRequest() {}

    public SelfCheckRequest(String result, List<CheckItem> details) {
        this.result = result;
        this.details = details;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("result", result);
        if (details != null) {
            JSONArray arr = new JSONArray();
            for (CheckItem item : details) {
                arr.put(item.toJson());
            }
            json.put("details", arr);
        }
        return json;
    }

    /** 单项自检结果 */
    public static class CheckItem {
        /** 检查项名称 */
        public String name;
        /** 结果：pass / fail */
        public String status;
        /** 失败时的错误信息 */
        public String errorMsg;

        public CheckItem() {}

        public CheckItem(String name, String status, String errorMsg) {
            this.name = name;
            this.status = status;
            this.errorMsg = errorMsg;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("status", status);
            if (errorMsg != null) json.put("errorMsg", errorMsg);
            return json;
        }
    }
}
