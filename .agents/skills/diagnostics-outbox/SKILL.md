---
name: diagnostics-outbox
description: Design, implement, or review reliable device diagnostics and event delivery for the work-card cabinet, including local persistence, Outbox, retry, backend ACK, crash/restart recovery, deduplication, rate limiting, privacy redaction, self-check, and fault reporting. Trigger for logging, error reporting, telemetry, hardware faults, or offline补传 work.
---

# Diagnostics Outbox

## Goal

Make important device failures explainable and deliverable without coupling business execution to current network availability. In V2, durable diagnostics and business outbox state are Vue SQLite responsibilities unless a native subsystem needs its own private crash-safe record.

The guarantee is:

```text
critical event created
→ durable local transaction
→ asynchronous delivery attempt
→ retry with backoff
→ backend ACK
→ local acknowledgement/pruning
```

Do not claim absolute delivery across sudden power loss before local persistence. Minimize that window by persisting before reporting success to the caller when the event is critical.

## Event schema

Use a stable structure:

```json
{
  "eventId": "...",
  "level": "ERROR",
  "category": "SERIAL",
  "code": "SERIAL_TIMEOUT",
  "message": "...",
  "deviceCode": "...",
  "operationId": "...",
  "msgId": "...",
  "slotId": 0,
  "stage": "SERIAL_SENT",
  "durationMs": 0,
  "context": {},
  "timestamp": 0
}
```

Recommended categories:

```text
APP
STARTUP
HTTP
MQTT
SERIAL
SLOT
FACE
FINGERPRINT
SYNC
SECURITY
STORAGE
UPGRADE
BUSINESS
```

## Persistence model

Prefer Vue SQLite tables for:

- diagnostic event;
- delivery state;
- attempt count and next attempt time;
- last error;
- backend ACK time;
- aggregation key/count;
- retention deadline.

Suggested delivery states:

```text
PENDING
SENDING
ACKED
RETRY_WAIT
DEAD_LETTER
```

Use atomic compare/update so multiple workers cannot send the same record concurrently. Reset stale `SENDING` records after process death.

## Delivery rules

- MQTT is the preferred live path when authenticated.
- HTTP batch is the recovery and bulk path.
- Both paths use the same `eventId`; backend deduplicates by `eventId`.
- ACK must identify accepted events, rejected events and retryable failures.
- Delete only ACKed records after the configured retention/audit period.
- Use exponential backoff with jitter and a maximum interval.
- Bound batch size, queue size, disk usage and individual context size.
- Hardware/security/recognition failures may bypass a normal debug-log toggle when contractually required.

## Severity and noise control

- `DEBUG`: local-only unless a bounded remote debug session is active.
- `INFO`: lifecycle and operator actions, sampled or configured.
- `WARN`: degraded behavior or recoverable anomalies.
- `ERROR`: failed operation or subsystem failure.
- `FATAL`: process integrity, database corruption or unrecoverable startup failure.

Aggregate repeated identical faults by stable key such as:

```text
category + code + slotId + normalized context
```

Keep first/last occurrence and count. Do not emit one backend event per failed poll indefinitely.

## Privacy and security

Never persist or upload:

- raw face image/frame;
- face feature/template bytes;
- fingerprint data;
- passwords, activation secrets, tokens, signing keys or MQTT password;
- full authorization headers;
- identity documents;
- arbitrary full request/response bodies.

Redact URLs with credentials/query secrets, hash identifiers when raw value is unnecessary, truncate RX/TX data to protocol-relevant bounded samples and allow-list context fields.

## Required producers

At minimum instrument:

- startup stage failures;
- provisioning/configuration failures;
- MQTT connect, subscribe, login, heartbeat and command validation failures;
- command duplicate/rejection and operation terminal failure;
- serial open, TX, parser, CRC, timeout and board rejection;
- stale/unmapped/faulted slots;
- employee/face/fingerprint fetch, merge, delete and apply failures;
- ArcFace activation/import/extraction/compare failure category;
- security denials and bridge origin violations;
- storage migration, queue overflow and disk failure;
- app/firmware upgrade stages.

## Self-check snapshot

A self-check should report sanitized status, not raw logs:

```text
app/build version
provisioning/auth state
MQTT state and last successful heartbeat
serial port/state/last valid frame age
known/stale/fault/unmapped slot counts
face engine/template/import job counts
sync cursors and last success/failure
outbox pending/retry/dead-letter counts
storage usage and clock skew
```

## Required tests

- event transaction survives process recreation;
- offline enqueue then online batch delivery;
- MQTT failure falls back to HTTP without duplicate business effect;
- ACK deletes/marks only accepted event IDs;
- retryable and permanent rejection handling;
- stale `SENDING` recovery;
- exponential backoff bounds;
- queue/disk limit and priority preservation;
- repeated fault aggregation;
- sensitive-field redaction;
- malformed/oversized context rejection;
- Vue SQLite migration and corruption behavior.

## Completion output

Document schema, ACK contract, retry schedule, retention, queue limits, redaction allow-list, producer coverage and operational UI/debug access. Finish with `$device-release-gate`.
