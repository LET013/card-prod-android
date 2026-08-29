package com.xingyao.card.core.biz.http;

import com.xingyao.card.core.entity.http.FaultReportRequest;
import com.xingyao.card.core.entity.http.LogReportRequest;
import com.xingyao.card.core.entity.http.LogsBatchRequest;
import com.xingyao.card.core.entity.http.LogsBatchResponse;
import com.xingyao.card.core.entity.http.StatisticsReportRequest;
import com.xingyao.card.core.entity.http.UpgradeStatusRequest;
import com.xingyao.card.core.http.BaseApiService;
import com.xingyao.card.core.http.HttpClientManager;

import org.json.JSONException;

import java.io.IOException;

/**
 * 日志、统计、故障、升级状态上报 HTTP API 服务。
 *
 * <p>文档 V4.2 §4.5-4.8。
 */
public class ReportApiService extends BaseApiService {

    public ReportApiService(HttpClientManager http) {
        super(http, "/api/v1");
    }

    /**
     * 单条日志上报。POST /api/v1/log/report
     */
    public void logReport(LogReportRequest req) throws IOException {
        try {
            apiPost("/log/report", req.toJson());
        } catch (JSONException e) {
            throw new IOException("ReportApiService.logReport JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量日志上报。POST /api/v1/logs/batch
     *
     * @param req 批量日志请求
     * @return 上报结果
     */
    public LogsBatchResponse logsBatch(LogsBatchRequest req) throws IOException {
        try {
            return LogsBatchResponse.fromJson(apiPost("/logs/batch", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("ReportApiService.logsBatch JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 统计日报。POST /api/v1/statistics/report
     */
    public void statisticsReport(StatisticsReportRequest req) throws IOException {
        try {
            apiPost("/statistics/report", req.toJson());
        } catch (JSONException e) {
            throw new IOException("ReportApiService.statisticsReport JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 故障上报。POST /api/v1/fault/report
     */
    public void faultReport(FaultReportRequest req) throws IOException {
        try {
            apiPost("/fault/report", req.toJson());
        } catch (JSONException e) {
            throw new IOException("ReportApiService.faultReport JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 固件升级状态上报。POST /api/v1/upgrade/status
     */
    public void upgradeStatus(UpgradeStatusRequest req) throws IOException {
        try {
            apiPost("/upgrade/status", req.toJson());
        } catch (JSONException e) {
            throw new IOException("ReportApiService.upgradeStatus JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
