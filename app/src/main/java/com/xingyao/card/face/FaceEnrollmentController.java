package com.xingyao.card.face;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.ai.face.core.engine.FaceAISDKEngine;
import com.ai.face.base.addFace.AddFaceCallBack;
import com.ai.face.base.addFace.AddFaceDispose;
import com.ai.face.faceSearch.search.FaceSearchEngine;
import com.ai.face.faceSearch.search.SearchProcessBuilder;
import com.ai.face.faceSearch.search.SearchProcessCallBack;
import com.xingyao.card.face.FaceScanOverlayView;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 人脸录入 / 1:N 搜索控制器。
 *
 * 录入模式（混合自动/手动）：
 * <pre>
 *   WAITING → GUIDANCE → COUNTDOWN → CAPTURING → SUCCESS
 *                    ↘ FALLBACK   → CAPTURING → SUCCESS
 *                     ↘ FAILED
 * </pre>
 *
 * - 帧节流 600ms，仅用于引导（不再 30fps 洪水式喂帧）。
 * - FaceAISDK onProcessTips 连续 3 帧稳定后进入倒计时（3→2→1）。
 * - 倒计时间断人脸偏离则退回 GUIDANCE。
 * - 10s 内未完成自动采集 → FALLBACK，按钮可点击手动触发。
 * - 全局 60s 超时。
 */
public class FaceEnrollmentController {

    private static final String TAG = "FaceEnrollController";

    // ── 状态机 ──────────────────────────
    private enum State {
        WAITING,   // 等待人脸出现
        GUIDANCE,  // 检测到人脸，给出引导提示
        COUNTDOWN, // 连续稳定，倒计时 3→2→1
        CAPTURING, // 拍照中（等 FaceAISDK onCompleted）
        FALLBACK,  // 自动超时 → 降级手动
        SUCCESS,   // 录入成功
        FAILED     // 全局超时 / 异常
    }

    // ── 稳定参数 ────────────────────────
    private static final int STABILITY_FRAMES = 3;
    private static final int COUNTDOWN_SECONDS = 3;

    // ── 超时参数 ────────────────────────
    private static final long DISPOSE_THROTTLE_MS = 500L;   // dispose() 最小间隔
    private static final long AUTO_TIMEOUT_MS = 10_000L;   // 自动模式超时
    private static final long CAPTURE_TIMEOUT_MS = 15_000L; // 单次拍照超时（节流后帧少）
    private static final long GLOBAL_TIMEOUT_MS = 60_000L; // 全局超时
    static final long SDK_SEARCH_TIMEOUT_MS = 6_000L;

    // ── 模式 ────────────────────────────
    static final boolean MODE_ENROLL = true;
    static final boolean MODE_SEARCH = false;

    private final Activity activity;
    private final boolean isEnrollMode;
    private final String targetFaceId;
    private final String targetFaceName;
    private final FaceResultCallback resultCallback;

    // UI 引用
    private final TextView tvStatus;
    @Nullable private final TextView tvCountdown;
    private final Button btnCapture;
    private final Button btnCancel;

    // 人脸 AI
    private FaceAISDKEngine engine;
    private AddFaceDispose addFaceDispose;
    private SearchProcessCallBack searchCallBack;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // 状态变量 — processEnrollFrame(analysis线程)与主线程 handler 共享，必须 volatile
    private volatile State state = State.WAITING;
    private volatile boolean resultReturned = false;
    private int stabilityCounter = 0;
    private int countdownRemaining = COUNTDOWN_SECONDS;

    private int frameCount = 0;
    private long lastDisposeTime = 0;  // throttle barrier (elapsedRealtime)

    // 倒计时任务
    private Runnable countdownRunnable;
    private Runnable autoTimeoutRunnable;
    private Runnable captureTimeoutRunnable;
    private Runnable globalTimeoutRunnable;

    // ── 摄像头方向 ──────────────────────
    private boolean isFrontCamera = false;

    // ── 引导框 ──────────────────────────
    @Nullable private FaceScanOverlayView faceScanOverlay;

    // ── 特征提取器（JsBridgeV2 路径用于照片编码，新流程使用引擎提取特征）─
    @Nullable private final FaceFeatureExtractor featureExtractor;

    // ── 按模式区分的提示语映射 ─────────
    private static final java.util.Map<Integer, String> TIP_MAP = new java.util.HashMap<>();

