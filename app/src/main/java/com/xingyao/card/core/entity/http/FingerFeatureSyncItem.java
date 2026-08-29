package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 指纹特征同步数据项，属于 {@link FingerSyncResponse}。
 * <p>文档 V4.2 §4.3.3。
 */
public class FingerFeatureSyncItem {

    /** 指纹 ID */
    public String fingerId;
    /** 所属员工 ID */
    public String employeeId;
    /** 指纹特征 Base64 字符串 */
    public String fingerFeature;
    /** 手指索引（1-10） */
    public int fingerIndex;
    /** 特征版本号 */
    public String featureVersion;
    /** 状态：active / inactive */
    public String status;

    public static FingerFeatureSyncItem fromJson(JSONObject json) throws JSONException {
        FingerFeatureSyncItem item = new FingerFeatureSyncItem();
        item.fingerId = json.optString("fingerId");
        item.employeeId = json.optString("employeeId");
        item.fingerFeature = json.optString("fingerFeature");
        item.fingerIndex = json.optInt("fingerIndex");
        item.featureVersion = json.optString("featureVersion");
        item.status = json.optString("status", "active");
        return item;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("fingerId", fingerId);
        json.put("employeeId", employeeId);
        json.put("fingerFeature", fingerFeature);
        json.put("fingerIndex", fingerIndex);
        if (featureVersion != null) json.put("featureVersion", featureVersion);
        json.put("status", status);
        return json;
    }
}
