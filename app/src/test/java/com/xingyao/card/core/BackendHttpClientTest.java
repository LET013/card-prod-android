package com.xingyao.card.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackendHttpClientTest {
    @Test(expected = IllegalArgumentException.class)
    public void blankBaseUrlIsRejected() {
        new BackendHttpClient("", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemeIsNeverGuessed() {
        new BackendHttpClient("api.example.com", "");
    }

    @Test
    public void explicitHttpUrlIsNormalizedWithoutTestFallback() {
        assertEquals("https://api.example.com/base",
                BackendHttpClient.normalizeBaseUrl("https://api.example.com/base/"));
    }
}
