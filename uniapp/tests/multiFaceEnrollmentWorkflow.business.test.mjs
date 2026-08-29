import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildFaceImageUploadRequest,
  buildFaceRecordRequest,
  createMultiFaceEnrollmentWorkflow,
  createTemporaryFaceAiId,
  parseFaceImageUploadResponse,
  parseFaceRecordResponse,
  sha256FacePhoto
} from '../src/services/faceEnrollmentWorkflow.js'

const PHOTO_BASE64 = Buffer.from('face-photo').toString('base64')
const PHOTO_HASH = 'a'.repeat(64)

test('uploads the image before building the documented faceRegister request', () => {
  assert.match(createTemporaryFaceAiId('10001', 'face-enrollment:operation-123'), /^10001_pending_/)
  assert.deepEqual(buildFaceImageUploadRequest({
    employeeId: '10001',
    faceImageBase64: PHOTO_BASE64,
    faceImageMimeType: 'image/jpeg'
  }), {
    employeeId: 10001,
    fields: { employeeId: 10001 },
    file: { fieldName: 'file', fileName: 'face.jpg', mimeType: 'image/jpeg', base64: PHOTO_BASE64 }
  })
  assert.deepEqual(buildFaceRecordRequest({
    employeeId: '10001',
    fileHash: PHOTO_HASH,
    faceImagePath: '/faces/10001.jpg',
    faceFeature: 'local-feature'
  }), {
    employeeId: 10001,
    fileHash: PHOTO_HASH,
    faceImagePath: '/faces/10001.jpg',
    faceFeature: 'local-feature'
  })
})

test('accepts current MQTT and HTTP face registration responses', () => {
  assert.deepEqual(parseFaceRecordResponse({
    code: 0,
    msg: 'success',
    employeeId: 10001
  }, '10001'), { employeeId: '10001' })
  assert.deepEqual(parseFaceRecordResponse({
    status: 200,
    body: { code: 200, msg: 'success', data: { employeeId: 10001 } }
  }, '10001'), { employeeId: '10001' })
  assert.throws(
    () => parseFaceRecordResponse({ code: 0, employeeId: 10002 }, '10001'),
    (error) => error?.code === 'FACE_RESPONSE_EMPLOYEE_MISMATCH'
  )
})

test('accepts server creation, removes the temporary template and leaves local import to exit sync', async () => {
  const calls = []
  const stages = []
  const workflow = createMultiFaceEnrollmentWorkflow({
    hashPhoto: async () => PHOTO_HASH,
    findPhotoByFileHash: async () => null,
    uploadFaceImage: async () => ({ code: 0, fileHash: PHOTO_HASH, faceImagePath: '/faces/10001.jpg' }),
    sendFaceRecord: async (request) => {
      calls.push(['register', request])
      return { code: 0, msg: 'success', employeeId: request.employeeId }
    },
    removeTemplate: async (faceAiId) => { calls.push(['remove', faceAiId]) },
    recordStage: async (stage) => { stages.push(stage) },
    now: () => 1_725_000_000_000
  })

  const result = await workflow({
    operationId: 'face-enrollment:10001:1',
    temporaryFaceAiId: '10001_pending_1',
    employeeId: '10001',
    employeeName: '张三',
    enrolled: {
      faceFeature: 'local-feature',
      faceImageBase64: PHOTO_BASE64,
      faceImageMimeType: 'image/jpeg'
    }
  })

  assert.equal(result.serverAccepted, true)
  assert.equal(result.pendingIncrementalSync, true)
  assert.deepEqual(calls.map(call => call[0]), ['register', 'remove'])
  assert.deepEqual(calls[0][1], {
    employeeId: 10001,
    fileHash: PHOTO_HASH,
    faceImagePath: '/faces/10001.jpg',
    faceFeature: 'local-feature'
  })
  assert.deepEqual(stages, ['CAPTURED', 'UPLOADING', 'REGISTERING', 'SERVER_ACCEPTED', 'COMPLETED'])
})

test('rejects a duplicate before registration and removes the temporary template', async () => {
  const calls = []
  const workflow = createMultiFaceEnrollmentWorkflow({
    hashPhoto: async () => PHOTO_HASH,
    findPhotoByFileHash: async () => ({ faceId: '10001', employeeId: '10001' }),
    uploadFaceImage: async () => { calls.push('upload') },
    sendFaceRecord: async () => { calls.push('network') },
    removeTemplate: async id => { calls.push(`remove:${id}`) },
  })
  await assert.rejects(workflow({
    operationId: 'face-enrollment:10001:2',
    temporaryFaceAiId: '10001_pending_2',
    employeeId: '10001',
    enrolled: { faceFeature: 'local-feature', faceImageBase64: PHOTO_BASE64 }
  }), (error) => error?.code === 'FACE_IMAGE_DUPLICATE')
  assert.deepEqual(calls, ['remove:10001_pending_2'])
})

test('does not persist local success when the server rejects registration', async () => {
  const calls = []
  const workflow = createMultiFaceEnrollmentWorkflow({
    hashPhoto: async () => PHOTO_HASH,
    findPhotoByFileHash: async () => null,
    uploadFaceImage: async () => ({ code: 0, fileHash: PHOTO_HASH, faceImagePath: '/faces/10001.jpg' }),
    sendFaceRecord: async () => ({ code: 500, msg: '人脸注册失败' }),
    removeTemplate: async id => { calls.push(`remove:${id}`) },
  })
  await assert.rejects(workflow({
    operationId: 'face-enrollment:10001:3',
    temporaryFaceAiId: '10001_pending_3',
    employeeId: '10001',
    enrolled: { faceFeature: 'local-feature', faceImageBase64: PHOTO_BASE64 }
  }), /人脸注册失败/)
  assert.deepEqual(calls, ['remove:10001_pending_3'])
})

test('maps the documented upload fileName to faceRegister faceImagePath', () => {
  assert.deepEqual(parseFaceImageUploadResponse({
    status: 200,
    body: {
      code: 200,
      data: {
        fileHash: PHOTO_HASH,
        fileName: '/profile/face/2026/08/13/5_1786617973617.jpg'
      }
    }
  }, '5', PHOTO_HASH), {
    employeeId: '5',
    fileHash: PHOTO_HASH,
    faceImagePath: '/profile/face/2026/08/13/5_1786617973617.jpg'
  })
})

test('does not call faceRegister when the image upload response is invalid', async () => {
  const calls = []
  const workflow = createMultiFaceEnrollmentWorkflow({
    hashPhoto: async () => PHOTO_HASH,
    findPhotoByFileHash: async () => null,
    uploadFaceImage: async () => ({ code: 0, fileHash: 'invalid', faceImagePath: '' }),
    sendFaceRecord: async () => { calls.push('register') },
    removeTemplate: async id => { calls.push(`remove:${id}`) }
  })
  await assert.rejects(workflow({
    operationId: 'face-enrollment:10001:4',
    temporaryFaceAiId: '10001_pending_4',
    employeeId: '10001',
    enrolled: { faceFeature: 'local-feature', faceImageBase64: PHOTO_BASE64 }
  }), (error) => error?.code === 'FACE_UPLOAD_RESPONSE_INVALID')
  assert.deepEqual(calls, ['remove:10001_pending_4'])
})

test('computes the real SHA-256 of the decoded photo bytes', async () => {
  assert.equal(
    await sha256FacePhoto(Buffer.from('abc').toString('base64')),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
  )
})
