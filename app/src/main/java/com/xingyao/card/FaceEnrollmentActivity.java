package com.xingyao.card;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ExperimentalLensFacing;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.ai.face.base.addFace.AddFaceCallBack;
import com.ai.face.base.addFace.AddFaceDispose;
import com.ai.face.core.engine.FaceAISDKEngine;
import com.ai.face.faceSearch.search.FaceSearchEngine;
import com.ai.face.faceSearch.search.FaceSearchFeatureManger;
import com.ai.face.faceSearch.search.SearchProcessBuilder;
import com.ai.face.faceSearch.search.SearchProcessCallBack;
import com.ai.face.faceSearch.utils.FaceSearchResult;
import com.google.common.util.concurrent.ListenableFuture;
import com.xingyao.card.core.FaceAiManager;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 人脸录入 / 人脸搜索 Activity.
 *
 * 使用 CameraX 获取相机帧, 通过 FaceAISDK 的:
 *   - {@link AddFaceDispose} 进行活体检测 + 人脸录入
 *   - {@link FaceSearchEngine} 进行 1:N 人脸搜索
 *
 * Intent 参数:
 *   EXTRA_MODE   : "enroll" 或 "search"
 *   EXTRA_FACE_ID: 录入时的人脸ID (如员工ID)
 *   EXTRA_FACE_NAME: 录入时的用户名
 *
 * 返回结果 (setResult):
 *   RESULT_OK: 含 "faceID", "faceFeature", "score" extras
 *   RESULT_CANCELED: 用户取消或失败
 */
public class FaceEnrollmentActivity extends AppCompatActivity {

    private static final String TAG = "FaceEnrollment";

    // --- Intent extras ---
    public static final String EXTRA_MODE = "face_mode";
    public static final String EXTRA_FACE_ID = "face_id";
    public static final String EXTRA_FACE_NAME = "face_name";
    public static final String MODE_ENROLL = "enroll";
    public static final String MODE_SEARCH = "search";

    // --- Result extras ---
    public static final String RESULT_FACE_ID = "face_id";
    public static final String RESULT_FACE_FEATURE = "face_feature";
    public static final String RESULT_SCORE = "score";

    // --- 启动方法: 录入 ---
    public static void startForEnrollment(AppCompatActivity activity, String faceId, String faceName, int requestCode) {
        Intent intent = new Intent(activity, FaceEnrollmentActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_ENROLL);
        intent.putExtra(EXTRA_FACE_ID, faceId);
        intent.putExtra(EXTRA_FACE_NAME, faceName);
        activity.startActivityForResult(intent, requestCode);
    }

    // --- 启动方法: 搜索 ---
    public static void startForVerification(AppCompatActivity activity, int requestCode) {
        Intent intent = new Intent(activity, FaceEnrollmentActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SEARCH);
        activity.startActivityForResult(intent, requestCode);
    }

    // --- UI ---
    private PreviewView cameraPreview;
    private TextView tvStatus;
    private Button btnCapture;
    private Button btnCancel;
    private LinearLayout loadingOverlay;
    private TextView tvLoading;
    private volatile boolean cameraStreaming = false;

    // --- 模式参数 ---
    private String mode;
    private String faceId;
    private String faceName;

    // --- CameraX ---
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;

    // --- FaceAISDK ---
    private AddFaceDispose addFaceDispose;
    private boolean faceDisposeActive = false;

    // --- 手动拍照状态 ---
    private boolean captureRequested = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable captureTimeoutRunnable;
    private static final long CAPTURE_TIMEOUT_MS = 8000L;

    // --- 黑屏计时埋点 ---
    private long timingOnCreateStart;
    private long timingCameraProviderStart;
    private long timingCameraProviderDone;
    private long timingBindStart;
    private long timingFirstFrame;

    // --- 结果标记 ---
    private boolean resultReturned = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        timingOnCreateStart = System.currentTimeMillis();
        super.onCreate(savedInstanceState);

        // 强制设置 Window 透明 + dim 标志（主题的 windowIsTranslucent 有时不够）
        // 必须放在 setContentView 之前，否则已有默认背景绘制
        Window win = getWindow();
        win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setDimAmount(0.6f);

        setContentView(R.layout.activity_face_enrollment);
        Log.d(TAG, "⏱ onCreate → setContentView 完成, 耗时 " + (System.currentTimeMillis() - timingOnCreateStart) + "ms");

        // 解析 Intent 参数
        Intent intent = getIntent();
        mode = intent.getStringExtra(EXTRA_MODE);
        faceId = intent.getStringExtra(EXTRA_FACE_ID);
        faceName = intent.getStringExtra(EXTRA_FACE_NAME);

