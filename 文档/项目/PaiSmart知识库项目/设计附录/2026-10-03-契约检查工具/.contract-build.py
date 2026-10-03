from pathlib import Path
import re, json, yaml

ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).parent
API=ROOT/'ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/api'
S={}
def ref(n): return {'$ref':'#/components/schemas/'+n}
def st(description=None,**kw):
    d={'type':'string',**kw}
    if description:d['description']=description
    return d
def num(**kw):return {'type':'integer','format':'int64',**kw}
def bo(**kw):return {'type':'boolean',**kw}
def dt(**kw):return st(format='date-time',**kw)
def en(*v,**kw):return st(enum=list(v),**kw)
def arr(s,**kw):return {'type':'array','items':s,**kw}
def obj(p=None,required=(),description=None,**kw):
    d={'type':'object','properties':p or {},'additionalProperties':True,**kw}
    if required:d['required']=list(required)
    if description:d['description']=description
    return d
def schema(n,p,required=(),**kw): S[n]=obj(p,required,**kw); return ref(n)
ID=st('业务实体 UUID；平台用户标识使用 int64。',format='uuid')
NID={**ID,'nullable':True}
LONG=num(); REV=num(minimum=0); TOK=num(minimum=0)
VIS=en('PRIVATE','ORGANIZATION','PUBLIC')
SHA=st(pattern='^[a-fA-F0-9]{64}$')
KEY=st('同一业务动作重试使用原键；不能用于绕过权限或版本校验。',minLength=1,maxLength=100,pattern='^[A-Za-z0-9._:-]+$')
BASE={'code':num(),'msg':st()}
S['AjaxResult']=obj({**BASE,'data':{},'errorCode':st(),'retryAfter':num(minimum=1)},('code','msg'),description='若依共享响应。成功 code=200，失败检查 code；部分 ServiceException 仍使用 HTTP 200。无数据成功可省略 data。')
S['AjaxFailure']=obj({'code':num(not_ignored=True),'msg':st(),'errorCode':st(),'retryAfter':num(minimum=1)},('code','msg'),description='失败响应没有成功data/rows；HTTP 200也可能携带共享业务异常。')
S['AjaxFailure']['properties']['code']=num(**{'not':{'enum':[200]}})
S['AjaxSuccess']=obj({'code':num(enum=[200]),'msg':st()},('code','msg'),description='无data成功响应。')
S['TableDataInfo']=obj({**BASE,'rows':arr({}),'total':num(minimum=0)},('code','msg','rows','total'),description='若依共享列表响应。部分配置列表返回全量有限集合，只有声明 pageNum/pageSize 的路径接受分页参数。')
schema('Document',{'id':ID,'spaceId':NID,'title':st(),'filename':st(),'state':st(),'contentVersion':num(minimum=1),'rowVersion':REV,'visibility':VIS,'byteSize':num(minimum=0),'errorMessage':st(nullable=True),'estimatedTokens':TOK,'actualTokens':TOK,'ownerId':LONG,'objectKey':st(),'mimeType':st(),'deletedAt':dt(nullable=True),'createdAt':dt(),'pageCount':num(minimum=1,nullable=True)},('id','title','filename','state','contentVersion','rowVersion','visibility','byteSize','estimatedTokens','actualTokens','ownerId','objectKey','mimeType'),description='rowVersion 用于修改并发校验；contentVersion 用于内容/引用定位，二者不可互换。未解析 PDF 的 pageCount 可为 null。')
schema('Upload',{'uploadId':ID,'filename':st(),'byteSize':num(minimum=1),'sha256':SHA,'chunkSize':num(),'totalChunks':num(),'completedChunks':arr(num(minimum=0)),'state':st(),'documentId':NID,'estimatedTokens':TOK,'expiresAt':dt()},('uploadId','filename','byteSize','chunkSize','totalChunks','completedChunks','state','estimatedTokens','expiresAt'),description='completedChunks 为零基分片编号；清单保存在后台，合并由清单保证幂等。')
schema('SupportedType',{'extension':st(),'mimeType':st()},('extension','mimeType'))
schema('Space',{'id':ID,'parentId':NID,'name':st(),'type':en('PERSONAL','ORGANIZATION'),'deptId':num(nullable=True),'uploadLimit':num(minimum=-1),'rowVersion':REV,'ownerId':num(nullable=True),'canManage':bo(),'uploadMaxSizeBytes':num(minimum=1,nullable=True)},('id','name','type','uploadLimit','rowVersion','canManage'),description='uploadLimit=-1 表示文档数量不限；uploadMaxSizeBytes=null 表示非管理人员单文件上限不限。个人空间只能 PRIVATE。')
schema('SpaceMembership',{'userId':LONG,'spaceId':ID,'primary':bo(),'uploadAllowed':bo(),'managerAllowed':bo()},('userId','spaceId','primary','uploadAllowed','managerAllowed'))
schema('Assignment',{'spaceId':ID,'uploadAllowed':bo(),'managerAllowed':bo()},('spaceId','uploadAllowed','managerAllowed'))
schema('Member',{'id':LONG,'userId':LONG,'userName':st(),'nickName':st(nullable=True),'enabled':bo(),'eligible':bo(),'status':en('ACTIVE','INELIGIBLE'),'spaces':arr(ref('SpaceMembership')),'spaceIds':arr(ID),'assignments':arr(ref('SpaceMembership')),'primarySpaceId':NID},('id','userId','enabled','eligible','status','spaces','spaceIds','assignments','primarySpaceId'),description='me 无用户名字段；管理列表只返回平台数据范围及可管理组织范围元数据，不返回账号秘密。成员批量分配无行版本字段。')
schema('QuotaPolicy',{'mode':en('BALANCE','DAILY'),'dailyLlmTokens':TOK,'dailyEmbeddingTokens':TOK,'rowVersion':REV},('mode','dailyLlmTokens','dailyEmbeddingTokens','rowVersion'))
schema('Account',{'scope':en('LLM','EMBEDDING'),'available':TOK,'frozen':TOK,'consumed':TOK,'debt':TOK},('scope','available','frozen','consumed','debt'))
schema('DailyUsage',{'businessDay':st(format='date'),'scope':en('LLM','EMBEDDING'),'usedTokens':TOK,'reservedTokens':TOK,'requestCount':TOK},('businessDay','scope','usedTokens','requestCount'),description='全局汇总行不含 reservedTokens。')
schema('UsageRecord',{'id':ID,'scope':en('LLM','EMBEDDING'),'action':st(),'tokens':LONG,'availableAfter':TOK,'frozenAfter':TOK,'debtAfter':TOK,'reason':st(),'createdAt':dt()},('id','scope','action','tokens','availableAfter','frozenAfter','debtAfter','createdAt'))
schema('Usage',{'userId':LONG,'accounts':arr(ref('Account')),'daily':arr(ref('DailyUsage')),'records':arr(ref('UsageRecord')),'policy':ref('QuotaPolicy')},('accounts','daily'),description='本人或指定用户含 userId、最近流水与 policy；无 userId 的全局汇总仅 accounts 和 daily。')
schema('TokenWindowRule',{'minuteMax':num(minimum=1),'minuteWindowSeconds':num(minimum=1),'dayMax':num(minimum=1),'dayWindowSeconds':num(minimum=1)},('minuteMax','minuteWindowSeconds','dayMax','dayWindowSeconds'))
schema('RateLimitRules',{'chatMessage':obj({'max':num(minimum=1),'windowSeconds':num(minimum=1)},('max','windowSeconds')) ,**{k:ref('TokenWindowRule') for k in ['llmGlobalToken','embeddingUploadToken','embeddingQueryRequest','embeddingQueryGlobalToken']}},('chatMessage','llmGlobalToken','embeddingUploadToken','embeddingQueryRequest','embeddingQueryGlobalToken'))
schema('RateLimits',{'chatPerMinute':num(minimum=1),'uploadPerMinute':num(minimum=1),'dailyChatTokens':num(minimum=1),'maxFileBytes':num(minimum=1),'rowVersion':REV,'rules':ref('RateLimitRules')},('chatPerMinute','uploadPerMinute','dailyChatTokens','maxFileBytes','rowVersion','rules'),description='完整五组窗口规则。chatPerMinute 为兼容字段，保存 rules.chatMessage.max 后归一；旧输入 maxRequests 可兼容，但输出仅 max。')
schema('Invite',{'id':ID,'code':st(),'spaceId':NID,'maxUses':num(minimum=1),'usedCount':TOK,'expiresAt':dt(nullable=True),'enabled':bo(),'initialLlmTokens':TOK,'initialEmbeddingTokens':TOK,'rowVersion':REV},('id','code','maxUses','usedCount','enabled','initialLlmTokens','initialEmbeddingTokens','rowVersion'))
schema('RechargePackage',{'id':ID,'name':st(),'amountFen':num(minimum=1),'llmTokens':TOK,'embeddingTokens':TOK,'description':st(),'enabled':bo(),'sortOrder':num(),'rowVersion':REV},('id','name','amountFen','llmTokens','embeddingTokens','enabled','sortOrder','rowVersion'))
schema('Order',{'id':ID,'tradeNo':st(),'userId':LONG,'packageId':NID,'amountFen':num(minimum=1),'llmTokens':TOK,'embeddingTokens':TOK,'state':en('CREATING','PREPAY_STARTED','UNCERTAIN','NOT_PAY','PAYING','SUCCEEDED','CLOSED','FAILED'),'codeUrl':st(nullable=True),'expiresAt':dt(),'errorMessage':st(nullable=True),'snapshot':obj()},('id','tradeNo','userId','amountFen','llmTokens','embeddingTokens','state','expiresAt','snapshot'),description='PREPAY_STARTED/UNCERTAIN 保留原订单号，须查询/对账，不自动重新预支付。仅 NOT_PAY/PAYING 且未过期允许二维码；SUCCEEDED 已成功入账。')
schema('Evidence',{'id':ID,'documentId':ID,'contentVersion':num(minimum=1),'chunkNo':num(),'page':num(minimum=1,nullable=True),'title':st(),'text':st(),'anchor':st(),'context':st(),'score':{'type':'number','format':'double'},'scoreType':st()},('id','documentId','contentVersion','chunkNo','title','text','anchor','context','score','scoreType'))
schema('Reference',{'id':ID,'documentId':ID,'title':st(),'number':num(minimum=1),'contentVersion':num(minimum=1),'page':num(minimum=1,nullable=True),'text':st(),'anchor':st(),'verification':obj(),'verified':bo(nullable=True)},('id','documentId','title','contentVersion','page','text','anchor'),description='引用注册结果含 number；读取结果含 verification/verified。每次读取重新检查文档权限和内容版本。')
schema('SearchResult',{'hits':arr(ref('Evidence')),'scoreType':st(),'degraded':bo(),'reason':st(),'indexRevision':ID},('hits','scoreType','degraded','reason','indexRevision'))
schema('GenerationSnapshot',{'generationId':ID,'conversationId':ID,'messageId':ID,'state':en('QUEUED','RUNNING','COMPLETE','STOPPED','FAILED','INTERRUPTED'),'errorMessage':st(nullable=True),'text':st(),'references':arr(ref('Reference')),'lastSequence':num(minimum=0)},('generationId','conversationId','messageId','state','errorMessage','text','references','lastSequence'),description='HTTP 查询和 WS 初次/恢复返回同一快照，快照无 kind/sequence；事件协议见 stream-v1.schema.json。')
schema('Message',{'id':ID,'role':en('user','assistant','tool'),'content':st(),'state':st(),'references':arr(ref('Reference')),'createdAt':dt()},('id','role','content','state','references','createdAt'))
schema('Conversation',{'id':ID,'title':st(),'archived':bo(),'spaceId':NID,'rowVersion':REV,'createdAt':dt(),'updatedAt':dt()},('id','title','archived','rowVersion','createdAt','updatedAt'))
schema('ConversationDetail',{'id':ID,'user_id':LONG,'title':st(),'archived':bo(),'space_id':NID,'row_version':REV,'spaceId':NID,'rowVersion':REV,'created_at':dt(),'updated_at':dt()},('id','title','archived','spaceId','rowVersion'),description='当前 create/select 接口返回数据库行字段，并补 camelCase spaceId/rowVersion；列表字段另见 Conversation。')
schema('AuditConversation',{'id':ID,'userId':LONG,'userName':st(),'title':st(),'archived':bo(),'spaceId':NID,'updatedAt':dt(),'messageCount':TOK},('id','userId','title','archived','updatedAt','messageCount'))
schema('Provider',{'id':ID,'scope':en('LLM','EMBEDDING','RERANK'),'name':st(),'provider':st(),'endpoint':st(),'address':st(),'model':st(),'dimension':num(),'secretRef':st(),'enabled':bo(),'active':bo(),'configured':bo(),'rowVersion':REV,'config':obj(),'updatedAt':dt()},('scope','provider','address','model','enabled','configured','rowVersion'),description='未配置 scope 返回空的可编辑默认配置，无 id；已配置条目同时保留 name/endpoint 及 provider/address 兼容字段。secretRef 仅托管用途 ai.paismart.*，不接受明文 key。')
schema('ProviderSaved',{'id':ID,'scope':st(),'rowVersion':REV},('id','scope','rowVersion'))
schema('ProviderTest',{'state':en('READY'),'reachable':bo(),'latencyMs':TOK,'responsePresent':bo()},('state','reachable','latencyMs','responsePresent'))
schema('Conflict',{'id':ID,'newDocumentId':ID,'oldDocumentId':ID,'newVersion':num(),'oldVersion':num(),'reason':obj(),'status':st(),'action':st(nullable=True),'rowVersion':REV,'createdAt':dt(),'newTitle':st(),'oldTitle':st()},('id','newDocumentId','oldDocumentId','reason','status','rowVersion'),description='真实字段 status 与对象 reason，前端适配为显示 state/文本。')
schema('ConflictResolved',{'id':ID,'action':en('KEEP_NEW','KEEP_EXISTING','IGNORED'),'state':en('RESOLVED'),'documentId':st()},('id','action','state','documentId'))
schema('IndexRevision',{'id':ID,'name':st(),'state':st(),'providerSnapshot':st('序列化后的供应商快照 JSON 字符串。'),'checkpoint':st(nullable=True),'processedCount':TOK,'rowVersion':REV,'errorMessage':st(nullable=True),'createdAt':dt()},('id','name','state','providerSnapshot','processedCount','rowVersion','createdAt'))
schema('Evaluation',{'id':ID,'label':st(),'state':st(),'rowVersion':REV,'createdAt':dt()},('id','label','state','rowVersion','createdAt'))
schema('EvaluationCase',{'id':st(),'question':st(),'expected':st(),'answer':st(),'state':st(),'rowVersion':REV,'references':arr(ref('Evidence')),'recall':{'type':'number'},'mrr':{'type':'number'},'ndcg':{'type':'number'},'coverage':{'type':'number'},'retrievalEvaluable':bo(),'judge':obj(),'diagnostics':arr(st()),'humanReviewReason':st(nullable=True)},('id','question','rowVersion','diagnostics'),description='失败用例可能没有 answer 或检索指标；judge 结构来自模型经格式验证的结果。')
schema('EvaluationReport',{'id':ID,'label':st(),'state':st(),'cases':arr(ref('EvaluationCase')),'summary':obj(),'configuration':obj(),'judgeHumanAgreement':obj()},('id','label','state','cases','summary'),description='运行未结束时 summary 为空；完成后有 samples/recall/mrr/ndcg/humanReviewNeeded，另附真实配置快照。')
schema('Review',{'id':ID,'caseId':st(),'reviewerId':LONG,'correct':bo(nullable=True),'score':num(minimum=1,maximum=5,nullable=True),'reason':st(),'rowVersion':REV,'createdAt':dt()},('id','caseId','reviewerId','correct','reason','rowVersion','createdAt'))
schema('ReviewSaved',{'id':ID,'caseId':st(),'correct':bo(nullable=True),'conclusion':st(),'rowVersion':REV},('id','caseId','correct','conclusion','rowVersion'))
schema('Job',{'id':ID,'userId':LONG,'documentId':NID,'type':st(),'state':en('QUEUED','RUNNING','RETRY','SUCCEEDED','FAILED','CANCELED'),'payload':obj(),'result':obj(),'leaseToken':NID,'leaseUntil':dt(nullable=True),'retries':TOK,'errorMessage':st(nullable=True),'rowVersion':REV},('id','userId','type','state','payload','result','retries','rowVersion'),description='MySQL 为任务真相；后台管理 Job 记录不同于智能创建入口的 JobSummary。载荷按 jobs-v1.asyncapi.yaml 中的内部任务定义解释。')
schema('JobSummary',{'id':ID,'jobId':ID,'state':st(),'kind':st(),'rowVersion':REV,'result':obj()},('id','jobId','state','kind','rowVersion','result'))
schema('Activity',{'id':ID,'userId':LONG,'userName':st(nullable=True),'action':st(),'target':st(),'reason':st(),'createdAt':dt()},('id','userId','action','target','createdAt'))
schema('OperationStatus',{'components':arr(obj({'name':st(),'state':st(),'reason':st(),'tested':bo(),'scope':st()},('name','state','reason'))),'configurationErrors':arr(st())},('components','configurationErrors'),description='配置状态、表结构检查与真实外部探测分别标示，不把 CONFIGURED_NOT_PROBED 解释为联机成功。')
PLAN_FIELDS={'id':ID,'type':en('cleanup-plans','object-migrations','bootstrap-import'),'state':st(),'reason':st(),'createdBy':LONG,'approvedBy':num(nullable=True),'planVersion':SHA,'rowVersion':REV,'createdAt':dt(),'jobId':NID,'manifest':obj()}
schema('OperationPlan',PLAN_FIELDS,('id','state','planVersion','rowVersion'),description='列表不含 manifest；创建返回 manifest 而未含 type；详情含 id/type/state/manifest/planVersion/rowVersion。批准必须由另一名具有该资源范围的操作人完成。')
schema('OcrBlock',{'text':st(),'bbox':arr({'type':'number'},minItems=4,maxItems=4),'confidence':{'type':'number','minimum':0,'maximum':1}},('text','bbox','confidence'))
schema('OcrResult',{'results':arr(ref('OcrBlock'))},('results',))
schema('OcrHealth',{'enabled':bo(),'configured':bo(),'state':en('CONFIGURED_NOT_TESTED','DISABLED_OR_MISSING')},('enabled','configured','state'))
schema('OcrHealthTest',{'state':en('READY'),'callId':ID,'actualPages':num(enum=[1]),'amountState':en('PROVIDER_BILL_REQUIRED'),'results':arr(ref('OcrBlock'))},('state','callId','actualPages','amountState','results'))
schema('OcrCall',{'id':ID,'userId':LONG,'jobId':NID,'documentId':NID,'contentVersion':num(nullable=True),'state':st(),'imageBytes':TOK,'providerRequestId':st(nullable=True),'createdAt':dt(),'reason':st(nullable=True)},('id','userId','state','imageBytes','createdAt'))
schema('ModelCall',{'id':ID,'userId':LONG,'operationId':st(),'scope':st(),'status':st(),'reason':st(nullable=True),'createdAt':dt()},('id','userId','operationId','scope','status','createdAt'))
schema('ModelCallReconciled',{'id':ID,'status':en('RECONCILED'),'actualTokens':TOK},('id','status','actualTokens'))

