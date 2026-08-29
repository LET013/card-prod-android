export const COLORS = {
  primary: '#1F76FF',
  primaryDark: '#0A53C4',
  pageLight: '#E6F0FF',
  success: '#16A34A',
  occupied: '#F97316',
  charging: '#EF4444',
  chargingFault: '#64748B',
  communicationFault: '#475569',
  illegal: '#334155',
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
  LOADING: 'LOADING',
  UNKNOWN: 'UNKNOWN'
}

export const SLOT_STATUS_META = {
  EMPTY: {
    label: '空卡',
    color: '#E7EBF0',
    gradient: 'linear-gradient(145deg, #FAFBFC 0%, #E7EBF0 55%, #D5DCE5 100%)',
    border: 'rgba(148, 163, 184, .42)',
    shadow: '0 4px 10px rgba(100, 116, 139, .14)',
    text: '#3F4A5A'
  },
  OCCUPIED: {
    label: '充电结束',
    color: '#F97316',
    gradient: 'linear-gradient(145deg, #FFAA4C 0%, #F97316 52%, #C2410C 100%)',
    border: 'rgba(254, 215, 170, .46)',
    shadow: '0 5px 12px rgba(249, 115, 22, .24)',
    text: '#FFFFFF'
  },
  CHARGING: {
    label: '充电中',
    color: '#EF4444',
    gradient: 'linear-gradient(145deg, #FF6B6B 0%, #EF4444 52%, #C92A2A 100%)',
    border: 'rgba(254, 202, 202, .46)',
    shadow: '0 5px 12px rgba(239, 68, 68, .23)',
    text: '#FFFFFF'
  },
  FULL: {
    label: '已充满',
    color: '#16A34A',
    gradient: 'linear-gradient(145deg, #3DDC78 0%, #16A34A 52%, #0B7A3D 100%)',
    border: 'rgba(134, 239, 172, .46)',
    shadow: '0 5px 12px rgba(22, 163, 74, .23)',
    text: '#FFFFFF'
  },
  CHARGING_FAULT: {
    label: '充电故障',
    color: '#64748B',
    gradient: 'linear-gradient(145deg, #94A3B8 0%, #64748B 54%, #475569 100%)',
    border: 'rgba(203, 213, 225, .46)',
    shadow: '0 5px 12px rgba(71, 85, 105, .2)',
    text: '#FFFFFF'
  },
  COMMUNICATION_FAULT: {
    label: '通信故障',
    color: '#475569',
    gradient: 'linear-gradient(145deg, #7C899B 0%, #475569 54%, #334155 100%)',
    border: 'rgba(148, 163, 184, .42)',
    shadow: '0 5px 12px rgba(51, 65, 85, .21)',
    text: '#FFFFFF'
  },
  ILLEGAL_CARD: {
    label: '非法卡',
    color: '#334155',
    gradient: 'linear-gradient(145deg, #64748B 0%, #334155 55%, #1E293B 100%)',
    border: 'rgba(148, 163, 184, .35)',
    shadow: '0 5px 12px rgba(17, 24, 39, .22)',
    text: '#FFFFFF'
  },
  OFFLINE: {
    label: '离线',
    color: '#8B98A9',
    gradient: 'linear-gradient(145deg, #AEB9C8 0%, #8B98A9 55%, #667488 100%)',
    border: 'rgba(203, 213, 225, .45)',
    shadow: '0 5px 12px rgba(100, 116, 139, .18)',
    text: '#FFFFFF'
  },
  LOADING: {
    label: '加载中',
    color: '#D7DEE8',
    gradient: 'linear-gradient(145deg, #F4F7FA 0%, #E6ECF3 55%, #D7DEE8 100%)',
    border: 'rgba(148, 163, 184, .28)',
    shadow: '0 4px 10px rgba(100, 116, 139, .1)',
    text: '#64748B'
  },
  UNKNOWN: {
    label: '未知',
    color: '#CBD4E0',
    gradient: 'linear-gradient(145deg, #E2E8F0 0%, #CBD4E0 55%, #AAB7C8 100%)',
    border: 'rgba(148, 163, 184, .36)',
    shadow: '0 4px 10px rgba(100, 116, 139, .16)',
    text: '#30343B'
  }
}

export const ROLE = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  SYSTEM_ADMIN: 'SYSTEM_ADMIN',
  OPS: 'OPS',
  DEVELOPER: 'DEVELOPER'
}

export const ROLE_META = {
  SUPER_ADMIN: { label: '超级管理员', color: '#F04A55' },
  SYSTEM_ADMIN: { label: '系统管理员', color: '#F04A55' },
  OPS: { label: '运维人员', color: '#FF9829' },
  DEVELOPER: { label: '开发人员', color: '#5965E9' }
}

export const PERMISSIONS = {
  SUPER_ADMIN: ['*'],
  SYSTEM_ADMIN: ['system.*', 'realtime.*'],
  OPS: ['maintenance.*', 'realtime.*'],
  DEVELOPER: ['*']
}

export const hasPermission = (session, permission) => {
  if (!session || !permission) return false
  const granted = session.permissions instanceof Set
    ? Array.from(session.permissions)
    : (Array.isArray(session.permissions) ? session.permissions : null)
  if (granted) {
    if (granted.includes('*') || granted.includes(permission)) return true
    return String(permission).endsWith('.*') && granted.some((key) => String(key).startsWith(String(permission).slice(0, -1)))
  }
  if (!session.role) return false
  return (PERMISSIONS[session.role] || []).includes(permission)
}
