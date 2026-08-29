package com.xingyao.serialdebug.protocol;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Protocol V1.5 for the Android host and work-card unit boards.
 * Frame: DD CC [length 2B] [master 1B] [slave 1B] [func 1B] [data N B] [CRC 2B]
 */
public final class WorkCardProtocol {
    public static final int FUNCTION_QUERY = 0x01;
    public static final int FUNCTION_OPEN_DOOR = 0x51;
    public static final int FUNCTION_LED = 0x52;
    public static final int FUNCTION_VERSION = 0x53;
    public static final int MASTER_ADDRESS = 0xF0;

    private static final int HEADER_0 = 0xDD;
    private static final int HEADER_1 = 0xCC;
    private static final byte[] FIXED_PREFIX = {(byte) 0x5A, (byte) 0xA5, (byte) 0x5A, (byte) 0xA5};

    private WorkCardProtocol() { }

    // ---- 构造指令 ----

    public static byte[] query(int address) {
        return frame(address, FUNCTION_QUERY, concat(FIXED_PREFIX, new byte[]{0x00,0x00,0x00,0x00}));
    }

    public static byte[] openDoor(int address, boolean administrator) {
        return frame(address, FUNCTION_OPEN_DOOR, concat(FIXED_PREFIX, new byte[]{(byte) (administrator ? 0x02 : 0x01)}));
    }

    public static byte[] setLedDutyCycle(int address, int dutyCycle) {
        if (dutyCycle < 30 || dutyCycle > 100) throw new IllegalArgumentException("LED占空比必须在30到100之间");
        return frame(address, FUNCTION_LED, concat(FIXED_PREFIX, new byte[]{(byte) dutyCycle}));
    }

    public static byte[] readVersion(int address) {
        return frame(address, FUNCTION_VERSION, concat(FIXED_PREFIX, new byte[]{0x01}));
    }

    public static byte[] rawCommand(int address, int function, byte[] data) {
        return frame(address, function, data);
    }

    // ---- 组帧 ----

    public static byte[] frame(int address, int function, byte[] data) {
        if (address < 0 || address > 255) throw new IllegalArgumentException("单板地址必须在0到255之间");
        int length = 3 + data.length; // master(1) + slave(1) + func(1) + data
        byte[] frame = new byte[6 + length];
        frame[0] = (byte) HEADER_0;
        frame[1] = (byte) HEADER_1;
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        frame[4] = (byte) MASTER_ADDRESS;
        frame[5] = (byte) address;
        frame[6] = (byte) function;
        System.arraycopy(data, 0, frame, 7, data.length);
        int crc = crc16Modbus(frame, 0, frame.length - 2);

        byte[] crcData = Arrays.copyOfRange(frame, 6, frame.length - 2);
        Log.d("CRC_DEBUG", "CRC计算数据: " + hex(crcData));

        int crc2 = crc16Modbus(crcData, 0, crcData.length);
        Log.d("CRC_DEBUG", "CRC计算结果: " + Integer.toHexString(crc2));

        frame[frame.length - 2] = (byte) crc;
        frame[frame.length - 1] = (byte) (crc >>> 8);
        return frame;
    }

    // ---- 解码 ----

    public static List<Frame> decode(List<Byte> buffer) {
        List<Frame> frames = new ArrayList<>();
        while (true) {
            int start = findHeader(buffer);
            if (start < 0) {
                while (buffer.size() > 1) buffer.remove(0);
                return frames;
            }
            for (int index = 0; index < start; index++) buffer.remove(0);
            if (buffer.size() < 7) return frames;
            int length = unsigned(buffer.get(2)) << 8 | unsigned(buffer.get(3));
            int totalLength = 6 + length;
            if (length < 3 || length > 512) { buffer.remove(0); continue; }
            if (buffer.size() < totalLength) return frames;
            byte[] raw = new byte[totalLength];
            for (int index = 0; index < totalLength; index++) raw[index] = buffer.get(index);
            // 按实机要求：接收不校验 CRC（板卡字节序与文档不符曾导致整帧被静默丢弃），仅按帧头/长度/字段解析；发送端仍照常带 CRC
            byte[] data = Arrays.copyOfRange(raw, 7, totalLength - 2);
            frames.add(new Frame(unsigned(raw[4]), unsigned(raw[5]), unsigned(raw[6]), data, raw));
            for (int index = 0; index < totalLength; index++) buffer.remove(0);
        }
    }

    // ---- CRC16 Modbus ----

    public static int crc16Modbus(byte[] bytes, int offset, int length) {
        int crc = 0xFFFF;
        for (int index = offset; index < offset + length; index++) {
            crc ^= unsigned(bytes[index]);
            for (int bit = 0; bit < 8; bit++)
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xFFFF;
    }

    // ---- 工具 ----

    public static boolean hasFixedPrefix(byte[] data) {
        if (data.length < FIXED_PREFIX.length) return false;
        for (int index = 0; index < FIXED_PREFIX.length; index++)
            if (data[index] != FIXED_PREFIX[index]) return false;
        return true;
    }

    public static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02X", unsigned(value)));
        return builder.toString();
    }

    private static int findHeader(List<Byte> buffer) {
        for (int index = 0; index + 1 < buffer.size(); index++)
            if (unsigned(buffer.get(index)) == HEADER_0 && unsigned(buffer.get(index + 1)) == HEADER_1)
                return index;
        return -1;
    }

    private static int unsigned(byte value) { return value & 0xFF; }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    // ---- 帧结构 ----

    public static final class Frame {
        public final int masterAddress;
        public final int slaveAddress;
        public final int function;
        public final byte[] data;
        public final byte[] raw;

        Frame(int masterAddress, int slaveAddress, int function, byte[] data, byte[] raw) {
            this.masterAddress = masterAddress;
            this.slaveAddress = slaveAddress;
            this.function = function;
            this.data = data;
            this.raw = raw;
        }

        @Override
        public String toString() {
            return String.format("[主0x%02X→从0x%02X] 功能=0x%02X 数据=%s 原始=%s",
                    masterAddress, slaveAddress, function, hex(data), hex(raw));
        }
    }
}