# 输入按 Controller / Service 实际接受字段定义，不把前端额外发送但后台忽略的字段变成必要协议。
schema('TextInput',{'title':st(maxLength=255),'content':st(),'body':st('content 为空时的兼容字段。'),'spaceId':NID,'visibility':VIS,'requestKey':KEY},('title',),description='需 content 或 body；需 requestKey 或 Idempotency-Key。个人空间或空 spaceId 只能 PRIVATE。')
schema('DocumentAction',{'rowVersion':num(minimum=1),'expectedVersion':num(minimum=1),'requestKey':KEY},description='rowVersion 优先，否则 expectedVersion；至少一个当前行版本。重处理另需 requestKey 或 Idempotency-Key；restore 忽略 requestKey。')
schema('UploadInput',{'filename':st(),'byteSize':num(minimum=1,maximum=536870912),'sha256':SHA,'chunkSize':num(minimum=65536,maximum=10485760,default=1048576),'spaceId':NID,'visibility':VIS,'requestKey':KEY},('filename','byteSize','sha256'),description='总分片不超过 10000；大小还受 DB maxFileBytes 和组织单文件上限约束。需 requestKey 或 Idempotency-Key，键重复时核对原清单。')
schema('SpaceInput',{'parentId':NID,'name':st(),'deptId':num(nullable=True),'uploadLimit':num(minimum=-1,default=-1),'rowVersion':num(minimum=1),'expectedVersion':num(minimum=1),'uploadMaxSizeBytes':num(minimum=1,nullable=True)},('name',),description='更新必须在 body 提交当前 rowVersion 或 expectedVersion；不使用 If-Match。')
schema('SpaceMemberInput',{'userId':LONG,'uploadAllowed':bo(),'managerAllowed':bo()},('userId',))
schema('MemberSpacesInput',{'assignments':arr(ref('Assignment')),'primarySpaceId':NID},('assignments',),description='替换执行者可管理的组织空间分配；保留其无权管理的分配，拒绝 PERSONAL，整个操作事务执行。无 rowVersion/expectedVersion 并发字段。')
schema('PrimarySpaceInput',{'spaceId':ID},('spaceId',))
schema('EligibilityInput',{'code':st()},('code',))
schema('CreditInput',{'reason':st(minLength=1,maxLength=500),'llmTokens':num(minimum=0,maximum=1000000000000,default=0),'chatTokens':num(minimum=0,maximum=1000000000000),'embeddingTokens':num(minimum=0,maximum=1000000000000,default=0)},('reason',),description='llmTokens 优先，chatTokens 为兼容别名；必须是精确整数。')
schema('QuotaPolicyInput',{'mode':en('BALANCE','DAILY'),'dailyLlmTokens':TOK,'dailyEmbeddingTokens':TOK})
INV_FIELDS={'code':st(pattern='^[A-Za-z0-9_-]{6,64}$'),'spaceId':NID,'maxUses':num(minimum=1,maximum=1000000,default=1),'expiresAt':dt(nullable=True),'enabled':bo(default=True),'initialLlmTokens':num(minimum=0,maximum=1000000000000),'initialEmbeddingTokens':num(minimum=0,maximum=1000000000000)}
schema('InviteInput',INV_FIELDS,description='创建 code 可省略由后台生成；修改不能重绑 code/spaceId。组织绑定需要其真实管理范围。')
schema('InviteBatchInput',{**INV_FIELDS,'count':num(minimum=1,maximum=100)},('count',))
schema('PackageInput',{'name':st(),'amountFen':num(minimum=1,maximum=2147483647),'llmTokens':num(minimum=0,maximum=1000000000000),'embeddingTokens':num(minimum=0,maximum=1000000000000),'description':st(),'enabled':bo(),'sortOrder':num()},('name','amountFen'))
schema('OrderInput',{'packageId':ID,'customAmountFen':num(minimum=1,maximum=2147483647)},description='选择 packageId 或 customAmountFen；自定义金额按当前最低单位套餐比例生成权益快照，后台检查有效套餐。')
schema('TicketInput',{'origin':st('Origin 请求头优先；代理请求无 Origin 时使用此字段。')})
schema('Ticket',{'ticket':st(pattern='^[A-Za-z0-9_-]{43}$'),'expiresIn':num(enum=[60])},('ticket','expiresIn'))
schema('ConversationInput',{'title':st(maxLength=160),'spaceId':NID})
schema('ConversationAction',{'expectedVersion':num(minimum=1)},description='archive/unarchive 需当前版本，switch 不检查版本。')
schema('FeedbackInput',{'messageId':ID,'rating':en('GOOD','BAD','good','bad'),'reason':st(),'note':st()},('messageId','rating'))
schema('ProviderInput',{'id':st('有 id 更新指定条目；空 id 明确新增。'),'provider':st(maxLength=100),'name':st(),'model':st(),'address':st(),'endpoint':st(),'dimension':num(),'secretRef':st('仅允许托管用途 ai.paismart.*；空值保留已有引用。'),'enabled':bo(),'active':bo(),'rowVersion':REV,'revision':REV,'config':obj({'providerCode':st('显式值优先，需匹配托管元数据 providerCode。'),'capabilityCode':en('llm','embedding','rerank')})},description='复用共享托管密钥，不传明文 key/env。修改使用 rowVersion（兼容 revision）；新建版本为 0。')
schema('ConflictResolveInput',{'action':en('KEEP_NEW','KEEP_EXISTING','IGNORED'),'note':st(minLength=1,maxLength=1000),'expectedVersion':num(minimum=1)},('action','note','expectedVersion'))
schema('IndexRebuildInput',{'targetProvider':ID,'fresh':bo(default=False)},('targetProvider',))
schema('IndexRollbackInput',{'indexId':ID,'expectedVersion':num(minimum=1)},('indexId','expectedVersion'))
schema('EvaluationInput',{'label':st(minLength=1,maxLength=160),'inlineJsonl':st(minLength=1,maxLength=900000),'topK':num(minimum=1,maximum=20,default=5),'spaceId':NID,'seed':num(default=42)},('label','inlineJsonl'),description='JSONL 最多 500 个样本，允许空行/# 注释；question 或 query 必需，可用 expectedDocumentIds/expectedFileNames/referenceAnswer；旧 expectedFileMd5s 不接受。')
schema('ReviewInput',{'caseId':st(minLength=1,maxLength=100),'conclusion':en('CORRECT','INCORRECT','correct','incorrect','PASS','FAIL','SUPPORTED','UNSUPPORTED','NEEDS_EVIDENCE'),'reason':st(minLength=1,maxLength=2000),'expectedVersion':num(minimum=0,default=0),'score':num(minimum=1,maximum=5)},('caseId','conclusion','reason'),description='NEEDS_EVIDENCE 保存 correct=null；报告一致率排除无结论记录。')
schema('OperationManifest',{'reason':st(minLength=1,maxLength=500),'documents':arr(obj({'id':ID,'sourceKey':st(),'targetKey':st(),'sha256':SHA,'byteSize':num(minimum=1)})),'files':arr(obj({'sourceKey':st(),'filename':st(),'byteSize':num(minimum=1),'sha256':SHA,'spaceId':NID,'visibility':VIS,'ownerId':LONG}))},('reason',),description='cleanup-plans 由后台重新生成超过30天的删除候选，忽略客户端 documents；迁移 targetKey 限 paismart/documents/<id>/，导入 sourceKey 限 paismart/imports/。最多1000项、计划JSON不超过200万字符，执行任务载荷另受100万字符预算约束。')
schema('PlanVersionInput',{'planVersion':SHA},('planVersion',))
schema('OcrReconcileInput',{'actualPages':num(enum=[0,1]),'reason':st(minLength=1,maxLength=500)},('actualPages','reason'))
schema('ModelReconcileInput',{'actualTokens':TOK,'reason':st(minLength=1,maxLength=500)},('actualTokens','reason'))
schema('WechatCallback',{'id':st(),'create_time':dt(),'event_type':st(),'resource_type':st(),'summary':st(),'resource':obj({'algorithm':st(),'ciphertext':st(),'associated_data':st(),'nonce':st()},('algorithm','ciphertext','nonce'))},('resource',),description='微信支付 v3 原始通知信封；交官方 SDK 校验签名并解密，再核对商户、应用、CNY、金额、交易号。不得发送明文支付成功对象替代签名通知。')

