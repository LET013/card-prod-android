package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * APP 版本检测响应: POST /api/v1/app-version/check 的 data 字段
 */
public class VersionCheckResponse {
    public final boolean hasUpdate;
    public final boolean forceUpdate;
    public final long versionId;
    public final int versionCode;
    public final String versionName;
    public final String apkUrl;
    public final String apkFilePath;
    public final long apkSize;
    public final String apkMd5;
    public final String releaseNotes;

    private VersionCheckResponse(JSONObject data) {
        this.hasUpdate = data.optBoolean("hasUpdate", false);
        this.forceUpdate = data.optBoolean("forceUpdate", false);
        this.versionId = data.optLong("versionId", 0L);
        this.versionCode = data.optInt("versionCode", 0);
        this.versionName = data.optString("versionName", "");
        this.apkUrl = data.optString("apkUrl", "");
        this.apkFilePath = data.optString("apkFilePath", "");
        this.apkSize = data.optLong("apkSize", 0L);
        this.apkMd5 = data.optString("apkMd5", "");
        this.releaseNotes = data.optString("releaseNotes", "");
    }

    public static VersionCheckResponse fromJson(JSONObject data) {
        return new VersionCheckResponse(data == null ? new JSONObject() : data);
    }

    public static VersionCheckResponse noUpdate() {
        return new VersionCheckResponse(new JSONObject());
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("hasUpdate", hasUpdate)
                .put("forceUpdate", forceUpdate)
                .put("versionId", versionId)
                .put("versionName", versionName)
                .put("versionCode", versionCode)
                .put("apkUrl", apkUrl)
                .put("apkFilePath", apkFilePath)
                .put("apkSize", apkSize)
                .put("apkMd5", apkMd5)
                .put("releaseNotes", releaseNotes);
    }
}
