---
name: backend-contract-mqtt-http
description: Implement, debug, or review backend communication contracts for the work-card cabinet, including HTTP provisioning and sync, MQTT connect/subscribe/login/heartbeat, signed envelopes, remote commands, response correlation, idempotency, reconnect behavior, and fallback reporting. Trigger for BackendHttpClient, provisioning, WebSocketConnectionManager, MQTT commands, or backend contract work.
---

# Backend Contract: MQTT + HTTP

## Channel ownership

Use HTTP for:

- app version check;
- device registration, activation and verification;
- configuration retrieval;
- paginated employee/face/fingerprint synchronization;
- image, APK and firmware download;
- batch upload and real-time channel fallback.

Use MQTT for:

- connect, subscribe and business login;
- heartbeat;
- remote commands and command responses;
- live slot status, card events, self-check and hardware faults;
- near-real-time diagnostic events when enabled.

Never put large binary/base64 payloads into MQTT when HTTP download is available.

## Connection state machine

Use explicit states:

```text
DISCONNECTED
CONNECTING
CONNECTED
SUBSCRIBED
LOGIN_SENT
AUTHENTICATED
DEGRADED
STOPPED
```

Rules:

- TCP/MQTT connect is not business authentication.
- Do not start authenticated-only sync or command execution before valid `loginResp`.
- A failed/expired login must clear authenticated state and trigger bounded recovery or reprovisioning.
- Reconnect must resubscribe and relogin without duplicating schedulers or heartbeats.
- Heartbeat success does not replace login state.

## Envelope

All device-originated business messages should be built by one envelope function:

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

Do not hand-build variants across handlers. Signature input ordering, canonicalization, encoding and Base64 behavior must be documented and tested against backend vectors.

## Downlink validation order

Before side effects:

1. parse JSON and enforce size/depth limits;
2. validate `cmd`, `msgId`, timestamp window and device identity;
3. validate signature when the contract requires it;
4. check persistent `msgId` idempotency;
5. validate command parameters;
6. create or recover `operationId`;
7. execute through the shared business entry;
8. persist terminal response before publishing it;
9. reuse the stored response for duplicate delivery.

Unknown commands receive a stable unsupported response and no side effects.

## Idempotency

- Persist enough data to survive process death and reboot.
- Use `msgId` as the remote request key; do not use timestamp alone.
- Store processing state, `operationId`, terminal code and serialized response.
- Define behavior for duplicate while RUNNING: return accepted/in-progress or wait, never start a second operation.
- Apply retention and maximum size; prune only terminal records older than the contract window.

## HTTP reliability

- Use bounded connect/read/write timeouts.
- Retry only operations that are safe or carry idempotency keys.
- Respect HTTP status, backend business code and parse failures separately.
- Do not advance sync/apply version until local application succeeds.
- Download to a temporary file, verify size/hash/signature, then atomically promote.
- Do not silently fall back to test servers or Mock in Release.

## Sync semantics

Before implementing incremental sync, freeze:

- cursor/version meaning;
- full snapshot versus delta;
- upsert key;
- deletion/tombstone representation;
- page consistency and final commit point;
- partial failure/retry behavior.

Incremental results merge by primary key. They never replace the entire local collection unless explicitly marked as a full snapshot and applied atomically.

For face data, separate:

```text
fetchedVersion
locallyAppliedVersion
templateImportJob status
```

Do not mark applied when feature import or image extraction fails.

## Response correlation

Every command response and diagnostic should retain:

```text
msgId
operationId
cmd
source
receivedAt
completedAt
code
stage
```

Preserve the original request ID in downstream serial and business diagnostics.

## Security

- Never log credentials, signing keys, tokens or full biometric payloads.
- Keep clocks and timestamp tolerance explicit; report clock skew distinctly.
- Rotate/replace credentials atomically.
- Clear old credentials after successful reprovisioning.
- Prefer TLS endpoints in production; any cleartext mode must be explicit and environment-scoped.

## Required tests

- connect → subscribe → login → authenticated;
- failed login and credential expiry;
- reconnect/resubscribe/relogin;
- one heartbeat scheduler after repeated reconnects;
- signature known vectors and tamper rejection;
- timestamp/device mismatch rejection;
- duplicate remote open before and after restart;
- duplicate while operation is running;
- terminal response replay;
- HTTP timeout, 4xx, 5xx and invalid JSON;
- incremental merge, deletion, page failure and apply-version separation.

## Completion output

Document exact request/response examples, state transitions, retry rules, idempotency retention and unresolved backend questions. Finish with `$device-release-gate`.
