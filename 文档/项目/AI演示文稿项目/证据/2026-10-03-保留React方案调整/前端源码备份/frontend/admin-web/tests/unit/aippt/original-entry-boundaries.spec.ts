import {beforeEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ChartDataEditor from '@/views/aippt/editor/ChartDataEditor.vue'
import ChartView from '@/views/aippt/editor/ChartView.vue'
import SourceList from '@/views/aippt/components/SourceList.vue'
import type {ChartBlock,Theme} from '@/views/aippt/render/types'

const mocks=vi.hoisted(()=>({permissions:new Set<string>(),project:vi.fn(),uploadSource:vi.fn(),addSource:vi.fn(),sections:vi.fn(),setOption:vi.fn(),resize:vi.fn(),dispose:vi.fn()}))
vi.mock('@/utils/permission',()=>({checkPermi:(permissions:string[])=>permissions.some(p=>mocks.permissions.has(p))}))
vi.mock('@/api/aippt',()=>({api:mocks}))
vi.mock('@/store/modules/user',()=>({default:()=>({token:'isolated-test'})}))
vi.mock('echarts',()=>({init:()=>({setOption:mocks.setOption,resize:mocks.resize,dispose:mocks.dispose})}))
const global={plugins:[ElementPlus],directives:{hasPermi:()=>{}}}
const chart=(type:ChartBlock['chart_type']='pie'):ChartBlock=>({id:'c',type:'chart',slot_id:'chart',locked:false,chart_type:type,categories:['甲','乙'],series:[{name:'收入',values:[10,20]},{name:'成本',values:[30,40]}],unit:'万元'})
const button=(wrapper:VueWrapper,text:string)=>wrapper.findAll('button').find(b=>b.text()===text)!
beforeEach(()=>{mocks.permissions=new Set(['aippt:source:add']);mocks.project.mockResolvedValue({id:'p',input_revision:7});mocks.uploadSource.mockResolvedValue({id:'new-source',parse_status:'queued'});vi.stubGlobal('ResizeObserver',class{observe(){}disconnect(){}})})

describe('原四图表入口合法多系列边界',()=>{
 it('合法双系列柱图改pie后按原数据保留全部系列，切回column仍保留',async()=>{const wrapper=mount(ChartDataEditor,{props:{block:chart('bar')},global});await wrapper.findComponent({name:'ElSelect'}).setValue('pie');await button(wrapper,'保存图表数据').trigger('click');expect(wrapper.emitted('change')?.[0][0]).toEqual({type:'chart',chart_type:'pie',categories:['甲','乙'],series:chart().series,unit:'万元'});await wrapper.setProps({block:{...chart(),chart_type:'column'}});await button(wrapper,'保存图表数据').trigger('click');expect(wrapper.emitted('change')?.[1][0]).toMatchObject({chart_type:'column',series:chart().series});wrapper.unmount()})
 it('pie增加第三系列入口可用，新值按分类对齐，原两个系列不丢失',async()=>{const wrapper=mount(ChartDataEditor,{props:{block:chart()},global});expect(button(wrapper,'增加系列').attributes('disabled')).toBeUndefined();await button(wrapper,'增加系列').trigger('click');await wrapper.find('input[aria-label="系列名称 3"]').setValue('利润');await button(wrapper,'保存图表数据').trigger('click');expect(wrapper.emitted('change')?.[0][0]).toMatchObject({series:[...chart().series,{name:'利润',values:[0,0]}]});wrapper.unmount()})
 it.each(['dimension','nonfinite'])('非法%s依旧拒绝且不发保存意图',async mode=>{const block=chart();block.series[1].values=mode==='dimension'?[30]:[Number.NaN,40];const wrapper=mount(ChartDataEditor,{props:{block},global});await button(wrapper,'保存图表数据').trigger('click');expect(wrapper.emitted('change')).toBeUndefined();expect(wrapper.text()).toContain('分类与系列数量需一致，数值必须有效');wrapper.unmount()})
 it('只读保留预览数据但实际表单/增加/保存仍禁用',async()=>{const wrapper=mount(ChartDataEditor,{props:{block:chart(),disabled:true},global});expect(button(wrapper,'增加系列').attributes('disabled')).toBeDefined();expect(button(wrapper,'保存图表数据').attributes('disabled')).toBeDefined();expect(wrapper.find('input[aria-label="系列名称 1"]').attributes('disabled')).toBeDefined();wrapper.unmount()})
 it('pie预览沿原React只绘首系列，column预览绘全部系列，数据本体保留',async()=>{const block=chart(),theme={palette:{chart_series:['#123456','#789abc'],ink:'#111111',ink_soft:'#555555',ink_muted:'#777777'},fonts:{body:{web:'sans-serif'}}} as Theme;const wrapper=mount(ChartView,{props:{block,theme}});expect(mocks.setOption.mock.calls.at(-1)?.[0].series).toEqual([{name:'收入',type:'pie',radius:'68%',data:[{name:'甲',value:10},{name:'乙',value:20}],label:{color:'#111111'}}]);await wrapper.setProps({block:{...block,chart_type:'column'}});expect(mocks.setOption.mock.calls.at(-1)?.[0].series).toEqual(block.series.map(s=>({name:s.name,type:'bar',data:s.values,label:{show:false}})));expect(block.series).toHaveLength(2);wrapper.unmount();expect(mocks.dispose).toHaveBeenCalledOnce()})
})

describe('真实添加材料权限与补传文档入口',()=>{
 const props={projectId:'p',inputRevision:3,sources:[],locked:false}
 it('只授source:add即可补传，实际File使用最新project revision与幂等键',async()=>{const wrapper=mount(SourceList,{props,global});expect(wrapper.find('input[type="file"]').exists()).toBe(true);const input=wrapper.find('input[type="file"]'),file=new File(['独立材料'],'材料.txt',{type:'text/plain'});Object.defineProperty(input.element,'files',{value:[file],configurable:true});await input.trigger('change');await flushPromises();expect(mocks.uploadSource).toHaveBeenCalledWith('p',file,7,expect.any(String));expect(mocks.uploadSource.mock.calls[0][3]).toMatch(/^[\da-f-]{36}$/);expect(wrapper.emitted('changed')).toHaveLength(1);wrapper.unmount()})
 it('未知上传回执失败保留文件，重试同File/revision/key且不重新读项目',async()=>{const wrapper=mount(SourceList,{props,global}),input=wrapper.find('input[type="file"]'),file=new File(['材料'],'保留.txt',{type:'text/plain'});Object.defineProperty(input.element,'files',{value:[file],configurable:true});mocks.uploadSource.mockRejectedValueOnce(new Error('回执断网'));await input.trigger('change');await flushPromises();expect(wrapper.text()).toContain('等待重试');await button(wrapper,'重试失败文件').trigger('click');await flushPromises();expect(mocks.uploadSource.mock.calls[1]).toEqual(mocks.uploadSource.mock.calls[0]);expect(mocks.project).toHaveBeenCalledOnce();expect(wrapper.text()).not.toContain('等待重试');wrapper.unmount()})
 it.each(['readonly','locked'])('%s不能补传或添加材料',async mode=>{if(mode==='readonly')mocks.permissions=new Set(['aippt:source:query']);const wrapper=mount(SourceList,{props:{...props,locked:mode==='locked'},global});expect(wrapper.find('input[type="file"]').exists()).toBe(false);expect(wrapper.find('textarea').exists()).toBe(false);expect(mocks.uploadSource).not.toHaveBeenCalled();wrapper.unmount()})
})
