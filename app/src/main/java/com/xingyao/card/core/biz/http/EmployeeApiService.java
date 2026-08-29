package com.xingyao.card.core.biz.http;

import com.xingyao.card.core.entity.http.EmployeeSyncRequest;
import com.xingyao.card.core.entity.http.EmployeeSyncResponse;
import com.xingyao.card.core.entity.http.EmployeeUpsertRequest;
import com.xingyao.card.core.entity.http.FaceRegisteredResponse;
import com.xingyao.card.core.entity.http.FaceSyncRequest;
import com.xingyao.card.core.entity.http.FaceSyncResponse;
import com.xingyao.card.core.entity.http.FaceUploadRequest;
import com.xingyao.card.core.entity.http.FingerSyncRequest;
import com.xingyao.card.core.entity.http.FingerSyncResponse;
import com.xingyao.card.core.http.BaseApiService;
import com.xingyao.card.core.http.HttpClientManager;

import org.json.JSONException;

import java.io.IOException;

/**
 * 员工资料相关 HTTP API 服务。
 *
 * <p>文档 V4.2 §4.3：员工同步、人脸/指纹特征同步、员工增改、人脸上传、查询已注册人脸。
 */
public class EmployeeApiService extends BaseApiService {

    public EmployeeApiService(HttpClientManager http) {
        super(http, "/api/v1");
    }

    /**
     * 员工资料同步（分页）。POST /api/v1/employee/sync
     *
     * @param req 分页请求
     * @return 同步响应（含员工列表）
     */
    public EmployeeSyncResponse sync(EmployeeSyncRequest req) throws IOException {
        try {
            return EmployeeSyncResponse.fromJson(apiPost("/employee/sync", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.sync JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 人脸特征同步（分页）。POST /api/v1/employee/face/sync
     *
     * @param req 分页请求
     * @return 同步响应（含人脸特征列表）
     */
    public FaceSyncResponse syncFace(FaceSyncRequest req) throws IOException {
        try {
            return FaceSyncResponse.fromJson(apiPost("/employee/face/sync", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.syncFace JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 指纹特征同步（分页）。POST /api/v1/employee/finger/sync
     *
     * @param req 分页请求
     * @return 同步响应（含指纹特征列表）
     */
    public FingerSyncResponse syncFinger(FingerSyncRequest req) throws IOException {
        try {
            return FingerSyncResponse.fromJson(apiPost("/employee/finger/sync", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.syncFinger JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 新增/更新员工（Web 端同步）。POST /api/v1/employee
     *
     * @param req 员工操作请求
     */
    public void upsert(EmployeeUpsertRequest req) throws IOException {
        try {
            apiPost("/employee", req.toJson());
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.upsert JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上报人脸特征。POST /api/v1/employee/face
     *
     * @param req 人脸特征上传请求
     */
    public void uploadFace(FaceUploadRequest req) throws IOException {
        try {
            apiPost("/employee/face", req.toJson());
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.uploadFace JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询已注册人脸的员工列表。GET /api/v1/employee/face/registered
     *
     * @return 已注册人脸的员工 ID 列表
     */
    public FaceRegisteredResponse getFaceRegistered() throws IOException {
        try {
            return FaceRegisteredResponse.fromJson(apiGet("/employee/face/registered"));
        } catch (JSONException e) {
            throw new IOException("EmployeeApiService.getFaceRegistered JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
