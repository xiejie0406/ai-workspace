"""形成原本地输入类型唯一合同材料；仅编译原纯转换/DTO到证据私有目录。"""
from datetime import datetime, timezone
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
SOURCE=ROOT/'参考项目/PaiFlow-main/console/backend'
BUILD=CHECKS/'原输入类型纯转换独立编译'
COMMON=SOURCE/'commons/src/main/java/com/iflytek/astron/console/commons'
TOOLKIT=SOURCE/'toolkit/src/main/java/com/iflytek/astron/console/toolkit'
BIZ=TOOLKIT/'entity/biz/workflow/node'
DTO_FIELDS=['id','name','nameErrMsg','schema','allowedFileType','fileType','description','required','refId','deleteDisabled','disabled','customParameterType']

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def nonfile(name,kind,required,schema=None):
    return {name:kind,name+'_required':required,'name':name,'type':kind,'schema':schema or {'type':kind}}
def fileextra(row):
    kind=row['allowedFileType'][0].lower()
    return {row['name']:kind,'required':row.get('required'),'schema':deepcopy(row.get('schema')),'name':row['name'],'type':kind,'fullParam':deepcopy(row)}
def without_nulls(value):
    if isinstance(value,dict):return {k:without_nulls(v) for k,v in value.items() if v is not None}
    if isinstance(value,list):return [without_nulls(v) for v in value]
    return value
def dto_state(rows):return [{key:deepcopy(row.get(key)) for key in DTO_FIELDS} for row in rows]

