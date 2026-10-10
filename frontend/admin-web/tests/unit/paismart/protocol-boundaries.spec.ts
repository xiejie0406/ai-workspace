import { describe,expect,it,vi,beforeEach,afterEach } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { defineComponent,h } from 'vue'
import { File as NodeFile } from 'node:buffer'
import { webcrypto } from 'node:crypto'
import { useChunkUpload } from '@/views/paismart/composables/useChunkUpload'
import { useChatStream,websocketAddress } from '@/views/paismart/composables/useChatStream'
import { validateDataset } from '@/views/paismart/composables/evaluationDataset'
import { usePage } from '@/views/paismart/composables/usePage'
import { normalizeConflict,normalizeIndex,normalizeJob } from '@/api/paismart/adapters'
import { rejectBusinessError } from '@/utils/structuredBusinessError'
import type { Job,Page,Reference } from '@/types/paismart'

const mocks=vi.hoisted(()=>({createUpload:vi.fn(),uploadStatus:vi.fn(),putChunk:vi.fn(),mergeUpload:vi.fn(),createTicket:vi.fn(),getGeneration:vi.fn(),request:vi.fn()}))
vi.mock('@/api/paismart/upload',()=>mocks)
vi.mock('@/api/paismart/chat',()=>mocks)
vi.mock('@/utils/request',()=>({default:mocks.request}))
vi.mock('@/api/paismart/client',async importOriginal=>({...await importOriginal<typeof import('@/api/paismart/client')>(),newKey:()=> 'request-key-test'}))

function component<T>(setup:()=>T){let model!:T;const wrapper=mount(defineComponent({setup(){model=setup();return()=>h('div')}}));return {model,wrapper}}
function result<T>(data:T){return {code:200,data}}
function deferred<T>() {
  let resolve!:(value:T)=>void, reject!:(reason:unknown)=>void
  const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no})
  return {promise,resolve,reject}
}
class Socket {
  static OPEN=1;static CONNECTING=0;static CLOSED=3;static instances:Socket[]=[]
  readyState=0;sent:string[]=[]
  onopen?:()=>void;onclose?:()=>void;onerror?:()=>void;onmessage?:(event:{data:string})=>void
  constructor(public url:string){Socket.instances.push(this)}
  open(){this.readyState=1;this.onopen?.()}
  send(data:string){this.sent.push(data)}
  close(){this.readyState=3;this.onclose?.()}
  emit(data:unknown){this.onmessage?.({data:JSON.stringify(data)})}
}
beforeEach(()=>{vi.clearAllMocks();Socket.instances=[];vi.stubGlobal('crypto',webcrypto);vi.stubGlobal('WebSocket',Socket)})
afterEach(()=>{vi.unstubAllGlobals()})

