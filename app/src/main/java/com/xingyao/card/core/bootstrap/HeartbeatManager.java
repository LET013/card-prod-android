package com.xingyao.card.core.bootstrap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xingyao.card.core.mqtt.BaseMqttService;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * MQTT 心跳管理器，每 60 秒发送一次心跳。
 *
 * <p>继承 {@link BaseMqttService} 获得信封构建、签名和发送能力。
 * 协议：
 * <pre>
 * Topic: card/{deviceCode}/up (QoS 1)
 * Envelope: { "msgId", "cmd":"heartbeat", "timestamp", "raw":"{...}" }
 * </pre>
 */
public class HeartbeatManager extends BaseMqttService {
    private static final String TAG = "HeartbeatManager";
    /** 默认心跳间隔 60s。服务端 config: {@code mqttHeartbeatInterval=60000} */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 60_000L;

    private final Handler handler;
    private volatile long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    private boolean running = false;

    public HeartbeatManager(XMqttClient mqttClient, String deviceCode,
                            CredentialStore credentialStore) {
        super(mqttClient, deviceCode, credentialStore);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置心跳间隔（应在 {@link #start()} 之前调用）。
     * 服务端 config 默认值: {@code mqttHeartbeatInterval=60000}（ms）
     */
    public void setHeartbeatInterval(long intervalMs) {
        if (intervalMs >= 1000) {
            this.heartbeatIntervalMs = intervalMs;
        } else {
            Log.w(TAG, "Heartbeat interval too small: " + intervalMs + "ms, keeping default: " + DEFAULT_HEARTBEAT_INTERVAL_MS + "ms");
        }
    }

    /** 启动心跳（发送首条，然后定时），幂等调用 */
    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            sendHeartbeat(new JSONObject()); // 立即发送第一条（继承自 BaseMqttService）
        } catch (JSONException e) {
            Log.e(TAG, "Initial heartbeat failed", e);
        }
        scheduleNext();
    }

    /** 停止心跳 */
    public synchronized void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Heartbeat stopped");
    }

    private void scheduleNext() {
        if (!running) return;
        handler.postDelayed(() -> {
            try {
                sendHeartbeat(new JSONObject()); // 继承自 BaseMqttService
            } catch (JSONException e) {
                Log.e(TAG, "Heartbeat send error", e);
            }
            scheduleNext();
        }, heartbeatIntervalMs);
    }
}
