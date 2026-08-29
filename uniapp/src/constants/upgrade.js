const APP_UPGRADE_BLOCKER = Object.freeze({
  available: true,
  code: 'APP_UPGRADE_AVAILABLE',
  title: 'APP 升级',
  message: '按 V4.2 检查新版本，下载后校验文件大小、MD5、包名和版本号，再交由 Android 系统安装器确认安装。'
})

const BOARD_FIRMWARE_UPGRADE = Object.freeze({
  available: true,
  code: 'BOARD_FIRMWARE_UPGRADE_AVAILABLE',
  title: '单板升级',
  message: '当前没有待处理的升级任务'
})

const WORK_CARD_UPGRADE_BLOCKER = Object.freeze({
  available: false,
  code: 'FIRMWARE_TARGET_CONTRACT_MISSING',
  title: '工作卡升级',
  message: '当前暂不支持工作卡升级'
})

const MAIN_BOARD_UPGRADE_BLOCKER = Object.freeze({
  available: false,
  code: 'FIRMWARE_TARGET_CONTRACT_MISSING',
  title: '主板升级',
  message: '当前暂不支持主板升级'
})

const CAPABILITIES = Object.freeze({
  app: APP_UPGRADE_BLOCKER,
  board: BOARD_FIRMWARE_UPGRADE,
  workCard: WORK_CARD_UPGRADE_BLOCKER,
  mainBoard: MAIN_BOARD_UPGRADE_BLOCKER
})

export function resolveUpgradeCapability(kind) {
  return CAPABILITIES[kind] || WORK_CARD_UPGRADE_BLOCKER
}
