---
name: workcard-serial-v15
description: Implement, debug, or review the work-card cabinet serial V1.5 protocol, including frame encoding/decoding, CRC, polling, command queueing, address mapping, open-door commands, board status, timeout, split packets, sticky packets, and firmware transfer. Trigger for SerialConnectionManager or WorkCardProtocol changes; do not invent undocumented topology or commands.
---

# Work-card Serial V1.5

## Protocol baseline

Treat the documented V1.5 protocol as authoritative:

- header: `DD CC`;
- two-byte length;
- fixed `F0` marker;
- slave address;
- function code;
- data field;
- CRC16 high byte then low byte;
- CRC range includes bytes from `DD CC` through the last byte before CRC.

Known functions:

- `0x01` query status;
- `0x51` open door;
- `0x52` LED brightness;
- `0x53` read version;
- `0x80` upgrade enable;
- `0x81` upgrade transfer.

Do not add another function code without an external protocol source.

## Non-negotiable execution model

- One serial command scheduler owns all writes.
- At most one response-waiting command is active.
- Polling pauses before a manual/business command and resumes after success, failure, timeout or cancellation.
- Match responses by slave address and function code.
- A timeout is a first-class operation failure and diagnostic event.
- Raw RX must support partial frames, multiple frames in one read and noise before a valid header.
- Invalid length or CRC must not mutate slot state.

## Address topology guardrail

Until hardware provides a confirmed topology:

- do not map `slotId` by modulo;
- do not treat addresses 1–10 as aliases for 1–100;
- do not assume a hidden current group;
- do not assume a group-select command;
- do not update multiple logical slots from one response;
- keep unmapped slots UNKNOWN rather than copying a plausible state.

Any task requiring 100-slot addressing must first produce a topology contract containing:

```text
addressMode: DIRECT | GROUP_CONTEXT | EXPLICIT_MAP
slotId -> boardAddress
slotId -> groupId (when applicable)
group selection command/context
response-to-slot resolution rule
```

## Status semantics

Keep raw protocol fields alongside normalized state:

- `workCode`, `doorCode`, `cardCode`, `faultMask`;
- voltage/current;
- card number only when valid;
- `updatedAt`, `stale`, mapping source and board address.

Never infer TAKE/RETURN solely from open-door ACK. Physical events require a confirmed transition such as:

- take: known card present → open requested → card absent;
- return: empty/expected slot → open requested → valid card present.

Exact transition semantics must match the frozen backend contract.

## Batch open

There is no assumed broadcast open-all command. Implement batch open as a parent operation containing sequential child commands:

- pause polling once;
- send one `0x51` command at a time;
- wait for each ACK/timeout;
- apply command gap;
- preserve per-slot success/failure;
- resume polling in `finally`;
- report aggregate and child results with one parent `operationId`.

## Required tests

Add or update tests for:

1. query/open/version encoding;
2. CRC known vectors and corrupted CRC rejection;
3. incomplete frame buffering;
4. two frames in one RX buffer;
5. leading noise and resynchronization;
6. malformed length bounds;
7. response address/function mismatch;
8. timeout cleanup;
9. polling pause/resume after every exit path;
10. command serialization under concurrent callers;
11. stale slot transition;
12. explicit topology mapping and unmapped slot behavior.

For firmware work also test chunk sequence, retry boundary, checksum, cancellation and power-loss resume policy.

## Required diagnostic context

For failures record, with sensitive fields removed:

```text
operationId
slotId
boardAddress
function
stage
port
baudRate
timeoutMs
txHex
lastRxHex
parserState
```

Do not flood production logs with every successful poll unless a bounded remote debug window is explicitly enabled.

## Completion checks

- No undocumented assumption introduced.
- No slot state mutation from invalid/unmapped frames.
- Polling always recovers.
- Timeout and parser failures link to an operation.
- Unit tests pass.
- Hardware questions are listed explicitly.

Finish with `$device-release-gate`.
