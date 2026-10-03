import {describe,it,expect} from 'vitest'
import {siteTime,siteTimeToUtc} from '@/components/aps/shared/site-time'
describe('站点时间与UTC边界',()=>{
  it('不继承本机时区并正确跨日',()=>{expect(siteTimeToUtc('2026-10-01 08:00:00','Asia/Shanghai')).toBe('2026-10-01T00:00:00.000Z');expect(siteTime('2026-09-30T20:30:00Z','Asia/Shanghai')).toBe('2026-10-01 04:30:00')})
  it('拒绝不存在日期及夏令时空档和重复时刻',()=>{expect(()=>siteTimeToUtc('2026-02-30 08:00:00','Asia/Shanghai')).toThrow();expect(()=>siteTimeToUtc('2026-03-08 02:30:00','America/New_York')).toThrow(/不存在/);expect(()=>siteTimeToUtc('2026-11-01 01:30:00','America/New_York')).toThrow(/重复/)})
})