def make_cases():
    cases=[]
    def add(cid,meaning,raw,typed,rawtypes,rawvalues,extras=None,selected=None,support=None,typedtypes=None,typedvalues=None,failure=None,notes=None):
        expected={'getInputsInfoData':typed,'rawJsonConverterTypeTemplate':rawtypes,'rawJsonConverterValueTemplate':rawvalues,
                  'typedJsonConverterTypeTemplate':rawtypes if typedtypes is None else typedtypes,
                  'typedJsonConverterValueTemplate':rawvalues if typedvalues is None else typedvalues,
                  'returnedDtoFieldState':dto_state(typed)}
        if failure:
            expected['maasLocalMetadataProjection']={'status':'source_derived_exception_before_mapper_calls','failure':failure,'metadataWriteReachable':False}
        else:
            expected['maasLocalMetadataProjection']={'status':'source_derived_success','extraInputsConfigMemory':extras,
                'extraInputsConfigStoredJsonValue':without_nulls(extras),'extraInputsMemory':selected,
                'extraInputsStoredJsonValue':without_nulls(selected),'supportUpload':support,
                'returnedDataIsUnfiltered':True,'metadataWriteReachable':True}
        cases.append({'id':cid,'meaning':meaning,'rawStartOutputs':raw,'independentExpected':expected,'notes':notes or []})
    add('empty','空列表',[],[],{},{},[],{},0)
    reserved=[{'id':'system-input','name':'AGENT_USER_INPUT','schema':{'type':'string'},'required':True}]
    add('agent-user-input','固定输入仅从metadata过滤，响应仍保留',reserved,reserved,{'AGENT_USER_INPUT':'string'},{'AGENT_USER_INPUT':''},[],{},0,
        notes=['MaasUtil filteredParams排除固定输入，但return res保留原data；Controller仍将完整data转换为DTO。'])
    kinds=['string','integer','boolean','number','object']
    primitives=[{'id':'primitive-'+k,'name':k,'schema':{'type':k,'default':'原默认忽略'},'required':i%2==0} for i,k in enumerate(kinds)]
    ptypes={k:({} if k=='object' else k) for k in kinds};pvalues={'string':'','integer':0,'boolean':True,'number':0,'object':{}}
    add('primitives','五种基本类型保留次序和required，但不进入旧extraInputs首项',primitives,primitives,ptypes,pvalues,
        [nonfile(k,k,i%2==0,{'type':k,'default':'原默认忽略'}) for i,k in enumerate(kinds)],{},0)
    pdf={'id':'file-pdf','name':'document','schema':{'type':'string'},'allowedFileType':['PDF','Image'],'required':True}
    add('file-first-allowed','文件首个allowedFileType优先：模板保大小写，metadata转小写', [pdf],[pdf],{'document':'PDF'},{'document':'PDF'},[fileextra(pdf)],fileextra(pdf),1,
        notes=['第二个Image不影响type/supportUpload；不是允许文件集合聚合。'])
    image={'id':'file-image','name':'picture','schema':{'type':'string'},'allowedFileType':['image'],'fileType':'file','description':'图片'}
    add('file-missing-required','文件required缺省在memory为null，默认落盘JSON省略null', [image],[image],{'picture':'image'},{'picture':'image'},[fileextra(image)],fileextra(image),2)
    arrpdf={'id':'array-file','name':'documents','schema':{'type':'array-string'},'allowedFileType':['pdf','image'],'required':False}
    add('array-file-only','数组文件完整metadata保留，但旧首项选择不选它', [arrpdf],[arrpdf],{'documents':'pdf'},{'documents':'pdf'},[fileextra(arrpdf)],{},0,
        notes=['getFileType(pdf,array-string)自身分支为21，但getInputsType先keepOldValue排除该row，所以持久supportUpload实际为0（源码推导）。'])
    scalar={'id':'second-image','name':'preview','schema':{'type':'string'},'allowedFileType':['image'],'required':True}
    add('array-file-before-scalar','数组文件在前不会挡住后续标量文件', [arrpdf,scalar],[arrpdf,scalar],{'documents':'pdf','preview':'image'},{'documents':'pdf','preview':'image'},[fileextra(arrpdf),fileextra(scalar)],fileextra(scalar),2)
    doc={'id':'first-doc','name':'word','schema':{'type':'string'},'allowedFileType':['doc'],'required':True}
    add('two-scalar-files','两个标量文件按原顺序只选第一枚', [doc,pdf],[doc,pdf],{'word':'doc','document':'PDF'},{'word':'doc','document':'PDF'},[fileextra(doc),fileextra(pdf)],fileextra(doc),3)
    nested={'id':'nested','name':'profile','schema':{'type':'object','description':'结构', 'properties':[
        {'name':'asset','type':'string','allowedFileType':['PDF'],'description':'原BizProperty不声明此字段','default':'ignored'},
        {'name':'age','type':'integer','required':True},
        {'name':'addresses','type':'array-object','properties':[{'name':'road','type':'string'}]}]},'required':False}
    nested_typed=deepcopy(nested)
    for key in ['allowedFileType','description']:nested_typed['schema']['properties'][0].pop(key)
    nt={'profile':{'asset':'PDF','age':'integer','addresses':[{'road':'string'}]}}
    nv={'profile':{'asset':'PDF','age':0,'addresses':[{'road':''}]}}
    ntt={'profile':{'asset':'string','age':'integer','addresses':[{'road':'string'}]}}
    ntv={'profile':{'asset':'','age':0,'addresses':[{'road':''}]}}
    add('nested-object','嵌套object/array与BizProperty字段裁剪', [nested],[nested_typed],nt,nv,[nonfile('profile','object',False,nested_typed['schema'])],{},0,ntt,ntv,
        notes=['直接JsonConverter支持properties.allowedFileType；实际getInputsInfo先BizProperty typed投影，未声明allowedFileType/description会丢失。'])
    arrays=[{'id':'array-'+k,'name':k+'List','schema':{'type':'array-'+k,**({'properties':[{'name':'label','type':'string'}]} if k=='object' else {})},'required':False} for k in kinds]
    at={k+'List':[{'label':'string'}] if k=='object' else [k] for k in kinds}
    av={'stringList':[''],'integerList':[0],'booleanList':[True],'numberList':[0],'objectList':[{'label':''}]}
    add('typed-arrays','原五种array-*简写分别生成单元素模板',arrays,arrays,at,av,[nonfile(k+'List','array-'+k,False,arrays[i]['schema']) for i,k in enumerate(kinds)],{},0)
    unknown={'id':'unknown','name':'tokenLike','schema':{'type':'custom-kind'},'required':True}
    add('unknown-before-file','未知类型能成为旧首项并挡住后续真实文件', [unknown,pdf],[unknown,pdf],{'tokenLike':'custom-kind','document':'PDF'},{'tokenLike':{},'document':'PDF'},[nonfile('tokenLike','custom-kind',True),fileextra(pdf)],nonfile('tokenLike','custom-kind',True),0,
        notes=['NO_SUPPORT_TYPE仅10种固定字符串，未知type不被排除；getFileType未知分支为NONE。'])
    empty_allowed={'id':'empty-allowed','name':'text','schema':{'type':'string'},'allowedFileType':[],'required':False}
    add('empty-allowed-file-types','空allowedFileType：JsonConverter回退schema，Maas会越界', [empty_allowed],[empty_allowed],{'text':'string'},{'text':''},failure={'source':'MaasUtil.getInputsType:568','expectedExceptionClass':'java.lang.IndexOutOfBoundsException','reason':'非null空JSONArray.getString(0)，不满足原非空先决条件。'},
        notes=['不把原异常改记成功；迁移边界应明确拒绝，精确HTTP错误码由后续正式合同决定。'])
    absent_schema={'id':'missing-schema','name':'untyped','schema':None,'required':None}
    absent_typed={'id':'missing-schema','name':'untyped'}
    add('null-schema','schema/required显式null经原DTO JSON后省略：两原算法行为不同',[absent_schema],[absent_typed],{'untyped':'object'},{'untyped':{}},failure={'source':'MaasUtil.getInputsType:578','expectedExceptionClass':'java.lang.NullPointerException','reason':'非file分支getJSONObject(schema)返回null后调用getString(type)。'})
    nullable={'id':'nullable','name':'label','nameErrMsg':None,'schema':{'type':'string','default':None,'futureSchema':True},'allowedFileType':None,'fileType':None,'description':None,'required':None,'refId':None,'deleteDisabled':None,'disabled':None,'customParameterType':None,'futureRoot':'原DTO未声明'}
    nullable_typed={'id':'nullable','name':'label','schema':{'type':'string'}}
    add('dto-nullable-and-unknown','原DTO的12字段nullable状态与unknown字段裁剪',[nullable],[nullable_typed],{'label':'string'},{'label':''},[nonfile('label','string',None)],{},0,
        notes=['raw canvas不应据typed旧读取链永久删unknown/null；这里仅记录原inputs读取响应的typed投影。','原字段null state按真正DTO反射核对；未运行原HTTP serializer，不能把WriteNulls探针误称原响应bytes。'])
    blank={'id':'blank','name':'','schema':{'type':'string'},'required':False}
    dup1={'id':'duplicate-first','name':'same','schema':{'type':'string'},'allowedFileType':['pdf'],'required':True}
    dup2={'id':'duplicate-last','name':'same','schema':{'type':'string'},'allowedFileType':['txt'],'required':False}
    add('duplicate-and-blank-names','空名称模板跳过；重复名称模板最后覆盖，metadata首项仍按原次序',[blank,dup1,dup2],[blank,dup1,dup2],{'same':'txt'},{'same':'txt'},[nonfile('','string',False),fileextra(dup1),fileextra(dup2)],fileextra(dup1),1,
        notes=['原Maas不跳过blank name；不得用JsonConverter结果JSONObject取代完整ordered extraInputsConfig。'])
    assert len(cases)==15
    return cases

