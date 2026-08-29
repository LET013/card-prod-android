package com.xingyao.card.core.biz.mqtt;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.log.AppLog;
import com.xingyao.card.core.mqtt.BaseMqttService;
import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 状态上报与监控 MQTT 服务。
 *
 * <p>负责上行：
 * <ul>
 *   <li>{@code statusReport} — 卡槽状态上报</li>
 *   <li>{@code hardwareFault} — 硬件故障上报</li>
 *   <li>{@code selfCheckReport} — 自检结果上报</li>
 *   <li>{@code logReport} — 日志上报</li>
 *   <li>{@code statisticsReport} — 统计数据上报</li>
 *   <li>{@code upgradeStatus} — 升级状态上报</li>
 *   <li>{@code batchOperationResult} — 批量操作结果上报</li>
 *   <li>{@code authStatusChange} — 授权状态变更上报</li>
 * </ul>
 *
 * <p>文档 V4.2 §4.2、§4.5-4.8。
 */
public class DeviceMonitorMqttService extends BaseMqttService {

    private static final String TAG = "DeviceMonitorMqttService";

    public DeviceMonitorMqttService(XMqttClient mqttClient, String deviceCode,
                                    CredentialStore credentialStore) {
        super(mqttClient, deviceCode, credentialStore);
        // 仅上行上报，不订阅下行
    }

    // ────────── 卡槽状态上报 ──────────

    /**
     * 上报卡槽状态。
     *
     * @param slots 卡槽状态列表，每项 {"slotId", "status", "cardNo"...}
     */
    public void sendStatusReport(JSONArray slots) {
        try {
            JSONObject data = new JSONObject();
            data.put("slots", slots);
            sendSignedEnvelope(MqttCmd.STATUS_REPORT, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendStatusReport JSON error", e);
        }
    }

    // ────────── 硬件故障上报 ──────────

    /**
     * 上报硬件故障。
     *
     * @param slotId    卡槽编号（-1 表示设备级）
     * @param faultCode 故障码
     * @param faultMsg  故障描述
     * @param severity  严重级别：critical / major / minor
     */
    public void sendHardwareFault(int slotId, String faultCode, String faultMsg, String severity) {
        try {
            JSONObject data = new JSONObject();
            data.put("slotId", slotId);
            data.put("faultCode", faultCode);
            if (faultMsg != null) data.put("faultMsg", faultMsg);
            data.put("severity", severity);
            data.put("timestamp", System.currentTimeMillis());
            sendSignedEnvelope(MqttCmd.HARDWARE_FAULT, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendHardwareFault JSON error", e);
        }
    }

    // ────────── 自检报告 ──────────

    /**
     * 上报自检结果。
     *
     * @param result  总体结果：pass / fail
     * @param details 检查项详情 JSONArray
     */
    public void sendSelfCheckReport(String result, JSONArray details) {
        try {
            JSONObject data = new JSONObject();
            data.put("result", result);
            data.put("details", details != null ? details : new JSONArray());
            sendSignedEnvelope(MqttCmd.SELF_CHECK_REPORT, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendSelfCheckReport JSON error", e);
        }
    }

    // ────────── 日志上报 ──────────

    /**
     * 兼容入口统一委托 AppLog，避免绕过日志上传开关。
     */
    public void sendLogReport(String level, String tag, String content) {
        AppLog.write(level, tag, content);
    }

    // ────────── 统计上报 ──────────

    /**
     * 上报统计数据。
     */
    public void sendStatisticsReport(JSONObject statsData) {
        try {
            sendSignedEnvelope(MqttCmd.STATISTICS_REPORT, statsData);
        } catch (JSONException e) {
            Log.e(TAG, "sendStatisticsReport JSON error", e);
        }
    }

    // ────────── 升级状态上报 ──────────

    /**
     * 上报固件升级状态。
     *
     * @param firmwareVersion 固件版本
     * @param status          状态：downloading / verifying / installing / success / failed
     * @param progress        进度 (0-100)
     * @param errorMsg        错误信息（可选）
     */
    public void sendUpgradeStatus(String firmwareVersion, String status, int progress, String errorMsg) {
        try {
            JSONObject data = new JSONObject();
            data.put("firmwareVersion", firmwareVersion);
            data.put("status", status);
            data.put("progress", progress);
            if (errorMsg != null) data.put("errorMsg", errorMsg);
            sendSignedEnvelope(MqttCmd.UPGRADE_STATUS, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendUpgradeStatus JSON error", e);
        }
    }

    // ────────── 批量操作结果 ──────────

    /**
     * 上报批量操作结果。
     */
    public void sendBatchOperationResult(String operationId, String result, JSONArray details) {
        try {
            JSONObject data = new JSONObject();
            data.put("operationId", operationId);
            data.put("result", result);
            if (details != null) data.put("details", details);
            sendSignedEnvelope(MqttCmd.BATCH_OPERATION_RESULT, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendBatchOperationResult JSON error", e);
        }
    }

    // ────────── 授权状态变更 ──────────

    /**
     * 上报授权状态变更。
     *
     * @param authStatus 新的授权状态
     * @param operator   操作人
     */
    public void sendAuthStatusChange(String authStatus, String operator) {
        try {
            JSONObject data = new JSONObject();
            data.put("authStatus", authStatus);
            if (operator != null) data.put("operator", operator);
            sendSignedEnvelope(MqttCmd.AUTH_STATUS_CHANGE, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendAuthStatusChange JSON error", e);
        }
    }

    // ────────── 下行处理 ──────────

    @Override
    public void handleMqttMessage(String cmd, JSONObject data, String topic) {
        // 本服务以单向主动上报为主，无下行指令处理。
        // 如需处理上报响应，可在此添加。
        Log.d(TAG, "Unhandled cmd: " + cmd);
    }
}
