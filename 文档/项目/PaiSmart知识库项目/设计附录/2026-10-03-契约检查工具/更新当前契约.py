"""保留首轮生成器，按完整性补齐后的真实DTO更新正式契约；不调用业务服务。"""
from datetime import datetime
import hashlib
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[5]
OUT = ROOT / "contracts/paismart"
ORIGINAL = Path(__file__).parent / ".contract-build.py"
BACKUP = Path(__file__).parent / ("正式契约更新前-" + datetime.now().strftime("%H%M%S"))
BACKUP.mkdir(exist_ok=False)
for path in OUT.iterdir():
    if path.is_file():
        shutil.copy2(path, BACKUP / path.name)
        print("保存原文 " + path.name + " sha256=" + hashlib.sha256(path.read_bytes()).hexdigest())

source = ORIGINAL.read_text(encoding="utf-8")
source = source.replace("ROOT=Path(__file__).resolve().parents[2]", "ROOT=Path(" + repr(ROOT.as_posix()) + ")", 1)
source = source.replace("OUT=Path(__file__).parent", "OUT=Path(" + repr(OUT.as_posix()) + ")", 1)
source = source.replace("ps=bo() if jt=='boolean'", "ps=bo() if jt in ['boolean','Boolean']")
source = source.replace("([\\w.<>]+)\\s+(\\w+)", "([\\w.<>,]+)\\s+(\\w+)")
source = source.replace("jt,vn=vm.groups();strings=", """jt,vn=vm.groups()
        if jt.startswith('Map<'):
            if vn!='filters':raise ValueError('未核对的查询Map: '+vn)
            pars.extend({'name':name,'in':'query','required':False,'schema':st('startTime/endTime优先，兼容start_date/end_date；后台核对格式及日期范围。')} for name in ['startTime','endTime','start_date','end_date'])
            continue
        strings=""")
