from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing expected target: {label}")
    return text.replace(old, new)


main_path = Path('app/src/main/java/com/xingyao/card/MainActivity.java')
main = main_path.read_text(encoding='utf-8')
if 'import com.xingyao.card.core.DeviceRuntimeRegistry;' not in main:
    main = replace_required(
        main,
        'import com.xingyao.card.service.DeviceCoreService;\n',
        'import com.xingyao.card.core.DeviceRuntimeRegistry;\nimport com.xingyao.card.service.DeviceCoreService;\n',
        'MainActivity registry import',
    )
main = main.replace(
    'DeviceCoreService.setDeviceEventListener(this::sendBridgeEvent);',
    'DeviceRuntimeRegistry.setUiListener(this::sendBridgeEvent);',
)
main = main.replace(
    'DeviceCoreService.restartFaceRecognition();',
    'DeviceRuntimeRegistry.requestFaceRestart();',
)
main = main.replace(
    'DeviceCoreService.setDeviceEventListener(null);',
    'DeviceRuntimeRegistry.setUiListener(null);',
)
old_record = 'DeviceCoreService.recordOperation("biometric.fingerprint." + operation.toLowerCase(), response);'
new_record = '''DeviceRuntimeRegistry.record("biometric.fingerprint." + operation.toLowerCase(), response);
            if (enrollment) {
                try {
                    DeviceRuntimeRegistry.require().markFingerprintAuthorized(employeeId, employeeName);
                } catch (Exception error) {
                    DeviceRuntimeRegistry.record("biometric.fingerprint.employeeUpdateFailed",
                            new JSONObject().put("message", error.getMessage()));
                }
            }'''
if old_record in main:
    main = main.replace(old_record, new_record)
if 'DeviceCoreService.setDeviceEventListener' in main or 'DeviceCoreService.restartFaceRecognition' in main or 'DeviceCoreService.recordOperation' in main:
    raise SystemExit('MainActivity still bypasses Android data layer')
main_path.write_text(main, encoding='utf-8')

face_path = Path('app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java')
face = face_path.read_text(encoding='utf-8')
face = face.replace(
    'import com.xingyao.card.service.DeviceCoreService;',
    'import com.xingyao.card.core.DeviceRuntimeRegistry;',
)
face = face.replace(
    'DeviceCoreService.verifyFace(frame, previewWidth, previewHeight)',
    'DeviceRuntimeRegistry.require().verifyFace(frame, previewWidth, previewHeight)',
)
face = face.replace(
    'DeviceCoreService.enrollFace(employeeId, employeeName == null ? "" : employeeName, frame, previewWidth, previewHeight)',
    'DeviceRuntimeRegistry.require().enrollFace(employeeId, employeeName == null ? "" : employeeName, frame, previewWidth, previewHeight)',
)
if 'DeviceCoreService' in face:
    raise SystemExit('FaceEnrollmentActivity still bypasses Android data layer')
face_path.write_text(face, encoding='utf-8')

Path('.refactor/apply_remaining_three_layer.py').unlink()
