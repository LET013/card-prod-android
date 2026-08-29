---
name: device-release-gate
description: Final verification and delivery gate for any work-card cabinet code change. Trigger before claiming completion, committing a batch, pushing a branch, updating a PR, producing an APK, or recommending release. Checks scope, V2 Vue/Android boundaries, V4.2/config contract evidence, FaceAISDK/JNI packaging, tests, build, documentation, temporary files, branch safety, and device failure scenarios.
---

# Device Release Gate

Run after implementation and before claiming completion.

## 1. Scope and diff

- Restate the requested scope.
- List deliberately excluded work and external blockers.
- Inspect status, diff stat, whitespace errors and full diff.
- For the current face-photo scope, allow changes only in `JsBridgeV2.java` and `HttpClientManager.java`; fail the gate if any other `app/**`, backend/formal-Mock asset, serial module or native face module changed as part of this task.
- Remove generated APKs, temporary scripts/workflows, diagnostics, local configuration and unrelated formatting.
- Confirm the branch is not `main`/`master`, the PR remains Draft unless explicitly requested otherwise, and auto-merge is disabled.

## 2. Contract evidence

For every changed external field, path, method, command, enum, error code, timeout and address rule:

- cite V4.2 Markdown, config guide, serial Markdown or an explicit user decision;
- verify it is not copied merely because it existed in `reference/motone-current`;
- keep missing behavior blank/disabled and report it as a concrete blocker in the current audit, plan or PR;
- ensure PDF/old Markdown did not override current V4.2/config docs.

Reject the change if it introduces a likely/default/test behavior without evidence.

## 3. Secret and privacy scan

Search changed files for actual:

```text
ghp_
github_pat_
password
token
signingKey
mqttPassword
Authorization
faceFeature
fingerFeature
base64 biometric data
private endpoints
local.properties values
```

Field names in code are expected; actual secrets, real biometric material and customer/private endpoint values are forbidden.

## 4. Architecture checks

Confirm:

- Vue owns business flow, HTTP/MQTT payloads, response parsing, SQLite cache, permissions, operations and outbox;
- `services/index.js -> nativeBridge.js -> JsBridgeV2` is the UI-to-native ability path;
- Service only wires lifecycle and long-lived native abilities;
- Android bootstrap/native managers expose capabilities and do not restore business Map truth;
- MQTT, HTTP, serial, SQLite and FaceAISDK adapters do not decide employee/card business success;
- native results return to Vue as response/event before Vue updates projection or SQLite;
- remote side effects are idempotent and responses reuse original `msgId`;
- board ACK is not physical TAKE/RETURN completion;
- no ArcSoft or `xmaihh` dependency returned;
- `old/**` remains read-only reference/negative sample and is not reconnected to V2.

Any failure blocks approval.

## 5. Baseline validation

For the current Vue-only scope, run the JavaScript checks, relevant `uniapp/tests/**` tests and H5 build. Android unit tests, APK assembly and ABI/assets packaging checks are required only when packaging/release validation is explicitly requested; otherwise report them as not performed, not failed.

Run:

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/localStore.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
cd uniapp && npm run test:local-store
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep lib/arm64-v8a/libSerialPort.so
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/index.html
```

Do not mark a skipped check as passed.

## 6. Specialist validation

### MQTT/HTTP

- registration→activation→config→authorization sequence;
- HTTP/MQTT login enters authenticated only on explicit `code=0`;
- reconnect does not duplicate heartbeat;
- uplink signature and envelope;
- downlink is not rejected for missing sign/deviceCode;
- response reuses original server msgId;
- exact ten documented downlink handlers;
- HTTP timeout, invalid JSON, 4xx/5xx and business error;
- incremental merge/deletion/cursor behavior.

Do not test or require an undocumented timestamp window or downlink signature.

### Serial

- JNI library packaged for target ABI;
- Java/C JNI symbol match;
- CRC and malformed/split/sticky frame;
- raw write failure propagation;
- logical mapping remains disabled until topology exists.

### FaceAISDK

- Camera permission and CameraX lifecycle;
- engine initialization and cleanup;
- enrollment only for existing employee;
- backend face-feature success before local commit;
- sync import failure does not advance applied cursor;
- no raw frame/feature logging.

### Android/UI

- cold start offline;
- Service restart without Activity;
- Activity recreation and executor cleanup;
- session expiry;
- denied camera/notification permission;
- page refresh preserves native truth.

## 7. Documentation

Update actual sources of truth:

- `docs/CURRENT_PROJECT_BASELINE.md` when scope, priority or ownership changes;
- the relevant current V4.2/config/JsBridgeV2/SQLite/admin document when its contract or design changes;
- relevant Skills/AGENTS;
- Draft PR body.

Documentation must distinguish runtime closed loop, business entrypoint, transport mapping and blocked feature.

## 8. Completion verdict

### PASS

All automated checks and required manual/device checks passed.

### CONDITIONAL PASS

Only for explicitly accepted non-production missing evidence. Never use for failed build, security, topology, idempotency or data migration.

### FAIL

List blockers and next corrective action.

A generated APK is not evidence of installation on rk3568_r. Unless `adb install`, cold start and relevant hardware/backend tests were run, report target-device installation as **not verified**.

Required report:

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
