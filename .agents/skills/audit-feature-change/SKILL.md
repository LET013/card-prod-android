---
name: audit-feature-change
description: Mandatory project-wide workflow for every task in the work-card cabinet Android/uni-app V2 repository, including read-only audits, questions, analysis, planning, implementation, bug fixes, refactors, documentation, tests, builds, Git, and delivery. Enforce re-reading every applicable AGENTS.md before each write action, evidence-first scope and flow confirmation, small implementation batches, simple control flow, regression checks after each batch, and final specialist/release gates.
---

# Audit Feature Change

Use this skill for every task performed in this repository. Apply it as the orchestration gate around project specialist skills; do not replace protocol, Android, serial, operation-state, diagnostics, architecture, or release expertise with this skill.

## Apply to every project task

- Load this skill before beginning any repository task, whether the task is read-only or write-capable.
- For a read-only audit, question, analysis, or plan, apply the scope, evidence, ownership, and blocker checks proportionally. Do not create files, run unrelated tests, or build artifacts merely because this skill is active.
- For any task that may write, apply the complete preflight, per-write `AGENTS.md` gate, batch review, and completion audit.
- Add relevant specialist skills after this skill. Never use a specialist skill to bypass this workflow or expand the current edit authorization.

## Current edit authorization

The current project baseline authorizes the Vue H5 / uni-app business client plus two narrowly scoped Android capability files for the face-photo closed loop. Treat `uniapp/**`, `JsBridgeV2.java` and `HttpClientManager.java` as the implementation surface, subject to `docs/CURRENT_PROJECT_BASELINE.md`. Treat all other `app/**`, backend/formal-Mock assets, serial modules and native face modules as read-only dependencies.

- Loading a specialist skill authorizes analysis, not edits outside the baseline.
- Native changes are limited to enrollment-photo result transport, generic FaceAISDK template import and authenticated generic multipart POST in the two authorized files.
- When a closed loop needs a missing read-only capability, stop that path, record the exact required input/output/failure semantics and assign it to the owning team.
- Do not make the Vue path appear complete by adding fixed-success behavior, synthetic files, fake biometric data or a private replacement Mock.

## Route the task

Read `AGENTS.md` and `docs/CURRENT_PROJECT_BASELINE.md` first. Then load every specialist skill required by the changed behavior:

- Use `$vue-business-client` for every currently writable page, component, service, state, SQLite business layer and frontend-test change.
- Use `$card-cabinet-architecture-guardian` before cross-layer or source-of-truth changes.
- Use `$android-device-integration` to audit Java, Android lifecycle, bridge security, FaceAISDK/CameraX, JNI, packaging, or Android build dependencies. Under the current baseline it is read-only.
- Use `$backend-contract-mqtt-http` for bootstrap, config, HTTP, MQTT, sync, upload, download, or backend payload work.
- Use `$device-operation-state-machine` when behavior spans validation, physical action, ACK, confirmation, timeout, cancel, retry, duplicate delivery, or restart.
- Use `$diagnostics-outbox` for logs, fault events, telemetry, retry, local event persistence, or offline replay.
- Use `$workcard-serial-v15` for serial protocol work, and preserve its read-only ownership limits unless the user explicitly authorizes changes.
- Finish every code change with `$device-release-gate`.

Read only the current documents routed by the baseline. Never use deleted documents, `old/**`, or reference code as present-day contract evidence.

## Re-read AGENTS before every write

Treat every persistent project mutation as a separate write action. This includes patches, edits, formatting, code generation, file creation or movement, and scripts or builds that can rewrite tracked project files.

Immediately before each write action:

1. List the exact intended target files and any generated files the action may update.
2. Discover and read in full every applicable `AGENTS.md`, from the workspace or repository root down through each target file's parent directories. Do not rely on a read, memory, or summary from an earlier batch or turn.
3. Restate the effective scope, ownership, read-only areas, prohibited changes, required evidence, and required validation for those targets.
4. Compare the intended write with those constraints. Proceed only when the write remains inside all applicable boundaries.

Evaluate each target path independently when one write spans multiple directories. Do not let a target without nested rules weaken the stricter rules that apply to another target.

If the target list grows, a generator reveals additional outputs, or an `AGENTS.md` changes, stop before the next mutation and repeat this gate for the new effective target set. Re-run affected preflight analysis, document routing, and specialist selection when the boundary changes.

No current `AGENTS.md` read means no write.

## Gate 1: audit before code

Inspect the current implementation, its callers, its downstream effects, tests, persisted data, and the worktree before editing. Distinguish the user's existing changes from the requested change.

Produce this preflight record:

```text
Requested outcome
Acceptance criteria
Explicit non-goals
Applicable AGENTS.md files and effective constraints
Current behavior with file/code evidence
Entry points
Source of truth for every changed value
Call chain and return/event chain
State transitions
Failure, timeout, cancel, duplicate, retry, restart and offline behavior
External contract evidence and unresolved blockers
Compatibility and migration risks
Files to change
Files deliberately not changed
Smallest viable design
Targeted and final test plan
```

