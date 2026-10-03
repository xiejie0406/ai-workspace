"""真实共享拒绝策略导致实际Stream tick同线程读取/发送的首反例；只在Temp执行。"""
from pathlib import Path
import ast
import hashlib
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
STREAM = ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java'
CONFIG = ROOT/'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/config/ThreadPoolConfig.java'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p):sha(p) for p in [STREAM,CONFIG]}
assert before[str(STREAM)]=='8af1d04ded19e0ac1447b3dc96321c297f50dcf878db1f6791eb600ff3f5d525', '本次首反例必须绑定原519冻结Stream'
baseline=CHECKS/'调试薄流CallerRuns首反例源码'
baseline.mkdir(exist_ok=True)
for p in [STREAM,CONFIG]:
    dest=baseline/p.name
    if dest.exists():assert sha(dest)==sha(p), '拒覆盖旧源'
    else:dest.write_bytes(p.read_bytes())
xml=ROOT/'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.adapter.legacy.LegacyAgentContractTest.xml'
props={p.get('name'):p.get('value') for p in ET.parse(xml).getroot().findall('./properties/property')}
cp=props['java.class.path']
temp=Path(tempfile.mkdtemp(prefix='paiflow-debug-caller-runs-'))
inputs={}
def write(relative,content):
    p=temp/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');inputs[relative]=sha(p);return p
files=[write('com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java',STREAM.read_text(encoding='utf-8-sig')),
       write('com/ruoyi/framework/config/ThreadPoolConfig.java',CONFIG.read_text(encoding='utf-8-sig'))]
# 只读取旧生命周期检查的明确内存fixtures，不执行旧10组检查、不改旧证据。
fixture_source=CHECKS/'DebugStreamLifecycleProbe.py'
for stmt in ast.parse(fixture_source.read_text(encoding='utf-8')).body:
    if isinstance(stmt,ast.Assign) and isinstance(stmt.value,ast.Call) and isinstance(stmt.value.func,ast.Name) and stmt.value.func.id=='source':
        if len(stmt.value.args)==2 and all(isinstance(x,ast.Constant) and isinstance(x.value,str) for x in stmt.value.args):
            files.append(write(stmt.value.args[0].value,stmt.value.args[1].value))
