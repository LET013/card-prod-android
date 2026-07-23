package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JsonCanonicalizerTest {
    @Test
    public void fieldOrderAndTransportMetadataDoNotChangeCommandIdentity() throws Exception {
        JSONObject first = new JSONObject()
                .put("msgId", "MSG-1")
                .put("cmd", "remoteOpen")
                .put("timestamp", 1000L)
                .put("data", new JSONObject().put("slotId", 12).put("authType", "REMOTE"))
                .put("_source", "mqtt:topic-a");
        JSONObject second = new JSONObject()
                .put("_source", "mqtt:topic-b")
                .put("data", new JSONObject().put("authType", "REMOTE").put("slotId", 12))
                .put("timestamp", 1000L)
                .put("cmd", "remoteOpen")
                .put("msgId", "MSG-1");

        assertEquals(JsonCanonicalizer.canonicalize(first),
                JsonCanonicalizer.canonicalize(second));
    }
}
