"""初始化3GET只读来源细化；仅复用字面量解析函数，不运行catalog生成main或SQL。"""
from pathlib import Path
from datetime import datetime,timezone
import ast,hashlib,json,re
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
FINAL=OUT/'工作流初始化三GET最小范围来源细化.json';assert not FINAL.exists(),'冻结证据不能覆写'
T='参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/'
files=[T+'controller/workflow/WorkflowController.java',T+'service/workflow/WorkflowService.java',T+'common/constant/CommonConst.java',T+'entity/biz/workflow/AgentStrategy.java',T+'entity/table/ConfigInfo.java',T+'mapper/ConfigInfoMapper.java',
 '参考项目/PaiFlow-main/console/backend/toolkit/src/main/resources/mybatis/mapper/mysql/ConfigInfoMapper.xml','参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql','contracts/paiflow/generate-public-config-catalog.py',
 'frontend/paiflow-web/src/services/flow.ts','frontend/paiflow-web/src/components/workflow/store/flow-manager-function.ts','frontend/paiflow-web/src/components/workflow/hooks/use-flow-common.ts',
 'frontend/paiflow-web/src/components/workflow/nodes/agent/index.tsx','frontend/paiflow-web/src/components/workflow/nodes/knowledge-pro/index.tsx',
 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/ConfigInfoService.java','ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/mapper/PublicConfigMapper.java',
 'ruoyi-backend/ruoyi-paiflow/src/main/resources/mapper/paiflow/PublicConfigMapper.xml','ruoyi-backend/ruoyi-paiflow/src/main/resources/paiflow/public-config-source.json','ruoyi-backend/ruoyi-paiflow/sql/014-paiflow-public-config.sql']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
before={p:sha(ROOT/p) for p in files};started=datetime.now(timezone.utc).isoformat()
generator=ROOT/'contracts/paiflow/generate-public-config-catalog.py';tree=ast.parse(generator.read_text(encoding='utf-8'))
function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='parse_rows')
namespace={'re':re};exec(compile(ast.Module(body=[function],type_ignores=[]),'existing-readonly-parse_rows','exec'),namespace)
sql=(ROOT/files[7]).read_text(encoding='utf-8');statement=re.search(r'INSERT INTO `config_info` VALUES .*?;\r?\n',sql,re.S).group(0)
columns=['id','category','code','name','value','isValid','remarks','createTime','updateTime']
selected=[dict(zip(columns,row)) for row in namespace['parse_rows'](statement) if row[1] in {'WORKFLOW_NODE_TEMPLATE','WORKFLOW_NODE_TEMPLATE_PRE','WORKFLOW_AGENT_STRATEGY','WORKFLOW_KNOWLEDGE_PRO_STRATEGY'}]
assert len(selected)==40
private={'password','passwd','secret','apikey','apisecret','appkey','appsecret','token','accesstoken','refreshtoken','authorization','privatekey','clientsecret','credential','credentialref','secretref'}
def key_paths(value,path='$'):
 result=[]
 if isinstance(value,dict):
  for key,child in value.items():
   here=path+'.'+key;normal=key.lower().replace('_','').replace('-','')
   if normal in private or key in {'uid','appId','projectId','repoId','repoList','modelId','llmId','serviceId','patchId','url'}:
    result.append({'path':here,'credentialNamed':normal in private,'valueType':type(child).__name__,'null':child is None,'emptyString':child=='','nonEmptyString':isinstance(child,str) and child!='','valueDisclosed':False})
   result+=key_paths(child,here)
 elif isinstance(value,list):
  for i,child in enumerate(value):result+=key_paths(child,f'{path}[{i}]')
 return result
categories=[]
for cat in ['WORKFLOW_NODE_TEMPLATE','WORKFLOW_NODE_TEMPLATE_PRE','WORKFLOW_AGENT_STRATEGY','WORKFLOW_KNOWLEDGE_PRO_STRATEGY']:
 rows=[r for r in selected if r['category']==cat];is_node='NODE_TEMPLATE' in cat;safe=[]
 for row in rows:
  item={'id':row['id'],'category':cat,'code':row['code'],'groupOrName':row['name'],'isValid':row['isValid'],'valueUTF8Bytes':len(row['value'].encode()),'valueSha256':hashlib.sha256(row['value'].encode()).hexdigest()}
  if is_node:
   node=json.loads(row['value']);item.update({'idType':node.get('idType'),'topLevelFields':list(node),'dataFields':list(node.get('data',{})),'nodeParamFields':list(node.get('data',{}).get('nodeParam',{})),'credentialAndReferencePaths':key_paths(node),'nodeObject':isinstance(node,dict),'dataObject':isinstance(node.get('data'),dict),'nodeParamObject':isinstance(node.get('data',{}).get('nodeParam'),dict)})
  else:item.update({'returnFieldNames':['name','description','code'],'returnCodeFromRemarks':int(row['remarks']),'descriptionIsOriginalValueString':True})
  safe.append(item)
 categories.append({'category':cat,'rows':len(rows),'activeRows':sum(r['isValid']==1 for r in rows),'allSourceCodes':sorted({r['code'] for r in rows}),'sourceRowsInDumpOrderForAuditOnly':safe})
