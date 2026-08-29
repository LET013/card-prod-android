package com.xingyao.card.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.xingyao.card.R;
import com.xingyao.card.BuildConfig;
import com.xingyao.card.core.FaceAiManager;
import com.xingyao.card.core.bootstrap.BootstrapEvent;
import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.entity.http.DeviceConfigEntity;
import com.xingyao.card.core.log.AppLog;
import com.xingyao.card.core.serial.DeviceSerialManager;
import com.xingyao.card.core.serial.SerialDataReceivedEvent;
import com.xingyao.card.core.serial.SerialRuntimeRegistry;
import com.xingyao.card.core.serial.SerialStatusEvent;
import com.xingyao.card.core.serial.SlotSnapshotEvent;
import com.xingyao.card.core.serial.SlotStatusBatchEvent;
import com.xingyao.card.core.serial.SlotStatusEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground-service lifecycle and dependency wiring only.
 */
public final class DeviceCoreService extends Service {
    private static final String TAG ="DSM.Listener";
    private static final String CHANNEL_ID = "device_core_service";
    private static final int NOTIFICATION_ID = 1001;

    private DeviceSerialManager deviceSerialManager;
    private FaceAiManager faceAiManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

//        NativeSettingsRepository settingsRepository = new NativeSettingsRepository(this);
//        JSONObject settings;
//        try { settings = settingsRepository.load(); }
//        catch (JSONException error) { settings = new JSONObject(); }
//
//        DeviceDataRepository dataRepository = new DeviceDataRepository(this);
//        SlotStateRepository slotRepository = new SlotStateRepository();
//        DeviceEventLogRepository eventLogRepository = new DeviceEventLogRepository(this);
//        DeviceStateStore stateStore = new DeviceStateStore(slotRepository, dataRepository,
//                eventLogRepository);
        deviceSerialManager = new DeviceSerialManager(BuildConfig.SERIAL_SIMULATOR_ENABLED);
        SerialRuntimeRegistry.install(deviceSerialManager);
        deviceSerialManager.setListener(new DeviceSerialManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) {
                String state = status.optString("state", "UNKNOWN");
                AppLog.i(TAG, "串口状态变更: " + state);
                EventBus.getDefault().post(new SerialStatusEvent(status));
            }

            @Override public void onDataReceived(JSONObject data) {
                EventBus.getDefault().post(new SerialDataReceivedEvent(data));
            }

            @Override public void onDataManualSent(JSONObject data) {
                EventBus.getDefault().post(new SerialDataReceivedEvent(data));
            }

            @Override public void onDataPollSent(JSONObject data) {
//                Log.d(TAG,"onDataPollSent:"+data);
            }

            @Override public void onSlotStatus(JSONObject slot) {
//                Log.d(TAG, "onSlotStatus: "+slot);
                EventBus.getDefault().post(new SlotStatusEvent(slot));
            }

            @Override public void onSlotStatusBatch(org.json.JSONArray slots) {
                EventBus.getDefault().post(new SlotStatusBatchEvent(slots));
            }

            @Override public void onSlotsSnapshot(org.json.JSONArray slots) {
                EventBus.getDefault().post(new SlotSnapshotEvent(slots));
            }
        });
        faceAiManager = FaceAiManager.getInstance();
        faceAiManager.init(this, status -> {
            Log.d("FAM.Listener", "onCreate: "+status);
        });

        // 注册 EventBus 监听 BootstrapEvent，等待 bootstrap 完毕后初始化串口。
        EventBus.getDefault().register(this);
        Log.d(TAG, "DeviceCoreService created — waiting for bootstrap RUNNING to init serial");

        faceAiManager.start();
    }

    /**
     * 监听 BootstrapEvent，在 bootstrap 完成（Phase.RUNNING）后初始化串口。
     * 串口参数全部由 getConfig 步骤下发，不依赖本地硬编码。
     * config 缺失必填项时不上报异常给 Vue（Vue 侧已有完整的 bootstrap.error 通道）。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBootstrapRunning(BootstrapEvent event) {
        if (event.phase != BootstrapEvent.Phase.RUNNING) return;
        Log.i(TAG, "Bootstrap RUNNING — initializing serial with server config");
        initSerialFromConfig();
    }

    private void initSerialFromConfig() {
        CredentialStore credentialStore = new CredentialStore(this.getApplicationContext());
        DeviceConfigEntity configEntity = null;
        try {
            configEntity = credentialStore.getDeviceConfigEntity();
        } catch (JSONException e) {
            AppLog.e(TAG, "设备启动后读取串口配置失败", e);
        }

        if (configEntity == null) {
            AppLog.e(TAG, "设备启动后串口配置为空，串口未初始化");
            return;
        }

        // 校验串口必填参数
        StringBuilder missing = new StringBuilder();
        if (configEntity.getSerialPort() == null || configEntity.getSerialPort().isEmpty())
            missing.append("serialPort,");
        if (configEntity.getBaudRate() <= 0)
            missing.append("baudRate,");
        if (configEntity.getTotalSlots() <= 0)
            missing.append("totalSlots,");
        if (missing.length() > 0) {
            missing.setLength(missing.length() - 1);
            AppLog.e(TAG, "串口配置缺少必填项: " + missing);
            return;
        }

        JSONObject config = new JSONObject();
        try {
            // 服务端默认值: serialPort="/dev/ttyS5", baudRate=57600, totalSlots=100,
            // serialPollEnabled=true, serialPollInterval=5000, serialResponseTimeout=3000
            config.put("serialPort", configEntity.getSerialPort());
            config.put("baudRate", configEntity.getBaudRate());
            config.put("totalSlots", configEntity.getTotalSlots());
            config.put("serialPollEnabled", configEntity.isSerialPollEnabled());
            config.put("serialPollInterval", configEntity.getSerialPollInterval());
            config.put("serialResponseTimeout", configEntity.getSerialResponseTimeout());
            config.put("groupSize", configEntity.getGroupSize());
            config.put("pollingMode", configEntity.getPollingMode());
        } catch (JSONException e) {
            AppLog.e(TAG, "构造串口配置失败", e);
            return;
        }

        deviceSerialManager.configure(config);
        deviceSerialManager.start();
        AppLog.i(TAG, "串口已启动: simulator=" + BuildConfig.SERIAL_SIMULATOR_ENABLED
                + ", slots=" + configEntity.getTotalSlots());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        if (deviceSerialManager != null) {
            SerialRuntimeRegistry.clear(deviceSerialManager);
            deviceSerialManager.stop();
        }
        if (faceAiManager != null) faceAiManager.stop();
        super.onDestroy();
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("工作卡柜设备服务")
                .setContentText("Android数据层与通信适配器正在运行")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "设备核心服务",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
