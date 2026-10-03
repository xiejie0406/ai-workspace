"""仅核对本次RFD断言修改及真实执行所用Spring字节码，不运行Maven。"""
from pathlib import Path
import hashlib, json, re

ROOT=Path.cwd()
CHECKS=ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks'
OLD=CHECKS/'工作流YAML导出RFD断言修正前原文'
TEST=ROOT/'ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportContractTest.java'
NAME='malformedAndOverflowPathRejectBeforeServiceEvenWithGlobalAdvice'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def split(text):
    matches=list(re.finditer(r'    @Test void (\w+)\(',text))
    return {m.group(1):text[m.start():matches[i+1].start() if i+1<len(matches) else len(text)] for i,m in enumerate(matches)}
before=(OLD/TEST.name).read_text(encoding='utf-8')
after=TEST.read_text(encoding='utf-8')
old_methods,new_methods=split(before),split(after)
assert old_methods.keys()==new_methods.keys() and len(new_methods)==8
changed=[k for k in old_methods if old_methods[k]!=new_methods[k]]
assert changed==[NAME]
assert before.replace(old_methods[NAME],'')==after.replace(new_methods[NAME],'')
assert sha(OLD/TEST.name)=='e96b1f6f04d251bc7bd2c0ef9d284cf17340fee82bff8187c11f7483dbbb3c2d'
jar=Path(r'C:/Users/admin/.m2/repository/org/springframework/spring-webmvc/7.0.7/spring-webmvc-7.0.7.jar')
log=CHECKS/'工作流YAML导出实际Spring7.0.7-RFD实现-javap.log'
raw=log.read_bytes()
code=raw.decode('utf-16') if raw.startswith((b'\xff\xfe',b'\xfe\xff')) else raw.decode('utf-8-sig')
assert 'private void addContentDispositionHeader(' in code
assert 'String inline;filename=f.txt' in code
assert 'String Content-Disposition' in code and 'Method safeExtension:' in code
assert sha(jar)=='41c0c6ed2244cdbcd84a9955b4b2717e8466e20e82335b043ea9ce768dc38d9c'
inputs=[TEST,OLD/TEST.name,OLD/'输入.json',jar,log,
    ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportController.java',
    ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowExportService.java',
    ROOT/'frontend/paiflow-web/src/utils/http.ts']
proof={
  'status':'scoped_assertion_correction_frozen_not_reexecuted_here',
  'actualParentRun':{'executed':52,'passed':51,'failed':1,'sourceWindowMismatches':0,
     'failure':'Spring真实错误响应附加inline;filename=f.txt；原断言要求完全无头过严，HTTP400已正确'},
  'changedMethods':changed,'unchangedOtherMethodCount':7,'methodCount':8,
  'outsideChangedMethodExactlyEqual':True,
  'newAssertions':['HTTP400','JSON code400','Content-Disposition缺省或精确inline;filename=f.txt',
     '非attachment','无Access-Control-Expose-Headers','无flowMeta YAML正文','Service零调用'],
  'actualFramework':{'version':'7.0.7','source':'真实失败JUnit XML classpath；本机jar javap实际字节码',
     'method':'AbstractMessageConverterMethodProcessor.addContentDispositionHeader',
     'behavior':'无现存Content-Disposition、HTTP2xx或>=400时检查URI扩展，非安全扩展加入inline;filename=f.txt',
     'wrongVersionLog':'工作流YAML导出Spring7.0.8参考非执行版本-javap.log仅为先前误选参考，不作本次执行依据'},
  'inputs':[{'path':str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p),'sha256':sha(p)} for p in inputs],
  'validation':{'sourceDiffCheck':True,'mavenExecutedHere':False,'candidateTestPassed':False,
     'pending':'root仅复验受影响方法；其余51项保留原真实结果'},
  'limits':['未修改Controller、Service、前端或共享GlobalExceptionHandler','不把MockService传输合同视为业务授权/持久化/页面验收']
}
out=CHECKS/'工作流YAML导出RFD断言局部更正核对.json'
assert not out.exists(), '已交付证据不得覆写'
out.write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'proof':str(out.relative_to(ROOT)),'proofSha256':sha(out),'testSha256':sha(TEST),'changed':changed,'otherMethodsUnchanged':7},ensure_ascii=False))
