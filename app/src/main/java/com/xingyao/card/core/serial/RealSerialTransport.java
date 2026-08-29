package com.xingyao.card.core.serial;

import java.io.IOException;

/** Keeps the production serial implementation behind the common transport boundary. */
final class RealSerialTransport implements SerialTransport {
    private final SerialConnectionManager delegate = new SerialConnectionManager();

    @Override public boolean open(String port, int baudRate) { return delegate.open(port, baudRate); }
    @Override public void close() { delegate.close(); }
    @Override public boolean isOpen() { return delegate.isOpen(); }
    @Override public void write(byte[] data) { delegate.write(data); }
    @Override public void writeDirect(byte[] data) throws IOException { delegate.writeDirect(data); }

    @Override
    public void setOnDataReceived(OnDataReceived callback) {
        delegate.setOnDataReceived(callback == null ? null : callback::onData);
    }

    @Override public boolean isSimulator() { return false; }
    @Override public int simulatedSlotCount() { return 0; }
}
