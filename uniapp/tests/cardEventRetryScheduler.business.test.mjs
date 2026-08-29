import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { createCardEventRetryScheduler } from '../src/services/cardEventRetryScheduler.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function createFakeTimers() {
  const callbacks = new Map()
  let nextId = 1
  return {
    callbacks,
    setTimer(callback) {
      const id = nextId
      nextId += 1
      callbacks.set(id, callback)
      return id
    },
    clearTimer(id) {
      callbacks.delete(id)
    },
    async runNext() {
      const entry = callbacks.entries().next().value
      assert.ok(entry, 'expected one scheduled retry')
      const [id, callback] = entry
      callbacks.delete(id)
      callback()
      await Promise.resolve()
      await Promise.resolve()
    }
  }
}

test('coalesces repeated retry requests into one scheduled flush', async () => {
  const timers = createFakeTimers()
  const calls = []
  const scheduler = createCardEventRetryScheduler({
    flush: async (limit, reason) => {
      calls.push({ limit, reason })
      return { total: 1, sent: 1, failed: 0 }
    },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer
  })

  assert.equal(scheduler.schedule('take-failed').reused, undefined)
  assert.equal(scheduler.schedule('network-failed').reused, true)
  assert.equal(timers.callbacks.size, 1)

  await timers.runNext()

  assert.deepEqual(calls, [{ limit: 20, reason: 'scheduled:take-failed' }])
  assert.equal(scheduler.isScheduled(), false)
})

test('coalesces concurrent flushes and schedules another attempt when events still fail', async () => {
  const timers = createFakeTimers()
  let release
  let flushCount = 0
  const gate = new Promise((resolve) => { release = resolve })
  const scheduler = createCardEventRetryScheduler({
    flush: async () => {
      flushCount += 1
      await gate
      return { total: 1, sent: 0, failed: 1 }
    },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer
  })

  const first = scheduler.flushNow('startup', 10)
  const second = scheduler.flushNow('native.ready', 50)
  release()
  const [firstResult, secondResult] = await Promise.all([first, second])

  assert.equal(flushCount, 1)
  assert.deepEqual(secondResult, firstResult)
  assert.equal(timers.callbacks.size, 1)
})

test('keeps a retry scheduled when an immediate flush throws', async () => {
  const timers = createFakeTimers()
  const scheduler = createCardEventRetryScheduler({
    flush: async () => { throw new Error('offline') },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer
  })

  await assert.rejects(scheduler.flushNow('native.ready'), /offline/)

  assert.equal(scheduler.isScheduled(), true)
  assert.equal(timers.callbacks.size, 1)
  scheduler.cancel()
  assert.equal(timers.callbacks.size, 0)
})

test('does not reschedule an in-flight failure after the scheduler is stopped', async () => {
  const timers = createFakeTimers()
  let rejectFlush
  const scheduler = createCardEventRetryScheduler({
    flush: () => new Promise((resolve, reject) => { rejectFlush = reject }),
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer
  })

  const pending = scheduler.flushNow('shutdown-race')
  await Promise.resolve()
  scheduler.cancel()
  rejectFlush(new Error('late failure'))

  await assert.rejects(pending, /late failure/)
  assert.equal(scheduler.isScheduled(), false)
  assert.equal(timers.callbacks.size, 0)
})

test('wires retry scheduling only after durable card-event handoff and at recovery points', async () => {
  const [serviceSource, mainSource] = await Promise.all([
    readFile(path.join(projectRoot, 'src/services/index.js'), 'utf8'),
    readFile(path.join(projectRoot, 'src/main.js'), 'utf8')
  ])

  assert.match(serviceSource, /if \(result\?\.reportPending === true\) schedulePendingCardEventFlush\('report-pending'\)/)
  assert.match(serviceSource, /getTakeCardWorkflow\(\)\.flushPendingReports\(limit\)/)
  assert.doesNotMatch(serviceSource, /schedulePendingCardEventFlush\([^\n]+SERIAL_SENT/)
  assert.match(mainSource, /schedulePendingCardEventFlush\('startup'\)/)
  assert.match(mainSource, /flushPendingCardEvents\(20, 'native\.ready'\)/)
  assert.match(mainSource, /flushPendingCardEvents\(20, reason\)/)
})
