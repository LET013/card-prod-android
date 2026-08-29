package com.xingyao.card.core.serial;

/** Process-local wiring between the foreground service and JsBridgeV2. */
public final class SerialRuntimeRegistry {
    private static volatile DeviceSerialManager current;

    private SerialRuntimeRegistry() { }

    public static void install(DeviceSerialManager manager) { current = manager; }
    public static DeviceSerialManager get() { return current; }
    public static void clear(DeviceSerialManager manager) {
        if (current == manager) current = null;
    }
}