def ajax(n):
    name=n+'Result'
    if name not in S:S[name]=obj({'code':num(enum=[200]),'msg':st(),'data':ref(n)},('code','msg'),description='成功 data 为 '+n+'；无数据成功可省略 data。')
    return ref(name)
def table(n):
    name=n+'Page'
    if name not in S:S[name]=obj({'code':num(enum=[200]),'msg':st(nullable=True),'rows':arr(ref(n)),'total':num(minimum=0)},('code','rows','total'),description='本业务通过 PaismartTables.page 统一成功 code=200,msg=查询成功；失败响应另见 AjaxResult。')
    return ref(name)
def list_result(n):
    name=n+'ListResult'
    if name not in S:S[name]=obj({'code':num(enum=[200]),'msg':st(),'data':arr(ref(n))},('code','msg'))
    return ref(name)

# Controller 名 / Java 方法名 -> 中文行为、成功响应、请求体。
M={}
def mapping(c,name,title,result='AjaxResult',body=None,kind='ajax',description=None):M[c,name]=(title,result,body,kind,description)
for name,title,res,body,kind in [
 ('list','查询文档','Document',None,'table'),('detail','读取文档详情','Document',None,'ajax'),('text','新建文本知识','Document','TextInput','ajax'),('content','读取受控文本','Document',None,'text'),('page','读取 PDF 单页','Document',None,'pdf'),('download','下载原件','Document',None,'binary'),('delete','软删除文档','Document',None,'ajax'),('restore','恢复文档','Document','DocumentAction','ajax'),('reprocess','重处理文档','Document','DocumentAction','ajax')]:mapping('DocumentController',name,title,res,body,kind)
