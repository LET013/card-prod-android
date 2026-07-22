package com.xingyao.card.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.xingyao.card.R;
import com.xingyao.card.core.ArcFaceManager;
import com.xingyao.card.core.ArcFaceTemplateCleaner;
import com.xingyao.card.core.BackendHttpGateway;
import com.xingyao.card.core.DeviceCommandCoordinator;
import com.xingyao.card.core.DeviceDataLayer;
import com.xingyao.card.core.DeviceDataRepository;
import com.xingyao.card.core.DeviceDataSyncManager;
import com.xingyao.card.core.DeviceEventLogRepository;
import com.xingyao.card.core.DeviceRuntimeRegistry;
import com.xingyao.card.core.DeviceStateStore;
import com.xingyao.card.core.InboundCommandRepository;
import com.xingyao.card.core.NativeSettingsRepository;
import com.xingyao.card.core.SerialConnectionManager;
import com.xingyao.card.core.SlotStateRepository;
import com.xingyao.card.core.WebSocketConnectionManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground-service lifecycle and dependency wiring only.
 *
 * Business decisions and device truth live in DeviceDataLayer/DeviceStateStore. Serial, MQTT and
 * HTTP classes are communication adapters and never address the WebView directly.
 */
public final class DeviceCoreService extends Service {
    private static final String CHANNEL_ID = "device_core_service";
    private static final int NOTIFICATION_ID = 1001;

    private DeviceDataLayer dataLayer;
    private SerialConnectionManager serialManager;
    private WebSocketConnectionManager backendManager;
    private ArcFaceManager arcFaceManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        NativeSettingsRepository settingsRepository = new NativeSettingsRepository(this);
        JSONObject settings;
        try { settings = settingsRepository.load(); }
        catch (JSONException error) { settings = new JSONObject(); }

        DeviceDataRepository dataRepository = new DeviceDataRepository(this);
        SlotStateRepository slotRepository = new SlotStateRepository();
        DeviceEventLogRepository eventLogRepository = new DeviceEventLogRepository(this);
        DeviceStateStore stateStore = new DeviceStateStore(slotRepository, dataRepository,
                eventLogRepository);
        BackendHttpGateway httpGateway = new BackendHttpGateway(settingsRepository);

        final DeviceDataLayer[] holder = new DeviceDataLayer[1];
        serialManager = new SerialConnectionManager(this, new SerialConnectionManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) {
                if (holder[0] != null) holder[0].onSerialStatus(status);
            }

            @Override public void onDataReceived(JSONObject data) {
                if (holder[0] != null) holder[0].onSerialData(data);
            }

            @Override public void onSlotStatus(JSONObject slot) {
                if (holder[0] != null) holder[0].onSlotStatus(slot);
            }
        });
        backendManager = new WebSocketConnectionManager(this, settingsRepository,
                new WebSocketConnectionManager.Listener() {
                    @Override public void onStatusChanged(JSONObject status) {
                        if (holder[0] != null) holder[0].onBackendStatus(status);
                    }

                    @Override public void onCommand(JSONObject command) {
                        if (holder[0] != null) holder[0].onBackendCommand(command);
                    }

                    @Override public void onMessage(JSONObject message) {
                        if (holder[0] != null) holder[0].onBackendMessage(message);
                    }
                });
        arcFaceManager = new ArcFaceManager(this, status -> {
            if (holder[0] != null) holder[0].onRecognitionStatus(status);
        });
        ArcFaceTemplateCleaner templateCleaner = new ArcFaceTemplateCleaner(this);
        DeviceDataSyncManager syncManager = new DeviceDataSyncManager(settingsRepository,
                dataRepository, arcFaceManager, templateCleaner, httpGateway);

        DeviceDataLayer.SerialPort serialPort = new DeviceDataLayer.SerialPort() {
            @Override public JSONObject snapshot() throws JSONException { return serialManager.snapshot(); }
            @Override public void configure(JSONObject value) { serialManager.configure(value); }
            @Override public void reconnect() { serialManager.reconnect(); }
            @Override public JSONObject setPollingEnabled(boolean enabled) throws JSONException {
                return serialManager.setPollingEnabled(enabled);
            }
            @Override public JSONObject listPorts() throws JSONException {
                return SerialConnectionManager.listAvailablePorts();
            }
            @Override public JSONObject send(String data, String encoding) throws Exception {
                return serialManager.send(data, encoding);
            }
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
                return serialManager.openDoor(slotNumber, administrator);
            }
            @Override public JSONObject querySlot(int slotNumber) throws Exception {
                return serialManager.querySlot(slotNumber);
            }
            @Override public JSONObject readVersion(int slotNumber) throws Exception {
                return serialManager.readVersion(slotNumber);
            }
            @Override public JSONObject openAllDoors(boolean administrator) throws Exception {
                return serialManager.openAllDoors(administrator);
            }
        };
        DeviceDataLayer.BackendPort backendPort = new DeviceDataLayer.BackendPort() {
            @Override public JSONObject snapshot() throws JSONException { return backendManager.snapshot(); }
            @Override public void configure(JSONObject value) { backendManager.configure(value); }
            @Override public void send(JSONObject payload) throws Exception { backendManager.send(payload); }
            @Override public boolean isAuthenticated() { return backendManager.isAuthenticated(); }
            @Override public String transportMode() { return backendManager.transportMode(); }
        };
        DeviceCommandCoordinator.AppControl appControl = delayMs ->
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    }
                }, Math.max(0L, delayMs));

        dataLayer = new DeviceDataLayer(this, settingsRepository, stateStore, dataRepository,
                syncManager, serialPort, backendPort, arcFaceManager, templateCleaner, httpGateway,
                new InboundCommandRepository(this), appControl);
        holder[0] = dataLayer;
        DeviceRuntimeRegistry.install(dataLayer);

        serialManager.start();
        backendManager.start();
        arcFaceManager.start();
        dataLayer.start(settings);
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
        DeviceDataLayer current = dataLayer;
        if (current != null) current.stop();
        if (backendManager != null) backendManager.stop();
        if (serialManager != null) serialManager.stop();
        if (arcFaceManager != null) arcFaceManager.stop();
        DeviceRuntimeRegistry.clear(current);
        dataLayer = null;
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