def main():
    sources=[COMMON/'util/MaasUtil.java',COMMON/'service/workflow/impl/WorkflowBotParamServiceImpl.java',COMMON/'dto/workflow/WorkflowInputTypeDto.java',COMMON/'enums/bot/BotUploadEnum.java',TOOLKIT/'tool/JsonConverter.java',*[BIZ/(name+'.java') for name in ['BizInputOutput','BizSchema','BizProperty','BizValue']],TOOLKIT/'service/workflow/WorkflowService.java',SOURCE/'hub/src/main/java/com/iflytek/astron/console/hub/controller/workflow/WorkflowBotController.java',SOURCE/'pom.xml',ROOT/'frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts',ROOT/'frontend/paiflow-web/src/services/flow.ts']
    before={p.relative_to(ROOT).as_posix():sha(p) for p in sources}
    cases=make_cases()
    BUILD.mkdir(exist_ok=True)
    input_path=BUILD/'fixed-cases-input.json'
    input_path.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    jars=[Path('C:/Users/admin/.m2/repository')/p for p in [
        'com/alibaba/fastjson2/fastjson2/2.0.51/fastjson2-2.0.51.jar',
        'org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar',
        'org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar',
        'org/projectlombok/lombok/1.18.32/lombok-1.18.32.jar',
        'com/fasterxml/jackson/core/jackson-annotations/2.19.2/jackson-annotations-2.19.2.jar']]
    if any(not p.is_file() for p in jars):raise FileNotFoundError('只使用现有缓存，不安装依赖')
    cp=os.pathsep.join(str(p) for p in jars)
    compiled=[TOOLKIT/'tool/JsonConverter.java',COMMON/'dto/workflow/WorkflowInputTypeDto.java',*[BIZ/(name+'.java') for name in ['BizInputOutput','BizSchema','BizProperty','BizValue']],CHECKS/'InputTypesSourceReview.java']
    javac=['javac','-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','-cp',cp,'-processorpath',str(jars[3]),'-d',str(BUILD),'-s',str(BUILD/'generated'),*[str(p) for p in compiled]]
    compile_run=subprocess.run(javac,cwd=ROOT,capture_output=True)
    (BUILD/'javac.stdout.bin').write_bytes(compile_run.stdout)
    (BUILD/'javac.stderr.bin').write_bytes(compile_run.stderr)
    try: compile_output=(compile_run.stdout+compile_run.stderr).decode('utf-8'); compiler_encoding='utf-8'
    except UnicodeDecodeError: compile_output=(compile_run.stdout+compile_run.stderr).decode('gb18030'); compiler_encoding='gb18030'
    (BUILD/'javac.log').write_text(compile_output,encoding='utf-8')
    if compile_run.returncode:raise RuntimeError(compile_output)
    java=['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',os.pathsep.join([str(BUILD),cp]),'InputTypesSourceReview',str(input_path)]
    run=subprocess.run(java,cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    (BUILD/'java.log').write_text(run.stdout+run.stderr,encoding='utf-8')
    if run.returncode:raise RuntimeError(run.stderr)
    observed=json.loads(run.stdout)
    by_id={item['id']:item for item in observed['cases']}
    checks=[]
    for case in cases:
        exp=case['independentExpected'];actual=by_id[case['id']]
        pairs={'getInputsInfoData':'actualGetInputsInfoData','rawJsonConverterTypeTemplate':'actualRawTypeTemplate','rawJsonConverterValueTemplate':'actualRawValueTemplate','typedJsonConverterTypeTemplate':'actualTypedTypeTemplate','typedJsonConverterValueTemplate':'actualTypedValueTemplate','returnedDtoFieldState':'actualDtoFieldState'}
        for e,a in pairs.items():
            checks.append({'caseId':case['id'],'assertion':e,'passed':exp[e]==actual[a]})
        case['actualOriginalPureObservations']=actual
    differences=[item for item in checks if not item['passed']]
    after={p.relative_to(ROOT).as_posix():sha(p) for p in sources}
    if before!=after:raise RuntimeError('原来源窗口改变')
    evidence={'schemaVersion':'paiflow.input-types.source-contract-review.v1','checkedAt':datetime.now(timezone.utc).isoformat(),
        'caseCount':len(cases),'cases':cases,'sourceHashesBefore':before,'sourceHashesAfter':after,'sourceWindowChanges':[],
        'sourceSemantics':{'configuredLocalRead':'hub maas.getInputs默认/workflow/get-inputs-info；get-inputs-yype是另一JsonConverter template接口，不能替代DTO列表。',
            'toolkitTypedRead':'WorkflowService.getInputsInfo:3366-3385，首个node-start前缀节点的BizInputOutput List用默认JSON.toJSONString序列化，data是JSON数组String。',
            'metadataTransformation':'MaasUtil.getInputsType:546-597：固定AGENT_USER_INPUT只从metadata过滤、allowedFileType首项lowercase、ordered extraInputsConfig与keepOldValue首项extraInputs；回传res原data不滤。',
            'selection':'MaasUtil.keepOldValue:606-619：排除schema.type array-string和NO_SUPPORT_TYPE10种固定值，保第一个其他type；getFileType:271-294映射标量1..7/数组21..27/未知0。',
            'dto':'WorkflowInputTypeDto12字段均为引用类型，无primitive默认值；schema是JSONObject，refId/deleteDisabled/disabled是Object。BizInputOutput/BizSchema/BizProperty读取链更早裁剪其未声明字段。'},
        'execution':{'javacCommand':javac,'javacExitCode':compile_run.returncode,'javaCommand':java,'javaExitCode':run.returncode,
            'workingDirectory':str(ROOT),'compilerDiagnosticEncoding':compiler_encoding,'compiledOriginalSources':[p.relative_to(ROOT).as_posix() for p in compiled[:-1]],
            'cachedJarHashes':{str(p):sha(p) for p in jars},'pureAssertions':len(checks),'pureDifferences':differences,
            'formatPrimitiveProbes':observed['formatPrimitiveProbes'],'fullMaasUtilExecuted':False,'fullToolkitServiceExecuted':False,
            'dtoHttpSerializerExecuted':False,'originalMetadataPersistenceExecuted':False},
        'interpretation':['原JsonConverter完整原源码与原Biz/WorkflowInputTypeDto在固定参考依赖下实际执行，可证明本15例纯模板转换和DTO字段状态。',
            'MaasUtil metadata/supportUpload/异常预期为逐行源码推导；其完整方法先外部HTTP后真实Mapper写，未编译/运行该类，不把本材料称Maas联机或业务通过。',
            '空文件列表越界辅助probe只执行Fastjson JSONArray.getString(0)，不是完整MaasService实测。',
            '默认持久JSON省略null与HTTP nullable字段是不同边界；本材料显式保存memory和storedJson语义，但未跑原HTTP serializer。',
            'unknown/blank/重复名字和malformed原行为用于定位来源边界，不自动授权正式服务接受非法结构或把原缺陷作为正式成功。',
            '仅证据私有javac输出，不写原参考、main/test、target或contracts；QA冻结后再由owner审查纳入正式fixture。'],
        'noMaven':True,'productionSourceWritten':False,'frontendWritten':False,'targetWritten':False,'contractsWritten':False,
        'realDatabaseWrites':False,'network':False,'servicesStarted':False,'businessVerified':False}
    path=CHECKS/'工作流原输入类型合同材料.json'
    path.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'path':path.relative_to(ROOT).as_posix(),'sha256':sha(path),'caseCount':len(cases),'pureAssertions':len(checks),'pureDifferences':differences,'sourceWindowChanges':0,'fullMaasUtilExecuted':False},ensure_ascii=False,indent=2))
    if differences:raise AssertionError(differences)
if __name__=='__main__':main()
