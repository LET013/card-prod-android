---
name: android-device-integration
description: Implement or review Android-native device integration in the work-card cabinet project, including foreground service lifecycle, WebView bridge security, native authorization, ArcFace, boot recovery, threading, local storage, and Android build behavior. Trigger for Java/Android changes; do not use for pure Vue styling or serial protocol semantics alone.
---

# Android Device Integration

## Scope

Use for changes involving:

- `DeviceCoreService`, `MainActivity`, `WebViewManager`, `JsBridge`;
- Android lifecycle, foreground service, boot receiver, permissions or process restart;
- ArcFace activation, template import, camera flow or native biometric behavior;
- SharedPreferences/Room/file persistence;
- Android thread, executor, callback and resource cleanup behavior;
- Gradle, manifest, ProGuard or native library packaging.

## Required preflight

1. Read `AGENTS.md` and `docs/CODEX_PROJECT_GUIDE.md`.
2. Identify Activity, Service, Repository and manager ownership.
3. State which work runs on main thread, serial executor, IO executor or scheduler.
4. Describe behavior for Activity recreation, Service restart and process death.
5. Identify permissions, secrets and sensitive data touched.

## Android boundaries

- UI lifecycle must not own the device lifecycle. Long-lived serial/MQTT work belongs in the foreground service.
- Static service access is transitional; avoid adding more global mutable state without a migration plan.
- Never block the main thread on HTTP, MQTT, serial response, image download, template extraction or database work.
- Every executor, listener, camera, engine, timer and open port needs deterministic shutdown or restart behavior.
- Bridge permission checks occur in Android immediately before the native action.
- Expose only sanitized settings and status to H5. Keep tokens, signing keys, MQTT passwords and face features native-only.
- External pages must never retain access to the native bridge.

## WebView and bridge checklist

- Exact trusted origin and main-frame validation.
- External navigation blocked or opened outside the privileged WebView.
- No universal file URL access.
- No generic `addJavascriptInterface` surface with debugging or arbitrary native methods.
- Every action registered in `NativeActionPolicy`.
- High-risk actions require an unexpired native session and explicit permission.
- Request and response include stable IDs; errors use stable codes, not only localized text.

## Service lifecycle checklist

Validate:

- foreground notification is created before background execution deadlines;
- `START_STICKY` restart can reconstruct state from local storage;
- duplicate `onCreate`/`onStartCommand` does not create duplicate schedulers;
- listeners are detached in `onDestroy`;
- reconnect loops are bounded and cancellable;
- local UI remains usable in an explicit degraded state when the backend is unavailable.

## ArcFace rules

- Distinguish engine activation, engine readiness, template availability, live frame extraction and compare result.
- A successful employee sync does not imply successful template import.
- Persist import job status and failure reason when work can span retries.
- Validate feature version compatibility before direct feature import.
- Never log or upload raw frames, face images or face feature bytes.
- Return template count, threshold, highest score and failure category for diagnostics without exposing biometric material.

## Fingerprint rule

Android system biometric APIs authenticate an enrolled device user but do not identify which employee fingerprint matched. Never represent system biometric success as employee-level fingerprint identification. Employee-level fingerprint requires an external reader and SDK with template enrollment and 1:N matching.

## Storage decisions

Use:

- ordinary preferences only for low-risk small settings;
- encrypted/keystore-backed storage for credentials and signing material;
- Room/SQLite for operations, idempotency records, outbox events, sync jobs and queryable history;
- atomic file replacement for large versioned snapshots only when a database is unsuitable.

For every persisted schema change, define migration, old-value default and rollback behavior.

## Required validation

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

Also reason through:

- cold start with no network;
- service restart without Activity;
- Activity recreation while an operation is running;
- denied camera/phone/notification permission;
- ArcFace unavailable or unlicensed;
- configuration change during an active connection;
- logout/session expiry during a high-risk operation.

## Completion output

Report:

- lifecycle changes;
- thread ownership;
- persisted state and migration;
- permissions/security impact;
- degraded behavior;
- tests and build results.

Finish with `$device-release-gate`.
