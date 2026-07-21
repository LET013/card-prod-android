package com.xingyao.card.core;

import android.content.Context;
import android.text.TextUtils;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tp.xmaihh.serialport.SerialHelper;
import tp.xmaihh.serialport.bean.ComBean;

/** Real V1.5 work-card-board serial controller with CRC validation and polling. */
public final class SerialConnectionManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onDataReceived(JSONObject data);
        void onSlotStatus(JSONObject slot);
    }

    private static final String DEFAULT_PORT = "/dev/ttyS5";
    private static final int DEFAULT_BAUD_RATE = 57600;
    private static final long RESPONSE_TIMEOUT_MS = 1500L;
    private static final long POLLING_INTERVAL_MS = 1200L;
    private static final long COMMAND_GAP_MS = 200L;

    private final NativeSettingsRepository settingsRepository;
    private final Listener listener;
    private final List<Byte> inboundBuffer = new ArrayList<>();
    private final ScheduledExecutorService pollingExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Object serialCommandLock = new Object();
    private ScheduledFuture<?> pollingTask;
    private SerialHelper serialHelper;
    private String state = "DISCONNECTED";
    private String message = "串口未连接";
    private String port = DEFAULT_PORT;
    private int baudRate = DEFAULT_BAUD_RATE;
    private int totalSlots = 100;
    private int singleGroupCount = 10;
    private int nextAddress = 1;
    private int pendingAddress = -1;
    private long pendingAt;
    private boolean polling;
    private boolean pollingEnabled;
    private long sentBytes;
    private long receivedBytes;
    private long lastReceivedAt;
    private String lastError = "";
    private String lastPermissionHint = "";
    private final Object commandWaitLock = new Object();
    private int awaitingAddress = -1;
    private int awaitingFunction = -1;
    private JSONObject awaitingFrame;
    private long responseTimeoutMs = RESPONSE_TIMEOUT_MS;
    private long commandGapMs = COMMAND_GAP_MS;
    private long pollingIntervalMs = POLLING_INTERVAL_MS;

    public SerialConnectionManager(Context context, Listener listener) {
        settingsRepository = new NativeSettingsRepository(context.getApplicationContext());
        this.listener = listener;
    }

    public synchronized void start() {
        try { configure(settingsRepository.load()); }
        catch (Exception error) { updateState("ERROR", "读取串口配置失败", error); }
    }

    public synchronized void configure(JSONObject settings) {
        String configuredPort = settings == null ? "" : settings.optString("serialPort", "").trim();
        int configuredBaud = parsePositiveInt(settings == null ? "" : settings.optString("baudRate", ""), DEFAULT_BAUD_RATE);
        totalSlots = parsePositiveInt(settings == null ? "" : settings.optString("totalCount", ""), 100);
        singleGroupCount = parsePositiveInt(settings == null ? "" : settings.optString("singleGroupCount", ""), 10);
        if (singleGroupCount > totalSlots) singleGroupCount = totalSlots;
        responseTimeoutMs = parsePositiveLong(settings == null ? "" : settings.optString("serialResponseTimeoutMs", ""), RESPONSE_TIMEOUT_MS);
        commandGapMs = parsePositiveLong(settings == null ? "" : settings.optString("serialCommandGapMs", ""), COMMAND_GAP_MS);
        pollingIntervalMs = parsePositiveLong(settings == null ? "" : settings.optString("serialPollingIntervalMs",
                settings.optString("backendPollingIntervalMs", "")), POLLING_INTERVAL_MS);
        pollingEnabled = settings != null && settings.optBoolean("serialPollingEnabled",
                settings.optBoolean("singleGroupPollingEnabled", false));
        open(configuredPort.isEmpty() ? DEFAULT_PORT : configuredPort, configuredBaud);
    }

    public synchronized void reconnect() { open(port, baudRate); }

    public synchronized JSONObject setPollingEnabled(boolean enabled) throws JSONException {
        pollingEnabled = enabled;
        pendingAddress = -1;
        if (enabled && serialHelper != null && serialHelper.isOpen()) {
            startPolling();
            message = String.format(Locale.US, "已连接 %s @ %d，自动轮询已开启", port, baudRate);
        } else {
            stopPolling();
            if (serialHelper != null && serialHelper.isOpen()) {
                message = String.format(Locale.US, "已连接 %s @ %d，自动轮询已关闭", port, baudRate);
            }
        }
        notifyStatus();
        return snapshot();
    }

    public synchronized void stop() {
        stopPolling();
        closePort();
        updateState("DISCONNECTED", "原生串口已停止", null);
    }

    public JSONObject send(String data, String encoding) throws Exception {
        synchronized (serialCommandLock) {
            boolean wasPolling = pausePollingForCommand();
            try {
                if ("HEX".equalsIgnoreCase(encoding)) return writeRaw(parseHex(data), "manual.hex");
                return writeRaw((data == null ? "" : data).getBytes(StandardCharsets.UTF_8), "manual.text");
            } finally {
                resumePollingAfterCommand(wasPolling);
            }
        }
    }

    public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        synchronized (serialCommandLock) {
            boolean wasPolling = pausePollingForCommand();
            try {
                int address = serialAddressForSlot(slotNumber);
                JSONObject result = writeCommandAndWait(address, WorkCardProtocol.FUNCTION_OPEN_DOOR,
                        WorkCardProtocol.openDoor(address, administrator), administrator ? "door.admin" : "door.issue");
                result.put("slotNumber", slotNumber).put("boardAddress", address).put("mode", administrator ? "ADMIN" : "ISSUE");
                return result;
            } finally {
                resumePollingAfterCommand(wasPolling);
            }
        }
    }

    public JSONObject querySlot(int slotNumber) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        synchronized (serialCommandLock) {
            boolean wasPolling = pausePollingForCommand();
            try {
                int address = serialAddressForSlot(slotNumber);
                JSONObject result = writeCommandAndWait(address, WorkCardProtocol.FUNCTION_QUERY,
                        WorkCardProtocol.query(address), "slot.query");
                result.put("slotNumber", slotNumber).put("boardAddress", address);
                return result;
            } finally {
                resumePollingAfterCommand(wasPolling);
            }
        }
    }

    public JSONObject readVersion(int slotNumber) throws Exception {
        if (slotNumber < 1 || slotNumber > totalSlots) throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");
        synchronized (serialCommandLock) {
            boolean wasPolling = pausePollingForCommand();
            try {
                int address = serialAddressForSlot(slotNumber);
                JSONObject result = writeCommandAndWait(address, WorkCardProtocol.FUNCTION_VERSION,
                        WorkCardProtocol.readVersion(address), "version.read");
                result.put("slotNumber", slotNumber).put("boardAddress", address);
                return result;
            } finally {
                resumePollingAfterCommand(wasPolling);
            }
        }
    }

    public JSONObject openAllDoors(boolean administrator) throws Exception {
        synchronized (serialCommandLock) {
            int sent = 0;
            int failed = 0;
            JSONArray failures = new JSONArray();
            boolean wasPolling = pausePollingForCommand();
            try {
                int addressLimit = pollingAddressLimit();
                for (int address = 1; address <= addressLimit; address++) {
                    try {
                        writeCommandAndWait(address, WorkCardProtocol.FUNCTION_OPEN_DOOR,
                                WorkCardProtocol.openDoor(address, administrator), "door.all");
                        sent++;
                    } catch (Exception error) {
                        failed++;
                        failures.put(new JSONObject().put("slotNumber", address).put("message", safeMessage(error)));
                    }
                    sleepQuietly(commandGapMs);
                }
            } finally {
                resumePollingAfterCommand(wasPolling);
            }
            return new JSONObject().put("success", failed == 0).put("successCount", sent).put("failedCount", failed)
                    .put("failures", failures)
                    .put("singleGroupCount", singleGroupCount)
                    .put("totalSlots", totalSlots)
                    .put("message", "已按单组数量发送全部卡门开门指令");
        }
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject().put("state", state).put("message", message).put("port", port).put("baudRate", baudRate)
                .put("protocol", "WORK_CARD_V1.5").put("polling", polling).put("pollingEnabled", pollingEnabled).put("totalSlots", totalSlots)
                .put("singleGroupCount", singleGroupCount).put("pollingAddressLimit", pollingAddressLimit())
                .put("responseTimeoutMs", responseTimeoutMs).put("pollingIntervalMs", pollingIntervalMs)
                .put("commandGapMs", commandGapMs)
                .put("sentBytes", sentBytes).put("receivedBytes", receivedBytes)
                .put("lastReceivedAt", lastReceivedAt == 0 ? JSONObject.NULL : lastReceivedAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError)
                .put("permissionHint", lastPermissionHint.isEmpty() ? JSONObject.NULL : lastPermissionHint);
    }

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
        Collections.sort(candidates, (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        for (File file : candidates) {
            ports.put(new JSONObject()
                    .put("path", file.getAbsolutePath())
                    .put("readable", file.canRead())
                    .put("writable", file.canWrite())
                    .put("exists", file.exists()));
        }
        return new JSONObject().put("ports", ports).put("count", ports.length())
                .put("message", ports.length() == 0 ? "未在 /dev 下发现常见串口节点" : "发现 " + ports.length() + " 个候选串口");
    }

    private void open(String targetPort, int targetBaudRate) {
        stopPolling();
        closePort();
        port = targetPort;
        baudRate = targetBaudRate;
        lastPermissionHint = "";
        updateState("CONNECTING", String.format(Locale.US, "正在连接 %s @ %d", port, baudRate), null);
        try {
            ensureDeviceAccessible(port);
            serialHelper = new SerialHelper(port, baudRate) {
                @Override protected void onDataReceived(ComBean value) { handleReceived(value == null ? null : value.bRec); }
            };
            serialHelper.open();
            if (!serialHelper.isOpen()) throw new IllegalStateException("串口驱动未能打开设备");
            nextAddress = 1;
            pendingAddress = -1;
            updateState("CONNECTED", String.format(Locale.US, "已连接 %s @ %d，工作卡协议V1.5，自动轮询%s",
                    port, baudRate, pollingEnabled ? "已开启" : "已关闭"), null);
            if (pollingEnabled) startPolling();
        } catch (Exception error) {
            closePort();
            updateState("ERROR", "串口连接失败：" + safeMessage(error), error);
        }
    }

    private void ensureDeviceAccessible(String devicePath) throws Exception {
        File device = new File(devicePath);
        if (!device.exists()) {
            lastPermissionHint = "串口设备不存在，请确认设备真实节点是否为 " + devicePath;
            throw new IOException(lastPermissionHint);
        }
        if (device.canRead() && device.canWrite()) return;
        String before = accessDescription(device);
        boolean fixed = tryChmodWithRoot(devicePath);
        String after = accessDescription(device);
        lastPermissionHint = "串口设备权限不足：" + before + "；chmod尝试后：" + after;
        if (!fixed || !device.canRead() || !device.canWrite()) {
            throw new SecurityException(lastPermissionHint + "。请让设备侧执行 chmod 666 " + devicePath
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
            output.write(("chmod 666 " + shellQuote(devicePath) + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String accessDescription(File device) {
        return "exists=" + device.exists() + ", read=" + device.canRead() + ", write=" + device.canWrite();
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private synchronized void startPolling() {
        if (polling) return;
        polling = true;
        pollingTask = pollingExecutor.scheduleAtFixedRate(this::pollNext, pollingIntervalMs, pollingIntervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPolling() {
        polling = false;
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
    }

    private void pollNext() {
        synchronized (this) {
            if (!polling || serialHelper == null || !serialHelper.isOpen()) return;
            long now = System.currentTimeMillis();
            if (pendingAddress > 0 && now - pendingAt < responseTimeoutMs) return;
            if (pendingAddress > 0) {
                try {
                    notifySlot(communicationFault(pendingAddress, "单板响应超时(" + responseTimeoutMs + "ms)"));
                } catch (JSONException ignored) { }
                pendingAddress = -1;
            }
            int addressLimit = pollingAddressLimit();
            int address = Math.min(nextAddress, addressLimit);
            nextAddress = nextAddress >= addressLimit ? 1 : nextAddress + 1;
            try {
                writeRaw(WorkCardProtocol.query(address), "poll.query");
                pendingAddress = address;
                pendingAt = now;
            } catch (Exception error) {
                updateState("ERROR", "轮询发送失败：" + safeMessage(error), error);
            }
        }
    }

    private synchronized JSONObject writeRaw(byte[] bytes, String category) throws Exception {
        if (serialHelper == null || !serialHelper.isOpen()) throw new IllegalStateException("串口未连接");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("发送内容不能为空");
        serialHelper.send(bytes);
        sentBytes += bytes.length;
        JSONObject result = new JSONObject().put("success", true).put("bytes", bytes.length).put("hex", WorkCardProtocol.hex(bytes)).put("category", category);
        notifyData(new JSONObject().put("type", "serialTx").put("timestamp", System.currentTimeMillis()).put("data", result));
        return result;
    }

    private JSONObject writeCommandAndWait(int address, int function, byte[] bytes, String category) throws Exception {
        synchronized (commandWaitLock) {
            awaitingAddress = address;
            awaitingFunction = function;
            awaitingFrame = null;
        }
        JSONObject result;
        try {
            result = writeRaw(bytes, category);
            JSONObject frame = awaitFrame(address, function, responseTimeoutMs);
            result.put("ack", true).put("response", frame);
            if (function == WorkCardProtocol.FUNCTION_OPEN_DOOR && !frame.optBoolean("accepted", false)) {
                throw new IllegalStateException("单板返回开门失败");
            }
            return result;
        } finally {
            synchronized (commandWaitLock) {
                if (awaitingAddress == address && awaitingFunction == function) {
                    awaitingAddress = -1;
                    awaitingFunction = -1;
                    awaitingFrame = null;
                }
            }
        }
    }

    private JSONObject awaitFrame(int address, int function, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(50L, timeoutMs);
        synchronized (commandWaitLock) {
            while (true) {
                if (awaitingFrame != null) return new JSONObject(awaitingFrame.toString());
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new java.util.concurrent.TimeoutException(String.format(Locale.US,
                            "单板%d功能0x%02X在%dms内未应答", address, function, Math.max(50L, timeoutMs)));
                }
                commandWaitLock.wait(remaining);
            }
        }
    }

    private boolean pausePollingForCommand() {
        synchronized (this) {
            boolean wasPolling = pollingEnabled && polling;
            stopPolling();
            pendingAddress = -1;
            return wasPolling;
        }
    }

    private void resumePollingAfterCommand(boolean wasPolling) {
        synchronized (this) {
            if (wasPolling && pollingEnabled && serialHelper != null && serialHelper.isOpen()) startPolling();
        }
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
            notifyData(new JSONObject().put("type", "serialRxRaw").put("timestamp", System.currentTimeMillis())
                    .put("hex", WorkCardProtocol.hex(bytes)).put("text", new String(bytes, StandardCharsets.ISO_8859_1)));
        } catch (JSONException ignored) { }
        for (WorkCardProtocol.Frame frame : frames) handleFrame(frame);
        notifyStatus();
    }

    private void handleFrame(WorkCardProtocol.Frame frame) {
        synchronized (this) { if (pendingAddress == frame.slaveAddress) pendingAddress = -1; }
        try {
            JSONObject protocol = new JSONObject().put("type", "serialFrame").put("address", frame.slaveAddress)
                    .put("function", String.format("0x%02X", frame.function)).put("hex", WorkCardProtocol.hex(frame.raw));
            notifyData(protocol);
            if (frame.function == WorkCardProtocol.FUNCTION_QUERY) notifySlot(parseSlotStatus(frame));
            else if (frame.function == WorkCardProtocol.FUNCTION_OPEN_DOOR) notifyData(protocol.put("command", "openDoor").put("accepted", isAccepted(frame.data)));
            else if (frame.function == WorkCardProtocol.FUNCTION_VERSION) notifyData(protocol.put("command", "version").put("version", parseVersion(frame.data)));
            completeAwaitingFrame(frame, protocol);
        } catch (JSONException ignored) { }
    }

    private void completeAwaitingFrame(WorkCardProtocol.Frame frame, JSONObject protocol) throws JSONException {
        synchronized (commandWaitLock) {
            if (awaitingAddress == frame.slaveAddress && awaitingFunction == frame.function) {
                awaitingFrame = new JSONObject(protocol.toString());
                commandWaitLock.notifyAll();
            }
        }
    }

    private JSONObject parseSlotStatus(WorkCardProtocol.Frame frame) throws JSONException {
        int offset = WorkCardProtocol.hasFixedPrefix(frame.data) ? 4 : 0;
        if (frame.data.length < offset + 22) return communicationFault(frame.slaveAddress, "状态帧长度不足");
        int work = unsigned(frame.data[offset]);
        int door = unsigned(frame.data[offset + 1]);
        int card = unsigned(frame.data[offset + 2]);
        int changed = unsigned(frame.data[offset + 3]);
        String cardNo = new String(frame.data, offset + 4, 15, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
        int fault = unsigned(frame.data[offset + 19]);
        double voltage = unsigned(frame.data[offset + 20]) * 0.05D;
        double current = unsigned(frame.data[offset + 21]) * 0.01D;
        String status = mapStatus(work, card, fault);
        return new JSONObject().put("slotNumber", frame.slaveAddress).put("status", status)
                .put("workCode", work).put("doorCode", door).put("cardCode", card).put("faultMask", fault)
                .put("workStatus", mapWork(work)).put("presenceStatus", mapPresence(card))
                .put("doorStatus", mapDoor(door)).put("cardNumber", cardNo)
                .put("faultCode", fault == 0 ? "" : String.format("0x%02X", fault)).put("faultMessage", faultMessage(fault))
                .put("voltage", voltage).put("current", current).put("cardChanged", changed == 1)
                .put("updatedAt", System.currentTimeMillis());
    }

    private JSONObject communicationFault(int address, String reason) throws JSONException {
        return new JSONObject().put("slotNumber", address).put("status", "COMMUNICATION_FAULT").put("workStatus", "通信超时")
                .put("workCode", 6).put("doorCode", -1).put("cardCode", -1).put("faultMask", 0)
                .put("presenceStatus", "未知").put("doorStatus", "未知")
                .put("faultCode", "COMM_TIMEOUT").put("faultMessage", reason).put("updatedAt", System.currentTimeMillis());
    }

    private static boolean isAccepted(byte[] data) { return data.length >= 5 && data[data.length - 1] == 0x11; }
    private static String parseVersion(byte[] data) {
        int offset = WorkCardProtocol.hasFixedPrefix(data) ? 4 : 0;
        return data.length >= offset + 4 ? String.format("HW %d.%d / SW %d.%d", unsigned(data[offset]), unsigned(data[offset + 1]), unsigned(data[offset + 2]), unsigned(data[offset + 3])) : "未知";
    }
    private static String mapStatus(int work, int card, int fault) {
        if (fault != 0 || work == 4) return "CHARGING_FAULT";
        if (work == 6) return "COMMUNICATION_FAULT";
        if (card == 2) return "ILLEGAL_CARD";
        if (work == 2) return "CHARGING";
        if (work == 3) return "FULL";
        if (work == 5) return "CHARGING_FAULT";
        return card == 1 ? "OCCUPIED" : "EMPTY";
    }
    private static String mapWork(int work) { return new String[]{"无效", "待机", "充电中", "充电结束", "故障", "授权到期", "通信超时"}[Math.min(Math.max(work, 0), 6)]; }
    private static String mapDoor(int door) {
        if (door == 1) return "开门状态";
        if (door == 2) return "关门状态";
        return "未知门状态(" + door + ")";
    }
    private static String mapPresence(int card) {
        if (card == 1) return "有卡";
        if (card == 2) return "读卡错误/非法卡";
        return "无卡";
    }
    private static String faultMessage(int fault) {
        if (fault == 0) return "";
        StringBuilder result = new StringBuilder();
        String[] names = {"插卡错误", "过流", "门控故障", "过压", "欠压"};
        for (int index = 0; index < names.length; index++) if ((fault & (1 << index)) != 0) { if (result.length() > 0) result.append("、"); result.append(names[index]); }
        return result.toString();
    }
    private synchronized void closePort() { if (serialHelper != null) { try { serialHelper.close(); } catch (Exception ignored) { } serialHelper = null; } }
    private void updateState(String nextState, String nextMessage, Exception error) { synchronized (this) { state = nextState; message = nextMessage; lastError = error == null ? "" : safeMessage(error); } notifyStatus(); }
    private void notifyStatus() { if (listener != null) try { listener.onStatusChanged(snapshot()); } catch (JSONException ignored) { } }
    private void notifyData(JSONObject data) { if (listener != null) listener.onDataReceived(data); }
    private void notifySlot(JSONObject slot) { if (listener != null) listener.onSlotStatus(slot); }
    private static int parsePositiveInt(String value, int fallback) { try { int result = Integer.parseInt(value); return result > 0 ? result : fallback; } catch (NumberFormatException ignored) { return fallback; } }
    private static long parsePositiveLong(String value, long fallback) { try { long result = Long.parseLong(value); return result > 0L ? result : fallback; } catch (NumberFormatException ignored) { return fallback; } }
    private static void sleepQuietly(long ms) { try { Thread.sleep(Math.max(0L, ms)); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
    private static byte[] parseHex(String value) { String normalized = value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", ""); if (TextUtils.isEmpty(normalized)) return new byte[0]; if ((normalized.length() & 1) == 1) throw new IllegalArgumentException("十六进制数据必须由成对字符组成"); byte[] result = new byte[normalized.length() / 2]; for (int index = 0; index < normalized.length(); index += 2) result[index / 2] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16); return result; }
    private static int unsigned(byte value) { return value & 0xFF; }
    private static String safeMessage(Exception error) { String value = error.getMessage(); return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value; }
    private int serialAddressForSlot(int slotNumber) {
        int groupSize = Math.max(1, singleGroupCount);
        if (groupSize >= totalSlots) return slotNumber;
        return ((slotNumber - 1) % groupSize) + 1;
    }
    private int pollingAddressLimit() { return Math.max(1, Math.min(totalSlots, singleGroupCount)); }
}
