package com.xingyao.card;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.xingyao.card.core.DeviceRuntimeRegistry;

import com.ai.face.base.addFace.AddFaceCallBack;
import com.ai.face.base.addFace.AddFaceDispose;
import com.ai.face.faceSearch.search.FaceSearchEngine;
import com.ai.face.faceSearch.search.SearchProcessBuilder;
import com.ai.face.faceSearch.search.SearchProcessCallBack;
import com.ai.face.faceSearch.utils.FaceSearchResult;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 人脸录入 / 1:N 搜索控制器（非 Dialog）。
 * UI 由 MainActivity 的 face_overlay 容器托管，本类仅管理 SDK 逻辑和 UI 更新。
 */
public class FaceEnrollmentController {
    private static final String TAG = "FaceEnrollCtrl";

    /* ========== 回调接口 ========== */
    public interface FaceResultCallback {
        void onFaceEnrolled(String faceId, String faceFeature, float score);
        void onFaceVerified(String faceId, float score);
        void onCancelled();
    }

    /* ========== 模式 ========== */
    private final boolean isEnrollMode;
    private final boolean isSearchMode;
    private final String faceId;
    private final String faceName;
    private final FaceResultCallback callback;
    private final AppCompatActivity activity;

    /* ========== UI（由 MainActivity 传入） ========== */
    private final TextView tvStatus;
    private final Button btnCapture;
    private final Button btnCancel;

    /* ========== FaceAISDK ========== */
    private AddFaceDispose addFaceDispose;

    /* ========== 手动拍照状态 ========== */
    private boolean captureRequested = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable captureTimeoutRunnable;
    private static final long CAPTURE_TIMEOUT_MS = 8000L;

    /* ========== 结果标记 ========== */
    private boolean resultReturned = false;

    public FaceEnrollmentController(AppCompatActivity activity, boolean isEnroll,
                                    String faceId, String faceName,
                                    TextView tvStatus, Button btnCapture, Button btnCancel,
                                    FaceResultCallback callback) {
        this.activity = activity;
        this.isEnrollMode = isEnroll;
        this.isSearchMode = !isEnroll;
        this.faceId = (faceId != null) ? faceId : "";
        this.faceName = (faceName != null) ? faceName : "";
        this.tvStatus = tvStatus;
        this.btnCapture = btnCapture;
        this.btnCancel = btnCancel;
        this.callback = callback;
    }

    /* ================================================================
     * 生命周期
     * ================================================================ */
    public void start() {
        setupUI();
        setListeners();

        // 将帧分析器设置到 MainActivity 的常驻相机上
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setFaceAnalyzer(createFrameAnalyzer());
        }

