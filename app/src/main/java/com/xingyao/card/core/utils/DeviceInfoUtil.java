package com.xingyao.card.core.utils;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.xingyao.card.BuildConfig;
import com.xingyao.card.core.bootstrap.CredentialStore;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设备信息工具方法。
 */
public final class DeviceInfoUtil {

    private DeviceInfoUtil() {}

    /**
     * 获取 machineId。优先级：
     * 1. SharedPreferences 中已持久化的 machineId
     * 2. Settings.Secure.ANDROID_ID（前缀 "ANDROID_"）
     * 3. Build.SERIAL（前缀 "SERIAL_"）
     */
    public static String machineId(Context context, CredentialStore store) throws JSONException {
        String configured = store.getMachineId();
        if (!configured.isEmpty()) return configured;

        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.trim().isEmpty()) {
            return "ANDROID_" + androidId.trim();
        }

        String serial = Build.SERIAL == null ? "" : Build.SERIAL.trim();
        if (!serial.isEmpty() && !"unknown".equalsIgnoreCase(serial)) {
            return "SERIAL_" + serial;
        }
        throw new IllegalStateException("无法获取 AndroidID 或设备序列号");
    }

    /**
     * 构造注册/激活请求中的设备信息 body。
     * @see #deviceBody(String) 
     */
    public static JSONObject deviceBody(String machineId) throws JSONException {
        return new JSONObject()
                .put("machineId", machineId)
                .put("mac", "")
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("osType", "ANDROID")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_NAME);
    }

    public static int versionCode() {
        return BuildConfig.VERSION_CODE;
    }

    public static String versionName() {
        return BuildConfig.VERSION_NAME;
    }
}