    static {
        TIP_MAP.put( 0, "未检测到人脸");
        TIP_MAP.put( 1, "请正对摄像头");
        TIP_MAP.put( 2, "请勿遮挡面部");
        TIP_MAP.put( 3, "请靠近一点");
        TIP_MAP.put( 4, "请离远一点");
        TIP_MAP.put( 5, "保持稳定");
        TIP_MAP.put( 6, "正在检测中...");
        TIP_MAP.put( 7, "请睁眼");
        TIP_MAP.put( 8, "请勿低头");
        TIP_MAP.put( 9, "检测中...");
        TIP_MAP.put(10, "请稍向左转");
        TIP_MAP.put(11, "请稍向右转");
        TIP_MAP.put(12, "请稍微抬头");
        TIP_MAP.put(13, "请稍微低头");
        TIP_MAP.put(14, "请保持头部竖直");
        TIP_MAP.put(-10, "请保持不动");
        TIP_MAP.put(-7, "未检测到人脸");
        TIP_MAP.put(30, "请抬头");
        TIP_MAP.put(31, "请稍向左转");
        TIP_MAP.put(32, "请稍向右转");
        TIP_MAP.put(34, "处理中...");
    }

    // ── 构造函数 ────────────────────────

    /**
     * 主构造函数。
     */
    private FaceEnrollmentController(Activity activity, boolean isEnrollMode, String targetFaceId,
                                     String targetFaceName, TextView tvStatus,
                                     @Nullable TextView tvCountdown,
                                     Button btnCapture, Button btnCancel,
                                     FaceResultCallback resultCallback,
                                     @Nullable FaceFeatureExtractor featureExtractor) {
        this.activity = activity;
        this.isEnrollMode = isEnrollMode;
        this.targetFaceId = (targetFaceId != null) ? targetFaceId : "";
        this.targetFaceName = (targetFaceName != null) ? targetFaceName : "";
        this.tvStatus = tvStatus;
        this.tvCountdown = tvCountdown;
        this.btnCapture = btnCapture;
        this.btnCancel = btnCancel;
        this.resultCallback = resultCallback;
        this.featureExtractor = featureExtractor;
    }

    /**
     * 标准构造函数（MainActivity 内部调用）。
     */
    public FaceEnrollmentController(Activity activity, boolean isEnrollMode, String targetFaceId,
                                     String targetFaceName, TextView tvStatus,
                                     @Nullable TextView tvCountdown,
                                     Button btnCapture, Button btnCancel,
                                     FaceResultCallback resultCallback) {
        this(activity, isEnrollMode, targetFaceId, targetFaceName, tvStatus, tvCountdown,
                btnCapture, btnCancel, resultCallback, null);
    }

    /**
     * @deprecated 使用带 tvCountdown 的完整构造函数。
     */
    @Deprecated
    public FaceEnrollmentController(Activity activity, boolean isEnrollMode, String targetFaceId,
                                     String targetFaceName, TextView tvStatus,
                                     Button btnCapture, Button btnCancel,
                                     FaceResultCallback resultCallback) {
        this(activity, isEnrollMode, targetFaceId, targetFaceName, tvStatus, null,
                btnCapture, btnCancel, resultCallback, null);
    }

    /**
     * 兼容 JsBridgeV2 的扩展构造函数（无 tvCountdown）。
     * 新流程已内置节流、稳定检测和倒计时，旧参数保留兼容但不使用。
     */
    public FaceEnrollmentController(Activity activity, boolean isEnrollMode, String targetFaceId,
                                     String targetFaceName, TextView tvStatus,
                                     Button btnCapture, Button btnCancel,
                                     FaceResultCallback resultCallback,
                                     @Nullable FaceFeatureExtractor featureExtractor,
                                     float threshold, boolean cameraMirror,
                                     int cameraRotation, boolean needFaceLiveness,
                                     int searchIntervalTime, int searchTimeout, int captureTimeout) {
        this(activity, isEnrollMode, targetFaceId, targetFaceName, tvStatus, null,
                btnCapture, btnCancel, resultCallback, featureExtractor);
    }

    /**
     * 兼容 JsBridgeV2 的完整构造函数（带 tvCountdown + 额外参数）。
     */
    public FaceEnrollmentController(Activity activity, boolean isEnrollMode, String targetFaceId,
                                     String targetFaceName, TextView tvStatus,
                                     @Nullable TextView tvCountdown,
                                     Button btnCapture, Button btnCancel,
                                     FaceResultCallback resultCallback,
                                     @Nullable FaceFeatureExtractor featureExtractor,
                                     float threshold, boolean cameraMirror,
                                     int cameraRotation, boolean needFaceLiveness,
                                     int searchIntervalTime, int searchTimeout, int captureTimeout) {
        this(activity, isEnrollMode, targetFaceId, targetFaceName, tvStatus, tvCountdown,
                btnCapture, btnCancel, resultCallback, featureExtractor);
    }