describe('分片上传事实回源',()=>{
  it('分片业务拒绝复用若依错误保留选项，中文原因进入上传提示且不推进或合并',async()=>{
    const actualUpload=await vi.importActual<typeof import('@/api/paismart/upload')>('@/api/paismart/upload')
    mocks.createUpload.mockResolvedValue(result({uploadId:'invalid/pdf'}))
    mocks.uploadStatus.mockResolvedValue(result({uploadId:'invalid/pdf',state:'UPLOADING',chunkSize:100,completedChunks:[]}))
    mocks.putChunk.mockImplementationOnce(actualUpload.putChunk)
    const message='PDF文件内容与扩展名不符'
    mocks.request.mockImplementationOnce(config=>{const response={config,status:200,data:{code:400,msg:message}};return rejectBusinessError(response,message,'error')})
    const upload=useChunkUpload();await upload.run(new NodeFile(['fake pdf'],'invalid.pdf') as unknown as File,'s1','PRIVATE')
    expect(mocks.request).toHaveBeenCalledOnce()
    const config=mocks.request.mock.calls[0]![0]
    expect(config).toMatchObject({url:'/paismart/uploads/invalid%2Fpdf/chunks/0',method:'put',timeout:60_000,preserveBusinessError:true,headers:{'Content-Type':'application/octet-stream',repeatSubmit:false}})
    expect(config.headers['X-Chunk-SHA256']).toMatch(/^[a-f0-9]{64}$/)
    expect(config.headers).not.toHaveProperty('Authorization');expect(config.signal).toBeInstanceOf(AbortSignal)
    expect(upload.error.value).toBe(message);expect(upload.progress.value).toBe(0);expect(upload.manifest.value?.completedChunks).toEqual([])
    expect(upload.result.value).toBeUndefined();expect(upload.busy.value).toBe(false);expect(mocks.mergeUpload).not.toHaveBeenCalled()
  })
  it('已合并记录读取原文档，不重传已回收的暂存片',async()=>{
    mocks.uploadStatus.mockResolvedValue(result({uploadId:'u1',state:'MERGED',chunkSize:2,completedChunks:[],filename:'sample.txt',byteSize:5}));mocks.mergeUpload.mockResolvedValue(result({id:'d1',title:'原文档',state:'UPLOADED'}));const upload=useChunkUpload();await upload.run(new NodeFile(['abcde'],'sample.txt') as unknown as File,'s1','PRIVATE','u1');expect(mocks.putChunk).not.toHaveBeenCalled();expect(mocks.mergeUpload).toHaveBeenCalledOnce();expect(upload.result.value?.id).toBe('d1');expect(upload.progress.value).toBe(100)
  })
  it('续传只上传缺失的 0 基分片，并在所有片确认后合并',async()=>{
    mocks.createUpload.mockResolvedValue(result({uploadId:'u1'}))
    mocks.uploadStatus.mockResolvedValue(result({uploadId:'u1',chunkSize:2,completedChunks:[1],filename:'sample.txt',byteSize:5}))
    mocks.putChunk.mockResolvedValue(result({}))
    mocks.mergeUpload.mockResolvedValue(result({id:'d1',title:'sample.txt',state:'UPLOADED'}))
    const file=new NodeFile(['abcde'],'sample.txt') as unknown as File
    const upload=useChunkUpload();await upload.run(file,'s1','PRIVATE')
    expect(mocks.putChunk.mock.calls.map(call=>call[1])).toEqual([0,2])
    expect(mocks.putChunk.mock.calls.map(call=>call[2].size)).toEqual([2,1])
    expect(mocks.mergeUpload).toHaveBeenCalledOnce();expect(upload.result.value?.id).toBe('d1')
    expect(upload.progress.value).toBe(100)
  })
  it('续传所选文件不匹配时保留失败且不传片、不合并',async()=>{
    mocks.uploadStatus.mockResolvedValue(result({uploadId:'u1',chunkSize:2,completedChunks:[0],filename:'other.txt',byteSize:5}))
    const upload=useChunkUpload();await upload.run(new NodeFile(['abcde'],'sample.txt') as unknown as File,'s1','PRIVATE','u1')
    expect(upload.error.value).toContain('不一致');expect(mocks.putChunk).not.toHaveBeenCalled();expect(mocks.mergeUpload).not.toHaveBeenCalled()
  })
  it('片写入失败不能被解释为文档合并完成',async()=>{
    mocks.createUpload.mockResolvedValue(result({uploadId:'u1'}));mocks.uploadStatus.mockResolvedValue(result({uploadId:'u1',chunkSize:2,completedChunks:[]}));mocks.putChunk.mockRejectedValue(new Error('HASH_MISMATCH'))
    const upload=useChunkUpload();await upload.run(new NodeFile(['abc'],'sample.txt') as unknown as File,'s1','PRIVATE')
    expect(upload.error.value).toBe('HASH_MISMATCH');expect(upload.result.value).toBeUndefined();expect(mocks.mergeUpload).not.toHaveBeenCalled()
  })
  it.each(['MERGED','UPLOADING'])('暂停 %s 运行后，迟到的合并成功不能把暂停改为完成',async(state)=>{
    const pending=deferred<ReturnType<typeof result>>()
    mocks.uploadStatus.mockResolvedValue(result({uploadId:'old',state,chunkSize:5,completedChunks:[0],filename:'old.txt',byteSize:5}))
    mocks.mergeUpload.mockImplementationOnce(()=>pending.promise)
    const upload=useChunkUpload()
    const running=upload.run(new NodeFile(['abcde'],'old.txt') as unknown as File,'s1','PRIVATE','old')
    await vi.waitFor(()=>expect(mocks.mergeUpload).toHaveBeenCalledOnce())
    upload.pause()
    const paused={phase:upload.phase.value,progress:upload.progress.value}
    pending.resolve(result({id:'old-document',title:'旧文档'}));await running
    expect(upload.phase.value).toBe(paused.phase);expect(upload.progress.value).toBe(paused.progress)
    expect(upload.result.value).toBeUndefined();expect(upload.busy.value).toBe(false)
  })
  it.each([
    ['MERGED','success'],['MERGED','failure'],['UPLOADING','success'],['UPLOADING','failure']
  ])('旧 %s 运行迟到 %s 不得改变新文件的当前上传',async(state,outcome)=>{
    const oldMerge=deferred<ReturnType<typeof result>>(),newMerge=deferred<ReturnType<typeof result>>()
    mocks.uploadStatus.mockImplementation((id:string)=>Promise.resolve(result({uploadId:id,state:id==='old'?state:'UPLOADING',chunkSize:5,completedChunks:[0],filename:id==='old'?'old.txt':'new.txt',byteSize:5})))
    mocks.mergeUpload.mockImplementationOnce(()=>oldMerge.promise).mockImplementationOnce(()=>newMerge.promise)
    const upload=useChunkUpload()
    const oldRun=upload.run(new NodeFile(['abcde'],'old.txt') as unknown as File,'s1','PRIVATE','old')
    await vi.waitFor(()=>expect(mocks.mergeUpload).toHaveBeenCalledTimes(1))
    upload.pause()
    const newRun=upload.run(new NodeFile(['vwxyz'],'new.txt') as unknown as File,'s1','PRIVATE','new')
    await vi.waitFor(()=>expect(mocks.mergeUpload).toHaveBeenCalledTimes(2))
    const current={manifest:upload.manifest.value,phase:upload.phase.value,progress:upload.progress.value}
    if(outcome==='success')oldMerge.resolve(result({id:'old-document',title:'旧文档'}))
    else oldMerge.reject(new Error('旧请求失败'))
    await oldRun
    expect(upload.manifest.value).toBe(current.manifest);expect(upload.manifest.value?.uploadId).toBe('new')
    expect(upload.phase.value).toBe(current.phase);expect(upload.progress.value).toBe(current.progress)
    expect(upload.result.value).toBeUndefined();expect(upload.error.value).toBe('');expect(upload.busy.value).toBe(true)
    newMerge.resolve(result({id:'new-document',title:'新文档'}));await newRun
    expect(upload.result.value?.id).toBe('new-document');expect(upload.busy.value).toBe(false)
    expect(upload.phase.value).toBe('上传已合并，解析状态以文档列表为准')
  })
  it('暂停后迟到的片确认不能更新新文件进度或继续合并旧上传',async()=>{
    const oldChunk=deferred<ReturnType<typeof result>>(),newStatus=deferred<ReturnType<typeof result>>()
    mocks.uploadStatus.mockImplementationOnce(()=>Promise.resolve(result({uploadId:'old',chunkSize:5,completedChunks:[],filename:'old.txt',byteSize:5}))).mockImplementationOnce(()=>newStatus.promise)
    mocks.putChunk.mockImplementationOnce(()=>oldChunk.promise)
    mocks.mergeUpload.mockResolvedValue(result({id:'new-document'}))
    const upload=useChunkUpload()
    const oldRun=upload.run(new NodeFile(['abcde'],'old.txt') as unknown as File,'s1','PRIVATE','old')
    await vi.waitFor(()=>expect(mocks.putChunk).toHaveBeenCalledOnce())
    upload.pause()
    const newRun=upload.run(new NodeFile(['vwxyz'],'new.txt') as unknown as File,'s1','PRIVATE','new')
    await vi.waitFor(()=>expect(mocks.uploadStatus).toHaveBeenCalledTimes(2))
    const current={phase:upload.phase.value,progress:upload.progress.value}
    oldChunk.resolve(result({}));await oldRun
    expect(upload.phase.value).toBe(current.phase);expect(upload.progress.value).toBe(current.progress)
    expect(upload.result.value).toBeUndefined();expect(upload.busy.value).toBe(true);expect(mocks.mergeUpload).not.toHaveBeenCalled()
    newStatus.resolve(result({uploadId:'new',chunkSize:5,completedChunks:[0],filename:'new.txt',byteSize:5}));await newRun
    expect(upload.result.value?.id).toBe('new-document')
  })
})
describe('问答恢复与序号',()=>{
  it('拒绝跨主机或脚本连接地址，并把开发代理前缀保留到 WS',()=>{
    expect(websocketAddress('/dev-api','one-time')).toContain('/dev-api/paismart/ws/chat?ticket=one-time')
    expect(()=>websocketAddress('/dev-api','t','https://untrusted.example/ws')).toThrow('受信')
    expect(()=>websocketAddress('/dev-api','t','javascript:alert(1)')).toThrow('受信')
  })
  it('接收初始快照，忽略重复及其他生成的事件，终态回调只一次',async()=>{
    mocks.createTicket.mockResolvedValue(result({ticket:'ticket'}));const done=vi.fn();const {model:stream,wrapper}=component(()=>useChatStream(done))
    const pending=stream.send('c1','question');await flushPromises();const socket=Socket.instances[0]!;socket.open();await pending
    expect(JSON.parse(socket.sent[0]!)).toMatchObject({type:'chat',conversationId:'c1',message:'question'})
    socket.emit({generationId:'g1',conversationId:'c1',lastSequence:0,text:'',state:'QUEUED'})
    expect(stream.generationId.value).toBe('g1')
    socket.emit({kind:'delta',generationId:'g1',sequence:1,text:'安全'})
    socket.emit({kind:'delta',generationId:'g1',sequence:1,text:'重复'})
    socket.emit({kind:'delta',generationId:'foreign',sequence:2,text:'泄漏'})
    socket.emit({kind:'delta',generationId:'g1',sequence:2,text:'回答'})
    socket.emit({kind:'done',generationId:'g1',sequence:3,state:'COMPLETE'})
    socket.emit({kind:'done',generationId:'g1',sequence:3,state:'COMPLETE'})
    expect(stream.text.value).toBe('安全回答');expect(done).toHaveBeenCalledOnce();expect(stream.state.value).toBe('done');wrapper.unmount()
  })
  it('已完成生成恢复只读取快照，不重发问题或创建新连接',async()=>{
    mocks.getGeneration.mockResolvedValue(result({generationId:'g1',conversationId:'c1',lastSequence:8,text:'已完成回答',references:[],state:'COMPLETE'}));const done=vi.fn();const {model:stream,wrapper}=component(()=>useChatStream(done));await stream.resume('g1')
    expect(stream.text.value).toBe('已完成回答');expect(stream.sequence.value).toBe(8);expect(Socket.instances).toHaveLength(0);expect(done).toHaveBeenCalledOnce();wrapper.unmount()
  })
  it('连接建立前关闭不会让发送永久等待',async()=>{
    mocks.createTicket.mockResolvedValue(result({ticket:'t'}));const {model:stream,wrapper}=component(()=>useChatStream(vi.fn()));const pending=stream.send('c1','q');await flushPromises();Socket.instances[0]!.close();await pending
    expect(stream.state.value).toBe('failed');expect(stream.error.value).toContain('关闭');wrapper.unmount()
  })
})
describe('列表与后台 DTO',()=>{
  it('旧列表响应返回更晚时不得覆盖新查询',async()=>{
    let first!:(value:Page<{id:string}>)=>void;let second!:(value:Page<{id:string}>)=>void
    const loader=vi.fn().mockImplementationOnce(()=>new Promise(resolve=>{first=resolve})).mockImplementationOnce(()=>new Promise(resolve=>{second=resolve}))
    const {model:page,wrapper}=component(()=>usePage<{id:string}>(loader))
    const a=page.load();const b=page.load();second({code:200,rows:[{id:'new'}],total:1});await b;first({code:200,rows:[{id:'old'}],total:1});await a
    expect(page.rows.value).toEqual([{id:'new'}]);expect(page.loading.value).toBe(false);wrapper.unmount()
  })
  it('作业 id 与冲突原因适配保留真实状态和证据',()=>{
    expect(normalizeJob({id:'job',type:'DOCUMENT_PROCESS',state:'FAILED'} as unknown as Job)).toMatchObject({jobId:'job',kind:'DOCUMENT_PROCESS',state:'FAILED'})
    expect(normalizeConflict({id:'x',status:'PENDING',reason:{reason:'值冲突',oldText:'旧值',newText:'新值',similarity:0.9}} as never)).toMatchObject({state:'PENDING',reason:'值冲突',oldText:'旧值',newText:'新值'})
    expect(normalizeIndex({id:'idx',state:'ACTIVE',providerSnapshot:JSON.stringify({name:'供应商',model:'m',dimension:768}),processedCount:10} as never)).toMatchObject({active:true,model:'m',dimension:768,processedCount:10})
    expect(normalizeIndex({id:'pending',state:'BUILDING',validationJson:JSON.stringify({state:'PENDING'}),sourceWatermark:'2026-10-03'} as never)).toMatchObject({sourceWatermark:'2026-10-03',validation:{state:'PENDING'}})
    expect(normalizeIndex({id:'pending',state:'BUILDING',validationJson:'invalid'} as never).validation).toBeUndefined()
  })
  it('索引重建进度不冒充实时文档总量，真实返回的计数各自保留',()=>{
    const initial=normalizeIndex({id:'initial',state:'ACTIVE',processedCount:0} as never)
    expect(initial.processedCount).toBe(0);expect(initial).not.toHaveProperty('documentCount')
    const rebuilt=normalizeIndex({id:'rebuilt',state:'ACTIVE',processedCount:10,documentCount:12} as never)
    expect(rebuilt.processedCount).toBe(10);expect(rebuilt.documentCount).toBe(12)
  })
  it('评测集拒绝坏 JSON、空问题和超出预算的样本量',()=>{
    expect(validateDataset('{"question":"q","referenceAnswer":"a"}\n{"query":"q2","referenceAnswer":"a2"}')).toBe(2)
    expect(()=>validateDataset('not-json')).toThrow('第 1 行')
    expect(()=>validateDataset('{"question":" "}')).toThrow('缺少')
    expect(()=>validateDataset(Array.from({length:501},()=>'{"question":"q"}').join('\n'))).toThrow('500')
  })
})
