package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 卡槽状态上报请求，对应 POST /api/v1/device/status。
 * <p>文档 V4.2 §4.2.2：上报所有卡槽状态。
 */
public class StatusReportRequest {

    /** 卡槽状态列表 */
    public List<SlotStatusItem> slots;

    public StatusReportRequest() {}

    public StatusReportRequest(List<SlotStatusItem> slots) {
        this.slots = slots;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (slots != null) {
            JSONArray arr = new JSONArray();
            for (SlotStatusItem s : slots) {
                arr.put(s.toJson());
            }
            json.put("slots", arr);
        }
        return json;
    }

    /** 单个卡槽状态 */
    public static class SlotStatusItem {
        /** 卡槽编号 */
        public int slotId;
        /** 卡槽状态：occupied / empty / charging / full / fault */
        public String status;
        /** 卡号（occupied 时有值） */
        public String cardNo;
        /** 电压 (mV) */
        public Integer voltage;
        /** 电流 (mA) */
        public Integer current;
        /** 充电状态：charging / full / none */
        public String chargeStatus;
        /** 故障码（fault 时有值） */
        public String faultCode;

        public SlotStatusItem() {}

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("slotId", slotId);
            json.put("status", status);
            if (cardNo != null) json.put("cardNo", cardNo);
            if (voltage != null) json.put("voltage", voltage);
            if (current != null) json.put("current", current);
            if (chargeStatus != null) json.put("chargeStatus", chargeStatus);
            if (faultCode != null) json.put("faultCode", faultCode);
            return json;
        }
    }
}