    // ── 提示语工具 ──────────────────────

    /**
     * 根据 actionCode 返回提示文本；未知码返回 "提示码:XXX"。
     */
    static String tipForCode(int actionCode) {
        String tip = TIP_MAP.get(actionCode);
        return tip != null ? tip : "提示码:" + actionCode;
    }

    // ── 公共入口 ────────────────────────

    public void start() {
        if (resultReturned) return;
        resultReturned = false;
        state = State.WAITING;
        stabilityCounter = 0;
        countdownRemaining = COUNTDOWN_SECONDS;
        frameCount = 0;

        engine = FaceAISDKEngine.getInstance(activity.getApplicationContext());
        setupUI();

        // 先设置分析器（在绑定时已通过 dummy analyzer 激活管道，这里替换为真正处理逻辑），
        // 再初始化 FaceAI 模型。这样 FaceAI 模型加载期间帧管道已就绪，加载完成后首帧即可进入处理。
        ImageAnalysis.Analyzer analyzer = createFrameAnalyzer();
        ((com.xingyao.card.MainActivity) activity).setFaceAnalyzer(analyzer);

        initFaceAI();
        scheduleGlobalTimeout();
        Log.d(TAG, "start: mode=" + (isEnrollMode ? "ENROLL" : "SEARCH")
                + " faceId=" + targetFaceId);
    }

    public void stop() {
        cancelAllTimers();
        releaseFaceAI();
        try {
            ((com.xingyao.card.MainActivity) activity).clearFaceAnalyzer();
        } catch (Exception e) {
            Log.w(TAG, "clearFaceAnalyzer failed", e);
        }
        resultReturned = true;
        state = State.FAILED;
        Log.d(TAG, "stop");
    }

    public void setFaceScanOverlay(@Nullable FaceScanOverlayView overlay) {
        this.faceScanOverlay = overlay;
    }

    /**
     * 设置摄像头方向，用于决定录入帧是否需要水平镜像。
     * 前置摄像头 sensor 原始数据为自然方向，FaceAISDK 录入模型训练数据为镜像后图像，
     * 因此前置必须镜像；后置不需要。
     * （对齐旧 FaceEnrollmentActivity.rotateAndMirrorBitmap 已验证行为）
     */
    public void setFrontCamera(boolean front) {
        this.isFrontCamera = front;
        Log.d(TAG, "setFrontCamera: " + front);
    }

    /**
     * 录入帧的镜像规则由实际镜头朝向决定，与预览是否镜像无关。
     */
    public static boolean isFrontCameraFacing(String facing) {
        return "front".equalsIgnoreCase(facing);
    }

    /**
     * 相机配置兼容接口（新流程中相机已由 MainActivity 统一管理，此方法保留为空操作）。
     */
    public void applyCameraConfig(int cameraRotation, boolean cameraMirror) {
        // 相机配置已由 MainActivity 统一处理，无需额外操作
    }

    // ── UI 初始化 ───────────────────────

    private void setupUI() {
        timeoutHandler.post(() -> {
            if (isEnrollMode) {
                setTextSafe(tvStatus, "请正对摄像头");
                setVisibilitySafe(btnCapture, View.VISIBLE);
                setEnabledSafe(btnCapture, false); // 自动模式，按钮灰色
                setTextSafe(btnCapture, "自动采集中...");
            } else {
                // 搜索模式：不显示"拍照"按钮
                setVisibilitySafe(btnCapture, View.GONE);
            }
            if (tvCountdown != null) {
                setVisibilitySafe(tvCountdown, View.GONE);
            }
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setOnClickListener(v -> onCancelClick());
        });
    }

    // ── 人脸 AI 初始化 ──────────────────

    private void initFaceAI() {
        if (isEnrollMode) {
            initAddFaceDispose();
        } else {
            initFaceSearch();
        }
    }

