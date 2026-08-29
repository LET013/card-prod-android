package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 员工同步数据项，属于 {@link EmployeeSyncResponse}。
 * <p>文档 V4.2 §4.3.1。
 */
public class EmployeeSyncItem {

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
    /** 是否已注册人脸 */
    public boolean faceRegistered;
    /** 是否已注册指纹 */
    public boolean fingerRegistered;

    public static EmployeeSyncItem fromJson(JSONObject json) throws JSONException {
        EmployeeSyncItem item = new EmployeeSyncItem();
        item.employeeId = json.optString("employeeId");
        item.employeeCode = json.optString("employeeCode");
        item.employeeName = json.optString("employeeName");
        item.cardNo = json.optString("cardNo");
        item.deptId = json.optString("deptId");
        item.phone = json.optString("phone");
        item.email = json.optString("email");
        item.department = json.optString("department");
        item.position = json.optString("position");
        item.status = json.optString("status", "enabled");
        item.faceRegistered = json.optBoolean("faceRegistered");
        item.fingerRegistered = json.optBoolean("fingerRegistered");
        return item;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("employeeId", employeeId);
        if (employeeCode != null) json.put("employeeCode", employeeCode);
        json.put("employeeName", employeeName);
        if (cardNo != null) json.put("cardNo", cardNo);
        if (deptId != null) json.put("deptId", deptId);
        if (phone != null) json.put("phone", phone);
        if (email != null) json.put("email", email);
        if (department != null) json.put("department", department);
        if (position != null) json.put("position", position);
        json.put("status", status);
        json.put("faceRegistered", faceRegistered);
        json.put("fingerRegistered", fingerRegistered);
        return json;
    }
}