for name,title,res,body,kind in [('types','查询支持文件类型','SupportedType',None,'list'),('create','建立分片清单','Upload','UploadInput','ajax'),('status','查询上传清单','Upload',None,'ajax'),('chunk','上传原始分片','Upload',None,'chunk'),('multipart','上传表单分片','Upload',None,'multipartChunk'),('merge','合并上传并建立文档','Document',None,'ajax')]:mapping('UploadController',name,title,res,body,kind)
for name,title,res,body,kind in [('list','列出可见空间','Space',None,'table'),('personal','建立本人个人空间','Space',None,'ajax'),('create','建立组织空间','Space','SpaceInput','ajax'),('update','修改组织空间','Space','SpaceInput','ajax'),('delete','删除空组织空间','AjaxResult',None,'empty'),('members','读取空间成员','SpaceMembership',None,'list'),('member','分配空间成员权限','AjaxResult','SpaceMemberInput','empty'),('removeMember','移除空间成员','AjaxResult',None,'empty'),('primary','设置本人主空间','AjaxResult','PrimarySpaceInput','empty')]:mapping('SpaceController',name,title,res,body,kind)
for name,title,res,body,kind in [('me','读取本人业务资格','Member',None,'ajax'),('claim','通过邀请码取得资格','Member','EligibilityInput','ajax'),('members','查询管理范围成员','Member',None,'table'),('spaces','批量保存成员空间分配','Member','MemberSpacesInput','ajax'),('credit','为成员追加额度','AjaxResult','CreditInput','empty'),('quota','读取或保存成员额度策略','QuotaPolicy','QuotaPolicyInput','ajax')]:mapping('MemberController',name,title,res,body,kind)
for name,title,res,body,kind in [('me','读取本人额度与用量','Usage',None,'ajax'),('overview','读取授权范围用量汇总','Usage',None,'ajax'),('records','查询额度流水','UsageRecord',None,'table')]:mapping('UsageController',name,title,res,body,kind)
mapping('RateLimitController','get','读取限流规则','RateLimits')
mapping('RateLimitController','put','保存完整限流规则','RateLimits','RateLimits')
for name,title,res,body,kind in [('list','查询可管理邀请码','Invite',None,'table'),('create','创建邀请码','Invite','InviteInput','ajax'),('batch','批量创建邀请码','Invite','InviteBatchInput','list'),('update','修改邀请码限制','Invite','InviteInput','ajax'),('delete','停用邀请码','AjaxResult',None,'empty')]:mapping('InviteController',name,title,res,body,kind)
for name,title,res,body,kind in [('packages','读取可购买套餐','RechargePackage',None,'table'),('adminPackages','读取全部充值套餐','RechargePackage',None,'table'),('createPackage','创建充值套餐','RechargePackage','PackageInput','ajax'),('updatePackage','修改充值套餐','RechargePackage','PackageInput','ajax'),('deletePackage','删除或停用充值套餐','AjaxResult',None,'empty'),('createOrder','创建原支付订单','Order','OrderInput','ajax'),('order','查询原订单并核对支付状态','Order',None,'ajax'),('orders','分页读取本人历史订单','Order',None,'table'),('qr','读取有效订单支付二维码','Order',None,'png'),('callback','接收微信支付签名通知','AjaxResult','WechatCallback','callback')]:mapping('RechargeController',name,title,res,body,kind)
for name,title,res,body,kind in [('status','读取依赖与配置状态','OperationStatus',None,'ajax'),('jobs','分页读取可操作作业','Job',None,'table'),('job','读取可操作作业','Job',None,'ajax'),('retry','重试可操作作业','Job',None,'ajax'),('cancel','取消可操作作业','Job',None,'ajax'),('ownJob','读取本人作业','Job',None,'ajax'),('activities','分页读取活动审计','Activity',None,'table'),('plan','创建或读取不可变运维计划','OperationPlan','OperationManifest','ajax'),('plans','分页读取可管理计划','OperationPlan',None,'table'),('approve','由另一名授权操作人批准计划','OperationPlan','PlanVersionInput','ajax'),('execute','执行已批准运维计划','Job','PlanVersionInput','ajax')]:mapping('OperationController',name,title,res,body,kind)
for name,title,res,body,kind in [
 ('search','权限过滤的混合检索','SearchResult',None,'ajax'),('ticket','签发一次性 WS 票据','Ticket','TicketInput','ajax'),('upgradeRequired','聊天 WebSocket 升级入口','AjaxResult',None,'ws'),('ocr','受限内部 OCR 回调','OcrResult',None,'ocr'),('ocrHealth','读取 OCR 配置状态','OcrHealth',None,'ajax'),('ocrHealthTest','执行真实 OCR 健康调用','OcrHealthTest',None,'file'),('ocrCalls','读取待核对 OCR 调用','OcrCall',None,'table'),('reconcileOcr','核对 OCR 实际页数','AjaxResult','OcrReconcileInput','empty'),('modelCalls','读取待核对模型调用','ModelCall',None,'table'),('reconcile','核对模型实际 token','ModelCallReconciled','ModelReconcileInput','ajax'),('audit','分页读取会话审计元数据','AuditConversation',None,'table'),('auditMessages','分页读取授权会话审计正文','Message',None,'table'),('generation','读取本人生成快照','GenerationSnapshot',None,'ajax'),('active','读取本人活动生成快照','GenerationSnapshot',None,'ajax'),('stop','请求停止生成','AjaxResult',None,'empty'),('reference','二次授权读取引用','Reference',None,'ajax'),('conversations','分页读取本人会话','Conversation',None,'table'),('createConversation','创建本人会话','ConversationDetail','ConversationInput','ajax'),('messages','分页读取本人会话消息','Message',None,'table'),('conversationAction','归档、恢复或选择会话','ConversationDetail','ConversationAction','ajax'),('feedback','保存本人消息反馈','AjaxResult','FeedbackInput','empty'),('providers','读取供应商配置列表','Provider',None,'table'),('provider','读取能力范围供应商配置','Provider',None,'ajax'),('saveProvider','保存能力范围供应商配置','ProviderSaved','ProviderInput','ajax'),('test','执行真实供应商健康调用','ProviderTest',None,'ajax'),('conflicts','分页读取可见冲突','Conflict',None,'table'),('resolve','裁决文档冲突','ConflictResolved','ConflictResolveInput','ajax'),('indexes','读取索引修订列表','IndexRevision',None,'table'),('rebuild','创建索引重建任务','JobSummary','IndexRebuildInput','ajax'),('rollback','创建索引回滚任务','JobSummary','IndexRollbackInput','ajax'),('recover','创建索引别名恢复任务','JobSummary',None,'ajax'),('evaluations','分页读取本人评测','Evaluation',None,'table'),('evaluation','创建离线评测任务','JobSummary','EvaluationInput','ajax'),('report','读取真实评测报告','EvaluationReport',None,'ajax'),('reviews','读取用例人工复核记录','Review',None,'table'),('review','保存人工复核修订','ReviewSaved','ReviewInput','ajax')]:mapping('IntelligenceController',name,title,res,body,kind)

