---
name: device-release-gate
description: Final verification and delivery gate for any work-card cabinet code change. Trigger before claiming completion, committing a batch, pushing a branch, opening/updating a PR, producing an APK, or recommending release. Checks scope, diff, security, tests, build, documentation, temporary files, branch safety, and device failure scenarios.
---

# Device Release Gate

Run this skill after implementation and before saying the task is complete.

## 1. Scope and diff

- Confirm the requested batch goal in one sentence.
- List deliberately excluded work.
- Run and inspect:

```bash
git status --short
git diff --stat
git diff --check
git diff
```

- Remove unrelated formatting, generated assets, temporary patches, logs, APKs and local configuration.
- Confirm no unexpected UI behavior or protocol field changed.
- Confirm the branch is not `main`/`master` and no automatic merge is enabled.

## 2. Secret and privacy scan

Search the diff and new files for:

```text
ghp_
github_pat_
password
token
signingKey
mqttPassword
Authorization
faceFeature
base64 biometric data
private endpoints or local.properties values
```

Distinguish field names from actual secrets. No actual credential, face image, face template, fingerprint data or identity document may be committed or logged.

## 3. Architecture checks

Confirm:

- UI does not become a second source of truth;
- high-risk native actions are authorized in Android;
- remote side effects are idempotent;
- operations have `operationId` and explicit stages;
- board ACK is not represented as physical completion;
- network loss and restart behavior are defined;
- no undocumented serial/backend assumption was introduced;
- errors are not swallowed or only written to Logcat.

If any item fails, do not approve release.

## 4. Baseline validation

Run:

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

When H5/UI changed, also run the project H5 build and verify assets are generated from a clean output directory.

Do not mark a check as passed when it was skipped because of missing tools. Report the blocker and either install/fix the toolchain or keep the task incomplete.

## 5. Specialist validation

Use the applicable matrix:

### MQTT/HTTP

- login/authenticated transition;
- duplicate command before and after restart;
- reconnect without duplicate heartbeat;
- signature/timestamp/device rejection;
- HTTP timeout and incremental sync failure.

### Serial

- CRC and malformed frame;
- split/sticky packet;
- timeout cleanup;
- polling pause/resume;
- explicit mapping/unmapped slot;
- concurrent callers serialize.

### Operation state

- board rejection;
- physical timeout;
- duplicate attaches to existing operation;
- restart recovery;
- batch child aggregation.

### Diagnostics

- offline persistence;
- restart recovery;
- ACK/pruning;
- backoff and queue limits;
- redaction.

### Android/UI

- cold start offline;
- Service restart without Activity;
- Activity recreation;
- session expiry;
- denied permission;
- page refresh preserves native truth.

## 6. Documentation

Update the relevant source of truth:

- `docs/CODEX_PROJECT_GUIDE.md` for project-wide rules;
- protocol/contract docs for message or frame changes;
- state machine docs for stages/timeouts;
- migration notes for persisted data;
- PR body for limitations and external blockers.

Documentation must describe actual implemented behavior, not planned behavior.

## 7. Git delivery

- Stage only intended files.
- Use a clear batch-level commit message.
- Push the independent branch with tracking.
- Default to a Draft PR.
- PR body includes:
  - root cause or goal;
  - changed behavior;
  - preserved compatibility;
  - tests/build evidence;
  - migration/rollback;
  - unresolved external contracts.
- Do not merge unless the user explicitly requests it after review.

## 8. Completion verdict

Return one of:

### PASS

All required checks passed. Include branch, commit, tests, build artifact status and known non-blocking limitations.

### CONDITIONAL PASS

Only when a clearly non-production check is unavailable and the user explicitly accepts the risk. List the exact missing evidence. Never use this for security, serial topology, data migration, idempotency or a failed build.

### FAIL

List blockers in priority order and the next corrective action. Do not claim the feature is complete.

## Required final report

```text
Verdict
Scope delivered
Files/modules changed
Behavior and compatibility
Security/privacy review
Tests and build results
Manual/device checks
Migration and rollback
Known limitations/blockers
Branch/commit/PR
```
