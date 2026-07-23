---
name: backend-contract-mqtt-http
description: Implement, debug, or review V4.1 backend contracts for the work-card cabinet, including HTTP provisioning and sync, BackendTransportManager MQTT/HTTP/TCP sessions, signed uplink envelopes, documented downlink commands, response msgId correlation, idempotency, reconnect behavior, multipart upload and firmware download. Trigger for BackendHttpClient, BackendHttpGateway, DeviceProvisioningManager, BackendTransportManager, DocumentedBackendService or backend contract work.
---

# Backend Contract: MQTT + HTTP

## Authoritative source

Use `docs/source-2026-07-02/Android客户端接口文档.md` V4.1. Older Markdown, PDFs and `reference/motone-current` do not override the backend contract.

Every field, method, path, enum, error code and response must have a V4.1 citation or an explicit user decision. Unknown behavior stays disabled or recorded in `docs/CONTRACT_EVIDENCE_REGISTER.md`.

## Layer ownership

Android business layer:

- `DeviceProvisioningManager`: version, registration, activation, verification, config and authorization sequence;
- `DeviceDataSyncManager`: paginated employee/face/finger sync and local apply;
- `DocumentedBackendService`: exact request validation for documented endpoints;
- `DeviceCommandCoordinator`: ten documented downlink commands, idempotency and responses.

Communication layer:

- `BackendTransportManager`: MQTT/HTTP runtime login, heartbeat, subscribe, envelope, send/receive;
- `BackendHttpGateway` / `BackendHttpClient`: HTTP JSON, multipart and download only.

Communication classes must not read `NativeSettingsRepository`, run provisioning, or update business repositories.

## Channel ownership

Use HTTP for:

- app version check;
- registration, activation and verification;
- configuration and authorization query;
- HTTP login and heartbeat;
- employee/face/fingerprint synchronization;
- employee and face-feature update;
- face image and fingerprint upload;
- firmware download and batch logs;
- documented HTTP equivalents of device-originated events.

Use MQTT for:

- real-time connect/subscribe/business login;
- heartbeat;
- the ten documented downlink commands and responses;
- live device-originated events.

HTTP mode has no documented downlink channel. Do not invent polling, SSE or WebSocket commands.

## Connection states

Keep transport and business authentication distinct:

```text
DISCONNECTED
CONNECTING / TRANSPORT_CONNECTED
SUBSCRIBED
LOGIN_SENT
AUTHENTICATED
ERROR / PENDING_CREDENTIALS
STOPPED
```

- TCP/MQTT connect is not business authentication.
- Only an explicit login response with `code=0` enters `AUTHENTICATED`.
- Reconnect clears old connecting/authenticated state, resubscribes and relogins.
- One heartbeat scheduler per active session.
- HTTP/MQTT login token may be saved, but V4.1 does not define it as Bearer; all HTTP Bearer remains `deviceToken`.

## MQTT envelope

Device-originated uplink:

```json
{
  "msgId": "...",
  "cmd": "...",
  "timestamp": 0,
  "deviceCode": "...",
  "sign": "...",
  "data": {}
}
```

Server downlink:

```json
{
  "msgId": "server-generated",
  "cmd": "remoteOpen",
  "timestamp": 0,
  "data": {}
}
```

V4.1 explicitly says downlink has no `sign` and does not define `deviceCode` there. Do not reject valid downlink for missing those fields.

Response must reuse the original server `msgId`; it must not generate a new response ID.

Signature construction and data serialization must follow V4.1 exactly. Do not introduce alternative canonicalization or compatibility signatures without backend evidence.

## Downlink commands

The only allowed commands are:

```text
remoteOpen
remoteEjectAll
restartApp
syncUser
syncConfig
firmwareUpgrade
cancelUpgrade
deviceSelfCheck
enableLogUpload
disableLogUpload
```

Log-upload toggles have no terminal response. Unknown commands have no side effect and use only documented generic failure semantics.

V4.1 lists `timestamp` but does not define a tolerance window. Do not invent a 10-minute window or similar rejection policy.

## Idempotency

- Persist `msgId` before any remote side effect.
- Store processing state and terminal serialized response.
- Duplicate terminal delivery reuses the stored response and original `msgId`.
- Never automatically replay an uncertain side effect after process death.
- V4.1 does not define PROCESSING recovery time; do not invent one.
- Prune only terminal records; do not delete active records to satisfy a size cap.

## HTTP rules

- Registration and app-version check are anonymous.
- All other documented HTTP requests use `Authorization: Bearer deviceToken`.
- Require an explicit HTTP/HTTPS base URL; never inject a test server or guess scheme.
- Validate HTTP status, JSON syntax and backend business code separately.
- V4.1 contains success examples with both `code=200` and `code=0`; accept both and keep the conflict recorded.
- Multipart face upload requires `userId`, real file and optional `faceFeature`.
- Firmware download supports Range; download to app-private storage. Download completion is not OTA installation completion.

## Sync semantics

Use exact documented keys:

```text
employeeId
faceId
fingerId
```

Missing primary keys are invalid; never substitute `employeeCode`, generic `id` or generated keys.

Incremental results merge by primary key. Employee deletion uses `deletedEmployeeIds`; face/finger item `status=1` disables that specific record.

For face data keep:

```text
fetchedVersion
appliedVersion
```

Do not advance applied version when FaceAISDK template application fails.

`syncUser` does not contain undocumented `full/fullSync` controls. Internal full-sync decisions must not be accepted from arbitrary downlink fields.

## Documented endpoint entrypoints

`DocumentedBackendService` may expose interfaces that lack a current producer, but must not manufacture data:

- card take/return wait for physical confirmation;
- fingerprint upload waits for external hardware feature data;
- logs batch waits for a real outbox/caller;
- face multipart waits for a real app-private file;
- firmware download does not install firmware.

## Security

- Never log credentials, signing keys, tokens, full envelopes with secrets, face images/features or fingerprint features.
- HTTP and MQTT hosts are independent local deployment configuration.
- TLS/cleartext choice must be explicit; no guessed default.
- Remote fields cannot overwrite independent endpoints unless V4.1 formally defines them.

## Required tests

- registration→activation→config→authorization;
- HTTP and MQTT login state;
- reconnect without duplicate heartbeat;
- uplink signature known vector;
- downlink missing sign/deviceCode accepted when otherwise valid;
- exact ten-command handler set;
- response reuses original msgId;
- duplicate before/after restart;
- HTTP timeout, non-JSON, 4xx/5xx and business error;
- employee/face/finger pagination, deletion and cursor separation;
- multipart private-file restriction;
- firmware Range resume behavior.

Finish with `$device-release-gate`.
