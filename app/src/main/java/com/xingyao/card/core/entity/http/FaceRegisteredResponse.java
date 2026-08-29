package com.xingyao.card.core.entity.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 已注册人脸员工查询响应，对应 GET /api/v1/employee/face/registered。
 * <p>文档 V4.2 §4.3.6。
 */
public class FaceRegisteredResponse {

    /** 已注册人脸的员工 ID 列表 */
    public List<String> employeeIds;
    /** 已注册员工数量 */
    public int count;

    public static FaceRegisteredResponse fromJson(JSONObject data) throws JSONException {
        FaceRegisteredResponse resp = new FaceRegisteredResponse();
        JSONArray arr = data.getJSONArray("employeeIds");
        resp.employeeIds = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            resp.employeeIds.add(String.valueOf(arr.get(i)));
        }
        resp.count = data.getInt("count");
        return resp;
    }
}
