"""修后exact Stream定向复验：沿用旧10组断言，再核CallerRuns拒绝，原失败证据不覆盖。"""
from pathlib import Path
import argparse
import ast
import hashlib
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--expected-stream-sha',required=True)
args=parser.parse_args()
ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
STREAM=ROOT/'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java'
CONFIG=ROOT/'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/config/ThreadPoolConfig.java'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p):sha(p) for p in [STREAM,CONFIG]}
assert sha(STREAM)==args.expected_stream_sha,'拒运行非通知冻结Stream'
assert sha(CONFIG)=='44800f73209b0d1b7cea4e01270f2044b73546c9dbef8f9978f7976d36c37cf4','原共享池不能改变'
xml=ROOT/'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.adapter.legacy.LegacyAgentContractTest.xml'
props={p.get('name'):p.get('value') for p in ET.parse(xml).getroot().findall('./properties/property')}
cp=props['java.class.path']
temp=Path(tempfile.mkdtemp(prefix='paiflow-debug-caller-runs-fixed-'))
inputs={}
def write(relative,content):
    p=temp/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');inputs[relative]=sha(p);return p
files=[write('com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java',STREAM.read_text(encoding='utf-8-sig')),
       write('com/ruoyi/framework/config/ThreadPoolConfig.java',CONFIG.read_text(encoding='utf-8-sig'))]
oldfixture=CHECKS/'DebugStreamLifecycleProbe.py'
for stmt in ast.parse(oldfixture.read_text(encoding='utf-8')).body:
    if isinstance(stmt,ast.Assign) and isinstance(stmt.value,ast.Call) and isinstance(stmt.value.func,ast.Name) and stmt.value.func.id=='source':
        if len(stmt.value.args)==2 and all(isinstance(x,ast.Constant) and isinstance(x.value,str) for x in stmt.value.args):
            relative,content=[x.value for x in stmt.value.args]
            if relative=='DebugStreamLifecycleProbe.java':
                old='void once(){pending.removeFirst().run();}'
                new='''void once(){var work=pending.removeFirst();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var worker=new Thread(()->{try{var before=SecurityContextHolder.getContext();var request=RequestContextHolder.getRequestAttributes();work.run();ok(SecurityContextHolder.getContext()==before&&RequestContextHolder.getRequestAttributes()==request,"delivery thread contexts restored");}catch(Throwable e){failure.set(e);}},"memory-io-delivery");worker.setDaemon(true);worker.start();try{worker.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}if(worker.isAlive()){worker.interrupt();throw new AssertionError("memory delivery timeout");}if(failure.get()!=null)throw new AssertionError("memory delivery failed",failure.get());}'''
                assert content.count(old)==1
                content=content.replace(old,new)
            files.append(write(relative,content))
