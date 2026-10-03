"""候选调试桥/薄流门只读审查的证据索引；不建立第二套开发状态。"""
from pathlib import Path
import hashlib
import json
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
base = 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/'
sources = [base + 'adapter/legacy/LegacyDebugBridgeService.java',
           base + 'adapter/legacy/LegacyDebugStreamService.java',
           base + 'adapter/legacy/LegacyDebugController.java',
           base + 'adapter/legacy/LegacyWorkflowStreamCodec.java',
           base + 'service/WorkflowService.java', base + 'service/AgentService.java',
           base + 'service/RunService.java', base + 'service/CheckpointService.java',
           base + 'service/ResourceAccessService.java', base + 'service/IdentityMappingService.java',
           base + 'mapper/RunMapper.java',
           'ruoyi-backend/ruoyi-paiflow/src/main/resources/mapper/paiflow/RunMapper.xml',
           'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/TokenService.java',
           'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/PermissionService.java',
           'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/security/context/PermissionContextHolder.java',
           'frontend/paiflow-web/src/utils/workflow-debug.ts',
           'frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts']
before = {p: sha(ROOT/p) for p in sources}
text = {p: (ROOT/p).read_text(encoding='utf-8-sig') for p in sources}
def at(relative, needle):
    return {'path':relative, 'line':text[relative][:text[relative].index(needle)].count('\n')+1, 'anchor':needle}
bridge = sources[0]
stream = sources[1]
workflow = sources[4]
run = sources[6]
checkpoint = sources[7]
facts = [
 {'id':'nested_http_shape', 'kind':'原实证失败后候选源码已收敛', 'statement':'实际Jackson3.1.2/2.21.2把JSONObject HTTP DTO的嵌套receipt/inputs解成LinkedHashMap；当前候选先窄验Map再getJSONObject，不接受字符串或数组。旧反例与旧源保留。', 'sources':[at(bridge,'instanceof Map<?,?>'),at(bridge,'JSONObject input=body.getJSONObject("inputs")')]},
 {'id':'lease_waiting', 'kind':'原源码缺口后候选源码已收敛', 'statement':'旧WAITING仅检查CP deadline。当前projection同时要求实际CP WAITING、run RUNNING、CP/run deadline未来和当前租约未来，并提供checkpoint_waiting；不在投影中伪写EXPIRED或终态。', 'sources':[at(bridge,'waiting="WAITING"'),at(checkpoint,'getLeaseExpiresAt()==null')]},
 {'id':'receipt_authority', 'kind':'源码核对', 'statement':'回执10字段由实际不可变debug version推出；requireDebugReceipt按实际debugOwner、WF/Agent/space绑定复核。comparison仍由不可变版本的mode/id推出，不能由metadata自授权。普通保存一次、不selectLatest；比较快照不保存草稿。', 'sources':[at(workflow,'JSONObject debugBuild('),at(workflow,'requireDebugReceipt(')]},
 {'id':'same_run', 'kind':'源码核对', 'statement':'chat把固定workflowVersionId/engine/input/requestKey/timeout交现有RunService.create；相同key固定请求返回原Run，改变权威请求409。chatId和regen仅校验原兼容输入，不是workflowVersionId/RunId/发布App绑定，也不因客户端断线新生成key。', 'sources':[at(bridge,'var run=runs.create('),at(run,'var existing=runs.byRequest(actor,key)')]},
 {'id':'transaction_scope', 'kind':'源码核对', 'statement':'Bridge事务先校验回执并持有SPACE→WF→真实Agent，再boundRun取Run锁，reply再复用CP锁/CAS。page的CP读取无需另制造状态；原CP写入同样先锁Run。后台发送和等待在短读取事务之后。', 'sources':[at(bridge,'var debug=workflows.requireDebugReceipt'),at(bridge,'var run=boundRun(attachment.runId()'),at(checkpoint,'var run=userRun(runId,"paiflow:run:resume")')]},
 {'id':'resume_abort', 'kind':'源码核对', 'statement':'resume固定run/receipt/checkpoint/revision/key，复用唯一CheckpointService.reply。只把原abort缺content收窄成空字符串；显式null及非法eventType仍拒。JSON ACK不是Run停止或成功。原QA abort最终23802/FAILED语义不被CP RESUMED冒充取消。', 'sources':[at(bridge,'!body.containsKey("content") && "abort"'),at(bridge,'var response=checkpoints.reply('),at(checkpoint,'if(value.getReplyKey()!=null)')]},
 {'id':'actual_cursor', 'kind':'源码核对', 'statement':'Last-Event-ID须runId:十进制实际序号，跨Run前缀和超实际watermark拒绝。锁内取得watermark，SQL只读after<seq<=through的32行，并检查实际SQL行seq连续且JSON.seq相等；辅助事件只推进scan，不造陌生cursor JSON。', 'sources':[at(bridge,'public static long cursor('),at(bridge,'row.seq()!=scanned+1'),at(sources[11],'<select id="eventsThrough"')]},
 {'id':'session_refresh', 'kind':'源码＋实际Stream内存端口探针', 'statement':'每batch用原TokenService重新读取JWT/Redis session的设计，核同actor/session且未到期；任务内建立该次SecurityContext/私有RequestAttributes，finally恢复。Bridge权限会再走IdentityMapping当前账号/实时菜单及ResourceAccess实时成员检查。探针仅模拟端口失效，不宣称真实Redis/logout联机。', 'sources':[at(stream,'public <T>T withSession('),at(sources[9],'SysUser user = users.selectUserById'),at(sources[8],'if(actor!=requirePermission(permission))')]},
 {'id':'permission_label', 'kind':'实际私有scope＋原ss探针', 'statement':'原PermissionService确会写PermissionContextHolder.request标签。候选scope只提供本次请求属性、拒HTTP session；实际两个actor隔离探针表明伪写权限标签不能授予权限、原上下文恢复且scope清空。', 'sources':[at(stream,'private static final class PermissionRequestScope'),at(sources[13],'PermissionContextHolder.setContext(permission)')]},
 {'id':'capacity_disconnect', 'kind':'实际Stream源码＋内存端口探针', 'statement':'每actor4/总64，reservation在创建Run或回复CP前取得，AtomicBoolean只release一次。实际验证上限拒绝、schedule/IO失败释放、会话失效不再page、一次inFlight、early disconnect仅交首帧且页after不提前推进。不是Servlet实际断线压测。', 'sources':[at(stream,'new Semaphore(64)'),at(stream,'if(next>4)'),at(stream,'released.compareAndSet(false,true)'),at(stream,'after=page.scannedThrough()')]},
 {'id':'thin_eof', 'kind':'源码＋实际Stream内存端口探针', 'statement':'实际有效WAITING及已扫至watermark的官方terminal才主动EOF；流关闭不改Run或CP，不制造Provider停止。内存探针WAITING只交给定原帧，空terminal零send仍释放资源。正式flow stop只由Codec读取中心run.*终态事件投影。', 'sources':[at(stream,'if(page.waiting() || page.terminal())'),at(bridge,'boolean terminal=TERMINAL.contains'),at(sources[3],'run.completed')]},
 {'id':'frontend_binding', 'kind':'只读当前源码核对，执行归API独立证据', 'statement':'原React依旧使用原消费者；helper核双响应头与同Run游标、固定body/key续读、EOF不是终态、onerror throw阻止原库隐式重发。comparison cancel需同version/promptDebugger，已通知API并在当前候选收敛；不声称页面验收。', 'sources':[at(sources[15],'cancelBody()'),at(sources[16],'onerror(error) { throw error; }')]},
]
proofs=[]
for name in ['原调试桥Jackson嵌套对象独立反例.json','原调试桥私有权限作用域独立核对.json','原调试流生命周期独立检查.json']:
    path=CHECKS/name
    proofs.append({'path':str(path.relative_to(ROOT)), 'sha256':sha(path), 'result':json.loads(path.read_text(encoding='utf-8'))})
