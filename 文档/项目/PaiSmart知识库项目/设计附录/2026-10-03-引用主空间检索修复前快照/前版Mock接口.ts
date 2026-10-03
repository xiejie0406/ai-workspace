// 明确标注的本地组件夹具，没有后台代理、认证或真实业务请求。
import {ref} from 'vue'
import type {Query,Upload} from '@/types/paismart'
export const fixtureCalls=ref<string[]>([])
const record=(name:string,data:unknown)=>fixtureCalls.value.push(`${name} ${JSON.stringify(data)}`)
export const newKey=()=>crypto.randomUUID()
export const spaces=[{id:'fixture-space',name:'测试夹具空间',type:'ORGANIZATION' as const,canManage:false,rowVersion:1,uploadMaxSizeBytes:1024*1024}]
export const listSpaces=async()=>({code:200,rows:spaces,total:spaces.length})
const quota={scope:'LLM',mode:'DAILY',enabled:true,limitTokens:100,usedTokens:25,reservedTokens:5,remainingTokens:70,requestCount:3,totalRequestCount:9,available:0,frozen:0,consumed:25,debt:0}
export const myMembership=async()=>({data:{eligible:true}})
export const myUsage=async()=>({data:{accounts:[],policy:{mode:'DAILY',dailyLlmTokens:100,dailyEmbeddingTokens:100,rowVersion:1},businessDay:'2026-10-03',llm:quota,embedding:{...quota,scope:'EMBEDDING'},chatRequestCount:2,totalChatRequestCount:8}})
export const claimEligibility=async()=>({data:{eligible:true}})
export const usageRecords=async()=>({code:200,rows:[],total:0})
export const usageOverview=async(query:Query)=>{record('usageOverview',query);const days=Number(query.days??7),trends=Array.from({length:days},(_,i)=>({day:`2026-09-${String(i+1).padStart(2,'0')}`,chatRequestCount:i+1,llmUsedTokens:10+i*2,llmRequestCount:i+2,embeddingUsedTokens:15+i,embeddingRequestCount:i+3}));return {data:{days,today:{...trends.at(-1),day:'2026-10-03'},trends,llmRankings:[{userId:100,username:'夹具用户',scope:'llm',usedTokens:90,requestCount:4,mode:'DAILY',limitTokens:100,remainingTokens:0,reservedTokens:10}],embeddingRankings:[],alerts:[{userId:100,username:'夹具用户',scope:'llm',usedTokens:90,requestCount:4,mode:'DAILY',limitTokens:100,remainingTokens:0,reservedTokens:10,level:'critical',debt:0,usageRatio:1,message:'测试夹具：今日额度已耗尽'}],lowBalanceThreshold:9300}}}
let upload:Upload={uploadId:'fixture-upload',filename:'resume-demo.txt',byteSize:5,sha256:'2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824',chunkSize:2,completedChunks:[0],totalChunks:3,state:'UPLOADING',spaceId:'fixture-space',visibility:'PRIVATE',createdAt:'2026-10-03T00:00:00Z'}
export const listUploads=async(query:Query)=>{record('listUploads',query);return {code:200,rows:upload.state==='UPLOADING'?[upload]:[],total:upload.state==='UPLOADING'?1:0}}
export const supportedUploadTypes=async()=>({data:[{extension:'txt',mimeType:'text/plain'},{extension:'pdf',mimeType:'application/pdf'}]})
export const createPersonalSpace=async()=>({data:{id:'fixture-personal',name:'测试个人空间',type:'PERSONAL',rowVersion:1}})
export const createUpload=async(data:Record<string,unknown>)=>{record('createUpload',data);upload={...upload,...data,uploadId:'new-fixture-upload',completedChunks:[],state:'UPLOADING'} as Upload;return {data:upload}}
export const uploadStatus=async(id:unknown)=>{record('uploadStatus',id);return {data:{...upload,completedChunks:[...upload.completedChunks]}}}
export const putChunk=async(id:unknown,n:number,blob:Blob)=>{record('putChunk',{id,n,byteSize:blob.size});upload.completedChunks.push(n);return {data:upload}}
export const mergeUpload=async(id:unknown)=>{record('mergeUpload',id);upload.state='MERGED';return {data:{id:'fixture-document',title:upload.filename,filename:upload.filename,state:'UPLOADED',rowVersion:1,contentVersion:1,visibility:'PRIVATE'}}}
export const sendFeedback=async()=>({data:{}})
export const getDocument=async()=>({data:{id:'fixture-document',title:'测试文档',contentVersion:1}})
export const getReference=async()=>({data:{id:'fixture-reference',documentId:'fixture-document',title:'测试文档',contentVersion:1,text:'hello'}})
export const getDocumentContent=async()=>new Blob(['hello'],{type:'text/plain'})
export const getDocumentPage=getDocumentContent
export const downloadDocument=getDocumentContent
const completeEvaluationReport=async()=>({data:{id:'fixture-evaluation',label:'测试夹具：无可比较样本的评测',state:'COMPLETED_WITH_ERRORS',summary:{totalCases:1,labeledCases:0,retrievalHits:0,recallAtK:null,mrr:null,meanExpectedDocCoverage:null,ndcg:null,judgedCases:0,correctCases:0,meanJudgeScore:null,accuracy:null,needsHumanReviewCount:1,failedCases:1,retrievalModeCounts:{NONE:1},diagnosisCounts:{JUDGE_FAILED:1}},cases:[{id:'fixture-case',question:'测试问题',referenceAnswer:'独立参考答案',answer:'组件测试回答',rowVersion:0,diagnosis:'JUDGE_FAILED',needsHumanReview:true,retrievalMode:'NONE',retrievalHit:null,references:[],judgeScore:null,judgeCorrect:null,judgeReason:'测试夹具：评委结果未确认',latencyMs:12}],diagnosisGuide:{JUDGE_FAILED:'评委调用失败或输出无法解析，需要人工判断'},configuration:{topK:5,humanSampleRate:0.1,humanAgreement:{minAgreementRate:0.8,minComparableCases:5}},judgeHumanAgreement:{status:'INSUFFICIENT_SAMPLE',warning:'测试夹具：没有可比较样本，一致率暂不下结论',reviewedCases:1,comparableCases:0,agreedCases:0,agreementRate:null,cohensKappa:null,judgeCorrectRate:null,humanCorrectRate:null,scoredCases:0,meanAbsoluteScoreDiff:null,humanOnlyCases:1,disagreementCaseIds:[],minAgreementRate:0.8,minKappa:null,minComparableCases:5}}})
export const listReviews=async()=>({code:200,rows:[],total:0})
export const saveReview=async()=>({data:{}})

