package com.xingyao.card.core.serial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debug-only in-memory work-card board simulator with autonomous lifecycle.
 *
 * <h3>Simulation behavior</h3>
 * <ul>
 *   <li>On {@link #open}, creates {@value #SLOT_COUNT} slots with random initial states.</li>
 *   <li>A background tick (every {@value #SIMULATION_TICK_MS}ms) randomly toggles each slot's
 *       work state between charging / charge-complete / charge-error.</li>
 *   <li>Card numbers use {@code "TSIM"} prefix + 11 random digits (15 chars, fits protocol).</li>
 *   <li>After card ejection (command 0x01), the card is removed after
 *       {@value #CARD_TAKE_DELAY_MS}ms, and a new random card is automatically inserted
 *       after {@value #CARD_REINSERT_DELAY_MS}ms.</li>
 *   <li>All slot states live in a {@link ConcurrentHashMap} and are read on QUERY polling.</li>
 * </ul>
 *
 * <p>It accepts raw protocol frames and returns encoded bytes through the regular receive
 * callback. It never writes slot state directly.</p>
 */
final class SimulatedSerialTransport implements SerialTransport {
    static final int SLOT_COUNT = 20;

    private static final long RESPONSE_DELAY_MS = 30L;
    private static final long DOOR_OPEN_DURATION_MS = 1_000L;
    private static final long CARD_TAKE_DELAY_MS = 120L;
    private static final long CARD_REINSERT_DELAY_MS = 60_000L;
    private static final long SIMULATION_TICK_MS = 10_000L;
    private static final int SIMULATED_GROUP_SIZE = 10;
    private static final double STATE_CHANGE_PROBABILITY = 0.12;
    /** 每个模拟 tick 电压电流的随机波动幅度 */
    private static final int VOLTAGE_JITTER = 2;
    private static final int CURRENT_JITTER = 3;
    private static final byte[] FIXED_PREFIX = {
            (byte) 0x5A, (byte) 0xA5, (byte) 0x5A, (byte) 0xA5
    };

    /** 卡槽实时状态存储 —— 轮询时从此 Map 读取组包 */
    private final Map<Integer, Slot> slots = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private volatile OnDataReceived callback;
    private volatile boolean open;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> simulationTickFuture;

    // ── 固件升级状态 ──
    private boolean firmwareEnabled;
    private int firmwareExpectedSequence;
    private int firmwareFrameCount;
    private long firmwareByteCount;

    SimulatedSerialTransport() { }

    // ═══════════════════ SerialTransport 接口 ═══════════════════

    @Override
    public synchronized boolean open(String ignoredPort, int ignoredBaudRate) {
        close();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SerialSimulator");
            thread.setDaemon(true);
            return thread;
        });
        open = true;
        firmwareEnabled = false;
        firmwareExpectedSequence = 0;
        firmwareFrameCount = 0;
        firmwareByteCount = 0;
        initSlots();
        startSimulationTick();
        return true;
    }

    @Override
    public synchronized void close() {
        open = false;
        stopSimulationTick();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        slots.clear();
    }

    @Override public boolean isOpen() { return open; }

    @Override
    public void write(byte[] data) {
        if (!open || data == null || data.length == 0) return;
        byte[] copy = Arrays.copyOf(data, data.length);
        ScheduledExecutorService current;
        synchronized (this) { current = executor; }
        if (current == null || current.isShutdown()) return;
        current.schedule(() -> handleWrite(copy), RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void writeDirect(byte[] data) throws IOException {
        if (!open) throw new IOException("模拟串口未连接");
        List<WorkCardProtocol.Frame> frames = decodeFrames(data);
        if (frames.size() != 1) throw new IOException("模拟串口固件帧格式无效");
        WorkCardProtocol.Frame frame = frames.get(0);
        if (frame.masterAddress != WorkCardProtocol.MASTER_ADDRESS) {
            throw new IOException("模拟串口固件帧主机地址无效");
        }
        acceptFirmwareFrame(frame);
    }

    @Override public void setOnDataReceived(OnDataReceived callback) { this.callback = callback; }
    @Override public boolean isSimulator() { return true; }
    @Override public int simulatedSlotCount() { return SLOT_COUNT; }

    // ═══════════════════ 模拟生命周期 ═══════════════════

    /** 初始化 N 个卡槽，随机状态（充电中/充电完成/充电异常），约70%概率有卡 */
    private void initSlots() {
        slots.clear();
        for (int address = 1; address <= SLOT_COUNT; address++) {
            slots.put(address, createRandomSlot());
        }
    }

    private void startSimulationTick() {
        ScheduledExecutorService current;
        synchronized (this) { current = executor; }
        if (current == null || current.isShutdown()) return;
        try {
            simulationTickFuture = current.scheduleAtFixedRate(
                    this::simulationTick, SIMULATION_TICK_MS, SIMULATION_TICK_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { }
    }

    private void stopSimulationTick() {
        ScheduledFuture<?> future = simulationTickFuture;
        simulationTickFuture = null;
        if (future != null) future.cancel(false);
    }

    /**
     * 每 1 秒执行：随机更新充电状态，电压电流微小波动，检查到期重新插卡。
     * 与 handleWrite 在同一单线程 executor 上串行执行，无并发写入冲突。
     */
    private void simulationTick() {
        if (!open) return;
        long now = System.currentTimeMillis();
        for (Slot slot : slots.values()) {
            randomlyChangeWorkState(slot);
            jitterVoltageAndCurrent(slot);
            checkCardReinsert(slot, now);
        }
    }

    /**
     * 以 {@value #STATE_CHANGE_PROBABILITY} 概率随机切换工作状态。
     * 仅在充电中(1)、充电完成(2)、充电异常(3)之间切换，不产生硬件故障(4)。
     * 状态变化时同步更新电压电流以匹配新状态。
     */
    private void randomlyChangeWorkState(Slot slot) {
        if (random.nextDouble() >= STATE_CHANGE_PROBABILITY) return;
        int roll = random.nextInt(10);
        int newWork;
        if (roll < 4)      newWork = 1; // 40% 充电中
        else if (roll < 8) newWork = 2; // 40% 充电完成
        else               newWork = 3; // 20% 充电异常
        if (newWork == slot.work) return;
        slot.work = newWork;
        slot.voltage = randomVoltageFor(newWork);
        slot.current = randomCurrentFor(newWork);
    }

    /**
     * 检查卡槽是否到达重新插卡时间。
     * 插入新卡时随机生成卡号和充电状态，并置 cardChanged 标志。
     */
    private void checkCardReinsert(Slot slot, long now) {
        if (slot.card == 0 && slot.cardReinsertAt > 0 && now >= slot.cardReinsertAt) {
            slot.work = randomWorkState(); // 新插入的卡随机充电状态
            slot.voltage = randomVoltageFor(slot.work);
            slot.current = randomCurrentFor(slot.work);
            slot.card = 1;
            slot.cardNumber = generateCardNumber();
            slot.cardChanged = true;
            slot.cardReinsertAt = 0;
        }
    }

    // ═══════════════════ 协议帧处理 ═══════════════════

    private void handleWrite(byte[] bytes) {
        if (!open) return;
        for (WorkCardProtocol.Frame frame : decodeFrames(bytes)) {
            if (frame.masterAddress != WorkCardProtocol.MASTER_ADDRESS) continue;
            byte[] response = responseFor(frame);
            OnDataReceived current = callback;
            if (open && response != null && current != null) current.onData(response);
        }
    }

    private static List<WorkCardProtocol.Frame> decodeFrames(byte[] bytes) {
        List<Byte> buffer = new ArrayList<>(bytes == null ? 0 : bytes.length);
        if (bytes != null) for (byte value : bytes) buffer.add(value);
        return WorkCardProtocol.decode(buffer);
    }

    private synchronized void acceptFirmwareFrame(WorkCardProtocol.Frame frame) throws IOException {
        if (frame.slaveAddress != 0) throw new IOException("固件升级必须使用广播地址0");
        if (frame.function == WorkCardProtocol.FUNCTION_UPGRADE_ENABLE) {
            byte[] expected = {
                    (byte) 0x5A, (byte) 0x5A, (byte) 0xA5, (byte) 0xA5,
                    (byte) 0xA5, (byte) 0xA5, (byte) 0x5A, (byte) 0x5A
            };
            if (!Arrays.equals(expected, frame.data)) throw new IOException("升级使能数据域无效");
            firmwareEnabled = true;
            firmwareExpectedSequence = 0;
            firmwareFrameCount = 0;
            firmwareByteCount = 0;
            return;
        }
        if (frame.function != WorkCardProtocol.FUNCTION_UPGRADE_DATA) {
            throw new IOException("不是固件升级功能码");
        }
        if (!firmwareEnabled) throw new IOException("尚未发送升级使能帧");
        if (frame.data.length != WorkCardProtocol.FIRMWARE_CHUNK_SIZE + 3) {
            throw new IOException("0x01 固件升级分片必须携带完整128字节");
        }
        int lengthFlag = frame.data[0] & 0xFF;
        int sequence = frame.data[1] & 0xFF;
        int inverse = frame.data[2] & 0xFF;
        if (lengthFlag != 0x01) throw new IOException("模拟器仅接受文档定义的128字节分片标识");
        if (((sequence ^ inverse) & 0xFF) != 0xFF) throw new IOException("固件帧序号反码无效");
        if (sequence != (firmwareExpectedSequence & 0xFF)) throw new IOException("固件帧序号不连续");
        firmwareExpectedSequence++;
        firmwareFrameCount++;
        firmwareByteCount += frame.data.length - 3L;
    }

    // ── 测试访问器 ──
    synchronized int firmwareFrameCountForTest() { return firmwareFrameCount; }
    synchronized long firmwareByteCountForTest() { return firmwareByteCount; }

    /** 测试用：向指定地址注入已知状态的卡槽，覆盖随机初始值 */
    void setSlotForTest(int address, int work, int card, int fault, String cardNumber) {
        setSlotForTest(address, work, card, fault, cardNumber, 0, 0);
    }

    /** 测试用：注入卡槽状态并指定电压电流 */
    void setSlotForTest(int address, int work, int card, int fault, String cardNumber,
                        int voltage, int current) {
        if (address < 1 || address > SLOT_COUNT) return;
        slots.put(address, new Slot(work, card, fault, cardNumber != null ? cardNumber : "",
                voltage, current));
    }

    // ═══════════════════ 协议应答 ═══════════════════

    private byte[] responseFor(WorkCardProtocol.Frame frame) {
        int address = frame.slaveAddress;
        if (frame.function == WorkCardProtocol.FUNCTION_QUERY && isMainBoardQuery(frame.data)) {
            if (address == 0) return queryResponses(1, SLOT_COUNT);
            return queryResponses(address, Math.min(SLOT_COUNT, address + SIMULATED_GROUP_SIZE - 1));
        }
        Slot slot = slots.get(address);
        if (slot == null) return null;
        switch (frame.function) {
            case WorkCardProtocol.FUNCTION_QUERY:
                return isDirectQuery(frame.data) || isCommand(frame.data, 0x01)
                        ? WorkCardProtocol.frame(address, frame.function, queryData(slot))
                        : null;
            case WorkCardProtocol.FUNCTION_OPEN_DOOR:
                return openDoorResponse(address, frame.data, slot);
            case WorkCardProtocol.FUNCTION_VERSION:
                return isCommand(frame.data, 0x01)
                        ? WorkCardProtocol.frame(address, frame.function,
                                concat(FIXED_PREFIX, new byte[]{1, 0, 1, 0}))
                        : null;
            default:
                return null;
        }
    }

    private byte[] openDoorResponse(int address, byte[] data, Slot slot) {
        int command = data != null && data.length >= 5 ? data[4] & 0xFF : -1;
        boolean accepted = WorkCardProtocol.hasFixedPrefix(data)
                && (command == 0x01 || command == 0x02);
        if (accepted) {
            slot.doorOpenUntil = System.currentTimeMillis() + DOOR_OPEN_DURATION_MS;
            if (command == 0x01) {
                scheduleCardTaken(slot);
            }
        }
        return WorkCardProtocol.frame(address, WorkCardProtocol.FUNCTION_OPEN_DOOR,
                concat(FIXED_PREFIX, new byte[]{(byte) (accepted ? 0x11 : 0x12)}));
    }

    /** 取卡命令：120ms 后移除卡，并安排在 60s 后自动重新插入随机卡 */
    private void scheduleCardTaken(Slot slot) {
        if (slot.card != 1) return;
        ScheduledExecutorService current;
        synchronized (this) { current = executor; }
        if (current == null || current.isShutdown()) return;
        try {
            current.schedule(() -> {
                if (!open || slot.card != 1) return;
                slot.work = 1; // 取卡后变为充电中
                slot.voltage = randomVoltageFor(1);
                slot.current = randomCurrentFor(1);
                slot.card = 0;
                slot.cardNumber = "";
                slot.cardChanged = true;
                // 安排 1 分钟后重新随机插入一张卡
                slot.cardReinsertAt = System.currentTimeMillis() + CARD_REINSERT_DELAY_MS;
            }, CARD_TAKE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { }
    }

    private static boolean isCommand(byte[] data, int command) {
        return data != null && data.length >= 5
                && WorkCardProtocol.hasFixedPrefix(data)
                && (data[4] & 0xFF) == command;
    }

    private static boolean isMainBoardQuery(byte[] data) {
        return data != null && data.length == 8
                && (data[0] & 0xFF) == 0x5A && (data[1] & 0xFF) == 0xA5
                && (data[2] & 0xFF) == 0x5A && (data[3] & 0xFF) == 0x01;
    }

    private static boolean isDirectQuery(byte[] data) {
        return data != null && data.length == 8 && WorkCardProtocol.hasFixedPrefix(data);
    }

    private byte[] queryResponses(int firstAddress, int lastAddress) {
        byte[] result = new byte[0];
        for (int address = firstAddress; address <= lastAddress; address++) {
            Slot slot = slots.get(address);
            if (slot != null) result = concat(result,
                    WorkCardProtocol.frame(address, WorkCardProtocol.FUNCTION_QUERY, queryData(slot)));
        }
        return result.length == 0 ? null : result;
    }

    /** 从 Map 中的 Slot 组装 22 字节查询应答数据 */
    private static byte[] queryData(Slot slot) {
        byte[] data = new byte[22];
        data[0] = (byte) slot.work;
        data[1] = (byte) (System.currentTimeMillis() < slot.doorOpenUntil ? 1 : 2);
        data[2] = (byte) slot.card;
        data[3] = (byte) (slot.cardChanged ? 1 : 0);
        slot.cardChanged = false;
        byte[] cardNumber = slot.cardNumber.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(cardNumber, 0, data, 4, Math.min(cardNumber.length, 15));
        data[19] = (byte) slot.fault;
        data[20] = (byte) slot.voltage;
        data[21] = (byte) slot.current;
        return data;
    }

    // ═══════════════════ 随机数据生成 ═══════════════════

    /** 创建随机状态的卡槽 */
    private Slot createRandomSlot() {
        int work = randomWorkState();
        boolean hasCard = random.nextDouble() < 0.7; // 70% 概率有卡
        String cardNumber = hasCard ? generateCardNumber() : "";
        return new Slot(work, hasCard ? 1 : 0, 0, cardNumber,
                randomVoltageFor(work), randomCurrentFor(work));
    }

    private int randomWorkState() {
        int roll = random.nextInt(10);
        if (roll < 5) return 1;      // 50% 充电中
        else if (roll < 9) return 2; // 40% 充电完成
        else return 3;               // 10% 充电异常
    }

    /**
     * 根据工作状态生成合理的电压原始值（50mV/bit）。
     * <ul>
     *   <li>充电中(1): 3.7~4.1V → 74~82</li>
     *   <li>充电完成(2): 4.15~4.20V → 83~84</li>
     *   <li>充电异常(3): 2.0~3.2V → 40~64</li>
     * </ul>
     */
    private int randomVoltageFor(int work) {
        switch (work) {
            case 1: return 74 + random.nextInt(9);  // 74-82 (3.70-4.10V)
            case 2: return 83 + random.nextInt(2);  // 83-84 (4.15-4.20V)
            case 3: return 40 + random.nextInt(25); // 40-64 (2.00-3.20V)
            default: return 80;
        }
    }

    /**
     * 根据工作状态生成合理的电流原始值（10mA/bit）。
     * <ul>
     *   <li>充电中(1): 0.3~1.0A → 30~100</li>
     *   <li>充电完成(2): 0~0.05A → 0~5</li>
     *   <li>充电异常(3): 0~0.02A → 0~2</li>
     * </ul>
     */
    private int randomCurrentFor(int work) {
        switch (work) {
            case 1: return 30 + random.nextInt(71);  // 30-100 (0.30-1.00A)
            case 2: return random.nextInt(6);        // 0-5 (0.00-0.05A)
            case 3: return random.nextInt(3);        // 0-2 (0.00-0.02A)
            default: return 0;
        }
    }

    /**
     * 电压电流微小随机波动，模拟真实硬件测量噪声。
     * 波动幅度 ±{@value #VOLTAGE_JITTER} (±0.1V) / ±{@value #CURRENT_JITTER} (±0.03A)。
     */
    private void jitterVoltageAndCurrent(Slot slot) {
        if (slot.work == 0) return;
        slot.voltage = clamp(slot.voltage + random.nextInt(VOLTAGE_JITTER * 2 + 1) - VOLTAGE_JITTER, 0, 255);
        slot.current = clamp(slot.current + random.nextInt(CURRENT_JITTER * 2 + 1) - CURRENT_JITTER, 0, 255);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 生成卡号：TSIM + 11 位随机数字，共 15 字符。
     * 参考格式：868909073452875 → TSIM07129452381
     */
    private String generateCardNumber() {
        StringBuilder sb = new StringBuilder("TSIM");
        for (int i = 0; i < 11; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    // ═══════════════════ 工具方法 ═══════════════════

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    // ═══════════════════ 卡槽状态模型 ═══════════════════

    /**
     * 单个卡槽的实时状态。
     * 所有字段由单线程 executor 串行读写，volatile 为防御性保护。
     */
    private static final class Slot {
        volatile int work;             // 1=充电中, 2=充电完成, 3=充电异常
        volatile int card;             // 0=空, 1=有卡
        final int fault;               // 故障码 (0=正常)
        volatile String cardNumber;    // ASCII 卡号, 最大 15 字符
        volatile boolean cardChanged;  // 卡号变化标志（QUERY 后自动清除）
        volatile long doorOpenUntil;   // 舱门打开截止时间戳
        volatile long cardReinsertAt;  // 计划重新插卡时间戳（0=无计划）
        volatile int voltage;          // 电压原始值 (50mV/bit, e.g. 80=4.0V)
        volatile int current;          // 电流原始值 (10mA/bit, e.g. 50=0.5A)

        Slot(int work, int card, int fault, String cardNumber,
             int voltage, int current) {
            this.work = work;
            this.card = card;
            this.fault = fault;
            this.cardNumber = cardNumber;
            this.voltage = voltage;
            this.current = current;
        }
    }
}
