import request from '@/utils/request'
import type { Project, Source, Outline, Deck, Slide, Proposal, Task, Theme, Layout, Issue,QualityReport } from '@/types/aippt'
export const idempotencyKey = () => crypto.randomUUID()
interface Envelope<T> { data: T; code?: number; msg?: string }
async function call<T>(url: string, method = 'get', data?: unknown, params?: unknown,key?:string): Promise<T> {
  const response = await request({ url: '/aippt'+url, method, data, params, preserveBusinessError:true, headers: {...(method==='get'?{}:{'Idempotency-Key': key||idempotencyKey(), repeatSubmit:false}),...(data instanceof FormData?{'Content-Type':undefined}:{})} } as any) as unknown as Envelope<T>
  return response.data
}
const path = (p:string) => `/projects/${encodeURIComponent(p)}`
const page = (p:string,s:string) => `${path(p)}/deck/slides/${encodeURIComponent(s)}`
export const api = {
  async projects(params: Record<string,unknown>) { return await request({url:'/aippt/projects',params}) as unknown as {rows:Project[];total:number} },
  project:(id:string)=>call<Project>(path(id)), create:(body:unknown,key?:string)=>call<Project>('/projects','post',body,undefined,key),
  update:(id:string,body:unknown,key?:string)=>call<Project>(path(id),'patch',body,undefined,key), remove:(id:string,revision:number)=>call<Task>(path(id),'delete',{input_revision:revision}),
  sources:(id:string)=>call<Source[]>(`${path(id)}/sources`), addSource:(id:string,body:unknown,key?:string)=>call<Source>(`${path(id)}/sources`,'post',body,undefined,key),
  uploadSource:(id:string,file:File,revision:number,key?:string)=>{const form=new FormData();form.append('file',file);form.append('input_revision',String(revision));return call<Source>(`${path(id)}/sources/upload`,'post',form,undefined,key)},
  sections:(id:string,sid:string)=>call<Source['sections']>(`${path(id)}/sources/${sid}/sections`), removeSource:(id:string,sid:string,revision:number)=>call<void>(`${path(id)}/sources/${sid}`,'delete',{input_revision:revision}),
  outline:(id:string)=>call<Outline>(`${path(id)}/outline`), generateOutline:(id:string,body:unknown,key?:string)=>call<Task>(`${path(id)}/outline/generate`,'post',body,undefined,key),
  saveOutline:(id:string,body:unknown,key?:string)=>call<Outline>(`${path(id)}/outline`,'patch',body,undefined,key), confirm:(id:string,body:unknown,key?:string)=>call<Outline>(`${path(id)}/outline/confirm`,'post',body,undefined,key), confirmAndGenerate:(id:string,body:unknown,key?:string)=>call<Task>(`${path(id)}/outline/confirm-and-generate`,'post',body,undefined,key), unconfirm:(id:string,body:unknown)=>call<Outline>(`${path(id)}/outline/unconfirm`,'post',body),
  deck:(id:string)=>call<Deck>(`${path(id)}/deck`), generate:(id:string,body:unknown,key?:string)=>call<Task>(`${path(id)}/deck/generate`,'post',body,undefined,key), cancel:(id:string,revision:number,key?:string)=>call<Task>(`${path(id)}/deck/cancel`,'post',{deck_revision:revision},undefined,key),
  retry:(id:string,sid:string,revision:number,deckRevision:number,key?:string)=>call<Task>(`${page(id,sid)}/retry`,'post',{revision,deck_revision:deckRevision},undefined,key),
  page:(id:string,sid:string,method:string,action:string,body:unknown,key?:string)=>call<Slide>(page(id,sid)+(action?'/'+action:''),method,body,undefined,key),
  insert:(id:string,after:string|null,revision:number,key?:string)=>call<Deck>(`${path(id)}/deck/slides`,'post',{after_slide_id:after,deck_revision:revision},undefined,key),
  duplicate:(id:string,sid:string,revision:number,key?:string)=>call<Deck>(`${page(id,sid)}/duplicate`,'post',{deck_revision:revision},undefined,key),
  deleteSlide:(id:string,sid:string,revision:number,key?:string)=>call<Deck>(page(id,sid),'delete',{deck_revision:revision},undefined,key),
  order:(id:string,ids:string[],revision:number,key?:string)=>call<Deck>(`${path(id)}/deck/slides/order`,'put',{slide_ids:ids,deck_revision:revision},undefined,key),
  theme:(id:string,body:unknown,key?:string)=>call<Project>(`${path(id)}/theme`,'patch',body,undefined,key),
  layouts:(id:string,sid:string)=>call<{layout_id:string;compatible:boolean;reason?:string}[]>(`${page(id,sid)}/layouts`),
  proposal:(id:string,sid:string,kind:'ai-edit'|'relayout',body:unknown)=>call<Proposal|Task>(`${page(id,sid)}/${kind}`,'post',body),
  getProposal:(id:string,pid:string)=>call<Proposal>(`${path(id)}/proposals/${pid}`),
  apply:(id:string,sid:string,kind:'ai-edit'|'relayout',body:unknown,key?:string)=>call<Slide>(`${page(id,sid)}/${kind}/apply`,'post',body,undefined,key),
  image:(id:string,sid:string,bid:string,file:File,revision:number,key?:string)=>{const form=new FormData();form.append('file',file);form.append('revision',String(revision));return call<Slide>(`${page(id,sid)}/blocks/${bid}/image`,'put',form,undefined,key)},
  quality:(id:string)=>call<QualityReport>(`${path(id)}/deck/quality`),
  export:(id:string,body:unknown,key?:string)=>call<Task>(`${path(id)}/deck/exports`,'post',body,undefined,key), exportState:(id:string,eid:string)=>call<Task&{asset_id?:string;download_url?:string;verify?:unknown}>(`/exports/${eid}`),
  async catalog(){const [t,l]=await Promise.all([call<Theme[]|{items:Theme[];catalog_version:string}>('/design/themes'),call<Layout[]|{items:Layout[];catalog_version:string}>('/design/layouts')]);return {themes:Array.isArray(t)?t:t.items,layouts:Array.isArray(l)?l:l.items,catalog_version:Array.isArray(t)?undefined:t.catalog_version}},
  sample:()=>call<Deck>('/design/sample-deck'), sampleIssues:(themeId='ivory')=>call<QualityReport>('/design/sample-deck/issues','get',undefined,{theme_id:themeId}), sampleExport:(body:unknown,key?:string)=>call<Task>('/design/sample-deck/exports','post',body,undefined,key),
  task:(id:string)=>call<Task>(`/tasks/${id}`),
  async binary(url:string) { return await request({url:url.startsWith('/aippt')?url:'/aippt'+url,responseType:'blob',aipptBinary:true,preserveBusinessError:true} as any) as unknown as Blob },
  events:(id:string,cursor:number,signal:AbortSignal,scope:'task'|'deck'='task')=>request({url:scope==='deck'?`/aippt/projects/${encodeURIComponent(id)}/deck/events`:`/aippt/tasks/${encodeURIComponent(id)}/events`,params:{afterSeq:cursor},signal,aipptStream:true} as any) as unknown as Promise<ReadableStream<Uint8Array>>
}
export async function download(url:string,name:string){const blob=await api.binary(url);const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000)}
