package com.xingyao.card.core.serial;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WorkCardProtocolTest {
    private static final long CAPTURE_TIME = 0x6A62E374L;

    @Test
    public void buildsCapturedMainBoardBroadcastQuery() {
        assertHexEquals("DDCC000BF000015AA55A016A62E37400CE",
                WorkCardProtocol.broadcastQuery(CAPTURE_TIME));
    }

    @Test
    public void buildsTimestampedGroupAndDirectQueries() {
        assertHexEquals("DDCC000BF00B015AA55A016A62E374E5BF",
                WorkCardProtocol.groupQuery(11, CAPTURE_TIME));
        assertHexEquals("DDCC000BF00B015AA55AA56A62E3743CCE",
                WorkCardProtocol.directQuery(11, CAPTURE_TIME));
    }

    @Test
    public void decodesBothObservedCrcByteOrdersAndRejectsInvalidCrc() {
        byte[] highLow = WorkCardProtocol.frame(23, WorkCardProtocol.FUNCTION_QUERY, new byte[22]);
        byte[] lowHigh = lowFirstCrc(highLow);
        assertTrue(highLow[highLow.length - 2] != highLow[highLow.length - 1]);

        assertEquals(1, decode(highLow).size());
        List<WorkCardProtocol.Frame> decodedLowHigh = decode(lowHigh);
        assertEquals(1, decodedLowHigh.size());
        assertEquals(23, decodedLowHigh.get(0).slaveAddress);
        assertArrayEquals(new byte[22], decodedLowHigh.get(0).data);

        lowHigh[10] ^= 0x01;
        assertTrue(decode(lowHigh).isEmpty());
    }

    @Test
    public void decodesOneHundredAndTwentyContiguousBoardResponses() {
        List<Byte> buffer = new ArrayList<>();
        for (int address = 1; address <= 120; address++) {
            for (byte value : lowFirstCrc(WorkCardProtocol.frame(
                    address, WorkCardProtocol.FUNCTION_QUERY, new byte[22]))) {
                buffer.add(value);
            }
        }

        List<WorkCardProtocol.Frame> frames = WorkCardProtocol.decode(buffer);
        assertEquals(120, frames.size());
        assertEquals(1, frames.get(0).slaveAddress);
        assertEquals(120, frames.get(119).slaveAddress);
        assertTrue(buffer.isEmpty());
    }

    private static List<WorkCardProtocol.Frame> decode(byte[] bytes) {
        List<Byte> buffer = new ArrayList<>(bytes.length);
        for (byte value : bytes) buffer.add(value);
        return WorkCardProtocol.decode(buffer);
    }

    private static void assertHexEquals(String expected, byte[] actual) {
        assertEquals(expected, WorkCardProtocol.hex(actual));
    }

    private static byte[] lowFirstCrc(byte[] highFirst) {
        byte[] result = highFirst.clone();
        byte last = result[result.length - 1];
        result[result.length - 1] = result[result.length - 2];
        result[result.length - 2] = last;
        return result;
    }
}
