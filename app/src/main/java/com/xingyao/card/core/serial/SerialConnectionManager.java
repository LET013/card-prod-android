package com.xingyao.card.core.serial;

import android.util.Log;

import com.xingyao.serialport.SerialManager;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 纯字节级串口 I/O 管理器。
 *
 * - 不知协议（WorkCardProtocol、Frame、功能码、卡槽、轮询等概念）。
 * - 只提供：open、close、write、isOpen、setOnDataReceived、snapshot、stats。
 * - 内部 WriteWorker 线程从 LinkedBlockingQueue 取 byte[] 串行写入硬件。
 * - 不依赖 Activity、Bridge、Facade、Store 或业务 Coordinator。
 */
public final class SerialConnectionManager {
    private static final String TAG = "SerialConnectionManager";

    // ────────────────────── 状态 ──────────────────────

    private SerialManager serialManager;
    private String state = "DISCONNECTED";
    private String message = "串口未连接";
    private String port = "";
    private int baudRate;
    private long sentBytes;
    private long lastErrorAt;
    private String lastError = "";

    // ────────────────────── 写队列 + WriteWorker ──────────────────────

    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private final Object writeLock = new Object();
    private volatile Thread writeWorker;
    private volatile boolean writeWorkerRunning;

    // ────────────────────── 读回调 ──────────────────────

    /** 字节级数据到达回调 */
    public interface OnDataReceived {
        void onData(byte[] data);
    }

    private volatile OnDataReceived dataCallback;

    // ────────────────────── 构造函数 ──────────────────────

    public SerialConnectionManager() { }

    // ═══════════════════ 公开 API ═══════════════════

    /**
     * 打开串口连接。
     *
     * @param targetPort     设备路径，如 /dev/ttyS5
     * @param targetBaudRate 波特率
     * @return true 表示连接成功
     */
    public boolean open(String targetPort, int targetBaudRate) {
        close();
        port = targetPort;
        baudRate = targetBaudRate;
        lastError = "";
        Log.d(TAG, "正在打开串口: " + port + " @ " + baudRate);

        try {
            ensureDeviceAccessible(port);
            serialManager = new SerialManager();
            serialManager.setOnDataReceivedListener(this::onDataReceived);
            if (!serialManager.open(port, baudRate)) {
                throw new IllegalStateException("串口驱动未能打开设备");
            }
            state = "CONNECTED";
            message = String.format(Locale.US, "已连接 %s @ %d", port, baudRate);
            writeWorkerRunning = true;
            Thread worker = new Thread(this::runWriteWorker, "SerialConn-WriteWorker");
            worker.setDaemon(true);
            writeWorker = worker;
            worker.start();
            return true;
        } catch (Exception error) {
            closeInternal();
            state = "ERROR";
            message = "串口连接失败：" + safeMessage(error);
            lastError = safeMessage(error);
            lastErrorAt = System.currentTimeMillis();
            Log.e(TAG, message, error);
            return false;
        }
    }

    /** 关闭串口连接 */
    public void close() {
        closeInternal();
        state = "DISCONNECTED";
        message = "串口已关闭";
    }

    /** 串口是否已打开 */
    public boolean isOpen() {
        return serialManager != null && serialManager.isOpen();
    }

    /**
     * 写入原始字节（线程安全，非阻塞）。
     * 字节进入内部队列，由 WriteWorker 串行写入硬件。
     */
    public void write(byte[] data) {
        if (data == null || data.length == 0) return;
        if (!isOpen()) {
            Log.w(TAG, "write: 串口未连接，丢弃 " + data.length + " 字节");
            return;
        }
        writeQueue.offer(data);
    }

