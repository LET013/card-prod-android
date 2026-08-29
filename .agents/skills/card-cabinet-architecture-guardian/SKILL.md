---
name: card-cabinet-architecture-guardian
description: Analyze and plan cross-module changes in the work-card cabinet Android/uni-app V2 project. Trigger for architecture, refactors, startup, Vue SQLite, JsBridgeV2, MQTT, serial, face, sync, and UI boundary work. Do not use as the only skill for narrow protocol implementation; route to a specialist after the architecture pass.
---

# Card Cabinet Architecture Guardian

Use this skill before changing behavior across more than one major layer.

## Current implementation boundary

The architecture spans Vue, Android and backend capabilities. The current writable product layer is the Vue H5 / uni-app business client plus the two narrowly authorized generic face-photo capability files described in `docs/CURRENT_PROJECT_BASELINE.md`. Architecture analysis must not be converted into backend, formal-Mock, serial or other native-face edits.

- Route all approved implementation files through `$vue-business-client` after this architecture pass.
- Plans may inspect `app/**` to confirm an existing action and response shape. Only `JsBridgeV2.java` and `HttpClientManager.java` may be changed for the explicitly authorized enrollment-photo result, template import and multipart transport capabilities; every other `app/**` file stays in the deliberately-not-modified list.
- `uniapp/src/services/nativeBridge.js` may consume existing actions; it cannot create an Android capability by contract alone.
- Missing native/backend data is an external blocker with an owner, not a reason to move the feature into Vue or modify the dependency.

## Required reading

- `AGENTS.md`
- `docs/CURRENT_PROJECT_BASELINE.md` as the unique scope and document entrypoint
- `docs/source-2026-07-23/Android客户端接口文档.md` and `docs/config-接入指南.md` for endpoint/configuration work
- `docs/VUE_JSBRIDGE_V2_GUIDE.md` for Vue/Android capability boundaries
- `docs/VUE_SQLITE_SCHEMA.md` for Vue-managed local cache work
- Existing implementation around every proposed entry point and state owner

## Required analysis before code

Produce these six items:

1. **Entry points**: UI action, MQTT command, startup event, face callback, serial callback, or timer.
2. **Single source of truth**: exact Vue state, Vue SQLite table, Android native manager, or backend contract that owns each value.
3. **Call chain**: entry → validation → Vue service/localStore or Android native ability → response/event → Vue projection/backend report.
4. **State transitions**: happy path, failure, timeout, cancel, retry, duplicate, restart, and offline path.
5. **Compatibility surface**: existing JSBridge action, MQTT command, HTTP endpoint, serial frame, persisted data, and UI field affected.
6. **File-level plan**: files to modify, files deliberately not modified, tests and docs to update.

Do not start implementation until this analysis is internally consistent.

## Architecture decisions

Apply these rules:

- Vue owns user-visible business flow, business HTTP/MQTT payloads, response parsing, SQLite schema, cache, permissions and outbox.
- Android owns native abilities only: WebView, bootstrap, HTTP transport, MQTT session/heartbeat, serial, SQLite execution and FaceAISDK/CameraX.
- Ownership describes runtime responsibility, not current edit permission. Android and backend remain read-only under the current baseline.
- `DeviceCoreService` owns foreground-service lifecycle and dependency wiring only. It must not coordinate Vue business workflows.
- `JsBridgeV2` exposes capability channels only. Do not add business verbs such as employee open-door or business sync.
- Android Map repositories are not V2 business truth. Do not reconnect old `DeviceDataLayer` / Store / Repository as the main flow.
- HTTP and MQTT endpoint configuration stay independent; never collapse them into one server field.
- UI, face result, MQTT downlink and administrator actions must converge in Vue before any business side effect is decided.
- Every side-effecting operation has Vue-managed `operationId`; every remote command preserves backend `msgId` for idempotency.
- Transport ACK, board ACK, physical confirmation, durable outbox handoff, and backend ACK are distinct states.
- Offline capability must be explicit: allowed, denied, or degraded. It must come from Vue SQLite cache and policy, not a hidden Android Map.

## Coupling test

Before approving the plan, answer:

- Can the WebView be recreated and recover needed display/business state from Vue SQLite or Android native status APIs?
- Can HTTP and MQTT point to different hosts without one overwriting the other?
- Can MQTT reconnect without Vue repeating a completed side effect?
- Can the process restart and explain whether an operation completed?
- Can a serial timeout be linked back to the initiating request?
- Can the backend distinguish board ACK from real take/return confirmation?
- Can one module be tested without starting every other module?
- Does the plan avoid creating a second business cache in Android?

Any “no” must be addressed or recorded as scoped technical debt.

## Contract evidence gate

For every external field, endpoint, enum, timing rule, address mapping, authentication rule and
wire payload, cite an original repository document or an explicit user decision. If no source exists,
keep it disabled/blank and report the concrete blocker in the current audit, plan or PR. Never implement a
"likely" backend or hardware behavior.

## Stop conditions

Stop and report a blocker instead of guessing when:

- serial topology, group selection, or a function code is undocumented;
- backend field, MQTT username, signature, ACK, deletion, or cursor semantics are ambiguous;
- HTTP-only mode is expected to receive downlink commands without a documented downlink endpoint;
- a requested feature depends on identifying an employee from Android system fingerprint;
- a change would require maintaining two competing sources of truth between Vue SQLite and Android Map;
- implementation would silently preserve legacy behavior that conflicts with physical reality.
- implementation would require changing Android files outside the two explicitly authorized capability files, backend/formal-Mock assets, serial modules or other native face modules under the current baseline.

## Implementation discipline

- Prefer one explicit rule over a new subsystem when behavior is local and stateless.
- Use a small state machine when behavior spans time, retries, restart, or multiple acknowledgements.
- Do not combine unrelated architecture migrations in one batch.
- Preserve current UI contracts unless the task explicitly includes coordinated UI migration.
- Add migration/default behavior for persisted data changes.
- Configuration fields without an implemented consumer stay blank or disabled; do not invent working defaults.

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
