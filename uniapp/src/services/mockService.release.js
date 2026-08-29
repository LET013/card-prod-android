/** 生产入口占位：构建时替换 mockService.js，确保正式包不引用任何 Mock 资产。 */
export function createMockService() {
  const error = new Error('Mock service is disabled in this build')
  error.code = 'MOCK_DISABLED'
  throw error
}
