package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 员工资料同步请求（分页），对应 POST /api/v1/employee/sync。
 * <p>文档 V4.2 §4.3.1。
 */
public class EmployeeSyncRequest {

    /** 上次同步时间戳（首次传 0） */
    public long lastSyncTime;
    /** 页码（从 1 开始） */
    public int page;
    /** 每页条数 */
    public int pageSize;

    public EmployeeSyncRequest() {}

    public EmployeeSyncRequest(long lastSyncTime, int page, int pageSize) {
        this.lastSyncTime = lastSyncTime;
        this.page = page;
        this.pageSize = pageSize;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("lastSyncTime", lastSyncTime);
        json.put("page", page);
        json.put("pageSize", pageSize);
        return json;
    }
}
