---
name: device-release-gate
description: Final verification and delivery gate for any work-card cabinet code change. Trigger before claiming completion, committing a batch, pushing a branch, updating a PR, producing an APK, or recommending release. Checks scope, three-layer boundaries, V4.1 contract evidence, FaceAISDK/JNI packaging, tests, build, documentation, temporary files, branch safety, and device failure scenarios.
---

# Device Release Gate

Run after implementation and before claiming completion.

## 1. Scope and diff

- Restate the requested scope.
- List deliberately excluded work and external blockers.
- Inspect status, diff stat, whitespace errors and full diff.
- Remove generated APKs, temporary scripts/workflows, diagnostics, local configuration and unrelated formatting.
- Confirm the branch is not `main`/`master`, the PR remains Draft unless explicitly requested otherwise, and auto-merge is disabled.

## 2. Contract evidence

For every changed external field, path, method, command, enum, error code, timeout and address rule:

- cite V4.1 Markdown, serial Markdown or an explicit user decision;
- verify it is not copied merely because it existed in `reference/motone-current`;
- keep missing behavior blank/disabled and register it in `docs/CONTRACT_EVIDENCE_REGISTER.md`;
- ensure PDF/old Markdown did not override current V4.1.

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

- Vue is only an in-memory projection and does not invent business fields;
- `JsBridge → DeviceApplicationFacade → DeviceDataLayer` is the only UI path;
- Service only wires lifecycle;
- Provisioning and documented endpoint validation live in Android data/business layer;
- `BackendTransportManager`, HTTP Gateway, serial and FaceAISDK adapters do not own business truth or read Settings Repository;
- communication results return to data layer before UI notification;
- remote side effects are idempotent and responses reuse original `msgId`;
- board ACK is not physical TAKE/RETURN completion;
- no ArcSoft or `xmaihh` dependency returned.

Any failure blocks approval.

## 5. Baseline validation

Run:

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/mockService.js
node --check uniapp/src/state/appState.js
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

- `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md`;
- `docs/DEVICE_CONFIGURATION_AND_INTERFACE_AUDIT.md`;
- `docs/CONTRACT_EVIDENCE_REGISTER.md`;
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
