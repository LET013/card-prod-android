package com.xingyao.card.core.maintenance;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.utils.DeviceInfoUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * APP 升级原生能力：下载到应用私有目录、校验 APK，并打开系统安装器。
 * 安装是否完成由系统决定；本类不会把“安装器已打开”当作升级成功。
 */
public final class AppUpdateManager {
    private static final String PREFS_FILE = "card_app_update";
    private static final String KEY_STATE = "state_json";
    private static final String UPDATE_DIRECTORY = "updates";
    private static final String FILE_PROVIDER_SUFFIX = ".update.fileprovider";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private AppUpdateManager() { }

    public interface ProgressListener {
        void onProgress(JSONObject progress);
    }

    public static JSONObject info(Context context) throws Exception {
        Context appContext = context.getApplicationContext();
        return new JSONObject()
                .put("currentVersionCode", DeviceInfoUtil.versionCode())
                .put("currentVersionName", DeviceInfoUtil.versionName())
                .put("channelId", new CredentialStore(appContext).getChannelId())
                .put("canRequestPackageInstalls", canRequestPackageInstalls(appContext));
    }

    public static JSONObject status(Context context, boolean clearCompleted) throws Exception {
        Context appContext = context.getApplicationContext();
        JSONObject state = loadState(appContext);
        if (!state.has("status")) {
            state.put("status", "NONE");
        }
        int targetVersionCode = state.optInt("versionCode", 0);
        int currentVersionCode = DeviceInfoUtil.versionCode();
        if (targetVersionCode > 0 && currentVersionCode >= targetVersionCode
                && !"NONE".equals(state.optString("status"))) {
            state.put("status", "COMPLETED")
                    .put("progress", 100)
                    .put("completedAt", System.currentTimeMillis());
            saveState(appContext, state);
        } else if ("VERIFIED".equals(state.optString("status"))) {
            File file = new File(state.optString("filePath", ""));
            if (!file.isFile()) {
                state.put("status", "FAILED")
                        .put("errorCode", "APK_FILE_MISSING")
                        .put("errorMessage", "已校验的 APK 文件不存在");
                saveState(appContext, state);
            }
        }
        state.put("currentVersionCode", currentVersionCode)
                .put("currentVersionName", DeviceInfoUtil.versionName())
                .put("channelId", new CredentialStore(appContext).getChannelId())
                .put("canRequestPackageInstalls", canRequestPackageInstalls(appContext));
        JSONObject result = new JSONObject(state.toString());
        if (clearCompleted && "COMPLETED".equals(state.optString("status"))) {
            deleteStoredApk(appContext, state);
            clearState(appContext);
        }
        return result;
    }