    /**
     * 创建帧分析器，根据模式调用不同的帧处理方法。
     * 每帧都通过此分析器进入，由 processEnrollFrame / processSearchFrame 做节流和分发。
     */
    private ImageAnalysis.Analyzer createFrameAnalyzer() {
        return imageProxy -> {
            try {
                if (isEnrollMode) {
                    processEnrollFrame(imageProxy);
                } else {
                    processSearchFrame(imageProxy);
                }
            } catch (Exception e) {
                Log.e(TAG, "Frame analyzer error", e);
                try { imageProxy.close(); } catch (Exception ignored) {}
            }
        };
    }

    private void initAddFaceDispose() {
        Log.d(TAG, "initAddFaceDispose needConfirm=false PERF_ACCURATE");
        addFaceDispose = new AddFaceDispose(
                activity,
                AddFaceDispose.PERFORMANCE_MODE_ACCURATE,
                false, // needConfirm=false → SDK 自动完成录入并回调 onCompleted（对齐 FaceEnrollmentActivity 已验证行为）
                new AddFaceCallBack() {
                    @Override
                    public void onProcessTips(int actionCode) {
                        handleEnrollTips(actionCode);
                    }

                    @Override
                    public void onCompleted(Bitmap croppedBmp, float liveScore) {
                        Log.d(TAG, "AddFaceDispose.onCompleted(2-arg) fired, liveScore=" + liveScore);
                        handleEnrollCompleted(croppedBmp, liveScore);
                    }

                    @Override
                    public void onCompleted(Bitmap croppedBmp, float liveScore, Bitmap originBmp) {
                        Log.d(TAG, "AddFaceDispose.onCompleted(3-arg) fired, liveScore=" + liveScore);
                        handleEnrollCompleted(croppedBmp, liveScore);
                    }
                });
    }

    private void initFaceSearch() {
        Log.d(TAG, "initFaceSearch");
        searchCallBack = new SearchProcessCallBack() {
            @Override
            public void onProcessTips(int actionCode) {
                handleSearchTips(actionCode);
            }

            @Override
            public void onMostSimilar(String faceId, float score, Bitmap bitmap, float liveness) {
                handleSearchMatched(faceId, score, bitmap, liveness);
            }

            @Override
            public void onFaceMatched(List<com.ai.face.faceSearch.utils.FaceSearchResult> results,
                                      Bitmap bestFace, float bestScore) {
                if (results != null && !results.isEmpty()) {
                    com.ai.face.faceSearch.utils.FaceSearchResult r = results.get(0);
                    handleSearchMatched(r.getFaceName(), r.getFaceScore(), bestFace, bestScore);
                }
            }

            @Override
            public void onFaceDetected(List<com.ai.face.faceSearch.utils.FaceSearchResult> results) {
                // NO-OP
            }

            @Override
            public void onLog(String msg) {
                // NO-OP
            }
        };

        SearchProcessBuilder builder = new SearchProcessBuilder.Builder(activity)
                .setNeedFaceLiveness(true)
                .setSearchIntervalTime(600)
                // FaceAISDK 只接受 3000-6000ms 的单轮搜索超时。
                .setSearchTimeOut(SDK_SEARCH_TIMEOUT_MS)
                .setProcessCallBack(searchCallBack)
                .create();

        FaceSearchEngine.getInstance().initSearchParams(builder);
    }

    // ── 帧处理入口（节流）──────────────