Confirm that the record is internally consistent before coding. Trace actual runtime wiring; class names, constants, pages, buttons, mocks, transport success, and board ACK do not prove a business closed loop.

Stop and report a concrete blocker instead of guessing when:

- an external field, endpoint, enum, ACK, timing rule, address mapping, or deletion rule lacks current evidence;
- two layers would own the same business truth;
- success cannot be distinguished from transport acceptance, board ACK, or an optimistic UI state;
- a required change enters any path marked read-only by the current baseline;
- unrelated worktree changes overlap the same logic and cannot be preserved safely.

## Choose the simplest correct design

Prefer the smallest explicit change that satisfies the acceptance criteria and preserves V2 ownership.

- Keep each function responsible for one decision or transformation.
- Use guard clauses and early returns for invalid, denied, duplicate, cancelled, and unsupported paths.
- Keep control-flow nesting at two levels or less by default. Extract a named helper or use an explicit state machine before adding a third level; document the rare case where deeper nesting is genuinely clearer.
- Replace nested scans with a keyed lookup or precomputed index when identity is stable and the extra structure has a measured purpose.
- Separate validation, side effects, persistence, projection updates, and reporting so each can fail visibly.
- Use a small explicit state machine for time-spanning operations; do not coordinate them with combinations of booleans or deeply nested callbacks.
- Await dependent work in order. Run independent work together only when ordering, shared mutation, rate limits, and partial failure are safe.
- Do not mutate a collection while iterating unless the behavior is local, documented, and covered by a focused test.
- Do not swallow errors, return fixed success, or switch to Mock after a Release-native failure.
- Do not add a generic abstraction until the current change has at least two real consumers that need the same rule.
- Preserve local conventions and avoid unrelated formatting, renaming, migrations, or cleanup.

If a loop remains necessary, state its input bound, exit condition, mutation, error behavior, and worst-case cost. Reject an unbounded retry, polling, recursion, or loop without cancellation and backoff evidence.

## Implement in reviewable batches

Pass the `AGENTS.md` write gate immediately before each write action. Then change one behavioral unit at a time. A unit should be small enough to explain as one input-to-outcome rule and to validate with a focused check.

After each unit:

1. Re-read the changed function and relevant surrounding caller/callee code.
2. Inspect the unit diff for scope drift, accidental deletion, duplicated logic, and hidden behavior changes.
3. Trace one success path and every relevant failure path from entry to final state.
4. Verify state ownership, persistence order, projection updates, idempotency keys, and original `msgId` correlation where applicable.
5. Check cleanup and lifecycle behavior for timers, listeners, subscriptions, executors, camera, WebView, Service, and MQTT resources touched by the unit.
6. Check boundary inputs: null/empty, malformed, stale, duplicate, oversized, unauthorized, offline, timeout, cancellation, and restart as applicable.
7. Reassess complexity: nesting, long functions, flag combinations, repeated scans, hidden shared mutation, and unnecessary abstraction.
8. Run the smallest relevant static check or test and record the exact result.

Record the result before starting the next unit:

```text
Batch goal
Files changed
AGENTS.md files re-read before writes
Success path checked
Failure/restart paths checked
State and contract invariants checked
Complexity findings
Tests/checks run
Verdict: PASS or FIX BEFORE CONTINUING
```

Fix every `FIX BEFORE CONTINUING` finding before adding another behavioral unit. Do not postpone a known correctness defect to the final review.

## Bug-prevention invariants

- Validate untrusted input once at the owning boundary and return actionable errors.
- Make duplicate remote commands and retried submissions safe through persisted identity, not UI memory alone.
- Persist durable intent before reporting durable success when restart recovery matters.
- Update UI success only after the documented business completion condition.
- Keep transport ACK, board ACK, physical confirmation, outbox handoff, and backend ACK as distinct states.
- Add explicit defaults and migrations for persisted schema or config changes.
- Preserve original errors or causal context; redact secrets, tokens, biometric material, and private data from logs.
- Cover the defect or changed rule with the narrowest practical regression test.
- Test observable behavior, not private implementation shape.

## Gate 2: audit the complete change

Before claiming implementation complete:

1. Re-run the preflight call chain against the final code.
2. Inspect the full diff, not only the last batch.
3. Confirm every write action was preceded by a current read of every applicable `AGENTS.md` and remained within its effective boundaries.
4. Confirm every acceptance criterion has runtime evidence and every non-goal remained untouched.
5. Confirm no second source of truth, undocumented protocol behavior, Release-to-Mock fallback, complex loop nest, or unbounded retry was introduced.
6. Run all repository-required checks through `$device-release-gate`; mark skipped checks and target-device work as **not verified**.
7. Report blockers separately from implemented behavior. A build or generated APK is not physical-device verification.

Use this completion handoff before the release-gate report:

```text
Implemented behavior
Preserved behavior
Acceptance criteria evidence
Batch audit summary
AGENTS.md compliance summary
Regression and complexity review
Known limitations and external blockers
Validation performed
Validation not performed
Next safe batch
```