def close_paren(t,start):
    depth=0;quote=False;esc=False
    for i in range(start,len(t)):
        ch=t[i]
        if quote:
            if esc:esc=False
            elif ch=='\\':esc=True
            elif ch=='"':quote=False
        elif ch=='"':quote=True
        elif ch=='(':depth+=1
        elif ch==')':
            depth-=1
            if depth==0:return i
    raise ValueError('unbalanced annotation')
routes=[]
for f in sorted(API.glob('*Controller.java')):
    t=f.read_text(encoding='utf-8'); classpos=t.index('public class')
    prefix=re.search(r'@RequestMapping\("([^"]+)"\)',t[:classpos]).group(1)
    classperm=re.findall(r"paismart:[a-z]+:[a-z]+",t[:classpos])
    for mt in re.finditer(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\b',t[classpos:]):
        start=classpos+mt.start();end=classpos+mt.end();a=''
        if t[end:end+1]=='(':ae=close_paren(t,end);a=t[end+1:ae];end=ae+1
        sig=re.search(r'public\s+([\w<>?,.\[\]]+)\s+(\w+)\s*\(',t[end:])
        if not sig:raise ValueError(f'{f}:{start}: signature')
        sigstart=end+sig.end()-1;sigend=close_paren(t,sigstart);params=t[sigstart+1:sigend];name=sig.group(2)
        ann=t[t.rfind('\n',0,start)+1:end+sig.start()];perm=re.findall(r"paismart:[a-z]+:[a-z]+",ann) or classperm
        # Mapping path strings precede method/consumes/headers conditions.
        part=re.split(r',\s*(?:method|consumes|produces|headers)\s*=',a)[0]
        paths=re.findall(r'"(/[^"]*)"',part) or ['']
        methods=[mt.group(1).lower()] if mt.group(1)!='Request' else [x.lower() for x in re.findall(r'RequestMethod\.(\w+)',a)]
        for path in paths:
            for method in methods:routes.append({'controller':f.stem,'name':name,'method':method,'path':prefix+path,'params':params,'ann':ann,'perm':perm,'line':t.count('\n',0,start)+1,'source':str(f.relative_to(ROOT)).replace('\\','/')})

S['Document']['properties']['jobId']=NID
S['TableDataInfo']['properties']['msg']=st(nullable=True)
S['TableDataInfo']['description']='若依共享列表响应，本业务成功 code=200,msg=查询成功。仅声明 pageNum/pageSize 的路径接受分页，其余配置列表返回全量有限集合。'
S['FeedbackInput']['properties'].pop('note')
S['FeedbackInput']['properties']['reason']['maxLength']=1000
S['TextInput']['properties']['content'].update(minLength=1,maxLength=5000000)
S['TextInput']['properties']['body'].update(minLength=1,maxLength=5000000)
S['Member']['properties']['rowVersion']=REV
S['Member']['required'].append('rowVersion')
S['Member']['description']='me 无用户名字段；管理列表只返回平台数据范围及可管理组织范围元数据。rowVersion 为真实成员空间分配版本，未建立版本记录时为0。'
S['MemberSpacesInput']['properties'].update({'rowVersion':REV,'expectedVersion':REV})
S['MemberSpacesInput']['description']='替换执行者可管理的组织空间分配，保留无权管理及个人空间分配；整个操作事务执行。必须给当前版本：If-Match优先，其次body.rowVersion，再expectedVersion；0表示尚未建立版本记录。主空间不得越过管理范围。'
paths={}
for i,r in enumerate(routes):
    k=(r['controller'],r['name'])
    if k not in M:raise ValueError('unmapped Java method '+str(k))
    title,result,body,kind,desc=M[k]; method=r['method']; path=r['path']
    op={'operationId':r['controller'].replace('Controller','')+'_'+r['name']+'_'+method+'_'+re.sub(r'[^A-Za-z0-9]+','_',path).strip('_'),'summary':title,'tags':[r['controller'].replace('Controller','')],'x-source':r['source']+':'+str(r['line']),'responses':{}}
    if r['perm']:
        op['x-ruoyi-permissions']=r['perm']
        op['x-ruoyi-permission-mode']='any' if 'hasAnyPermi' in r['ann'] else 'all'
    if '@Anonymous' in r['ann']:op['security']=[]
    pars=[]
    for pn in re.findall(r'\{(\w+)\}',path):
        ps=ID if pn=='id' else st()
        if pn=='id' and r['controller']=='MemberController':ps=LONG
        if pn=='userId':ps=LONG
        if pn=='number':ps=num(minimum=0 if r['controller']=='UploadController' else 1)
        if pn=='scope':ps=en('LLM','EMBEDDING','RERANK')
        if pn=='action':ps=en('archive','unarchive','switch')
        if pn=='kind':ps=en('cleanup-plans','object-migrations','bootstrap-import')
        pars.append({'name':pn,'in':'path','required':True,'schema':ps})
    for pm in re.finditer(r'@(RequestParam|RequestHeader)\b',r['params']):
        pe=pm.end();pa=''
        if r['params'][pe:pe+1]=='(':ae=close_paren(r['params'],pe);pa=r['params'][pe+1:ae];pe=ae+1
        vm=re.match(r'\s*([\w.<>]+)\s+(\w+)',r['params'][pe:]);
        if not vm:raise ValueError('param '+r['params'][pe:])
        jt,vn=vm.groups();strings=re.findall(r'"([^"]*)"',pa);explicit=re.search(r'(?:value|name)\s*=\s*"([^"]+)"',pa)
        pn=explicit.group(1) if explicit else (strings[0] if strings and not re.search(r'defaultValue\s*=',pa) else vn)
        if 'MultipartFile' in jt or (kind=='multipartChunk' and vn=='sha256'):continue
        default=re.search(r'defaultValue\s*=\s*"([^"]*)"',pa);required='required=false' not in pa.replace(' ','') and not default
        ps=bo() if jt=='boolean' else num() if jt in ['long','int','Long','Integer'] else st()
        if default:
            dv=default.group(1);ps['default']=dv=='true' if jt=='boolean' else int(dv) if jt in ['long','int','Long','Integer'] else dv
        if pn=='pageNum':ps.update(minimum=1)
        if pn=='pageSize':ps.update(minimum=1,maximum=100)
        if pn=='Idempotency-Key':
            ps=KEY if r['controller'] in ['DocumentController','UploadController'] else st('保留原请求键用于同一动作重试。',minLength=1)
            if r['controller'] in ['RechargeController','InviteController']:ps['maxLength']=128
            if r['controller']=='IntelligenceController' and r['name']=='ocrHealthTest':ps['maxLength']=100
            if r['controller']=='IntelligenceController' and r['name'] in ['rebuild','rollback','recover','evaluation']:ps['maxLength']=160
        if pn=='X-Chunk-SHA256':ps=SHA
        if pn=='visibility':ps=VIS
        pars.append({'name':pn,'in':'query' if pm.group(1)=='RequestParam' else 'header','required':required,'schema':ps})
    if pars:op['parameters']=pars
    if body and '@RequestBody' in r['params']:op['requestBody']={'required':'@RequestBody(required=false)' not in r['params'].replace(' ',''),'content':{'application/json':{'schema':ref(body)}}}
    success=ref('AjaxSuccess') if kind=='empty' else table(result) if kind=='table' else list_result(result) if kind=='list' else ajax(result)
    if kind in ['text','pdf','binary','png']:
        media={'text':'text/plain;charset=UTF-8','pdf':'application/pdf','binary':'application/octet-stream','png':'image/png'}[kind]
        successcontent={media:{'schema':st(format='binary')},'application/json':{'schema':ref('AjaxFailure')}}
        hdr={'Cache-Control':{'schema':st(enum=['no-store'])}}
        if kind!='png':hdr['X-Content-Type-Options']={'schema':st(enum=['nosniff'])}
        if kind=='binary':hdr['Content-Disposition']={'schema':st(),'description':'attachment，UTF-8 文件名。'}
        op['responses']['200']={'description':'成功直接返回文件/文本，无 Ajax 包裹；共享 ServiceException 可能仍以 HTTP 200 返回 Ajax 错误 JSON。','headers':hdr,'content':successcontent}
    elif kind=='callback':op['responses']['204']={'description':'官方验签解密及订单幂等入账完成，无响应体。'}
    elif kind=='ws':
        op['parameters']=[{'name':'ticket','in':'query','required':True,'schema':st(pattern='^[A-Za-z0-9_-]{43}$')},{'name':'Origin','in':'header','required':True,'schema':st()}]
        op['responses']['101']={'description':'实际 WebSocket 升级由 Spring WebSocket handler 处理；帧协议见 stream-v1.schema.json。'}
        op['responses']['426']={'description':'普通 GET 未带 Upgrade 请求头，需升级 WebSocket；无响应体。'}
        op['x-stream-schema']='stream-v1.schema.json'
        op['description']='ticket 单次使用、60秒、绑定准确 Origin 和共享平台会话。连接每帧及重放周期复核登录、权限与业务资格；浏览器 WS 不使用 URL Bearer token。'
    elif kind=='ocr':op['responses']['200']={'description':'内部 OCR 返回原始识别对象，无 Ajax 包裹。','content':{'application/json':{'schema':ref('OcrResult')}}}
    else:op['responses']['200']={'description':'成功与业务错误均须检查 code；成功 data/rows 结构如下。','content':{'application/json':{'schema':{'oneOf':[success,ref('AjaxFailure')]}}}}
    if kind in ['chunk','multipartChunk']:
        if kind=='chunk':op['requestBody']={'required':True,'content':{'application/octet-stream':{'schema':st(format='binary')}}}
        else:op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'file':st(format='binary'),'sha256':SHA},('file','sha256'))}}}
        op['description']='number 为零基；同编号同校验和重传幂等，清单总大小及分片 SHA-256 必须匹配。raw 使用 X-Chunk-SHA256；multipart 使用 sha256 表单字段。'
    if kind in ['ocr','file']:op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'file':st(format='binary')},('file',))}}}
    if r['name'] in ['ocrHealthTest','reconcileOcr','reconcile']:op['description']='除声明权限外，必须为平台管理员；真实外部调用仍受服务端默认关闭的 external-calls-enabled 等配置控制。'
    if r['name']=='active':
        op['description']='无活动生成时 data=null。'
        if 'ActiveGenerationResult' not in S:S['ActiveGenerationResult']=obj({'code':num(enum=[200]),'msg':st(),'data':{'type':'object','nullable':True,'allOf':[ref('GenerationSnapshot')]}},('code','msg','data'))
        op['responses']['200']['content']['application/json']['schema']['oneOf'][0]=ref('ActiveGenerationResult')
    if r['controller']=='OperationController' and r['name']=='plan' and method=='get':op['summary']='读取不可变运维计划'
    if r['controller']=='DocumentController' and r['name'] in ['delete','restore','reprocess']:op['description']='返回当前 Document 元数据，处理作业在后台排队；不是 Job 或 JobSummary。版本指 rowVersion。'
    if r['controller']=='DocumentController' and r['name']=='delete':op['description']+=' rowVersion query 优先，否则 If-Match；至少一项必需，If-Match 接受数字或带引号的数字。'
    if r['controller']=='DocumentController' and r['name']=='page':op['description']='number 为一基 PDF 页码；原件预览上限100 MiB，返回独立单页PDF。contentVersion可选，版本不匹配拒绝。'
    if r['controller']=='RechargeController' and r['name']=='order':op['description']='仅订单所有者可查；查询可能调用微信查询原订单并核对账务。UNCERTAIN 不自动再次创建预支付。'
    if r['controller']=='RechargeController' and r['name']=='callback':op['description']='匿名仅指无平台 Bearer；必须通过官方支付签名。时间戳有效窗口300秒，消息预算64000字符，权益按不可变订单快照一次入账。'
    op['responses'].update({x:{'$ref':'#/components/responses/'+n} for x,n in [('400','BadRequest'),('401','Unauthorized'),('403','Forbidden'),('409','Conflict'),('429','RateLimited'),('503','Unavailable')]})
    if path in paths and method in paths[path]:
        old=paths[path][method];old['requestBody']['content'].update(op['requestBody']['content']); old['parameters']=[p for p in old['parameters'] if p['name']!='X-Chunk-SHA256']+[{'name':'X-Chunk-SHA256','in':'header','required':False,'schema':SHA,'description':'application/octet-stream 时必需；multipart 改用 sha256 表单字段。'}]
        old['x-source']=[old['x-source'],op['x-source']]
    else:paths.setdefault(path,{})[method]=op