    /**
     * 录入帧处理：500ms 节流喂给 dispose()，防止洪水式帧导致 SDK 内部队列溢出。
     * 未经节流的帧仅供预览引导展示，不喂给 SDK。
     */
    void processEnrollFrame(ImageProxy imageProxy) {
        if (resultReturned) {
            imageProxy.close();
            return;
        }
        if (addFaceDispose == null) {
            imageProxy.close();
            return;
        }

        Bitmap source = null;
        Bitmap transformed = null;
        boolean disposed = false;
        try {
            // 首次帧及每30帧打印一次，确认帧管道正常
            if (frameCount == 0 || frameCount % 30 == 0) {
                Log.d(TAG, "processEnrollFrame #" + frameCount + " state=" + state);
            }
            frameCount++;

            source = imageProxyToBitmap(imageProxy);
            if (source == null) {
                Log.w(TAG, "processEnrollFrame: imageProxyToBitmap returned null");
                imageProxy.close();
                return;
            }

            // 旋转 + 前置水平镜像。
            // 前置摄像头 sensor 原始数据为自然方向，FaceAISDK 录入模型在镜像后数据上训练，
            // 因此录入帧必须旋转+镜像才能触发 onCompleted（对齐 FaceEnrollmentActivity 已验证行为）。
            // 1:N 搜索使用 runSearchWithImageProxy 直接喂 ImageProxy，SDK 内部处理方向，
            // 所以识别不受此影响。
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            if (isFrontCamera) {
                matrix.postScale(-1, 1, source.getWidth() / 2f, source.getHeight() / 2f);
            }
            transformed = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);

            // 首次帧：打印关键调试信息（frameCount 在此处已递增，故用 == 1）
            if (frameCount == 1) {
                Log.d(TAG, "processEnrollFrame first frame: imageProxy w=" + imageProxy.getWidth()
                        + " h=" + imageProxy.getHeight() + " rotationDegrees=" + rotationDegrees
                        + " format=" + imageProxy.getFormat()
                        + " source w=" + source.getWidth() + " h=" + source.getHeight()
                        + " transformed w=" + transformed.getWidth() + " h=" + transformed.getHeight());
            }

            // 节流策略：GUIDANCE/COUNTDOWN 期间限频 500ms 避免 SDK 队列溢出；
            // CAPTURING 期间不限频喂足全部帧，对齐 FaceEnrollmentActivity 已验证的
            // 每帧 dispose 模式。进入 CAPTURING 时 AddFaceDispose 已重建为全新实例，
            // 以最快速度累积质量证据触发 onCompleted。
            long now = SystemClock.elapsedRealtime();
            boolean shouldDispose = (state == State.CAPTURING)
                    || (now - lastDisposeTime >= DISPOSE_THROTTLE_MS);
            if (shouldDispose) {
                lastDisposeTime = now;
                addFaceDispose.dispose(transformed);
                disposed = true;
            }
            // 否则跳过该帧（transformed 未被 SDK 持有，可在 finally 中安全回收）
        } catch (Exception e) {
            Log.e(TAG, "processEnrollFrame error", e);
        } finally {
            recycleBitmap(source);
            if (!disposed) {
                recycleBitmap(transformed);  // 未传给 SDK，安全回收
            }
            imageProxy.close();
        }
    }

    /**
     * 搜索帧处理：直接喂给 FaceSearchEngine（SDK 内置节流）。
     */
    void processSearchFrame(ImageProxy imageProxy) {
        if (resultReturned) {
            imageProxy.close();
            return;
        }
        try {
            FaceSearchEngine.getInstance().runSearchWithImageProxy(imageProxy, 0);
        } catch (Exception e) {
            Log.e(TAG, "processSearchFrame error", e);
        } finally {
            imageProxy.close();
        }
    }

    // ── 录入提示处理（状态机核心）──────

    private void handleEnrollTips(int actionCode) {
        timeoutHandler.post(() -> {
            if (resultReturned || state == State.SUCCESS || state == State.FAILED) {
                return;
            }
            // CAPTURING 期间：只记录日志方便诊断，不改变状态
            if (state == State.CAPTURING) {
                String tip = tipForCode(actionCode);
                Log.d(TAG, "enroll tip during CAPTURING: code=" + actionCode
                        + " tip=" + tip);
                return;
            }
            String tip = tipForCode(actionCode);
            Log.d(TAG, "enroll tip: code=" + actionCode + " tip=" + tip);

            // WAITING → GUIDANCE
            if (state == State.WAITING && !"未检测到人脸".equals(tip)) {
                enterGuidance();
            }

            boolean stable = isStableTip(tip);

            switch (state) {
                case GUIDANCE:
                    if (stable) {
                        stabilityCounter++;
                        Log.d(TAG, "stability counter: " + stabilityCounter);
                        if (stabilityCounter >= STABILITY_FRAMES) {
                            enterCountdown();
                            return;
                        }
                    } else {
                        stabilityCounter = 0;
                    }
                    setTextSafe(tvStatus, tip);
                    break;

                case COUNTDOWN:
                    if (!stable) {
                        // 人脸偏离 → 退回 GUIDANCE
                        exitCountdown();
                        setTextSafe(tvStatus, tip);
                    }
                    // 仍稳定 → 倒计时独立运行
                    break;

                case FALLBACK:
                    setTextSafe(tvStatus, "检测超时，请点击拍照");
                    break;

                default:
                    break;
            }
        });
    }

    // ── 稳定提示判定 ────────────────────

    static boolean isStableTip(String tip) {
        return "保持稳定".equals(tip)
                || "正在检测中...".equals(tip)
                || "检测中...".equals(tip)
                || "请保持不动".equals(tip)
                || "处理中...".equals(tip);
    }

    // ── 搜索提示处理 ────────────────────

    private void handleSearchTips(int actionCode) {
        timeoutHandler.post(() -> {
            if (resultReturned) return;
            String tip = tipForCode(actionCode);
            setTextSafe(tvStatus, tip);
        });
    }

    // ── 状态转换 ────────────────────────

    private void enterGuidance() {
        state = State.GUIDANCE;
        stabilityCounter = 0;
        cancelAutoTimeout();

        // 自动超时定时器（10s）
        autoTimeoutRunnable = () -> {
            if (state == State.GUIDANCE || state == State.COUNTDOWN) {
                enterFallback();
            }
        };
        timeoutHandler.postDelayed(autoTimeoutRunnable, AUTO_TIMEOUT_MS);
        Log.d(TAG, "→ GUIDANCE (auto timeout: 10s)");
    }

    private void enterCountdown() {
        cancelAutoTimeout();
        state = State.COUNTDOWN;
        countdownRemaining = COUNTDOWN_SECONDS;
        stabilityCounter = 0; // 重置，倒计时内仍需监控

        setTextSafe(tvStatus, "请保持不动");
        if (tvCountdown != null) {
            setTextSafe(tvCountdown, String.valueOf(COUNTDOWN_SECONDS));
            setVisibilitySafe(tvCountdown, View.VISIBLE);
        }
        Log.d(TAG, "→ COUNTDOWN (3→2→1)");

        scheduleCountdownTick();
    }

    private void exitCountdown() {
        state = State.GUIDANCE;
        stabilityCounter = 0;
        cancelCountdown();
        if (tvCountdown != null) {
            setVisibilitySafe(tvCountdown, View.GONE);
        }
        // 重新启动自动超时
        cancelAutoTimeout();
        autoTimeoutRunnable = () -> {
            if (state == State.GUIDANCE || state == State.COUNTDOWN) {
                enterFallback();
            }
        };
        timeoutHandler.postDelayed(autoTimeoutRunnable, AUTO_TIMEOUT_MS);
        Log.d(TAG, "← GUIDANCE (countdown interrupted)");
    }

    private void scheduleCountdownTick() {
        cancelCountdown();
        countdownRunnable = () -> {
            if (state != State.COUNTDOWN || resultReturned) {
                cancelCountdown();
                return;
            }

            if (countdownRemaining > 0) {
                if (tvCountdown != null) {
                    setTextSafe(tvCountdown, String.valueOf(countdownRemaining));
                }
                setTextSafe(tvStatus, "请保持不动 · " + countdownRemaining);
                countdownRemaining--;
                timeoutHandler.postDelayed(countdownRunnable, 1000);
            } else {
                // 倒计时结束 → 进入拍照
                enterCapturing();
            }
        };
        timeoutHandler.postDelayed(countdownRunnable, 1000);
    }

    private void enterCapturing() {
        cancelCountdown();
        cancelAutoTimeout();
        cancelCaptureTimeout();
        state = State.CAPTURING;

        setTextSafe(tvStatus, "正在拍照...");
        setEnabledSafe(btnCapture, false);
        setTextSafe(btnCapture, "拍照中...");
        if (tvCountdown != null) {
            setVisibilitySafe(tvCountdown, View.GONE);
        }

        // 每次进入 CAPTURING 都创建全新 AddFaceDispose 实例。
        // FaceEnrollmentActivity 验证行为：每次拍照尝试都 release 旧实例后创建新实例，
        // 避免同一实例长时间使用后 SDK 内部质量累加器错乱导致 onCompleted 永不触发。
        AddFaceDispose oldDispose = addFaceDispose;
        addFaceDispose = null;
        if (oldDispose != null) {
            oldDispose.release();
        }
        initAddFaceDispose();

        Log.d(TAG, "→ CAPTURING");

        // 拍照超时（3s 内 FaceAISDK onCompleted 必须返回）
        captureTimeoutRunnable = () -> {
            if (state == State.CAPTURING && !resultReturned) {
                Log.w(TAG, "capture timeout, retry");
                setTextSafe(tvStatus, "拍照超时，请重试");
                // 回退到 GUIDANCE，给一次重试机会
                cancelCaptureTimeout();
                state = State.GUIDANCE;
                stabilityCounter = 0;
                if (btnCapture.getVisibility() == View.VISIBLE) {
                    setEnabledSafe(btnCapture, false);
                    setTextSafe(btnCapture, "自动采集中...");
                }
            }
        };
        timeoutHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS);
    }

    private void enterFallback() {
        cancelCountdown();
        cancelAutoTimeout();
        state = State.FALLBACK;

        setTextSafe(tvStatus, "检测超时，请点击拍照");
        setEnabledSafe(btnCapture, true);
        setTextSafe(btnCapture, "点击拍照");
        if (tvCountdown != null) {
            setVisibilitySafe(tvCountdown, View.GONE);
        }
        // 设置手动按钮点击
        btnCapture.setOnClickListener(v -> {
            if (state == State.FALLBACK && !resultReturned) {
                cancelAllTimersExceptGlobal();
                enterCapturing();
            }
        });
        Log.d(TAG, "→ FALLBACK (manual capture enabled)");
    }

    // ── 录入完成处理 ────────────────────

    private void handleEnrollCompleted(Bitmap croppedBmp, float liveScore) {
        timeoutHandler.post(() -> {
            if (resultReturned) return;
            // onCompleted 可在任意状态触发（GUIDANCE/COUNTDOWN 阶段 SDK 已可能自动拍照）
            if (state == State.SUCCESS || state == State.FAILED) {
                Log.w(TAG, "onCompleted in terminal state: " + state + ", ignored");
                return;
            }
            Log.d(TAG, "onCompleted accepted from state: " + state);
            cancelAllTimers();
            state = State.SUCCESS;
            resultReturned = true;

            try {
                // 通过特征提取器编码照片（JsBridgeV2 路径依赖此副作用保存 enrollmentPhotoBase64）
                if (featureExtractor != null && croppedBmp != null) {
                    featureExtractor.extract(croppedBmp);
                }
                // 使用引擎提取特征
                String feature = engine.croppedBitmap2Feature(croppedBmp);
                setTextSafe(tvStatus, "录入成功!");
                setEnabledSafe(btnCapture, false);
                setTextSafe(btnCapture, "录入成功");
                Log.d(TAG, "enroll success, feature length="
                        + (feature != null ? feature.length() : 0));

                if (resultCallback != null) {
                    resultCallback.onFaceEnrolled(targetFaceId, feature, liveScore);
                }
            } catch (Exception e) {
                Log.e(TAG, "feature extraction failed", e);
                setTextSafe(tvStatus, "特征提取失败");
                if (resultCallback != null) {
                    resultCallback.onCancelled();
                }
            } finally {
                releaseFaceAI();
            }
        });
    }

    // ── 搜索匹配处理 ────────────────────

    private void handleSearchMatched(String faceId, float score, Bitmap bitmap, float liveness) {
        timeoutHandler.post(() -> {
            if (resultReturned) return;
            cancelAllTimers();
            resultReturned = true;

            setTextSafe(tvStatus, "识别完成");
            Log.d(TAG, "search matched: faceId=" + faceId + " score=" + score
                    + " liveness=" + liveness);

            if (resultCallback != null) {
                resultCallback.onFaceVerified(faceId, Math.max(score, liveness));
            }
            releaseFaceAI();
        });
    }

    // ── 按钮回调 ────────────────────────

    private void onCancelClick() {
        if (resultReturned) return;
        Log.d(TAG, "onCancelClick");
        cancelAllTimers();
        state = State.FAILED;
        resultReturned = true;
        releaseFaceAI();

        if (resultCallback != null) {
            resultCallback.onCancelled();
        }
    }

    // ── 超时管理 ────────────────────────

    private void scheduleGlobalTimeout() {
        cancelGlobalTimeout();
        globalTimeoutRunnable = () -> {
            if (resultReturned) return;
            Log.w(TAG, "global timeout (60s)");
            state = State.FAILED;
            resultReturned = true;
            releaseFaceAI();
            setTextSafe(tvStatus, "录入超时，请重试");
            setEnabledSafe(btnCapture, true);
            setTextSafe(btnCapture, "重试");
            if (tvCountdown != null) {
                setVisibilitySafe(tvCountdown, View.GONE);
            }
            if (resultCallback != null) {
                resultCallback.onCancelled();
            }
        };
        timeoutHandler.postDelayed(globalTimeoutRunnable, GLOBAL_TIMEOUT_MS);
    }

    private void cancelAllTimers() {
        cancelCountdown();
        cancelAutoTimeout();
        cancelCaptureTimeout();
        cancelGlobalTimeout();
    }

    private void cancelAllTimersExceptGlobal() {
        cancelCountdown();
        cancelAutoTimeout();
        cancelCaptureTimeout();
    }

    private void cancelCountdown() {
        if (countdownRunnable != null) {
            timeoutHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void cancelAutoTimeout() {
        if (autoTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(autoTimeoutRunnable);
            autoTimeoutRunnable = null;
        }
    }

    private void cancelCaptureTimeout() {
        if (captureTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(captureTimeoutRunnable);
            captureTimeoutRunnable = null;
        }
    }

    private void cancelGlobalTimeout() {
        if (globalTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(globalTimeoutRunnable);
            globalTimeoutRunnable = null;
        }
    }

    // ── 资源释放 ────────────────────────

    private void releaseFaceAI() {
        if (addFaceDispose != null) {
            addFaceDispose.release();
            addFaceDispose = null;
        }
        if (!isEnrollMode) {
            FaceSearchEngine.getInstance().stopSearchProcess();
        }
        searchCallBack = null;
        Log.d(TAG, "face AI released");
    }

    // ── 图片工具 ────────────────────────

    @Nullable
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        int format = imageProxy.getFormat();
        if (format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "不支持的图像格式: " + format + ", 仅支持 YUV_420_888");
            return null;
        }

        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes == null || planes.length < 3) return null;
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

        byte[] nv21 = new byte[width * height * 3 / 2];

        // --- 拷贝 Y 通道（逐行处理 stride）---
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, width * height);
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

        uBuffer.rewind();
        vBuffer.rewind();

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

        // JPEG 管线：与 FaceEnrollmentActivity 完全一致。
        // FaceEnrollmentActivity 使用此管线已验证 onCompleted 可正常触发。
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 100, out);
        byte[] jpegBytes = out.toByteArray();
        try { out.close(); } catch (Exception ignored) {}
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }

    @Nullable
    private Bitmap createGuideFrame(Bitmap source) {
        if (source == null) return null;
        if (faceScanOverlay == null) return source;

        try {
            int srcW = source.getWidth();
            int srcH = source.getHeight();
            int overlayW = faceScanOverlay.getWidth();
            int overlayH = faceScanOverlay.getHeight();
            if (overlayW <= 0 || overlayH <= 0) return source;

            // 从 overlay 的 faceBounds 推算引导区域：inset 9dp 去除边距
            float density = faceScanOverlay.getResources().getDisplayMetrics().density;
            float insetPx = 9f * density;
            float guideLeft = insetPx;
            float guideTop = insetPx;
            float guideRight = overlayW - insetPx;
            float guideBottom = overlayH - insetPx;

            // overlay→图片坐标映射（横屏摄像头竖屏 overlay，需旋转交换）
            float scaleX = (float) srcH / overlayW;
            float scaleY = (float) srcW / overlayH;

            int cropX = Math.max(0, (int) (guideLeft * scaleX));
            int cropY = Math.max(0, (int) (guideTop * scaleY));
            int cropW = Math.min(srcW - cropX, (int) ((guideRight - guideLeft) * scaleX));
            int cropH = Math.min(srcH - cropY, (int) ((guideBottom - guideTop) * scaleY));
            if (cropW <= 0 || cropH <= 0) return source;

            Bitmap cropped = Bitmap.createBitmap(source, cropX, cropY, cropW, cropH);
            Bitmap result = Bitmap.createBitmap(cropped.getWidth(), cropped.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 椭圆蒙版
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawOval(0, 0, cropped.getWidth(), cropped.getHeight(), paint);
            paint.setXfermode(null);
            canvas.drawBitmap(cropped, 0, 0, paint);
            recycleBitmap(cropped);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "createGuideFrame error", e);
            return source;
        }
    }

    private static void recycleBitmap(@Nullable Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ── 线程安全 UI 操作 ────────────────

    private void setTextSafe(TextView view, String text) {
        if (view != null) view.setText(text);
    }

    private void setEnabledSafe(Button button, boolean enabled) {
        if (button != null) button.setEnabled(enabled);
    }

    private void setVisibilitySafe(View view, int visibility) {
        if (view != null) view.setVisibility(visibility);
    }

    // ── 外部回调接口 ────────────────────

    public interface FaceResultCallback {
        /** 录入成功：faceId，特征值，活体分数 */
        void onFaceEnrolled(String faceId, String faceFeature, float score);
        /** 识别/核验成功：匹配到的 faceId，分数 */
        void onFaceVerified(String faceId, float score);
        /** 取消或异常 */
        void onCancelled();
    }

}
