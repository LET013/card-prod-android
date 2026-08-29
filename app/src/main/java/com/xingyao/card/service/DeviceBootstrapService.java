package com.xingyao.card.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.xingyao.card.core.bootstrap.BootstrapActionEvent;
import com.xingyao.card.core.bootstrap.BootstrapEvent;
import com.xingyao.card.core.bootstrap.DeviceBootstrapManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 设备启动流程后台 Service。
 *
 * <p>职责：
 * <ul>
 *   <li>在后台线程执行设备启动流程（注册 → 激活 → 配置 → 登录）</li>
 *   <li>通过 EventBus {@link BootstrapEvent} 通知 Activity 进度和状态</li>
 *   <li>通过 {@link BootstrapActionEvent} 接收 Activity 的操作（输入激活码、重试等）</li>
 * </ul>
 *
 * <h3>Activity 使用方式</h3>
 * <pre>{@code
 * // 1. 启动 Service
 * Intent intent = new Intent(this, DeviceBootstrapService.class);
 * intent.putExtra(DeviceBootstrapService.EXTRA_SERVER_URL, "http://10.0.0.1:8800");
 * startService(intent);
 *
 * // 2. 注册 EventBus 接收进度
 * EventBus.getDefault().register(this);
 *
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onBootstrapEvent(BootstrapEvent event) {
 *     switch (event.phase) {
 *         case WAITING_ACTIVATION_CODE:
 *             showActivationDialog(event.registerCode);
 *             break;
 *         case RUNNING:
 *             navigateToMain();
 *             break;
 *         case ERROR:
 *             showError(event.message);
 *             break;
 *     }
 * }
 *
 * // 3. 管理员输入激活码后
 * EventBus.getDefault().post(
 *     new BootstrapActionEvent(PROVIDE_ACTIVATION_CODE, "ABC-123"));
 * }</pre>
 */
public class DeviceBootstrapService extends Service {
    private static final String TAG = "DeviceBootstrapService";

    /** Intent extra: 服务器基础 URL（如 "http://10.0.0.1:8800"） */
    public static final String EXTRA_SERVER_URL = "server_url";
    /** Intent extra: MQTT broker host */
    public static final String EXTRA_MQTT_HOST = "mqtt_host";
    /** Intent extra: MQTT TCP port（默认 1883） */
    public static final String EXTRA_MQTT_PORT = "mqtt_port";
    /** Intent extra: HTTP port（默认 8800） */
    public static final String EXTRA_HTTP_PORT = "http_port";

    private DeviceBootstrapManager bootstrapManager;
    private Thread bootstrapThread;
    private boolean isDestroyed = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        EventBus.getDefault().register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_SERVER_URL)) {
            // 调用方传入的服务器配置，写入 CredentialStore 供后续流程使用
            String serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
            String mqttHost = intent.getStringExtra(EXTRA_MQTT_HOST);
            int mqttPort = intent.getIntExtra(EXTRA_MQTT_PORT, 1883);
            int httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 8800);

            if (mqttHost != null && !mqttHost.isEmpty()) {
                storeServerConfig(serverUrl, mqttHost, mqttPort, httpPort);
            }
        }

        startBootstrap();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不绑定，仅通过 EventBus 通信
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroying");
        isDestroyed = true;
        EventBus.getDefault().unregister(this);
        if (bootstrapManager != null) {
            bootstrapManager.shutdown();
        }
        if (bootstrapThread != null && bootstrapThread.isAlive()) {
            bootstrapThread.interrupt();
        }
        super.onDestroy();
    }

    /* ==================== 内部 ==================== */

    private void startBootstrap() {
        if (bootstrapThread != null && bootstrapThread.isAlive() && bootstrapManager != null
                && (bootstrapManager.isBootstrapping() || bootstrapManager.isBootstrapComplete())) {
            // 已经在启动中或已启动
            Log.d(TAG, "Bootstrap already in progress or completed, skip");
            return;
        }

        bootstrapThread = new Thread(() -> {
            Log.i(TAG, "Bootstrap thread started");
            bootstrapManager = new DeviceBootstrapManager(this::getApplicationContext);
            bootstrapManager.bootstrap();
            Log.i(TAG, "Bootstrap thread finished");
        }, "Bootstrap-Thread");
        bootstrapThread.setPriority(Thread.NORM_PRIORITY);
        bootstrapThread.start();
    }

    private void storeServerConfig(String serverUrl, String mqttHost, int mqttPort, int httpPort) {
        try {
            com.xingyao.card.core.bootstrap.CredentialStore store =
                    new com.xingyao.card.core.bootstrap.CredentialStore(this);

            org.json.JSONObject settings = store.load();
            if (serverUrl != null && !serverUrl.isEmpty()) {
                settings.put("serverUrl", serverUrl);
            }
            if (mqttHost != null && !mqttHost.isEmpty()) {
                settings.put("mqttHost", mqttHost);
                settings.put("mqttTcpPort", mqttPort);
                // 也写入旧格式字段
                settings.put("backendHost", mqttHost);
                settings.put("backendHttpPort", httpPort);
                settings.put("backendMqttPort", mqttPort);
            }
            store.save(settings);
            Log.d(TAG, "Server config stored: " + serverUrl);
        } catch (Exception e) {
            Log.e(TAG, "Failed to store server config", e);
        }
    }

    // ── EventBus 接收 Activity 发来的动作 ──

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBootstrapAction(BootstrapActionEvent event) {
        if (bootstrapManager == null) return;

        switch (event.action) {
            case PROVIDE_ACTIVATION_CODE:
                if (event.code != null && !event.code.isEmpty()) {
                    Log.d(TAG, "Activation code provided");
                    bootstrapManager.verifyCode(event.code);
                }
                break;

            case RETRY:
                Log.d(TAG, "Retry requested, restarting bootstrap");
                if (bootstrapManager != null) {
                    bootstrapManager.shutdown();
                }
                startBootstrap();
                break;

            case SKIP_ACTIVATION:
                Log.w(TAG, "Skip activation requested (not implemented)");
                break;
        }
    }
}
