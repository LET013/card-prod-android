package com.xingyao.card.core.bootstrap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xingyao.card.core.entity.http.ActivateRequest;
import com.xingyao.card.core.entity.http.ActivateResponse;
import com.xingyao.card.core.entity.http.ActivationResult;
import com.xingyao.card.core.entity.http.DeviceConfigEntity;
import com.xingyao.card.core.entity.http.DeviceConfigResponse;
import com.xingyao.card.core.entity.http.LoginRequest;
import com.xingyao.card.core.entity.http.RegisterRequest;
import com.xingyao.card.core.entity.http.RegisterResponse;
import com.xingyao.card.core.entity.http.VerifyCodeRequest;
import com.xingyao.card.core.entity.http.VerifyCodeResponse;
import com.xingyao.card.core.entity.http.VersionCheckResponse;
import com.xingyao.card.core.entity.mqtt.MqttLoginResp;
import com.xingyao.card.core.biz.http.DeviceApiService;
import com.xingyao.card.core.http.DeviceTokenProvider;
import com.xingyao.card.core.http.HttpClientManager;
import com.xingyao.card.core.log.AppLog;
import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.MqttConnectionEvent;
import com.xingyao.card.core.mqtt.MqttEnvelope;
import com.xingyao.card.core.mqtt.MqttMessageEvent;
import com.xingyao.card.core.mqtt.MqttTopics;
import com.xingyao.card.core.mqtt.XMqttClient;
import com.xingyao.card.core.utils.DeviceInfoUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备启动流程编排器。
 *
 * <p>启动流程（文档 2.1 启动流程 + 3.3 MQTT 交互协议）：
 * <ol>
 *   <li>Phase 0: 检查 APP 版本 (POST /app-version/check) — 可选</li>
 *   <li>Phase 0: 设备注册 (POST /device/register) → deviceToken + deviceCode</li>
 *   <li>Phase A: 设备激活 (POST /device/activate) → 直接激活 or 返回注册码</li>
 *   <li>Phase B: 注册码验证 (POST /device/verify) → MQTT 凭证（需要管理员输入）</li>
 *   <li>Phase C: 获取设备配置 (GET /device/config) → 通信模式 + 配置</li>
 *   <li>Phase D: 连接 MQTT → 登录 → 心跳</li>
 * </ol>
 *
 * <p>流程中通过 {@link BootstrapEvent} 通知 Activity 进度变化。
 * Phase B 需要 Activity 输入激活码，通过 {@link BootstrapActionEvent} 回传。
 */
public class DeviceBootstrapManager {
    private static final String TAG = "DeviceBootstrapManager";
    private static final long LOGIN_TIMEOUT_MS = 15_000L;
    private static final long VERSION_CHECK_TIMEOUT_MS = 5_000L;

