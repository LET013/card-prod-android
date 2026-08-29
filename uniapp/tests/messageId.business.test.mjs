import { describe, it } from 'node:test'
import assert from 'node:assert'
import { createUniqueMessageId } from '../src/services/messageId.js'

describe('createUniqueMessageId', () => {
  it('generates unique ids with the requested prefix', () => {
    const ids = new Set()
    for (let i = 0; i < 1000; i += 1) {
      const id = createUniqueMessageId('status')
      assert.ok(id.startsWith('status_'), `expected status prefix, got ${id}`)
      assert.ok(!ids.has(id), `duplicate id generated: ${id}`)
      ids.add(id)
    }
    assert.strictEqual(ids.size, 1000)
  })

  it('produces a diagnostic style id when given a compound prefix', () => {
    const prefix = 'diagnostic_hardware_fault'
    const id = createUniqueMessageId(prefix)
    assert.ok(id.startsWith(`${prefix}_`), `expected prefix, got ${id}`)
    const suffix = id.slice(prefix.length + 1)
    const parts = suffix.split('_')
    assert.strictEqual(parts.length, 3, `expected 3 suffix parts, got ${suffix}`)
    const [time, random, seq] = parts
    assert.ok(/^\d+$/.test(time), `time part malformed: ${time}`)
    assert.ok(/^[0-9a-f]{16}$/.test(random), `random part malformed: ${random}`)
    assert.ok(/^[0-9a-f]{6}$/.test(seq), `seq part malformed: ${seq}`)
  })

  it('generates ids without a prefix', () => {
    const id = createUniqueMessageId()
    const parts = id.split('_')
    assert.strictEqual(parts.length, 3)
    assert.ok(/^\d+$/.test(parts[0]))
    assert.ok(/^[0-9a-f]{16}$/.test(parts[1]))
    assert.ok(/^[0-9a-f]{6}$/.test(parts[2]))
  })
})
