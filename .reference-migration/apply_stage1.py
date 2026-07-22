from pathlib import Path
import re
import subprocess

ROOT = Path('.')
REF = 'origin/reference/motone-current'


def run(*args):
    return subprocess.check_output(args, text=True)


def ref_file(path):
    return run('git', 'show', f'{REF}:{path}')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, content):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')


def replace_once(path, old, new):
    value = read(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one occurrence, got {count}: {old[:120]!r}')
    write(path, value.replace(old, new, 1))


def replace_regex(path, pattern, replacement, flags=re.S):
    value = read(path)
    result, count = re.subn(pattern, replacement, value, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f'{path}: pattern not found: {pattern[:120]!r}')
    write(path, result)


# ---------------------------------------------------------------------------
# Build and platform files: reference branch is the device-capability source.
# ---------------------------------------------------------------------------
write('app/build.gradle', r'''plugins {
    id 'com.android.application'
}

android {
    namespace 'com.xingyao.card'
    compileSdk 34
    ndkVersion "22.1.7171670"

    defaultConfig {
        applicationId "com.xingyao.card"
        minSdk 21
        targetSdk 33
        versionCode 1
        versionName "1.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }

    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    dataBinding { enabled = true }
    lint { baseline = file("lint-baseline.xml") }
    sourceSets {
        main {
            assets.srcDirs = ['src/main/assets', '../../uniapp/dist/build/h5']
        }
    }
    externalNativeBuild {
        cmake {
            path file('src/main/cpp/CMakeLists.txt')
            version '3.22.1'
        }
    }
    buildFeatures {
        viewBinding true
        buildConfig true
    }
}

configurations.all {
    resolutionStrategy {
        force 'org.jetbrains.kotlin:kotlin-stdlib:1.8.10'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.10'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10'
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.webkit:webkit:1.6.0'
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5'

    // User-confirmed face engine. ArcSoft is intentionally not part of the build.
    implementation 'io.github.faceaisdk:Android:2026.06.25'
    def camerax_version = "1.4.2"
    implementation "androidx.camera:camera-core:${camerax_version}"
    implementation "androidx.camera:camera-camera2:${camerax_version}"
    implementation "androidx.camera:camera-lifecycle:${camerax_version}"
    implementation "androidx.camera:camera-view:${camerax_version}"

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20240303'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
''')

write('app/src/main/AndroidManifest.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_FINGERPRINT" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />
    <uses-permission android:name="android.permission.CAMERA" />

    <application
        android:allowBackup="true"
        android:usesCleartextTraffic="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.UniAppDemo"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.DeviceCoreService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
''')

for path in [
    'app/src/main/res/layout/activity_main.xml',
    'app/src/main/res/drawable/bg_card_border.xml',
    'app/src/main/cpp/CMakeLists.txt',
    'app/src/main/cpp/SerialPort.c',
    'app/src/main/cpp/SerialPort.h',
    'app/src/main/java/com/xingyao/card/serial/SerialManager.java',
]:
    write(path, ref_file(path))

# Correct the JNI close mismatch in the historical reference implementation.
write('app/src/main/java/com/xingyao/card/serial/SerialPort.java', r'''package com.xingyao.card.serial;

import android.util.Log;

import java.io.FileDescriptor;

/** JNI shell for the device serial port. */
public final class SerialPort {
    private static final String TAG = "SerialPort";
    private static volatile boolean libraryLoaded;
    private static volatile String libraryLoadError;

    static {
        try {
            System.loadLibrary("SerialPort");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            libraryLoaded = false;
            libraryLoadError = error.getMessage();
            Log.e(TAG, "libSerialPort.so load failed", error);
        }
    }

    private FileDescriptor mFd;

    public static boolean isAvailable() { return libraryLoaded; }
    public static String getLoadError() { return libraryLoadError; }

    protected native FileDescriptor open(String path, int baudRate, int flags);
    private native void closeNative();

    protected synchronized void close() {
        if (mFd == null) return;
        closeNative();
        mFd = null;
    }
}
''')

# SerialManager must surface write failures instead of logging a false success.
serial_manager = read('app/src/main/java/com/xingyao/card/serial/SerialManager.java')
serial_manager = serial_manager.replace('    public void send(byte[] data) {', '    public void send(byte[] data) throws IOException {')
serial_manager = serial_manager.replace('''        if (serialPort == null || outputStream == null) {
            Log.e(TAG, "串口未打开，无法发送数据");
            return;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            Log.d(TAG, "发送 " + data.length + " 字节");
        } catch (IOException e) {
            Log.e(TAG, "串口发送失败", e);
        }''', '''        if (serialPort == null || outputStream == null) {
            throw new IOException("串口未打开");
        }
        outputStream.write(data);
        outputStream.flush();
        Log.d(TAG, "发送 " + data.length + " 字节");''')
write('app/src/main/java/com/xingyao/card/serial/SerialManager.java', serial_manager)

# Pure serial communication adapter. It restores the proven JNI transport but keeps logical slot
# operations disabled because neither the Markdown nor the reference branch proves the topology.
write('app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java', r'''package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.xingyao.card.serial.SerialManager;
import com.xingyao.card.serial.SerialPort;

/** V1.5 serial transport. Logical slot/address mapping remains disabled until documented. */
public final class SerialConnectionManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onDataReceived(JSONObject data);
        void onSlotStatus(JSONObject slot);
    }

    private static final String DEFAULT_PORT = "/dev/ttyS5";
    private static final int DEFAULT_BAUD_RATE = 57600;

    private final Listener listener;
    private final List<Byte> inboundBuffer = new ArrayList<>();
    private SerialManager serialManager;
    private boolean running;
    private String state = "DISCONNECTED";
    private String message = "串口未连接";
    private String port = DEFAULT_PORT;
    private int baudRate = DEFAULT_BAUD_RATE;
    private int totalSlots = 100;
    private int singleGroupCount = 10;
    private long responseTimeoutMs = 100L;
    private long commandGapMs = 200L;
    private long pollingIntervalMs = 5000L;
    private long sentBytes;
    private long receivedBytes;
    private long lastReceivedAt;
    private String lastError = "";
    private String lastPermissionHint = "";

    public SerialConnectionManager(Listener listener) {
        this.listener = listener;
    }

    public synchronized void configure(JSONObject settings) {
        String configuredPort = settings == null ? "" : settings.optString("serialPort", "").trim();
        port = configuredPort.isEmpty() ? DEFAULT_PORT : configuredPort;
        baudRate = positiveInt(settings == null ? null : settings.opt("baudRate"), DEFAULT_BAUD_RATE);
        totalSlots = positiveInt(settings == null ? null : settings.opt("totalCount"), 100);
        singleGroupCount = positiveInt(settings == null ? null : settings.opt("singleGroupCount"), 10);
        responseTimeoutMs = positiveLong(settings == null ? null : settings.opt("serialResponseTimeoutMs"), 100L);
        commandGapMs = positiveLong(settings == null ? null : settings.opt("serialCommandGapMs"), 200L);
        pollingIntervalMs = positiveLong(settings == null ? null : settings.opt("serialPollingIntervalMs"), 5000L);
        if (running) openConfiguredPort();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        openConfiguredPort();
    }

    public synchronized void reconnect() {
        if (!running) running = true;
        openConfiguredPort();
    }

    public synchronized void stop() {
        running = false;
        closePort();
        updateState("DISCONNECTED", "原生串口已停止", null);
    }

    public synchronized JSONObject setPollingEnabled(boolean enabled) {
        if (enabled) {
            throw new IllegalStateException("SERIAL_TOPOLOGY_UNCONFIRMED：文档未定义slotId到从机地址或切组协议");
        }
        return snapshotQuietly();
    }

    public synchronized JSONObject send(String data, String encoding) throws Exception {
        byte[] bytes = "HEX".equalsIgnoreCase(encoding)
                ? parseHex(data) : (data == null ? "" : data).getBytes(StandardCharsets.UTF_8);
        return writeRaw(bytes, "HEX".equalsIgnoreCase(encoding) ? "manual.hex" : "manual.text");
    }

    public JSONObject openDoor(int slotNumber, boolean administrator) {
        throw topologyError();
    }

    public JSONObject querySlot(int slotNumber) {
        throw topologyError();
    }

    public JSONObject readVersion(int slotNumber) {
        throw topologyError();
    }

    public JSONObject openAllDoors(boolean administrator) {
        throw topologyError();
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("state", state).put("message", message)
                .put("port", port).put("baudRate", baudRate)
                .put("protocol", "WORK_CARD_V1.5")
                .put("transport", "JNI_BLOCKING_IO")
                .put("nativeLibraryReady", SerialPort.isAvailable())
                .put("polling", false).put("pollingEnabled", false)
                .put("totalSlots", totalSlots).put("singleGroupCount", singleGroupCount)
                .put("responseTimeoutMs", responseTimeoutMs)
                .put("pollingIntervalMs", pollingIntervalMs)
                .put("commandGapMs", commandGapMs)
                .put("cardNumberMode", "VISIBLE_ASCII")
                .put("addressMode", "UNCONFIRMED")
                .put("sentBytes", sentBytes).put("receivedBytes", receivedBytes)
                .put("lastReceivedAt", lastReceivedAt == 0L ? JSONObject.NULL : lastReceivedAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError)
                .put("permissionHint", lastPermissionHint.isEmpty() ? JSONObject.NULL : lastPermissionHint)
                .put("nativeLibraryError", SerialPort.getLoadError() == null
                        ? JSONObject.NULL : SerialPort.getLoadError());
    }

    public static JSONObject listAvailablePorts() throws JSONException {
        JSONArray ports = new JSONArray();
        List<File> candidates = new ArrayList<>();
        File[] files = new File("/dev").listFiles();
        if (files != null) {
            for (File file : files) if (isSerialDeviceName(file.getName())) candidates.add(file);
        }
        Collections.sort(candidates, (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        for (File file : candidates) {
            ports.put(new JSONObject().put("path", file.getAbsolutePath())
                    .put("readable", file.canRead()).put("writable", file.canWrite())
                    .put("exists", file.exists()));
        }
        return new JSONObject().put("ports", ports).put("count", ports.length())
                .put("message", ports.length() == 0
                        ? "未在/dev下发现常见串口节点" : "发现" + ports.length() + "个候选串口");
    }

    private synchronized void openConfiguredPort() {
        closePort();
        lastPermissionHint = "";
        updateState("CONNECTING", String.format(Locale.US, "正在连接 %s @ %d", port, baudRate), null);
        try {
            if (!SerialPort.isAvailable()) {
                throw new UnsatisfiedLinkError("libSerialPort.so不可用：" + SerialPort.getLoadError());
            }
            ensureDeviceAccessible(port);
            serialManager = new SerialManager();
            serialManager.setOnDataReceivedListener(this::handleReceived);
            if (!serialManager.open(port, baudRate)) throw new IOException("JNI串口驱动未能打开设备");
            updateState("CONNECTED", String.format(Locale.US,
                    "已连接 %s @ %d；地址拓扑未确认，自动轮询和逻辑开门保持禁用", port, baudRate), null);
        } catch (Throwable error) {
            closePort();
            updateState("ERROR", "串口连接失败：" + safeMessage(error), error);
        }
    }

    private synchronized JSONObject writeRaw(byte[] bytes, String category) throws Exception {
        if (serialManager == null || !serialManager.isOpen()) throw new IllegalStateException("串口未连接");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("发送内容不能为空");
        serialManager.send(bytes);
        sentBytes += bytes.length;
        JSONObject result = new JSONObject().put("success", true).put("bytes", bytes.length)
                .put("hex", WorkCardProtocol.hex(bytes)).put("category", category);
        notifyData(new JSONObject().put("type", "serialTx")
                .put("timestamp", System.currentTimeMillis()).put("data", result));
        return result;
    }

    private void handleReceived(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        List<WorkCardProtocol.Frame> frames;
        synchronized (this) {
            receivedBytes += bytes.length;
            lastReceivedAt = System.currentTimeMillis();
            for (byte value : bytes) inboundBuffer.add(value);
            frames = WorkCardProtocol.decode(inboundBuffer);
        }
        try {
            notifyData(new JSONObject().put("type", "serialRxRaw")
                    .put("timestamp", System.currentTimeMillis())
                    .put("hex", WorkCardProtocol.hex(bytes)));
        } catch (JSONException ignored) { }
        for (WorkCardProtocol.Frame frame : frames) {
            try {
                JSONObject parsed = frame.function == WorkCardProtocol.FUNCTION_QUERY
                        ? parseBoardStatus(frame) : new JSONObject()
                            .put("slaveAddress", frame.slaveAddress)
                            .put("function", frame.function)
                            .put("dataHex", WorkCardProtocol.hex(frame.data));
                notifyData(new JSONObject().put("type", "unmappedBoardFrame")
                        .put("boardAddress", frame.slaveAddress)
                        .put("frame", parsed)
                        .put("timestamp", System.currentTimeMillis()));
            } catch (Exception error) {
                try { notifyData(new JSONObject().put("type", "serialFrameError")
                        .put("message", safeMessage(error))); }
                catch (JSONException ignored) { }
            }
        }
        notifyStatus();
    }

    private JSONObject parseBoardStatus(WorkCardProtocol.Frame frame) throws JSONException {
        byte[] data = frame.data == null ? new byte[0] : frame.data;
        JSONObject result = new JSONObject().put("boardAddress", frame.slaveAddress)
                .put("rawDataHex", WorkCardProtocol.hex(data));
        if (data.length >= 20) {
            result.put("workCode", data[0] & 0xFF)
                    .put("presenceCode", data[1] & 0xFF)
                    .put("cardCode", data[2] & 0xFF)
                    .put("cardNumber", new String(data, 3, 15, StandardCharsets.US_ASCII)
                            .replace("\u0000", "").trim());
        }
        return result;
    }

    private void ensureDeviceAccessible(String devicePath) throws Exception {
        File device = new File(devicePath);
        if (!device.exists()) {
            lastPermissionHint = "串口设备不存在：" + devicePath;
            throw new IOException(lastPermissionHint);
        }
        if (device.canRead() && device.canWrite()) return;
        String before = accessDescription(device);
        boolean fixed = tryChmodWithRoot(devicePath);
        String after = accessDescription(device);
        lastPermissionHint = "串口权限不足：" + before + "；chmod后：" + after;
        if (!fixed || !device.canRead() || !device.canWrite()) {
            throw new SecurityException(lastPermissionHint
                    + "。需要系统应用权限、厂商白名单或设备侧chmod 666。");
        }
    }

    private static boolean tryChmodWithRoot(String devicePath) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            OutputStream output = process.getOutputStream();
            output.write(("chmod 666 " + shellQuote(devicePath) + "\nexit\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private synchronized void closePort() {
        if (serialManager != null) serialManager.close();
        serialManager = null;
        inboundBuffer.clear();
    }

    private synchronized void updateState(String nextState, String nextMessage, Throwable error) {
        state = nextState;
        message = nextMessage;
        lastError = error == null ? "" : safeMessage(error);
        notifyStatus();
    }

    private void notifyStatus() {
        if (listener == null) return;
        try { listener.onStatusChanged(snapshot()); }
        catch (JSONException ignored) { }
    }

    private void notifyData(JSONObject data) {
        if (listener != null) listener.onDataReceived(data);
    }

    private JSONObject snapshotQuietly() {
        try { return snapshot(); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private static IllegalStateException topologyError() {
        return new IllegalStateException(
                "SERIAL_TOPOLOGY_UNCONFIRMED：文档未定义slotId到从机地址或切组协议");
    }

    private static int positiveInt(Object value, int fallback) {
        try { int result = Integer.parseInt(String.valueOf(value)); return result > 0 ? result : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static long positiveLong(Object value, long fallback) {
        try { long result = Long.parseLong(String.valueOf(value)); return result > 0 ? result : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static byte[] parseHex(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "");
        if (normalized.isEmpty() || (normalized.length() & 1) == 1) {
            throw new IllegalArgumentException("HEX数据长度必须为偶数且不能为空");
        }
        byte[] result = new byte[normalized.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String accessDescription(File file) {
        return "exists=" + file.exists() + ",read=" + file.canRead() + ",write=" + file.canWrite();
    }

    private static boolean isSerialDeviceName(String name) {
        return name.startsWith("ttyS") || name.startsWith("ttyUSB") || name.startsWith("ttyACM")
                || name.startsWith("ttyAMA") || name.startsWith("ttyMT") || name.startsWith("ttyHS")
                || name.startsWith("ttyHSL") || name.startsWith("ttyMSM") || name.startsWith("ttyFIQ")
                || name.startsWith("ttyXRUSB") || name.startsWith("ttymxc") || name.startsWith("ttyO");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
''')

# ---------------------------------------------------------------------------
# FaceAISDK adapter and camera UI from the reference branch.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/FaceAiManager.java', r'''package com.xingyao.card.core;

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
            throw error;
        }
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
''')

write('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
      ref_file('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java'))
replace_once('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
             'import com.xingyao.card.core.FaceAiManager;\n', '')
replace_regex('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
              r'''\s*FaceAiManager manager = FaceAiManager\.getInstance\(\);\s*\n\s*String faceFeature = manager\.extractFaceFeature\(croppedBitmap\);''',
              '''\n            String faceFeature = DeviceRuntimeRegistry.require().extractFaceFeature(croppedBitmap);''')
replace_regex('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
              r'''\s*manager\.insertFaceFeature\(faceId, faceFeature,\s*\n\s*faceName != null \? faceName : faceId, ""\);''', '')
replace_once('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
             '.setThreshold(0.85f)', '.setThreshold(configuredFaceThreshold())')
replace_once('app/src/main/java/com/xingyao/card/FaceEnrollmentController.java',
             '''    private void initFaceSearch() {''',
             '''    private float configuredFaceThreshold() {
        try { return DeviceRuntimeRegistry.require().faceRecognitionThreshold(); }
        catch (Exception ignored) { return 0.8f; }
    }

    private void initFaceSearch() {''')

# MainActivity: reference camera/FaceAISDK flow + current secure bridge/data-layer callbacks.
write('app/src/main/java/com/xingyao/card/MainActivity.java',
      ref_file('app/src/main/java/com/xingyao/card/MainActivity.java'))
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'import android.content.Intent;\n', 'import android.Manifest;\nimport android.content.Intent;\n')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'import androidx.core.content.ContextCompat;\n',
             'import androidx.core.content.ContextCompat;\nimport androidx.core.app.ActivityCompat;\n')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'import com.xingyao.card.service.DeviceCoreService;\n',
             'import com.xingyao.card.core.DeviceRuntimeRegistry;\nimport com.xingyao.card.service.DeviceCoreService;\n')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             '    private static final int LOCAL_HTTP_PORT = 8088;\n',
             '    private static final int LOCAL_HTTP_PORT = 8088;\n    private static final int REQUEST_CAMERA = 4201;\n')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             '''        startDeviceCoreService();
        DeviceCoreService.setDeviceEventListener(this::sendBridgeEvent);''',
             '''        startDeviceCoreService();
        DeviceRuntimeRegistry.setUiListener(this::sendBridgeEvent);''')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             '''        Log.d(TAG, "onCreate prewarmCameraX...");
        prewarmCameraX();''',
             '''        Log.d(TAG, "onCreate prewarmCameraX...");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            prewarmCameraX();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }''')
replace_regex('app/src/main/java/com/xingyao/card/MainActivity.java',
              r'''    @Override\n    public void onRequestPermissionsResult\(int requestCode, String\[] permissions, int\[] grantResults\) \{\n        super\.onRequestPermissionsResult\(requestCode, permissions, grantResults\);\n    \}''',
              '''    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prewarmCameraX();
        }
    }''')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'webViewManager.loadUrl("http://localhost:" + LOCAL_HTTP_PORT + "/index.html");',
             'webViewManager.loadUrl("http://127.0.0.1:" + LOCAL_HTTP_PORT + "/index.html");')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             '''    public void sendBridgeResponse(JSONObject response) {''',
             '''    public boolean isOriginScopedBridgeEnabled() {
        return webViewManager != null && webViewManager.isOriginScopedBridgeEnabled();
    }

    public void sendBridgeResponse(JSONObject response) {''')
replace_regex('app/src/main/java/com/xingyao/card/MainActivity.java',
              r'''            @Override\n            public void onFaceEnrolled\(String faceId, String faceFeature, float score\) \{.*?\n            \}\n\n            @Override\n            public void onFaceVerified\(String faceId, float score\) \{.*?\n            \}''',
              '''            @Override
            public void onFaceEnrolled(String faceId, String faceFeature, float score) {
                String reqId = consumeFaceRequestId();
                hideFaceOverlay();
                if (reqId == null) return;
                try {
                    JSONObject data = DeviceRuntimeRegistry.require()
                            .completeFaceEnrollment(faceId, "", faceFeature, score);
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
                    JSONObject data = DeviceRuntimeRegistry.require()
                            .completeFaceVerification(faceId, score);
                    sendBridgeResponse(new JSONObject().put("type", "response")
                            .put("requestId", reqId).put("success", true).put("data", data));
                } catch (Exception error) {
                    sendBridgeError(reqId, "FACE_VERIFICATION_FAILED", safeMessage(error));
                }
            }''')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'DeviceCoreService.recordOperation("biometric.fingerprint." + operation.toLowerCase(), response);',
             '''DeviceRuntimeRegistry.record("biometric.fingerprint." + operation.toLowerCase(), response);
            if (enrollment) DeviceRuntimeRegistry.require()
                    .markFingerprintAuthorized(employeeId, employeeName);''')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             'DeviceCoreService.setDeviceEventListener(null);',
             '''DeviceRuntimeRegistry.setUiListener(null);
        if (faceController != null) faceController.stop();
        if (mCameraProvider != null) mCameraProvider.unbindAll();''')
replace_once('app/src/main/java/com/xingyao/card/MainActivity.java',
             '''    private static String fingerprintAvailabilityMessage(int availability) {''',
             '''    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    private static String fingerprintAvailabilityMessage(int availability) {''')

# ---------------------------------------------------------------------------
# Android data layer integration.
# ---------------------------------------------------------------------------
replace_once('app/src/main/java/com/xingyao/card/core/DeviceDataRepository.java',
             '''    public synchronized JSONArray searchEmployees(String query) throws JSONException {''',
             '''    public synchronized boolean hasEmployee(String id) {
        String target = id == null ? "" : id.trim();
        return !target.isEmpty() && findEmployeeKey(target) != null;
    }

    public synchronized JSONObject employee(String id) throws JSONException {
        String target = id == null ? "" : id.trim();
        String key = findEmployeeKey(target);
        return key == null ? null : copy(employees.get(key));
    }

    public synchronized JSONArray searchEmployees(String query) throws JSONException {''')

path = 'app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java'
value = read(path)
value = value.replace('ArcFaceManager', 'FaceAiManager')
value = value.replace('ArcFaceTemplateCleaner', 'FaceAiManager')
value = value.replace('arcFaceManager', 'faceAiManager')
value = value.replace('templateCleaner', 'faceAiManager')
value = value.replace('虹软人脸引擎未接入', 'FaceAISDK未接入')
value = value.replace('虹软人脸引擎未就绪', 'FaceAISDK未就绪')
value = value.replace('''        java.util.HashSet<String> activeEmployeeIds = new java.util.HashSet<>();
''', '')
value = value.replace('''            activeEmployeeIds.add(employeeId);
''', '')
value = value.replace('''        if (full && failures.length() == 0) {
            deletedCount += faceAiManager.deleteTemplatesNotIn(activeEmployeeIds);
        }
''', '')
# Employee IDs are mandatory in V4.1. Never synthesize a business key from employeeCode.
value = value.replace('''            if (!employeeId.isEmpty()) {
                item.put("id", employeeId).put("employeeId", employeeId);
            } else if (!source.optString("employeeCode", "").trim().isEmpty()) {
                item.put("id", source.optString("employeeCode"));
            }
''', '''            if (employeeId.isEmpty()) continue;
            item.put("id", employeeId).put("employeeId", employeeId);
''')
write(path, value)

path = 'app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java'
value = read(path)
value = value.replace('import org.json.JSONArray;', 'import android.graphics.Bitmap;\n\nimport org.json.JSONArray;')
value = value.replace('ArcFaceManager', 'FaceAiManager')
value = value.replace('arcFaceManager', 'faceAiManager')
value = value.replace('    private final ArcFaceTemplateCleaner templateCleaner;\n', '')
value = value.replace('                           ArcFaceTemplateCleaner templateCleaner,\n', '')
value = value.replace('        this.templateCleaner = templateCleaner;\n', '')
value = value.replace('if (!employeeId.isEmpty()) templateCleaner.deleteTemplate(employeeId);',
                      'if (!employeeId.isEmpty()) faceAiManager.deleteTemplate(employeeId);')
# Replace ArcSoft frame methods with FaceAISDK completion methods used by MainActivity.
value, count = re.subn(r'''    public JSONObject enrollFace\(String employeeId, String employeeName, byte\[] frame,\n                                 int width, int height\) throws Exception \{.*?\n    public void markFingerprintAuthorized''', r'''    public String extractFaceFeature(Bitmap bitmap) {
        return faceAiManager.extractFaceFeature(bitmap);
    }

    public float faceRecognitionThreshold() {
        try {
            double value = settingsRepository.load().optDouble("faceRecognitionThreshold", 0.8D);
            return (float) Math.max(0D, Math.min(1D, value));
        } catch (Exception ignored) {
            return 0.8F;
        }
    }

    public JSONObject completeFaceEnrollment(String employeeId, String employeeName,
                                             String faceFeature, float score) throws Exception {
        String id = employeeId == null ? "" : employeeId.trim();
        if (!dataRepository.hasEmployee(id)) {
            throw new IllegalStateException("员工不存在，禁止仅凭本机人脸录入创建后台员工资料");
        }
        JSONObject employee = dataRepository.employee(id);
        String resolvedName = employeeName == null || employeeName.trim().isEmpty()
                ? employee == null ? "" : employee.optString("employeeName", "")
                : employeeName.trim();
        faceAiManager.awaitReady(8000L);
        JSONObject result = faceAiManager.enrollFeature(id, resolvedName, faceFeature, "LOCAL_CAMERA")
                .put("similarity", score).put("engine", "FaceAISDK");
        dataRepository.markFaceRegistered(id, resolvedName, true);
        stateStore.record("biometric.face.enrolled", result);
        stateStore.emit("sync.employeeChanged", new JSONObject()
                .put("employeeId", id).put("faceRegistered", true));
        return result;
    }

    public JSONObject completeFaceVerification(String employeeId, float score) throws Exception {
        String id = employeeId == null ? "" : employeeId.trim();
        if (!dataRepository.hasEmployee(id)) {
            throw new IllegalStateException("FaceAISDK识别结果没有对应的Android员工数据：" + id);
        }
        JSONObject result = new JSONObject().put("success", true)
                .put("employeeId", id).put("similarity", score).put("engine", "FaceAISDK");
        int slotNumber = stateStore.pickTakeSlot();
        if (slotNumber < 1) {
            result.put("doorOpen", false).put("status", "NO_AVAILABLE_CARD")
                    .put("message", "人脸识别成功，但当前没有已确认可取的卡槽");
        } else {
            try {
                JSONObject door = openDoor(slotNumber, false, "TAKE", "FACE",
                        "", "FACE", id);
                result.put("doorOpen", true).put("slotNumber", slotNumber).put("door", door);
            } catch (IllegalStateException topologyError) {
                result.put("doorOpen", false).put("slotNumber", slotNumber)
                        .put("status", "SERIAL_TOPOLOGY_UNCONFIRMED")
                        .put("message", topologyError.getMessage());
            }
        }
        stateStore.record("biometric.face.verified", result);
        return result;
    }

    public void markFingerprintAuthorized''', value, count=1, flags=re.S)
if count != 1:
    raise RuntimeError('DeviceDataLayer face method block not found')
write(path, value)

path = 'app/src/main/java/com/xingyao/card/service/DeviceCoreService.java'
value = read(path)
value = value.replace('ArcFaceManager', 'FaceAiManager')
value = value.replace('arcFaceManager', 'faceAiManager')
value = value.replace('        ArcFaceTemplateCleaner templateCleaner = new ArcFaceTemplateCleaner(this);\n', '')
value = value.replace('dataRepository, faceAiManager, templateCleaner, httpGateway',
                      'dataRepository, faceAiManager, faceAiManager, httpGateway')
value = value.replace('syncManager, serialPort, backendPort, faceAiManager, templateCleaner, httpGateway,',
                      'syncManager, serialPort, backendPort, faceAiManager, httpGateway,')
value = value.replace('faceAiManager = new FaceAiManager(this, status -> {',
                      'faceAiManager = FaceAiManager.getInstance();\n        faceAiManager.init(this, status -> {')
# Current serial constructor must no longer receive Context/settings repository.
value = value.replace('serialManager = new SerialConnectionManager(this, new SerialConnectionManager.Listener() {',
                      'serialManager = new SerialConnectionManager(new SerialConnectionManager.Listener() {')
write(path, value)

# Slot grouping is presentation-only. Do not publish an unproven board address.
path = 'app/src/main/java/com/xingyao/card/core/SlotStateRepository.java'
value = read(path)
value = value.replace('''        slot.put("slotNumber", slotNumber)
                .put("displayNumber", String.format(Locale.US, "%02d", slotNumber))
                .put("groupNumber", groupNumber)
                .put("groupSlotNumber", groupSlotNumber)
                .put("boardAddress", groupSlotNumber)
                .put("boardAddressLabel", "BOARD-" + String.format(Locale.US, "%02d", groupSlotNumber));''',
'''        slot.put("slotNumber", slotNumber)
                .put("displayNumber", String.format(Locale.US, "%02d", slotNumber))
                .put("groupNumber", groupNumber)
                .put("groupSlotNumber", groupSlotNumber);''')
write(path, value)

# Vue displays only fields received from Android/backend.
path = 'uniapp/src/services/index.js'
value = read(path)
value = value.replace("  id: String(item.id || item.employeeId || item.employeeCode || `EMP-${index}`),",
                      "  id: String(item.employeeId || item.id || ''),")
value = value.replace("  employeeCode: String(item.employeeCode || item.employeeId || ''),",
                      "  employeeCode: String(item.employeeCode || ''),")
value = value.replace("  avatarUrl: item.avatarUrl || item.faceImageUrl || '/static/avatars/employee-1.jpg',",
                      "  avatarUrl: item.avatarUrl || item.faceImageUrl || '',")
value = value.replace("  deviceIds: item.deviceIds || [appState.settings.deviceId || appState.settings.deviceCode]",
                      "  deviceIds: Array.isArray(item.deviceIds) ? item.deviceIds : []")
write(path, value)

# Remove abandoned ArcSoft implementation from the source tree.
for path in [
    'app/src/main/java/com/xingyao/card/core/ArcFaceManager.java',
    'app/src/main/java/com/xingyao/card/core/ArcFaceTemplateCleaner.java',
    'app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java',
    'app/libs/arcsoft_face.jar',
]:
    target = ROOT / path
    if target.exists(): target.unlink()

print('stage1 reference device migration applied')
