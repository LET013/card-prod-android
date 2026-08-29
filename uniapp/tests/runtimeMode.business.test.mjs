import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const readSource = (relativePath) => readFile(path.join(projectRoot, relativePath), 'utf8')

test('only explicit MOCK_DEV enables the Mock runtime', async () => {
  const source = await readSource('src/services/index.js')

  assert.match(source, /const isMockDev = typeof __CARD_MOCK_DEV__ !== 'undefined' && __CARD_MOCK_DEV__ === true/)
  assert.match(source, /const isRelease = !isMockDev/)
  assert.doesNotMatch(source, /window\.location\.hostname/)
  assert.match(source, /if \(isMockDev\) \{[\s\S]*createMockService\(\)/)
})

test('Vite injects MOCK_DEV only for the explicit development Mock server', async () => {
  const source = await readSource('vite.config.js')

  assert.match(source, /const isMockDev = process\.env\.MOCK_DEV === 'true' && command === 'serve'/)
  assert.match(source, /__CARD_MOCK_DEV__: JSON\.stringify\(isMockDev\)/)
  assert.match(source, /releaseMockServicePath/)
})

test('production projection imports neutral defaults instead of Mock data', async () => {
  const stateSource = await readSource('src/state/appState.js')
  const defaultsSource = await readSource('src/constants/runtimeDefaults.js')

  assert.match(stateSource, /@\/constants\/runtimeDefaults\.js/)
  assert.doesNotMatch(stateSource, /@\/mock\//)
  assert.match(defaultsSource, /state: 'UNKNOWN'/)
  assert.doesNotMatch(defaultsSource, /Mock 串口已连接|MOCK_DEVICE/)
})

test('production isolation blocks development Mock modules without rejecting native simulator copy', async () => {
  const source = await readSource('scripts/verify-no-mock.js')

  assert.match(source, /mock\[\/-\]\(bridge\|service\|data/)
  assert.match(source, /mockService\|MOCK_DEVICE/)
  assert.doesNotMatch(source, /MOCK_DEVICE\|模拟串口/)
})
