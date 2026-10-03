import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import type {AxiosAdapter} from 'axios'
vi.mock('@/store/modules/user',()=>({default:()=>({permissions:['paicli:artifact:read'],roles:['ordinary']})}))
vi.mock('@/utils/auth',()=>({getToken:()=>undefined}))
vi.mock('element-plus',()=>({ElMessage:vi.fn(),ElNotification:{error:vi.fn()},ElMessageBox:{confirm:vi.fn(()=>Promise.reject(new Error('fixture取消')))},ElLoading:{service:vi.fn()}}))
import request from '@/utils/request'
import {downloadArtifact} from '@/api/paicli/sessions'
import {getSessionEvents} from '@/api/paicli/events'
const {Blob:NativeBlob}=await vi.importActual<{Blob:typeof Blob}>('node:buffer')
const original=request.defaults.adapter

describe('实际共享request的私有下载与事件错误契约',()=>{
  beforeEach(()=>{vi.clearAllMocks()})
  afterEach(()=>{request.defaults.adapter=original})
  it('HTTP200 JSON权限错误经真实拦截器拒绝，不作为下载文件成功',async()=>{request.defaults.adapter=(async config=>({config,status:200,statusText:'OK',headers:{'content-type':'application/json'},data:new NativeBlob([JSON.stringify({code:403,msg:'当前用户不可读取私有产物'})],{type:'application/json'})})) satisfies AxiosAdapter;await expect(downloadArtifact('9')).rejects.toMatchObject({status:403,response:{data:{code:403,msg:'当前用户不可读取私有产物'}}})})
  it('合法text/plain完整结果不被JSON错误处理改变或截断',async()=>{const text='完整产物'.repeat(20000),blob=new NativeBlob([text],{type:'text/plain;charset=utf-8'});request.defaults.adapter=(async config=>({config,status:200,statusText:'OK',headers:{'content-type':'text/plain;charset=utf-8'},data:blob})) satisfies AxiosAdapter;const actual=await downloadArtifact('9');expect(actual).toBe(blob);expect(await actual.text()).toBe(text)})
  it('HTTP200业务410保留response.code给游标过期恢复逻辑',async()=>{request.defaults.adapter=(async config=>({config,status:200,statusText:'OK',headers:{'content-type':'application/json'},data:{code:410,msg:'事件游标已过期'}})) satisfies AxiosAdapter;await expect(getSessionEvents('8','10',new AbortController().signal)).rejects.toMatchObject({status:410,response:{data:{code:410}}})})
})