// 以下仅供会话切换/索引详情的本地组件技术复验；写入动作均拒绝。
const blocked=async()=>{throw new Error('技术夹具不执行该写入或外部调用')}
export const createTicket=blocked,getGeneration=blocked,createConversation=blocked,conversationAction=blocked,rebuildIndex=blocked,rollbackIndex=blocked,recoverIndex=blocked
export const getActiveGeneration=async()=>({data:null})
export const listConversations=async()=>({code:200,rows:[{id:'fixture-c1',title:'夹具会话甲',archived:false,rowVersion:1},{id:'fixture-c2',title:'夹具会话乙',archived:false,rowVersion:1}],total:2})
export const conversationMessages=async(id:string,query:Query)=>{record('conversationMessages',{id,...query});const suffix=id==='fixture-c1'?'甲':'乙';return {code:200,rows:[{id:'answer-'+id,role:'assistant',content:'夹具回答'+suffix,state:'COMPLETE',createdAt:'2026-10-03T00:00:00Z',references:[{id:'ref-'+id,documentId:'doc-'+id,title:'夹具引用'+suffix,contentVersion:1}]}],total:1}}
export const listProviders=async()=>({code:200,rows:[],total:0})
export const listIndexes=async()=>({code:200,rows:[{id:'fixture-index',name:'测试待校验索引',state:'BUILDING',provider:'测试供应商',active:false,rowVersion:1,sourceWatermark:'2026-10-03T00:00:00Z',validation:{state:'PENDING'}}],total:1})

