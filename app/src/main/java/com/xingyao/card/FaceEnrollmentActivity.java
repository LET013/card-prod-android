package com.xingyao.card;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.xingyao.card.core.DeviceRuntimeRegistry;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, single-RGB-camera ArcFace enrollment flow for mobile devices. */
@SuppressWarnings("deprecation")
public class FaceEnrollmentActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_EMPLOYEE_ID = "employeeId";
    public static final String EXTRA_EMPLOYEE_NAME = "employeeName";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_MESSAGE = "message";
    public static final String MODE_ENROLL = "ENROLL";
    public static final String MODE_VERIFY = "VERIFY";
    private static final int REQUEST_CAMERA = 4201;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private TextView statusView;
    private String employeeId;
    private String employeeName;
    private String mode;
    private int previewWidth;
    private int previewHeight;
    private volatile boolean processing;
    private volatile boolean completed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        employeeId = getIntent().getStringExtra(EXTRA_EMPLOYEE_ID);
        employeeName = getIntent().getStringExtra(EXTRA_EMPLOYEE_NAME);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_ENROLL;
        if (MODE_ENROLL.equals(mode) && (employeeId == null || employeeId.trim().isEmpty())) {
            finishWithError("缺少职员 ID");
            return;
        }
        buildContent();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void buildContent() {
        FrameLayout root = new FrameLayout(this);
        SurfaceView preview = new SurfaceView(this);
        surfaceHolder = preview.getHolder();
        surfaceHolder.addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statusView = new TextView(this);
        statusView.setText("请将正脸置于取景框内");
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(32, 24, 32, 24);
        statusView.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusLayout = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(statusView, statusLayout);
        setContentView(root);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { startCameraIfAllowed(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCameraIfAllowed();
        else if (requestCode == REQUEST_CAMERA) finishWithError("未授予相机权限");
    }

    private void startCameraIfAllowed() {
        if (surfaceHolder == null || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || camera != null) return;
        try {
            camera = Camera.open(findFrontCamera());
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
            parameters.setPreviewSize(size.width, size.height);
            parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            camera.setParameters(parameters);
            previewWidth = size.width;
            previewHeight = size.height;
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surfaceHolder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception error) {
            finishWithError("无法打开前置摄像头：" + safeMessage(error));
        }
    }

    @Override public void onPreviewFrame(byte[] data, Camera source) {
        if (processing || completed || data == null) return;
        processing = true;
        runOnUiThread(() -> statusView.setText(MODE_VERIFY.equals(mode) ? "正在进行人脸活体核验…" : "正在采集人脸特征…"));
        final byte[] frame = data.clone();
        worker.execute(() -> {
            try {
                JSONObject result = MODE_VERIFY.equals(mode)
                        ? DeviceRuntimeRegistry.require().verifyFace(frame, previewWidth, previewHeight)
                        : DeviceRuntimeRegistry.require().enrollFace(employeeId, employeeName == null ? "" : employeeName, frame, previewWidth, previewHeight);
                completed = true;
                runOnUiThread(() -> finishWithSuccess(result));
            } catch (Exception error) {
                String message = safeMessage(error);
                if (isTerminalEngineError(message)) {
                    runOnUiThread(() -> finishWithError(message));
                } else {
                    runOnUiThread(() -> statusView.setText(message));
                }
            } finally {
                processing = false;
            }
        });
    }

    private int findFrontCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int index = 0; index < Camera.getNumberOfCameras(); index++) {
            Camera.getCameraInfo(index, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return index;
        }
        return 0;
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        Camera.Size selected = sizes.get(0);
        for (Camera.Size candidate : sizes) {
            if (candidate.width % 4 == 0 && candidate.width >= 640 &&
                    candidate.width * candidate.height < selected.width * selected.height) selected = candidate;
        }
        return selected;
    }

    private void finishWithSuccess(JSONObject result) {
        Intent data = new Intent();
        data.putExtra(EXTRA_MESSAGE, result.toString());
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishWithError(String message) {
        Intent data = new Intent();
        data.putExtra(EXTRA_MESSAGE, message);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    private void releaseCamera() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    @Override protected void onDestroy() {
        releaseCamera();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? "请调整光线或正脸后重试" : value;
    }

    private static boolean isTerminalEngineError(String message) {
        return message.contains("设备架构不支持") || message.contains("虹软激活") ||
                message.contains("尚未配置") || message.contains("初始化失败") || message.contains("虹软服务未启动");
    }
}
