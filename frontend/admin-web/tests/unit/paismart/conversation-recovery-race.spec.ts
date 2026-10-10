import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import History from '@/views/paismart/history/index.vue'
import Chat from '@/views/paismart/chat/index.vue'
import { useChatStream } from '@/views/paismart/composables/useChatStream'

const mocks=vi.hoisted(()=>({listConversations:vi.fn(),conversationMessages:vi.fn(),conversationAction:vi.fn(),createConversation:vi.fn(),getActiveGeneration:vi.fn(),getGeneration:vi.fn(),createTicket:vi.fn(),myMembership:vi.fn()}))
vi.mock('@/api/paismart/conversations',()=>mocks)
vi.mock('@/api/paismart/chat',()=>({...mocks,sendFeedback:vi.fn()}))
vi.mock('@/api/paismart/spaces',()=>mocks)
vi.mock('@/api/paismart/client',()=>({newKey:()=> 'offline-key',pathId:String,call:vi.fn(),get:vi.fn(),list:vi.fn(),post:vi.fn(),put:vi.fn(),remove:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn()})}))
const slot={template:'<div><slot/><slot name="footer"/></div>'}
const global={stubs:{...Object.fromEntries(['el-form','el-form-item','el-empty','el-dialog','el-drawer','el-select','el-option','el-table'].map(name=>[name,slot])),'el-table-column':true,'el-alert':{props:['title'],template:'<div>{{title}}</div>'},'el-button':{props:['disabled'],emits:['click'],template:'<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'},'el-input':true,'el-date-picker':true,'el-pagination':true,ChatMessage:{props:['message'],template:'<article>{{message.content}}</article>'},ConversationList:true,ReferencePreview:true},directives:{loading:()=>{},hasPermi:()=>{}}}
const row=(id:string)=>({id,title:'会话 '+id,rowVersion:1,archived:false,spaceId:null})
const page=(id:string)=>({rows:[{id:'m-'+id,role:'assistant',content:'答案 '+id,references:[]}],total:1})
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(error:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
function streamComponent(done=vi.fn()){let stream!:ReturnType<typeof useChatStream>;const wrapper=mount(defineComponent({setup(){stream=useChatStream(done);return()=>null}}));return {stream,wrapper,done}}
class Socket {
  static OPEN=1;static instances:Socket[]=[];readyState=0;sent:string[]=[];onopen?:()=>void;onclose?:()=>void;onerror?:()=>void;onmessage?:({data}:{data:string})=>void
  constructor(_url:string){Socket.instances.push(this)}
  open(){this.readyState=1;this.onopen?.()}
  close(){this.readyState=3;this.onclose?.()}
  send(value:string){this.sent.push(value)}
  emit(value:unknown){this.onmessage?.({data:JSON.stringify(value)})}
}
beforeEach(()=>{vi.resetAllMocks();Socket.instances=[];vi.stubGlobal('WebSocket',Socket);mocks.listConversations.mockResolvedValue({rows:[],total:0});mocks.conversationMessages.mockImplementation((id:string)=>Promise.resolve(page(id)));mocks.conversationAction.mockImplementation((value)=>Promise.resolve({data:value}));mocks.getActiveGeneration.mockResolvedValue({data:null});mocks.myMembership.mockResolvedValue({data:{eligible:true,visibleSpaces:[]}});mocks.createTicket.mockResolvedValue({data:{ticket:'offline-ticket'}})})

describe('历史详情与恢复目标的会话归属',()=>{
  it.each(['成功','失败'])('历史 A 迟到%s不能覆盖后来选中的 B',async outcome=>{
    const first=deferred<unknown>();mocks.conversationMessages.mockImplementation((id:string)=>id==='A'?first.promise:Promise.resolve(page(id)))
    const wrapper=mount(History,{global}),vm=wrapper.vm as any;await flushPromises();const a=vm.view(row('A'));await flushPromises();await vm.view(row('B'));await flushPromises()
    if(outcome==='成功')first.resolve(page('A'));else first.reject(new Error('A 的旧错误'));await a;await flushPromises()
    expect(vm.selected.id).toBe('B');expect(vm.messages[0].content).toBe('答案 B');expect(vm.detailError).toBe('');expect(wrapper.text()).not.toContain('答案 A');wrapper.unmount()
  })
  it('从断线 A 切到 B 后不再提供 A 的恢复按钮',async()=>{
    const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('A'))
    vm.stream.generationId.value='g-A';vm.stream.conversationId.value='A';vm.stream.state.value='disconnected';vm.stream.text.value='A 已生成部分';vm.stream.error.value='A 连接中断';await flushPromises()
    await vm.selectConversation(row('B'));await flushPromises()
    expect(vm.current.id).toBe('B');expect(wrapper.findAll('button').some(button=>button.text()==='恢复生成')).toBe(false);expect(vm.stream.generationId.value).toBeUndefined();expect(vm.stream.error.value).toBe('');expect(mocks.getGeneration).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('重选同一会话 A 保留生成编号，恢复只订阅原生成不重发问题',async()=>{
    const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('A'));vm.stream.generationId.value='g-A';vm.stream.conversationId.value='A';vm.stream.state.value='disconnected';await vm.selectConversation(row('A'));await flushPromises()
    mocks.getGeneration.mockResolvedValue({data:{generationId:'g-A',conversationId:'A',lastSequence:2,text:'原部分答案',references:[],state:'RUNNING'}});const restoring=vm.resumeCurrent();await flushPromises();const ws=Socket.instances[0]!;ws.open();await restoring
    expect(vm.stream.generationId.value).toBe('g-A');expect(vm.showStream).toBe(true);expect(ws.sent.map(value=>JSON.parse(value))).toEqual([{type:'resume',generationId:'g-A',lastSequence:2}]);wrapper.unmount()
  })
  it('A 断线后经过 B 再回 A 能用原编号核实并恢复，不重新提问',async()=>{
    const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('A'));vm.stream.generationId.value='g-A';vm.stream.conversationId.value='A';vm.stream.state.value='disconnected'
    await vm.selectConversation(row('B'));expect(vm.stream.generationId.value).toBeUndefined();await vm.selectConversation(row('A'));await flushPromises()
    expect(vm.stream.generationId.value).toBe('g-A');expect(mocks.getGeneration).not.toHaveBeenCalled();mocks.getGeneration.mockResolvedValue({data:{generationId:'g-A',conversationId:'A',lastSequence:4,text:'原部分答案',references:[],state:'RUNNING'}})
    const restoring=vm.resumeCurrent();await flushPromises();Socket.instances[0]!.open();await restoring;expect(mocks.getGeneration).toHaveBeenCalledWith('g-A');expect(Socket.instances[0]!.sent.map(value=>JSON.parse(value))).toEqual([{type:'resume',generationId:'g-A',lastSequence:4}]);wrapper.unmount()
  })
  it.each(['成功','失败'])('初始活动生成读取迟到%s不能覆盖用户已经选中的 B',async outcome=>{
    const pending=deferred<unknown>();mocks.getActiveGeneration.mockReturnValue(pending.promise);const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('B'))
    if(outcome==='成功')pending.resolve({data:{generationId:'g-A',conversationId:'A',state:'RUNNING'}});else pending.reject(new Error('初始读取旧错误'));await flushPromises()
    expect(vm.current.id).toBe('B');expect(vm.error).toBe('');expect(mocks.getGeneration).not.toHaveBeenCalled();expect(mocks.createTicket).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('初始恢复 A 的会话元数据迟到也不能继续恢复到 B 页面',async()=>{
    const pending=deferred<unknown>();mocks.getActiveGeneration.mockResolvedValue({data:{generationId:'g-A',conversationId:'A',state:'RUNNING'}});mocks.conversationAction.mockImplementation((value)=>value.id==='A'?pending.promise:Promise.resolve({data:value}))
    const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('B'));pending.resolve({data:row('A')});await flushPromises()
    expect(vm.current.id).toBe('B');expect(vm.showStream).toBe(false);expect(mocks.getGeneration).not.toHaveBeenCalled();expect(mocks.createTicket).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('A 终态消息回读迟到不隐藏 B 正在显示的生成',async()=>{
    const wrapper=mount(Chat,{global}),vm=wrapper.vm as any;await flushPromises();await vm.selectConversation(row('A'));const pending=deferred<unknown>();mocks.conversationMessages.mockImplementation((id:string)=>id==='A'?pending.promise:Promise.resolve(page(id)))
    const finishing=vm.finished();await flushPromises();await vm.selectConversation(row('B'));vm.showStream=true;pending.resolve(page('A'));await finishing;await flushPromises()
    expect(vm.current.id).toBe('B');expect(vm.messages[0].content).toBe('答案 B');expect(vm.showStream).toBe(true);wrapper.unmount()
  })
  it('同一会话翻页的新响应不被上一页迟到消息覆盖',async()=>{
    const first=deferred<unknown>();mocks.conversationMessages.mockImplementationOnce(()=>first.promise).mockResolvedValueOnce(page('第二页'))
    const wrapper=mount(History,{global}),vm=wrapper.vm as any;await flushPromises();const opening=vm.view(row('A'));vm.messagePage=2;await vm.loadMessages();first.resolve(page('第一页'));await opening
    expect(vm.messages[0].content).toBe('答案 第二页');wrapper.unmount()
  })
})

describe('撤销旧生成订阅的异步边界',()=>{
  it.each(['成功','失败'])('A 恢复请求迟到%s不能重新绑定 B',async outcome=>{
    const pending=deferred<unknown>();mocks.getGeneration.mockReturnValue(pending.promise);const {stream,wrapper,done}=streamComponent();stream.reset('A');stream.generationId.value='g-A';const restoring=stream.resume();stream.reset('B')
    if(outcome==='成功')pending.resolve({data:{generationId:'g-A',conversationId:'A',lastSequence:1,text:'A 的旧输出',references:[],state:'RUNNING'}});else pending.reject(new Error('A 的旧恢复错误'));await restoring
    expect(stream.conversationId.value).toBe('B');expect(stream.generationId.value).toBeUndefined();expect(stream.text.value).toBe('');expect(stream.error.value).toBe('');expect(stream.state.value).toBe('idle');expect(mocks.createTicket).not.toHaveBeenCalled();expect(done).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('A 的连接票据迟到不能在 B 下建连接或发问',async()=>{
    const pending=deferred<unknown>();mocks.createTicket.mockReturnValue(pending.promise);const {stream,wrapper}=streamComponent();const sending=stream.send('A','A 的问题');stream.reset('B');pending.resolve({data:{ticket:'offline-late-ticket'}});await sending
    expect(Socket.instances).toHaveLength(0);expect(stream.conversationId.value).toBe('B');expect(stream.state.value).toBe('idle');expect(stream.error.value).toBe('');wrapper.unmount()
  })
  it('已关闭 A 连接的迟到内容及终态不能回写 B 或触发完成',async()=>{
    const {stream,wrapper,done}=streamComponent();const sending=stream.send('A','A 的问题');await flushPromises();const ws=Socket.instances[0]!;ws.open();await sending;ws.emit({generationId:'g-A',conversationId:'A',lastSequence:0,state:'RUNNING',text:''});stream.reset('B')
    ws.emit({kind:'delta',generationId:'g-A',conversationId:'A',sequence:1,text:'A 的迟到内容'});ws.emit({kind:'done',generationId:'g-A',conversationId:'A',sequence:2,state:'COMPLETE'})
    expect(stream.text.value).toBe('');expect(stream.conversationId.value).toBe('B');expect(stream.state.value).toBe('idle');expect(done).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('恢复接口返回其他会话时拒绝绑定且不连接',async()=>{
    mocks.getGeneration.mockResolvedValue({data:{generationId:'foreign',conversationId:'B',lastSequence:0,text:'外部会话内容',state:'RUNNING'}});const {stream,wrapper}=streamComponent();stream.reset('A');await stream.resume('g-A')
    expect(stream.conversationId.value).toBe('A');expect(stream.text.value).toBe('');expect(stream.error.value).toContain('不属于当前会话');expect(mocks.createTicket).not.toHaveBeenCalled();wrapper.unmount()
  })
})
