package com.xingyao.card.core;

import com.xingyao.card.core.entity.http.RegisterRequest;
import com.xingyao.card.core.entity.http.VersionCheckResponse;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppVersionContractTest {
    @Test
    public void versionResponseKeepsEveryV42Field() throws Exception {
        VersionCheckResponse response = VersionCheckResponse.fromJson(new JSONObject()
                .put("hasUpdate", true)
                .put("forceUpdate", true)
                .put("versionId", 12L)
                .put("versionName", "2.1.0")
                .put("versionCode", 210)
                .put("apkUrl", "https://example.com/app.apk")
                .put("apkSize", 1024L)
                .put("apkMd5", "0123456789abcdef0123456789abcdef")
                .put("releaseNotes", "修复问题"));

        assertTrue(response.hasUpdate);
        assertTrue(response.forceUpdate);
        assertEquals(12L, response.versionId);
        assertEquals(210, response.versionCode);
        assertEquals(1024L, response.apkSize);
        assertEquals("修复问题", response.toJson().getString("releaseNotes"));
    }

    @Test
    public void versionResponsePreservesBackendApkFilePathForTheVueUpdateFlow() throws Exception {
        VersionCheckResponse response = VersionCheckResponse.fromJson(new JSONObject()
                .put("hasUpdate", true)
                .put("versionCode", 102)
                .put("apkFilePath", "app-version/apk_affc281e_card-cabinet-v1.0.2-debug.apk"));

        assertEquals("app-version/apk_affc281e_card-cabinet-v1.0.2-debug.apk", response.apkFilePath);
        assertEquals(response.apkFilePath, response.toJson().getString("apkFilePath"));
    }

    @Test
    public void nullDataCanRepresentNoUpdate() {
        VersionCheckResponse response = VersionCheckResponse.noUpdate();
        assertFalse(response.hasUpdate);
        assertEquals(0, response.versionCode);
    }

    @Test
    public void registrationChannelIsAString() throws Exception {
        RegisterRequest request = new RegisterRequest.Builder()
                .channelId("test")
                .build();
        assertEquals("test", request.toJson().getString("channelId"));
    }
}
