import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parseWorkspaceBootstrap, parseTaskSnapshot } from '../../src/shared/contracts/operator-bootstrap'

const fixturePath = join(process.cwd(), '../../../contracts/aden/examples/current/operator/bootstrap-long-max-watermark.json')

describe('WorkspaceBootstrap runtime boundary', () => {
  it('accepts read-only collection task pairs without exposing synthetic commands', () => {
    const task = JSON.parse(readFileSync(fixturePath, 'utf8')).tasks.items[0]
    for (const taskType of ['JD_DETAIL_CAPTURE', 'MANUAL_COLLECTION_ENTRY']) {
      expect(parseTaskSnapshot({ ...task, taskType, capabilityCode: 'COL', allowedCommands: [] }).taskType).toBe(taskType)
      expect(() => parseTaskSnapshot({ ...task, taskType, capabilityCode: 'COL', allowedCommands: ['REQUEST_CANCEL'] })).toThrow('只读')
    }
  })
  it('accepts explicit restricted collection projection, never partial CORE capabilities', () => {
    const original = JSON.parse(readFileSync(fixturePath, 'utf8'))
    const restricted = { ...original, tasks: { ...original.tasks, items: [] }, runners: [], capabilities: [] }
    expect(parseWorkspaceBootstrap(restricted, true).capabilities).toEqual([])
    expect(() => parseWorkspaceBootstrap(restricted)).toThrow('四项')
    expect(() => parseWorkspaceBootstrap({ ...restricted, capabilities: original.capabilities.slice(0, 2) }, true)).toThrow('四项')
  })

  it('preserves int64 above 2^53 and Long.MAX_VALUE as strings', () => {
    const parsed = parseWorkspaceBootstrap(JSON.parse(readFileSync(fixturePath, 'utf8')))
    expect(parsed.tasks.items[0]?.version).toBe('9007199254740992')
    expect(parsed.streamWatermark).toBe('9223372036854775807')
    expect(typeof parsed.streamWatermark).toBe('string')
  })

  it('rejects number coercion, unknown fields and missing capability entries', () => {
    const original = JSON.parse(readFileSync(fixturePath, 'utf8'))
    expect(() => parseWorkspaceBootstrap({ ...original, streamWatermark: 9007199254740992 })).toThrow()
    expect(() => parseWorkspaceBootstrap({ ...original, injected: true })).toThrow(/未知字段/)
    expect(() => parseWorkspaceBootstrap({ ...original, capabilities: original.capabilities.slice(0, 3) })).toThrow(/四项/)
  })
})
