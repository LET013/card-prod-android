package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 人脸特征同步请求（分页），对应 POST /api/v1/employee/face/sync。
 * <p>文档 V4.2 §4.3.2。
 */
public class FaceSyncRequest {

    /** 上次同步时间戳（首次传 0） */
    public long lastSyncTime;
    /** 页码（从 1 开始） */
    public int page;
    /** 每页条数 */
    public int pageSize;
    /** 是否包含图片标记 */
    public boolean includeFlags;

    public FaceSyncRequest() {}

    public FaceSyncRequest(long lastSyncTime, int page, int pageSize) {
        this(lastSyncTime, page, pageSize, false);
    }

    public FaceSyncRequest(long lastSyncTime, int page, int pageSize, boolean includeFlags) {
        this.lastSyncTime = lastSyncTime;
        this.page = page;
        this.pageSize = pageSize;
        this.includeFlags = includeFlags;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("lastSyncTime", lastSyncTime);
        json.put("page", page);
        json.put("pageSize", pageSize);
        json.put("includeFlags", includeFlags);
        return json;
    }
}
