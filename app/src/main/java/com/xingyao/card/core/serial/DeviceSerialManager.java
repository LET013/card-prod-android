package com.xingyao.card.core.serial;

import android.text.TextUtils;
import android.util.Log;

import com.xingyao.card.core.SlotStateManager;
import com.xingyao.card.core.log.AppLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备串口管理门面（对外唯一入口）。
 *
 * 职责：
 * - 持有纯字节 I/O 的 {@link SerialConnectionManager}
 * - 持有帧路由的 {@link SerialMessageRouter}
 * - 持有卡槽状态缓存的 {@link SlotStateManager}
 * - 双队列调度：manualQueue（手动命令立即优先） + pollQueue（轮询排队）
 * - SendWorker 单线程串行发送
 * - PollingScheduler 定时批量轮询 + 超时检测
 *
 * 外部（DeviceCoreService）只应通过此类访问串口功能。
 */
public final class DeviceSerialManager {
    private static final String TAG = "DeviceSerialManager";

    // ────────────────────── 常量 ──────────────────────
    private static final String DEFAULT_PORT = "/dev/ttyS5";
    private static final int DEFAULT_BAUD_RATE = 57600;
    private static final long RESPONSE_TIMEOUT_MS = 700L;
    private static final long DOOR_RESPONSE_TIMEOUT_MS = 300L;
    private static final long POLL_TICK_INTERVAL_MS = 200L;
    private static final long DEFAULT_SLOT_PUSH_INTERVAL_MS = 60_000L;
    private static final long SLOT_EVENT_BATCH_WINDOW_MS = 100L;
    private static final long SLOT_EVENT_HEARTBEAT_MS = 3_000L;
    private static final int MANUAL_QUEUE_CAPACITY = 32;
    private static final int POLL_QUEUE_CAPACITY = 256;
    private static final long MAX_FIRMWARE_BYTES = 64L * 1024L * 1024L;

    // ────────────────────── 可配置运行时参数（服务端默认值见注释） ──────────────────────
    /** 单板响应超时(ms)。安全兜底值，RS-485 正常时从机 < 100ms 应答。默认值: {@code 3000}（服务端），代码常量备份 {@value #RESPONSE_TIMEOUT_MS} */
    private volatile long responseTimeoutMs = RESPONSE_TIMEOUT_MS;
    /** 轮询 tick 间隔(ms)。默认值: {@code 200}。RS-485 57600 波特率下从机应答 < 100ms，200ms 间隔安全 */
    private volatile long pollTickIntervalMs = POLL_TICK_INTERVAL_MS;

    // ────────────────────── 监听器 ──────────────────────

    /** 对外回调（连接到 DeviceDataLayer） */
    public interface Listener {
        void onStatusChanged(JSONObject status);
        /** 收到设备返回的消息（原始字节 / 解析后的帧） */
        void onDataReceived(JSONObject data);
        /** 手动命令已发送成功（openDoor/querySlot/openAllDoors 等） */
        default void onDataManualSent(JSONObject data) {}
        /** 轮询命令已发送成功 */
        default void onDataPollSent(JSONObject data) {}
        /** 单个卡槽状态更新（收到查询应答后立即回调，不等待批量推送） */
        void onSlotStatus(JSONObject slot);
        /** 合并后的卡槽状态更新；默认回退到旧的单槽监听，保障现有调用方兼容。 */
        default void onSlotStatusBatch(JSONArray slots) {
            if (slots == null) return;
            for (int index = 0; index < slots.length(); index++) {
                JSONObject slot = slots.optJSONObject(index);
                if (slot != null) onSlotStatus(slot);
            }
        }
        /** 完整卡槽快照；用于首轮广播收齐后的一次性页面刷新。 */
        default void onSlotsSnapshot(JSONArray slots) {}
    }

    public interface FirmwareProgressListener {
        void onProgress(JSONObject progress);
    }

    public static final class FirmwareUpgradeCancelledException extends IOException {
        FirmwareUpgradeCancelledException() { super("固件传输已取消"); }
    }

    // ────────────────────── 内部组件 ──────────────────────

    private final SerialTransport serialConn;
    private final SerialMessageRouter messageRouter;
    private final SlotStateManager slotStateManager;
    private Listener listener;

    // ────────────────────── 双队列 ──────────────────────

    /** 手动命令队列（openDoor、querySlot 等），容量 32，put() 阻塞 */
    private final ArrayBlockingQueue<SendTask> manualQueue = new ArrayBlockingQueue<>(MANUAL_QUEUE_CAPACITY);
    /** 轮询队列，容量 256，offer() 非阻塞 */
    private final ArrayBlockingQueue<SendTask> pollQueue = new ArrayBlockingQueue<>(POLL_QUEUE_CAPACITY);

    // ────────────────────── 发送任务 ──────────────────────

