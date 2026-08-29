package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 员工资料同步响应，对应 POST /api/v1/employee/sync。
 * <p>文档 V4.2 §4.3.1。
 */
public class EmployeeSyncResponse {

    public String syncVersion;
    public List<EmployeeSyncItem> employees;
    public List<String> deletedEmployeeIds;
    public int total;
    public int page;
    public int pageSize;
    public boolean hasMore;

    public static EmployeeSyncResponse fromJson(JSONObject data) throws JSONException {
        EmployeeSyncResponse resp = new EmployeeSyncResponse();
        resp.syncVersion = data.optString("syncVersion");
        resp.total = data.optInt("total");
        resp.page = data.optInt("page");
        resp.pageSize = data.optInt("pageSize");
        resp.hasMore = data.optBoolean("hasMore");

        if (data.has("employees")) {
            JSONArray arr = data.getJSONArray("employees");
            resp.employees = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                resp.employees.add(EmployeeSyncItem.fromJson(arr.getJSONObject(i)));
            }
        }

        if (data.has("deletedEmployeeIds")) {
            JSONArray arr = data.getJSONArray("deletedEmployeeIds");
            resp.deletedEmployeeIds = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                resp.deletedEmployeeIds.add(arr.getString(i));
            }
        }

        return resp;
    }
}
