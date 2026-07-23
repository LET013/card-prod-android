---
name: device-operation-state-machine
description: Design, implement, or review long-running device operations such as open door, take card, return card, remote open, face open, batch eject, and upgrade. Trigger when an action spans validation, serial queue, board ACK, physical confirmation, reporting, timeout, cancellation, retry, or process restart.
---

# Device Operation State Machine

## Purpose

Use one operation model for UI, MQTT, face recognition and administrator actions. The source changes authorization and metadata, not the underlying physical workflow.

## Canonical stages

```text
RECEIVED
VALIDATED
QUEUED
SERIAL_SENT
BOARD_ACKED
PHYSICAL_PENDING
PHYSICAL_CONFIRMED
REPORT_PENDING
COMPLETED
FAILED
TIMED_OUT
CANCELLED
```

Not every operation needs every stage, but skipping a stage must be intentional and documented.

## Required operation fields

```text
operationId
operationType
source
requestId
requestMsgId
employeeId
slotId
boardAddress
parentOperationId
stage
createdAt
updatedAt
deadlineAt
errorCode
errorMessage
result
```

Keep sensitive fields out of `result` and diagnostics.

## Transition rules

- Generate `operationId` before the first side effect.
- Validate authorization, parameters, slot mapping, current state and idempotency before `QUEUED`.
- Only the serial scheduler moves the operation to `SERIAL_SENT`.
- A matched successful board response moves to `BOARD_ACKED`.
- `BOARD_ACKED` is not a terminal TAKE/RETURN success.
- Physical confirmation comes from an authoritative slot transition within a bounded window.
- Backend publishing is represented by `REPORT_PENDING`; completion requires the contractually required ACK or durable Outbox handoff.
- Terminal stages are immutable except for appended reporting metadata.
- Every transition is persisted atomically with relevant Repository changes.

## Take-card model

A safe baseline is:

```text
known valid card present
→ open request
→ BOARD_ACKED
→ PHYSICAL_PENDING
→ observed card absent
→ PHYSICAL_CONFIRMED
→ report TAKE
```

If the user does not remove the card before the deadline, mark `TIMED_OUT` or a contract-specific incomplete result. Do not report confirmed TAKE.

## Return-card model

A safe baseline is:

```text
known empty/assigned return slot
→ open request
→ BOARD_ACKED
→ PHYSICAL_PENDING
→ observed valid card present/card identity accepted
→ PHYSICAL_CONFIRMED
→ report RETURN
```

Illegal card, wrong card, door timeout and communication loss are distinct failures.

## Batch operations

Represent batch eject as:

- one parent operation;
- one child operation per mapped slot;
- sequential serial execution;
- bounded cancellation;
- aggregate counts derived from child terminal states;
- failures retained per child, not flattened into one message.

The parent cannot report full success when any required child failed.

## Restart recovery

For non-terminal operations on startup:

- recover persisted stage and deadline;
- never blindly resend a side effect;
- query current hardware state where possible;
- use idempotency and physical evidence to classify completed, pending, failed or unknown;
- emit an explicit recovery diagnostic;
- require operator intervention when state cannot be safely inferred.

## Concurrency

Define slot-level exclusion:

- only one physical operation per slot at a time;
- batch parent reserves child slots as they enter the queue;
- duplicate remote request attaches to the existing operation;
- unrelated read-only queries may proceed only if the serial scheduler can safely serialize them.

## Error taxonomy

Use stable codes such as:

```text
AUTH_REQUIRED
PERMISSION_DENIED
INVALID_SLOT
SLOT_UNMAPPED
SLOT_UNAVAILABLE
SERIAL_DISCONNECTED
SERIAL_TIMEOUT
BOARD_REJECTED
PHYSICAL_TIMEOUT
ILLEGAL_CARD
REPORT_FAILED
CANCELLED_BY_OPERATOR
RECOVERY_UNCERTAIN
```

Localized text is supplementary, not the identifier.

## Required tests

- happy path stage order;
- board rejection;
- serial timeout;
- physical timeout after board ACK;
- duplicate request reuses operation;
- conflicting operation on same slot;
- restart at each non-terminal stage;
- parent/child batch aggregation;
- cancellation before send and during physical wait;
- reporting failure with durable retry;
- terminal state immutability.

## Completion output

Provide a transition table, persistence boundary, timeout values, recovery rules, event/report timing and tests. Finish with `$device-release-gate`.