    private static class SendTask {
        final byte[] data;
        final String category;
        final boolean isManual;
        final int slotAddress;
        final int expectedFunction;
        final int firstResponseAddress;
        final int lastResponseAddress;
        final boolean collectQueryFrames;
        final boolean detectsTotalSlots;
        final boolean retryOnTimeout;
        final long timeoutMs;

        SendTask(byte[] data, String category, boolean isManual, int slotAddress) {
            this(data, category, isManual, slotAddress, 0, 0, 0,
                    false, false, false, 0L);
        }

        SendTask(byte[] data, String category, boolean isManual, int slotAddress,
                 int expectedFunction, int firstResponseAddress, int lastResponseAddress,
                 boolean collectQueryFrames, boolean detectsTotalSlots,
                 boolean retryOnTimeout, long timeoutMs) {
            this.data = data;
            this.category = category;
            this.isManual = isManual;
            this.slotAddress = slotAddress;
            this.expectedFunction = expectedFunction;
            this.firstResponseAddress = firstResponseAddress;
            this.lastResponseAddress = lastResponseAddress;
            this.collectQueryFrames = collectQueryFrames;
            this.detectsTotalSlots = detectsTotalSlots;
            this.retryOnTimeout = retryOnTimeout;
            this.timeoutMs = timeoutMs;
        }

        boolean waitsForResponse() {
            return expectedFunction != 0 && timeoutMs > 0;
        }
    }

    private enum ResponseWaitResult { RESPONSE, TIMEOUT, CANCELLED }

    private static final class PendingResponse {
        private final int expectedFunction;
        private final int firstAddress;
        private final int lastAddress;
        private final boolean collectQueryFrames;
        private final long timeoutMs;
        private final Set<Integer> queryAddresses = new HashSet<>();
        private boolean cancelled;
        private boolean completed;
        private boolean received;
        private long lastResponseAt;

        PendingResponse(SendTask task) {
            expectedFunction = task.expectedFunction;
            firstAddress = task.firstResponseAddress;
            lastAddress = task.lastResponseAddress;
            collectQueryFrames = task.collectQueryFrames;
            timeoutMs = task.timeoutMs;
        }

        synchronized void onFrame(int address, int function) {
            if (cancelled || completed || function != expectedFunction
                    || address < firstAddress || address > lastAddress) return;
            received = true;
            lastResponseAt = System.currentTimeMillis();
            if (function == WorkCardProtocol.FUNCTION_QUERY) queryAddresses.add(address);
            if (!collectQueryFrames) completed = true;
            notifyAll();
        }

        synchronized ResponseWaitResult await() throws InterruptedException {
            long noResponseDeadline = System.currentTimeMillis() + timeoutMs;
            while (!cancelled && !completed) {
                long deadline = received ? lastResponseAt + timeoutMs : noResponseDeadline;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return received ? ResponseWaitResult.RESPONSE : ResponseWaitResult.TIMEOUT;
                wait(remaining);
            }
            return cancelled ? ResponseWaitResult.CANCELLED : ResponseWaitResult.RESPONSE;
        }

        synchronized int detectedTotalSlots() {
            if (queryAddresses.isEmpty()) return 0;
            int maxAddress = 0;
            for (Integer address : queryAddresses) if (address != null && address > maxAddress) maxAddress = address;
            for (int address = 1; address <= maxAddress; address++) {
                if (!queryAddresses.contains(address)) return 0;
            }
            return maxAddress;
        }

        synchronized void cancel() {
            cancelled = true;
            notifyAll();
        }
    }

    // ────────────────────── SendWorker ──────────────────────

    private final Thread sendWorker;
    private volatile boolean sendWorkerRunning;
    private volatile boolean started;
    private final Object firmwareLock = new Object();
    private final AtomicBoolean firmwareCancelRequested = new AtomicBoolean(false);
    private volatile boolean firmwareUpgradeInProgress;

    // ────────────────────── 轮询状态 ──────────────────────

    private final ScheduledExecutorService pollingExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService slotEventExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pollingTask;
    private ScheduledFuture<?> slotEventTask;
    private volatile boolean pollingEnabled;
    private volatile boolean polling;

    private int totalSlots = 100;
    private int groupSize = 10;
    private String pollingMode = "GROUP";
    private int currentPollAddress = 1;
    private final ConcurrentHashMap<String, Long> pendingManualResponses = new ConcurrentHashMap<>();
    private volatile PendingResponse activeResponse;

    /** 队列级去重：pollQueue 中已有轮询任务的槽位 */
    private boolean[] pendingPollSlots;

    /** 首次轮询是否已推送快照 */
    private volatile boolean firstRoundPushed;
    /** 广播查询尚未达到接收静默条件时，不向页面逐条推送卡槽状态。 */
    private volatile boolean startupBroadcastActive;
    private final Object slotEventLock = new Object();
    private final Map<Integer, JSONObject> pendingSlotEvents = new TreeMap<>();
    private final Map<Integer, SlotEventPublication> lastPublishedSlotEvents = new HashMap<>();

