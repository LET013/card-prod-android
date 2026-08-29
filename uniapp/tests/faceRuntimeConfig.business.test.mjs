import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeFaceRuntimeOptions } from '../src/services/faceRuntimeConfig.js'

test('passes every documented face runtime field to the native capability layer', () => {
  assert.deepEqual(normalizeFaceRuntimeOptions({
    cameraFacing: 'back',
    cameraMirror: false,
    cameraRotation: 270,
    cameraFrameWidth: 1280,
    cameraFrameHeight: 720,
    faceThreshold: 0.86,
    faceRecognitionTimeout: 42000,
    searchTimeout: 18000,
    searchIntervalTime: 2500,
    needFaceLiveness: true,
    captureTimeout: 12000
  }), {
    cameraFacing: 'back',
    cameraMirror: false,
    cameraRotation: 270,
    cameraFrameWidth: 1280,
    cameraFrameHeight: 720,
    threshold: 0.86,
    faceRecognitionTimeout: 42000,
    searchTimeout: 18000,
    searchIntervalTime: 2500,
    needFaceLiveness: true,
    captureTimeout: 12000
  })
})

test('uses the config contract defaults for missing or invalid face values', () => {
  assert.deepEqual(normalizeFaceRuntimeOptions({
    cameraFrameWidth: 0,
    cameraFrameHeight: -1,
    threshold: 0,
    faceRecognitionTimeout: 'bad',
    searchTimeout: 0,
    searchIntervalTime: -2,
    captureTimeout: null
  }), {
    cameraFacing: 'front',
    cameraMirror: true,
    cameraRotation: 0,
    cameraFrameWidth: 640,
    cameraFrameHeight: 480,
    threshold: 0.8,
    faceRecognitionTimeout: 30000,
    searchTimeout: 15000,
    searchIntervalTime: 3000,
    needFaceLiveness: false,
    captureTimeout: 8000
  })
})
