package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public class NativeAuthManager {
    private static final String PREFS = "card_native_auth";
    private static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String ROLE_OPS = "OPS";
    private static final String ROLE_DEVELOPER = "DEVELOPER";

    private final SharedPreferences preferences;
    private String currentRole;

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

    public JSONObject login(String password) throws JSONException {
        String passwordHash = sha256(password == null ? "" : password);
        for (String role : new String[]{ROLE_SYSTEM_ADMIN, ROLE_OPS, ROLE_DEVELOPER}) {
            if (passwordHash.equals(preferences.getString(role, ""))) {
                currentRole = role;
                JSONObject result = new JSONObject();
                result.put("role", role);
                result.put("permissions", permissionsFor(role));
                result.put("loginAt", System.currentTimeMillis());
                return result;
            }
        }
        return null;
    }

    public void logout() {
        currentRole = null;
    }

    public String getCurrentRole() {
        return currentRole;
    }

    public boolean changePassword(String role, String password) {
        if (!ROLE_SYSTEM_ADMIN.equals(currentRole)) {
            return false;
        }
        if (role == null || password == null || !password.matches("\\d{6}")) {
            return false;
        }
        if (!ROLE_SYSTEM_ADMIN.equals(role) && !ROLE_OPS.equals(role) && !ROLE_DEVELOPER.equals(role)) {
            return false;
        }
        preferences.edit().putString(role, sha256(password)).apply();
        return true;
    }

    private JSONArray permissionsFor(String role) {
        Map<String, String[]> permissions = new LinkedHashMap<>();
        permissions.put(ROLE_SYSTEM_ADMIN, new String[]{
                "system.menu", "management.menu", "cabinet.view", "cabinet.unlock", "cabinet.unlockAll",
                "employee.view", "employee.edit", "biometric.register", "history.view", "unit.view",
                "settings.basic", "settings.advanced", "engine.activate", "authorization.manage",
                "upgrade.app", "upgrade.firmware", "debug.command", "auth.password.manage", "app.restart"
        });
        permissions.put(ROLE_OPS, new String[]{
                "system.menu", "management.menu", "cabinet.view", "cabinet.unlock", "cabinet.unlockAll",
                "history.view", "unit.view", "settings.basic", "upgrade.firmware", "app.restart"
        });
        permissions.put(ROLE_DEVELOPER, new String[]{
                "system.menu", "cabinet.view", "cabinet.unlock", "settings.basic", "settings.advanced",
                "engine.activate", "authorization.manage", "upgrade.app", "upgrade.firmware",
                "debug.command", "app.restart"
        });
        JSONArray array = new JSONArray();
        String[] list = permissions.get(role);
        if (list != null) {
            for (String permission : list) array.put(permission);
        }
        return array;
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
