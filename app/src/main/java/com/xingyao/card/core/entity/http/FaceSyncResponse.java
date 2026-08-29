package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 人脸特征同步响应，对应 POST /api/v1/employee/face/sync。
 * <p>文档 V4.2 §4.3.2。
 */
public class FaceSyncResponse {

    public String syncVersion;
    public List<FaceFeatureSyncItem> faceFeatures;
    public int total;
    public int page;
    public int pageSize;
    public boolean hasMore;

    public static FaceSyncResponse fromJson(JSONObject data) throws JSONException {
        FaceSyncResponse resp = new FaceSyncResponse();
        resp.syncVersion = data.optString("syncVersion");
        resp.total = data.optInt("total");
        resp.page = data.optInt("page");
        resp.pageSize = data.optInt("pageSize");
        resp.hasMore = data.optBoolean("hasMore");

        if (data.has("faceFeatures")) {
            JSONArray arr = data.getJSONArray("faceFeatures");
            resp.faceFeatures = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                resp.faceFeatures.add(FaceFeatureSyncItem.fromJson(arr.getJSONObject(i)));
            }
        }

        return resp;
    }
}
