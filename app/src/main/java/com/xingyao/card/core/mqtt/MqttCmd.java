package com.xingyao.card.core.mqtt;

/**
 * MQTT cmd 字符串统一管理。
 *
 * <p>使用方式：
 * <pre>{@code
 * if (MqttCmd.LOGIN_RESP.equals(event.cmd)) { ... }
 * client.sendMessage(MqttCmd.HEARTBEAT, data);
 * }</pre>
 */
public final class MqttCmd {
    private MqttCmd() {}

    // ── 上行 cmd (设备 → 服务端) ──
    public static final String LOGIN = "login";
    public static final String HEARTBEAT = "heartbeat";
    public static final String CARD_EVENT = "cardEvent";
    public static final String STATUS_REPORT = "statusReport";
    public static final String HARDWARE_FAULT = "hardwareFault";
    public static final String SELF_CHECK_REPORT = "selfCheckReport";
    public static final String LOG_REPORT = "logReport";
    public static final String STATISTICS_REPORT = "statisticsReport";
    public static final String UPGRADE_STATUS = "upgradeStatus";
    public static final String BATCH_OPERATION_RESULT = "batchOperationResult";
    public static final String AUTH_STATUS_CHANGE = "authStatusChange";
    /** 同步指令 — 员工资料（上行分页请求） */
    public static final String SYNC_EMPLOYEE_DATA = "syncEmployeeData";
    /** 同步指令 — 人脸特征（上行分页请求） */
    public static final String SYNC_FACE_DATA = "syncFaceData";
    /** 同步指令 — 指纹特征（上行分页请求） */
    public static final String SYNC_FINGER_DATA = "syncFingerData";
    /** 人脸注册 — 终端录入后上报人脸记录 */
    public static final String FACE_REGISTER = "faceRegister";

    // ── 下行 cmd (服务端 → 设备) ──
    public static final String REMOTE_OPEN = "remoteOpen";
    public static final String REMOTE_EJECT_ALL = "remoteEjectAll";
    public static final String RESTART_APP = "restartApp";
    public static final String SYNC_USER = "syncUser";
    public static final String SYNC_CONFIG = "syncConfig";
    public static final String FIRMWARE_UPGRADE = "firmwareUpgrade";
    public static final String CANCEL_UPGRADE = "cancelUpgrade";
    public static final String DEVICE_SELF_CHECK = "deviceSelfCheck";
    public static final String ENABLE_LOG_UPLOAD = "enableLogUpload";
    public static final String DISABLE_LOG_UPLOAD = "disableLogUpload";

    // ── 响应 cmd (服务端 → 设备) ──
    public static final String LOGIN_RESP = "loginResp";
    public static final String HEARTBEAT_RESP = "heartbeatResp";
    public static final String CARD_EVENT_RESP = "cardEventResp";
    public static final String STATUS_REPORT_RESP = "statusReportResp";
    public static final String HARDWARE_FAULT_RESP = "hardwareFaultResp";
    public static final String SELF_CHECK_REPORT_RESP = "selfCheckReportResp";
    public static final String LOG_REPORT_RESP = "logReportResp";
    public static final String STATISTICS_REPORT_RESP = "statisticsReportResp";
    public static final String UPGRADE_STATUS_RESP = "upgradeStatusResp";
    public static final String BATCH_OPERATION_RESULT_RESP = "batchOperationResultResp";
    public static final String AUTH_STATUS_CHANGE_RESP = "authStatusChangeResp";
    public static final String SYNC_EMPLOYEE_DATA_RESP = "syncEmployeeDataResp";
    public static final String SYNC_FACE_DATA_RESP = "syncFaceDataResp";
    public static final String SYNC_FINGER_DATA_RESP = "syncFingerDataResp";
    public static final String FACE_REGISTER_RESP = "faceRegisterResp";
    // ── 下行→上行响应 ──
    public static final String REMOTE_OPEN_RESP = "remoteOpenResp";
    public static final String REMOTE_EJECT_ALL_RESP = "remoteEjectAllResp";
    public static final String RESTART_APP_RESP = "restartAppResp";
    public static final String SYNC_USER_RESP = "syncUserResp";
    public static final String SYNC_CONFIG_RESP = "syncConfigResp";
    public static final String FIRMWARE_UPGRADE_RESP = "firmwareUpgradeResp";
    public static final String CANCEL_UPGRADE_RESP = "cancelUpgradeResp";
    public static final String DEVICE_SELF_CHECK_RESP = "deviceSelfCheckResp";

    /** 根据上行 cmd 推导对应的响应 cmd: {@code cmd + "Resp"} */
    public static String respCmd(String cmd) {
        return cmd + "Resp";
    }
}
