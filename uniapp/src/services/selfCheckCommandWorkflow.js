export function createSelfCheckCommandWorkflow({
  findResponse,
  markProcessing,
  runSelfCheck,
  reportSelfCheck,
  recordResult,
  sendResponse
} = {}) {
  const dependencies = [findResponse, markProcessing, runSelfCheck, reportSelfCheck, recordResult, sendResponse]
  if (dependencies.some((dependency) => typeof dependency !== 'function')) {
    throw new Error('self check command workflow dependencies are incomplete')
  }

  const inFlight = new Map()

  const execute = async (msgId) => {
    const existing = await findResponse(msgId)
    if (existing?.payload?.data != null) {
      const reused = await sendResponse(existing.payload.data, msgId)
      return {
        responded: reused.sent,
        queued: reused.queued === true,
        reused: true,
        msgId,
        data: existing.payload.data
      }
    }

    await markProcessing(msgId)
    let responseData
    try {
      const report = await runSelfCheck()
      const delivery = await reportSelfCheck(report)
      responseData = {
        code: 0,
        msg: delivery?.queued === true ? '自检完成，报告待补传' : 'success',
        result: report.result,
        details: report.details
      }
      await recordResult({
        msgId,
        report,
        delivery,
        state: report.result === 'pass' ? 'COMPLETED' : 'FAILED'
      })
    } catch (error) {
      responseData = { code: 500, msg: error?.message || '设备自检执行失败' }
      await recordResult({
        msgId,
        report: null,
        delivery: null,
        state: 'FAILED',
        error
      }).catch(() => {})
    }

    const sent = await sendResponse(responseData, msgId)
    return {
      responded: sent.sent,
      queued: sent.queued === true,
      msgId: sent.msgId || msgId,
      data: responseData
    }
  }

  return async function handleSelfCheckCommand(message = {}) {
    if (String(message.cmd || '').trim() !== 'deviceSelfCheck') {
      return { responded: false, reason: 'UNHANDLED_COMMAND' }
    }
    const msgId = String(message.msgId || '').trim()
    if (!msgId) return { responded: false, reason: 'MISSING_MSG_ID' }
    if (inFlight.has(msgId)) return inFlight.get(msgId)

    const promise = execute(msgId)
    inFlight.set(msgId, promise)
    try {
      return await promise
    } finally {
      inFlight.delete(msgId)
    }
  }
}
