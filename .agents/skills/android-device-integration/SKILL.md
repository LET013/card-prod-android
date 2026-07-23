---
name: android-device-integration
description: Implement or review Android-native device integration in the work-card cabinet project, including foreground service lifecycle, WebView bridge security, native authorization, FaceAISDK/CameraX, JNI serial packaging, boot recovery, threading, storage, and Android build behavior. Trigger for Java/Android changes; do not use for pure Vue styling or protocol semantics alone.
---

# Android Device Integration

## Scope

Use for changes involving:

- `DeviceCoreService`, `MainActivity`, `WebViewManager`, `JsBridge`;
- Android lifecycle, foreground service, boot receiver, permissions or process restart;
- FaceAISDK engine, CameraX preview/analyzer, local face library or biometric result handling;
- JNI serial native library and ABI packaging;
- SharedPreferences/Room/file persistence;
- Android thread, executor, callback and resource cleanup behavior;
- Gradle, manifest, ProGuard, CMake or native library packaging.

ArcSoft is abandoned. Do not restore `ArcFaceManager`, `arcsoft_face.jar`, `ARCSOFT_*` or READ_PHONE_STATE behavior for ArcSoft activation.

## Required preflight

1. Read `AGENTS.md`, `docs/COMPLETE_THREE_LAYER_ARCHITECTURE.md` and `docs/CONTRACT_EVIDENCE_REGISTER.md`.
2. Compare device behavior with `reference/motone-current`, but do not copy its old architecture or contracts.
3. Identify Activity, Service, data layer, Repository and adapter ownership.
4. State which work runs on main thread, CameraX analyzer, data-layer executor, transport executor or scheduler.
5. Describe Activity recreation, Service restart and process death behavior.
6. Identify permissions, credentials and biometric data touched.

## Android boundaries

- UI lifecycle must not own serial/backend lifecycle; long-lived work belongs in the foreground Service container.
- `DeviceCoreService` wires and owns lifecycle only; it does not coordinate business workflows.
- Never block the main thread on HTTP, MQTT, serial, image download, feature extraction or database work.
- Every executor, listener, camera, engine, timer and open port needs deterministic shutdown.
- Bridge permission checks occur in Android immediately before the native action.
- Expose only sanitized settings/status to H5. Tokens, signing keys, MQTT passwords and face features remain native-only.
- External pages must never retain native bridge access.

## WebView and bridge checklist

- Exact trusted origin and main-frame validation.
- External navigation blocked or opened outside the privileged WebView.
- No universal file URL access.
- No generic `addJavascriptInterface` surface.
- Every action registered in `NativeActionPolicy`.
- High-risk actions require an unexpired native session and explicit permission.
- Deferred network actions finish through one correlated native response.

## Service lifecycle checklist

Validate:

- foreground notification is created before background work;
- `START_STICKY` reconstructs data layer and adapters;
- duplicate lifecycle calls do not create duplicate schedulers;
- listeners and runtime registry are cleared in `onDestroy`;
- reconnect loops are cancellable;
- local UI remains available in an explicit degraded state when backend or serial is unavailable.

## FaceAISDK rules

- `FaceAiManager` is an adapter; employee truth remains in `DeviceDataRepository`.
- CameraX and `FaceEnrollmentController` live in the Activity UI flow; results return through `DeviceDataLayer`.
- A successful employee sync does not imply a successful FaceAISDK template import.
- Do not advance applied face cursor when import fails.
- Local enrollment must verify the employee exists; it cannot create an employee from a face ID.
- When the documented face-feature endpoint is used, backend success precedes local template/Map commit.
- Do not persist or log raw frames, face images or full feature strings.
- The multipart upload endpoint may only read an explicit real file from app-private files/cache.
- Return counts, threshold, score and failure category without exposing biometric material.

## Fingerprint rule

Android system biometric APIs authenticate an enrolled device user but do not identify an employee fingerprint. Never represent system success as employee-level fingerprint identification. Employee-level upload requires an external reader and real `fingerFeature/fingerIndex`.

## JNI serial rules

- The packaged APK must contain `lib/arm64-v8a/libSerialPort.so`.
- JNI Java method names must match C exports.
- Write failures must propagate; do not log and return success.
- Device-node permission diagnostics may be reported, but do not claim a port is open without a valid file descriptor.
- Logical slot commands remain disabled until address topology is documented.

## Storage decisions

Use:

- ordinary preferences only for small settings and current restart backup;
- encrypted/keystore-backed storage for credentials and signing material;
- Room/SQLite for operations, idempotency, outbox, sync jobs and history;
- app-private files for firmware and explicit upload sources.

For every schema change, define migration, old-value default and rollback behavior.

## Required validation

```bash
cd uniapp && npm run build:h5
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep lib/arm64-v8a/libSerialPort.so
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/index.html
```

Also reason through:

- cold start without network;
- Service restart without Activity;
- Activity recreation during face/fingerprint action;
- denied camera/notification permission;
- FaceAISDK initialization or template import failure;
- configuration change during active connection;
- logout/session expiry during high-risk action.

Build success is not target-device installation evidence. Report rk3568_r installation and hardware checks separately.

Finish with `$device-release-gate`.
