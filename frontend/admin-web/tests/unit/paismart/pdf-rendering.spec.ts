import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { flushPromises,mount } from '@vue/test-utils'
import { Blob as NodeBlob } from 'node:buffer'
import PdfPage from '@/views/paismart/documents/components/PdfPage.vue'
const mock=vi.hoisted(()=>({getDocument:vi.fn(),createWorker:vi.fn(),cleanup:vi.fn(),destroy:vi.fn(),cancel:vi.fn(),render:vi.fn()}))
vi.mock('pdfjs-dist',()=>({PDFWorker:{create:mock.createWorker},getDocument:mock.getDocument,Util:{transform:(a:number[],b:number[])=>[a[0]!*b[0]!,0,0,a[3]!*b[3]!,a[0]!*b[4]!,a[3]!*b[5]!+a[5]!]}}))
vi.mock('pdfjs-dist/build/pdf.worker.min.mjs?url',()=>({default:'/assets/local-pdf-worker.mjs'}))
class LocalWorker {static urls:string[]=[];constructor(url:string){LocalWorker.urls.push(url)}terminate=vi.fn()}
beforeEach(()=>{vi.clearAllMocks();LocalWorker.urls=[];vi.stubGlobal('Worker',LocalWorker);vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({} as CanvasRenderingContext2D);Object.defineProperty(HTMLElement.prototype,'scrollIntoView',{configurable:true,value:vi.fn()});mock.createWorker.mockReturnValue({destroy:vi.fn()});mock.render.mockReturnValue({promise:Promise.resolve(),cancel:mock.cancel});const page={getViewport:({scale}:{scale:number})=>({width:200*scale,height:200*scale,scale,transform:[scale,0,0,-scale,0,200*scale]}),render:mock.render,getTextContent:async()=>({items:[{str:'引用内容',width:60,transform:[10,0,0,10,20,150]}]})};mock.getDocument.mockReturnValue({promise:Promise.resolve({getPage:async()=>page,cleanup:mock.cleanup}),destroy:mock.destroy})})
afterEach(()=>{vi.restoreAllMocks();vi.unstubAllGlobals();Reflect.deleteProperty(HTMLElement.prototype,'scrollIntoView')})
describe('受控 PDF 本地页渲染',()=>{
  it('使用本地 Worker 和字节输入，缩放实际画布并定位引用文本',async()=>{
    const blob=new NodeBlob(['pdf-fixture']) as unknown as Blob
    const wrapper=mount(PdfPage,{props:{blob,zoom:100,anchor:'引用内容'},global:{directives:{loading:()=>{}},stubs:{'el-alert':true}}})
    await flushPromises();expect(LocalWorker.urls).toEqual(['/assets/local-pdf-worker.mjs']);expect(mock.getDocument.mock.calls[0]?.[0]).toMatchObject({useWorkerFetch:false,disableAutoFetch:true});expect(mock.getDocument.mock.calls[0]?.[0].url).toBeUndefined()
    expect(wrapper.find('canvas').element.width).toBe(200);expect(wrapper.findAll('mark')).toHaveLength(1);expect(wrapper.text()).toContain('已定位 1 处')
    await wrapper.setProps({zoom:200});await flushPromises();expect(wrapper.find('canvas').element.width).toBe(400);expect(wrapper.find('mark').attributes('style')).toContain('left: 40px');wrapper.unmount();expect(mock.destroy).toHaveBeenCalled()
  })
  it('没有命中引用时明确要求人工核对，不伪造高亮',async()=>{
    const wrapper=mount(PdfPage,{props:{blob:new NodeBlob(['pdf']) as unknown as Blob,zoom:100,anchor:'不在本页'},global:{directives:{loading:()=>{}},stubs:{'el-alert':true}}});await flushPromises();expect(wrapper.findAll('mark')).toHaveLength(0);expect(wrapper.text()).toContain('未匹配到引用文本');wrapper.unmount()
  })
})