harness=write('DebugStreamCallerRunsProbe.java',r'''
import java.util.concurrent.*;import java.lang.reflect.*;import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.framework.config.ThreadPoolConfig;
import com.ruoyi.paiflow.adapter.legacy.LegacyDebugStreamService;
public class DebugStreamCallerRunsProbe {
 static Field findField(Class<?> c,String name)throws Exception{while(c!=null){try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
 static class InlineIo extends DebugStreamLifecycleProbe.Io {
  final RejectedExecutionHandler handler;final ThreadPoolExecutor privateInactivePool=new ThreadPoolExecutor(0,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
  int submissions;long submitterThread;
  InlineIo(RejectedExecutionHandler handler){this.handler=handler;}
  @Override public void execute(Runnable work){submissions++;submitterThread=Thread.currentThread().threadId();handler.rejectedExecution(work,privateInactivePool);}
 }
 public static void main(String[] args)throws Exception{
  // 原@Bean构造配置，未initialize/start共享pool，没有填充共享200线程/1000队列。
  var configured=new ThreadPoolConfig().threadPoolTaskExecutor();
  var rejection=(RejectedExecutionHandler)findField(configured.getClass(),"rejectedExecutionHandler").get(configured);
  DebugStreamLifecycleProbe.ok(rejection instanceof ThreadPoolExecutor.CallerRunsPolicy,"actual bean caller-runs");
  var rig=new DebugStreamLifecycleProbe.Rig();var inline=new InlineIo(rejection);
  DebugStreamLifecycleProbe.field(LegacyDebugStreamService.class,"io").set(rig.service,inline);
  long tickThread=Thread.currentThread().threadId();long[] pageThread={-1},sendThread={-1};
  rig.bridge.port=(a,after)->{pageThread[0]=Thread.currentThread().threadId();return DebugStreamLifecycleProbe.page(false,false,1);};
  var connection=new DebugStreamLifecycleProbe.Connection(rig,rig.service.reserve(rig.session(42)));
  connection.emitter.onSend=()->sendThread[0]=Thread.currentThread().threadId();
  try{
   rig.scheduler.once();
   var observed=JSONObject.of("actualBeanPolicy",rejection.getClass().getName(),"corePoolSize",configured.getCorePoolSize(),"maxPoolSize",configured.getMaxPoolSize(),"queueCapacity",configured.getQueueCapacity(),"submissions",inline.submissions,
    "tickThread",tickThread,"submitterThread",inline.submitterThread,"pageThread",pageThread[0],"sendThread",sendThread[0],"bridgeCalls",rig.bridge.calls,"sends",connection.emitter.sent,"serverAfter",connection.after(),"failure",connection.emitter.failure==null?null:connection.emitter.failure.getClass().getName(),"permitsBeforeCleanup",rig.available(),
    "invariantExpected",JSONObject.of("bridgeCalls",0,"sends",0,"code",503,"permits",64),"invariantSatisfied",false);
   DebugStreamLifecycleProbe.ok(inline.submissions==1&&pageThread[0]==tickThread&&sendThread[0]==tickThread&&rig.bridge.calls==1&&connection.emitter.sent==1&&connection.after()==1&&connection.emitter.failure==null&&rig.available()==63,"first causal counterexample reproduced");
   connection.disconnect();DebugStreamLifecycleProbe.ok(rig.available()==64,"explicit cleanup restored");
   observed.put("permitsAfterExplicitCleanup",rig.available());System.out.println(observed.toJSONString());
  }finally{inline.privateInactivePool.shutdown();connection.disconnect();}
 }
}
''')
files.append(harness)
jdk=Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
compiled=subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(temp),*[str(p) for p in files]],capture_output=True,text=True,encoding='utf-8')
ran=subprocess.run([str(jdk/'java.exe'),'-classpath',str(temp)+';'+cp,'DebugStreamCallerRunsProbe'],capture_output=True,text=True,encoding='utf-8',timeout=30) if compiled.returncode==0 else None
after={str(p):sha(p) for p in [STREAM,CONFIG]}
result={'recordType':'实际首反例，exit0代表复现到了未满足的发送线程不变量，不代表候选通过',
        'scope':'原ThreadPoolConfig真实bean只构造未initialize；提取实际CallerRunsPolicy对实际Stream tick提交任务直接施用。Bridge/Token/Identity/Emitter/手动Scheduler为明确内存ports，不是真实共享pool饱和、Servlet、HTTP/JWT/Redis或数据库联机。',
        'sharedPoolThreadsStarted':0,'networkCalls':0,'databaseCalls':0,'redisCalls':0,'mavenInvocations':0,'sharedTargetWrites':0,
        'sourceBefore':before,'sourceAfter':after,'changed':[p for p in before if before[p]!=after[p]],
        'baselineSourceDirectory':str(baseline),'fixtureSourceSha256':sha(fixture_source),'temp':str(temp),'compiledInputsSha256':inputs,
        'classpathOrigin':str(xml),'classpathEntries':len(cp.split(';')),
        'compileExit':compiled.returncode,'compileStderr':compiled.stderr,'runExit':None if ran is None else ran.returncode,
        'stdout':None if ran is None else ran.stdout,'stderr':None if ran is None else ran.stderr,
        'observed':None if ran is None or ran.returncode else json.loads(ran.stdout),
        'suggestedMinimalFix':'tick捕获提交线程；提交给现pool的job仅当实际执行线程不同才readSend。同线程降级明确503，关闭流并释放capacity，finally清inFlight；不改原共享CallerRunsPolicy，不新建池。'}
out=CHECKS/'原调试薄流CallerRuns调度线程首反例.json'
if out.exists():raise RuntimeError('已有首反例证据拒覆盖')
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'output':str(out),'sha256':sha(out),'compileExit':compiled.returncode,'runExit':None if ran is None else ran.returncode,'stdout':None if ran is None else ran.stdout,'stderr':compiled.stderr if ran is None else ran.stderr,'changed':result['changed']},ensure_ascii=False))
assert compiled.returncode==0 and ran is not None and ran.returncode==0 and not result['changed']
