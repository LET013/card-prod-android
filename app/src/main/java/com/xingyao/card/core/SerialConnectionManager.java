package com.xingyao.card.core;

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
