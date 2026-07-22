---
name: card-cabinet-architecture-guardian
description: Analyze and plan cross-module changes in the work-card cabinet Android/uni-app project. Trigger for architecture, refactors, new device workflows, or tasks touching DeviceCoreService, JSBridge, MQTT, serial, repositories, sync, and UI together. Do not use as the only skill for narrow protocol implementation; route to a specialist after the architecture pass.
---

# Card Cabinet Architecture Guardian

Use this skill before changing behavior across more than one major layer.

## Required reading

- `AGENTS.md`
- `docs/CODEX_PROJECT_GUIDE.md`
- Existing implementation around every proposed entry point and state owner

## Required analysis before code

Produce these six items:

1. **Entry points**: UI action, MQTT command, startup event, face callback, serial callback, or timer.
2. **Single source of truth**: exact Repository or state machine that owns each value.
3. **Call chain**: entry → validation → operation engine → communication → Repository → UI/backend report.
4. **State transitions**: happy path, failure, timeout, cancel, retry, duplicate, restart, and offline path.
5. **Compatibility surface**: existing JSBridge action, MQTT command, HTTP endpoint, serial frame, persisted data, and UI field affected.
6. **File-level plan**: files to modify, files deliberately not modified, tests and docs to update.

Do not start implementation until this analysis is internally consistent.

## Architecture decisions

Apply these rules:

- UI renders state and sends intent. It must not own hardware or backend truth.
- `DeviceCoreService` coordinates; it should not become a monolithic parser, database, or protocol implementation.
- Communication managers send/receive and expose transport state. They do not make business decisions.
- Repositories own durable or authoritative local state.
- UI and remote commands converge before any serial side effect.
- Every side-effecting operation has `operationId`; every remote command has persistent `msgId` idempotency.
- Transport ACK, board ACK, physical confirmation, and backend ACK are distinct states.
- Offline capability must be explicit: allowed, denied, or degraded. Never implicit.

## Coupling test

Before approving the plan, answer:

- Can the UI be recreated without losing device truth?
- Can MQTT reconnect without repeating a completed side effect?
- Can the process restart and explain whether an operation completed?
- Can a serial timeout be linked back to the initiating request?
- Can the backend distinguish board ACK from real take/return confirmation?
- Can one module be tested without starting every other module?

Any “no” must be addressed or recorded as scoped technical debt.

## Stop conditions

Stop and report a blocker instead of guessing when:

- serial topology or a function code is undocumented;
- backend field, signature, ACK, deletion, or cursor semantics are ambiguous;
- a requested feature depends on identifying an employee from Android system fingerprint;
- a change would require maintaining two competing sources of truth;
- implementation would silently preserve legacy behavior that conflicts with physical reality.

## Implementation discipline

- Prefer one explicit rule over a new subsystem when the behavior is local and stateless.
- Use a small state machine when behavior spans time, retries, restart, or multiple acknowledgements.
- Do not combine unrelated architecture migrations in one batch.
- Preserve current UI contracts unless the task explicitly includes coordinated UI migration.
- Add migration/default behavior for persisted data changes.

## Required output

Before code:

```text
Scope
Entry points
Source-of-truth table
Call chain
State transitions
Failure/restart behavior
Compatibility risks
External blockers
File-level plan
Test plan
```

After code:

```text
Implemented behavior
Preserved behavior
Known limitations
Validation evidence
Next safe batch
```

Finish by invoking `$device-release-gate`.
