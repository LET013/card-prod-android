export const SLOT_OPERATION_EFFECT = Object.freeze({
  SUCCESS: 'success',
  FAILURE: 'failure'
})

const SUCCESS_STATES = new Set(['PHYSICAL_CONFIRMED'])
const FAILURE_STATES = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED'])

export function resolveSlotOperationEffect(state) {
  const normalized = String(state || '').trim().toUpperCase()
  if (SUCCESS_STATES.has(normalized)) return SLOT_OPERATION_EFFECT.SUCCESS
  if (FAILURE_STATES.has(normalized)) return SLOT_OPERATION_EFFECT.FAILURE
  return ''
}

export function isTerminalSlotOperationEffect(effect) {
  return effect === SLOT_OPERATION_EFFECT.SUCCESS || effect === SLOT_OPERATION_EFFECT.FAILURE
}
