package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;

import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.MaskInfo;
import com.arcsoft.face.enums.DetectFaceOrientPriority;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.face.enums.ExtractType;
import com.xingyao.card.BuildConfig;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

/** Owns ArcFace activation and the reusable recognition engine lifecycle. */
public final class ArcFaceManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FaceEngine faceEngine;
    private String state = "NOT_CONFIGURED";
    private String message = "虹软 ArcFace 尚未配置";
    private int lastCode = -1;
    private boolean initializing;

    public ArcFaceManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (initializing || "ACTIVE".equals(state)) return;
        if (TextUtils.isEmpty(BuildConfig.ARCSOFT_APP_ID) || TextUtils.isEmpty(BuildConfig.ARCSOFT_SDK_KEY)
                || TextUtils.isEmpty(BuildConfig.ARCSOFT_ACTIVE_KEY)) {
            update("NOT_CONFIGURED", "请在本机 local.properties 配置虹软 APP_ID、SDK_KEY 与 ACTIVE_KEY", -1);
            return;
        }
        initializing = true;
        update("ACTIVATING", "正在激活虹软 ArcFace", -1);
        executor.execute(this::activateAndInitialize);
    }

    public synchronized void restart() {
        releaseEngine();
        state = "NOT_CONFIGURED";
        start();
    }

    public synchronized void stop() {
        releaseEngine();
        initializing = false;
        update("STOPPED", "虹软 ArcFace 已停止", -1);
        executor.shutdownNow();
    }

    public synchronized boolean isActive() {
        return "ACTIVE".equals(state) && faceEngine != null;
    }

    /** Waits for the asynchronous online activation before a camera frame is processed. */
    public synchronized void awaitReady(long timeoutMs) throws Exception {
        if (!isActive()) start();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (!isActive() && initializing && System.currentTimeMillis() < deadline) {
            wait(Math.max(1L, deadline - System.currentTimeMillis()));
        }
        if (!isActive()) throw new IllegalStateException(message);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        JSONObject result = new JSONObject()
                .put("state", state)
                .put("message", message)
                .put("lastCode", lastCode == -1 ? JSONObject.NULL : lastCode)
                .put("mode", "ONLINE_FIRST_ACTIVATION")
                .put("liveness", "RGB");
        try {
            JSONObject templates = templateSummary();
            result.put("templateCount", templates.optInt("templateCount", 0))
                    .put("templateEmployeeIds", templates.optJSONArray("employeeIds"));
        } catch (Exception ignored) {
            result.put("templateCount", 0);
        }
        return result;
    }

    private void activateAndInitialize() {
        try {
            FaceEngine activationEngine = new FaceEngine();
            int activationCode = activationEngine.activeOnline(
                    context, BuildConfig.ARCSOFT_ACTIVE_KEY, BuildConfig.ARCSOFT_APP_ID, BuildConfig.ARCSOFT_SDK_KEY);
            if (activationCode != ErrorInfo.MOK && activationCode != ErrorInfo.MERR_ASF_ALREADY_ACTIVATED) {
                update("ACTIVATION_FAILED", activationFailureMessage(activationCode), activationCode);
                return;
            }

            FaceEngine recognitionEngine = new FaceEngine();
            int initCode = recognitionEngine.init(
                    context,
                    DetectMode.ASF_DETECT_MODE_IMAGE,
                    DetectFaceOrientPriority.ASF_OP_ALL_OUT,
                    1,
                    FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_LIVENESS);
            if (initCode != ErrorInfo.MOK) {
                recognitionEngine.unInit();
                update("INITIALIZATION_FAILED", "虹软引擎初始化失败，错误码：" + initCode, initCode);
                return;
            }
            synchronized (this) {
                releaseEngine();
                faceEngine = recognitionEngine;
            }
            update("ACTIVE", "虹软 ArcFace 已激活并就绪", ErrorInfo.MOK);
        } catch (UnsatisfiedLinkError error) {
            update("UNSUPPORTED_ABI", "当前设备架构不支持虹软动态库；请使用 ARM 真机或 ARM 模拟器", -1);
        } catch (Exception error) {
            update("ACTIVATION_FAILED", "虹软激活异常：" + safeMessage(error), -1);
        } finally {
            synchronized (this) { initializing = false; notifyAll(); }
        }
    }

    private synchronized void releaseEngine() {
        if (faceEngine == null) return;
        try {
            faceEngine.unInit();
        } catch (Exception ignored) {
            // The native SDK may already have released the engine after a failed initialization.
        }
        faceEngine = null;
    }

    /** Extracts and stores a live face feature; no camera frame or face photo is persisted. */
    public synchronized JSONObject enrollNv21(String employeeId, String employeeName, byte[] data, int width, int height) throws Exception {
        if (!isActive()) throw new IllegalStateException("虹软人脸引擎尚未就绪");
        if (TextUtils.isEmpty(employeeId) || data == null || width <= 0 || height <= 0 || width % 4 != 0 ||
                data.length != width * height * 3 / 2) {
            throw new IllegalArgumentException("无效的人脸采集数据");
        }
        FaceFeature feature = extractLiveFeature(data, width, height, ExtractType.REGISTER);
        saveTemplate(employeeId, employeeName, feature.getFeatureData(), "");
        return new JSONObject()
                .put("success", true)
                .put("employeeId", employeeId)
                .put("employeeName", employeeName)
                .put("templateSize", feature.getFeatureData().length);
    }

    /** Extracts a feature from a backend face photo. Static photos must not run liveness checks. */
    public synchronized JSONObject enrollImage(String employeeId, String employeeName, byte[] imageBytes, String sourceUrl) throws Exception {
        if (!isActive()) throw new IllegalStateException("虹软人脸引擎尚未就绪");
        if (TextUtils.isEmpty(employeeId) || imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("无效的人脸图片数据");
        }
        Nv21Image image = decodeToNv21(imageBytes);
        FaceFeature feature = extractImageFeature(image.data, image.width, image.height, ExtractType.REGISTER);
        saveTemplate(employeeId, employeeName, feature.getFeatureData(), sourceUrl);
        return new JSONObject()
                .put("success", true)
                .put("employeeId", employeeId)
                .put("employeeName", employeeName)
                .put("templateSize", feature.getFeatureData().length)
                .put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
    }

    /** Loads a feature payload returned by backend sync and stores it directly to local engine store. */
    public synchronized JSONObject enrollFeature(String employeeId, String employeeName, String featureBase64, String sourceUrl) throws Exception {
        if (!isActive()) throw new IllegalStateException("虹软人脸引擎尚未就绪");
        if (TextUtils.isEmpty(employeeId) || TextUtils.isEmpty(featureBase64)) {
            throw new IllegalArgumentException("无效的人脸特征数据");
        }
        byte[] featureBytes = decodeBase64(featureBase64);
        if (featureBytes.length == 0) throw new IllegalArgumentException("人脸特征为空");
        saveTemplate(employeeId, employeeName, featureBytes, sourceUrl == null ? "" : sourceUrl);
        return new JSONObject()
                .put("success", true)
                .put("employeeId", employeeId)
                .put("employeeName", employeeName)
                .put("templateSize", featureBytes.length)
                .put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
    }

    public synchronized JSONObject templateSummary() throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences("arcface_templates", Context.MODE_PRIVATE);
        int count = 0;
        JSONArray employeeIds = new JSONArray();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith("feature.")) continue;
            count++;
            employeeIds.put(key.substring("feature.".length()));
        }
        return new JSONObject().put("templateCount", count).put("employeeIds", employeeIds);
    }

    /** Matches a live face against templates enrolled on this device. */
    public synchronized JSONObject verifyNv21(byte[] data, int width, int height) throws Exception {
        FaceFeature liveFeature = extractLiveFeature(data, width, height, ExtractType.RECOGNIZE);
        SharedPreferences preferences = context.getSharedPreferences("arcface_templates", Context.MODE_PRIVATE);
        float bestScore = -1F;
        String bestEmployeeId = null;
        String bestEmployeeName = null;
        FaceSimilar similar = new FaceSimilar();
        for (java.util.Map.Entry<String, ?> item : preferences.getAll().entrySet()) {
            if (!item.getKey().startsWith("feature.") || !(item.getValue() instanceof String)) continue;
            FaceFeature enrolled = new FaceFeature();
            enrolled.setFeatureData(Base64.decode((String) item.getValue(), Base64.NO_WRAP));
            int compareCode = faceEngine.compareFaceFeature(liveFeature, enrolled, similar);
            if (compareCode != ErrorInfo.MOK || similar.getScore() <= bestScore) continue;
            bestScore = similar.getScore();
            bestEmployeeId = item.getKey().substring("feature.".length());
            try {
                JSONObject metadata = new JSONObject(preferences.getString("metadata." + bestEmployeeId, "{}"));
                bestEmployeeName = metadata.optString("employeeName", bestEmployeeId);
            } catch (JSONException ignored) {
                bestEmployeeName = bestEmployeeId;
            }
        }
        if (bestEmployeeId == null) {
            return new JSONObject().put("success", false).put("status", "UNREGISTERED")
                    .put("message", "未登记人脸，请先录入人员信息");
        }
        float threshold = 0.8F;
        try {
            threshold = (float) new NativeSettingsRepository(context).load().optDouble("faceRecognitionThreshold", threshold);
        } catch (JSONException ignored) { }
        if (bestScore < threshold) {
            return new JSONObject().put("success", false).put("status", "UNREGISTERED")
                    .put("similarity", bestScore).put("message", "未登记人脸，请先录入人员信息");
        }
        return new JSONObject().put("success", true).put("employeeId", bestEmployeeId)
                .put("employeeName", bestEmployeeName).put("similarity", bestScore)
                .put("message", "人脸验证成功");
    }

    private FaceFeature extractLiveFeature(byte[] data, int width, int height, ExtractType extractType) throws Exception {
        if (!isActive()) throw new IllegalStateException("虹软人脸引擎尚未就绪");
        if (data == null || width <= 0 || height <= 0 || width % 4 != 0 || data.length != width * height * 3 / 2) {
            throw new IllegalArgumentException("无效的人脸采集数据");
        }
        List<FaceInfo> faces = new ArrayList<>();
        int detectCode = faceEngine.detectFaces(data, width, height, FaceEngine.CP_PAF_NV21, faces);
        if (detectCode != ErrorInfo.MOK) throw new IllegalStateException("人脸检测失败，错误码：" + detectCode);
        if (faces.size() != 1) throw new IllegalStateException(faces.isEmpty() ? "请将一张正脸置于镜头中" : "请确保镜头中仅有一张人脸");
        int processCode = faceEngine.process(data, width, height, FaceEngine.CP_PAF_NV21, faces, FaceEngine.ASF_LIVENESS);
        if (processCode != ErrorInfo.MOK) throw new IllegalStateException("活体检测失败，错误码：" + processCode);
        List<LivenessInfo> livenessInfos = new ArrayList<>();
        int livenessCode = faceEngine.getLiveness(livenessInfos);
        if (livenessCode != ErrorInfo.MOK || livenessInfos.isEmpty() || livenessInfos.get(0).getLiveness() != LivenessInfo.ALIVE) {
            throw new IllegalStateException("未通过活体检测，请正对镜头后重试");
        }
        FaceFeature feature = new FaceFeature();
        int featureCode = faceEngine.extractFaceFeature(data, width, height, FaceEngine.CP_PAF_NV21, faces.get(0),
                extractType, MaskInfo.NOT_WORN, feature);
        if (featureCode != ErrorInfo.MOK || feature.getFeatureData() == null) {
            throw new IllegalStateException("人脸特征提取失败，错误码：" + featureCode);
        }
        return feature;
    }

    private FaceFeature extractImageFeature(byte[] data, int width, int height, ExtractType extractType) throws Exception {
        if (!isActive()) throw new IllegalStateException("虹软人脸引擎尚未就绪");
        if (data == null || width <= 0 || height <= 0 || width % 4 != 0 || data.length != width * height * 3 / 2) {
            throw new IllegalArgumentException("无效的人脸图片数据");
        }
        List<FaceInfo> faces = new ArrayList<>();
        int detectCode = faceEngine.detectFaces(data, width, height, FaceEngine.CP_PAF_NV21, faces);
        if (detectCode != ErrorInfo.MOK) throw new IllegalStateException("人脸检测失败，错误码：" + detectCode);
        if (faces.size() != 1) throw new IllegalStateException(faces.isEmpty() ? "后台人脸图片未检测到人脸" : "后台人脸图片必须只有一张人脸");
        FaceFeature feature = new FaceFeature();
        int featureCode = faceEngine.extractFaceFeature(data, width, height, FaceEngine.CP_PAF_NV21, faces.get(0),
                extractType, MaskInfo.NOT_WORN, feature);
        if (featureCode != ErrorInfo.MOK || feature.getFeatureData() == null) {
            throw new IllegalStateException("人脸特征提取失败，错误码：" + featureCode);
        }
        return feature;
    }

    private void saveTemplate(String employeeId, String employeeName, byte[] feature, String sourceUrl) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences("arcface_templates", Context.MODE_PRIVATE);
        JSONObject metadata = new JSONObject()
                .put("employeeId", employeeId)
                .put("employeeName", employeeName)
                .put("sourceUrl", sourceUrl == null ? "" : sourceUrl)
                .put("updatedAt", System.currentTimeMillis());
        preferences.edit()
                .putString("feature." + employeeId, Base64.encodeToString(feature, Base64.NO_WRAP))
                .putString("metadata." + employeeId, metadata.toString())
                .apply();
    }

    private static Nv21Image decodeToNv21(byte[] imageBytes) {
        Bitmap source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (source == null) throw new IllegalArgumentException("无法解析后台人脸图片");
        Bitmap bitmap = source.getConfig() == Bitmap.Config.ARGB_8888 ? source : source.copy(Bitmap.Config.ARGB_8888, false);
        int width = bitmap.getWidth() & ~3;
        int height = bitmap.getHeight() & ~1;
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("后台人脸图片尺寸无效");
        if (width != bitmap.getWidth() || height != bitmap.getHeight()) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        }
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        return new Nv21Image(argbToNv21(argb, width, height), width, height);
    }

    private static byte[] argbToNv21(int[] argb, int width, int height) {
        int frameSize = width * height;
        byte[] yuv = new byte[frameSize * 3 / 2];
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int pixel = argb[row * width + col];
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yuv[yIndex++] = (byte) clamp(y);
                if ((row & 1) == 0 && (col & 1) == 0) {
                    yuv[uvIndex++] = (byte) clamp(v);
                    yuv[uvIndex++] = (byte) clamp(u);
                }
            }
        }
        return yuv;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static final class Nv21Image {
        final byte[] data;
        final int width;
        final int height;

        Nv21Image(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private void update(String nextState, String nextMessage, int code) {
        synchronized (this) {
            state = nextState;
            message = nextMessage;
            lastCode = code;
            notifyAll();
        }
        if (listener == null) return;
        try {
            listener.onStatusChanged(snapshot());
        } catch (JSONException ignored) {
            // State reporting must not affect the biometric engine lifecycle.
        }
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static byte[] decodeBase64(String input) {
        String normalized = input == null ? "" : input.trim().replace("\\n", "").replace("\\r", "");
        if (normalized.isEmpty()) return new byte[0];
        try {
            return Base64.decode(normalized, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception firstError) {
            try {
                return Base64.decode(normalized, Base64.URL_SAFE | Base64.NO_WRAP);
            } catch (Exception secondError) {
                throw new IllegalArgumentException("人脸特征解码失败: " + safeMessage(firstError));
            }
        }
    }

    private static String activationFailureMessage(int code) {
        if (code == 90122) {
            return "虹软激活失败，错误码：90122。当前 SDK 版本不支持此设备或授权配置，请确认 V5.0 的 ACTIVE_KEY、APP_ID、SDK_KEY 来自同一个激活码。";
        }
        if (code == 90136) {
            return "虹软激活失败，错误码：90136。APP_ID、SDK_KEY、ACTIVE_KEY 与当前 SDK 版本不一致。";
        }
        return "虹软激活失败，错误码：" + code;
    }
}
