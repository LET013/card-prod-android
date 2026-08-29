export const EMPLOYEE_FINGERPRINT_CAPABILITY = Object.freeze({
  available: false,
  code: 'EMPLOYEE_FINGERPRINT_MODULE_REQUIRED',
  title: '员工指纹暂不可用',
  message: '当前 Android 系统指纹只能确认本机用户，不返回员工身份、fingerId 或指纹特征，不能用于员工级录入和取卡。',
  requirement: '需接入可返回员工级识别结果的外接指纹模块与 SDK 后再开放。'
})