    /** 等待普通写队列排空后执行真实写入，并把 IOException 返回调用方。 */
    public void writeDirect(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("串口写入数据不能为空");
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!writeQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("等待串口普通队列排空时被中断", error);
            }
        }
        if (!writeQueue.isEmpty()) throw new IOException("串口普通写队列未能及时排空");
        synchronized (writeLock) {
            SerialManager manager = serialManager;
            if (manager == null || !manager.isOpen()) throw new IOException("串口未连接");
            manager.send(data);
            sentBytes += data.length;
        }
    }

    /** 设置字节级数据回调 */
    public void setOnDataReceived(OnDataReceived callback) {
        this.dataCallback = callback;
    }

    /** 简化快照（仅 I/O 信息，不含协议字段） */
    public JSONObject snapshot() {
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("message", message)
                    .put("port", port)
                    .put("baudRate", baudRate)
                    .put("sentBytes", sentBytes)
                    .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ────────────────────── 静态工具 ──────────────────────

    /** 列出 /dev 下常见串口设备 */
    public static JSONObject listAvailablePorts() throws JSONException {
        JSONArray ports = new JSONArray();
        List<File> candidates = new ArrayList<>();
        File dev = new File("/dev");
        File[] files = dev.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isSerialDeviceName(file.getName())) candidates.add(file);
            }
        }
        Collections.sort(candidates,
                (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        for (File file : candidates) {
            ports.put(new JSONObject()
                    .put("path", file.getAbsolutePath())
                    .put("readable", file.canRead())
                    .put("writable", file.canWrite())
                    .put("exists", file.exists()));
        }
        return new JSONObject()
                .put("ports", ports)
                .put("count", ports.length())
                .put("message", ports.length() == 0
                        ? "未在 /dev 下发现常见串口节点"
                        : "发现 " + ports.length() + " 个候选串口");
    }

    // ═══════════════════ 内部实现 ═══════════════════

    // ────────────────────── WriteWorker ──────────────────────

    private void runWriteWorker() {
        Thread current = Thread.currentThread();
        while (writeWorkerRunning && writeWorker == current) {
            try {
                byte[] data = writeQueue.poll(200, TimeUnit.MILLISECONDS);
                if (data == null) continue;

                SerialManager mgr = serialManager;
                if (mgr == null || !mgr.isOpen()) {
                    Log.w(TAG, "WriteWorker: 串口未打开，跳过 " + data.length + " 字节");
                    continue;
                }
                synchronized (writeLock) {
                    mgr.send(data);
                    sentBytes += data.length;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "WriteWorker error: " + e.getMessage());
            }
        }
        // 退出前清空队列
        writeQueue.clear();
    }

    // ────────────────────── 数据接收 ──────────────────────

    private void onDataReceived(byte[] bytes) {
        OnDataReceived cb = dataCallback;
        if (cb != null && bytes != null && bytes.length > 0) {
            try {
                cb.onData(bytes);
            } catch (Exception e) {
                Log.e(TAG, "onDataReceived callback error: " + e.getMessage());
            }
        }
    }

    // ────────────────────── 连接管理 ──────────────────────

    private void closeInternal() {
        writeWorkerRunning = false;
        Thread worker = writeWorker;
        writeWorker = null;
        if (worker != null) {
            worker.interrupt();
        }
        writeQueue.clear();
        if (serialManager != null) {
            try { serialManager.close(); } catch (Exception ignored) { }
            serialManager = null;
        }
    }

    // ────────────────────── 权限处理（OS 级，非协议） ──────────────────────

    private void ensureDeviceAccessible(String devicePath) throws Exception {
        File device = new File(devicePath);
        if (!device.exists()) {
            throw new IOException("串口设备不存在：" + devicePath);
        }
        if (device.canRead() && device.canWrite()) return;
        boolean fixed = tryChmodWithRoot(devicePath);
        if (!fixed || !device.canRead() || !device.canWrite()) {
            throw new SecurityException("串口设备权限不足。请让设备侧执行 chmod 666 " + devicePath
                    + "，或把APP做成有串口权限的系统应用/厂商白名单应用后再重连。");
        }
    }

    private static boolean tryChmodWithRoot(String devicePath) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            Process runningProcess = process;
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(1500L);
                    runningProcess.destroy();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "SerialChmodTimeout");
            killer.setDaemon(true);
            killer.start();
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isSerialDeviceName(String name) {
        return name.startsWith("ttyS")
                || name.startsWith("ttyUSB")
                || name.startsWith("ttyACM")
                || name.startsWith("ttyAMA")
                || name.startsWith("ttyMT")
                || name.startsWith("ttyHS")
                || name.startsWith("ttyHSL")
                || name.startsWith("ttyMSM")
                || name.startsWith("ttyFIQ")
                || name.startsWith("ttyXRUSB")
                || name.startsWith("ttymxc")
                || name.startsWith("ttyO");
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