    private static final class SlotEventPublication {
        final String signature;
        final long publishedAt;

        SlotEventPublication(String signature, long publishedAt) {
            this.signature = signature;
            this.publishedAt = publishedAt;
        }
    }

    // ────────────────────── 连接状态 ──────────────────────

    private String state = "DISCONNECTED";
    private String message = "串口未连接";
    private String port = DEFAULT_PORT;
    private int baudRate = DEFAULT_BAUD_RATE;
    private long sentBytes;
    private long receivedBytes;
    private long lastReceivedAt;
    private String lastError = "";
    private long connectionGeneration;

    // ────────────────────── 构造函数 ──────────────────────

    public DeviceSerialManager() {
        this(false);
    }

    public DeviceSerialManager(boolean simulatorEnabled) {
        this.serialConn = simulatorEnabled
                ? new SimulatedSerialTransport()
                : new RealSerialTransport();
        this.messageRouter = new SerialMessageRouter();
        this.slotStateManager = new SlotStateManager();
        this.pendingPollSlots = new boolean[totalSlots + 1]; // 1-based

        // 串口字节回调 → 消息路由
        serialConn.setOnDataReceived(this::onRawBytesReceived);

        // 消息路由 → 业务分发
        messageRouter.setCallback(new SerialMessageRouter.FrameCallback() {
            @Override
            public void onQueryResponse(int address, JSONObject slotStatus) {
                handleQueryResponse(address, slotStatus);
            }

            @Override
            public void onDoorResponse(int address, boolean accepted, JSONObject frameInfo) {
                handleFrameData(frameInfo);
            }

            @Override
            public void onVersionResponse(int address, String version, JSONObject frameInfo) {
                handleFrameData(frameInfo);
            }

            @Override
            public void onAnyFrame(JSONObject frameInfo) {
                handleFrameData(frameInfo);
            }
        });

        // SlotStateManager 订阅 → 对外逐条推送
        slotStateManager.subscribe(snapshot -> {
            notifySlotsSnapshot(snapshot);
        }, DEFAULT_SLOT_PUSH_INTERVAL_MS);

        // SendWorker 线程
        sendWorker = new Thread(this::runSendWorker, "DeviceSerial-SendWorker");
        sendWorker.setDaemon(true);
    }

    // ────────────────────── 公开 API ──────────────────────

    /** 设置外部回调 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 启动串口连接与轮询 */
    public synchronized void start() {
        if (started) return;
        started = true;
        //启动卡槽实时状态管理
        slotStateManager.start();
        sendWorkerRunning = true;
        sendWorker.start();
    }

    /** 停止串口连接与轮询 */
    public synchronized void stop() {
        if (!started) {
            serialConn.close();
            return;
        }
        started = false;
        stopPolling();
        sendWorkerRunning = false;
        cancelActiveResponse();
        sendWorker.interrupt();
        serialConn.close();
        slotStateManager.stop();
        stopSlotEventDispatch();
        manualQueue.clear();
        pollQueue.clear();
        firstRoundPushed = false;
        startupBroadcastActive = false;
        clearSlotEventPublications();
        updateState("DISCONNECTED", "原生串口已停止", null);
    }

    /** 配置串口参数并打开连接 */
    public synchronized void configure(JSONObject settings) {
        String targetPort = settings == null
                ? DEFAULT_PORT
                : settings.optString("serialPort", DEFAULT_PORT).trim();
        int targetBaudRate = settings == null
                ? DEFAULT_BAUD_RATE
                : settings.optInt("baudRate", DEFAULT_BAUD_RATE);
        if (settings != null) {
            // 卡槽总数 — 服务端默认值: 100。兼容旧 key "totalCount"
            totalSlots = settings.optInt("totalSlots",
                    settings.optInt("totalCount", 100));
            if (serialConn.isSimulator()) totalSlots = serialConn.simulatedSlotCount();
            groupSize = settings.optInt("groupSize", settings.optInt("singleGroupCount", 10));
            if (groupSize < 1) groupSize = 1;
            if (groupSize > totalSlots) groupSize = totalSlots;
            pollingMode = "SINGLE".equalsIgnoreCase(settings.optString("pollingMode", "GROUP"))
                    ? "SINGLE" : "GROUP";

            // 串口轮询开关 — 服务端默认值: true。兼容旧 key "serialPollingEnabled" / "singleGroupPollingEnabled"
            pollingEnabled = settings.optBoolean("serialPollEnabled",
                    settings.optBoolean("serialPollingEnabled",
                            settings.optBoolean("singleGroupPollingEnabled", false)));

            // 单板响应超时(ms) — 安全兜底值，RS-485 正常时从机应答 << 此值
            responseTimeoutMs = settings.optLong("serialResponseTimeout", RESPONSE_TIMEOUT_MS);
            if (responseTimeoutMs < 100) {
                android.util.Log.w(TAG, "serialResponseTimeout too low: " + responseTimeoutMs
                        + "ms, using default: " + RESPONSE_TIMEOUT_MS + "ms");
                responseTimeoutMs = RESPONSE_TIMEOUT_MS;
            }

            // 轮询 tick 间隔(ms) — tick 只做超时检测和兜底推进，正常应答后立即触发下一轮询
            pollTickIntervalMs = settings.optLong("serialPollInterval",
                    settings.optLong("pollTickInterval", POLL_TICK_INTERVAL_MS));
        }
        // open() 依据上述配置决定是否启动轮询，避免首次启动仍使用默认关闭状态。
        firstRoundPushed = false; // 配置变更后重新等待首次轮询完成
        clearSlotEventPublications();
        open(targetPort, targetBaudRate);
        notifyStatus();
    }

