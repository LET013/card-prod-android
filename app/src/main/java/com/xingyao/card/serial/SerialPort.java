package com.xingyao.card.serial;

import android.util.Log;

import java.io.FileDescriptor;

/** JNI shell for the device serial port. */
public final class SerialPort {
    private static final String TAG = "SerialPort";
    private static volatile boolean libraryLoaded;
    private static volatile String libraryLoadError;

    static {
        try {
            System.loadLibrary("SerialPort");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            libraryLoaded = false;
            libraryLoadError = error.getMessage();
            Log.e(TAG, "libSerialPort.so load failed", error);
        }
    }

    private FileDescriptor mFd;

    public static boolean isAvailable() { return libraryLoaded; }
    public static String getLoadError() { return libraryLoadError; }

    protected native FileDescriptor open(String path, int baudRate, int flags);
    private native void closeNative();

    protected synchronized void close() {
        if (mFd == null) return;
        closeNative();
        mFd = null;
    }
}
