package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class DocumentedBackendServiceTest {
    @Test
    public void employeeAddUsesExactDocumentedPathAndFields() throws Exception {
        FakeTransport transport = new FakeTransport();
        DocumentedBackendService service = new DocumentedBackendService(temp("files"), temp("cache"), transport);
        service.upsertEmployee(new JSONObject().put("action", "add")
                .put("employeeCode", "EMP001").put("employeeName", "张三")
                .put("cardNo", "CARD001"));
        assertEquals(BackendHttpGateway.EMPLOYEE_UPSERT, transport.path);
        assertEquals("add", transport.body.getString("action"));
        assertEquals("EMP001", transport.body.getString("employeeCode"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void employeeUpdateRequiresNumericEmployeeId() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport());
        service.upsertEmployee(new JSONObject().put("action", "update")
                .put("employeeId", "not-a-number"));
    }

    @Test
    public void faceFeatureUsesExactDocumentedPath() throws Exception {
        FakeTransport transport = new FakeTransport();
        DocumentedBackendService service = new DocumentedBackendService(temp("files"), temp("cache"), transport);
        service.upsertFaceFeature(new JSONObject().put("employeeId", 1)
                .put("faceFeature", "feature"));
        assertEquals(BackendHttpGateway.EMPLOYEE_FACE_UPSERT, transport.path);
        assertEquals(1L, transport.body.getLong("employeeId"));
    }


    @Test(expected = IllegalArgumentException.class)
    public void invalidEmployeeStatusIsRejected() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport());
        service.upsertEmployee(new JSONObject().put("action", "add")
                .put("employeeCode", "EMP001").put("employeeName", "张三")
                .put("status", "ACTIVE"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidCardAuthTypeIsRejected() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport());
        service.reportTake("CARD001", 1, "ADMIN");
    }

    private static final class FakeTransport implements DocumentedBackendService.Transport {
        String path;
        JSONObject body;
        @Override public JSONObject postData(String path, JSONObject body) {
            this.path = path; this.body = body; return new JSONObject();
        }
        @Override public JSONArray fetchArray(String path) { this.path = path; return new JSONArray(); }
        @Override public JSONObject uploadFaceImage(String userId, File file, String faceFeature) {
            return new JSONObject();
        }
        @Override public JSONObject downloadFirmware(String firmwareId, File target, long offset) {
            return new JSONObject();
        }
    }

    private static File temp(String name) {
        File value = new File(System.getProperty("java.io.tmpdir"),
                "card-prod-test-" + name);
        value.mkdirs();
        return value;
    }
}
