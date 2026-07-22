export const COLORS = {
  primary: '#1F76FF',
  primaryDark: '#0A53C4',
  pageLight: '#E6F0FF',
  success: '#16A34A',
  occupied: '#0FAF8F',
  charging: '#F59E0B',
  chargingFault: '#DC2626',
  communicationFault: '#8B98A9',
  illegal: '#FACC15',
  error: '#EF1010',
  text: '#30343B'
}

export const SLOT_STATUS = {
  EMPTY: 'EMPTY',
  OCCUPIED: 'OCCUPIED',
  CHARGING: 'CHARGING',
  FULL: 'FULL',
  CHARGING_FAULT: 'CHARGING_FAULT',
  COMMUNICATION_FAULT: 'COMMUNICATION_FAULT',
  ILLEGAL_CARD: 'ILLEGAL_CARD',
  OFFLINE: 'OFFLINE',
  UNKNOWN: 'UNKNOWN'
}

export const SLOT_STATUS_META = {
  EMPTY: { label: '空卡', color: '#16A34A', text: '#FFFFFF' },
  OCCUPIED: { label: '有卡', color: '#0FAF8F', text: '#FFFFFF' },
  CHARGING: { label: '充电中', color: '#F59E0B', text: '#FFFFFF' },
  FULL: { label: '已充满', color: '#1F76FF', text: '#FFFFFF' },
  CHARGING_FAULT: { label: '充电故障', color: '#DC2626', text: '#FFFFFF' },
  COMMUNICATION_FAULT: { label: '通信故障', color: '#8B98A9', text: '#FFFFFF' },
  ILLEGAL_CARD: { label: '非法卡', color: '#FACC15', text: '#30343B' },
  OFFLINE: { label: '离线', color: '#8B98A9', text: '#FFFFFF' },
  UNKNOWN: { label: '未知', color: '#CBD4E0', text: '#30343B' }
}

export const ROLE = {
  SYSTEM_ADMIN: 'SYSTEM_ADMIN',
  OPS: 'OPS',
  DEVELOPER: 'DEVELOPER'
}

export const ROLE_META = {
  SYSTEM_ADMIN: { label: '系统管理员', color: '#F04A55' },
  OPS: { label: '运维人员', color: '#FF9829' },
  DEVELOPER: { label: '开发人员', color: '#5965E9' }
}

export const PERMISSIONS = {
  SYSTEM_ADMIN: [
    'system.menu', 'management.menu', 'cabinet.view', 'cabinet.unlock', 'cabinet.unlockAll',
    'employee.view', 'employee.edit', 'biometric.register', 'history.view', 'unit.view',
    'settings.basic', 'settings.advanced', 'engine.activate', 'authorization.manage',
    'upgrade.app', 'upgrade.firmware', 'debug.command', 'auth.password.manage', 'app.restart'
  ],
  OPS: [
    'system.menu', 'management.menu', 'cabinet.view', 'cabinet.unlock', 'cabinet.unlockAll',
    'history.view', 'unit.view', 'settings.basic', 'upgrade.firmware', 'app.restart'
  ],
  DEVELOPER: [
    'system.menu', 'cabinet.view', 'cabinet.unlock', 'settings.basic', 'settings.advanced',
    'engine.activate', 'authorization.manage', 'upgrade.app', 'upgrade.firmware',
    'debug.command', 'app.restart'
  ]
}

export const hasPermission = (session, permission) => {
  if (!session?.role) return false
  return (session.permissions || PERMISSIONS[session.role] || []).includes(permission)
}
