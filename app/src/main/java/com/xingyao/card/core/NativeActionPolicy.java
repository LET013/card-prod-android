package com.xingyao.card.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Native authorization policy for every action exposed by the WebView bridge.
 *
 * UI visibility is not a security boundary. Every protected action must pass
 * through this policy again before Android performs the operation.
 */
public final class NativeActionPolicy {
    private static final Set<String> PUBLIC_ACTIONS;
    private static final Map<String, String> REQUIRED_PERMISSIONS;

    static {
        LinkedHashSet<String> publicActions = new LinkedHashSet<>();
        publicActions.add("app.ready");
        publicActions.add("auth.login");
        publicActions.add("auth.logout");
        publicActions.add("settings.load");
        publicActions.add("cabinet.getSlots");
        publicActions.add("face.getStatus");
        publicActions.add("face.verify");
        publicActions.add("fingerprint.getStatus");
        publicActions.add("fingerprint.verify");
        publicActions.add("fingerprint.cancel");
        PUBLIC_ACTIONS = Collections.unmodifiableSet(publicActions);

        LinkedHashMap<String, String> permissions = new LinkedHashMap<>();
        permissions.put("settings.save", "settings.basic");
        permissions.put("auth.changePassword", "auth.password.manage");
        permissions.put("device.snapshot", "cabinet.view");
        permissions.put("serial.getStatus", "settings.basic");
        permissions.put("serial.reconnect", "settings.basic");
        permissions.put("serial.setPolling", "settings.basic");
        permissions.put("serial.listPorts", "settings.basic");
        permissions.put("serial.send", "debug.command");
        permissions.put("cabinet.unlockDoor", "cabinet.unlock");
        permissions.put("cabinet.takeCard", "cabinet.unlock");
        permissions.put("cabinet.returnCard", "cabinet.unlock");
        permissions.put("cabinet.querySlot", "cabinet.view");
        permissions.put("cabinet.readVersion", "cabinet.view");
        permissions.put("cabinet.unlockAll", "cabinet.unlockAll");
        permissions.put("face.reactivate", "engine.activate");
        permissions.put("face.enroll", "biometric.register");
        permissions.put("fingerprint.enroll", "biometric.register");
        permissions.put("socket.getStatus", "cabinet.view");
        permissions.put("employee.search", "employee.view");
        permissions.put("employee.delete", "employee.edit");
        permissions.put("employee.upsert", "employee.edit");
        permissions.put("employee.face.upsert", "biometric.register");
        permissions.put("employee.face.registered", "employee.view");
        permissions.put("fingerprint.uploadFeature", "biometric.register");
        permissions.put("logs.uploadBatch", "debug.command");
        permissions.put("firmware.download", "upgrade.firmware");
        permissions.put("app.restart", "app.restart");
        REQUIRED_PERMISSIONS = Collections.unmodifiableMap(permissions);
    }

    private NativeActionPolicy() { }

    public static boolean isKnownAction(String action) {
        return action != null && (PUBLIC_ACTIONS.contains(action) || REQUIRED_PERMISSIONS.containsKey(action));
    }

    public static boolean isPublicAction(String action) {
        return action != null && PUBLIC_ACTIONS.contains(action);
    }

    /** Returns null for public actions or the permission required for protected actions. */
    public static String requiredPermission(String action) {
        return action == null ? null : REQUIRED_PERMISSIONS.get(action);
    }
}
