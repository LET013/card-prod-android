package com.xingyao.card.core.log;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppLogTest {
    @Test
    public void defaultsToLocalOnly() {
        AppLog.setUploadEnabled(false);
        assertFalse(AppLog.isUploadEnabled());
    }

    @Test
    public void redactsSensitiveValuesAndBoundsMessage() {
        String sanitized = AppLog.sanitizeMessage(
                "password=test_value token=test_token sign=test_sign faceFeature=test_feature \"authorization\":\"Bearer test\"");
        assertTrue(sanitized.contains("password=***"));
        assertTrue(sanitized.contains("token=***"));
        assertTrue(sanitized.contains("sign=***"));
        assertTrue(sanitized.contains("faceFeature=***"));
        assertTrue(sanitized.contains("authorization=***"));
    }

    @Test
    public void redactsQuotedJsonValuesWithoutLeavingTheSecret() {
        String sanitized = AppLog.sanitizeMessage("{\"token\":\"secret value\",\"message\":\"ok\"}");
        assertTrue(sanitized.contains("token=***"));
        assertFalse(sanitized.contains("secret value"));
    }

    @Test
    public void redactsSensitiveQueryParametersAndIdentityFields() {
        String sanitized = AppLog.sanitizeMessage(
                "https://example.test/?token=secret&x=1 employeeId=42 cardNo=123456");
        assertTrue(sanitized.contains("token=***"));
        assertTrue(sanitized.contains("employeeId=***"));
        assertTrue(sanitized.contains("cardNo=***"));
        assertFalse(sanitized.contains("secret"));
        assertFalse(sanitized.contains("123456"));
    }
}