    public static JSONObject downloadAndVerify(Context context, JSONObject payload,
                                               ProgressListener progressListener) throws Exception {
        Context appContext = context.getApplicationContext();
        String operationId = required(payload, "operationId");
        String apkUrl = required(payload, "apkUrl");
        if (!apkUrl.startsWith("https://") && !apkUrl.startsWith("http://")) {
            throw new IllegalArgumentException("apkUrl 必须是 HTTP 或 HTTPS 地址");
        }
        int versionCode = payload.optInt("versionCode", 0);
        if (versionCode <= DeviceInfoUtil.versionCode()) {
            throw new IllegalArgumentException("目标版本必须高于当前版本");
        }
        String versionName = payload.optString("versionName", "").trim();
        long expectedSize = payload.optLong("apkSize", 0L);
        String expectedMd5 = required(payload, "apkMd5").toLowerCase(Locale.ROOT);
        if (!expectedMd5.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("apkMd5 必须是 32 位 MD5");
        }

        File directory = new File(appContext.getFilesDir(), UPDATE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建 APP 升级目录");
        }
        File target = new File(directory, "app-update-" + versionCode + ".apk");
        File partial = new File(directory, "app-update-" + versionCode + ".apk.part");
        deleteIfExists(partial);

        JSONObject state = new JSONObject()
                .put("operationId", operationId)
                .put("status", "DOWNLOADING")
                .put("progress", 0)
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("apkSize", expectedSize)
                .put("apkMd5", expectedMd5)
                .put("startedAt", System.currentTimeMillis());
        saveState(appContext, state);
        notifyProgress(progressListener, state, 0L, expectedSize, 0);

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES)
                    .build();
            Request request = new Request.Builder().url(apkUrl).get().build();
            long downloaded;
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("APK 下载失败，HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IllegalStateException("APK 下载响应为空");
                }
                long responseSize = body.contentLength();
                long progressTotal = expectedSize > 0 ? expectedSize : responseSize;
                downloaded = copyToFile(body.byteStream(), partial, progressTotal,
                        state, progressListener);
            }

            if (expectedSize > 0 && downloaded != expectedSize) {
                throw new IllegalStateException("APK 文件大小校验失败");
            }
            String actualMd5 = md5(partial);
            if (!expectedMd5.equals(actualMd5)) {
                throw new IllegalStateException("APK MD5 校验失败");
            }
            verifyArchive(appContext, partial, versionCode);
            deleteIfExists(target);
            if (!partial.renameTo(target)) {
                throw new IllegalStateException("APK 校验后无法保存");
            }

            state.put("status", "VERIFIED")
                    .put("progress", 100)
                    .put("filePath", target.getAbsolutePath())
                    .put("downloadedSize", downloaded)
                    .put("verifiedAt", System.currentTimeMillis())
                    .remove("errorCode");
            state.remove("errorMessage");
            saveState(appContext, state);
            notifyProgress(progressListener, state, downloaded, downloaded, 100);
            return new JSONObject(state.toString());
        } catch (Exception error) {
            deleteIfExists(partial);
            state.put("status", "FAILED")
                    .put("errorCode", "APP_UPDATE_DOWNLOAD_FAILED")
                    .put("errorMessage", safeMessage(error))
                    .put("failedAt", System.currentTimeMillis());
            saveState(appContext, state);
            throw error;
        }
    }

    public static JSONObject install(Context context, String requestedOperationId) throws Exception {
        Context appContext = context.getApplicationContext();
        JSONObject state = loadState(appContext);
        String operationId = required(state, "operationId");
        if (requestedOperationId != null && !requestedOperationId.trim().isEmpty()
                && !operationId.equals(requestedOperationId.trim())) {
            throw new IllegalArgumentException("升级操作编号不匹配");
        }
        String status = state.optString("status", "");
        if (!"VERIFIED".equals(status) && !"PERMISSION_REQUIRED".equals(status)
                && !"INSTALLER_OPENED".equals(status)) {
            throw new IllegalStateException("APK 尚未完成下载校验");
        }
        File apkFile = new File(required(state, "filePath"));
        if (!isInsideUpdateDirectory(appContext, apkFile) || !apkFile.isFile()) {
            throw new IllegalStateException("APK 文件路径无效");
        }
        long expectedSize = state.optLong("apkSize", 0L);
        if (expectedSize > 0 && apkFile.length() != expectedSize) {
            throw new IllegalStateException("安装前 APK 文件大小校验失败");
        }
        if (!required(state, "apkMd5").equalsIgnoreCase(md5(apkFile))) {
            throw new IllegalStateException("安装前 APK MD5 校验失败");
        }
        verifyArchive(appContext, apkFile, state.optInt("versionCode", 0));

        if (!canRequestPackageInstalls(appContext)) {
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + appContext.getPackageName()));
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(settingsIntent);
            state.put("status", "PERMISSION_REQUIRED")
                    .put("permissionRequired", true)
                    .put("updatedAt", System.currentTimeMillis());
            saveState(appContext, state);
            return new JSONObject(state.toString());
        }

        Uri apkUri = FileProvider.getUriForFile(
                appContext,
                appContext.getPackageName() + FILE_PROVIDER_SUFFIX,
                apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (installIntent.resolveActivity(appContext.getPackageManager()) == null) {
            throw new IllegalStateException("系统没有可用的 APK 安装器");
        }
        appContext.startActivity(installIntent);
        state.put("status", "INSTALLER_OPENED")
                .put("permissionRequired", false)
                .put("installerOpenedAt", System.currentTimeMillis());
        saveState(appContext, state);
        return new JSONObject(state.toString());
    }

    private static long copyToFile(InputStream input, File target, long total,
                                   JSONObject state, ProgressListener listener) throws Exception {
        long downloaded = 0L;
        int lastPercent = -1;
        long lastNotifyAt = 0L;
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                downloaded += count;
                int percent = total > 0 ? (int) Math.min(99L, downloaded * 100L / total) : 0;
                long now = System.currentTimeMillis();
                if (percent != lastPercent || now - lastNotifyAt >= 500L) {
                    lastPercent = percent;
                    lastNotifyAt = now;
                    notifyProgress(listener, state, downloaded, total, percent);
                }
            }
            output.getFD().sync();
        }
        return downloaded;
    }

    private static void notifyProgress(ProgressListener listener, JSONObject state,
                                       long downloaded, long total, int percent) throws JSONException {
        if (listener == null) return;
        listener.onProgress(new JSONObject()
                .put("operationId", state.optString("operationId"))
                .put("status", state.optString("status", "DOWNLOADING"))
                .put("downloadedSize", downloaded)
                .put("totalSize", total)
                .put("progress", percent));
    }

    private static void verifyArchive(Context context, File apkFile,
                                      int expectedVersionCode) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (archive == null) {
            throw new IllegalStateException("下载文件不是有效 APK");
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            throw new IllegalStateException("APK 包名与当前应用不一致");
        }
        long archiveVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveVersionCode != expectedVersionCode) {
            throw new IllegalStateException("APK 版本号与服务端信息不一致");
        }
    }

    private static String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(32);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean canRequestPackageInstalls(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    private static boolean isInsideUpdateDirectory(Context context, File file) throws Exception {
        File directory = new File(context.getFilesDir(), UPDATE_DIRECTORY).getCanonicalFile();
        File candidate = file.getCanonicalFile();
        return candidate.getParentFile() != null && directory.equals(candidate.getParentFile());
    }

    private static String required(JSONObject object, String key) {
        String value = object == null ? "" : object.optString(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static JSONObject loadState(Context context) throws JSONException {
        String raw = preferences(context).getString(KEY_STATE, "{}");
        return new JSONObject(raw == null ? "{}" : raw);
    }

    private static void saveState(Context context, JSONObject state) throws JSONException {
        state.put("updatedAt", System.currentTimeMillis());
        if (!preferences(context).edit().putString(KEY_STATE, state.toString()).commit()) {
            throw new IllegalStateException("无法保存 APP 升级状态");
        }
    }

    private static void clearState(Context context) {
        if (!preferences(context).edit().remove(KEY_STATE).commit()) {
            throw new IllegalStateException("无法清理 APP 升级状态");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    private static void deleteStoredApk(Context context, JSONObject state) {
        try {
            File file = new File(state.optString("filePath", ""));
            if (isInsideUpdateDirectory(context, file)) deleteIfExists(file);
        } catch (Exception ignored) { }
    }

    private static void deleteIfExists(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "APP 升级失败" : message.trim();
    }
}