old=CHECKS/'调试桥Jackson反例源码/LegacyDebugBridgeService.java'
after={p:sha(ROOT/p) for p in sources}
result={'recordType':'只读源码审查与私有隔离检查证据，不是第二套状态或设计',
        'generatedAtUtc':datetime.now(timezone.utc).isoformat(),
        'scope':'只读新调试桥与原设施，实际探针只写checks/OS Temp；0Provider/服务/HTTP/DB/Redis调用，0Maven/共享target写。',
        'facts':facts,'sourceBeforeSha256':before,'sourceAfterSha256':after,'generationWindowChanged':[p for p in before if before[p]!=after[p]],
        'preservedOriginalBridge':{'path':str(old.relative_to(ROOT)),'sha256':sha(old)},'proofs':proofs,
        'remainingBoundaries':['读取/授权粒度是每batch最多32行；emitter逐帧发送在该批事务和withSession之后，不是逐帧重新核JWT/菜单。尚无真实HTTP慢发送中途撤权场景实测。',
                               '64/actor4是活动连接reservation限制；共享IO/scheduler有各自实际线程与队列限制，探针不是负载或跨进程容量测试。',
                               '本审查未编译或执行真实Bridge/Workflow/Run/CP事务；父任务Maven结果、H2链及API消费者检查必须按各自实际证据独立记录。',
                               '无真实App/发布/接口App、Worker部署、服务启动、Provider调用或数据库迁移声明。'],
        'confirmedOpenDefects':[]}
out=CHECKS/'原调试桥与薄流门独立源审查.json'
if out.exists():raise RuntimeError('已有证据拒覆盖：'+str(out))
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':str(out),'sha256':sha(out),'facts':len(facts),'proofs':len(proofs),'sourceCount':len(sources),'generationWindowChanged':result['generationWindowChanged']},ensure_ascii=False))
assert not result['generationWindowChanged']