assert [c['rows'] for c in categories]==[19,18,1,2]
keys=[(r['id'],p) for c in categories for r in c['sourceRowsInDumpOrderForAuditOnly'] for p in r.get('credentialAndReferencePaths',[]) if p['credentialNamed']]
assert len(keys)==1 and keys[0][0]==1823 and keys[0][1]['path']=='$.data.nodeParam.header.apiKey' and keys[0][1]['emptyString']
catalog=json.loads((ROOT/files[17]).read_text(encoding='utf-8'));assert all(c['category'] not in catalog['publicWhitelist'] for c in categories)
controller=(ROOT/files[0]).read_text(encoding='utf-8');service=(ROOT/files[1]).read_text(encoding='utf-8')
def lines(path,token):return [i for i,s in enumerate((ROOT/path).read_text(encoding='utf-8').splitlines(),1) if token in s]
facts=[
 {'route':'GET /workflow/node-template','controllerLines':lines(files[0],'@GetMapping("/node-template")'),'serviceLines':lines(files[1],'public Object getNodeTemplate('),'query':'source optional Integer，原Service完全忽略；固定COMMON=1，用code LIKE %1%；节点is_valid=1；完整profile字符串只有pre切WORKFLOW_NODE_TEMPLATE_PRE；其他profile用WORKFLOW_NODE_TEMPLATE。','return':'JSONArray<{name:config.name,nodes:[JSON.parseObject(config.value),...]}>；不返回配置9字段/分页。','ordering':'SQL无ORDER BY；LinkedHashMap以Mapper encounter次序分组、组内List保encounter序，不能把dump顺序冒作正式排序合同。'},
 {'route':'GET /workflow/get-agent-strategy','controllerLines':lines(files[0],'@GetMapping("/get-agent-strategy")'),'serviceLines':lines(files[1],'public Object getAgentStrategy('),'query':'category=WORKFLOW_AGENT_STRATEGY AND code=agentStrategy，原不筛is_valid、不分页、不排序。','return':'List<AgentStrategy>{name:String=config.name,description:String=config.value,code:Integer=Integer.valueOf(remarks)}；remarks非整数/null原异常，非空success占位。'},
 {'route':'GET /workflow/get-knowledge-pro-strategy','controllerLines':lines(files[0],'@GetMapping("/get-knowledge-pro-strategy")'),'serviceLines':lines(files[1],'public Object getKnowledgeProStrategy('),'query':'category=WORKFLOW_KNOWLEDGE_PRO_STRATEGY AND code=knowledgeProStrategy，原不筛is_valid、不分页、不排序。','return':'与AgentStrategy相同三字段，code必须Integer；原UI严格===匹配ragType，不可返回字符串code。'},
 {'consumer':files[10],'lines':lines(files[10],'export const initFlowData')+lines(files[10],'await Promise.all'),'effect':'detail、node-template、text/list、两strategy在同Promise.all；三GET缺失会使赋currentFlow/nodeList前reject；此为源码事实，没有实际挂载页面。'},
 {'consumer':files[11],'lines':lines(files[11],'nodeParam.uid'),'effect':'原add/insert/copy节点会把登录user.uid写nodeParam.uid；catalog的真实UID不可成为归属/费用/资源授权。初始化模板应无个人运行发起者，不误删schema/业务HTTP UID。'},
 {'consumer':files[12],'lines':lines(files[12],'item?.code ===')+lines(files[12],'value={item?.code}'),'effect':'Agent node策略code严格对modelConfig.agentStrategy，name显示、description tooltip；仅策略描述不授Model/Agent执行。'},
 {'consumer':files[13],'lines':lines(files[13],'data?.nodeParam?.ragType ===')+lines(files[13],'ragType = item?.code'),'effect':'KnowledgePro策略code严格匹配ragType且点击赋相同code；显示模板不授知识库访问。'}]
