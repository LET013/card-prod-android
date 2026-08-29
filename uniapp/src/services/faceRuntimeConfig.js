const positiveNumber = (value, fallback) => {
  const normalized = Number(value)
  return Number.isFinite(normalized) && normalized > 0 ? normalized : fallback
}

const positiveInteger = (value, fallback) => {
  const normalized = Number(value)
  return Number.isInteger(normalized) && normalized > 0 ? normalized : fallback
}

const normalizeCameraFacing = (value) => (
  String(value || '').toLowerCase() === 'back' ? 'back' : 'front'
)

const normalizeCameraRotation = (value) => {
  const rotation = Number(value)
  return [0, 90, 180, 270].includes(rotation) ? rotation : 0
}

export function normalizeFaceRuntimeOptions(settings = {}) {
  const cameraFacing = normalizeCameraFacing(settings.cameraFacing)
  return {
    cameraFacing,
    cameraMirror: settings.cameraMirror != null
      ? Boolean(settings.cameraMirror)
      : cameraFacing === 'front',
    cameraRotation: normalizeCameraRotation(settings.cameraRotation),
    cameraFrameWidth: positiveInteger(settings.cameraFrameWidth, 640),
    cameraFrameHeight: positiveInteger(settings.cameraFrameHeight, 480),
    threshold: positiveNumber(
      settings.threshold ?? settings.faceRecognitionThreshold ?? settings.faceThreshold,
      0.8
    ),
    faceRecognitionTimeout: positiveInteger(settings.faceRecognitionTimeout, 30000),
    searchTimeout: positiveInteger(settings.searchTimeout, 15000),
    searchIntervalTime: positiveInteger(settings.searchIntervalTime, 3000),
    needFaceLiveness: Boolean(settings.needFaceLiveness),
    captureTimeout: positiveInteger(settings.captureTimeout, 8000)
  }
}
