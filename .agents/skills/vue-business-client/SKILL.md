---
name: vue-business-client
description: Implement or review the writable Vue H5 / uni-app frontend and business-client layer of the work-card cabinet project. Trigger for pages, components, services, nativeBridge callers, Vue-managed SQLite schema/localStore, appState projections, permissions, forms, HTTP/MQTT business payloads, operation records, outbox, and frontend tests. Android, backend, formal Mock, serial and native face implementations remain read-only.
---

# Vue Business Client

Use this skill for all implementation work in the current project phase.

## Writable surface

- `uniapp/src/pages/**`
- `uniapp/src/components/**`
- `uniapp/src/services/**`, except Mock simulation files excluded by the current baseline
- `uniapp/src/state/**`
- `uniapp/src/constants/**`
- `uniapp/src/styles/**` and related Vue entry/build configuration when required by the feature
- `uniapp/tests/**`
- current frontend contract and boundary documentation

## Read-only dependencies

- all `app/**` except the two capability files explicitly authorized by the current baseline;
- backend and formal Mock server, broker, database and tests;
- `uniapp/src/mock/**`, `uniapp/src/services/mockService.js`, `uniapp/scripts/dev-mock.js` and `uniapp/scripts/faceai-server/**` in the current formal-Mock phase;
- serial, `old/**` and `reference/**` modules.

Inspect these dependencies only to confirm an existing contract. A missing dependency capability is a blocker, not an implementation target.

## Client call path

Keep page and business calls in this order:

```text
page/component
  -> uniapp/src/services/index.js
  -> uniapp/src/services/nativeBridge.js
  -> existing JsBridgeV2 action
  -> response/event
  -> service validation and business decision
  -> localStore durable state
  -> appState/UI projection
```

- Pages own form drafts, selection, loading and user-visible errors; they do not assemble native calls or SQL.
- `services/index.js` owns business validation, documented HTTP/MQTT payloads, response parsing, operation ordering and failure propagation.
- `nativeBridge.js` owns correlated request/event mechanics only. It may call existing actions but cannot define a native capability into existence.
- `localStore.js` owns SQL and durable Vue business data. Per the user's explicit decision, it stores the bounded enrollment/server face photo in application-private SQLite together with identifiers and delivery state; raw face features remain transient and are not persisted in Vue SQLite.
- `appState` is a WebView-lifetime projection, never the only source for data needed after refresh/restart.

## Preflight

Before editing, record:

```text
User entry and expected final UI state
Existing service and native action/event evidence
Backend endpoint and response evidence
Validation rules
Persistence order and final SQLite record
Failure, timeout, cancel, duplicate, offline and restart behavior
Writable files
Read-only dependencies
External capability gaps
Focused test plan
```

Do not implement a path when required input cannot reach Vue through an existing response/event, or when the existing transport cannot send the documented request. Report the exact gap.

## Implementation rules

- Validate employee identity, permissions, required fields, bounds and current state before side effects.
- Use guard clauses and keep nesting at two levels or less by default.
- Await dependent steps in business order. Do not persist final success before every required remote/native step succeeds.
- Distinguish native acceptance, native completion, HTTP transport success, backend business success and local persistence success.
- Do not return fixed success, silently fall back to Mock, or hide an unsupported path behind a successful toast.
- Keep duplicate commands/submissions safe using persisted IDs when the action has durable side effects.
- Preserve backend `msgId` for downlink responses and durable retry.
- Redact tokens, passwords, face/fingerprint features, photos and identity data from logs.
- Store only the single bounded photo used by the current face binding, never continuous frames. Enforce 10 MB before persistence, never log it, and never copy it to system gallery/public media storage.
- Do not persist raw face/fingerprint features in Vue SQLite.

## Biometric client rules

- Validate that the selected employee exists, is enabled and is not expired before starting enrollment or accepting recognition.
- Treat `face.enrolled` as native completion only. Validate its current documented payload before invoking backend business APIs.
- Upload only the actual photo returned by enrollment or face sync. Enforce the user-confirmed 10 MB maximum before SQLite persistence and upload.
- A feature upload and a photo upload are separate backend outcomes unless the current contract explicitly combines them.
- Save the local `faceId -> employeeId` binding after native template application and local photo persistence. Server delivery state is durable and may remain `PENDING` offline; UI must distinguish local completion from server synchronization.
- Never synthesize a photo or substitute a Mock success result.

## Required validation

Run the smallest focused tests after each batch, then:

```bash
node --check uniapp/src/services/nativeBridge.js
node --check uniapp/src/services/index.js
node --check uniapp/src/services/localStore.js
node --check uniapp/src/state/appState.js
cd uniapp && npm run test:local-store
cd uniapp && npm run build:h5
```

For UI behavior, verify the changed page at desktop and target compact viewport sizes when a runnable H5 environment is available. Report Android APK and device checks as not performed unless explicitly requested.

Finish with `$device-release-gate`.
