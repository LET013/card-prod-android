package com.xingyao.card.core.biz.mqtt;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.mqtt.BaseMqttService;
import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * 员工/人脸/指纹数据同步 MQTT 服务。
 *
 * <p>使用 {@code sendAndWaitReply} 模式实现分页同步：
 * <ul>
 *   <li>上行：{@code syncEmployeeData} / {@code syncFaceData} / {@code syncFingerData}</li>
 *   <li>等待下行：{@code syncEmployeeDataResp} / {@code syncFaceDataResp} / {@code syncFingerDataResp}</li>
 * </ul>
 *
 * <p>文档 V4.2 §4.3.1-4.3.3。
 */
public class DataSyncMqttService extends BaseMqttService {

    private static final String TAG = "DataSyncMqttService";

    /** 默认超时（秒） */
    private static final long DEFAULT_TIMEOUT_S = 30;

    public DataSyncMqttService(XMqttClient mqttClient, String deviceCode,
                               CredentialStore credentialStore) {
        super(mqttClient, deviceCode, credentialStore);
        register();
    }

    /**
     * 同步员工资料（分页）。
     *
     * @param lastTimestamp 上次同步时间戳（首次传 0）
     * @param page          页码
     * @param pageSize      每页条数
     * @param timeoutMs     超时 (ms)
     * @return {@link MqttReplyResult}，调用方需检查 success 和数据
     */
    public MqttReplyResult syncEmployeeData(long lastTimestamp, int page, int pageSize, long timeoutMs) {
        try {
            JSONObject data = new JSONObject();
            data.put("lastSyncTime", lastTimestamp);
            data.put("page", page);
            data.put("pageSize", pageSize);
            return sendAndWaitReply(MqttCmd.SYNC_EMPLOYEE_DATA, data,
                    timeoutMs > 0 ? timeoutMs : TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_S),
                    MqttCmd.SYNC_EMPLOYEE_DATA_RESP);
        } catch (JSONException e) {
            Log.e(TAG, "syncEmployeeData JSON error", e);
            return new MqttReplyResult(false, "JSON error: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "syncEmployeeData interrupted", e);
            return new MqttReplyResult(false, "Interrupted", null);
        }
    }

    /**
     * 同步人脸特征（分页）。
     *
     * @param lastTimestamp 上次同步时间戳
     * @param page          页码
     * @param pageSize      每页条数
     * @param includeFlags  是否包含图片标记
     * @param timeoutMs     超时 (ms)
     * @return {@link MqttReplyResult}
     */
    public MqttReplyResult syncFaceData(long lastTimestamp, int page, int pageSize,
                                         boolean includeFlags, long timeoutMs) {
        try {
            JSONObject data = new JSONObject();
            data.put("lastSyncTime", lastTimestamp);
            data.put("page", page);
            data.put("pageSize", pageSize);
            data.put("includeFlags", includeFlags);
            return sendAndWaitReply(MqttCmd.SYNC_FACE_DATA, data,
                    timeoutMs > 0 ? timeoutMs : TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_S),
                    MqttCmd.SYNC_FACE_DATA_RESP);
        } catch (JSONException e) {
            Log.e(TAG, "syncFaceData JSON error", e);
            return new MqttReplyResult(false, "JSON error: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "syncFaceData interrupted", e);
            return new MqttReplyResult(false, "Interrupted", null);
        }
    }

    /**
     * 同步指纹特征（分页）。
     *
     * @param lastTimestamp 上次同步时间戳
     * @param page          页码
     * @param pageSize      每页条数
     * @param timeoutMs     超时 (ms)
     * @return {@link MqttReplyResult}
     */
    public MqttReplyResult syncFingerData(long lastTimestamp, int page, int pageSize, long timeoutMs) {
        try {
            JSONObject data = new JSONObject();
            data.put("lastSyncTime", lastTimestamp);
            data.put("page", page);
            data.put("pageSize", pageSize);
            return sendAndWaitReply(MqttCmd.SYNC_FINGER_DATA, data,
                    timeoutMs > 0 ? timeoutMs : TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_S),
                    MqttCmd.SYNC_FINGER_DATA_RESP);
        } catch (JSONException e) {
            Log.e(TAG, "syncFingerData JSON error", e);
            return new MqttReplyResult(false, "JSON error: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "syncFingerData interrupted", e);
            return new MqttReplyResult(false, "Interrupted", null);
        }
    }

    @Override
    public void handleMqttMessage(String cmd, JSONObject data, String topic) {
        // sendAndWaitReply 已在 BaseMqttService 中通过 CountDownLatch 处理
        Log.d(TAG, "sync response via BaseMqttService latch: cmd=" + cmd);
    }
}