// 新入口也仅在本地模拟。以下ID和配额均为明确夹具值，不对应业务数据。
export const pathId=(value:unknown)=>encodeURIComponent(String(value))
export const call=async(url:string)=>{record('blocked call',url);throw new Error('技术夹具不调用真实请求或二维码服务')}
const fixtureDataset={id:'builtin:fixture-resource',label:'技术夹具：登记评测资源',source:'RESOURCE' as const,ownerId:null,sha256:'fixture-sha256',byteSize:80,caseCount:1}
export const listEvaluationDatasets=async(query:Query)=>{record('listEvaluationDatasets',query);return {code:200,rows:Number(query.pageNum??1)===1?[fixtureDataset]:[],total:1}}
export const uploadEvaluationDataset=async(file:File,key:string,label=file.name)=>{record('uploadEvaluationDataset',{filename:file.name,label,byteSize:file.size,key});return {code:200,data:{...fixtureDataset,id:'fixture-upload-dataset',label,source:'UPLOAD' as const,ownerId:1,byteSize:file.size,createdAt:'2026-10-03T00:00:00Z'}}}
export const createEvaluation=async(data:Record<string,unknown>,key:string)=>{record('createEvaluation',{...data,key});return {code:200,data:{jobId:'fixture-evaluation-job',state:'SUCCEEDED'}}}
export const getJob=async()=>({code:200,data:{jobId:'fixture-evaluation-job',state:'SUCCEEDED',progress:100}})
export const listEvaluations=async()=>({code:200,rows:[{id:'fixture-evaluation',label:'技术夹具：可跳转报告',state:'COMPLETE',topK:5,rowVersion:1}],total:1})
export const createText=blocked,deleteDocument=blocked,documentAction=blocked
export const listDocuments=async(query:Query)=>{record('listDocuments',query);return {code:200,rows:[{id:'fixture-document-own',title:'技术夹具：本人上传',state:'READY',spaceId:'fixture-space',visibility:'PRIVATE',contentVersion:1,rowVersion:1},...query.mine?[]:[{id:'fixture-document-other',title:'技术夹具：可见组织文档',state:'READY',spaceId:'fixture-space',visibility:'ORGANIZATION',contentVersion:1,rowVersion:1}]],total:query.mine?1:2}}
export const listPackages=async()=>({code:200,rows:[{id:'fixture-package',name:'技术夹具：套餐选择',enabled:true,amountFen:100,llmTokens:10,embeddingTokens:20}],total:1})
const fixtureOrders=[{id:'fixture-order-r',tradeNo:'fixture-revoked',amountFen:100,state:'REVOKED'},{id:'fixture-order-p',tradeNo:'fixture-payerror',amountFen:100,state:'PAYERROR'},{id:'fixture-order-u',tradeNo:'fixture-uncertain',amountFen:100,state:'UNCERTAIN'}]
export const listOrders=async(query:Query)=>{record('listOrders',query);const rows=fixtureOrders.filter(order=>!query.state||query.state===order.state);return {code:200,rows,total:rows.length}}
export const getOrder=async(tradeNo:string)=>{record('getOrder',tradeNo);return {code:200,data:fixtureOrders.find(order=>order.tradeNo===tradeNo)??{...fixtureOrders[2],tradeNo}}}
export const createOrder=blocked,hybridSearch=blocked



export const evaluationReport=async(id:string)=>{const response=await completeEvaluationReport();return id==='fixture-partial'?{data:{...response.data,id,label:'技术夹具：取消后的部分报告',state:'CANCELED',summary:{...response.data.summary,totalCases:3,processedCases:1,pendingCases:2},configuration:{...response.data.configuration,seed:42}}}:response}

