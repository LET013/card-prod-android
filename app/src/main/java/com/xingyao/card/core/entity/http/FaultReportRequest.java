package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 故障上报请求，对应 POST /api/v1/fault/report。
 * <p>文档 V4.2 §4.7。
 */
public class FaultReportRequest {

    /** 卡槽编号（-1 表示设备级故障） */
    public int slotId;
    /** 故障码 */
    public String faultCode;
    /** 故障描述信息 */
    public String faultMsg;
    /** 严重级别：critical / major / minor */
    public String severity;
    /** 故障发生时间戳 (ms) */
    public long timestamp;

    public FaultReportRequest() {}

    public FaultReportRequest(int slotId, String faultCode, String faultMsg, String severity, long timestamp) {
        this.slotId = slotId;
        this.faultCode = faultCode;
        this.faultMsg = faultMsg;
        this.severity = severity;
        this.timestamp = timestamp;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("slotId", slotId);
        json.put("faultCode", faultCode);
        if (faultMsg != null) json.put("faultMsg", faultMsg);
        json.put("severity", severity);
        json.put("timestamp", timestamp);
        return json;
    }
}
