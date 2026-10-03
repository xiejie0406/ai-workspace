import {beforeEach,describe,expect,it,vi} from 'vitest'
import {createEvaluation,listEvaluationDatasets,uploadEvaluationDataset} from '@/api/paismart/governance'

const request=vi.hoisted(()=>vi.fn())
vi.mock('@/utils/request',()=>({default:request}))
beforeEach(()=>{vi.clearAllMocks();request.mockResolvedValue({code:200,data:{id:'actual-upload-id'}})})
describe('评测集复用共享请求协议',()=>{
  it('文件按multipart保存，保留filename、Idempotency-Key且不自行携带另一套认证',async()=>{const file=new File(['{"question":"q","referenceAnswer":"a"}'],'sample.jsonl');await uploadEvaluationDataset(file,'same-file-key');const config=request.mock.calls[0]?.[0];expect(config).toMatchObject({url:'/paismart/evaluation-datasets',method:'post',headers:{'Content-Type':'multipart/form-data','Idempotency-Key':'same-file-key',repeatSubmit:false}});expect(config.data.get('file')).toBe(file);expect(config.data.get('label')).toBe('sample.jsonl');expect(config.headers).not.toHaveProperty('Authorization');expect(config.data.has('path')).toBe(false)})
  it('真实分页参数及资源ID/seed通过共享API，不混入空inline正文或资源路径',async()=>{await listEvaluationDatasets({pageNum:2,pageSize:20});expect(request.mock.calls[0]?.[0]).toMatchObject({url:'/paismart/evaluation-datasets',params:{pageNum:2,pageSize:20}});await createEvaluation({label:'资源评测',topK:5,seed:Number.MIN_SAFE_INTEGER,datasetId:'builtin:actual-api-fixture'},'same-evaluation-key');expect(request.mock.calls[1]?.[0]).toMatchObject({url:'/paismart/evaluations',method:'post',headers:{'Idempotency-Key':'same-evaluation-key'},data:{datasetId:'builtin:actual-api-fixture',seed:Number.MIN_SAFE_INTEGER}});expect(request.mock.calls[1]?.[0].data).not.toHaveProperty('inlineJsonl');expect(request.mock.calls[1]?.[0].data).not.toHaveProperty('path')})
  it('合法长文件名保留原filename，短评测名称独立成为上传label',async()=>{const file=new File(['{"question":"q","referenceAnswer":"a"}'],'a'.repeat(161)+'.jsonl');await uploadEvaluationDataset(file,'long-file-key','短评测名称');const data=request.mock.calls[0]?.[0].data as FormData;expect((data.get('file') as File).name).toBe(file.name);expect(data.get('label')).toBe('短评测名称')})
})
