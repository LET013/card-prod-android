package com.xingyao.card.core.maintenance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 应用进程重启能力。业务记录和 MQTT 响应仍由 Vue 管理，本类只保存重启凭据并调度进程重启。
 */
public final class AppRestartManager {
    static final String EXTRA_OPERATION_ID = "operation_id";
    private static final String PREFS_FILE = "app_restart_state";
    private static final String KEY_OPERATION_ID = "operation_id";
    private static final String KEY_STATUS = "status";
    private static final String KEY_REQUESTED_AT = "requested_at";
    private static final String KEY_EXECUTED_AT = "executed_at";
    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final int RESTART_REQUEST_CODE = 42001;
    private static final int RELAUNCH_REQUEST_CODE = 42002;
    private static final long MIN_RESTART_DELAY_MS = 1_000L;
    private static final long RELAUNCH_DELAY_MS = 500L;

    private AppRestartManager() { }

    public static JSONObject schedule(Context context, String operationId, long requestedDelayMs)
            throws JSONException {
        String actualOperationId = operationId == null ? "" : operationId.trim();
        if (actualOperationId.isEmpty()) {
            throw new IllegalArgumentException("operationId is required");
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = prefs(appContext);
        String activeOperationId = prefs.getString(KEY_OPERATION_ID, "");
        String activeStatus = prefs.getString(KEY_STATUS, "");
        if (STATUS_SCHEDULED.equals(activeStatus) && !actualOperationId.equals(activeOperationId)) {
            throw new IllegalStateException("another app restart is already scheduled");
        }

        long delayMs = Math.max(MIN_RESTART_DELAY_MS, requestedDelayMs);
        long requestedAt = System.currentTimeMillis();
        if (!prefs.edit()
                .putString(KEY_OPERATION_ID, actualOperationId)
                .putString(KEY_STATUS, STATUS_SCHEDULED)
                .putLong(KEY_REQUESTED_AT, requestedAt)
                .remove(KEY_EXECUTED_AT)
                .commit()) {
            throw new IllegalStateException("failed to persist app restart state");
        }

        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            clear(appContext);
            throw new IllegalStateException("alarm service is unavailable");
        }
        Intent intent = new Intent(appContext, AppRestartReceiver.class)
                .setAction(AppRestartReceiver.ACTION_EXECUTE_RESTART)
                .putExtra(EXTRA_OPERATION_ID, actualOperationId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                appContext,
                RESTART_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT | immutableFlag());
        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent);
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }

        return status(appContext, false).put("effectiveDelayMs", delayMs);
    }

    static void execute(Context context, String operationId) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = prefs(appContext);
        String persistedOperationId = prefs.getString(KEY_OPERATION_ID, "");
        if (!persistedOperationId.equals(operationId) || !STATUS_SCHEDULED.equals(prefs.getString(KEY_STATUS, ""))) {
            return;
        }
        prefs.edit()
                .putString(KEY_STATUS, STATUS_EXECUTED)
                .putLong(KEY_EXECUTED_AT, System.currentTimeMillis())
                .commit();

        Intent launchIntent = appContext.getPackageManager()
                .getLaunchIntentForPackage(appContext.getPackageName());
        if (launchIntent == null) return;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent relaunch = PendingIntent.getActivity(
                appContext,
                RELAUNCH_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_CANCEL_CURRENT | immutableFlag());
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS,
                relaunch);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    public static JSONObject status(Context context, boolean clearExecuted) throws JSONException {
        SharedPreferences prefs = prefs(context.getApplicationContext());
        String operationId = prefs.getString(KEY_OPERATION_ID, "");
        String status = prefs.getString(KEY_STATUS, "NONE");
        JSONObject result = new JSONObject()
                .put("operationId", operationId)
                .put("status", status)
                .put("requestedAt", prefs.getLong(KEY_REQUESTED_AT, 0L))
                .put("executedAt", prefs.getLong(KEY_EXECUTED_AT, 0L));
        if (clearExecuted && STATUS_EXECUTED.equals(status)) {
            clear(context);
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    private static void clear(Context context) {
        prefs(context.getApplicationContext()).edit().clear().commit();
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