files.append(write('DebugStreamCallerRunsFixedProbe.java',r'''
import java.util.concurrent.*;import java.lang.reflect.*;import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.framework.config.ThreadPoolConfig;
import com.ruoyi.paiflow.adapter.legacy.LegacyDebugStreamService;
import com.ruoyi.common.exception.ServiceException;
public class DebugStreamCallerRunsFixedProbe {
 static Field findField(Class<?> c,String name)throws Exception{while(c!=null){try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
 static class InlineIo extends DebugStreamLifecycleProbe.Io {
  final RejectedExecutionHandler handler;final ThreadPoolExecutor privateInactivePool=new ThreadPoolExecutor(0,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
  int submissions;long submitterThread;
  InlineIo(RejectedExecutionHandler handler){this.handler=handler;}
  @Override public void execute(Runnable work){submissions++;submitterThread=Thread.currentThread().threadId();handler.rejectedExecution(work,privateInactivePool);}
 }
 public static void main(String[] args)throws Exception{
  var configured=new ThreadPoolConfig().threadPoolTaskExecutor();
  var rejection=(RejectedExecutionHandler)findField(configured.getClass(),"rejectedExecutionHandler").get(configured);
  DebugStreamLifecycleProbe.ok(rejection instanceof ThreadPoolExecutor.CallerRunsPolicy,"shared policy unchanged");
  var rig=new DebugStreamLifecycleProbe.Rig();var inline=new InlineIo(rejection);
  DebugStreamLifecycleProbe.field(LegacyDebugStreamService.class,"io").set(rig.service,inline);
  rig.bridge.port=(a,after)->DebugStreamLifecycleProbe.page(false,false,1);
  var connection=new DebugStreamLifecycleProbe.Connection(rig,rig.service.reserve(rig.session(42)));
  try{
   rig.scheduler.once();
   boolean inFlight=((java.util.concurrent.atomic.AtomicBoolean)DebugStreamLifecycleProbe.field(connection.value.getClass(),"inFlight").get(connection.value)).get();
   DebugStreamLifecycleProbe.ok(inline.submissions==1&&rig.bridge.calls==0&&connection.emitter.sent==0&&connection.after()==0&&connection.emitter.failure instanceof ServiceException&&((ServiceException)connection.emitter.failure).getCode()==503&&rig.available()==64&&!inFlight&&rig.scheduler.future.cancelled,"inline rejection stops before read/send and releases");
   var result=JSONObject.of("actualBeanPolicy",rejection.getClass().getName(),"submissions",inline.submissions,"submitterThread",inline.submitterThread,"bridgeCalls",rig.bridge.calls,"sends",connection.emitter.sent,"serverAfter",connection.after(),"errorCode",((ServiceException)connection.emitter.failure).getCode(),"permits",rig.available(),"inFlight",inFlight,"scheduleCancelled",rig.scheduler.future.cancelled,"invariantSatisfied",true);
   connection.disconnect();DebugStreamLifecycleProbe.ok(rig.available()==64,"release remains once");
   result.put("permitsAfterDuplicateClose",rig.available());System.out.println(result.toJSONString());
  }finally{inline.privateInactivePool.shutdown();connection.disconnect();}
 }
}
'''))
jdk=Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
compiled=subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(temp),*[str(p) for p in files]],capture_output=True,text=True,encoding='utf-8')
runs=[]
if compiled.returncode==0:
    for main in ['DebugStreamLifecycleProbe','DebugStreamCallerRunsFixedProbe']:
        r=subprocess.run([str(jdk/'java.exe'),'-classpath',str(temp)+';'+cp,main],capture_output=True,text=True,encoding='utf-8',timeout=30)
        runs.append({'main':main,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr,'observed':json.loads(r.stdout) if r.returncode==0 else None})
after={str(p):sha(p) for p in [STREAM,CONFIG]}
passed=compiled.returncode==0 and len(runs)==2 and all(r['exit']==0 for r in runs) and before==after
result={'scope':'actual修后Stream＋原真实bean CallerRunsPolicy，在Temp独立编译。旧10组断言原样，测试IO交付改为真实不同私有线程并join以表达正常异步；实际增加交付线程上下文恢复断言。新CallerRuns同提交线程则要求503且不读不发、release一次、清inFlight。端口为明确内存fixtures，不是真实共享pool/HTTP/JWT/Redis/数据库。',
        'networkCalls':0,'databaseCalls':0,'redisCalls':0,'mavenInvocations':0,'sharedTargetWrites':0,'sharedPoolThreadsStarted':0,
        'sourceBefore':before,'sourceAfter':after,'changed':[p for p in before if before[p]!=after[p]],'temp':str(temp),'compiledInputsSha256':inputs,
        'oldLifecycleFixtureSha256':sha(oldfixture),'preservedCounterexample':{'path':str(CHECKS/'原调试薄流CallerRuns调度线程首反例.json'),'sha256':sha(CHECKS/'原调试薄流CallerRuns调度线程首反例.json')},
        'classpathOrigin':str(xml),'classpathEntries':len(cp.split(';')),'compileExit':compiled.returncode,'compileStderr':compiled.stderr,'runs':runs,
        'passedGroups':11 if passed else None,'scopeChanged':'旧10组主断言没有削弱；因修后guard真实比较线程身份，先前同线程手动队列无法代表正常异步，fixture必须使用独立私有交付线程；旧10组结果及源不覆盖。'}
out=CHECKS/('原调试薄流CallerRuns修后定向复验.json' if passed else '原调试薄流CallerRuns修后定向复验-首失败.json')
if out.exists():raise RuntimeError('已有证据拒覆盖：'+str(out))
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'output':str(out),'sha256':sha(out),'compileExit':compiled.returncode,'runs':runs,'passedGroups':result['passedGroups'],'changed':result['changed']},ensure_ascii=False))
assert passed
