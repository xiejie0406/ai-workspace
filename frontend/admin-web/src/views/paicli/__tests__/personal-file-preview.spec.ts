import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PcPersonalFile, PcPersonalFilePreview } from '@/types/paicli'
const mocks=vi.hoisted(()=>({permissions:['paicli:artifact:read'],request:vi.fn()}))
vi.mock('@/store/modules/user',()=>({default:()=>({permissions:mocks.permissions,roles:['ordinary']})}))
vi.mock('@/utils/request',()=>({default:mocks.request}))
import hasPermi from '@/directive/permission/hasPermi'
import PersonalFilePreview from '../components/PersonalFilePreview.vue'
const global={directives:{hasPermi},stubs:{
  ElButton:{props:['disabled','loading'],emits:['click'],template:'<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'},
  ElDrawer:{props:['modelValue','title'],emits:['update:modelValue'],template:'<div v-if="modelValue" role="dialog"><h2>{{title}}</h2><button @click="$emit(\'update:modelValue\',false)">关闭</button><slot /></div>'},
  ElAlert:{props:['title'],template:'<p role="alert">{{title}}</p>'},
}}
const file:PcPersonalFile={id:'31',fileName:'中文资料.txt',purpose:'USER_FILE',sha256:'a'.repeat(64),size:12,contentType:'text/plain',supportedAnalysis:true}
function value(text='甲😀',offset=0,nextOffset=7,more=true,source=file):PcPersonalFilePreview{return {artifactId:source.id,fileName:source.fileName,purpose:'USER_FILE',sha256:source.sha256,size:source.size,offset,nextOffset,hasMore:more,text}}
function deferred(){let resolve!:(result:{code:number;data:PcPersonalFilePreview})=>void;const promise=new Promise<{code:number;data:PcPersonalFilePreview}>(ok=>{resolve=ok});return {promise,resolve}}
type PreviewVm={start:()=>Promise<void>;next:()=>Promise<void>;open:boolean}
const make=(source=file)=>mount(PersonalFilePreview,{props:{file:source},global})
describe('本人文件只读预览',()=>{
  beforeEach(()=>{mocks.permissions=['paicli:artifact:read'];mocks.request.mockReset()})
  it('分页、回退及重读均只GET真实ID，不创建会话、任务或发送分析',async()=>{
    mocks.request.mockResolvedValueOnce({data:value()}).mockResolvedValueOnce({data:value('乙\r\n',7,12,false)}).mockResolvedValue({data:value()})
    const w=make();await w.get('button').trigger('click');await flushPromises()
    expect(w.get('pre').text()).toBe('甲😀')
    await w.findAll('button').find(b=>b.text()==='下一段原文')!.trigger('click');await flushPromises()
    expect(w.get('pre').text()).toBe('乙');expect(w.text()).toContain('已到文件末尾')
    await w.findAll('button').find(b=>b.text()==='上一段原文')!.trigger('click');await flushPromises()
    await w.findAll('button').find(b=>b.text()==='从头重新读取')!.trigger('click');await flushPromises()
    expect(mocks.request.mock.calls.map(([c])=>c.params.offset)).toEqual([0,7,0,0])
    for(const [c] of mocks.request.mock.calls){expect(c).toMatchObject({url:'/paicli/v1/artifacts/31/preview-text',method:'get',params:{limit:65536},signal:expect.any(AbortSignal)});expect(c.params).not.toHaveProperty('sessionId');expect(c.params).not.toHaveProperty('directoryContextId')}
    expect(w.emitted('analyse')).toBeUndefined();w.unmount()
  })
  it('HTML和脚本按原文转义，不执行、不嵌入资源',async()=>{
    const text='<script>alert(1)</script><img src="https://example.invalid/pixel">',source={...file,size:new TextEncoder().encode(text).length}
    mocks.request.mockResolvedValue({data:value(text,0,source.size,false,source)})
    const w=make(source);await w.get('button').trigger('click');await flushPromises()
    expect(w.get('pre').text()).toBe(text);expect(w.find('script').exists()).toBe(false);expect(w.find('img').exists()).toBe(false);w.unmount()
  })
  it('无读取权限时隐藏入口，程序调用也不派发请求',async()=>{
    mocks.permissions=[];const w=make();await(w.vm as unknown as PreviewVm).start();expect(w.find('button').exists()).toBe(false);expect(mocks.request).not.toHaveBeenCalled();w.unmount()
  })
  it('非USER_FILE不调用个人预览，原产物不借此绕会话门禁',async()=>{
    const w=make({...file,purpose:'TOOL_OUTPUT'});await(w.vm as unknown as PreviewVm).start();expect(w.find('button').exists()).toBe(false);expect(mocks.request).not.toHaveBeenCalled();w.unmount()
  })
  it.each([
    {name:'错误ID',patch:{artifactId:'other'}},
    {name:'摘要变化',patch:{sha256:'b'.repeat(64)}},
    {name:'长度变化',patch:{size:13}},
    {name:'错误用途',patch:{purpose:'TOOL_OUTPUT'}},
    {name:'跨页文本字节不符',patch:{nextOffset:9}},
    {name:'错误末尾标记',patch:{hasMore:true}},
  ])('$name 时不把回执作为本人原文，保留已读页',async({patch})=>{
    mocks.request.mockResolvedValueOnce({data:value()}).mockResolvedValueOnce({data:{...value('乙\r\n',7,12,false),...patch}})
    const w=make();await w.get('button').trigger('click');await flushPromises();await(w.vm as unknown as PreviewVm).next();await flushPromises()
    expect(w.get('pre').text()).toBe('甲😀');expect(w.get('[role=alert]').text()).toContain('与列表不一致');w.unmount()
  })
  it('下一页失败保留原文，重试成功后清除错误',async()=>{
    mocks.request.mockResolvedValueOnce({data:value()}).mockRejectedValueOnce(new Error('文件已过期，不能读取')).mockResolvedValueOnce({data:value('乙\r\n',7,12,false)})
    const w=make();await w.get('button').trigger('click');await flushPromises();await(w.vm as unknown as PreviewVm).next();await flushPromises()
    expect(w.get('pre').text()).toBe('甲😀');expect(w.text()).toContain('文件已过期');await(w.vm as unknown as PreviewVm).next();await flushPromises();expect(w.find('[role=alert]').exists()).toBe(false);expect(w.text()).toContain('已到文件末尾');w.unmount()
  })
  it('切文件取消旧请求，旧响应不覆盖新文件',async()=>{
    const old=deferred(),fresh={...file,id:'32',fileName:'新文件.txt',sha256:'c'.repeat(64)}
    mocks.request.mockReturnValueOnce(old.promise).mockResolvedValueOnce({data:value('甲😀',0,7,true,fresh)})
    const w=make();await w.get('button').trigger('click');const signal=mocks.request.mock.calls[0][0].signal
    await w.setProps({file:fresh});expect(signal.aborted).toBe(true);await w.get('button').trigger('click');await flushPromises()
    old.resolve({code:200,data:value()});await flushPromises();expect(w.text()).toContain('新文件.txt');expect(w.text()).not.toContain('中文资料.txt');expect(w.get('pre').text()).toBe('甲😀');w.unmount()
  })
  it('关闭抽屉取消读取并清除原文，晚到响应不重开',async()=>{
    const pending=deferred();mocks.request.mockReturnValue(pending.promise);const w=make();await w.get('button').trigger('click');const signal=mocks.request.mock.calls[0][0].signal
    await w.findAll('button').find(b=>b.text()==='关闭')!.trigger('click');expect(signal.aborted).toBe(true);pending.resolve({code:200,data:value()});await flushPromises();expect(w.find('[role=dialog]').exists()).toBe(false);expect(w.find('pre').exists()).toBe(false);w.unmount()
  })
})
