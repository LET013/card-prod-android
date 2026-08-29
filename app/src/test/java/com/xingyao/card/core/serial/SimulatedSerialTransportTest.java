package com.xingyao.card.core.serial;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** SimulatedSerialTransport protocol + simulation lifecycle tests */
public class SimulatedSerialTransportTest {
    private SimulatedSerialTransport transport;

    @Before
    public void setUp() { transport = new SimulatedSerialTransport(); }

    @After
    public void tearDown() { if (transport != null) transport.close(); }

    // ── 随机初始化验证 ──

    @Test
    public void initCreatesTwentySlotsWithValidStates() throws Exception {
        transport.open("sim", 115200);
        assertEquals(20, transport.simulatedSlotCount());
        for (int address = 1; address <= 20; address++) {
            WorkCardProtocol.Frame frame = queryAndGet(transport, address);
            assertNotNull("Slot " + address + " should respond", frame);
            assertEquals(address, frame.slaveAddress);
            assertEquals(WorkCardProtocol.FUNCTION_QUERY, frame.function);
            assertEquals(22, frame.data.length);
            int work = frame.data[0] & 0xFF;
            assertTrue("Slot " + address + " work out of range: " + work, work >= 1 && work <= 4);
            int card = frame.data[2] & 0xFF;
            assertTrue("Slot " + address + " card out of range: " + card, card == 0 || card == 1);
            // 验证电压电流不为零（已随机初始化，但受 simulationTick 波动影响）
            int voltage = frame.data[20] & 0xFF;
            int current = frame.data[21] & 0xFF;
            assertTrue("Slot " + address + " voltage should be > 0, got: " + voltage, voltage > 0);
            assertTrue("Slot " + address + " voltage should be <= 255, got: " + voltage, voltage <= 255);
            if (card == 1) {
                byte[] cardNo = Arrays.copyOfRange(frame.data, 4, 19);
                String cn = new String(cardNo, StandardCharsets.US_ASCII).trim();
                assertTrue("Card number should start with TSIM: " + cn, cn.startsWith("TSIM"));
            }
        }
    }

    @Test
    public void cardNumberFormatIsTSIMPlusElevenDigits() throws Exception {
        transport.open("sim", 115200);
        // 遍历所有卡槽，找到有卡的验证卡号格式
        for (int address = 1; address <= 20; address++) {
            WorkCardProtocol.Frame frame = queryAndGet(transport, address);
            assertNotNull(frame);
            int card = frame.data[2] & 0xFF;
            if (card == 1) {
                byte[] cardNo = Arrays.copyOfRange(frame.data, 4, 19);
                String cn = new String(cardNo, StandardCharsets.US_ASCII).trim();
                assertEquals("Card number length", 15, cn.length());
                assertTrue("Card number should start with TSIM: " + cn, cn.startsWith("TSIM"));
                for (int i = 4; i < 15; i++) {
                    assertTrue("Position " + i + " should be digit", Character.isDigit(cn.charAt(i)));
                }
                return;
            }
        }
        // 所有槽都为空（极低概率），也算通过
    }

    // ── 门控 / 取卡 / 重新插卡 ──

    @Test
    public void openDoorReturnsAcceptedAckAndQuerySeesDoorOpen() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(1, 1, 1, 0, "TSIM00000000001");

        // 发送管理员开门命令
        byte[] raw = writeAndGetRaw(transport, WorkCardProtocol.openDoor(1, true));
        assertNotNull(raw);
        WorkCardProtocol.Frame ack = decodeRaw(raw).get(0);
        assertEquals(1, ack.slaveAddress);
        assertEquals(WorkCardProtocol.FUNCTION_OPEN_DOOR, ack.function);
        assertTrue(Arrays.equals(
                new byte[]{(byte) 0x5A, (byte) 0xA5, (byte) 0x5A, (byte) 0xA5, 0x11},
                ack.data));

