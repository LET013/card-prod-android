package com.xingyao.card.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.ai.face.core.engine.FaceAISDKEngine;
import com.ai.face.faceSearch.search.FaceSearchFeature;
import com.ai.face.faceSearch.search.FaceSearchFeatureManger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** FaceAISDK platform adapter. Business state remains in DeviceDataLayer/DeviceDataRepository. */
public final class FaceAiManager {
    public interface Listener { void onStatusChanged(JSONObject status); }

    private static volatile FaceAiManager instance;
    private Context appContext;
    private Listener listener;
    private volatile boolean initialized;
    private volatile String state = "STOPPED";
    private volatile String message = "FaceAISDK尚未启动";

    private FaceAiManager() { }

    public static FaceAiManager getInstance() {
        if (instance == null) {
            synchronized (FaceAiManager.class) {
                if (instance == null) instance = new FaceAiManager();
            }
        }
        return instance;
    }

    public synchronized void init(Context context, Listener listener) {
        if (context == null) throw new IllegalArgumentException("context is required");
        appContext = context.getApplicationContext();
        this.listener = listener;
        if (initialized) {
            update("READY", "FaceAISDK已就绪");
            return;
        }
        try {
            FaceAISDKEngine.getInstance(appContext);
            FaceSearchFeatureManger.getInstance(appContext);
            initialized = true;
            update("READY", "FaceAISDK已初始化");
        } catch (Throwable error) {
            initialized = false;
            update("ERROR", "FaceAISDK初始化失败：" + safeMessage(error));
            throw new IllegalStateException("FaceAISDK初始化失败：" + safeMessage(error), error);
        }
    }

    public synchronized void start() {
        if (!initialized) {
            if (appContext == null) throw new IllegalStateException("FaceAISDK尚未配置Context");
            init(appContext, listener);
        }
    }

    public synchronized void stop() {
        release();
    }

    public synchronized void restart() {
        if (appContext == null) throw new IllegalStateException("FaceAISDK尚未配置Context");
        release();
        init(appContext, listener);
    }

    public synchronized void release() {
        if (initialized && appContext != null) {
            try { FaceAISDKEngine.getInstance(appContext).release(); }
            catch (Throwable ignored) { }
        }
        initialized = false;
        update("STOPPED", "FaceAISDK已停止");
    }

    public synchronized boolean isInitialized() { return initialized; }

    public synchronized void awaitReady(long timeoutMs) {
        if (!initialized) throw new IllegalStateException(message);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject().put("state", state).put("message", message)
                .put("engine", "FaceAISDK")
                .put("templateCount", initialized ? getFaceCount() : 0);
    }

    public synchronized String extractFaceFeature(Bitmap croppedFaceBitmap) {
        ensureReady();
        if (croppedFaceBitmap == null) throw new IllegalArgumentException("人脸Bitmap不能为空");
        String feature = FaceAISDKEngine.getInstance(appContext).croppedBitmap2Feature(croppedFaceBitmap);
        if (feature == null || feature.trim().isEmpty()) throw new IllegalStateException("FaceAISDK未提取到人脸特征");
        return feature;
    }

    public synchronized JSONObject enrollFeature(String employeeId, String employeeName,
                                                  String faceFeature, String sourceUrl)
            throws JSONException {
        ensureReady();
        String id = required(employeeId, "employeeId");
        String feature = required(faceFeature, "faceFeature");
        FaceSearchFeatureManger.getInstance(appContext).insertFaceFeature(
                id, feature, System.currentTimeMillis(),
                employeeName == null ? "" : employeeName, "");
        return new JSONObject().put("success", true).put("employeeId", id)
                .put("employeeName", employeeName == null ? "" : employeeName)
                .put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
    }

    public synchronized JSONObject enrollImage(String employeeId, String employeeName,
                                                byte[] imageBytes, String sourceUrl)
            throws JSONException {
        ensureReady();
        if (imageBytes == null || imageBytes.length == 0) throw new IllegalArgumentException("人脸图片为空");
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap == null) throw new IllegalArgumentException("人脸图片无法解码");
        String feature;
        try { feature = extractFaceFeature(bitmap); }
        finally { bitmap.recycle(); }
        return enrollFeature(employeeId, employeeName, feature, sourceUrl);
    }

    public synchronized boolean deleteTemplate(String employeeId) {
        ensureReady();
        String id = required(employeeId, "employeeId");
        FaceSearchFeatureManger.getInstance(appContext).deleteFaceFaceFeature(id);
        return true;
    }

    public synchronized int getFaceCount() {
        ensureReady();
        return FaceSearchFeatureManger.getInstance(appContext).getFaceSearchLibCount();
    }

    public synchronized List<FaceSearchFeature> listAllFaces() {
        ensureReady();
        return FaceSearchFeatureManger.getInstance(appContext).queryAllFaceFaceFeature();
    }

    public synchronized JSONObject templateSummary() throws JSONException {
        return new JSONObject().put("templateCount", initialized ? getFaceCount() : 0)
                .put("employeeIds", new JSONArray());
    }

    private void ensureReady() {
        if (!initialized || appContext == null) throw new IllegalStateException(message);
    }

    private void update(String nextState, String nextMessage) {
        state = nextState;
        message = nextMessage;
        Listener current = listener;
        if (current != null) {
            try { current.onStatusChanged(snapshot()); }
            catch (Exception ignored) { }
        }
    }

    private static String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
