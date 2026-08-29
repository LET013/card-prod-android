package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 指纹特征同步响应，对应 POST /api/v1/employee/finger/sync。
 * <p>文档 V4.2 §4.3.3。
 */
public class FingerSyncResponse {

    public String syncVersion;
    public List<FingerFeatureSyncItem> fingerFeatures;
    public int total;
    public int page;
    public int pageSize;
    public boolean hasMore;

    public static FingerSyncResponse fromJson(JSONObject data) throws JSONException {
        FingerSyncResponse resp = new FingerSyncResponse();
        resp.syncVersion = data.optString("syncVersion");
        resp.total = data.optInt("total");
        resp.page = data.optInt("page");
        resp.pageSize = data.optInt("pageSize");
        resp.hasMore = data.optBoolean("hasMore");

        if (data.has("fingerFeatures")) {
            JSONArray arr = data.getJSONArray("fingerFeatures");
            resp.fingerFeatures = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                resp.fingerFeatures.add(FingerFeatureSyncItem.fromJson(arr.getJSONObject(i)));
            }
        }

        return resp;
    }
}