        // 初始化 FaceSDK
        if (isSearchMode) {
            initFaceSearch();
        } else {
            initAddFaceDispose();
        }
    }

    public void stop() {
        cancelCaptureTimeout();
        if (addFaceDispose != null) {
            addFaceDispose.release();
            addFaceDispose = null;
        }
        if (isSearchMode) {
            FaceSearchEngine.getInstance().stopSearchProcess();
        }
        // 清除 Activity 常驻相机上的分析器
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).clearFaceAnalyzer();
        }
    }

    /* ================================================================
     * UI 初始化
     * ================================================================ */
    private void setupUI() {
        if (isEnrollMode) {
            tvStatus.setText("请正对摄像头，点击拍照");
            btnCapture.setVisibility(View.VISIBLE);
            btnCapture.setEnabled(true);
        } else {
            tvStatus.setText("正在识别...");
            btnCapture.setVisibility(View.GONE);
        }
    }

    private void setListeners() {
        btnCapture.setOnClickListener(v -> onCaptureClick());
        btnCancel.setOnClickListener(v -> returnCancel());
    }

    /* ================================================================
     * 帧分析器 — 路由到录入 / 搜索处理逻辑
     * ================================================================ */
    private ImageAnalysis.Analyzer createFrameAnalyzer() {
        return imageProxy -> {
            if (resultReturned) {
                imageProxy.close();
                return;
            }

            if (isEnrollMode) {
                processEnrollFrame(imageProxy);
            } else {
                processSearchFrame(imageProxy);
            }
        };
    }

    /* ================================================================
     * 录入模式
     * ================================================================ */
    private void onCaptureClick() {
        if (captureRequested || resultReturned) return;
        captureRequested = true;
        Log.d(TAG, "开始拍照检测, faceId=" + faceId);
        btnCapture.setEnabled(false);
        btnCapture.setText("检测中...");
        tvStatus.setText("正在检测...");

        cancelCaptureTimeout();
        captureTimeoutRunnable = () -> {
            if (resultReturned) return;
            captureRequested = false;
            if (addFaceDispose != null) {
                addFaceDispose.release();
                addFaceDispose = null;
            }
            initAddFaceDispose();
            btnCapture.setEnabled(true);
            btnCapture.setText("重新拍照");
            tvStatus.setText("检测超时，请调整姿势后重试");
            Toast.makeText(activity, "未检测到有效人脸，请调整姿势后重试", Toast.LENGTH_SHORT).show();
        };
        timeoutHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS);
    }

    private void initAddFaceDispose() {
        if (addFaceDispose != null) return;

        addFaceDispose = new AddFaceDispose(
                activity,
                AddFaceDispose.PERFORMANCE_MODE_ACCURATE,
                false,
                new AddFaceCallBack() {
                    @Override
                    public void onCompleted(Bitmap croppedBitmap, float silentLiveScore) {
                        cancelCaptureTimeout();
                        onFaceCaptured(croppedBitmap, silentLiveScore);
                    }

                    @Override
                    public void onCompleted(Bitmap croppedBitmap, float silentLiveScore, Bitmap originBitmap) {
                        cancelCaptureTimeout();
                        onFaceCaptured(croppedBitmap, silentLiveScore);
                    }

                    @Override
                    public void onProcessTips(int actionCode) {
                        Log.d(TAG, "onProcessTips: code=" + actionCode + " → " + getEnrollTips(actionCode));
                        timeoutHandler.post(() -> tvStatus.setText(getEnrollTips(actionCode)));
                    }
                });
    }

    private void cancelCaptureTimeout() {
        if (captureTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(captureTimeoutRunnable);
            captureTimeoutRunnable = null;
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processEnrollFrame(ImageProxy imageProxy) {
        if (!captureRequested || resultReturned) {
            imageProxy.close();
            return;
        }

        try {
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            Log.d(TAG, String.format("处理帧: format=%d w=%d h=%d rotation=%d°",
                    imageProxy.getFormat(), imageProxy.getWidth(), imageProxy.getHeight(),
                    rotationDegrees));
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                Log.w(TAG, "imageProxyToBitmap 返回 null, format=" + imageProxy.getFormat());
            } else {
                Bitmap transformed = rotateAndMirrorBitmap(bitmap, rotationDegrees);
                if (addFaceDispose != null) {
                    addFaceDispose.dispose(transformed);
                } else {
                    Log.w(TAG, "addFaceDispose 为 null, 帧被丢弃");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "processEnrollFrame 异常", e);
        } finally {
            imageProxy.close();
        }
    }

    private void onFaceCaptured(Bitmap croppedBitmap, float silentLiveScore) {
        if (resultReturned || croppedBitmap == null) return;
        synchronized (this) {
            if (resultReturned || croppedBitmap == null) return;
            resultReturned = true;
        }
        cancelCaptureTimeout();

        timeoutHandler.post(() -> {
            btnCapture.setEnabled(false);
            btnCapture.setText("处理中...");
            tvStatus.setText("提取特征中...");
        });

        try {
            String faceFeature = DeviceRuntimeRegistry.require().extractFaceFeature(croppedBitmap);
            if (faceFeature == null || faceFeature.isEmpty()) {
                timeoutHandler.post(() -> {
                    Toast.makeText(activity, "特征提取失败,请重试", Toast.LENGTH_SHORT).show();
                    resultReturned = false;
                });
                return;
            }

            Log.i(TAG, "Face enrolled: id=" + faceId + ", score=" + silentLiveScore);

            timeoutHandler.post(() -> {
                tvStatus.setText("录入成功!");
                Toast.makeText(activity, "人脸录入成功", Toast.LENGTH_SHORT).show();
                callback.onFaceEnrolled(faceId, faceFeature, silentLiveScore);
            });

        } catch (Exception e) {
            timeoutHandler.post(() -> {
                Toast.makeText(activity, "处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                returnCancel();
            });
        }
    }

    /* ================================================================
     * 搜索模式
     * ================================================================ */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processSearchFrame(ImageProxy imageProxy) {
        if (resultReturned) {
            imageProxy.close();
            return;
        }

        try {
            FaceSearchEngine.getInstance().runSearchWithImageProxy(imageProxy, 0);
        } catch (Exception e) {
            // ignore
        } finally {
            imageProxy.close();
        }
    }

    private float configuredFaceThreshold() {
        try { return DeviceRuntimeRegistry.require().faceRecognitionThreshold(); }
        catch (Exception ignored) { return 0.8f; }
    }

    private void initFaceSearch() {
        SearchProcessBuilder builder = new SearchProcessBuilder.Builder(activity)
                .setThreshold(configuredFaceThreshold())
                .setNeedFaceLiveness(false)
                .setSearchType(SearchProcessBuilder.SearchType.N_SEARCH_1)
                .setCallBackAllMatch(false)
                .setSearchIntervalTime(3000)
                .setSearchTimeOut(15000)
                .setLifecycleOwner(activity)
                .setProcessCallBack(new SearchProcessCallBack() {
                    @Override
                    public void onMostSimilar(String mostSimilarID, float score,
                                              Bitmap bitmap, float livenessValue) {
                        handleMatchResult(mostSimilarID, score);
                    }

                    @Override
                    public void onFaceDetected(List<FaceSearchResult> result) {
                    }

                    @Override
                    public void onFaceMatched(List<FaceSearchResult> matchedResults,
                                              Bitmap searchBitmap, float livenessValue) {
                        if (matchedResults != null && !matchedResults.isEmpty()) {
                            FaceSearchResult best = matchedResults.get(0);
                            handleMatchResult(best.getFaceName(), best.getFaceScore());
                        }
                    }

                    @Override
                    public void onProcessTips(int code) {
                        timeoutHandler.post(() -> tvStatus.setText(getSearchTips(code)));
                    }

                    @Override
                    public void onLog(String msg) {
                    }
                })
                .create();

        FaceSearchEngine.getInstance().initSearchParams(builder);
    }

    private void handleMatchResult(String matchedFaceID, float score) {
        if (resultReturned) return;
        synchronized (this) {
            if (resultReturned) return;
            resultReturned = true;
        }

        Log.i(TAG, "Search matched: " + matchedFaceID + ", score=" + score);

        timeoutHandler.post(() -> {
            tvStatus.setText("识别成功: " + matchedFaceID + " (" + String.format("%.2f", score) + ")");
            Toast.makeText(activity, "识别: " + matchedFaceID, Toast.LENGTH_SHORT).show();
            callback.onFaceVerified(matchedFaceID, score);
        });
    }

    /* ================================================================
     * 工具方法
     * ================================================================ */
    private void returnCancel() {
        if (resultReturned) return;
        resultReturned = true;
        callback.onCancelled();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        int format = imageProxy.getFormat();
        if (format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "不支持的图像格式: " + format + ", 仅支持 YUV_420_888");
            return null;
        }

        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int ySize = Math.min(width * height, yBuffer.remaining());
        byte[] nv21 = new byte[width * height * 3 / 2];

        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, row * width, width);
            }
        }

        int uvOffset = width * height;
        int uvWidth = width / 2;
        int uvHeight = height / 2;

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvIndex = row * uRowStride + col * uPixelStride;
                int nvIndex = uvOffset + (row * width + col * 2);
                if (nvIndex + 1 < nv21.length && uvIndex < vBuffer.limit()) {
                    nv21[nvIndex] = vBuffer.get(uvIndex);
                }
                if (nvIndex + 1 < nv21.length && uvIndex < uBuffer.limit()) {
                    nv21[nvIndex + 1] = uBuffer.get(uvIndex);
                }
            }
        }

        android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21, ImageFormat.NV21, width, height, null);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(
                new android.graphics.Rect(0, 0, width, height),
                100, out);
        byte[] imageBytes = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private Bitmap rotateAndMirrorBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        matrix.postScale(-1, 1, source.getWidth() / 2f, source.getHeight() / 2f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private String getEnrollTips(int actionCode) {
        switch (actionCode) {
            case 0:  return "未检测到人脸";
            case 1:  return "请靠近一点";
            case 2:  return "请离远一点";
            case 3:  return "请正对摄像头";
            case 4:  return "请勿遮挡面部";
            case 5:  return "保持稳定";
            case 6:  return "正在检测中...";
            case 7:  return "请睁眼";
            case 8:  return "请勿低头";
            case 9:  return "检测中...";
            case 30: return "请抬头";
            case 31: return "请稍向左转";
            case 32: return "请稍向右转";
            case 33: return "请保持正面";
            default: return "请正对摄像头(" + actionCode + ")";
        }
    }

    private String getSearchTips(int code) {
        switch (code) {
            case 0: return "未检测到人脸";
            case 1: return "请靠近一点";
            case 2: return "请离远一点";
            case 3: return "人脸偏转角度过大";
            case 4: return "请正对摄像头";
            default: return "正在识别...";
        }
    }
}
