package com.xingyao.card;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.SurfaceRequest;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import com.xingyao.card.face.FaceEnrollmentController;
import com.xingyao.card.service.DeviceCoreService;
import com.ai.face.faceSearch.search.FaceSearchEngine;
import com.ai.face.core.engine.FaceAISDKEngine;
import com.xingyao.card.webview.ErrorHandler;
import com.xingyao.card.webview.JsBridgeV2;
import com.xingyao.card.webview.LocalHttpServer;
import com.xingyao.card.webview.WebViewManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int LOCAL_HTTP_PORT = 8088;
    private static final int REQUEST_CAMERA = 4201;
    private static boolean sClassesPrewarmed = false;
    private static boolean sCameraXConfigured = false;

    private WebViewManager webViewManager;
    private JsBridgeV2 jsBridge;
    private ErrorHandler errorHandler;
    private RelativeLayout loadingLayout;
    private RelativeLayout errorLayout;
    private FrameLayout webViewContainer;
    private Button retryButton;
    private LocalHttpServer localHttpServer;
    private String pendingFaceEnrollmentRequestId;
    private String pendingFingerprintRequestId;
    private String pendingFingerprintOperation;
    private int fingerprintSessionId;
    private BiometricPrompt activeBiometricPrompt;
    private CancellationSignal activeFingerprintCancellationSignal;

    // 常驻相机：始终在 face_overlay 内的 TextureView 上运行，通过 setFaceAnalyzer 切换帧处理。
    // TextureView（而非 PreviewView）可保证 setTransform(Matrix) 在所有设备上生效，
    // 包括不再回退到 SurfaceView 孔窗的设备。
    private TextureView mCameraPreviewView;
    private ProcessCameraProvider mCameraProvider;
    private SurfaceRequest mPendingSurfaceRequest;
    private final ExecutorService mCameraAnalysisExecutor = Executors.newSingleThreadExecutor();
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Log.d(TAG, "TextureView SurfaceTexture available: " + width + "x" + height);
            synchronized (MainActivity.this) {
                if (mPendingSurfaceRequest != null) {
                    providePreviewSurface(mPendingSurfaceRequest, surfaceTexture);
                    mPendingSurfaceRequest = null;
                }
            }
            applyFacePreviewMirror();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            applyFacePreviewMirror();
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            Log.d(TAG, "TextureView SurfaceTexture destroyed");
            return true;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // 不在每帧回调中重新应用 mirror，避免不必要的变换重置
        }
    };
    private Preview mCameraPreview;
    private ImageAnalysis mCameraAnalysis;
    private String faceCameraFacing = "front";
    private boolean faceCameraMirror = true;
    private int faceCameraRotation = 0;
    private int faceCameraFrameWidth = 640;
    private int faceCameraFrameHeight = 480;
    private Size mCameraPreviewResolution;

    // 人脸操作 UI（face_overlay 容器 + 控制器）
    private FrameLayout faceOverlay;
    private TextView tvFaceStatus;
    private TextView tvFaceCountdown;
    private Button btnFaceCapture, btnFaceCancel;
    private FaceEnrollmentController faceController;
    private boolean faceOverlayActive;

    private static final String TAG ="MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate START");
        configureFullscreen();
        setContentView(R.layout.activity_main);
        mCameraPreviewView = findViewById(R.id.camera_preview);
        mCameraPreviewView.setOpaque(false);
        mCameraPreviewView.setSurfaceTextureListener(mSurfaceTextureListener);
        Log.d(TAG, "onCreate startDeviceCoreService...");
        startDeviceCoreService();
        initViews();
        Log.d(TAG, "onCreate initManagers...");
        initManagers();
        Log.d(TAG, "onCreate prewarmCameraX...");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            prewarmCameraX();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
        Log.d(TAG, "onCreate prewarmFaceAI...");
        prewarmFaceAI();
        Log.d(TAG, "onCreate startLocalHttpServer...");
        startLocalHttpServer();
        Log.d(TAG, "onCreate loadUniApp...");
        loadUniApp();
        Log.d(TAG, "onCreate END");
    }

    private void configureFullscreen() {
        getWindow().setStatusBarColor(0xFF1F76FF);
        getWindow().setNavigationBarColor(0xFF1F76FF);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void startDeviceCoreService() {
        Intent serviceIntent = new Intent(this, DeviceCoreService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    /**
     * 启动设备核心前台 Service（串口、FaceAI 引擎初始化）。
     * MQTT 连接由 Vue 端通过 JsBridgeV2 bootstrap 通道按需建立。
     */

    /**
     * 预配置 CameraX + 后台预初始化（必须在首次 getInstance 之前调用）。
     *
     * P0 v9: 后台预加载 CameraX + ML Kit + Camera2 Session + GMS + Coroutines 关键类，
     *     消除 CameraX 初始化路径上的 ART Dex 验证卡顿。
     *     - v1: CameraX 12 类 (~300ms)
     *     - v2: ML Kit zzix/zzhw/zzvz (~2.5s) → bind 从 2661ms→1675ms
     *     - v3: Camera2Session 内部类 + GMS + coroutines (~1.2s)
     *     - v4: 打地鼠 4 类 (~1.3s) → 已验证消除 ✅
     *     - v5: 打地鼠 2 类 (~329ms) → 已验证消除 ✅
     *     - v6: 打地鼠 2 类 (~866ms) → 已验证消除 ✅, 总计 7872ms(最佳)
     *     - v7: 收尾 2 类 (~448ms) → 已验证消除 ✅, 总计 9030ms(GC噪声)
     *     - v8: 打地鼠 3 类 (~387ms) → 已验证消除 ✅, 总计 8798ms(GC噪声)
     *     - v9: 打地鼠 2 类 (zzcy + zzun, ~286ms)
     *
     * P2: 后台预初始化 ProcessCameraProvider，让 CameraValidator 重试（4 次 × ~1.6s
     *     = ~6.5s）在 App 冷启动时后台完成。用户导航到人脸页面时 getInstance() 秒返。
     *     已验证：getInstance 从 6557ms→8ms（省 99.8%）。
     */
    private void prewarmCameraX() {
        configureCameraXOnce();

        // P0: 后台预加载 CameraX + ML Kit 关键类（一次性，消除主线程类加载卡顿）
        if (!sClassesPrewarmed) {
            sClassesPrewarmed = true;
            Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.d(TAG, "预加载 CameraX 关键类开始...");
                long start = System.currentTimeMillis();

                // CameraSelector + 内部类（addCameraFilter 回调所依赖的 CameraInfo）
                Class.forName("androidx.camera.core.CameraSelector");
                Class.forName("androidx.camera.core.CameraSelector$Builder");
                Class.forName("androidx.camera.core.CameraInfo");

                // Preview + 内部类 + SurfaceRequest（setSurfaceProvider 路径）
                Class.forName("androidx.camera.core.Preview");
                Class.forName("androidx.camera.core.Preview$Builder");
                Class.forName("androidx.camera.core.SurfaceRequest");

                // ImageAnalysis + 内部类 + Analyzer 接口
                Class.forName("androidx.camera.core.ImageAnalysis");
                Class.forName("androidx.camera.core.ImageAnalysis$Builder");
                Class.forName("androidx.camera.core.ImageAnalysis$Analyzer");

                // ImageProxy（analyzer 回调参数）
                Class.forName("androidx.camera.core.ImageProxy");

                // SafeCloseImageReaderProxy 等 bindToLifecycle 内部路径类
                Class.forName("androidx.camera.core.ImageCapture");

                // ML Kit 人脸检测关键类（bindCameraUseCases 时触发 zzix.configure 验证 ~2.5s）
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzix");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzhw");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face_bundled.zzvz");

                // P0 v3: P2 后 bind 阶段新出现的 ART 验证类（合计 ~2.4s）
                // - Camera2SessionOptionUnpacker.unpack         316ms
                // - ConfigurationCompat.getLocales              330ms
                // - BaseGmsClient.getRemoteService              200ms
                // - zabq.zav (GMS internal)                     124ms
                // - MainDispatcherLoader.loadMainDispatcher()   114ms
                // - zzoe.<init> (ML Kit face)                   113ms
                // - SynchronizedCaptureSessionBaseImpl          121ms
                // - zzgh.configure (ML Kit common)             1230ms (analyzer 路径)
                Class.forName("androidx.camera.camera2.internal.Camera2SessionOptionUnpacker");
                Class.forName("androidx.camera.camera2.internal.SynchronizedCaptureSessionBaseImpl");
                Class.forName("androidx.core.os.ConfigurationCompat");
                Class.forName("com.google.android.gms.common.internal.BaseGmsClient");
                Class.forName("com.google.android.gms.common.api.internal.zabq");
                Class.forName("kotlinx.coroutines.internal.MainDispatcherLoader");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzoe");
                Class.forName("com.google.android.gms.internal.mlkit_vision_common.zzgh");

                // P0 v4: v3 消除旧验证后，代码路径变更暴露的新类（合计 ~1.3s）
                // - zzkt.<clinit> (ML Kit face)                 459ms
                // - zzs.zza (GMS common)                        650ms
                // - zzb.handleMessage (GMS common)              124ms
                // - Config.mergeOptionValue (CameraX core)        109ms
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzkt");
                Class.forName("com.google.android.gms.common.internal.zzs");
                Class.forName("com.google.android.gms.common.internal.zzb");
                Class.forName("androidx.camera.core.impl.Config");

                // P0 v5: v4 消除旧验证后，代码路径变更暴露的新类（合计 ~329ms）
                // - AbstractResolvableFuture$Failure.<clinit>()    107ms (concurrent.futures)
                // - Camera2CameraCaptureResult.getAeMode()         222ms (camera2 capture result)
                Class.forName("androidx.concurrent.futures.AbstractResolvableFuture$Failure");
                Class.forName("androidx.camera.camera2.internal.Camera2CameraCaptureResult");

                // P0 v6: v5 消除旧验证后，代码路径变更暴露的新类（合计 ~866ms）
                // - zaad.<init>() (GMS api internal)               103ms (后台线程, 回主线程延迟阶段)
                // - zaad.zac()  (GMS api internal)                 270ms (后台线程, 回主线程延迟阶段)
                // - zzcw.zzk()  (ML Kit face encoder)              181ms (后台线程, 首帧后)
                // - zzcw.zzb()  (ML Kit face encoder)              312ms (后台线程, 首帧后)
                Class.forName("com.google.android.gms.common.api.internal.zaad");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzcw");

                // P0 v7: v6 消除旧验证后，代码路径变更暴露的收尾类（合计 ~448ms）
                // - ConnectionTracker.<clinit>() (GMS common stats)  281ms (后台线程 2187, 回主线程延迟阶段)
                // - zzew.<clinit>() (ML Kit face)                     167ms (后台线程 2181, camera open 阶段)
                Class.forName("com.google.android.gms.common.stats.ConnectionTracker");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzew");

                // P0 v8: v7 消除旧验证后，代码路径变更暴露的收尾类（合计 ~387ms）
                // - ClientSettings.getApplicableScopes() (GMS common)     134ms (后台线程 3774, 回主线程延迟阶段)
                // - AutoBatchedLogRequestEncoder.configure() (datatransport) 151ms (后台线程 3772, 首帧后)
                // - zzea.<clinit>() (ML Kit vision common)                 102ms (后台线程 3773, 首帧后)
                Class.forName("com.google.android.gms.common.internal.ClientSettings");
                Class.forName("com.google.android.datatransport.cct.internal.AutoBatchedLogRequestEncoder");
                Class.forName("com.google.android.gms.internal.mlkit_vision_common.zzea");

                // P0 v9: v8 消除后代码路径变更暴露的收尾类（合计 ~286ms）
                // - zzcy.<clinit>() (ML Kit vision face)              165ms (后台线程 5262, bind 阶段)
                // - zzun.<clinit>() (ML Kit vision face bundled)      121ms (后台线程 5332, 首帧后)
                Class.forName("com.google.android.gms.internal.mlkit_vision_face.zzcy");
                Class.forName("com.google.android.gms.internal.mlkit_vision_face_bundled.zzun");

                Log.d(TAG, "预加载 CameraX + ML Kit 关键类完成, 耗时 " + (System.currentTimeMillis() - start) + "ms");
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "预加载类失败: " + e.getMessage());
            }
        });
        } // end if (!sClassesPrewarmed)

        // P2: 后台初始化 ProcessCameraProvider + 预绑定摄像头。
        // face_overlay 声明在 webview_container 之前（z-order 底层）且始终 VISIBLE，
        // TextureView SurfaceTexture 就绪，绑定不会超时。摄像头预热运行但被 WebView 遮挡，
        // 人脸识别触发时只需 bringToFront，画面即时可用。
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.d(TAG, "P2 初始化 CameraProvider 开始（CameraValidator 重试在后台完成）...");
                long start = System.currentTimeMillis();
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Log.d(TAG, "P2 初始化 CameraProvider 完成, 耗时 " + (System.currentTimeMillis() - start) + "ms");

                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        bindPersistentCamera(provider);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "P2 初始化 CameraProvider 失败: " + e.getMessage());
            }
        });
    }

    private static synchronized void configureCameraXOnce() {
        if (sCameraXConfigured) return;

        CameraXConfig config = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .build();
        try {
            ProcessCameraProvider.configureInstance(config);
            Log.d(TAG, "CameraX Camera2Config 已配置");
        } catch (IllegalStateException alreadyConfigured) {
            Log.d(TAG, "CameraX 已配置，复用现有 CameraProvider");
        }
        sCameraXConfigured = true;
    }

    /**
     * 常驻绑定摄像头到 face_overlay 容器内的 PreviewView，摄像头始终运行。
     * FaceEnrollmentController 通过 setFaceAnalyzer / clearFaceAnalyzer 切换帧处理逻辑。
     */
    private void bindPersistentCamera(ProcessCameraProvider provider) {
        if (isFinishing()) return;
        try {
            mCameraProvider = provider;

            CameraSelector cameraSelector = "back".equals(faceCameraFacing)
                    ? CameraSelector.DEFAULT_BACK_CAMERA
                    : CameraSelector.DEFAULT_FRONT_CAMERA;

            mCameraPreview = new Preview.Builder()
                    .setTargetRotation(toSurfaceRotation(faceCameraRotation))
                    .build();
            mCameraPreview.setSurfaceProvider(createPreviewSurfaceProvider());

            mCameraAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(new android.util.Size(faceCameraFrameWidth, faceCameraFrameHeight))
                    .setTargetRotation(toSurfaceRotation(faceCameraRotation))
                    .build();

            // 必须设置虚拟分析器以确保 CameraX 在绑定时启动 ImageAnalysis 帧管道。
            // 如果 ImageAnalysis 绑定时不带 analyzer，后续通过 setAnalyzer() 设置可能不会
            // 正确激活底层图像读取器，导致帧永远无法进入分析器。
            mCameraAnalysis.setAnalyzer(mCameraAnalysisExecutor,
                    imageProxy -> imageProxy.close());

            provider.unbindAll();
            try {
                provider.bindToLifecycle(this, cameraSelector, mCameraPreview, mCameraAnalysis);
            } catch (IllegalArgumentException noRequestedCamera) {
                Log.w(TAG, "Requested camera unavailable, falling back to first available camera: "
                        + faceCameraFacing);
                CameraSelector fallbackSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> {
                            if (cameraInfos == null || cameraInfos.isEmpty())
                                return java.util.Collections.emptyList();
                            return java.util.Collections.singletonList(cameraInfos.get(0));
                        })
                        .build();
                provider.bindToLifecycle(this, fallbackSelector, mCameraPreview, mCameraAnalysis);
            }
            Log.d(TAG, "Camera bound persistently to fullscreen TextureView");
            applyFacePreviewMirror();
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind persistent camera", e);
        }
    }

    /**
     * 创建 CameraX Preview 的 SurfaceProvider，直接使用 TextureView 的 SurfaceTexture 创建 Surface。
     * TextureView（非 PreviewView）让我们能通过 setTransform(Matrix) 可靠控制镜像变换，
     * 不会受 PreviewView 内部 SurfaceView 孔窗模式的限制。
     */
    private Preview.SurfaceProvider createPreviewSurfaceProvider() {
        return request -> {
            SurfaceTexture surfaceTexture = mCameraPreviewView.getSurfaceTexture();
            if (surfaceTexture != null) {
                providePreviewSurface(request, surfaceTexture);
            } else {
                Log.d(TAG, "SurfaceTexture not ready, deferring SurfaceRequest");
                synchronized (MainActivity.this) {
                    mPendingSurfaceRequest = request;
                }
            }
        };
    }

    private void providePreviewSurface(SurfaceRequest request, SurfaceTexture surfaceTexture) {
        Size resolution = request.getResolution();
        surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
        mCameraPreviewResolution = resolution;
        updateFacePreviewLayout();
        Surface surface = new Surface(surfaceTexture);
        Log.d(TAG, "Providing Surface for camera preview: " + resolution.getWidth()
                + "x" + resolution.getHeight());
        request.provideSurface(surface,
                ContextCompat.getMainExecutor(MainActivity.this),
                result -> {
                    Log.d(TAG, "Camera Surface released, code=" + result.getResultCode());
                    surface.release();
                });
    }

    private void updateFacePreviewLayout() {
        if (mCameraPreviewView == null || mCameraPreviewResolution == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mCameraPreviewView.getLayoutParams();
        params.width = mCameraPreviewResolution.getWidth();
        params.height = mCameraPreviewResolution.getHeight();
        params.gravity = android.view.Gravity.CENTER;
        mCameraPreviewView.setLayoutParams(params);
        mCameraPreviewView.post(this::applyFacePreviewMirror);
    }

    public void configureFaceCamera(String facing, boolean mirror, int rotation, int frameWidth, int frameHeight) {
        String normalizedFacing = "back".equalsIgnoreCase(facing) ? "back" : "front";
        int normalizedRotation = normalizeCameraRotation(rotation);
        int normalizedWidth = frameWidth > 0 ? frameWidth : 640;
        int normalizedHeight = frameHeight > 0 ? frameHeight : 480;
        boolean changed = !normalizedFacing.equals(faceCameraFacing)
                || mirror != faceCameraMirror
                || normalizedRotation != faceCameraRotation
                || normalizedWidth != faceCameraFrameWidth
                || normalizedHeight != faceCameraFrameHeight;
        faceCameraFacing = normalizedFacing;
        faceCameraMirror = mirror;
        faceCameraRotation = normalizedRotation;
        faceCameraFrameWidth = normalizedWidth;
        faceCameraFrameHeight = normalizedHeight;
        applyFacePreviewMirror();
        // prewarmCameraX 已预绑定，此处仅在参数变更或兜底（prewarm 失败的极端情况）时重新绑定
        boolean needsFirstBind = mCameraPreview == null;
        if ((changed || needsFirstBind) && mCameraProvider != null) {
            bindPersistentCamera(mCameraProvider);
        }
    }

    private void applyFacePreviewMirror() {
        if (mCameraPreviewView == null) return;
        Matrix matrix = new Matrix();
        int w = mCameraPreviewView.getWidth();
        int h = mCameraPreviewView.getHeight();
        if (w <= 0 || h <= 0) return;
        matrix.setScale(faceCameraMirror ? -1f : 1f, 1f, w / 2f, 0f);
        mCameraPreviewView.setTransform(matrix);
        int containerWidth = mCameraPreviewView.getParent() instanceof View
                ? ((View) mCameraPreviewView.getParent()).getWidth() : w;
        int containerHeight = mCameraPreviewView.getParent() instanceof View
                ? ((View) mCameraPreviewView.getParent()).getHeight() : h;
        float scale = calculateCenterCropScale(containerWidth, containerHeight, w, h);
        mCameraPreviewView.setPivotX(w / 2f);
        mCameraPreviewView.setPivotY(h / 2f);
        mCameraPreviewView.setScaleX(scale);
        mCameraPreviewView.setScaleY(scale);
        Log.d(TAG, "applyFacePreviewMirror: mirror=" + faceCameraMirror + " preview="
                + w + "x" + h + " container=" + containerWidth + "x" + containerHeight
                + " scale=" + scale);
    }

    static float calculateCenterCropScale(int containerWidth, int containerHeight,
                                          int previewWidth, int previewHeight) {
        if (containerWidth <= 0 || containerHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) {
            return 1f;
        }
        return Math.max((float) containerWidth / previewWidth,
                (float) containerHeight / previewHeight);
    }

    private static int normalizeCameraRotation(int rotation) {
        return (rotation == 90 || rotation == 180 || rotation == 270) ? rotation : 0;
    }

    private static int toSurfaceRotation(int rotation) {
        switch (normalizeCameraRotation(rotation)) {
            case 90: return Surface.ROTATION_90;
            case 180: return Surface.ROTATION_180;
            case 270: return Surface.ROTATION_270;
            default: return Surface.ROTATION_0;
        }
    }

    // ==================== 启动流程 UI ====================
    // ==================== 启动流程已迁移至 Vue 层 ====================
    // bootstrap 进度、激活码输入、错误重试、强制更新等 UI 由 Vue bootstrap 页接管
    // 详见 uniapp/src/pages/bootstrap/bootstrap.vue

    public void setFaceAnalyzer(ImageAnalysis.Analyzer analyzer) {
        if (mCameraAnalysis != null) {
            mCameraAnalysis.clearAnalyzer();
            mCameraAnalysis.setAnalyzer(mCameraAnalysisExecutor, analyzer);
            Log.d(TAG, "Face analyzer set on persistent camera executor");
        }
    }

    public void clearFaceAnalyzer() {
        if (mCameraAnalysis != null) {
            mCameraAnalysis.clearAnalyzer();
            Log.d(TAG, "Face analyzer cleared from persistent camera");
        }
    }

    /**
     * 后台预初始化 FaceAISDK 组件，提前触发 ML Kit DynamiteModule 加载和引擎初始化。
     * FaceDetectorV2Jni 模型加载约 4 秒，提前初始化可减少用户进入人脸页面时的等待时间。
     */
    private void prewarmFaceAI() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.d(TAG, "预初始化 FaceAI SDK 开始...");
                // 获取单例，触发底层 ML Kit Face 模块加载和 TFLite 模型初始化
                FaceSearchEngine.getInstance();
                FaceAISDKEngine.getInstance(this);
                Log.d(TAG, "预初始化 FaceAI SDK 完成");
            } catch (Exception e) {
                Log.w(TAG, "预初始化 FaceAI SDK 失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prewarmCameraX();
        }
    }

    private void initViews() {
        webViewContainer = findViewById(R.id.webview_container);
        loadingLayout = findViewById(R.id.loading_layout);
        errorLayout = findViewById(R.id.error_layout);
        retryButton = findViewById(R.id.retry_button);
        // retryButton 只处理运行时错误（WebView 加载失败），bootstrap 异常由 Vue 处理
        retryButton.setOnClickListener(v -> {
            errorLayout.setVisibility(View.GONE);
            loadingLayout.setVisibility(View.VISIBLE);
            loadUniApp();
        });

        // 人脸操作覆盖层
        faceOverlay = findViewById(R.id.face_overlay);
        tvFaceStatus = findViewById(R.id.tvFaceStatus);
        tvFaceCountdown = findViewById(R.id.tvFaceCountdown);
        btnFaceCapture = findViewById(R.id.btnFaceCapture);
        btnFaceCancel = findViewById(R.id.btnFaceCancel);
    }

    private void initManagers() {
        jsBridge = new JsBridgeV2(this);
        errorHandler = new ErrorHandler(this, this::onError, this::onErrorDismiss);
        webViewManager = new WebViewManager(this, webViewContainer, jsBridge, errorHandler, this::onPageFinished);
    }

    private void onPageFinished() {
        loadingLayout.setVisibility(View.GONE);
        webViewContainer.setVisibility(View.VISIBLE);
        try {
            sendBridgeEvent("native.ready", new JSONObject().put("ready", true));
        } catch (JSONException ignored) {
        }
    }

    private void startLocalHttpServer() {
        try {
            localHttpServer = new LocalHttpServer(this, LOCAL_HTTP_PORT);
            localHttpServer.start();
            Log.d("MainActivity", "Local HTTP server started on port " + LOCAL_HTTP_PORT);
        } catch (IOException e) {
            Log.e("MainActivity", "Failed to start local HTTP server", e);
        }
    }

    private void stopLocalHttpServer() {
        if (localHttpServer != null) {
            localHttpServer.stop();
            localHttpServer = null;
        }
    }

    private void loadUniApp() {
        webViewManager.loadUrl("http://127.0.0.1:" + LOCAL_HTTP_PORT + "/index.html");
    }

    public boolean isOriginScopedBridgeEnabled() {
        return webViewManager != null && webViewManager.isOriginScopedBridgeEnabled();
    }

    public void sendBridgeResponse(JSONObject response) {
        if (response == null || webViewManager == null) return;
        String script = "window.NativeBridge&&window.NativeBridge.receive(" + response.toString() + ");";
        webViewManager.evaluateJavascript(script);
    }

    public void sendBridgeEvent(String event, JSONObject data) {
        try {
            sendBridgeResponse(new JSONObject()
                    .put("type", "event")
                    .put("event", event)
                    .put("data", data == null ? JSONObject.NULL : data));
        } catch (JSONException error) {
            Log.e("MainActivity", "Unable to send bridge event", error);
        }
    }

    public void scheduleRecreate() {
        new Handler(Looper.getMainLooper()).postDelayed(this::recreate, 450);
    }

    public void startFaceEnrollment(String requestId, JSONObject payload) {
        if (pendingFaceEnrollmentRequestId != null) {
            // 上一次注册未正常结束（如特征提取失败未触发回调），清理残留状态
            String staleId = consumeFaceRequestId();
            if (staleId != null) {
                sendBridgeError(staleId, "FACE_ENROLLMENT_INTERRUPTED", "上一次注册被中断，请重试");
            }
            runOnUiThread(this::hideFaceOverlay);
            // 不 return，继续执行本次注册
        }
        pendingFaceEnrollmentRequestId = requestId;
        String employeeId = payload.optString("employeeId", "");
        String employeeName = payload.optString("employeeName", "");

        runOnUiThread(() -> showFaceOverlay(true, employeeId, employeeName));
    }

    public void startFaceVerification(String requestId) {
        if (pendingFaceEnrollmentRequestId != null) {
            sendBridgeError(requestId, "FACE_VERIFICATION_IN_PROGRESS", "已有正在进行的人脸识别");
            return;
        }
        pendingFaceEnrollmentRequestId = requestId;

        runOnUiThread(() -> showFaceOverlay(false, "", ""));
    }

    private void showFaceOverlay(boolean isEnroll, String faceId, String faceName) {
        faceOverlayActive = true;
        faceOverlay.bringToFront();
        faceOverlay.setVisibility(View.VISIBLE);

        faceController = new FaceEnrollmentController(this, isEnroll, faceId, faceName,
                tvFaceStatus, tvFaceCountdown, btnFaceCapture, btnFaceCancel, createFaceResultCallback());
        faceController.setFrontCamera("front".equals(faceCameraFacing));
        faceController.start();
    }

    private void hideFaceOverlay() {
        if (faceController != null) {
            faceController.stop();
            faceController = null;
        }
        faceOverlayActive = false;
        webViewContainer.bringToFront();
    }

    /**
     * JsBridgeV2 调用：显示人脸容器。
     * 仅提供 UI 容器和按钮状态，FaceEnrollmentController 由 JsBridgeV2 自行管理。
     * @param faceId  用于显示的 faceId（搜索模式可为 null）
     * @param isEnroll true=录入模式（显示拍照按钮），false=搜索模式（隐藏拍照按钮）
     * @param onCancel 取消按钮点击回调（可为 null）
     */
    public void showFaceContainer(String faceId, boolean isEnroll, Runnable onCancel) {
        runOnUiThread(() -> {
            faceOverlayActive = true;
            faceOverlay.bringToFront();
            faceOverlay.setVisibility(View.VISIBLE);
            btnFaceCapture.setVisibility(isEnroll ? View.VISIBLE : View.INVISIBLE);
            btnFaceCancel.setVisibility(View.VISIBLE);
            btnFaceCancel.setOnClickListener(v -> {
                if (onCancel != null) onCancel.run();
            });
            if (faceId != null && !faceId.isEmpty()) {
                tvFaceStatus.setText("录入人脸: " + faceId);
            } else {
                tvFaceStatus.setText("请正对摄像头");
            }
        });
    }

    /**
     * JsBridgeV2 调用：隐藏人脸容器。
     */
    public void hideFaceContainer() {
        runOnUiThread(() -> {
            btnFaceCancel.setOnClickListener(null);
            faceOverlayActive = false;
            webViewContainer.bringToFront();
        });
    }

    // === 暴露 face overlay UI 引用给 JsBridgeV2 ===

    public TextView getTvFaceStatus() {
        return tvFaceStatus;
    }

    public TextView getTvFaceCountdown() {
        return tvFaceCountdown;
    }

    public Button getBtnFaceCapture() {
        return btnFaceCapture;
    }

    public Button getBtnFaceCancel() {
        return btnFaceCancel;
    }

    private FaceEnrollmentController.FaceResultCallback createFaceResultCallback() {
        return new FaceEnrollmentController.FaceResultCallback() {
            @Override
            public void onFaceEnrolled(String faceId, String faceFeature, float score) {
                String reqId = consumeFaceRequestId();
                hideFaceOverlay();
                if (reqId == null) return;
                try {
                    // V2: Vue 负责后端同步和 SQLite 写入，Android 只返回人脸结果
                    JSONObject data = new JSONObject()
                            .put("success", true)
                            .put("faceId", faceId)
                            .put("faceFeature", faceFeature)
                            .put("similarity", score)
                            .put("engine", "FaceAISDK");
                    sendBridgeResponse(new JSONObject().put("type", "response")
                            .put("requestId", reqId).put("success", true).put("data", data));
                } catch (Exception error) {
                    sendBridgeError(reqId, "FACE_ENROLLMENT_FAILED", safeMessage(error));
                }
            }

            @Override
            public void onFaceVerified(String faceId, float score) {
                String reqId = consumeFaceRequestId();
                hideFaceOverlay();
                if (reqId == null) return;
                try {
                    // V2: Vue 负责选卡、开门和后端同步，Android 只返回识别结果
                    JSONObject data = new JSONObject()
                            .put("success", true)
                            .put("faceId", faceId)
                            .put("similarity", score)
                            .put("engine", "FaceAISDK");
                    sendBridgeResponse(new JSONObject().put("type", "response")
                            .put("requestId", reqId).put("success", true).put("data", data));
                } catch (Exception error) {
                    sendBridgeError(reqId, "FACE_VERIFICATION_FAILED", safeMessage(error));
                }
            }

            @Override
            public void onCancelled() {
                String reqId = consumeFaceRequestId();
                hideFaceOverlay();
                if (reqId != null) {
                    sendBridgeError(reqId, "FACE_CANCELLED", "已取消人脸操作");
                }
            }
        };
    }

    private String consumeFaceRequestId() {
        String reqId = pendingFaceEnrollmentRequestId;
        pendingFaceEnrollmentRequestId = null;
        return reqId;
    }

    /**
     * Android intentionally keeps fingerprint templates inside the secure system area. This method
     * asks the operating system to authenticate the current device user and never receives a print,
     * image, template, or a fingerprint identifier.
     */
    public void startFingerprintAuthentication(String requestId, JSONObject payload, boolean enrollment) {
        runOnUiThread(() -> startFingerprintAuthenticationOnMainThread(requestId, payload, enrollment));
    }

    private void startFingerprintAuthenticationOnMainThread(String requestId, JSONObject payload, boolean enrollment) {
        if (pendingFingerprintRequestId != null) {
            cancelStaleFingerprintAuthentication();
        }
        final String employeeId = payload.optString("employeeId", "").trim();
        final String employeeName = payload.optString("employeeName", "").trim();
        final String operation = enrollment ? "ENROLL" : "VERIFY";
        if (startFingerprintManagerAuthentication(requestId, employeeId, employeeName, operation, enrollment)) return;
        // Several OEM devices classify their physical fingerprint reader as BIOMETRIC_WEAK.
        // Use the system's biometric capability level so those readers can still open the prompt.
        int availability = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            sendBridgeError(requestId, "FINGERPRINT_UNAVAILABLE", fingerprintAvailabilityMessage(availability));
            return;
        }
        pendingFingerprintRequestId = requestId;
        pendingFingerprintOperation = operation;
        final int sessionId = ++fingerprintSessionId;
        sendFingerprintEvent("STARTED", enrollment ? "已打开系统指纹授权" : "已打开系统指纹验证", operation, 0);
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                String completedRequestId = takePendingFingerprintRequestId(sessionId);
                if (completedRequestId == null) return;
                sendFingerprintSuccess(completedRequestId, enrollment, employeeId, employeeName, operation);
            }

            @Override public void onAuthenticationError(int errorCode, CharSequence errorMessage) {
                String completedRequestId = takePendingFingerprintRequestId(sessionId);
                if (completedRequestId == null) return;
                String message = errorMessage == null ? "系统指纹验证未完成" : errorMessage.toString();
                sendFingerprintEvent("ERROR", message, operation, errorCode);
                sendBridgeError(completedRequestId, "FINGERPRINT_AUTH_FAILED", message);
            }

            @Override public void onAuthenticationFailed() {
                if (!isCurrentFingerprintSession(sessionId)) return;
                sendFingerprintEvent("FAILED_ATTEMPT", "未识别到有效指纹，请调整手指后重试", operation, 0);
            }
        });
        activeBiometricPrompt = prompt;
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(enrollment ? "确认本机指纹授权" : "指纹验证")
                .setSubtitle(enrollment ? "请使用本设备已录入的指纹确认" : "请将已录入设备的手指放在指纹传感器上")
                .setNegativeButtonText("取消")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
        sendFingerprintEvent("WAITING_FOR_TOUCH", "请将已录入系统的手指放在指纹采集区", operation, 0);
        prompt.authenticate(promptInfo);
    }

    public void cancelFingerprintAuthentication() {
        runOnUiThread(() -> {
            FingerprintCleanup cleanup = clearFingerprintState();
            if (cleanup.biometricPrompt != null) cleanup.biometricPrompt.cancelAuthentication();
            if (cleanup.fingerprintCancellationSignal != null && !cleanup.fingerprintCancellationSignal.isCanceled()) {
                cleanup.fingerprintCancellationSignal.cancel();
            }
        });
    }

    public JSONObject fingerprintStatus() throws JSONException {
        if (isFingerprintManagerReady()) {
            return new JSONObject().put("available", true)
                    .put("status", "READY")
                    .put("message", "本机指纹传感器可用")
                    .put("scope", "SYSTEM_FINGERPRINT");
        }
        int availability = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return new JSONObject().put("available", availability == BiometricManager.BIOMETRIC_SUCCESS)
                .put("status", availability == BiometricManager.BIOMETRIC_SUCCESS ? "READY" : "UNAVAILABLE")
                .put("message", fingerprintAvailabilityMessage(availability))
                .put("scope", "SYSTEM_DEVICE_BIOMETRIC");
    }

    private boolean isCurrentFingerprintSession(int sessionId) {
        return pendingFingerprintRequestId != null && fingerprintSessionId == sessionId;
    }

    private String takePendingFingerprintRequestId(int sessionId) {
        if (fingerprintSessionId != sessionId) return null;
        String requestId = pendingFingerprintRequestId;
        clearFingerprintState();
        return requestId;
    }

    private void cancelStaleFingerprintAuthentication() {
        String operation = pendingFingerprintOperation == null ? "VERIFY" : pendingFingerprintOperation;
        FingerprintCleanup cleanup = clearFingerprintState();
        if (cleanup.biometricPrompt != null) cleanup.biometricPrompt.cancelAuthentication();
        if (cleanup.fingerprintCancellationSignal != null && !cleanup.fingerprintCancellationSignal.isCanceled()) {
            cleanup.fingerprintCancellationSignal.cancel();
        }
        sendFingerprintEvent("RESTARTED", "已重置上一次未结束的系统指纹验证，请重新按压手指", operation, 0);
    }

    private FingerprintCleanup clearFingerprintState() {
        FingerprintCleanup cleanup = new FingerprintCleanup(activeBiometricPrompt, activeFingerprintCancellationSignal);
        pendingFingerprintRequestId = null;
        pendingFingerprintOperation = null;
        activeBiometricPrompt = null;
        activeFingerprintCancellationSignal = null;
        fingerprintSessionId++;
        return cleanup;
    }

    @SuppressWarnings("deprecation")
    private boolean startFingerprintManagerAuthentication(String requestId, String employeeId, String employeeName,
                                                          String operation, boolean enrollment) {
        if (!isFingerprintManagerReady()) return false;
        FingerprintManager fingerprintManager = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintManager = getSystemService(FingerprintManager.class);
        }
        pendingFingerprintRequestId = requestId;
        pendingFingerprintOperation = operation;
        final int sessionId = ++fingerprintSessionId;
        CancellationSignal cancellationSignal = new CancellationSignal();
        activeFingerprintCancellationSignal = cancellationSignal;
        sendFingerprintEvent("STARTED", enrollment ? "已启动系统指纹授权" : "已启动系统指纹验证", operation, 0);
        sendFingerprintEvent("WAITING_FOR_TOUCH", "请将已录入系统的手指放在指纹采集区", operation, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintManager.authenticate(null, cancellationSignal, 0, new FingerprintManager.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                    String completedRequestId = takePendingFingerprintRequestId(sessionId);
                    if (completedRequestId == null) return;
                    sendFingerprintSuccess(completedRequestId, enrollment, employeeId, employeeName, operation);
                }

                @Override public void onAuthenticationFailed() {
                    if (!isCurrentFingerprintSession(sessionId)) return;
                    sendFingerprintEvent("FAILED_ATTEMPT", "未识别到有效指纹，请调整手指后重试", operation, 0);
                }

                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    String completedRequestId = takePendingFingerprintRequestId(sessionId);
                    if (completedRequestId == null) return;
                    String message = errString == null ? "系统指纹验证未完成" : errString.toString();
                    sendFingerprintEvent("ERROR", message, operation, errorCode);
                    sendBridgeError(completedRequestId, "FINGERPRINT_AUTH_FAILED", message);
                }

                @Override public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                    if (!isCurrentFingerprintSession(sessionId)) return;
                    sendFingerprintEvent("HELP", helpString == null ? "请重新放置手指" : helpString.toString(), operation, helpCode);
                }
            }, null);
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean isFingerprintManagerReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        FingerprintManager fingerprintManager = getSystemService(FingerprintManager.class);
        return fingerprintManager != null && fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints();
    }

    private void sendFingerprintSuccess(String completedRequestId, boolean enrollment, String employeeId, String employeeName,
                                        String operation) {
        try {
            JSONObject response = new JSONObject()
                    .put("success", true)
                    .put("status", "SYSTEM_AUTHENTICATED")
                    .put("operation", operation)
                    .put("employeeId", employeeId)
                    .put("employeeName", employeeName)
                    .put("deviceBound", true)
                    .put("message", enrollment ? "本机系统指纹授权成功" : "系统指纹验证成功");
            // V2: 指纹事件日志和员工标记由 Vue 管理，Android 仅返回结果
            sendFingerprintEvent("SUCCESS", response.optString("message"), operation, 0);
            sendBridgeResponse(new JSONObject().put("type", "response").put("requestId", completedRequestId)
                    .put("success", true).put("data", response));
        } catch (JSONException error) {
            sendBridgeError(completedRequestId, "FINGERPRINT_RESPONSE_FAILED", error.getMessage());
        }
    }

    private static final class FingerprintCleanup {
        final BiometricPrompt biometricPrompt;
        final CancellationSignal fingerprintCancellationSignal;

        FingerprintCleanup(BiometricPrompt biometricPrompt, CancellationSignal fingerprintCancellationSignal) {
            this.biometricPrompt = biometricPrompt;
            this.fingerprintCancellationSignal = fingerprintCancellationSignal;
        }
    }

    private void sendFingerprintEvent(String status, String message, String operation, int errorCode) {
        try {
            sendBridgeEvent("fingerprint.statusChanged", new JSONObject()
                    .put("status", status)
                    .put("message", message)
                    .put("operation", operation)
                    .put("errorCode", errorCode));
        } catch (JSONException error) {
            Log.e("MainActivity", "Unable to send fingerprint event", error);
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    private static String fingerprintAvailabilityMessage(int availability) {
        switch (availability) {
            case BiometricManager.BIOMETRIC_SUCCESS: return "本机系统生物认证可用";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE: return "此设备没有可用的指纹硬件";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE: return "指纹传感器暂时不可用";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED: return "请先在系统设置中录入指纹";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED: return "系统安全更新后才能使用指纹";
            default: return "本机系统生物认证不可用";
        }
    }

    private void sendBridgeError(String requestId, String code, String message) {
        try {
            sendBridgeResponse(new JSONObject().put("type", "response").put("requestId", requestId)
                    .put("success", false).put("code", code).put("message", message));
        } catch (JSONException ignored) { }
    }

    private void onError(int errorType, String message) {
        loadingLayout.setVisibility(View.GONE);
        webViewContainer.setVisibility(View.GONE);
        errorLayout.setVisibility(View.VISIBLE);
        errorHandler.showError(errorType, message);
    }

    private void onErrorDismiss() {
        errorLayout.setVisibility(View.GONE);
        loadingLayout.setVisibility(View.VISIBLE);
        loadUniApp();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureFullscreen();
            if (!faceOverlayActive && webViewContainer != null) webViewContainer.bringToFront();
        }
    }

    @Override
    protected void onPause() {
        if (jsBridge != null) jsBridge.cancelFaceOperationForActivityPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (faceController != null) faceController.stop();
        if (mCameraProvider != null) mCameraProvider.unbindAll();
        mCameraAnalysisExecutor.shutdownNow();
        stopLocalHttpServer();
        if (webViewManager != null) webViewManager.destroy();
        if (jsBridge != null) jsBridge.close();
        super.onDestroy();
    }
}
