package com.xingyao.card.core.serial;

import java.io.IOException;

/** Byte-level serial transport used by the protocol manager. */
interface SerialTransport {
    interface OnDataReceived {
        void onData(byte[] data);
    }

    boolean open(String port, int baudRate);

    void close();

    boolean isOpen();

    void write(byte[] data);

    /** 在调用线程完成实际写入；用于需要可靠错误传播的固件传输。 */
    void writeDirect(byte[] data) throws IOException;

    void setOnDataReceived(OnDataReceived callback);

    boolean isSimulator();

    int simulatedSlotCount();
}
