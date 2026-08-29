/**
 * Mock Dev 全局配置
 *
 * 仅 npm run dev:mock 时加载，不进入生产构建。
 */

/** 后端服务器地址（默认测试环境） */
export const SERVER_URL = 'http://card-test.quyohui.com'

/** MQTT WebSocket 地址（硬编码，config 接口不返回 ws 地址） */
export const MQTT_WS_URL = 'ws://card-test.quyohui.com/mqtt'

/** 默认 HTTP 端口 */
export const HTTP_PORT = 8800

/** MQTT 默认端口 */
export const MQTT_PORT = 8083

/** 心跳间隔 (ms) */
export const HEARTBEAT_INTERVAL = 60000

/** Bootstrap 各阶段超时 (ms) */
export const BOOTSTRAP_TIMEOUT = 15000

/** 人脸服务 */
export const FACE_MODE = 'nodejs' // 'nodejs' | 'overlay'
export const FACE_SERVICE_URL = 'http://card-test.quyohui.com/faceapi' // 已部署到服务器
export const FACE_TIMEOUT = 60000

/** 串口模拟 */
export const SERIAL_POLL_INTERVAL = 10000 // ms，卡槽状态轮询间隔
export const SERIAL_RANDOM_CHANGE_CHANCE = 0.15 // 每次轮询随机变更概率

/** 默认请求超时 (ms) */
export const DEFAULT_TIMEOUT = 30000

/** 调试日志 */
export const DEBUG = true

export function log(tag, ...args) {
  if (DEBUG) console.log(`[Mock:${tag}]`, ...args)
}

export function warn(tag, ...args) {
  console.warn(`[Mock:${tag}]`, ...args)
}

export function error(tag, ...args) {
  console.error(`[Mock:${tag}]`, ...args)
}
