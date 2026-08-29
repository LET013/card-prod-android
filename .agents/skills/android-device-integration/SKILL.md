---
name: android-device-integration
description: Review Android-native device integration in the work-card cabinet project, including foreground service lifecycle, WebView bridge security, native authorization, FaceAISDK/CameraX, JNI serial packaging, boot recovery, threading, storage, and Android build behavior. Implement only when the current baseline and an explicit user scope revision authorize Android changes; do not use for pure Vue styling or protocol semantics alone.
---

# Android Device Integration

## Current authorization

The user explicitly authorized a minimal face-photo capability change in `JsBridgeV2.java` and `HttpClientManager.java`. Those files may expose the captured enrollment photo, import a supplied photo/feature through the existing `FaceAiManager`, and perform authenticated generic multipart POST. All other Android files remain read-only.

Do not add employee, synchronization, SQLite-photo-table or take-card business logic to Android. If the two authorized files are insufficient, return a concrete capability gap instead of widening the edit set.

## Scope

Use for read-only audits involving:

- `DeviceCoreService`, `MainActivity`, `WebViewManager`, `JsBridge`;
- Android lifecycle, foreground service, boot receiver, permissions or process restart;
- FaceAISDK engine, CameraX preview/analyzer, local face library or biometric result handling;
- JNI serial native library and ABI packaging;
- SharedPreferences/Room/file persistence;
- Android thread, executor, callback and resource cleanup behavior;
- Gradle, manifest, ProGuard, CMake or native library packaging.

ArcSoft is abandoned. Do not restore `ArcFaceManager`, `arcsoft_face.jar`, `ARCSOFT_*` or READ_PHONE_STATE behavior for ArcSoft activation.

## Required preflight

1. Read `AGENTS.md` and the unique current entrypoint `docs/CURRENT_PROJECT_BASELINE.md`, then follow its task-specific document list.
2. Compare device behavior with `reference/motone-current`, but do not copy its old architecture or contracts.
3. Identify Activity, Service, JsBridgeV2 channel, Vue caller and native adapter ownership.
4. State which work runs on main thread, CameraX analyzer, SQLite executor, transport executor or scheduler.
5. Describe Activity recreation, Service restart and process death behavior.
6. Identify permissions, credentials and biometric data touched.

## Android boundaries

- Vue owns business flow, but UI lifecycle must not directly own serial/backend native lifecycle; long-lived native work belongs in the foreground Service container.
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

- `FaceAiManager` is an adapter; employee and `faceId` binding truth remains in Vue SQLite.
- CameraX and `FaceEnrollmentController` live in the Activity UI flow; results return to Vue through `face.*` response/event.
- A successful employee sync does not imply a successful FaceAISDK template import.
- Do not advance applied face cursor when import fails.
- Local enrollment must be initiated by Vue with an explicit `faceId`; Android cannot create an employee from a face ID.
- When the documented face-feature endpoint is used, backend success and Vue SQLite update must be separated from local FaceAISDK template application.
- Do not persist or log continuous raw frames, face images or full feature strings in Android. The one cropped enrollment photo may be returned to trusted Vue for the explicitly authorized SQLite flow.
- Generic multipart may accept only a bounded explicit photo supplied by trusted Vue and must reuse the existing token provider.
- Return the minimum photo/feature data required by the trusted Vue workflow; never include it in logs or diagnostics.

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
- Android SQLite bridge only as an execution engine for Vue-owned schema; do not interpret Vue business tables in Android;
- app-private files for firmware and explicit upload sources.

For every Vue SQLite schema change, update `docs/VUE_SQLITE_SCHEMA.md` and keep SQL centralized in Vue services.

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
