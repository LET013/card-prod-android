package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 人脸特征上传请求，对应 POST /api/v1/employee/face。
 * <p>文档 V4.2 §4.3.5：设备端录入人脸后上报特征。
 */
public class FaceUploadRequest {

    /** 员工 ID */
    public String employeeId;
    /** 人脸特征 Base64 */
    public String faceFeature;

    public FaceUploadRequest() {}

    public FaceUploadRequest(String employeeId, String faceFeature) {
        this.employeeId = employeeId;
        this.faceFeature = faceFeature;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("employeeId", employeeId);
        json.put("faceFeature", faceFeature);
        return json;
    }
}
