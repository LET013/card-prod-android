package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 员工新增/更新请求，对应 POST /api/v1/employee。
 * <p>文档 V4.2 §4.3.4：Web 端添加/修改员工时同步到设备。
 * action = add / update。
 */
public class EmployeeUpsertRequest {

    /** 操作：add / update */
    public String action;
    public String employeeId;
    public String employeeCode;
    public String employeeName;
    public String cardNo;
    public String deptId;
    public String phone;
    public String email;
    public String department;
    public String position;
    /** 状态：enabled / disabled */
    public String status;

    public EmployeeUpsertRequest() {}

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("action", action);
        json.put("employeeId", employeeId);
        if (employeeCode != null) json.put("employeeCode", employeeCode);
        json.put("employeeName", employeeName);
        if (cardNo != null) json.put("cardNo", cardNo);
        if (deptId != null) json.put("deptId", deptId);
        if (phone != null) json.put("phone", phone);
        if (email != null) json.put("email", email);
        if (department != null) json.put("department", department);
        if (position != null) json.put("position", position);
        json.put("status", status != null ? status : "enabled");
        return json;
    }
}
