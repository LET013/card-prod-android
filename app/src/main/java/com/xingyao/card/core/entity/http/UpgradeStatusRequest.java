package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 固件升级状态上报请求，对应 POST /api/v1/upgrade/status。
 * <p>文档 V4.2 §4.8：升级过程中上报进度。
 */
public class UpgradeStatusRequest {

    /** 固件版本号 */
    public String firmwareVersion;
    /** 状态：downloading / verifying / installing / success / failed */
    public String status;
    /** 进度百分比 (0-100) */
    public int progress;
    /** 错误信息（failed 时填写） */
    public String errorMsg;

    public UpgradeStatusRequest() {}

    public UpgradeStatusRequest(String firmwareVersion, String status, int progress, String errorMsg) {
        this.firmwareVersion = firmwareVersion;
        this.status = status;
        this.progress = progress;
        this.errorMsg = errorMsg;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("firmwareVersion", firmwareVersion);
        json.put("status", status);
        json.put("progress", progress);
        if (errorMsg != null) json.put("errorMsg", errorMsg);
        return json;
    }
}
