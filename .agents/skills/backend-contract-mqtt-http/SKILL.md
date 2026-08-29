---
name: backend-contract-mqtt-http
description: Implement, debug, or review current V4.2 backend contracts for the work-card cabinet V2 flow, including Vue HTTP/MQTT business payloads, Android bootstrap/transport abilities, MQTT status/message delivery, response msgId correlation, idempotency, reconnect behavior, multipart upload and firmware download. Trigger for backend protocol, config, MQTT, HTTP, sync, or transport work.
---

# Backend Contract: MQTT + HTTP

## Current edit authorization

For the current project phase, implement Vue-side request construction, response parsing, SQLite delivery state and calls to transport actions. The user additionally authorized generic multipart support only in `JsBridgeV2.java` and `HttpClientManager.java`; backend and formal Mock remain read-only.

- Do not modify Android outside the two authorized files, and do not alter MQTT, token ownership or backend business semantics.
- Do not modify the formal Mock or create a replacement service in `uniapp/src/mock/**` or `uniapp/scripts/**`.
- If the existing Android transport cannot carry a documented request, record the exact transport capability gap and leave that path incomplete.

## Authoritative source

Use `docs/source-2026-07-23/Android客户端接口文档.md` V4.2 and `docs/config-接入指南.md`. Older Markdown, PDFs and `reference/motone-current` do not override the current backend contract.

Every field, method, path, enum, error code and response must have a V4.2/config citation or an explicit user decision. Unknown behavior stays disabled and is reported as a concrete blocker in the current audit, plan or PR, following `docs/CURRENT_PROJECT_BASELINE.md`.

## Layer ownership

V2 ownership:

- Vue: business HTTP request bodies, response parsing, employee/face/finger sync application, SQLite cache, outbox, downlink business handling and idempotency projection;
- Android bootstrap: version, registration, activation, verification, config fetch, login and heartbeat startup abilities;
- Android HTTP ability: generic GET/POST/download transport for Vue-built requests;
- Android MQTT ability: connection, subscribe, publish, heartbeat, reconnect, login status and downlink event delivery;
- Backend: final online business data and remote ACK truth.

Android communication layer (read-only in the current phase):

- MQTT manager/client: runtime login, heartbeat, subscribe, envelope, send/receive;
- HTTP client/manager: HTTP JSON, multipart and download only;
- bridge: surface status and messages to Vue without interpreting business tables.

Communication classes must not update Android business repositories, write Vue SQLite business tables directly, or decide employee/card operation success.

## Channel ownership

Use HTTP through Vue business services or Android bootstrap for:

- app version check;
- registration, activation and verification;
- configuration and authorization query;
- HTTP login and heartbeat;
- employee/face/fingerprint synchronization;
- employee and face-feature update;
- face image and fingerprint upload;
- firmware download and batch logs;
- documented HTTP equivalents of device-originated events.

Use MQTT through Android transport and Vue business handlers for:

- real-time connect/subscribe/business login;
- heartbeat;
- the ten documented downlink commands and responses;
- live device-originated events.

HTTP mode has no downlink channel unless the current V4.2/config source defines one. Do not invent polling, SSE or WebSocket commands.

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
- HTTP/MQTT tokens may be saved only according to the current contract; do not convert unrelated tokens into Bearer credentials without evidence.

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

For uplink/downlink envelope fields, follow the current V4.2 source exactly. If V4.2 says downlink has no `sign` or `deviceCode`, do not reject a valid downlink for missing those fields.

Response must reuse the original server `msgId`; it must not generate a new response ID.

Signature construction and data serialization must follow the current contract exactly. Do not introduce alternative canonicalization or compatibility signatures without backend evidence.

## Downlink commands

Only handle commands listed by the current V4.2 source or explicitly confirmed by the user/backend. Unknown commands have no side effect and use only documented generic failure semantics.

Known command set may include:

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

Log-upload toggles have no terminal response unless current docs say otherwise.

If the current contract lists `timestamp` but does not define a tolerance window, do not invent a rejection window.

## Idempotency

- Persist `msgId` before any remote side effect.
- Store processing state and terminal serialized response.
- Duplicate terminal delivery reuses the stored response and original `msgId`.
- Never automatically replay an uncertain side effect after process death.
- If the current contract does not define PROCESSING recovery time, do not invent one.
- Prune only terminal records; do not delete active records to satisfy a size cap.

In V2, durable idempotency and outbox state belongs in Vue SQLite unless the specific native capability requires native persistence.

## HTTP rules

- Registration and app-version check are anonymous.
- Protected HTTP requests use the authorization rule defined by current V4.2/config docs.
- Require an explicit HTTP/HTTPS base URL; never inject a test server or guess scheme.
- Validate HTTP status, JSON syntax and backend business code separately.
- If current examples conflict between `code=200` and `code=0`, record the conflict and implement only an explicitly accepted compatibility rule.
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

Incremental results merge by primary key into Vue SQLite. Deletion and disabled semantics must follow V4.2.

For face data keep:

```text
fetchedVersion
appliedVersion
```

Do not advance applied version when FaceAISDK template application fails. Do not store raw face or fingerprint features. Per the user's explicit decision, the bounded face photo is stored only in application-private Vue SQLite and never logged or copied to public storage.

`syncUser` does not contain undocumented `full/fullSync` controls. Internal full-sync decisions must not be accepted from arbitrary downlink fields.

## Documented endpoint entrypoints

Endpoint constants or wrappers may exist without a current producer. They must not manufacture data:

- card take/return wait for physical confirmation;
- fingerprint upload waits for external hardware feature data;
- logs batch waits for a real Vue outbox/caller;
- face multipart waits for a real app-private file;
- firmware download does not install firmware.

## Security

- Never log credentials, signing keys, tokens, full envelopes with secrets, face images/features or fingerprint features.
- HTTP and MQTT hosts are independent local deployment configuration.
- TLS/cleartext choice must be explicit; no guessed default.
- Remote fields cannot overwrite independent endpoints unless current docs formally define them.

## Required tests

For Vue-only work, test the client-observable request, response, persistence, duplicate and failure behavior with existing transport boundaries stubbed. Backend/Mock internals and Android transport implementation tests belong to their owners and must be reported as not performed by this task.

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
