import {mount} from '@vue/test-utils'
import {describe,expect,it} from 'vitest'
import type {PcMemory} from '@/types/paicli'
import MemoryVerification from '../components/MemoryVerification.vue'
import {isMemoryStale,memoryVerificationTime} from '../shared/memoryFreshness'
const verified='2026-09-01T08:00:00Z',boundary=Date.parse(verified)+30*24*60*60*1000
const memory={id:'9',version:3,status:'VERIFIED',lastVerifiedAt:verified,content:'项目事实'} as PcMemory
const global={stubs:{ElTag:{template:'<span><slot /></span>'},ElAlert:{props:['title'],template:'<p role="alert">{{title}}</p>'}}}
describe('记忆过时提醒保持真实核实状态',()=>{
  it('恰好30天不提醒，超过1毫秒才提醒；组件同时保留已核实状态与版本',async()=>{const wrapper=mount(MemoryVerification,{props:{memory,now:boundary},global});expect(wrapper.find('[role="alert"]').exists()).toBe(false);await wrapper.setProps({now:boundary+1});expect(wrapper.get('[role="alert"]').text()).toContain('可能已过时');expect(wrapper.text()).toContain('已核实');expect(wrapper.text()).toContain('版本 3');expect(memory.status).toBe('VERIFIED');wrapper.unmount()})
  it('31天未核实和CONFLICT不伪装成已核实过时状态，未来/非法时间不判过时',()=>{for(const status of ['PENDING','CONFLICT'] as const){const row={...memory,status};const wrapper=mount(MemoryVerification,{props:{memory:row,now:boundary+86400000},global});expect(wrapper.find('[role="alert"]').exists()).toBe(false);expect(isMemoryStale(row,boundary+86400000)).toBe(false);wrapper.unmount()}expect(isMemoryStale({...memory,lastVerifiedAt:'invalid'},boundary)).toBe(false);expect(isMemoryStale({...memory,lastVerifiedAt:'2027-01-01T00:00:00Z'},boundary)).toBe(false)})
  it('最近verify的verifiedAt优先于旧lastVerifiedAt；已核实未知时间明确提示核对',()=>{const row={...memory,verifiedAt:'2026-10-01T08:00:00Z'};expect(memoryVerificationTime(row)).toBe(row.verifiedAt);expect(isMemoryStale(row,boundary+86400000)).toBe(false);const wrapper=mount(MemoryVerification,{props:{memory:{...memory,lastVerifiedAt:undefined},now:boundary},global});expect(wrapper.text()).toContain('核实时间未上报');expect(wrapper.find('[role="alert"]').exists()).toBe(false);wrapper.unmount()})
})
