package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 批量操作结果上报请求，对应 POST /api/v1/device/batch-result。
 * <p>文档 V4.2 §4.2.7。
 */
public class BatchOperationResultRequest {

    /** 操作 ID（用于关联批量操作） */
    public String operationId;
    /** 整体结果：success / partial / fail */
    public String result;
    /** 每项操作详情 */
    public List<OperationDetail> details;

    public BatchOperationResultRequest() {}

    public BatchOperationResultRequest(String operationId, String result, List<OperationDetail> details) {
        this.operationId = operationId;
        this.result = result;
        this.details = details;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("operationId", operationId);
        json.put("result", result);
        if (details != null) {
            JSONArray arr = new JSONArray();
            for (OperationDetail d : details) {
                arr.put(d.toJson());
            }
            json.put("details", arr);
        }
        return json;
    }

    public static class OperationDetail {
        public int slotId;
        public String action;
        public String status;
        public String cardNo;
        public String errorMsg;

        public OperationDetail() {}

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("slotId", slotId);
            json.put("action", action);
            json.put("status", status);
            if (cardNo != null) json.put("cardNo", cardNo);
            if (errorMsg != null) json.put("errorMsg", errorMsg);
            return json;
        }
    }
}
