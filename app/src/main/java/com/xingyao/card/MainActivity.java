package com.xingyao.card;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import com.xingyao.card.core.DeviceRuntimeRegistry;
import com.xingyao.card.service.DeviceCoreService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private static final int LOCAL_HTTP_PORT = 8088;
    private static final int REQUEST_ARCSOFT_DEVICE_ID = 4101;
    private static final int REQUEST_FACE_ENROLLMENT = 4102;

    private WebViewManager webViewManager;
    private JsBridge jsBridge;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureFullscreen();
        setContentView(R.layout.activity_main);
        startDeviceCoreService();
        DeviceRuntimeRegistry.setUiListener(this::sendBridgeEvent);
        requestArcSoftDevicePermission();
        initViews();
        initManagers();
        startLocalHttpServer();
        loadUniApp();
    }

    private void configureFullscreen() {
        getWindow().setStatusBarColor(0xFF1F76FF);
        getWindow().setNavigationBarColor(0xFF1F76FF);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
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

    private void requestArcSoftDevicePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            DeviceRuntimeRegistry.requestFaceRestart();
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_ARCSOFT_DEVICE_ID);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ARCSOFT_DEVICE_ID && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            DeviceRuntimeRegistry.requestFaceRestart();
        }
    }

    private void initViews() {
        webViewContainer = findViewById(R.id.webview_container);
        loadingLayout = findViewById(R.id.loading_layout);
        errorLayout = findViewById(R.id.error_layout);
        retryButton = findViewById(R.id.retry_button);
        retryButton.setOnClickListener(v -> {
            errorLayout.setVisibility(View.GONE);
            loadingLayout.setVisibility(View.VISIBLE);
            loadUniApp();
        });
    }

    private void initManagers() {
        jsBridge = new JsBridge(this);
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
            sendBridgeError(requestId, "FACE_ENROLLMENT_IN_PROGRESS", "已有正在进行的人脸注册");
            return;
        }
        pendingFaceEnrollmentRequestId = requestId;
        Intent intent = new Intent(this, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_MODE, FaceEnrollmentActivity.MODE_ENROLL)
                .putExtra(FaceEnrollmentActivity.EXTRA_EMPLOYEE_ID, payload.optString("employeeId", ""))
                .putExtra(FaceEnrollmentActivity.EXTRA_EMPLOYEE_NAME, payload.optString("employeeName", ""));
        runOnUiThread(() -> startActivityForResult(intent, REQUEST_FACE_ENROLLMENT));
    }

    public void startFaceVerification(String requestId) {
        if (pendingFaceEnrollmentRequestId != null) {
            sendBridgeError(requestId, "FACE_VERIFICATION_IN_PROGRESS", "已有正在进行的人脸识别");
            return;
        }
        pendingFaceEnrollmentRequestId = requestId;
        Intent intent = new Intent(this, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_MODE, FaceEnrollmentActivity.MODE_VERIFY);
        runOnUiThread(() -> startActivityForResult(intent, REQUEST_FACE_ENROLLMENT));
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
        FingerprintManager fingerprintManager = getSystemService(FingerprintManager.class);
        pendingFingerprintRequestId = requestId;
        pendingFingerprintOperation = operation;
        final int sessionId = ++fingerprintSessionId;
        CancellationSignal cancellationSignal = new CancellationSignal();
        activeFingerprintCancellationSignal = cancellationSignal;
        sendFingerprintEvent("STARTED", enrollment ? "已启动系统指纹授权" : "已启动系统指纹验证", operation, 0);
        sendFingerprintEvent("WAITING_FOR_TOUCH", "请将已录入系统的手指放在指纹采集区", operation, 0);
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
            DeviceRuntimeRegistry.record("biometric.fingerprint." + operation.toLowerCase(), response);
            if (enrollment) {
                try {
                    DeviceRuntimeRegistry.require().markFingerprintAuthorized(employeeId, employeeName);
                } catch (Exception error) {
                    DeviceRuntimeRegistry.record("biometric.fingerprint.employeeUpdateFailed",
                            new JSONObject().put("message", error.getMessage()));
                }
            }
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FACE_ENROLLMENT || pendingFaceEnrollmentRequestId == null) return;
        String requestId = pendingFaceEnrollmentRequestId;
        pendingFaceEnrollmentRequestId = null;
        String payload = data == null ? "" : data.getStringExtra(FaceEnrollmentActivity.EXTRA_MESSAGE);
        if (resultCode == RESULT_OK) {
            try {
                sendBridgeResponse(new JSONObject().put("type", "response").put("requestId", requestId)
                        .put("success", true).put("data", new JSONObject(payload)));
            } catch (JSONException error) {
                sendBridgeError(requestId, "FACE_ENROLLMENT_FAILED", error.getMessage());
            }
        } else {
            sendBridgeError(requestId, "FACE_ENROLLMENT_CANCELLED", payload == null || payload.isEmpty() ? "已取消人脸注册" : payload);
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
        if (hasFocus) configureFullscreen();
    }

    @Override
    protected void onDestroy() {
        DeviceRuntimeRegistry.setUiListener(null);
        stopLocalHttpServer();
        if (webViewManager != null) webViewManager.destroy();
        super.onDestroy();
    }
}
