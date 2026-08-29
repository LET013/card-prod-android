import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * Vite 插件：显式 Mock 开发模式替换 nativeBridge / mockService；
 * 其他模式把 mockService 指向无 Mock 依赖的生产占位模块。
 */
function mockAliasPlugin(command) {
  const isMockDev = process.env.MOCK_DEV === 'true' && command === 'serve'

  const srcDir = path.resolve(__dirname, 'src')
  const servicesDir = path.join(srcDir, 'services')
  const nativeBridgePath = path.join(servicesDir, 'nativeBridge.js')
  const mockServicePath = path.join(servicesDir, 'mockService.js')
  const mockBridgePath = path.join(srcDir, 'mock', 'bridge.js')
  const mockServiceImplPath = path.join(srcDir, 'mock', 'service.js')
  const releaseMockServicePath = path.join(servicesDir, 'mockService.release.js')

  return {
    name: 'mock-alias',
    enforce: 'pre',
    resolveId(source, importer) {
      if (!importer || importer.includes('node_modules')) return null

      const resolved = path.resolve(path.dirname(importer), source)

      if (isMockDev && resolved === nativeBridgePath) {
        return mockBridgePath
      }
      if (resolved === mockServicePath) {
        return isMockDev ? mockServiceImplPath : releaseMockServicePath
      }
      return null
    }
  }
}

export default defineConfig(({ command }) => {
  const isMockDev = process.env.MOCK_DEV === 'true' && command === 'serve'

  return {
    plugins: [mockAliasPlugin(command), uni()],
    server: {
      host: '0.0.0.0',
      port: 5173,
    },
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    // mock 模式：注入全局常量供 main.js 运行时检测
    define: {
      __CARD_MOCK_DEV__: JSON.stringify(isMockDev),
    },
    // mock 模式下排除 mqtt (Node.js → browser) 的预构建
    ...(isMockDev ? {
      optimizeDeps: {
        exclude: ['mqtt']
      }
    } : {}),
  }
})