after={p:sha(ROOT/p) for p in files};assert before==after
report={'schemaVersion':'paiflow.workflow-init-three-get-scope-review.v1','startedAt':started,'endedAt':datetime.now(timezone.utc).isoformat(),'scope':'下一初始化显示目录3GET只读源码/字面种子分析，不是已迁、产品决定或第二长期状态','sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],
 'literalParserOnly':{'actualFunction':'现generate-public-config-catalog.py parse_rows单函数AST，未调用main','mysqlEscapeAssumption':'MySQL标准反斜杠语义，与原dump一致；真实旧库sql_mode/新增行未核','sqlExecuted':False,'databaseConnected':False,'rowsSelected':40},'categories':categories,'facts':facts,
 'currentGap':{'threeTargetRoutesDeclared':False,'current014WhitelistHasAnyFourCategories':False,'mapperOriginalStrategySemanticDifference':'现PublicConfigMapper.byCategory/byCategoryAndCode总is_valid=1，ConfigInfoService.view也只允许1；不能把原Strategy动态inactive/null行静默删掉。','publicSafetyDifference':'ConfigInfoService.privateFields对凭据key无条件拒，空apiKey也拒；不能把四类别加入白名单就说可公开完整目录。'},
 'recommendedMinimum':['同paiflow_public_config唯一实时表扩四个精确category/code授权与未执行增量；来源catalog只记录审查/种子SHA，不当动态读结果；不新建第二目录真相。','3薄legacy GET+只读目录Service，复用current活动平台身份与最后同主体核对，不依赖真实App/Provider，也不初始化defaultspace。','Node读固定COMMON substring+is_valid1+精确pre profile；strategy用专用受限pair读真实所有is_valid状态并作安全三字段投影，不放宽现generic Config5语义。','保持Mapper encounter次序及分组/组内顺序；无源码排序保证，不新增page或任意按名称/ID排序替代原合同。','审查40原候选且按profile/active实际保36节点? 正式主18、pre18分别选择，不混拼；1Agent+2Knowledge策略。缺配置/坏JSON/remarks/unsafe字段明确失败，不用只返回纯节点空占位假成功。'],
 'credentialAndUidProjectionRecommendation':['只在经审查的共享显示模板副本/种子上处理具体占位：RPA id1823 $.data.nodeParam.header.apiKey恰为空String可去掉该空key（保header{}及其他字段）；这是明确声明差异，不放宽Config5/Workflow/Run正式秘密规则。','后续动态非空凭据/secretRef/privateURL/credential-key即拒绝并只报路径，不静默删除真实值，不导入环境，不建立resource绑定；嵌套JSON字符串同审。','原40所见凭据named仅空RPA apiKey；其他UID/资源ID只报类型/路径不输出值。若模板nodeParam.uid具体非空String，应在显示副本置空或拒绝并显式记录，不能相信其owner；业务schema/default/HTTP UID按上下文保留。','template节点类型Agent/flow/knowledge/RPA仍是编辑描述；已有Compiler/Run/App/资源门保持，目录存在不把所有用户Workflow变public、不给资源/Provider执行ready。'],
 'verificationRecommendation':['实际Mapper动态update后3GET读取、Node inactive排除、Strategy inactive/null仍按原读、固定COMMON与source忽略、精确pre vs pre,test、encounter分组顺序；隔离H2不是MySQL部署。','实际原initFlowData AST用同形三返回读取icons/group/strictnumber策略，不用实际页面通过称技术VM；对应真实MockMVC路由/LegacyResult合同独立。','RPA空占位确定path变更与储存原SHA/审查记录、非空秘密及encoded/privateURL拒绝、nodeParam.uid不授权、DB当前unsafe行拒；整体预算/深度/rows有限且不静默截断。'],
 'businessVerified':False,'limits':['仅源码与现有SQL字面解析，未执行原Service/DTO策略算法、UI、Mapper/DB/Provider，未生成新catalog/DDL/源码/索引。','原SQLdump顺序仅审计，SQL无ORDER BY不冒称执行顺序；原动态配置真实数据仍未导入/连接。','现有175旧索引保历史不读取新Export路由作最终source统计；本JSON无长期实施状态。']}
FINAL.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'path':FINAL.relative_to(ROOT).as_posix(),'sha256':sha(FINAL),'sourceCount':len(files),'sourceWindowChanges':0,'rowsSelected':len(selected),'credentialKeyCount':len(keys)},ensure_ascii=False))
