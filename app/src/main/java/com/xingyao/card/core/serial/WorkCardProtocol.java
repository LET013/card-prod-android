package com.xingyao.card.core.serial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Protocol V1.5 for the Android host and work-card unit boards. */
public final class WorkCardProtocol {
    public static final int FUNCTION_QUERY = 0x01;
    public static final int FUNCTION_OPEN_DOOR = 0x51;
    public static final int FUNCTION_LED = 0x52;
    public static final int FUNCTION_VERSION = 0x53;
    public static final int FUNCTION_UPGRADE_ENABLE = 0x80;
    public static final int FUNCTION_UPGRADE_DATA = 0x81;
    public static final int FIRMWARE_CHUNK_SIZE = 128;
    public static final int MASTER_ADDRESS = 0xF0;
    private static final int HEADER_0 = 0xDD;
    private static final int HEADER_1 = 0xCC;
    private static final byte[] FIXED_PREFIX = {(byte) 0x5A, (byte) 0xA5, (byte) 0x5A, (byte) 0xA5};
    private static final byte[] BROADCAST_QUERY_PREFIX = {
            (byte) 0x5A, (byte) 0xA5, (byte) 0x5A, 0x01
    };
    private static final byte[] UPGRADE_ENABLE_DATA = {
            (byte) 0x5A, (byte) 0x5A, (byte) 0xA5, (byte) 0xA5,
            (byte) 0xA5, (byte) 0xA5, (byte) 0x5A, (byte) 0x5A
    };

    private WorkCardProtocol() { }

    public static byte[] query(int address) {
        return frame(address, FUNCTION_QUERY, concat(FIXED_PREFIX, new byte[]{0x01}));
    }

    /** Main-board broadcast query; the board returns one complete status frame per slot. */
    public static byte[] broadcastQuery(long unixSeconds) {
        return frame(0, FUNCTION_QUERY, concat(BROADCAST_QUERY_PREFIX, unixSeconds(unixSeconds)));
    }

    /** Small-mainboard group query, addressed by the first slot in that group. */
    public static byte[] groupQuery(int firstSlotAddress, long unixSeconds) {
        requireSlotAddress(firstSlotAddress);
        return frame(firstSlotAddress, FUNCTION_QUERY, concat(BROADCAST_QUERY_PREFIX, unixSeconds(unixSeconds)));
    }

    /** Direct query for a cabinet without a main board. */
    public static byte[] directQuery(int slotAddress, long unixSeconds) {
        requireSlotAddress(slotAddress);
        return frame(slotAddress, FUNCTION_QUERY, concat(FIXED_PREFIX, unixSeconds(unixSeconds)));
    }

    public static byte[] openDoor(int address, boolean administrator) {
        return frame(address, FUNCTION_OPEN_DOOR,
                concat(FIXED_PREFIX, new byte[]{(byte) (administrator ? 0x02 : 0x01)}));
    }

    public static byte[] setLedDutyCycle(int address, int dutyCycle) {
        if (dutyCycle < 30 || dutyCycle > 100) throw new IllegalArgumentException("LED占空比必须在30到100之间");
        return frame(address, FUNCTION_LED, concat(FIXED_PREFIX, new byte[]{(byte) dutyCycle}));
    }

    public static byte[] readVersion(int address) {
        return frame(address, FUNCTION_VERSION, concat(FIXED_PREFIX, new byte[]{0x01}));
    }

    /** V1.5 §2.7：广播进入单板升级模式，无应答。 */
    public static byte[] upgradeEnable() {
        return frame(0, FUNCTION_UPGRADE_ENABLE,
                Arrays.copyOf(UPGRADE_ENABLE_DATA, UPGRADE_ENABLE_DATA.length));
    }

    /** V1.5 §2.8：标识 0x01 代表完整 128 字节分片，帧序号按单字节循环并携带反码。 */
    public static byte[] upgradeData(int sequence, byte[] firmwareData, int length) {
        if (firmwareData == null) throw new IllegalArgumentException("固件数据不能为空");
        if (length != FIRMWARE_CHUNK_SIZE || length > firmwareData.length) {
            throw new IllegalArgumentException("0x01 固件分片必须为完整128字节");
        }
        int normalizedSequence = sequence & 0xFF;
        byte[] data = new byte[length + 3];
        data[0] = 0x01;
        data[1] = (byte) normalizedSequence;
        data[2] = (byte) (~normalizedSequence);
        System.arraycopy(firmwareData, 0, data, 3, length);
        return frame(0, FUNCTION_UPGRADE_DATA, data);
    }

    public static byte[] frame(int address, int function, byte[] data) {
        if (address < 0 || address > 255) throw new IllegalArgumentException("单板地址必须在0到255之间");
        int length = 3 + data.length; // Master address + slave address + function + data.
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
        frame[frame.length - 2] = (byte) (crc >>> 8); // Protocol transmits CRC high byte first.
        frame[frame.length - 1] = (byte) crc;
        return frame;
    }

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
            if (length < 3 || length > 512) {
                buffer.remove(0);
                continue;
            }
            if (buffer.size() < totalLength) return frames;
            byte[] raw = new byte[totalLength];
            for (int index = 0; index < totalLength; index++) raw[index] = buffer.get(index);
            int expectedCrc = crc16Modbus(raw, 0, totalLength - 2);
            int receivedHighLow = unsigned(raw[totalLength - 2]) << 8 | unsigned(raw[totalLength - 1]);
            int receivedLowHigh = unsigned(raw[totalLength - 1]) << 8 | unsigned(raw[totalLength - 2]);
            // Captures show board replies use low-byte-first CRC while commands use high-byte-first.
            if (expectedCrc != receivedHighLow && expectedCrc != receivedLowHigh) {
                buffer.remove(0);
                continue;
            }
            byte[] data = Arrays.copyOfRange(raw, 7, totalLength - 2);
            frames.add(new Frame(unsigned(raw[4]), unsigned(raw[5]), unsigned(raw[6]), data, raw));
            for (int index = 0; index < totalLength; index++) buffer.remove(0);
        }
    }

    public static int crc16Modbus(byte[] bytes, int offset, int length) {
        int crc = 0xFFFF;
        for (int index = offset; index < offset + length; index++) {
            crc ^= unsigned(bytes[index]);
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xFFFF;
    }

    public static boolean hasFixedPrefix(byte[] data) {
        if (data.length < FIXED_PREFIX.length) return false;
        for (int index = 0; index < FIXED_PREFIX.length; index++) if (data[index] != FIXED_PREFIX[index]) return false;
        return true;
    }

    public static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02X", unsigned(value)));
        return builder.toString();
    }

    private static int findHeader(List<Byte> buffer) {
        for (int index = 0; index + 1 < buffer.size(); index++) {
            if (unsigned(buffer.get(index)) == HEADER_0 && unsigned(buffer.get(index + 1)) == HEADER_1) return index;
        }
        return -1;
    }

    private static int unsigned(byte value) { return value & 0xFF; }

    private static void requireSlotAddress(int address) {
        if (address < 1 || address > 255) throw new IllegalArgumentException("Slot address must be between 1 and 255");
    }

    private static byte[] unixSeconds(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Unix time must fit in an unsigned 32-bit value");
        }
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value
        };
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

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
    }
}