        if (mode == null || (!mode.equals(MODE_ENROLL) && !mode.equals(MODE_SEARCH))) {
            finish();
            return;
        }

        // 初始化 UI
        cameraPreview = findViewById(R.id.cameraPreview);
        tvStatus = findViewById(R.id.tvStatus);
        btnCapture = findViewById(R.id.btnCapture);
        btnCancel = findViewById(R.id.btnCancel);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvLoading = findViewById(R.id.tvLoading);

        // 根据模式显示不同的 UI
        if (MODE_ENROLL.equals(mode)) {
            tvStatus.setText("请正对摄像头，点击拍照");
            tvLoading.setText("摄像头启动中...");
            btnCapture.setVisibility(View.VISIBLE);
            btnCapture.setEnabled(false);  // 摄像头就绪后才启用
            btnCapture.setOnClickListener(v -> onCaptureClick());
        } else {
            tvStatus.setText("正在识别...");
            tvLoading.setText("摄像头启动中，模型加载中...");
            btnCapture.setVisibility(View.GONE);
        }
        btnCancel.setOnClickListener(v -> returnCancel());

        // 点击卡片外的半透明遮罩区域关闭
        findViewById(R.id.dimBackground).setOnClickListener(v -> returnCancel());

        // 启动相机
        timingCameraProviderStart = System.currentTimeMillis();
        Log.d(TAG, "⏱ onCreate 完成 → startCamera, 累计耗时 " + (timingCameraProviderStart - timingOnCreateStart) + "ms");
        startCamera();
    }

    // ==================== CameraX ====================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        // 在后台线程等待 camera provider 初始化, 避免阻塞主线程导致黑屏。
        java.util.concurrent.Executor backgroundExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                timingCameraProviderDone = System.currentTimeMillis();
                Log.d(TAG, "⏱ ProcessCameraProvider.getInstance() 返回, 耗时 "
                        + (timingCameraProviderDone - timingCameraProviderStart) + "ms, "
                        + "累计 " + (timingCameraProviderDone - timingOnCreateStart) + "ms");

                // unbindAll() 要求主线程, 必须回主线程完成绑定
                runOnUiThread(() -> {
                    timingBindStart = System.currentTimeMillis();
                    Log.d(TAG, "⏱ 开始 bindCameraUseCases, "
                            + (timingBindStart - timingCameraProviderDone) + "ms 后回调到主线程");
                    bindCameraUseCases();
                });
            } catch (ExecutionException | InterruptedException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "相机启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    returnCancel();
                });
            }
        }, backgroundExecutor);
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // 解绑旧用例 (必须在主线程)
        cameraProvider.unbindAll();

        // 嵌入式设备 (如 rk3568) 只有一个摄像头 (camera 0 = 前置)，
        // 且 HAL 不报告 lensFacing，直接用第一个摄像头，省去 hasCamera() IPC。
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .addCameraFilter(cameraInfos -> {
                    if (cameraInfos == null || cameraInfos.isEmpty())
                        return java.util.Collections.emptyList();
                    return java.util.Collections.singletonList(cameraInfos.get(0));
                })
                .build();

        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        // ImageAnalysis - 分析每一帧
        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(640, 480))
                .build();

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageProxy -> {
            // 首帧到达 = 摄像头已开始串流，隐藏加载遮罩
            if (!cameraStreaming) {
                cameraStreaming = true;
                timingFirstFrame = System.currentTimeMillis();
                Log.d(TAG, "⏱ 首帧到达! 摄像头串流就绪");
                Log.d(TAG, "⏱ 耗时明细: "
                        + "onCreate→startCamera=" + (timingCameraProviderStart - timingOnCreateStart) + "ms, "
                        + "getInstance=" + (timingCameraProviderDone - timingCameraProviderStart) + "ms, "
                        + "回主线程延迟=" + (timingBindStart - timingCameraProviderDone) + "ms, "
                        + "bindToLifecycle→首帧=" + (timingFirstFrame - timingBindStart) + "ms, "
                        + "总计=" + (timingFirstFrame - timingOnCreateStart) + "ms");
                hideLoadingOverlay();
            }

            if (resultReturned) {
                imageProxy.close();
                return;
            }

            if (MODE_ENROLL.equals(mode)) {
                processEnrollFrame(imageProxy);
            } else {
                processSearchFrame(imageProxy);
            }
        });

        cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis);
        Log.d(TAG, "⏱ bindToLifecycle 完成, bind 阶段耗时 " + (System.currentTimeMillis() - timingBindStart) + "ms");
    }

    /**
     * 摄像头串流就绪，隐藏加载遮罩（带淡出动画，150ms 快速过渡）
     */
    private void hideLoadingOverlay() {
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                        // 录入模式下启用拍照按钮
                        if (MODE_ENROLL.equals(mode) && btnCapture != null) {
                            btnCapture.setEnabled(true);
                        }
                    })
                    .start();
        }
    }

    // ==================== 录入模式 ====================

    /**
     * 用户点击「拍照」按钮: 初始化 AddFaceDispose 并开始喂帧检测
     */
    private void onCaptureClick() {
        if (captureRequested || resultReturned) return;
        captureRequested = true;
        Log.d(TAG, "开始拍照检测, faceId=" + faceId);
        btnCapture.setEnabled(false);
        btnCapture.setText("检测中...");
        tvStatus.setText("正在检测...");

        // 初始化 AddFaceDispose (仅首次点击创建)
        initAddFaceDispose();

        // 超时处理: 8秒内未检测到有效人脸则允许重试
        if (captureTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(captureTimeoutRunnable);
        }
        captureTimeoutRunnable = () -> {
            if (resultReturned) return;
            captureRequested = false;
            faceDisposeActive = false;
            if (addFaceDispose != null) {
                addFaceDispose.release();
                addFaceDispose = null;
            }
            btnCapture.setEnabled(true);
            btnCapture.setText("重新拍照");
            tvStatus.setText("检测超时，请调整姿势后重试");
            Toast.makeText(this, "未检测到有效人脸，请调整姿势后重试", Toast.LENGTH_SHORT).show();
        };
        timeoutHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS);
    }

    private void initAddFaceDispose() {
        if (addFaceDispose != null) return;

        addFaceDispose = new AddFaceDispose(
                this,
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
                        runOnUiThread(() -> {
                            String tip = getEnrollTips(actionCode);
                            tvStatus.setText(tip);
                        });
                    }
                });
    }

    private void cancelCaptureTimeout() {
        if (captureTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(captureTimeoutRunnable);
            captureTimeoutRunnable = null;
        }
    }

    @OptIn(markerClass = {ExperimentalGetImage.class})
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
                // 使用 ImageProxy 的实际旋转角度，而非硬编码 270°
                // 前置摄像头 sensor 通常为 270°，再硬转 270° 会导致人脸倒置
                // 前置摄像头还需水平镜像：sensor 原始数据是非镜像的，但 SDK 期望镜像后人脸
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

        runOnUiThread(() -> {
            btnCapture.setEnabled(false);
            btnCapture.setText("处理中...");
            tvStatus.setText("提取特征中...");
        });

        try {
            FaceAiManager manager = FaceAiManager.getInstance();

            // 提取人脸特征
            String faceFeature = manager.extractFaceFeature(croppedBitmap);
            if (faceFeature == null || faceFeature.isEmpty()) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "特征提取失败,请重试", Toast.LENGTH_SHORT).show();
                    resultReturned = false;
                });
                return;
            }

            // 保存人脸特征到 1:N 搜索库
            manager.insertFaceFeature(faceId, faceFeature,
                    faceName != null ? faceName : faceId, "");

            // 返回结果
            Intent result = new Intent();
            result.putExtra(RESULT_FACE_ID, faceId);
            result.putExtra(RESULT_FACE_FEATURE, faceFeature);
            result.putExtra(RESULT_SCORE, silentLiveScore);
            setResult(RESULT_OK, result);

            runOnUiThread(() -> {
                tvStatus.setText("录入成功!");
                Toast.makeText(this, "人脸录入成功", Toast.LENGTH_SHORT).show();
                finish();
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                returnCancel();
            });
        }
    }

    // ==================== 搜索模式 ====================

    @OptIn(markerClass = {ExperimentalGetImage.class})
    private void processSearchFrame(ImageProxy imageProxy) {
        if (resultReturned) {
            imageProxy.close();
            return;
        }

        try {
            // 直接使用 ImageProxy 进行搜索
            FaceSearchEngine.getInstance().runSearchWithImageProxy(imageProxy, 0);
        } catch (Exception e) {
            // ignore
        } finally {
            imageProxy.close();
        }
    }

    private void initFaceSearch() {
        SearchProcessBuilder builder = new SearchProcessBuilder.Builder(this)
                .setThreshold(0.85f)                      // 匹配阈值
                .setNeedFaceLiveness(false)                // 活体检测
                .setSearchType(SearchProcessBuilder.SearchType.N_SEARCH_1)   // 1:N 搜索
                .setCallBackAllMatch(false)                // 只返回最相似的
                .setSearchIntervalTime(3000)               // 搜索间隔
                .setSearchTimeOut(15000)                   // 超时时间
                .setLifecycleOwner(this)
                .setProcessCallBack(new SearchProcessCallBack() {
                    @Override
                    public void onMostSimilar(String mostSimilarID, float score,
                                              Bitmap bitmap, float livenessValue) {
                        handleMatchResult(mostSimilarID, score);
                    }

                    @Override
                    public void onFaceDetected(List<FaceSearchResult> result) {
                        // 检测到人脸, 不需要特别处理
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
                        runOnUiThread(() -> {
                            String tip = getSearchTips(code);
                            tvStatus.setText(tip);
                        });
                    }

                    @Override
                    public void onLog(String msg) {
                        // 可选日志
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

        runOnUiThread(() -> {
            tvStatus.setText("识别成功: " + matchedFaceID + " (" + String.format("%.2f", score) + ")");
        });

        Intent result = new Intent();
        result.putExtra(RESULT_FACE_ID, matchedFaceID);
        result.putExtra(RESULT_SCORE, score);
        setResult(RESULT_OK, result);

        runOnUiThread(() -> {
            Toast.makeText(this, "识别: " + matchedFaceID, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    // ==================== 工具方法 ====================

    @OptIn(markerClass = {ExperimentalGetImage.class})
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

        // 偶发打印 stride 信息，用于诊断
        if (Math.random() < 0.05) {
            Log.d(TAG, String.format("YUV strides: Y(row=%d,pix=%d) U(row=%d,pix=%d) V(row=%d,pix=%d)",
                    yRowStride, yPixelStride, uRowStride, uPixelStride, vRowStride, vPixelStride));
        }

        int ySize = Math.min(width * height, yBuffer.remaining());
        byte[] nv21 = new byte[width * height * 3 / 2];

        // --- 拷贝 Y 通道 (逐行处理 stride) ---
        if (yPixelStride == 1 && yRowStride == width) {
            // 快速路径: 无 padding
            yBuffer.get(nv21, 0, ySize);
        } else {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, row * width, width);
            }
        }

        // --- 拷贝 UV 通道为 NV21 格式 (VUVUVU...) ---
        int uvOffset = width * height;
        int uvWidth = width / 2;
        int uvHeight = height / 2;

        // U/V buffer 可能指向同一块内存 (semi-planar), 需要按 pixelStride 读取
        uBuffer.rewind();
        vBuffer.rewind();

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvIndex = row * uRowStride + col * uPixelStride;
                int nvIndex = uvOffset + (row * width + col * 2);
                if (nvIndex + 1 < nv21.length && uvIndex < vBuffer.limit()) {
                    nv21[nvIndex] = vBuffer.get(uvIndex);     // V 在前 (NV21 = VUVU)
                }
                if (nvIndex + 1 < nv21.length && uvIndex < uBuffer.limit()) {
                    nv21[nvIndex + 1] = uBuffer.get(uvIndex); // U 在后
                }
            }
        }

        android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21, ImageFormat.NV21, width, height, null);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(
                new android.graphics.Rect(0, 0, width, height),
                100, out); // 最高质量，避免压缩损失
        byte[] imageBytes = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    /**
     * 旋转 + 水平镜像（适配前置摄像头）。
     * 前置摄像头 sensor 原始数据是非镜像的，
     * 但人眼习惯看镜像（类似照镜子），SDK 也期望镜像后人脸。
     */
    private Bitmap rotateAndMirrorBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        // 绕图像中心水平翻转
        matrix.postScale(-1, 1, source.getWidth() / 2f, source.getHeight() / 2f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private String getEnrollTips(int actionCode) {
        // 常见提示码映射 (来自 SDK)
        switch (actionCode) {
            case 0: return "未检测到人脸";
            case 1: return "请靠近一点";
            case 2: return "请离远一点";
            case 3: return "请正对摄像头";
            case 4: return "请勿遮挡面部";
            case 5: return "保持稳定";
            case 6: return "正在检测中...";
            case 7: return "请睁眼";
            case 8: return "请勿低头";
            case 9: return "检测中...";
            // 扩展码（从实际日志中观测到）
            case 30: return "请抬头";
            case 31: return "请稍向左转";
            case 32: return "请稍向右转";
            case 33: return "请保持正面";
            default: return "请正对摄像头(" + actionCode + ")"; // 带上码号方便诊断
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

    private void returnCancel() {
        if (resultReturned) return;
        resultReturned = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onResume() {
        super.onResume();
        if (MODE_SEARCH.equals(mode)) {
            initFaceSearch();
        } else {
            // 录入模式: 提前创建 AddFaceDispose 触发 ML Kit 模型加载
            // 用户点击拍照时模型已加载完成，无需等待 ~4 秒
            initAddFaceDispose();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MODE_SEARCH.equals(mode)) {
            FaceSearchEngine.getInstance().stopSearchProcess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelCaptureTimeout();
        if (addFaceDispose != null) {
            addFaceDispose.release();
            addFaceDispose = null;
        }
    }
}
