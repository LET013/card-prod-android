package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 人脸特征同步数据项，属于 {@link FaceSyncResponse}。
 * <p>文档 V4.2 §4.3.2。
 */
public class FaceFeatureSyncItem {

    /** 人脸 ID（如 "face_xxx"） */
    public String faceId;
    /** 所属员工 ID */
    public String employeeId;
    /** 人脸特征 Base64 字符串 */
    public String faceFeature;
    /** 人脸图片 URL（可能为空） */
    public String faceImage;
    /** 人脸图片 Base64（可能为空） */
    public String faceImageBase64;
    /** 特征版本号 */
    public String featureVersion;
    /** 状态：active / inactive */
    public String status;

    public static FaceFeatureSyncItem fromJson(JSONObject json) throws JSONException {
        FaceFeatureSyncItem item = new FaceFeatureSyncItem();
        item.faceId = json.optString("faceId");
        item.employeeId = json.optString("employeeId");
        item.faceFeature = json.optString("faceFeature");
        item.faceImage = json.optString("faceImage");
        item.faceImageBase64 = json.optString("faceImageBase64");
        item.featureVersion = json.optString("featureVersion");
        item.status = json.optString("status", "active");
        return item;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("faceId", faceId);
        json.put("employeeId", employeeId);
        json.put("faceFeature", faceFeature);
        if (faceImage != null) json.put("faceImage", faceImage);
        if (faceImageBase64 != null) json.put("faceImageBase64", faceImageBase64);
        if (featureVersion != null) json.put("featureVersion", featureVersion);
        json.put("status", status);
        return json;
    }
}
