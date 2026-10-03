"""实际候选Stream源码的内存生命周期检查；认证/Bridge端口受控，不运行HTTP或数据库。"""
from pathlib import Path
import hashlib
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
STREAM = ROOT / 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
before = sha(STREAM)
xml = ROOT / 'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.adapter.legacy.LegacyAgentContractTest.xml'
props = {p.get('name'): p.get('value') for p in ET.parse(xml).getroot().findall('./properties/property')}
classpath = props['java.class.path']
temp = Path(tempfile.mkdtemp(prefix='paiflow-debug-stream-'))
files = {}
def source(relative, content):
    path = temp / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
    files[relative] = sha(path)
    return path

actual = source('com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java', STREAM.read_text(encoding='utf-8-sig'))
bridge = source('com/ruoyi/paiflow/adapter/legacy/LegacyDebugBridgeService.java', r'''
package com.ruoyi.paiflow.adapter.legacy;
import java.util.*;import com.alibaba.fastjson2.JSONObject;
public class LegacyDebugBridgeService {
 public record Attachment(String runId,long workflowVersionId,long spaceId,JSONObject receipt,long after){}
 public record Frame(String id,JSONObject data){}
 public record Page(List<Frame> frames,long scannedThrough,long watermark,String state,boolean waiting,boolean terminal){}
 public int calls;public java.util.function.BiFunction<Attachment,Long,Page> port;
 public Page page(Attachment a,long after){calls++;return port.apply(a,after);}
}
''')
tokens = source('com/ruoyi/framework/web/service/TokenService.java', r'''
package com.ruoyi.framework.web.service;
import com.ruoyi.common.core.domain.model.LoginUser;
public class TokenService {public LoginUser current;public int calls;public LoginUser getLoginUserByToken(String token){calls++;return current;}}
''')
identities = source('com/ruoyi/paiflow/service/IdentityMappingService.java', r'''
package com.ruoyi.paiflow.service;
import com.ruoyi.common.core.domain.model.LoginUser;
public class IdentityMappingService {public LoginUser current;public LoginUser current(){return current;}}
''')
harness = source('DebugStreamLifecycleProbe.java', r'''
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.paiflow.service.IdentityMappingService;
import com.ruoyi.paiflow.adapter.legacy.*;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class DebugStreamLifecycleProbe {
 static final JSONObject results=new JSONObject();
 static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);}
 static class Future implements ScheduledFuture<Object>{boolean cancelled;public long getDelay(TimeUnit unit){return 0;}public int compareTo(Delayed value){return 0;}public boolean cancel(boolean mayInterrupt){cancelled=true;return true;}public boolean isCancelled(){return cancelled;}public boolean isDone(){return cancelled;}public Object get(){return null;}public Object get(long timeout,TimeUnit unit){return null;}}
 static class Scheduler extends ScheduledThreadPoolExecutor {Runnable tick;Future future;boolean reject;Scheduler(){super(1);}public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long initial,long delay,TimeUnit u){if(reject)throw new RejectedExecutionException();tick=r;future=new Future();return future;}void once(){if(!future.cancelled)tick.run();}}
 static class Io extends ThreadPoolTaskExecutor {final List<Runnable> pending=new ArrayList<>();boolean reject;@Override public void execute(Runnable work){if(reject)throw new RejectedExecutionException();pending.add(work);}void once(){pending.removeFirst().run();}}
 static class Emitter extends SseEmitter {int sent,completed;Throwable failure;Runnable done,timeout;Consumer<Throwable> error;Runnable onSend;Emitter(){super(60_000L);}@Override public synchronized void send(SseEventBuilder event){sent++;if(onSend!=null)onSend.run();}@Override public synchronized void complete(){completed++;if(done!=null)done.run();}@Override public synchronized void completeWithError(Throwable e){failure=e;if(error!=null)error.accept(e);}@Override public synchronized void onCompletion(Runnable r){done=r;}@Override public synchronized void onTimeout(Runnable r){timeout=r;}@Override public synchronized void onError(Consumer<Throwable> c){error=c;}}
 static Field field(Class<?> clazz,String name)throws Exception{var f=clazz.getDeclaredField(name);f.setAccessible(true);return f;}
 static LoginUser login(long id,String session){var u=new LoginUser();u.setUserId(id);u.setToken(session);u.setExpireTime(System.currentTimeMillis()+600_000);u.setPermissions(Set.of("paiflow:run:query"));return u;}
 static class Rig {final Scheduler scheduler=new Scheduler();final Io io=new Io();final LegacyDebugBridgeService bridge=new LegacyDebugBridgeService();final TokenService tokens=new TokenService();final IdentityMappingService identities=new IdentityMappingService();final LegacyDebugStreamService service=new LegacyDebugStreamService(bridge,tokens,identities,scheduler,io);Rig(){tokens.current=identities.current=login(42,"memory-session");bridge.port=(a,after)->new LegacyDebugBridgeService.Page(List.of(),after,after,"RUNNING",false,false);}int available()throws Exception{return ((Semaphore)field(LegacyDebugStreamService.class,"capacity").get(service)).availablePermits();}int actorCount()throws Exception{return ((Map<?,?>)field(LegacyDebugStreamService.class,"actors").get(service)).size();}LegacyDebugStreamService.Session session(long actor)throws Exception{var c=LegacyDebugStreamService.Session.class.getDeclaredConstructor(String.class,String.class,long.class);c.setAccessible(true);return c.newInstance("memory-bearer","memory-session",actor);}}
 static class Connection {final Object value;final Emitter emitter=new Emitter();Connection(Rig r,LegacyDebugStreamService.Reservation reservation)throws Exception{var c=Arrays.stream(LegacyDebugStreamService.class.getDeclaredClasses()).filter(x->x.getSimpleName().equals("Connection")).findFirst().orElseThrow();var ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);value=ctor.newInstance(r.service,reservation,new LegacyDebugBridgeService.Attachment("memory-run",1,1,new JSONObject(),0));field(c,"emitter").set(value,emitter);var start=c.getDeclaredMethod("start");start.setAccessible(true);start.invoke(value);}long after()throws Exception{return (long)field(value.getClass(),"after").get(value);}void disconnect(){emitter.done.run();}}
 static LegacyDebugBridgeService.Page page(boolean waiting,boolean terminal,int count){var frames=new ArrayList<LegacyDebugBridgeService.Frame>();for(int i=1;i<=count;i++)frames.add(new LegacyDebugBridgeService.Frame("memory-run:"+i,JSONObject.of("memory",i)));return new LegacyDebugBridgeService.Page(frames,count,count,terminal?"COMPLETED":"RUNNING",waiting,terminal);}
 static void execute(Rig r){r.scheduler.once();r.io.once();}
 static boolean code(Runnable work,int expected){try{work.run();return false;}catch(ServiceException e){return Objects.equals(e.getCode(),expected);}}
 public static void main(String[] args)throws Exception{
  var original=SecurityContextHolder.getContext();var originalRequest=RequestContextHolder.getRequestAttributes();var prior=SecurityContextHolder.createEmptyContext();var old=login(7,"prior");prior.setAuthentication(new UsernamePasswordAuthenticationToken(old,null,old.getAuthorities()));SecurityContextHolder.setContext(prior);RequestContextHolder.resetRequestAttributes();
  try{
   var actorRig=new Rig();var actorReservations=new ArrayList<LegacyDebugStreamService.Reservation>();for(int i=0;i<4;i++)actorReservations.add(actorRig.service.reserve(actorRig.session(42)));ok(code(()->actorRig.service.reserve(uncheckedSession(actorRig,42)),503),"actor fifth rejected");ok(actorRig.available()==60,"actor denial does not leak global permit");for(var rr:actorReservations){rr.close();rr.close();}ok(actorRig.available()==64&&actorRig.actorCount()==0,"actor close idempotent");results.put("actorCapacity",JSONObject.of("limit",4,"fifth503",true,"permitsRestored",64,"actorsEmpty",true));
   var globalRig=new Rig();var globalReservations=new ArrayList<LegacyDebugStreamService.Reservation>();for(int i=0;i<64;i++)globalReservations.add(globalRig.service.reserve(globalRig.session(i+1)));ok(code(()->globalRig.service.reserve(uncheckedSession(globalRig,1000)),503),"global 65th rejected");for(var rr:globalReservations){rr.close();rr.close();}ok(globalRig.available()==64&&globalRig.actorCount()==0,"global close restored");results.put("globalCapacity",JSONObject.of("limit",64,"sixtyFifth503",true,"permitsRestored",64));
   var rejected=new Rig();rejected.scheduler.reject=true;var res=rejected.service.reserve(rejected.session(42));ok(code(()->res.open(new LegacyDebugBridgeService.Attachment("memory-run",1,1,new JSONObject(),0)),503),"schedule rejection");res.close();ok(rejected.available()==64&&rejected.actorCount()==0,"schedule restores");results.put("schedulerRejected",JSONObject.of("code",503,"permitsRestored",64,"bridgeCalls",rejected.bridge.calls));
   var live=new Rig();live.bridge.port=(a,after)->{ok(SecurityUtils.getUserId()==42,"fresh actor in page");ok(RequestContextHolder.getRequestAttributes()!=null,"private request scope");return page(false,false,1);};var liveConnection=new Connection(live,live.service.reserve(live.session(42)));execute(live);ok(liveConnection.emitter.sent==1&&liveConnection.after()==1,"first live frame");ok(SecurityContextHolder.getContext()==prior&&RequestContextHolder.getRequestAttributes()==null,"page contexts restored");live.tokens.current=null;execute(live);ok(live.bridge.calls==1&&liveConnection.emitter.failure instanceof ServiceException&&((ServiceException)liveConnection.emitter.failure).getCode()==401,"logout before page");ok(live.available()==64&&live.scheduler.future.cancelled,"logout releases");ok(SecurityContextHolder.getContext()==prior&&RequestContextHolder.getRequestAttributes()==null,"logout contexts retained");results.put("sessionInvalidated",JSONObject.of("initialFrames",1,"pageCalls",1,"subsequent401",true,"permitsRestored",64,"contextsRestored",true));
   var changed=new Rig();var changedConnection=new Connection(changed,changed.service.reserve(changed.session(42)));changed.tokens.current=login(42,"different-session");execute(changed);ok(changed.bridge.calls==0&&changedConnection.emitter.failure instanceof ServiceException&&changed.available()==64,"session identity changed");results.put("sessionChanged",JSONObject.of("bridgeCalls",0,"error401",((ServiceException)changedConnection.emitter.failure).getCode()==401));
   var waiting=new Rig();waiting.bridge.port=(a,after)->page(true,false,1);var waitConnection=new Connection(waiting,waiting.service.reserve(waiting.session(42)));execute(waiting);ok(waitConnection.emitter.sent==1&&waitConnection.emitter.completed==1&&waitConnection.after()==1&&waiting.available()==64,"waiting EOF releases without extra frame");results.put("waitingEOF",JSONObject.of("memoryPageWaiting",true,"actualSends",1,"completeCalls",1,"permitsRestored",64,"manufacturedFrames",0));
   var terminal=new Rig();terminal.bridge.port=(a,after)->page(false,true,0);var termConnection=new Connection(terminal,terminal.service.reserve(terminal.session(42)));execute(terminal);ok(termConnection.emitter.sent==0&&termConnection.emitter.completed==1&&terminal.available()==64,"empty terminal closes");results.put("emptyTerminalEOF",JSONObject.of("actualSends",0,"completeCalls",1,"permitsRestored",64));
   var disconnected=new Rig();disconnected.bridge.port=(a,after)->page(false,false,2);var disConnection=new Connection(disconnected,disconnected.service.reserve(disconnected.session(42)));disConnection.emitter.onSend=disConnection::disconnect;execute(disconnected);ok(disConnection.emitter.sent==1&&disConnection.after()==0&&disconnected.available()==64,"partial send does not advance page cursor");disconnected.scheduler.once();ok(disconnected.bridge.calls==1&&disconnected.io.pending.isEmpty(),"no read after disconnect");results.put("earlyDisconnect",JSONObject.of("pageFrames",2,"actualSends",1,"serverAfter",0,"pageCalls",1,"permitsRestored",64));
   var busy=new Rig();var busyConnection=new Connection(busy,busy.service.reserve(busy.session(42)));busy.scheduler.once();busy.scheduler.once();ok(busy.io.pending.size()==1,"one inFlight");busy.io.once();busyConnection.disconnect();ok(busy.available()==64,"busy release");results.put("singleInFlight",JSONObject.of("queued",1,"pageCalls",busy.bridge.calls,"permitsRestored",64));
   var ioRejected=new Rig();var ioConnection=new Connection(ioRejected,ioRejected.service.reserve(ioRejected.session(42)));ioRejected.io.reject=true;ioRejected.scheduler.once();ok(ioConnection.emitter.failure instanceof ServiceException&&((ServiceException)ioConnection.emitter.failure).getCode()==503&&ioRejected.available()==64&&ioRejected.bridge.calls==0,"IO rejection releases");results.put("ioRejected",JSONObject.of("code",503,"pageCalls",0,"permitsRestored",64));
   System.out.println(results.toJSONString());
  }finally{SecurityContextHolder.setContext(original);if(originalRequest==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(originalRequest);}
 }
 static LegacyDebugStreamService.Session uncheckedSession(Rig r,long actor){try{return r.session(actor);}catch(Exception e){throw new RuntimeException(e);}}
}
''')
jdk = Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
compiled = subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-classpath',classpath,'-d',str(temp),*[str(x) for x in [actual, bridge, tokens, identities, harness]]], capture_output=True, text=True, encoding='utf-8')
ran = subprocess.run([str(jdk/'java.exe'),'-classpath',str(temp)+';'+classpath,'DebugStreamLifecycleProbe'],capture_output=True,text=True,encoding='utf-8',timeout=30) if compiled.returncode == 0 else None
after = sha(STREAM)
result = {'scope':'实际LegacyDebugStreamService源码在Temp独立编译；TokenService/IdentityMappingService/Bridge为明确内存端口，Scheduler/IO/Emitter为受控内存交付。不是实际Bridge事务/Redis/JWT/Servlet/SSE联机或HTTP业务。',
          'networkCalls':0,'databaseCalls':0,'redisCalls':0,'mavenInvocations':0,'sharedTargetWrites':0,
          'sourcePath':str(STREAM),'sourceBeforeSha256':before,'sourceAfterSha256':after,'changed':before!=after,
          'temp':str(temp),'compiledInputsSha256':files,'classpathOrigin':str(xml),'classpathEntries':len(classpath.split(';')),
          'compileExit':compiled.returncode,'compileStdout':compiled.stdout,'compileStderr':compiled.stderr,
          'runExit':None if ran is None else ran.returncode,'stdout':None if ran is None else ran.stdout,'stderr':None if ran is None else ran.stderr}
passed = compiled.returncode == 0 and ran is not None and ran.returncode == 0 and before == after
output = CHECKS / ('原调试流生命周期独立检查.json' if passed else '原调试流生命周期独立检查-首次探针失败.json')
if output.exists():
    raise RuntimeError('证据已有，拒绝覆盖：'+str(output))
output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'output':str(output),'sha256':sha(output),'compileExit':compiled.returncode,'runExit':None if ran is None else ran.returncode,'stdout':None if ran is None else ran.stdout,'stderr':compiled.stderr if ran is None else ran.stderr,'changed':before!=after},ensure_ascii=False))
assert passed
