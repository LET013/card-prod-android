package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/** Deletes ArcFace templates from the same native backing store used by ArcFaceManager. */
public final class ArcFaceTemplateCleaner {
    private static final String PREFS = "arcface_templates";
    private static final String FEATURE_PREFIX = "feature.";
    private static final String METADATA_PREFIX = "metadata.";

    private final SharedPreferences preferences;

    public ArcFaceTemplateCleaner(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean deleteTemplate(String employeeId) {
        String id = employeeId == null ? "" : employeeId.trim();
        if (id.isEmpty()) return false;
        boolean existed = preferences.contains(FEATURE_PREFIX + id)
                || preferences.contains(METADATA_PREFIX + id);
        boolean committed = preferences.edit()
                .remove(FEATURE_PREFIX + id)
                .remove(METADATA_PREFIX + id)
                .commit();
        if (!committed) throw new IllegalStateException("无法删除本机人脸模板：" + id);
        return existed;
    }

    public synchronized int deleteTemplatesNotIn(Set<String> activeEmployeeIds) {
        SharedPreferences.Editor editor = preferences.edit();
        int count = 0;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(FEATURE_PREFIX)) continue;
            String employeeId = key.substring(FEATURE_PREFIX.length());
            if (activeEmployeeIds != null && activeEmployeeIds.contains(employeeId)) continue;
            editor.remove(FEATURE_PREFIX + employeeId)
                    .remove(METADATA_PREFIX + employeeId);
            count++;
        }
        if (count > 0 && !editor.commit()) {
            throw new IllegalStateException("无法提交本机人脸模板全量对账");
        }
        return count;
    }
}