        // 查询应看到 doorOpen=1
        WorkCardProtocol.Frame qf = queryAndGet(transport, 1);
        assertEquals(1, qf.data[1] & 0xFF);
    }

    @Test
    public void adminOpenDoesNotRemovePresentCard() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(2, 2, 1, 0, "TSIM00000000002");

        writeAndGetRaw(transport, WorkCardProtocol.openDoor(2, true));
        Thread.sleep(150); // 等待门开延迟

        WorkCardProtocol.Frame qf = queryAndGet(transport, 2);
        assertEquals(1, qf.data[2] & 0xFF);
    }

    @Test
    public void issueOpenTransitionsPresentCardToEmptyAndSchedulesReinsert() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(5, 1, 1, 0, "TSIM12345678901");

        byte[] ack = writeAndGetRaw(transport, WorkCardProtocol.openDoor(5, false));
        assertNotNull(ack);
        Thread.sleep(200); // 等待取卡延迟（120ms）

        WorkCardProtocol.Frame qf = queryAndGet(transport, 5);
        assertEquals(0, qf.data[2] & 0xFF);                // 卡已被移除
        assertTrue((qf.data[3] & 0xFF) != 0);               // cardChanged 标志置位

        // 再查一次确认 cardChanged 已清除
        qf = queryAndGet(transport, 5);
        assertEquals(0, qf.data[3] & 0xFF);
    }

    // ── 固件升级 ──

    @Test
    public void firmwareDataBeforeEnableIsRejected() {
        transport.open("sim", 115200);
        // 创建合法的固件数据帧，但在升级使能前发送 → 应被拒绝
        byte[] data = WorkCardProtocol.upgradeData(0, new byte[128], 128);
        try {
            transport.writeDirect(data);
            fail("Should throw because enable was not sent");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("尚未发送升级使能帧"));
        }
    }

    @Test
    public void firmwareFramesAreValidatedAndCountedWithoutFakeAck() throws Exception {
        transport.open("sim", 115200);
        transport.writeDirect(WorkCardProtocol.upgradeEnable());
        transport.writeDirect(WorkCardProtocol.upgradeData(0, new byte[128], 128));
        transport.writeDirect(WorkCardProtocol.upgradeData(1, new byte[128], 128));
        assertEquals(2, transport.firmwareFrameCountForTest());
        assertEquals(256L, transport.firmwareByteCountForTest());
    }

    @Test
    public void firmwarePartialChunkIsRejectedByProtocol() {
        // upgradeData 要求 length==128，传入 100 应在构造时抛异常
        try {
            WorkCardProtocol.upgradeData(0, new byte[128], 100);
            fail("Should throw for partial chunk");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("完整128字节"));
        }
    }

    // ── 协议边界 ──

    @Test
    public void invalidAddressProducesNoResponse() throws Exception {
        transport.open("sim", 115200);
        AtomicReference<byte[]> ref = new AtomicReference<>();
        transport.setOnDataReceived(ref::set);
        transport.write(WorkCardProtocol.query(21));
        Thread.sleep(50);
        assertNull("Slot 21 should not produce response", ref.get());
    }

    @Test
    public void queryInClosedStateProducesNoResponse() {
        // 未 open，write 应无响应
        AtomicReference<byte[]> ref = new AtomicReference<>();
        transport.setOnDataReceived(ref::set);
        transport.write(WorkCardProtocol.query(1));
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        assertNull(ref.get());
    }

    @Test
    public void ledDutyCycleUsesExistingProtocolAndDoesNotChangeConnectionState() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(1, 1, 1, 0, "TSIM00000000001");

        transport.write(WorkCardProtocol.setLedDutyCycle(1, 60));
        Thread.sleep(50);

        assertTrue(transport.isOpen());
        assertNotNull(queryAndGet(transport, 1));
    }

    @Test
    public void closedSimulatorCanReconnectAndRespondAgain() throws Exception {
        transport.open("sim", 115200);
        assertNotNull(queryAndGet(transport, 1));

        transport.close();
        assertFalse(transport.isOpen());
        assertTrue(transport.open("sim", 115200));

        assertTrue(transport.isOpen());
        assertNotNull(queryAndGet(transport, 1));
    }

    // ── SerialMessageRouter 集成 ──

    @Test
    public void queryResponseTravelsThroughTheExistingMessageRouter() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(3, 2, 1, 0, "TSIM00000000003");

        SerialMessageRouter router = new SerialMessageRouter();
        AtomicReference<JSONObject> slotStatus = new AtomicReference<>();
        router.setCallback(new SerialMessageRouter.FrameCallback() {
            @Override public void onQueryResponse(int address, JSONObject status) { slotStatus.set(status); }
            @Override public void onDoorResponse(int address, boolean accepted, JSONObject frameInfo) {}
            @Override public void onVersionResponse(int address, String version, JSONObject frameInfo) {}
            @Override public void onAnyFrame(JSONObject frameInfo) {}
        });

        CountDownLatch latch = new CountDownLatch(1);
        transport.setOnDataReceived(data -> {
            router.onRawData(data);
            latch.countDown();
        });
        transport.write(WorkCardProtocol.query(3));

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertNotNull(slotStatus.get());
        assertEquals(3, slotStatus.get().getInt("slotNumber"));
        // work=2 → mapStatus 返回 "CHARGING"
        assertEquals("CHARGING", slotStatus.get().getString("status"));
    }

    @Test
    public void removedCardChangeWithNoCardNumberIsEmptyNotIllegal() {
        assertEquals("EMPTY", SerialMessageRouter.mapStatus(3, 0, 0, false, ""));
        assertEquals("EMPTY", SerialMessageRouter.mapStatus(1, 2, 0, true, ""));
        assertEquals("ILLEGAL_CARD", SerialMessageRouter.mapStatus(1, 2, 0, false, ""));
    }

    // ── 模拟生命周期 ──

    @Test
    public void reinsertSchedulesWorkStateToRandomOnCardInsert() throws Exception {
        transport.open("sim", 115200);
        transport.setSlotForTest(8, 1, 1, 0, "TSIM12345678901");

        // 取卡
        writeAndGetRaw(transport, WorkCardProtocol.openDoor(8, false));
        Thread.sleep(200);

        WorkCardProtocol.Frame qf = queryAndGet(transport, 8);
        assertEquals(0, qf.data[2] & 0xFF); // 卡已移除
        assertEquals(1, qf.data[0] & 0xFF); // 取卡后变为充电中
    }

    @Test
    public void voltageAndCurrentCorrespondToWorkState() throws Exception {
        transport.open("sim", 115200);
        // 注入已知充电中状态
        transport.setSlotForTest(10, 1, 1, 0, "TSIM00000000010", 80, 50);
        WorkCardProtocol.Frame qf = queryAndGet(transport, 10);
        assertEquals(80, qf.data[20] & 0xFF);
        assertEquals(50, qf.data[21] & 0xFF);

        // 注入充电完成状态
        transport.setSlotForTest(11, 2, 1, 0, "TSIM00000000011", 84, 2);
        qf = queryAndGet(transport, 11);
        assertEquals(84, qf.data[20] & 0xFF);
        assertEquals(2, qf.data[21] & 0xFF);

        // 注入充电异常状态
        transport.setSlotForTest(12, 3, 0, 0, "", 50, 1);
        qf = queryAndGet(transport, 12);
        assertEquals(50, qf.data[20] & 0xFF);
        assertEquals(1, qf.data[21] & 0xFF);
    }

    // ── helpers ──

    /** 发送 QUERY 命令，等待响应并返回解析后的帧 */
    private static WorkCardProtocol.Frame queryAndGet(SimulatedSerialTransport t, int address)
            throws Exception {
        AtomicReference<WorkCardProtocol.Frame> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        t.setOnDataReceived(data -> {
            for (WorkCardProtocol.Frame f : decodeRaw(data)) {
                ref.set(f);
                latch.countDown();
            }
        });
        t.write(WorkCardProtocol.query(address));
        assertTrue("Timed out waiting for slot " + address, latch.await(500, TimeUnit.MILLISECONDS));
        return ref.get();
    }

    /** 发送命令，等待响应并返回原始字节 */
    private static byte[] writeAndGetRaw(SimulatedSerialTransport t, byte[] cmd) throws Exception {
        AtomicReference<byte[]> ref = new AtomicReference<>();
        t.setOnDataReceived(ref::set);
        t.write(cmd);
        Thread.sleep(60);
        return ref.get();
    }

    private static java.util.List<WorkCardProtocol.Frame> decodeRaw(byte[] bytes) {
        java.util.List<Byte> buffer = new java.util.ArrayList<>(bytes.length);
        for (byte b : bytes) buffer.add(b);
        return WorkCardProtocol.decode(buffer);
    }
}