    /** 重新连接串口 */
    public synchronized void reconnect() {
        open(port, baudRate);
    }

    /** 断开串口但保留管理器和发送线程，允许后续重新连接。 */
    public synchronized JSONObject disconnect() throws JSONException {
        if (firmwareUpgradeInProgress) {
            throw new IllegalStateException("固件传输进行中，不能断开串口");
        }
        stopPolling();
        cancelActiveResponse();
        serialConn.close();
        manualQueue.clear();
        pollQueue.clear();
        firstRoundPushed = false;
        startupBroadcastActive = false;
        updateState("DISCONNECTED", "串口未连接", null);
        return snapshot();
    }

    /** 启用/禁用轮询 */
    public synchronized JSONObject setPollingEnabled(boolean enabled) throws JSONException {
        pollingEnabled = enabled;
        if (enabled && serialConn.isOpen()) {
            startPolling();
            message = String.format(Locale.US, "已连接 %s @ %d，自动轮询已开启", port, baudRate);
        } else {
            stopPolling();
            if (serialConn.isOpen()) {
                message = String.format(Locale.US, "已连接 %s @ %d，自动轮询已关闭", port, baudRate);
            }
        }
//        notifyStatus();
        return snapshot();
    }

    // ────────────────────── 手动命令 API（入 manualQueue） ──────────────────────

    /** 发送原始数据（HEX 或 UTF-8） */
    public synchronized JSONObject send(String data, String encoding) throws Exception {
        byte[] bytes;
        if ("HEX".equalsIgnoreCase(encoding)) {
            bytes = parseHex(data);
        } else {
            bytes = (data == null ? "" : data).getBytes(StandardCharsets.UTF_8);
        }
        String category = "HEX".equalsIgnoreCase(encoding) ? "manual.hex" : "manual.text";
        enqueueManual(new SendTask(bytes, category, true, 0));
        return new JSONObject().put("success", true).put("bytes", bytes.length)
                .put("category", category).put("queued", true);
    }