responses={}
for name,text,code in [('BadRequest','输入无效',400),('Unauthorized','共享登录已失效或未登录',401),('Forbidden','权限、资格或资源范围拒绝',403),('Conflict','行版本、状态或业务重复动作冲突',409),('RateLimited','超出服务端窗口限制',429),('Unavailable','业务数据库暂不可用',503)]:
    responses[name]={'description':text+'。部分共享异常也可能用 HTTP 200 + Ajax code 返回；消费者必须检查 code。','content':{'application/json':{'schema':ref('AjaxResult')}}}
responses['RateLimited']['headers']={'Retry-After':{'schema':num(minimum=1),'description':'重试等待秒数；响应 errorCode=RATE_LIMITED，retryAfter 同值。'}}
doc={'openapi':'3.0.3','info':{'title':'PaiSmart 知识库真实 HTTP 契约','version':'1.0.0','description':'对应当前若依 PaiSmart 模块。共享 Bearer 登录、按钮权限和服务端空间 ACL；不建立第二套认证。源码范围含当前所有 /paismart Controller 映射及现有别名。默认外发与真实运维执行可关闭，路径存在不表示配置或业务联机通过。'},'servers':[{'url':'/','description':'若依后端根地址；开发环境由既有 /dev-api 代理转发。'}],'security':[{'BearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'BearerAuth':{'type':'http','scheme':'bearer','bearerFormat':'JWT','description':'共享若依登录令牌；WS 使用受此登录会话绑定的一次性票据。'}},'schemas':S,'responses':responses}}
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'knowledge-v1.openapi.yaml').write_text(yaml.safe_dump(doc,allow_unicode=True,sort_keys=False,width=110),encoding='utf-8')
(OUT/'.route-inventory.json').write_text(json.dumps(routes,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'OpenAPI: {len(paths)} paths, {sum(len(v) for v in paths.values())} operations, {len(S)} schemas')

# WS 为 JSON Schema 2020-12；快照与持久事件分离，禁止误用未实现的统一 envelope。
def json_schema(v):
    if isinstance(v,list):return [json_schema(x) for x in v]
    if not isinstance(v,dict):return v
    d={k:json_schema(x) for k,x in v.items() if k not in ['nullable']}
    if '$ref' in d:d['$ref']=d['$ref'].replace('#/components/schemas/','#/$defs/')
    if v.get('nullable'):
        if 'type' in d:d['type']=[d['type'],'null']
        else:d={'anyOf':[d,{'type':'null'}]}
    return d
defs={n:json_schema(S[n]) for n in ['Reference','GenerationSnapshot']}
def jr(n):return {'$ref':'#/$defs/'+n}
def jo(p,req=(),**kw):return {'type':'object','properties':p,'additionalProperties':True,'required':list(req),**kw}
def jsenum(*v):return {'type':'string','enum':list(v)}
JID={'type':'string','format':'uuid'}
def discriminator(*names):return {'anyOf':[{'required':['kind']},{'required':['type'],'not':{'required':['kind']}}]}
defs['ClientSubmit']=jo({'kind':jsenum('submit','chat','message'),'type':jsenum('submit','chat','message'),'text':{'type':'string','minLength':1,'maxLength':16000},'message':{'type':'string','minLength':1,'maxLength':16000},'clientRequestId':{'type':'string','minLength':1,'maxLength':100},'conversationId':{'type':['string','null'],'format':'uuid'},'spaceId':{'type':['string','null'],'format':'uuid'}},['clientRequestId'],allOf=[discriminator(),{'anyOf':[{'required':['text']},{'required':['message']}]}],description='kind 优先，type 为兼容别名；text 优先，未提供时才用 message。clientRequestId 对用户和原请求内容做幂等校验，平台session由服务端覆写。')
defs['ClientResume']=jo({'kind':jsenum('resume'),'type':jsenum('resume'),'generationId':JID,'afterSequence':{'type':'integer','minimum':0},'lastSequence':{'type':'integer','minimum':0},'sequence':{'type':'integer','minimum':0}},['generationId'],allOf=[discriminator()],description='offset优先afterSequence，其次lastSequence，再sequence，默认0；必须<=当前lastSequence。先返回快照，再重放>offset事件，终态不再订阅。')
defs['ClientStop']=jo({'kind':jsenum('stop'),'type':jsenum('stop'),'generationId':JID},['generationId'],allOf=[discriminator()],description='只设置停止旗标；停止完成后输出stopped持久事件。')
defs['ClientPing']=jo({'kind':jsenum('ping'),'type':jsenum('ping')},allOf=[discriminator()])
defs['LegacyPing']={'type':'string','const':'__chat_ping__','description':'此兼容帧为原始字符串文本，不经过JSON对象解析。'}
defs['Connected']=jo({'kind':{'const':'connected'},'protocolVersion':{'const':1}},['kind','protocolVersion'])
defs['Heartbeat']=jo({'kind':{'const':'heartbeat'}},['kind'])
eventbase={'kind':{'type':'string'},'sequence':{'type':'integer','minimum':1},'generationId':JID,'conversationId':JID,'messageId':JID}
eventreq=list(eventbase)
for n,kind,extra,req in [
 ('ToolEvent',['tool'],{'tool':{'type':'string'},'state':jsenum('RUNNING','COMPLETE')},['tool','state']),
 ('TextEvent',['delta','summary'],{'text':{'type':'string'}},['text']),
 ('ReferencesEvent',['references'],{'references':{'type':'array','items':jr('Reference')}},['references']),
 ('DoneEvent',['done'],{'state':jsenum('COMPLETE')},['state']),
 ('StoppedEvent',['stopped'],{'state':jsenum('STOPPED'),'errorMessage':{'type':'string'}},['state']),
 ('ErrorEvent',['error'],{'state':jsenum('FAILED','INTERRUPTED'),'errorMessage':{'type':'string'}},['state','errorMessage'])]:
    defs[n]=jo({**eventbase,'kind':jsenum(*kind),**extra},eventreq+req)
defs['ClientFrame']={'oneOf':[jr(n) for n in ['ClientSubmit','ClientResume','ClientStop','ClientPing','LegacyPing']]}
defs['PersistentEvent']={'oneOf':[jr(n) for n in ['ToolEvent','TextEvent','ReferencesEvent','DoneEvent','StoppedEvent','ErrorEvent']]}
defs['ServerFrame']={'oneOf':[jr('Connected'),jr('Heartbeat'),jr('GenerationSnapshot'),jr('PersistentEvent')]}
stream={'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'urn:paismart:stream:v1','title':'PaiSmart 聊天 WS 帧 v1','description':'/paismart/ws/chat?ticket=...；connected声明protocolVersion=1。连接票据60秒、单次、绑定Origin和共享登录session；每帧和250ms重放复核权限。断线不取消任务，不重发问题，恢复最多每批500事件；sequence仅持久事件存在，快照lastSequence用于对齐。kind是事件字段，不使用候选设计type-envelope。','oneOf':[jr('ClientFrame'),jr('ServerFrame')],'$defs':defs,'x-source':['ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/intelligence/PaismartChatWebSocket.java','ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/application/intelligence/ChatService.java','ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/application/intelligence/WebSocketTicketService.java']}
(OUT/'stream-v1.schema.json').write_text(json.dumps(stream,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

# Kafka 真正线上消息只有 jobId，不把 MySQL 中的业务 payload 声称为队列消息。
def ar(n):return {'$ref':'#/components/schemas/'+n}
AS={
 'JobNotification':{'type':'object','properties':{'jobId':{'type':'string','format':'uuid'}},'required':['jobId'],'additionalProperties':True,'description':'Kafka key也是jobId。处理器按此ID从MySQL读取类型、当前状态、payload及租约；消息不含文档正文/模型凭据。'},
 'JobDeadLetter':{'type':'object','properties':{'jobId':{'type':'string','format':'uuid'},'reason':{'type':'string'}},'required':['jobId','reason'],'additionalProperties':True},
 'MalformedNotificationDeadLetter':{'type':'object','properties':{'notificationId':{'type':'string','format':'uuid'},'sourceTopic':{'type':'string'},'partition':{'type':'integer','minimum':0},'offset':{'type':'integer','minimum':0},'payloadHash':{'type':'string','pattern':'^[a-f0-9]{64}$'},'reason':{'type':'string','enum':['通知格式无效']}},'required':['notificationId','sourceTopic','partition','offset','payloadHash','reason'],'additionalProperties':True,'description':'坏通知先持久化哈希及位置到outbox，再提交该分区offset；不存原消息内容。落库或commit失败才seek重试。'},
 'DocumentPayload':{'type':'object','properties':{'documentId':{'type':'string','format':'uuid'},'contentVersion':{'type':'integer','minimum':1}},'required':['documentId'],'additionalProperties':True,'description':'新DOCUMENT_PROCESS提交明确contentVersion=1，恢复/重处理为新内容版本；兼容旧任务未带contentVersion，因此schema保留可选。'},
 'DocumentProcessResult':{'type':'object','properties':{'documentId':{'type':'string','format':'uuid'},'chunks':{'type':'integer','minimum':0},'actualTokens':{'type':'integer','minimum':0},'state':{'type':'string','enum':['READY']},'skipped':{'type':'string','enum':['DELETED','SUPERSEDED']}},'required':['documentId'],'additionalProperties':True},
 'DocumentDeleteResult':{'type':'object','properties':{'documentId':{'type':'string','format':'uuid'},'deleted':{'type':'boolean'}},'required':['documentId','deleted'],'additionalProperties':True},
 'ChatPayload':{'type':'object','properties':{'generationId':{'type':'string','format':'uuid'}},'required':['generationId'],'additionalProperties':True},
 'ChatResult':{'type':'object','properties':{'generationId':{'type':'string','format':'uuid'},'state':{'type':'string','enum':['COMPLETE','STOPPED']}},'required':['generationId','state'],'additionalProperties':True},
 'IndexPayload':{'type':'object','properties':{'indexId':{'type':'string','format':'uuid'},'fresh':{'type':'boolean'}},'required':['indexId'],'additionalProperties':True},
 'IndexResult':{'type':'object','properties':{'indexId':{'type':'string','format':'uuid'},'state':{'type':'string','enum':['ACTIVE']}},'required':['indexId','state'],'additionalProperties':True},
 'IndexRecoverPayload':{'type':'object','properties':{'operation':{'type':'string','enum':['recover-alias']}},'required':['operation'],'additionalProperties':True},
 'IndexRecoverResult':{'type':'object','properties':{'state':{'type':'string','enum':['NO_RECOVERY_REQUIRED','RECOVERED']},'transitions':{'type':'integer','minimum':0}},'required':['state'],'additionalProperties':True},
 'EvaluationPayload':{'type':'object','properties':{'evaluationId':{'type':'string','format':'uuid'}},'required':['evaluationId'],'additionalProperties':True},
 'EvaluationResult':{'type':'object','properties':{'evaluationId':{'type':'string','format':'uuid'},'state':{'type':'string','enum':['COMPLETE','COMPLETED_WITH_ERRORS']}},'required':['evaluationId','state'],'additionalProperties':True},
 'ConflictResult':{'type':'object','properties':{'detected':{'type':'integer','minimum':0}},'required':['detected'],'additionalProperties':True},
 'OperationPayload':{'type':'object','properties':{'planId':{'type':'string','format':'uuid'},'kind':{'type':'string','enum':['cleanup-plans','object-migrations','bootstrap-import']},'reason':{'type':'string'},'documents':{'type':'array','items':{'type':'object'}},'files':{'type':'array','items':{'type':'object'}}},'required':['planId','kind','reason'],'additionalProperties':True,'description':'存于MySQL ps_job.payload_json；执行器重新读取已批准不可变plan清单和manifestHash，不以任务消息提升权限。文件项字段见HTTP OperationManifest。'},
 'OperationResult':{'type':'object','properties':{'planId':{'type':'string','format':'uuid'},'state':{'type':'string','enum':['SUCCEEDED']},'completedItems':{'type':'integer','minimum':0},'items':{'type':'array','items':{'type':'object','properties':{'itemKey':{'type':'string'},'state':{'type':'string'},'result':{'type':'string','description':'单项结果的JSON字符串。'}},'required':['itemKey','state','result'],'additionalProperties':True}}},'required':['planId','state','completedItems','items'],'additionalProperties':True}
}
# 普通任务快照不是Kafka消息；作为HTTP/内部约束提供独立schema。
AS['JobRecord']=json_schema(S['Job'])
def fixrefs(v):
    if isinstance(v,dict):
        if '$ref' in v:v['$ref']=v['$ref'].replace('#/$defs/','#/components/schemas/')
        for x in v.values():fixrefs(x)
    elif isinstance(v,list):
        for x in v:fixrefs(x)
fixrefs(AS['JobRecord'])
AS['JobRecord']['description']='MySQL任务记录，非Kafka消息；状态QUEUED/RUNNING/RETRY/SUCCEEDED/FAILED/CANCELED，与聊天generation终态独立。'
types=[]
for names,payload,result,src in [
 (['DOCUMENT_PROCESS','DOCUMENT_PARSE','DOCUMENT_REINDEX','REINDEX'],'DocumentPayload','DocumentProcessResult','DocumentIntelligenceService'),
 (['DOCUMENT_DELETE','DELETE'],'DocumentPayload','DocumentDeleteResult','DocumentIntelligenceService'),
 (['CHAT_GENERATE'],'ChatPayload','ChatResult','ChatService'),
 (['INDEX_REBUILD','INDEX_ROLLBACK'],'IndexPayload','IndexResult','GovernanceService'),
 (['INDEX_RECOVER'],'IndexRecoverPayload','IndexRecoverResult','GovernanceService'),
 (['EVALUATION_RUN'],'EvaluationPayload','EvaluationResult','GovernanceService'),
 (['CONFLICT_DETECT'],'DocumentPayload','ConflictResult','GovernanceService'),
 (['OPERATION_EXECUTE'],'OperationPayload','OperationResult','OperationExecutionService')]:
    types.append({'types':names,'payload':ar(payload),'result':ar(result),'handler':src})
asyncdoc={'asyncapi':'2.6.0','info':{'title':'PaiSmart 中央作业通知 v1','version':'1.0.0','description':'只描述已实现Kafka通知/死信及MySQL任务载荷。MySQL ps_job/outbox为权威真相，Kafka是调度提示；默认Kafka关闭，不声明已运行真实消息集群。'},'defaultContentType':'application/json','channels':{'paismart.jobs':{'description':'outbox发送jobId提示；key=jobId，消费者只提交当前通知分区offset。数据库claim失败seek，不吞通知。','publish':{'operationId':'dispatchJobNotification','message':{'$ref':'#/components/messages/JobNotification'}},'subscribe':{'operationId':'consumeJobNotification','message':{'$ref':'#/components/messages/JobNotification'}}},'paismart.jobs.dlq':{'description':'终止失败任务或确定坏通知的脱敏死信。坏通知按topic/partition/offset生成稳定notificationId，先持久化后提交。','publish':{'operationId':'dispatchDeadLetter','message':{'oneOf':[{'$ref':'#/components/messages/JobDeadLetter'},{'$ref':'#/components/messages/MalformedNotificationDeadLetter'}]}}}},'components':{'messages':{n:{'name':n,'contentType':'application/json','payload':ar(n)} for n in ['JobNotification','JobDeadLetter','MalformedNotificationDeadLetter']},'schemas':AS},'x-mysql-job-types':types,'x-runtime':{'authoritativeTables':['ps_job','ps_outbox','ps_job_checkpoint'],'featureFlag':'paismart.jobs.kafka-enabled (default false)','notificationBudgetCharacters':65536,'jobPayloadBudgetCharacters':1000000,'producer':{'acks':'all','enable.idempotence':True},'consumer':{'enable.auto.commit':False,'max.poll.records':1},'lease':{'defaultSeconds':900,'heartbeatSeconds':60,'minimumSeconds':30,'maximumSeconds':3600},'retryDescription':'作业失败按中央runner规则递增retries并退避；显式重试须通过ACL和状态CAS。已成功发布通知attempts归零，QUEUED任务通知恢复不会累计为失败。终态和核账未确定的真实模型调用不得盲目再次外发。'},'x-source':['ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/application/jobs/JobService.java','ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/PaismartKafkaRuntime.java']}
(OUT/'jobs-v1.asyncapi.yaml').write_text(yaml.safe_dump(asyncdoc,allow_unicode=True,sort_keys=False,width=110),encoding='utf-8')
print(f'AsyncAPI: {len(asyncdoc["channels"])} real channels; stream: {len(defs)} definitions')