source = source.replace("(OUT/'.route-inventory.json')", "(Path(" + repr(BACKUP.as_posix()) + ")/'当前路由清单.json')")
updates = r'''
mapping('UploadController','list','发现本人未完成上传','Upload',None,'table')
S['Upload']['properties'].update({'spaceId':NID,'visibility':VIS,'createdAt':dt()})
S['Space']['properties']['description']=st(nullable=True,maxLength=5000)
S['SpaceInput']['properties']['description']=st(nullable=True,maxLength=5000)
S['Member']['properties'].update({'platformStatus':en('0','1',nullable=True),'usage':ref('Usage')})
S['Member']['properties']['createdAt']=dt(nullable=True)
S['InviteInput']['description']='创建code可省略生成，code统一trim及大写；未消费前可修改code，有消费后不可改。组织绑定不可改变；expiresAt缺省保留，显式null表示永久；新增或改变到过去时点拒绝。'
S['RechargePackage']['properties']['benefit']=st(nullable=True,maxLength=4000)
S['PackageInput']['properties']['benefit']=st(nullable=True,maxLength=4000)
S['Order']['properties']['state']=en('CREATING','PREPAY_STARTED','UNCERTAIN','NOT_PAY','PAYING','SUCCEEDED','FAILED','CLOSED','REVOKED','PAYERROR')
schema('UsageScope',{'scope':en('LLM','EMBEDDING'),'mode':en('DAILY','BALANCE'),'enabled':bo(),'limitTokens':num(nullable=True),'usedTokens':TOK,'reservedTokens':TOK,'remainingTokens':TOK,'requestCount':TOK,'totalRequestCount':TOK,'available':TOK,'frozen':TOK,'consumed':TOK,'debt':TOK},description='DAILY依据今日used/reserved与limit计算剩额；BALANCE的limitTokens为null、remainingTokens为available；不把余额0当每日额度0。')
S['Usage']['properties'].update({'businessDay':st(format='date'),'llm':ref('UsageScope'),'embedding':ref('UsageScope'),'chatRequestCount':TOK,'totalChatRequestCount':TOK})
S['Usage']['required']=['userId','accounts','daily','policy','businessDay','llm','embedding','chatRequestCount','totalChatRequestCount']
S['Usage']['description']='本人或指定成员的真实账户、额度策略、今日已用/预留/剩余与每日/历史请求统计；只读聚合不授予业务资格。'
schema('UsageTrend',{'day':st(format='date'),'chatRequestCount':TOK,'llmUsedTokens':TOK,'llmRequestCount':TOK,'embeddingUsedTokens':TOK,'embeddingRequestCount':TOK},('day','chatRequestCount','llmUsedTokens','llmRequestCount','embeddingUsedTokens','embeddingRequestCount'))
rank={'userId':LONG,'username':st(),'scope':en('llm','embedding'),'usedTokens':TOK,'requestCount':TOK,'mode':en('DAILY','BALANCE'),'limitTokens':num(nullable=True),'remainingTokens':TOK,'reservedTokens':TOK}
schema('UsageRanking',rank)
schema('UsageAlert',{**rank,'level':en('warning','critical'),'debt':TOK,'usageRatio':{'type':'number','nullable':True},'message':st()})
schema('UsageOverview',{'days':num(enum=[7,30]),'today':ref('UsageTrend'),'trends':arr(ref('UsageTrend')),'accounts':arr(ref('Account')),'daily':arr(ref('DailyUsage')),'llmRankings':arr(ref('UsageRanking')),'embeddingRankings':arr(ref('UsageRanking')),'alerts':arr(ref('UsageAlert')),'lowBalanceThreshold':num(enum=[9300])},description='无userId管理汇总：Asia/Shanghai业务日，7/30日补零且不含未来；排行为今日前5；每日预警含冻结量，余额预警独立。指定userId时返回Usage。')
S['UsageOverview']['required']=['days','today','trends','llmRankings','embeddingRankings','alerts','lowBalanceThreshold']
mapping('UsageController','overview','读取成员用量或管理趋势排行','UsageOverview',None,'ajax')
S['Evidence']['properties'].update({'parentContext':st(),'parentKey':st()})
S['Reference']['properties'].update({'parentContext':st(),'parentKey':st(),'evidenceSnippet':st(),'query':st(),'retrievalMode':st(),'scoreType':st(),'score':{'type':'number'},'chunkId':ID,'matchedChunkText':st(),'context':st()})
S['Reference']['description']='text为命中原文，parentContext为授权扩展事实；核验缺少裁决或调用失败保持未核验，不能写成supported=false。每次预览复核当前授权和内容版本。'
metric={'type':'number','nullable':True}
S['EvaluationCase']['properties'].update({'caseId':st(),'referenceAnswer':st(nullable=True),'expectedDocumentIds':arr(st()),'expectedFileNames':arr(st()),'retrievalMode':st(),'retrieved':arr(obj({'rank':num(),'documentId':ID,'fileName':st(),'chunkId':ID,'score':{'type':'number'},'relevant':bo(nullable=True)})),'retrievalHit':bo(nullable=True),'firstRelevantRank':num(nullable=True),'reciprocalRank':metric,'expectedDocCoverage':metric,'judgeScore':num(minimum=1,maximum=5,nullable=True),'judgeCorrect':bo(nullable=True),'judgeReason':st(nullable=True),'judgeRawOutput':st(nullable=True),'diagnosis':st(),'needsHumanReview':bo(),'reviewReason':st(nullable=True),'error':st(nullable=True),'latencyMs':TOK,'humanVerdict':obj()})
summary={k:TOK for k in ['samples','totalCases','labeledCases','retrievalHits','judgedCases','correctCases','needsHumanReviewCount','failedCases']}
summary.update({k:metric for k in ['recall','mrr','ndcg','recallAtK','meanExpectedDocCoverage','meanJudgeScore','accuracy']})
summary.update({'retrievalModeCounts':obj(),'diagnosisCounts':obj(),'humanReviewNeeded':TOK})
schema('EvaluationSummary',summary)
schema('JudgeHumanAgreement',{**{k:TOK for k in ['reviewedCases','agreedCases','scoredCases','humanOnlyCases','comparableCases','minComparableCases']},**{k:metric for k in ['agreementRate','cohensKappa','meanAbsoluteScoreDiff','judgeCorrectRate','humanCorrectRate','minAgreementRate','minKappa']},'disagreementCaseIds':arr(st()),'status':en('NO_REVIEWS','INSUFFICIENT_SAMPLE','NEEDS_RECALIBRATION','OK'),'warning':st(nullable=True)})
S['EvaluationReport']['properties'].update({'summary':ref('EvaluationSummary'),'judgeHumanAgreement':ref('JudgeHumanAgreement'),'diagnosisGuide':obj(),'config':obj(),'datasetSource':st(),'topK':num(),'startedAt':dt(),'finishedAt':dt()})
schema('EvaluationConfiguration',{'requestHash':SHA,'generationTimeoutSeconds':num(minimum=1,maximum=90,default=90),'judgeTimeoutSeconds':num(minimum=1,maximum=90,default=60)},description='任务创建时冻结的配置；requestHash只摘要标准化用户输入，重试恢复原任务，不因当前配置变化再次创建。超时为完整单次HTTP请求预算。其余冻结配置按后台结果保留。')
S['EvaluationReport']['properties'].update({'configuration':ref('EvaluationConfiguration'),'config':ref('EvaluationConfiguration')})
S['EvaluationReport']['description']='逐例保留检索/生成/裁判失败与独立诊断，报告冻结配置；totalCases保留全部样本、failedCases单列，指标明确使用labeledCases或judgedCases分母；人工结论最新修订参与一致性，未知指标为null而非0。'
S['Evaluation']['properties'].update({'topK':num(minimum=1,maximum=20),'jobId':NID,'jobState':st(nullable=True),'jobResult':obj(nullable=True)})
S['IndexRevision']['properties'].update({'sourceWatermark':dt(nullable=True),'validationJson':st(nullable=True,description='序列化校验JSON；PENDING只有冻结queries，不代表已完成count或KNN。完成时保留expectedChunks/actualChunks、样本queries/scores和实际状态。')})
S['Review']['properties'].update({'conclusion':en('SUPPORTED','UNSUPPORTED','NEEDS_EVIDENCE'),'reviewer':LONG})
schema('ProviderTestInput',{'id':ID},description='可选正文；指定同scope条目，省略id仍测激活配置。仅允许id，不接受任意地址、模型或凭据。')
mapping('IntelligenceController','test','测试指定或激活供应商','ProviderTest','ProviderTestInput','ajax')
for key in ['recall','mrr','ndcg','coverage','answer']:
    S['EvaluationCase']['properties'][key]['nullable']=True
S['OperationManifest']['properties']['documents']['items']['properties'].update({'legacySourceKey':st(),'contentVersion':num(),'rowVersion':REV})
S['OperationManifest']['properties']['documents']['items']['properties'].update({'uploadId':ID,'chunkNo':num(minimum=0)})
S['OperationManifest']['properties']['files']['items']['properties'].update({'logicalKey':st(),'repair':bo()})
schema('CleanupUpload',{'uploadId':ID,'state':st(),'expiresAt':dt(),'sha256':SHA,'byteSize':TOK,'userId':LONG,'spaceId':NID,'visibility':VIS,'chunks':arr(obj({'chunkNo':num(minimum=0),'objectKey':st(),'sha256':SHA,'byteSize':TOK}))})
S['OperationManifest']['properties']['uploads']=arr(ref('CleanupUpload'))
S['OperationManifest']['description']='cleanup由后台冻结30日软删文档与过期/已合并临时片，只删除明确自有对象，documents+uploads合计最多1000；迁移精确核对源与目标hash/尺寸，legacySourceKey只接受批准原桶merged或chunks且不删源；bootstrap按持久逻辑绑定去重/替换/修复，不伪造处理成功。'
dataset_id=st(pattern=r'^(?:builtin:[a-z0-9][a-z0-9_-]{0,63}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$',description='只接受服务器列出的受控资源或本人上传样本ID；不接受文件路径、URL或任意对象key。')
schema('EvaluationDataset',{'id':dataset_id,'label':st(minLength=1,maxLength=160),'source':en('UPLOAD','RESOURCE'),'ownerId':num(format='int64',nullable=True),'sha256':SHA,'byteSize':num(minimum=1,maximum=900000),'caseCount':num(minimum=1,maximum=500),'createdAt':dt()},('id','label','source','ownerId','sha256','byteSize','caseCount'),description='不可变样本元数据；上传样本仅owner及明确管理权限可读，受控资源须服务端登记ID/摘要/范围。响应不返回对象key或服务器路径，不进行模型调用。')
mapping('IntelligenceController','evaluationDatasets','列出本人上传和可访问受控评测样本','EvaluationDataset',None,'table')
mapping('IntelligenceController','uploadEvaluationDataset','上传本人不可变JSONL评测样本','EvaluationDataset',None,'file')
S['EvaluationInput']['properties']['datasetId']=dataset_id
S['EvaluationInput']['properties']['seed']=num(minimum=-9007199254740991,maximum=9007199254740991,default=42)
S['EvaluationInput']['required']=['label']
S['EvaluationInput']['additionalProperties']=False
S['EvaluationInput']['oneOf']=[{'required':['inlineJsonl']},{'required':['datasetId']}]
S['EvaluationInput']['description']='inlineJsonl与datasetId只提交一种非空来源；JSONL严格UTF-8、最多900000字节/500样本，TopK1—20，seed必须为安全整数。datasetId取服务端列表，上传文件先提交独立multipart样本入口。新任务冻结样本/摘要/来源、seed与模型配置；同key同输入恢复原任务，不重读已变更资源、不重新收费；未知字段、任意主机路径/URL拒绝。'
S['EvaluationConfiguration']['properties'].update({'topK':num(minimum=1,maximum=20),'seed':num(minimum=-9007199254740991,maximum=9007199254740991),'datasetSource':en('inline','upload','resource'),'datasetId':dataset_id,'datasetLabel':st(maxLength=160),'datasetHash':SHA,'datasetByteSize':num(minimum=1,maximum=900000),'datasetCaseCount':num(minimum=1,maximum=500)})
S['EvaluationReport']['properties']['datasetSource']=en('inline','upload','resource')
S['EvaluationSummary']['properties'].update({'processedCases':TOK,'pendingCases':TOK})
S['EvaluationSummary']['description']='完整报告沿原总数与分母；未完成报告totalCases为冻结总样本，processedCases为正式已存逐例结果，pendingCases为未处理数。指标只用已存可计算案例，待处理不填0或虚构失败。'
'''
source = source.replace("paths={}\n", updates + "\npaths={}\n", 1)
source = source.replace("responses={}\n", """S['UsageOverviewResult']['properties']['data']={'oneOf':[ref('UsageOverview'),ref('Usage')]}
dataset_upload=paths['/paismart/evaluation-datasets']['post']
dataset_upload['parameters']=[p for p in dataset_upload.get('parameters',[]) if not(p['in']=='query' and p['name']=='label')]
for p in dataset_upload['parameters']:
    if p['name']=='Idempotency-Key':p['schema']=st(minLength=1,maxLength=128)
dataset_upload['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'file':st(format='binary',description='不含路径的.jsonl文件，严格UTF-8且实际字节数须与声明一致，最多900000字节/500样本。'),'label':st(minLength=1,maxLength=160,description='可选；省略时使用文件名，最终标签仍须不超过160字符。')},('file',))}}}
dataset_upload['description']='复用共享权限/业务资格及既有对象写入开关；按用户与请求键保存上传回执，同key不同标签/内容拒绝，相同输入不重复写对象。仅准备不可变样本，不解析知识文档、不排评测作业或调用模型；业务库事务失败会有限回收本次对象，回收失败保留异常。'
paths['/paismart/evaluation-datasets']['get']['description']='真实分页：pageNum从1开始、pageSize1—100；先过滤SHARED或当前owner/管理范围的登记资源（最多100），再按created_at DESC/id分页本人可访问上传记录。total为授权资源数加同范围SQL真实COUNT，不以500截断冒充全部；不返回对象key/服务器资源路径。'
responses={}
""", 1)
source = source.replace("asyncdoc={'asyncapi'", "AS['OperationPayload']['properties']['uploads']={'type':'array','items':{'type':'object'}}\nfor kind in ['IndexPayload','EvaluationPayload']:AS[kind]['properties']['requestHash']={'type':'string','pattern':'^[0-9a-f]{64}$','description':'可选的原用户输入摘要；同key恢复原任务，输入不同拒绝。回滚等非创建载荷可省略。'}\nasyncdoc={'asyncapi'", 1)
exec(compile(source, str(ORIGINAL), "exec"), {"__file__": str(ORIGINAL)})
print("当前契约已生成，需随后只读静态核对及完整OpenAPI校验。")