    /** 开门（手动命令） */
    public synchronized JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) {
            throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        }
        String category = administrator ? "door.admin" : "door.issue";
        enqueueManual(new SendTask(WorkCardProtocol.openDoor(slotNumber, administrator),
                category, true, slotNumber, WorkCardProtocol.FUNCTION_OPEN_DOOR,
                slotNumber, slotNumber, false, false, true, DOOR_RESPONSE_TIMEOUT_MS));
        return new JSONObject().put("success", true).put("slotNumber", slotNumber)
                .put("mode", administrator ? "ADMIN" : "ISSUE").put("queued", true);
    }

    /** 查询单槽（手动命令） */
    public synchronized JSONObject querySlot(int slotNumber) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) {
            throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        }
        enqueueManual(new SendTask(WorkCardProtocol.directQuery(slotNumber, currentUnixSeconds()),
                "query.slot", true, slotNumber, WorkCardProtocol.FUNCTION_QUERY,
                slotNumber, slotNumber, false, false, false, responseTimeoutMs));
        return new JSONObject().put("success", true).put("slotNumber", slotNumber)
                .put("category", "query.slot").put("queued", true);
    }

    /** 调整指定单板 LED 占空比（手动命令）。 */
    public synchronized JSONObject setLedDutyCycle(int slotNumber, int dutyCycle) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) {
            throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        }
        enqueueManual(new SendTask(
                WorkCardProtocol.setLedDutyCycle(slotNumber, dutyCycle),
                "led.duty", true, slotNumber));
        return new JSONObject().put("success", true).put("slotNumber", slotNumber)
                .put("dutyCycle", dutyCycle).put("category", "led.duty").put("queued", true);
    }

    /** 读取版本（手动命令） */
    public synchronized JSONObject readVersion(int slotNumber) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) {
            throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        }
        enqueueManual(new SendTask(WorkCardProtocol.readVersion(slotNumber), "version.read", true, slotNumber));
        return new JSONObject().put("success", true).put("slotNumber", slotNumber)
                .put("category", "version.read").put("queued", true);
    }

    /**
     * 按串口协议 V1.5 §2.7/2.8 广播传输固件。
     * 协议没有应答帧，因此返回值只证明所有字节已写入 transport，不代表单板刷写成功。
     */
    public JSONObject transferFirmware(File firmwareFile, String operationId,
                                       FirmwareProgressListener progressListener) throws Exception {
        if (firmwareFile == null || !firmwareFile.isFile()) throw new IOException("固件文件不存在");
        long totalBytes = firmwareFile.length();
        if (totalBytes < 1L) throw new IOException("固件文件为空");
        if (totalBytes > MAX_FIRMWARE_BYTES) throw new IOException("固件文件超过64MB安全上限");
        if (totalBytes % WorkCardProtocol.FIRMWARE_CHUNK_SIZE != 0L) {
            throw new IOException("固件长度必须是128字节的整数倍，协议未定义尾帧长度");
        }
        synchronized (firmwareLock) {
            if (firmwareUpgradeInProgress) throw new IllegalStateException("已有固件传输正在执行");
            if (!serialConn.isOpen()) throw new IOException("串口未连接");
            firmwareUpgradeInProgress = true;
            firmwareCancelRequested.set(false);
        }

        boolean resumePolling;
        synchronized (this) {
            resumePolling = pollingEnabled;
            stopPolling();
            pollQueue.clear();
        }
        long transmitted = 0L;
        int frameCount = 0;
        try (FileInputStream input = new FileInputStream(firmwareFile)) {
            notifyFirmwareProgress(progressListener, operationId, "ENABLING", 0,
                    transmitted, totalBytes, frameCount);
            serialConn.writeDirect(WorkCardProtocol.upgradeEnable());
            if (firmwareCancelRequested.get()) throw new FirmwareUpgradeCancelledException();
            byte[] buffer = new byte[WorkCardProtocol.FIRMWARE_CHUNK_SIZE];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (firmwareCancelRequested.get()) throw new FirmwareUpgradeCancelledException();
                serialConn.writeDirect(WorkCardProtocol.upgradeData(
                        frameCount, Arrays.copyOf(buffer, length), length));
                transmitted += length;
                frameCount++;
                int progress = (int) Math.min(100L, transmitted * 100L / totalBytes);
                notifyFirmwareProgress(progressListener, operationId, "TRANSMITTING", progress,
                        transmitted, totalBytes, frameCount);
            }
            if (firmwareCancelRequested.get()) throw new FirmwareUpgradeCancelledException();
            return new JSONObject()
                    .put("success", true)
                    .put("status", "TRANSMITTED")
                    .put("operationId", operationId == null ? "" : operationId)
                    .put("bytes", transmitted)
                    .put("frames", frameCount)
                    .put("progress", 100)
                    .put("simulator", serialConn.isSimulator())
                    .put("hardwareVerified", false);
        } finally {
            synchronized (firmwareLock) {
                firmwareUpgradeInProgress = false;
                firmwareCancelRequested.set(false);
            }
            synchronized (this) {
                if (resumePolling && serialConn.isOpen()) startPolling();
            }
        }
    }

    public JSONObject cancelFirmwareTransfer() throws JSONException {
        boolean active = firmwareUpgradeInProgress;
        if (active) firmwareCancelRequested.set(true);
        return new JSONObject().put("accepted", active).put("active", active);
    }

    /** 一键全开门 */
    public synchronized JSONObject openAllDoors(boolean administrator) throws Exception {
        throw new UnsupportedOperationException(
                "原生批量开门已禁用，请使用 Vue 一键弹卡流程逐槽等待开门应答");
    }

    // ────────────────────── 快照 ──────────────────────

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("state", state).put("message", message)
                .put("port", port).put("baudRate", baudRate)
                .put("protocol", "WORK_CARD_V1.5")
                .put("simulator", serialConn.isSimulator())
                .put("polling", polling).put("pollingEnabled", pollingEnabled)
                .put("firmwareUpgradeInProgress", firmwareUpgradeInProgress)
                .put("totalSlots", totalSlots)
                .put("groupSize", groupSize).put("pollingMode", pollingMode)
                .put("responseTimeoutMs", responseTimeoutMs)
                .put("pollingIntervalMs", pollTickIntervalMs)
                .put("sentBytes", sentBytes).put("receivedBytes", receivedBytes)
                .put("lastReceivedAt", lastReceivedAt == 0 ? JSONObject.NULL : lastReceivedAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
    }

    /** 串口连接状态 */
    public boolean isOpen() {
        return serialConn.isOpen();
    }

    /** 获取 SlotStateManager（供外部订阅更多槽位） */
    public SlotStateManager getSlotStateManager() {
        return slotStateManager;
    }

    // ────────────────────── 静态工具 ──────────────────────

    /** 列出可用串口设备 */
    public static JSONObject listAvailablePorts() throws JSONException {
        return SerialConnectionManager.listAvailablePorts();
    }

    // ═══════════════════ 内部实现 ═══════════════════

    // ────────────────────── 连接管理 ──────────────────────

    private void open(String targetPort, int targetBaudRate) {
        connectionGeneration++;
        stopPolling();
        cancelActiveResponse();
        serialConn.close();
        manualQueue.clear();
        pollQueue.clear();
        port = serialConn.isSimulator()
                ? "simulator://" + serialConn.simulatedSlotCount()
                : targetPort;
        baudRate = targetBaudRate;
        lastError = "";
        updateState("CONNECTING", String.format(Locale.US, "Connecting %s @ %d", port, baudRate), null);
        if (!serialConn.open(port, baudRate)) {
            updateState("ERROR", "Serial connection failed", null);
            return;
        }

        currentPollAddress = 1;
        queueStartupBroadcast();
        updateState("CONNECTED", String.format(Locale.US, "Connected %s @ %d", port, baudRate), null);
        if (pollingEnabled) startPolling();
    }

    private void queueStartupBroadcast() {
        startupBroadcastActive = true;
        SendTask task = new SendTask(WorkCardProtocol.broadcastQuery(currentUnixSeconds()),
                "startup.broadcast", true, 0, WorkCardProtocol.FUNCTION_QUERY,
                1, 255, true, true, false, responseTimeoutMs);
        if (!manualQueue.offer(task)) {
            startupBroadcastActive = false;
            notifyCommandTimeout(task, 0);
        }
    }

    private void cancelActiveResponse() {
        PendingResponse pending = activeResponse;
        activeResponse = null;
        if (pending != null) pending.cancel();
    }

    private static long currentUnixSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private void enqueueManual(SendTask task) throws InterruptedException {
        manualQueue.put(task);
    }

    private void runSendWorker() {
        while (sendWorkerRunning) {
            try {
                SendTask task = manualQueue.poll();
                if (task == null) task = pollQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                executeSendTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                AppLog.e(TAG, "串口发送线程异常", e);
            }
        }
    }

    private void executeSendTask(SendTask task) throws InterruptedException {
        int attempts = task.retryOnTimeout ? 2 : 1;
        for (int attempt = 1; attempt <= attempts && sendWorkerRunning; attempt++) {
            PendingResponse pending = task.waitsForResponse() ? new PendingResponse(task) : null;
            synchronized (this) {
                if (!serialConn.isOpen()) return;
                activeResponse = pending;
                serialConn.write(task.data);
                sentBytes += task.data.length;
                if (task.isManual) trackManualResponse(task.data);
            }
            notifyTx(task);
            if (pending == null) return;

            ResponseWaitResult result = pending.await();
            synchronized (this) {
                if (activeResponse == pending) activeResponse = null;
            }
            if (result == ResponseWaitResult.RESPONSE) {
                if (task.detectsTotalSlots) {
                    applyDetectedTotalSlots(pending.detectedTotalSlots());
                    startupBroadcastActive = false;
                    firstRoundPushed = true;
                    slotStateManager.pushSnapshotImmediate();
                }
                return;
            }
            if (result == ResponseWaitResult.CANCELLED) {
                if (task.detectsTotalSlots) startupBroadcastActive = false;
                return;
            }
            if (task.detectsTotalSlots) startupBroadcastActive = false;
            if (attempt == attempts) notifyCommandTimeout(task, attempts);
        }
    }

    private void notifyTx(SendTask task) {
        JSONObject event = buildTxEvent(task);
        if (task.isManual) notifyDataManualSent(event);
        else notifyDataPollSent(event);
    }

    private void notifyCommandTimeout(SendTask task, int attempts) {
        try {
            notifyDataReceived(new JSONObject()
                    .put("type", "serialCommandTimeout")
                    .put("timestamp", System.currentTimeMillis())
                    .put("category", task.category)
                    .put("slotNumber", task.slotAddress)
                    .put("attempts", attempts));
        } catch (JSONException ignored) { }
    }

    private synchronized void startPolling() {
        if (polling) return;
        polling = true;
        pollingTask = pollingExecutor.scheduleAtFixedRate(
                this::pollNext, 0L, pollTickIntervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPolling() {
        polling = false;
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
        pollQueue.clear();
        pendingPollSlots = new boolean[totalSlots + 1];
    }

    private void pollNext() {
        if (!polling || !serialConn.isOpen() || activeResponse != null || !pollQueue.isEmpty()) return;
        synchronized (this) {
            if (!polling || activeResponse != null || !pollQueue.isEmpty()) return;
            SendTask task = buildPollingTask(currentPollAddress);
            if (pollQueue.offer(task)) {
                currentPollAddress = "GROUP".equals(pollingMode)
                        ? nextGroupAddress(currentPollAddress) : nextSlotAddress(currentPollAddress);
            }
        }
    }

    private SendTask buildPollingTask(int address) {
        if ("GROUP".equals(pollingMode)) {
            int lastAddress = Math.min(totalSlots, address + groupSize - 1);
            return new SendTask(WorkCardProtocol.groupQuery(address, currentUnixSeconds()),
                    "poll.group", false, address, WorkCardProtocol.FUNCTION_QUERY,
                    address, lastAddress, true, false, false, responseTimeoutMs);
        }
        return new SendTask(WorkCardProtocol.directQuery(address, currentUnixSeconds()),
                "poll.slot", false, address, WorkCardProtocol.FUNCTION_QUERY,
                address, address, false, false, false, responseTimeoutMs);
    }

    private int nextGroupAddress(int address) {
        int next = address + groupSize;
        return next > totalSlots ? 1 : next;
    }

    private int nextSlotAddress(int address) {
        return address >= totalSlots ? 1 : address + 1;
    }

    private void onRawBytesReceived(byte[] bytes) {
        synchronized (this) {
            receivedBytes += bytes.length;
            lastReceivedAt = System.currentTimeMillis();
        }
        // 原始字节此前会在 WebView 侧被直接忽略，却仍经过 EventBus 主线程。
        // 仅让完整协议帧继续进入路由，避免状态轮询制造无效跨线程消息。
        messageRouter.onRawData(bytes);
    }

    private void handleQueryResponse(int address, JSONObject slotStatus) {
        slotStateManager.updateSlot(address, slotStatus);
        if (!startupBroadcastActive) queueSlotEvent(slotStatus);
        if (!firstRoundPushed && slotStateManager.slotCount() >= totalSlots) {
            firstRoundPushed = true;
            slotStateManager.pushSnapshotImmediate();
        }
    }

    private void handleFrameData(JSONObject frameInfo) {
        notifyActiveResponse(frameInfo);
        try {
            String source = resolveFrameSource(frameInfo);
            frameInfo.put("source", source);
            if ("poll".equals(source)
                    && WorkCardProtocol.FUNCTION_QUERY == parseFunction(frameInfo.optString("function", ""))) {
                return;
            }
        } catch (JSONException ignored) { }
        notifyDataReceived(frameInfo);
    }

    private static int parseFunction(String value) {
        try {
            return Integer.parseInt(value.replace("0x", ""), 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void notifyActiveResponse(JSONObject frameInfo) {
        int address = frameInfo.optInt("address", -1);
        String value = frameInfo.optString("function", "");
        try {
            int function = Integer.parseInt(value.replace("0x", ""), 16);
            PendingResponse pending = activeResponse;
            if (pending != null) pending.onFrame(address, function);
        } catch (NumberFormatException ignored) { }
    }

    private void applyDetectedTotalSlots(int detected) {
        if (detected < 1) return;
        synchronized (this) {
            totalSlots = detected;
            groupSize = Math.min(groupSize, totalSlots);
            currentPollAddress = 1;
            pendingPollSlots = new boolean[totalSlots + 1];
            firstRoundPushed = false;
        }
        try {
            JSONObject status = snapshot();
            status.put("detectedTotalSlots", detected);
            status.put("slotCountSource", "BROADCAST");
            Listener currentListener = listener;
            if (currentListener != null) currentListener.onStatusChanged(status);
        } catch (JSONException ignored) { }
    }

    private void trackManualResponse(byte[] data) {
        if (data == null || data.length < 7 || (data[0] & 0xFF) != 0xDD || (data[1] & 0xFF) != 0xCC) return;
        pendingManualResponses.put(frameKey(data[5] & 0xFF, data[6] & 0xFF),
                System.currentTimeMillis() + responseTimeoutMs);
    }

    private String resolveFrameSource(JSONObject frameInfo) {
        int address = frameInfo.optInt("address", -1);
        String function = frameInfo.optString("function", "");
        try {
            int code = Integer.parseInt(function.replace("0x", ""), 16);
            Long expiresAt = pendingManualResponses.remove(frameKey(address, code));
            if (expiresAt != null && expiresAt >= System.currentTimeMillis()) return "manual";
            return code == WorkCardProtocol.FUNCTION_QUERY ? "poll" : "unknown";
        } catch (NumberFormatException ignored) {
            return "unknown";
        }
    }

    private static String frameKey(int address, int function) {
        return address + ":" + function;
    }

    private JSONObject buildTxEvent(SendTask task) {
        try {
            JSONObject data = new JSONObject().put("success", true).put("bytes", task.data.length)
                    .put("hex", WorkCardProtocol.hex(task.data)).put("category", task.category);
            return new JSONObject().put("type", "serialTx").put("timestamp", System.currentTimeMillis())
                    .put("source", task.isManual ? "manual" : "poll").put("data", data);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void notifyFirmwareProgress(FirmwareProgressListener listener, String operationId,
                                               String phase, int progress, long transmitted,
                                               long total, int frames) {
        if (listener == null) return;
        try {
            listener.onProgress(new JSONObject().put("operationId", operationId == null ? "" : operationId)
                    .put("phase", phase).put("progress", progress)
                    .put("transmittedBytes", transmitted).put("totalBytes", total).put("frames", frames));
        } catch (JSONException ignored) { }
    }

    private void updateState(String nextState, String nextMessage, Exception error) {
        synchronized (this) {
            state = nextState;
            message = nextMessage;
            lastError = error == null ? "" : safeMessage(error);
        }
        notifyStatus();
    }

    private void notifyStatus() {
        Listener currentListener = listener;
        if (currentListener != null) {
            try {
                currentListener.onStatusChanged(snapshot());
            } catch (JSONException ignored) { }
        }
    }

    private void notifyDataReceived(JSONObject data) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onDataReceived(data);
    }

    private void notifyDataManualSent(JSONObject data) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onDataManualSent(data);
    }

    private void notifyDataPollSent(JSONObject data) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onDataPollSent(data);
    }

    private void notifySlot(JSONObject slot) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onSlotStatus(slot);
    }

    private void queueSlotEvent(JSONObject slot) {
        if (slot == null) return;
        int slotNumber = slot.optInt("slotNumber", slot.optInt("slotId", 0));
        if (slotNumber < 1) return;
        synchronized (slotEventLock) {
            pendingSlotEvents.put(slotNumber, slot);
            if (slotEventTask != null && !slotEventTask.isDone()) return;
            slotEventTask = slotEventExecutor.schedule(
                    this::flushSlotEvents, SLOT_EVENT_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushSlotEvents() {
        Map<Integer, JSONObject> pending;
        synchronized (slotEventLock) {
            pending = new TreeMap<>(pendingSlotEvents);
            pendingSlotEvents.clear();
            slotEventTask = null;
        }
        if (pending.isEmpty()) return;

        long now = System.currentTimeMillis();
        JSONArray changedSlots = new JSONArray();
        synchronized (slotEventLock) {
            for (Map.Entry<Integer, JSONObject> entry : pending.entrySet()) {
                String signature = slotStateSignature(entry.getValue());
                SlotEventPublication previous = lastPublishedSlotEvents.get(entry.getKey());
                boolean unchanged = previous != null && previous.signature.equals(signature);
                if (unchanged && now - previous.publishedAt < SLOT_EVENT_HEARTBEAT_MS) continue;
                changedSlots.put(entry.getValue());
                lastPublishedSlotEvents.put(entry.getKey(), new SlotEventPublication(signature, now));
            }
        }
        if (changedSlots.length() == 0) return;
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onSlotStatusBatch(changedSlots);
    }

    private static String slotStateSignature(JSONObject slot) {
        return slot.optString("status", "") + '|'
                + slot.optInt("workCode", -1) + '|'
                + slot.optInt("doorCode", -1) + '|'
                + slot.optInt("cardCode", -1) + '|'
                + slot.optInt("faultMask", -1) + '|'
                + slot.optString("cardNumber", "") + '|'
                + slot.optDouble("voltage", Double.NaN) + '|'
                + slot.optDouble("current", Double.NaN) + '|'
                + slot.optBoolean("cardChanged", false);
    }

    private void stopSlotEventDispatch() {
        synchronized (slotEventLock) {
            if (slotEventTask != null) slotEventTask.cancel(false);
            slotEventTask = null;
            pendingSlotEvents.clear();
        }
        slotEventExecutor.shutdownNow();
    }

    private void clearSlotEventPublications() {
        synchronized (slotEventLock) {
            pendingSlotEvents.clear();
            lastPublishedSlotEvents.clear();
        }
    }

    private void notifySlotsSnapshot(Map<Integer, JSONObject> snapshot) {
        Listener currentListener = listener;
        if (currentListener == null || snapshot == null || snapshot.isEmpty()) return;
        JSONArray slots = new JSONArray();
        for (JSONObject slot : new TreeMap<>(snapshot).values()) slots.put(slot);
        currentListener.onSlotsSnapshot(slots);
    }

    private static byte[] parseHex(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "");
        if (TextUtils.isEmpty(normalized)) return new byte[0];
        if ((normalized.length() & 1) == 1) throw new IllegalArgumentException("十六进制数据必须由成对字符组成");
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(normalized.substring(i, i + 2), 16);
        }
        return result;
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

}
