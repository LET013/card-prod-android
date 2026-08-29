package com.xingyao.card.core.bootstrap;

/**
 * 设备启动流程进度事件，通过 EventBus 从 Service 发送到 Activity。
 */
public class BootstrapEvent {

    public enum Phase {
        VERSION_CHECK,
        REGISTERING,
        REGISTERED,
        ACTIVATING,
        ACTIVATED,
        WAITING_ACTIVATION_CODE,
        VERIFYING_CODE,
        CODE_VERIFY_FAILED,
        GETTING_CONFIG,
        CONNECTING_MQTT,
        MQTT_CONNECTED,
        LOGGING_IN,
        LOGGED_IN,
        MQTT_SESSION_LOST,
        RUNNING,
        ERROR,
        FORCE_UPDATE,
    }

    public final Phase phase;
    public final String message;
    public final String deviceCode;
    public final String registerCode;
    public final long registerCodeExpireTime;
    public final String initialAdminPassword;
    public final String versionInfo;
    public final Object extra;

    public BootstrapEvent(Phase phase, String message) {
        this(phase, message, "", "", 0L, "", null, null);
    }

    public BootstrapEvent(Phase phase, String message, String deviceCode) {
        this(phase, message, deviceCode, "", 0L, "", null, null);
    }

    public BootstrapEvent(Phase phase, String message, String deviceCode,
                          String registerCode, long registerCodeExpireTime) {
        this(phase, message, deviceCode, registerCode, registerCodeExpireTime, "", null, null);
    }

    public BootstrapEvent(Phase phase, String message, String deviceCode,
                          String registerCode, long registerCodeExpireTime,
                          String initialAdminPassword) {
        this(phase, message, deviceCode, registerCode, registerCodeExpireTime,
                initialAdminPassword, null, null);
    }

    public BootstrapEvent(Phase phase, String message, String deviceCode,
                          String registerCode, long registerCodeExpireTime,
                          String versionInfo, Object extra) {
        this(phase, message, deviceCode, registerCode, registerCodeExpireTime,
                "", versionInfo, extra);
    }

    public BootstrapEvent(Phase phase, String message, String deviceCode,
                          String registerCode, long registerCodeExpireTime,
                          String initialAdminPassword, String versionInfo, Object extra) {
        this.phase = phase;
        this.message = message;
        this.deviceCode = deviceCode;
        this.registerCode = registerCode;
        this.registerCodeExpireTime = registerCodeExpireTime;
        this.initialAdminPassword = initialAdminPassword;
        this.versionInfo = versionInfo;
        this.extra = extra;
    }

    @Override
    public String toString() {
        return "BootstrapEvent{" + phase + ", " + message + "}";
    }
}
