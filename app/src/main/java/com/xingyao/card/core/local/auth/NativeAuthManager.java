package com.xingyao.card.core.local.auth;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class NativeAuthManager {
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    private static final String PREFS = "card_native_auth";
    private static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String ROLE_OPS = "OPS";
    private static final String ROLE_DEVELOPER = "DEVELOPER";
    private static final long SESSION_TTL_MS = 60L * 60L * 1000L;

    private final SharedPreferences preferences;
    private String currentRole;
    private String currentSessionId;
    private long loginAt;
    private long lastActivityAt;

    public NativeAuthManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        installDefaults();
    }

    private void installDefaults() {
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(ROLE_SYSTEM_ADMIN)) {
            editor.putString(ROLE_SYSTEM_ADMIN, "bcb15f821479b4d5772bd0ca866c00ad5f926e3580720659cc80d39c9d09802a");
        }
        if (!preferences.contains(ROLE_OPS)) {
            editor.putString(ROLE_OPS, "4cc8f4d609b717356701c57a03e737e5ac8fe885da8c7163d3de47e01849c635");
        }
        if (!preferences.contains(ROLE_DEVELOPER)) {
            editor.putString(ROLE_DEVELOPER, "68487dc295052aa79c530e283ce698b8c6bb1b42ff0944252e1910dbecdc5425");
        }
        editor.apply();
    }

    public synchronized JSONObject login(String password) throws JSONException {
        String passwordHash = sha256(password == null ? "" : password);
        for (String role : new String[]{ROLE_SYSTEM_ADMIN, ROLE_OPS, ROLE_DEVELOPER}) {
            if (passwordHash.equals(preferences.getString(role, ""))) {
                long now = System.currentTimeMillis();
                currentRole = role;
                currentSessionId = "NS-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US);
                loginAt = now;
                lastActivityAt = now;
                return sessionSnapshotLocked();
            }
        }
        return null;
    }

    public synchronized void logout() {
        clearSessionLocked();
    }

    public synchronized String getCurrentRole() {
        return isSessionActiveLocked() ? currentRole : null;
    }

    /**
     * Returns null when access is allowed, AUTH_REQUIRED when no active native
     * session exists, or PERMISSION_DENIED when the role lacks the permission.
     */
    public synchronized String authorize(String permission) {
        if (permission == null || permission.trim().isEmpty()) return null;
        if (!isSessionActiveLocked()) return AUTH_REQUIRED;
        if (!hasPermissionLocked(currentRole, permission)) return PERMISSION_DENIED;
        lastActivityAt = System.currentTimeMillis();
        return null;
    }

    public synchronized JSONObject sessionSnapshot() throws JSONException {
        if (!isSessionActiveLocked()) return new JSONObject().put("authenticated", false);
        return sessionSnapshotLocked();
    }

    public synchronized boolean changePassword(String role, String password) {
        if (!isSessionActiveLocked() || !hasPermissionLocked(currentRole, "auth.password.manage")) {
            return false;
        }
        if (role == null || password == null || !password.matches("\\d{6}")) {
            return false;
        }
        if (!ROLE_SYSTEM_ADMIN.equals(role) && !ROLE_OPS.equals(role) && !ROLE_DEVELOPER.equals(role)) {
            return false;
        }
        lastActivityAt = System.currentTimeMillis();
        return preferences.edit().putString(role, sha256(password)).commit();
    }

    private JSONObject sessionSnapshotLocked() throws JSONException {
        return new JSONObject()
                .put("authenticated", true)
                .put("sessionId", currentSessionId)
                .put("role", currentRole)
                .put("permissions", permissionsFor(currentRole))
                .put("loginAt", loginAt)
                .put("lastActivityAt", lastActivityAt)
                .put("expiresAt", lastActivityAt + SESSION_TTL_MS);
    }

    private boolean isSessionActiveLocked() {
        if (currentRole == null || currentSessionId == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastActivityAt <= SESSION_TTL_MS) return true;
        clearSessionLocked();
        return false;
    }

    private void clearSessionLocked() {
        currentRole = null;
        currentSessionId = null;
        loginAt = 0L;
        lastActivityAt = 0L;
    }

    private boolean hasPermissionLocked(String role, String permission) {
        String[] permissions = permissionMap().get(role);
        if (permissions == null) return false;
        for (String candidate : permissions) {
            if (candidate.equals(permission)) return true;
        }
        return false;
    }

    private JSONArray permissionsFor(String role) {
        JSONArray array = new JSONArray();
        String[] list = permissionMap().get(role);
        if (list != null) {
            for (String permission : list) array.put(permission);
        }
        return array;
    }

    private Map<String, String[]> permissionMap() {
        Map<String, String[]> permissions = new LinkedHashMap<>();
        permissions.put(ROLE_SYSTEM_ADMIN, new String[]{
                "system.menu", "management.menu", "cabinet.view", "cabinet.unlock", "cabinet.unlockAll",
                "employee.view", "employee.edit", "biometric.register", "history.view", "unit.view",
                "settings.basic", "settings.advanced", "authorization.manage",
                "upgrade.app", "upgrade.firmware", "debug.command", "auth.password.manage", "app.restart"
        });
        permissions.put(ROLE_OPS, new String[]{
                "system.menu", "management.menu", "cabinet.view", "cabinet.unlock", "cabinet.unlockAll",
                "history.view", "unit.view", "settings.basic", "upgrade.firmware", "app.restart"
        });
        permissions.put(ROLE_DEVELOPER, new String[]{
                "system.menu", "cabinet.view", "cabinet.unlock", "settings.basic", "settings.advanced",
                "authorization.manage", "upgrade.app", "upgrade.firmware",
                "debug.command", "app.restart"
        });
        return permissions;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