    private final ContextProvider contextProvider;
    private volatile HttpClientManager httpClient;
    private volatile XMqttClient mqttClient;
    private volatile CredentialStore credentialStore;
    private volatile HeartbeatManager heartbeatManager;
    private volatile HttpHeartbeatManager httpHeartbeatManager;
    private volatile DeviceConfigEntity activeConfig;
    private volatile boolean mqttAuthenticated = false;
    private volatile String pendingLoginMsgId = null;
    private volatile int mqttReloginAttempts = 0;
    private final AtomicInteger mqttLoginGeneration = new AtomicInteger(0);
    private final ScheduledExecutorService mqttSessionExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "MQTT-Session");
                thread.setDaemon(true);
                return thread;
            });
    private ScheduledFuture<?> mqttLoginRetryFuture;
    private String deviceCode;
    private final Handler mainHandler;

    // Phase B 同步控制（Activity 输入激活码后唤醒 bootstrap 线程）
    private CountDownLatch activationCodeLatch = new CountDownLatch(1);
    private AtomicReference<String> activationCodeHolder = new AtomicReference<>(null);

    // 状态
    private volatile boolean isBootstrapping = false;
    private volatile boolean isBootstrapComplete = false;
    private volatile boolean activationAborted = false;

    /** 提供 Application Context，避免在构造时内存泄漏 */
    public interface ContextProvider {
        android.content.Context getContext();
    }

    public DeviceBootstrapManager(ContextProvider contextProvider) {
        this.contextProvider = contextProvider;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /* ==================== 公开入口 ==================== */

    /**
     * 启动完整引导流程（阻塞，请在后台线程调用）。
     */
    public synchronized void bootstrap() {
        if (isBootstrapping || isBootstrapComplete) {
            Log.w(TAG, "Already " + (isBootstrapComplete ? "completed" : "bootstrapping") + ", skip");
            if (isBootstrapComplete) {
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.RUNNING, "设备已在运行中", deviceCode));
            }
            return;
        }
        isBootstrapping = true;
        isBootstrapComplete = false;
        stopHeartbeatManagers();
        cancelMqttRelogin();
        mqttAuthenticated = false;
        mqttLoginGeneration.incrementAndGet();
        activeConfig = null;
        if (mqttClient != null) mqttClient.disconnect();
        // 重置 Phase B 等待锁和登录标志
        activationCodeLatch = new CountDownLatch(1);
        activationCodeHolder = new AtomicReference<>(null);
        loginRespReceived = false;
        loginFailedMsg = null;
        EventBus.getDefault().register(this);

        try {
            this.credentialStore = new CredentialStore(contextProvider.getContext());

            postEvent(new BootstrapEvent(BootstrapEvent.Phase.VERSION_CHECK, "检查APP版本..."));

            // Step 0a: 版本检查（可选，失败不阻塞）
            VersionCheckResponse versionCheck = null;
            try {
                versionCheck = getAndCreateApi().checkVersion(
                        DeviceInfoUtil.versionCode(),
                        credentialStore.getChannelId(),
                        credentialStore.getDeviceCode());
            } catch (Exception e) {
                Log.w(TAG, "Version check failed (non-blocking): " + e.getMessage());
            }
            if (versionCheck != null) {
                int currentVersionCode = DeviceInfoUtil.versionCode();
                if (versionCheck.forceUpdate && versionCheck.versionCode > currentVersionCode) {
                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.FORCE_UPDATE,
                            "检测到新版本，请升级", "",
                            "", 0L, versionCheck.releaseNotes, versionCheck.toJson()));
                    return;
                }
            }

            // Step 0b: 设备注册
            String token = credentialStore.getDeviceToken();
            String storedDeviceCode = credentialStore.getDeviceCode();
            if (!hasCompleteRegistration(token, storedDeviceCode)) {
                if (!token.trim().isEmpty() || !storedDeviceCode.trim().isEmpty()) {
                    Log.w(TAG, "Incomplete registration credentials detected; registering again to recover deviceCode");
                }
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.REGISTERING, "注册设备..."));
                try {
                    register();
                } catch (DeviceApiService.ForceUpdateRequiredException updateError) {
                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.FORCE_UPDATE,
                            updateError.getMessage(), "", "", 0L,
                            updateError.versionInfo.releaseNotes,
                            updateError.versionInfo.toJson()));
                    return;
                }
            } else {
                this.deviceCode = storedDeviceCode;
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.REGISTERED,
                        "设备已注册", deviceCode));
            }

            // Step A: 激活
            if (!credentialStore.isActivated() || !credentialStore.hasMqttCredentials()) {
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.ACTIVATING, "激活设备...", deviceCode));

                try {
                    activate();
                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.ACTIVATED,
                            "设备已激活", deviceCode));
                } catch (ActivationCodeRequiredException e) {
                    // Path B: 需要注册码 → 等 Activity 输入
                    String registerCode = e.getRegisterCode();
                    long expireTime = e.getExpireTime();
                    credentialStore.putAndSave("registerCode", registerCode);
                    credentialStore.putAndSave("registerCodeExpireTime", expireTime);

                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.WAITING_ACTIVATION_CODE,
                            "等待管理员输入激活码", deviceCode,
                            registerCode, expireTime, e.getInitialAdminPassword()));

                    // 阻塞等待 Activity 通过 verifyCode() 提交激活码或超时
                    String activeKey = waitForActivationCode();
                    if (activeKey != null) {
                        postEvent(new BootstrapEvent(BootstrapEvent.Phase.VERIFYING_CODE,
                                "验证激活码...", deviceCode));
                        verifyCodeSync(registerCode, activeKey);
                    } else {
                        if (!activationAborted) {
                            postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                                    "激活码输入超时", deviceCode));
                        }
                        return;
                    }
                }
            } else {
                this.deviceCode = credentialStore.getDeviceCode();
            }

            // Step C–E: 配置 → 登录 → 心跳
            continueAfterActivation();

        } catch (Exception e) {
            AppLog.e(TAG, "设备启动流程失败", e);
            cleanupFailedBootstrapTransport();
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                    e.getMessage() != null ? e.getMessage() : "启动失败", deviceCode));
        } finally {
            isBootstrapping = false;
            // 未完成且非 Phase B 等待激活码状态时，注销 EventBus 以便 retry 重新注册
            if (!isBootstrapComplete) {
                try { EventBus.getDefault().unregister(this); } catch (Exception ignored) {}
            }
        }
    }

    /** Activity 提交激活码（Phase B 中调用，可在主线程） */
    public void verifyCode(String activeKey) {
        activationCodeHolder.set(activeKey);
        activationCodeLatch.countDown();
    }

    /** 手动重试启动（通常 Activity 在 BootstrapEvent(ERROR, RETRY) 时调用）。
     *  若上次失败在激活码阶段，直接回到 Phase B 让用户重新输入校验码，不做前置版本检查/注册。 */
    public void retry() {
        if (isBootstrapping) return;
        new Thread(() -> {
            try {
                CredentialStore store = new CredentialStore(contextProvider.getContext());
                String registerCode = store.getString("registerCode");
                if (registerCode != null && !registerCode.isEmpty() && !store.isActivated()) {
                    retryActivationInternal(store, registerCode);
                } else {
                    bootstrap();
                }
            } catch (Exception e) {
                bootstrap();
            }
        }, "Bootstrap-Thread").start();
    }

    /** Phase B 重试：直接回到等待激活码状态，不重跑版本检查/设备注册 */
    private void retryActivationInternal(CredentialStore store, String registerCode) {
        long expireTime;
        try {
            expireTime = Long.parseLong(store.getString("registerCodeExpireTime"));
        } catch (Exception e) {
            expireTime = 0;
        }

        isBootstrapping = true;
        isBootstrapComplete = false;
        activationCodeLatch = new CountDownLatch(1);
        activationCodeHolder = new AtomicReference<>(null);
        loginRespReceived = false;
        loginFailedMsg = null;
        this.credentialStore = store;
        EventBus.getDefault().register(this);

        try {
            this.deviceCode = store.getDeviceCode();
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.WAITING_ACTIVATION_CODE,
                    "等待输入激活码", deviceCode, registerCode, expireTime));

            String activeKey = waitForActivationCode();
            if (activeKey == null) {
                if (!activationAborted) {
                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                            "激活码输入超时", deviceCode));
                }
                return;
            }

            postEvent(new BootstrapEvent(BootstrapEvent.Phase.VERIFYING_CODE,
                    "验证激活码...", deviceCode));
            verifyCodeSync(registerCode, activeKey);

            continueAfterActivation();
        } catch (Exception e) {
            Log.e(TAG, "Bootstrap retry from activation failed", e);
            cleanupFailedBootstrapTransport();
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                    e.getMessage() != null ? e.getMessage() : "启动失败", deviceCode));
        } finally {
            isBootstrapping = false;
            if (!isBootstrapComplete) {
                try { EventBus.getDefault().unregister(this); } catch (Exception ignored) {}
            }
        }
    }

    /** 刷新注册码和有效期。仅当处于激活码等待阶段（Phase B）时有效。
     *  重新调用 activate() 获取新的 registerCode + expireTime，唤醒旧线程后接管等待。 */
    public void refreshActivationCode() {
        // 中断当前等待线程
        activationAborted = true;
        if (activationCodeLatch != null) {
            activationCodeLatch.countDown();
        }
        new Thread(() -> {
            try {
                // 等待旧线程完成清理（反注册 EventBus、isBootstrapping=false）
                int waitCycles = 0;
                while (isBootstrapping && waitCycles < 100) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    waitCycles++;
                }
                if (waitCycles >= 100) {
                    Log.w(TAG, "Old bootstrap thread did not finish cleanup, proceeding anyway");
                }

                CredentialStore store = new CredentialStore(contextProvider.getContext());
                this.credentialStore = store;
                this.deviceCode = store.getDeviceCode();
                isBootstrapping = true;
                isBootstrapComplete = false;
                activationAborted = false;
                activationCodeLatch = new CountDownLatch(1);
                activationCodeHolder = new AtomicReference<>(null);
                loginRespReceived = false;
                loginFailedMsg = null;
                EventBus.getDefault().register(this);

                // Step B: 重新调用 activate()
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.ACTIVATING,
                        "正在刷新注册码...", deviceCode));
                try {
                    activate();
                    // Path A: 直接激活（通常不会发生，因为首次已走 Path B）
                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.ACTIVATED,
                            "设备已激活", deviceCode));
                    continueAfterActivation();
                } catch (ActivationCodeRequiredException e) {
                    String registerCode = e.getRegisterCode();
                    long expireTime = e.getExpireTime();
                    credentialStore.putAndSave("registerCode", registerCode);
                    credentialStore.putAndSave("registerCodeExpireTime", expireTime);

                    postEvent(new BootstrapEvent(BootstrapEvent.Phase.WAITING_ACTIVATION_CODE,
                            "等待输入激活码（已刷新）", deviceCode, registerCode, expireTime,
                            e.getInitialAdminPassword()));

                    String activeKey = waitForActivationCode();
                    if (activeKey != null) {
                        postEvent(new BootstrapEvent(BootstrapEvent.Phase.VERIFYING_CODE,
                                "验证激活码...", deviceCode));
                        verifyCodeSync(registerCode, activeKey);
                    } else {
                        if (!activationAborted) {
                            postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                                    "激活码输入超时", deviceCode));
                        }
                        return;
                    }
                    continueAfterActivation();
                }
            } catch (Exception e) {
                Log.e(TAG, "Refresh activation code failed", e);
                cleanupFailedBootstrapTransport();
                postEvent(new BootstrapEvent(BootstrapEvent.Phase.ERROR,
                        e.getMessage() != null ? e.getMessage() : "刷新失败", deviceCode));
            } finally {
                isBootstrapping = false;
                if (!isBootstrapComplete) {
                    try { EventBus.getDefault().unregister(this); } catch (Exception ignored) {}
                }
            }
        }, "Bootstrap-Refresh-Thread").start();
    }

    public boolean isBootstrapComplete() { return isBootstrapComplete; }
    public boolean isBootstrapping() { return isBootstrapping; }
    public boolean isMqttAuthenticated() { return mqttAuthenticated; }
    public int getMqttAuthenticationGeneration() { return mqttLoginGeneration.get(); }
    public String getDeviceCode() { return deviceCode; }

    public HttpClientManager getHttpClient() { return httpClient; }
    public XMqttClient getMqttClient() { return mqttClient; }
    public CredentialStore getCredentialStore() { return credentialStore; }

    public void shutdown() {
        try { EventBus.getDefault().unregister(this); } catch (Exception ignored) {}
        stopHeartbeatManagers();
        cancelMqttRelogin();
        mqttSessionExecutor.shutdownNow();
        if (mqttClient != null) mqttClient.disconnect();
    }

    /** Step C–E：获取设备配置 → 登录 → 心跳，从 bootstrap() 和 retryActivationInternal() 复用 */
    private void continueAfterActivation() throws Exception {
        // Step C: 获取设备配置
        postEvent(new BootstrapEvent(BootstrapEvent.Phase.GETTING_CONFIG, "获取设备配置...", deviceCode));
        DeviceConfigResponse configResp = getAndCreateApi().getConfig();
        DeviceConfigEntity configEntity;
        try {
            configEntity = DeviceConfigEntity.fromJson(new JSONObject(configResp.rawJson));
            configEntity.validate();
            Log.i(TAG, "Device config validated: " + configEntity.getTotalSlots()
                    + " slots, mode=" + configEntity.getCommunicationMode()
                    + ", mqtt=" + configEntity.getMqttHost() + ":" + configEntity.getMqttPort());
        } catch (JSONException e) {
            throw new IOException("设备配置 JSON 解析失败: " + e.getMessage());
        } catch (DeviceConfigEntity.BootstrapConfigException e) {
            throw new IOException("设备配置校验失败: " + e.getMessage());
        }
        // 保存 config 到 CredentialStore
        credentialStore.putAndSave("communicationMode", configEntity.getCommunicationMode());
        credentialStore.putAndSave("deviceConfig_v2", configEntity.toJson().toString());
        activeConfig = configEntity;

        if (configEntity.getMqttHost() != null && !configEntity.getMqttHost().isEmpty()) {
            credentialStore.putAndSave("mqttHost", configEntity.getMqttHost());
            credentialStore.putAndSave("mqttTcpPort", configEntity.getMqttPort());
            Log.i(TAG, "MQTT config from server: " + configEntity.getMqttHost()
                    + ":" + configEntity.getMqttPort());
        }
        if (usesMqttTransport(configEntity.getCommunicationMode())
                && (configEntity.getMqttHost() == null || configEntity.getMqttHost().isEmpty())) {
            throw new IOException("MQTT host 未配置，请联系管理员检查 config 下发");
        }

        // Step D: 登录
        if (!usesMqttTransport(configEntity.getCommunicationMode())) {
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.LOGGING_IN, "HTTP 登录...", deviceCode));
            loginHttp(configEntity);
        } else {
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.CONNECTING_MQTT, "连接MQTT...", deviceCode));
            connectAndLoginMqtt(configEntity);
        }
        postEvent(new BootstrapEvent(BootstrapEvent.Phase.LOGGED_IN, "设备登录成功", deviceCode));

        startHeartbeat(configEntity);
        isBootstrapComplete = true;
        postEvent(new BootstrapEvent(BootstrapEvent.Phase.RUNNING, "设备运行中", deviceCode));
    }

    /* ==================== 内部：HTTP 调用 ==================== */

    /** 确保 httpClient 已创建（带 deviceToken），懒初始化 */
    private HttpClientManager getAndCreateHttp() throws Exception {
        if (httpClient != null) return httpClient;

        String token = credentialStore.getDeviceToken();
        httpClient = new HttpClientManager.Builder()
                .baseUrl(getServerBaseUrl())
                .tokenProvider(token.isEmpty() ? null : new DeviceTokenProvider(credentialStore))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        return httpClient;
    }

    /** 懒创建类型化的 {@link DeviceApiService}，供本类和外部模块复用 */
    private DeviceApiService getAndCreateApi() throws Exception {
        return new DeviceApiService(getAndCreateHttp());
    }

    private void register() throws Exception {
        String machineId = DeviceInfoUtil.machineId(contextProvider.getContext(), credentialStore);
        credentialStore.putAndSave("machineId", machineId);

        HttpClientManager http = new HttpClientManager.Builder()
                .baseUrl(getServerBaseUrl())
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

        RegisterRequest req = new RegisterRequest.Builder()
                .fromDevice(machineId)
                .channelId(credentialStore.getChannelId())
                .build();

        RegisterResponse resp = new DeviceApiService(http).register(req);

        credentialStore.saveRegistration(resp.deviceToken, resp.deviceCode, resp.isNew);
        this.deviceCode = resp.deviceCode;

        // 重新创建带 token 的 httpClient
        httpClient = new HttpClientManager.Builder()
                .baseUrl(getServerBaseUrl())
                .tokenProvider(new DeviceTokenProvider(credentialStore))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        postEvent(new BootstrapEvent(BootstrapEvent.Phase.REGISTERED,
                "设备注册成功", deviceCode));
    }

    static boolean hasCompleteRegistration(String deviceToken, String deviceCode) {
        return deviceToken != null && !deviceToken.trim().isEmpty()
                && deviceCode != null && !deviceCode.trim().isEmpty();
    }

    private void activate() throws Exception {
        String machineId = credentialStore.getMachineId();
        String deviceCode = credentialStore.getDeviceCode();

        Log.i(TAG, "Calling activate API: machineId=" + machineId + " deviceId=" + deviceCode);
        ActivateRequest req = new ActivateRequest.Builder()
                .fromDevice(machineId)
                .deviceId(deviceCode)
                .build();

        ActivateResponse resp = getAndCreateApi().activate(req);
        if (resp.isDirectActivated()) {
            // Path A: 直接激活
            Log.i(TAG, "Activate: direct activated");
            saveActivationResult(resp.activationResult);
        } else {
            // Path B: 需要注册码
            Log.i(TAG, "Activate: requires activation code, registerCode=" + resp.registerCode
                    + " expireTime=" + resp.expireTime);
            throw new ActivationCodeRequiredException(resp.registerCode, resp.expireTime,
                    resp.initialAdminPassword);
        }
    }

    private void verifyCodeSync(String registerCode, String activeKey) throws Exception {
        VerifyCodeRequest req = new VerifyCodeRequest(registerCode, activeKey);

        VerifyCodeResponse resp = getAndCreateApi().verifyCode(req);
        if (resp.isSuccess()) {
            saveActivationResult(resp.activationResult);
            credentialStore.putAndSave("registerCode", "");
            credentialStore.putAndSave("registerCodeExpireTime", 0L);
        } else {
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.CODE_VERIFY_FAILED,
                    resp.message, deviceCode));
            throw new IOException(resp.message);
        }
    }

    private void saveActivationResult(ActivationResult result) throws Exception {
        result.validate();

        if (this.deviceCode == null || this.deviceCode.isEmpty()) {
            this.deviceCode = !result.deviceCode.isEmpty()
                    ? result.deviceCode : credentialStore.getDeviceCode();
        }

        credentialStore.putAndSave("mqttPassword", result.mqttPassword);
        credentialStore.putAndSave("signingKey", result.signingKey);
        credentialStore.putAndSave("clientId", result.clientId);
        credentialStore.putAndSave("deviceName", result.deviceName);
        credentialStore.putAndSave("expireTime", result.expireTime);
        credentialStore.putAndSave("activationStatus", "ACTIVATED");
    }

    /* ==================== 内部：MQTT 连接与登录 ==================== */

    private void connectAndLoginMqtt(DeviceConfigEntity config) throws Exception {
        String clientId = credentialStore.getClientId();
        String password = credentialStore.getMqttPassword();
        String brokerUrl = getMqttBrokerUrl();

        // 创建 MQTT 客户端，注入 config 中的重连参数（默认: init=1000ms, max=60000ms）
        mqttClient = new XMqttClient(brokerUrl, clientId, deviceCode, password);
        mqttClient.setReconnectDelays(config.getMqttReconnectInitialInterval(),
                config.getMqttReconnectMaxInterval());
        mqttClient.addSubscribeTopic(MqttTopics.down(deviceCode), MqttTopics.QOS_DOWN);
        mqttClient.addSubscribeTopic(MqttTopics.downResponse(deviceCode), MqttTopics.QOS_DOWN_RESPONSE);
        mqttClient.setPublishTopic(MqttTopics.up(deviceCode), MqttTopics.QOS_UP);
        mqttClient.connect();

        // 等待连接完成
        int waited = 0;
        while (!mqttClient.isConnected() && waited < 10_000) {
            Thread.sleep(200);
            waited += 200;
            if (mqttClient.isConnected()) break;
        }
        if (!mqttClient.isConnected()) {
            throw new IOException("MQTT 连接超时");
        }

        postEvent(new BootstrapEvent(BootstrapEvent.Phase.MQTT_CONNECTED,
                "MQTT 已连接", deviceCode));
        postEvent(new BootstrapEvent(BootstrapEvent.Phase.LOGGING_IN, "设备登录中...", deviceCode));

        // 发送登录报文
        loginRespReceived = false;
        loginFailedMsg = null;
        mqttAuthenticated = false;
        sendMqttLogin();

        // 等待 loginResp（最多 15 秒）
        waited = 0;
        while (!loginRespReceived && waited < LOGIN_TIMEOUT_MS) {
            Thread.sleep(200);
            waited += 200;
        }
        if (!loginRespReceived) {
            throw new IOException("设备登录超时（未收到 loginResp）");
        }
        if (loginFailedMsg != null) {
            throw new IOException("设备登录被拒绝: " + loginFailedMsg);
        }

        // 注入 V4.2 签名凭证，后续所有 sendMessage 自动携带 deviceCode + HMAC-SHA256 签名
        String signingKey = credentialStore.getSigningKey();
        if (signingKey != null && !signingKey.isEmpty()) {
            mqttClient.setCredentials(deviceCode, signingKey);
        }
    }

    private void loginHttp(DeviceConfigEntity config) throws Exception {
        MqttLoginResp response = getAndCreateApi().loginHttp(buildLoginRequest());
        if (!response.isSuccess()) {
            throw new IOException(!response.message.isEmpty() ? response.message : "HTTP 登录失败");
        }
    }

    private void sendMqttLogin() throws Exception {
        MqttEnvelope envelope = new MqttEnvelope.Builder(MqttCmd.LOGIN, deviceCode)
                .msgId("lg_" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .signingKey(credentialStore.getSigningKey())
                .data(buildLoginRequest().toJson())
                .build();

        pendingLoginMsgId = envelope.msgId;
        String topic = MqttTopics.up(deviceCode);
        mqttClient.publish(topic, envelope.toBytes(), MqttTopics.QOS_UP, false);

        Log.d(TAG, "Login message sent to " + topic + ": msgId=" + envelope.msgId);
    }

    // ── EventBus 接收 loginResp ──

    private volatile boolean loginRespReceived = false;
    private volatile String loginFailedMsg = null;

    @Subscribe(threadMode = ThreadMode.MAIN, priority = 100)
    public void onMqttMessage(MqttMessageEvent event) {
        if (requiresMqttRelogin(event.cmd, event.data)) {
            handleMqttSessionRejected(event.data);
            return;
        }
        if (!MqttCmd.LOGIN_RESP.equals(event.cmd)) return;
        if (!isExpectedLoginResponse(pendingLoginMsgId, event.topic, deviceCode)) {
            Log.w(TAG, "Ignoring loginResp outside the pending /down/response login flow");
            return;
        }
        pendingLoginMsgId = null;
        mqttLoginGeneration.incrementAndGet();
        loginRespReceived = true;
        if (event.data == null) {
            mqttAuthenticated = false;
            loginFailedMsg = "登录响应缺少 data";
        } else {
            MqttLoginResp loginResp = MqttLoginResp.fromJson(event.data);
            mqttAuthenticated = loginResp.isSuccess();
            loginFailedMsg = mqttAuthenticated
                    ? null
                    : (!loginResp.message.isEmpty() ? loginResp.message : "登录失败");
        }
        if (mqttAuthenticated) {
            AppLog.i(TAG, "MQTT 设备登录成功");
        } else {
            AppLog.w(TAG, "MQTT 设备登录失败: " + loginFailedMsg);
        }

        if (!isBootstrapComplete) return;
        if (mqttAuthenticated && activeConfig != null) {
            mqttReloginAttempts = 0;
            cancelMqttRelogin();
            startMqttHeartbeat(activeConfig);
            postEvent(new BootstrapEvent(BootstrapEvent.Phase.LOGGED_IN,
                    "设备重新登录成功", deviceCode));
        } else {
            stopMqttHeartbeat();
            scheduleMqttRelogin(nextConfiguredReloginDelay());
        }
    }

    private LoginRequest buildLoginRequest() {
        return new LoginRequest(DeviceInfoUtil.versionName(), resolveLocalIpv4());
    }

    /** 登录 IP 取当前可用的非回环 IPv4，无法获取时按可选字段发送空串。 */
    private String resolveLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to resolve login IP: " + error.getMessage());
        }
        return "";
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMqttConnection(MqttConnectionEvent event) {
        if (!event.connected) {
            pendingLoginMsgId = null;
            mqttAuthenticated = false;
            mqttLoginGeneration.incrementAndGet();
            stopMqttHeartbeat();
            cancelMqttRelogin();
            return;
        }
        if (isBootstrapComplete && activeConfig != null
                && usesMqttTransport(activeConfig.getCommunicationMode())) {
            mqttReloginAttempts = 0;
            scheduleMqttRelogin(0L);
        }
    }

    /* ==================== 内部：Phase B 等待激活码 ==================== */

    private String waitForActivationCode() {
        try {
            boolean received = activationCodeLatch.await(5, TimeUnit.MINUTES);
            return received ? activationCodeHolder.get() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /* ==================== 内部：心跳 ==================== */

    private void startHeartbeat(DeviceConfigEntity config) throws Exception {
        if (usesMqttTransport(config.getCommunicationMode())) {
            if (!mqttAuthenticated) {
                throw new IOException("MQTT 登录尚未成功，不能启动心跳");
            }
            if (httpHeartbeatManager != null) httpHeartbeatManager.stop();
            startMqttHeartbeat(config);
            return;
        }
        stopMqttHeartbeat();
        if (httpHeartbeatManager != null) httpHeartbeatManager.stop();
        httpHeartbeatManager = new HttpHeartbeatManager(getAndCreateApi());
        httpHeartbeatManager.setHeartbeatInterval(config.getMqttHeartbeatInterval());
        httpHeartbeatManager.start();
    }

    private void startMqttHeartbeat(DeviceConfigEntity config) {
        stopMqttHeartbeat();
        heartbeatManager = new HeartbeatManager(mqttClient, deviceCode, credentialStore);
        heartbeatManager.setHeartbeatInterval(config.getMqttHeartbeatInterval());
        heartbeatManager.start();
    }

    private void stopMqttHeartbeat() {
        if (heartbeatManager != null) {
            heartbeatManager.stop();
            heartbeatManager = null;
        }
    }

    private void stopHeartbeatManagers() {
        stopMqttHeartbeat();
        if (httpHeartbeatManager != null) {
            httpHeartbeatManager.stop();
            httpHeartbeatManager = null;
        }
    }

    private void cleanupFailedBootstrapTransport() {
        stopHeartbeatManagers();
        cancelMqttRelogin();
        mqttLoginGeneration.incrementAndGet();
        pendingLoginMsgId = null;
        mqttAuthenticated = false;
        activeConfig = null;
        if (mqttClient != null) {
            mqttClient.disconnect();
            mqttClient = null;
        }
    }

    private synchronized void scheduleMqttRelogin(long delayMs) {
        if (mqttSessionExecutor.isShutdown()) return;
        cancelMqttRelogin();
        mqttLoginRetryFuture = mqttSessionExecutor.schedule(
                this::runMqttReloginAttempt,
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS);
    }

    private void runMqttReloginAttempt() {
        if (!isBootstrapComplete || mqttClient == null || !mqttClient.isConnected()
                || activeConfig == null || !usesMqttTransport(activeConfig.getCommunicationMode())) {
            return;
        }
        loginRespReceived = false;
        loginFailedMsg = null;
        mqttAuthenticated = false;
        int loginGeneration = mqttLoginGeneration.incrementAndGet();
        mqttReloginAttempts++;
        try {
            sendMqttLogin();
        } catch (Exception error) {
            Log.w(TAG, "MQTT re-login send failed: " + error.getMessage());
            scheduleMqttRelogin(nextConfiguredReloginDelay());
            return;
        }
        synchronized (this) {
            if (mqttSessionExecutor.isShutdown()
                    || mqttAuthenticated
                    || mqttLoginGeneration.get() != loginGeneration) return;
            mqttLoginRetryFuture = mqttSessionExecutor.schedule(() -> {
                if (!mqttAuthenticated
                        && mqttLoginGeneration.get() == loginGeneration
                        && mqttClient != null
                        && mqttClient.isConnected()) {
                    Log.w(TAG, "MQTT re-login timed out; scheduling retry");
                    scheduleMqttRelogin(nextConfiguredReloginDelay());
                }
            }, LOGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private long nextConfiguredReloginDelay() {
        DeviceConfigEntity config = activeConfig;
        if (config == null) return 1000L;
        return nextMqttReloginDelay(
                config.getMqttReconnectInitialInterval(),
                config.getMqttReconnectMaxInterval(),
                Math.max(0, mqttReloginAttempts - 1));
    }

    private synchronized void cancelMqttRelogin() {
        if (mqttLoginRetryFuture != null) {
            mqttLoginRetryFuture.cancel(true);
            mqttLoginRetryFuture = null;
        }
    }

    /** 后台明确判定设备未登录时立即失效当前会话并重新登录。 */
    private void handleMqttSessionRejected(JSONObject data) {
        synchronized (this) {
            if (!isBootstrapComplete || !mqttAuthenticated || activeConfig == null
                    || !usesMqttTransport(activeConfig.getCommunicationMode())) return;
            pendingLoginMsgId = null;
            loginRespReceived = false;
            loginFailedMsg = data.optString("msg", data.optString("message", "设备登录已失效"));
            mqttAuthenticated = false;
            mqttLoginGeneration.incrementAndGet();
            mqttReloginAttempts = 0;
        }
        stopMqttHeartbeat();
        AppLog.w(TAG, "MQTT 设备登录态已失效，正在重新登录");
        postEvent(new BootstrapEvent(BootstrapEvent.Phase.MQTT_SESSION_LOST,
                "设备连接已失效，正在重新登录", deviceCode));
        scheduleMqttRelogin(0L);
    }

    static boolean usesMqttTransport(String communicationMode) {
        return DeviceConfigEntity.usesMqttTransport(communicationMode);
    }

    static long nextMqttReloginDelay(long initialMs, long maxMs, int attempt) {
        long safeInitial = Math.max(100L, initialMs);
        long safeMax = Math.max(safeInitial, maxMs);
        long multiplier = 1L << Math.min(Math.max(0, attempt), 10);
        return Math.min(safeInitial * multiplier, safeMax);
    }

    static boolean isExpectedLoginResponse(String pendingMsgId, String responseTopic, String deviceCode) {
        return pendingMsgId != null && !pendingMsgId.isEmpty()
                && deviceCode != null && !deviceCode.isEmpty()
                && MqttTopics.downResponse(deviceCode).equals(responseTopic);
    }

    static boolean requiresMqttRelogin(String cmd, JSONObject data) {
        if (MqttCmd.LOGIN_RESP.equals(cmd) || data == null) return false;
        int code = data.optInt("code", Integer.MIN_VALUE);
        if (code == Integer.MIN_VALUE || code == 0 || code == 200) return false;
        String message = data.optString("msg", data.optString("message", ""));
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return message.contains("设备未登录")
                && (normalized.contains("login") || message.contains("登录"));
    }

    /* ==================== 内部：工具 ==================== */

    /** 从 CredentialStore 读取 server base URL，未配置时直接抛异常阻止启动 */
    private String getServerBaseUrl() throws Exception {
        String url = credentialStore.getServerUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 HTTP 服务器地址 (serverUrl)，请在首次启动前通过 CredentialStore.initializeBootstrapConfig() 写入配置");
        }
        return url;
    }

    private String getMqttBrokerUrl() throws Exception {
        String url = credentialStore.getMqttBrokerUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 MQTT Broker 地址");
        }
        return url;
    }

    private void postEvent(BootstrapEvent event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            EventBus.getDefault().post(event);
        } else {
            mainHandler.post(() -> EventBus.getDefault().post(event));
        }
    }

    /* ==================== 内部：异常 ==================== */

    /** Phase A 返回注册码，需要走 Path B */
    static class ActivationCodeRequiredException extends Exception {
        private final String registerCode;
        private final long expireTime;
        private final String initialAdminPassword;

        ActivationCodeRequiredException(String registerCode, long expireTime,
                                        String initialAdminPassword) {
            super("Activation code required: " + registerCode);
            this.registerCode = registerCode;
            this.expireTime = expireTime;
            this.initialAdminPassword = initialAdminPassword;
        }

        String getRegisterCode() { return registerCode; }
        long getExpireTime() { return expireTime; }
        String getInitialAdminPassword() { return initialAdminPassword; }
    }

}
