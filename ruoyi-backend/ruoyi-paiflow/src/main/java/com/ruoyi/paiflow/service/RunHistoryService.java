package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.RunHistoryMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.ModelOperationMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 执行历史与前端显示历史分别保存；正式身份/会话范围唯一来自中心租约和上下文。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name={"enabled","runtime-state.enabled"},havingValue="true")
public class RunHistoryService {
    private final RunHistoryMapper histories;
    private final RunMapper runs;
    private final RunLeaseService leases;
    private final ResourceAccessService access;
    private org.springframework.beans.factory.ObjectProvider<CompletedOutputSources> javaSources;
    private SpaceMapper spaces;
    private ModelOperationMapper models;
    @org.springframework.beans.factory.annotation.Autowired
    public void useJavaSources(org.springframework.beans.factory.ObjectProvider<CompletedOutputSources> source,SpaceMapper spaces,ModelOperationMapper models) {
        this.javaSources=source;this.spaces=spaces;this.models=models;
    }
    public RunHistoryService(RunHistoryMapper histories,RunMapper runs,RunLeaseService leases,ResourceAccessService access) {
        this.histories=histories;this.runs=runs;this.leases=leases;this.access=access;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void bindDebug(String id,String flowId,String chatId) {
        long actor=access.requirePermission("paiflow:run:create");var run=runs.lock(id);
        if(run==null || run.getOwnerUserId()!=actor)throw new ServiceException("调试历史运行不属于当前用户",404);
        access.requireSpace(run.getSpaceId(),"paiflow:run:create",3,false);
        if(flowId==null || flowId.isBlank() || flowId.length()>128 || chatId==null || chatId.length()>128)
            throw new ServiceException("调试历史作用域无效",400);
        String app="debug:"+run.getWorkflowId();var old=histories.context(id);
        if(old!=null) {
            requireContext(run,old);
            if(!Objects.equals(old.get("flowId"),flowId) || !Objects.equals(old.get("appId"),app) || !Objects.equals(old.get("chatId"),chatId))
                throw new ServiceException("同一运行不能更换历史会话",409);
        } else if(histories.bind(id,run.getSpaceId(),run.getWorkflowId(),actor,flowId,app,chatId)!=1)
            throw new ServiceException("中心历史作用域保存失败",500);
        if(access.requirePermission("paiflow:run:create")!=actor)throw new ServiceException("历史绑定主体已变化",403);
    }
    /** 只在现有租约校验之后投影；未绑定运行不会伪造正式App。 */
    public JSONObject executionContext(RunRecord run) {
        var context=histories.context(run.getId());if(context==null)return null;requireContext(run,context);
        return JSONObject.of("flowId",context.get("flowId"),"userId",IdentityMappingService.legacyUid(run.getOwnerUserId()),
                "appId",context.get("appId"),"chatId",context.get("chatId"));
    }
    private static void requireContext(RunRecord run,Map<String,Object> c) {
        if(!Objects.equals(c.get("runId"),run.getId()) || number(c.get("spaceId"))!=run.getSpaceId()
                || number(c.get("workflowId"))!=run.getWorkflowId() || number(c.get("ownerUserId"))!=run.getOwnerUserId())
            throw new ServiceException("历史上下文与中心运行不一致",409);
    }
    private static long number(Object raw) {
        if(!(raw instanceof Number n))throw new ServiceException("中心历史关联无效",409);return n.longValue();
    }
    private RunRecord active(String id,WorkerIdentityService.Principal worker,JSONObject body,boolean begun) {
        var run=leases.requireActiveLease(id,worker,ModelOperationJson.text(body.get("leaseId"),64,"leaseId"),RunLeaseService.attempt(body));
        if(!"RUNNING".equals(run.getState()))throw new ServiceException("当前运行不能读写历史",410);
        if(begun)RunLeaseService.requireExecutionBegun(run);
        var c=histories.context(id);if(c==null)throw new ServiceException("中心历史作用域尚未绑定",503);requireContext(run,c);return run;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject append(String id,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","nodeId","question","answer","idempotencyKey"),Set.of("iterationId"));
        RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);var run=pythonActive(id,worker,body,true);
        if(body.get("iterationId")!=null)throw new ServiceException("迭代历史关联尚未接通",503);
        String node=ModelOperationJson.text(body.get("nodeId"),255,"nodeId");
        var fixed=pythonNode(run,node);
        if(!PythonModelHistorySemantics.appendsLegacy(fixed))throw new ServiceException("固定节点未启用原Python历史追加",403);
        var question=message(body.get("question"),"user");var answer=message(body.get("answer"),"assistant");
        String key=ModelOperationJson.sha(ModelOperationJson.text(body.get("idempotencyKey"),4096,"idempotencyKey"));
        var facts=JSONObject.of("nodeId",node,"question",question,"answer",answer);String hash=RunJsonBoundary.hashFull(facts);
        var operationIds=new LinkedHashSet<String>();long readBytes=pythonAuxiliaryCost(run,addCost(0,histories.pythonRunCost(id),1000),operationIds);
        for(String operation:operationIds)readBytes=addCost(readBytes,histories.pythonOperationCost(operation),ModelOperationJson.MAX_FRAMES);
        var inputs=pythonInputs(run,fixed);var completed=source().pythonHistory(run,fixed,inputs);
        if(!ToolSchema.same(question,completed.get("question")) || !ToolSchema.same(answer,completed.get("answer")))
            throw new ServiceException("Python历史正文偏离同次原模型成功事实",403);
        var evidence=pythonEvidence(run,fixed,inputs,hash,completed.getString("operationId"));
        var old=histories.byRequest(id,run.getAttempt(),key);
        if(old!=null) {
            if(!Objects.equals(old.get("requestHash"),hash))throw new ServiceException("历史幂等键已用于不同正文",409);
            requirePythonEvidence(histories.pythonEvidence(id,run.getAttempt(),key),evidence);
            return JSONObject.of("stored",true,"revision",number(old.get("revision")));
        }
        if(histories.pythonEvidenceByOperation(completed.getString("operationId"))!=null)
            throw new ServiceException("同一原模型操作已有历史追加，不能更换幂等键",409);
        if(histories.append(id,run.getAttempt(),node,key,hash,RunJsonBoundary.canonicalFull(question),RunJsonBoundary.canonicalFull(answer))!=1)
            throw new ServiceException("执行历史保存失败",500);
        try {
            if(histories.recordPythonEvidence(id,run.getAttempt(),key,completed.getString("operationId"),RunJsonBoundary.canonicalFull(evidence),RunJsonBoundary.hashFull(evidence))!=1)
                throw new ServiceException("Python历史来源证据保存失败",500);
        }catch(org.springframework.dao.DuplicateKeyException conflict){throw new ServiceException("原模型操作的历史来源已由其它幂等请求保存",409);}
        return JSONObject.of("stored",true,"revision",1);
    }
    private static JSONObject message(Object raw,String role) {
        if(!(raw instanceof JSONObject m) || !m.keySet().equals(Set.of("role","content")) || !role.equals(m.get("role")) || !(m.get("content") instanceof String))
            throw new ServiceException("执行历史须为原文本角色和内容",400);
        return m;
    }
    private static final int PYTHON_ACK_BYTES=1800*1024;
    private static final long PYTHON_SOURCE_BYTES=128L*1024*1024;
    private static final Set<String> PYTHON_SNAPSHOT_KEYS=Set.of("runId","leaseId","attempt","workflowVersionId","dslHash","spaceId","ownerUserId","workflowId","flowId","userId","appId","chatId","inputHash","history","historyV2");
    private CompletedOutputSources source(){var value=javaSources==null?null:javaSources.getIfAvailable();if(value==null)throw new ServiceException("中心历史完成来源未装配",503);return value;}
    private RunRecord pythonActive(String id,WorkerIdentityService.Principal worker,JSONObject body,boolean begun) {
        if(spaces==null)throw new ServiceException("Python历史空间端口未装配",503);
        var before=runs.find(id);if(before==null || spaces.lock(before.getSpaceId())==null)throw new ServiceException("Python历史运行或空间不存在",404);
        // 原租约方法锁Run，空间锁必须在此前取得；不信任Worker声明任何会话或用户。
        var run=active(id,worker,body,begun);
        if(!Objects.equals(before.getSpaceId(),run.getSpaceId()) || !"python".equals(run.getEngineProfile()))throw new ServiceException("Python历史不属于当前固定引擎",403);
        return run;
    }
    private static JSONObject pythonNode(RunRecord run,String id) {
        var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        // 持久任务不含lease/attempt，这两项由claim/verify动态投影；真实身份取Run及租约服务。
        if(!"python".equals(run.getEngineProfile()) || !"python".equals(assignment.get("engineProfile")) || !Objects.equals(run.getId(),assignment.get("runId")))
            throw new ServiceException("Python历史与固定任务引擎不一致",403);
        var node=DataWorkerOperationService.find(assignment.getJSONObject("dsl"),id);
        if(node==null || assignment.getJSONObject("dsl").getJSONArray("nodes").stream().noneMatch(raw->raw instanceof JSONObject n&&Objects.equals(id,n.get("id"))))
            throw new ServiceException("Python历史节点不属于顶层固定版本",403);
        PythonModelHistorySemantics.requireLegacy(node);return node;
    }
    private LinkedHashMap<String,Object> pythonInputs(RunRecord run,JSONObject node) {
        if(run.getLastEventSeq()==null)throw new ServiceException("Python历史完成事件水位缺失",403);
        var accepted=new JSONArray();var events=runs.eventsThrough(run.getId(),0,run.getLastEventSeq(),1001);
        if(events.size()>1000)throw new ServiceException("Python历史来源事件超过预算",413);
        for(var event:events){var value=ModelMessageSources.sourceObject(event.eventJson());
            if(value.getLongValue("seq")!=event.seq() || !Objects.equals(value.get("runId"),run.getId()))throw new ServiceException("Python历史来源偏离中心事件",403);
            if(Objects.equals(value.getInteger("attempt"),run.getAttempt()))accepted.add(value);
        }
        return source().values(run,ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("dsl"),node,accepted);
    }
    private JSONObject pythonBinding(RunRecord run,JSONArray history) {
        var context=histories.context(run.getId());if(context==null)throw new ServiceException("Python历史作用域缺失",503);requireContext(run,context);
        var task=ModelMessageSources.sourceObject(run.getAssignmentJson());
        return JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"workflowVersionId",run.getWorkflowVersionId(),"dslHash",task.get("dslHash"),
                "spaceId",run.getSpaceId(),"ownerUserId",run.getOwnerUserId(),"workflowId",run.getWorkflowId(),"flowId",context.get("flowId"),"userId",IdentityMappingService.legacyUid(run.getOwnerUserId()),
                "appId",context.get("appId"),"chatId",context.get("chatId"),"inputHash",RunJsonBoundary.hashFull(task.getJSONObject("input")),"history",history,"historyV2",new JSONArray());
    }
    private JSONObject checkedPythonSnapshot(RunRecord run,Map<String,Object> row) {
        if(row==null)throw new ServiceException("Python运行尚未冻结可信历史窗口",409);
        if(!Objects.equals(row.get("runId"),run.getId()) || !Objects.equals(row.get("leaseId"),run.getLeaseId()) || number(row.get("attempt"))!=run.getAttempt()
                || !(row.get("historyRef") instanceof String ref) || ref.isBlank())throw new ServiceException("Python历史快照scope不一致",409);
        var snapshot=ModelMessageSources.sourceObject((String)row.get("snapshotJson"));
        if(!snapshot.keySet().equals(PYTHON_SNAPSHOT_KEYS) || !(snapshot.get("history") instanceof JSONArray history) || !(snapshot.get("historyV2") instanceof JSONArray v2) || !v2.isEmpty()
                || !Objects.equals(row.get("snapshotHash"),RunJsonBoundary.hashFull(snapshot)) || !ToolSchema.same(snapshot,pythonBinding(run,history)))
            throw new ServiceException("Python历史快照哈希或固定输入不一致",409);
        var ids=new HashSet<String>();for(Object raw:history){if(!(raw instanceof JSONObject item) || !item.keySet().equals(Set.of("nodeID","chat_history")) || !(item.get("nodeID") instanceof String id) || !ids.add(id)
                    || !(item.get("chat_history") instanceof JSONArray messages) || messages.size()>20 || messages.size()%2!=0)throw new ServiceException("Python历史快照原窗口无效",409);
            if(!PythonModelHistorySemantics.readsLegacy(pythonNode(run,id)))throw new ServiceException("Python历史快照包含未启用读取的节点",409);
            for(int i=0;i<messages.size();i++)message(messages.get(i),i%2==0?"user":"assistant");}
        pythonAck(row,snapshot);return snapshot;
    }
    private static JSONObject pythonAck(Map<String,Object> row,JSONObject snapshot) {
        var ack=JSONObject.of("history",snapshot.get("history"),"historyV2",snapshot.get("historyV2"),"historyRef",row.get("historyRef"),"historyHash",row.get("snapshotHash"),"snapshot",snapshot);
        RunJsonBoundary.check(ack,PYTHON_ACK_BYTES);return ModelOperationJson.copy(ack);
    }
    /** 私有准入/完成事实使用同一冻结快照；返回值不接受Worker覆盖。 */
    public JSONObject pythonSnapshot(RunRecord run,JSONObject node) {
        var fixed=pythonNode(run,node.getString("id"));if(!ToolSchema.same(fixed,node))throw new ServiceException("Python历史不是固定节点",403);
        var row=histories.pythonSnapshot(run.getId(),run.getAttempt());var snapshot=checkedPythonSnapshot(run,row);var messages=new JSONArray();
        for(Object raw:snapshot.getJSONArray("history")){var item=(JSONObject)raw;if(Objects.equals(node.get("id"),item.get("nodeID")))messages=item.getJSONArray("chat_history");}
        return ModelOperationJson.copy(JSONObject.of("historyRef",row.get("historyRef"),"historyHash",row.get("snapshotHash"),"history",messages,"historyV2",new JSONArray()));
    }
    private JSONObject pythonEvidence(RunRecord run,JSONObject node,Map<String,Object> inputs,String requestHash,String operation) {
        var proof=pythonSnapshot(run,node);var task=ModelMessageSources.sourceObject(run.getAssignmentJson());
        return JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"workflowVersionId",run.getWorkflowVersionId(),"dslHash",task.get("dslHash"),
                "nodeId",node.get("id"),"nodeHash",RunJsonBoundary.hashFull(node),"inputHash",RunJsonBoundary.hashFull(task.getJSONObject("input")),
                "verifiedInputsHash",RunJsonBoundary.hashFull(new JSONObject(inputs)),"requestHash",requestHash,"modelOperationId",operation,
                "historyRef",proof.get("historyRef"),"historyHash",proof.get("historyHash"));
    }
    private static JSONObject checkedEvidence(Map<String,Object> row) {
        if(row==null)throw new ServiceException("原Python历史窗口含未核实来源的旧轮次",503);
        if(!(row.get("evidenceJson") instanceof String text) || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)
            throw new ServiceException("Python历史来源证据超过固定关联预算",413);
        var value=ModelMessageSources.sourceObject(text);
        if(!Objects.equals(row.get("evidenceHash"),RunJsonBoundary.hashFull(value)) || !Objects.equals(row.get("modelOperationId"),value.get("modelOperationId")))throw new ServiceException("Python历史来源证据已损坏",409);
        return value;
    }
    private static void requirePythonEvidence(Map<String,Object> row,JSONObject expected){if(!ToolSchema.same(checkedEvidence(row),expected))throw new ServiceException("Python历史来源证据偏离实际原调用",403);}
    private static long addCost(long used,Map<String,Object> cost,int countLimit) {
        if(cost==null || number(cost.get("count"))>countLimit || number(cost.get("count"))<0 || number(cost.get("bytes"))<0)throw new ServiceException("Python历史来源读取预算或对象无效",413);
        long next;try{next=Math.addExact(used,number(cost.get("bytes")));}catch(ArithmeticException e){throw new ServiceException("Python历史来源累计预算溢出",413);}
        if(next>PYTHON_SOURCE_BYTES)throw new ServiceException("Python历史来源累计超过128MiB校核预算",413);return next;
    }
    private long pythonAuxiliaryCost(RunRecord run,long bytes,Set<String> operations) {
        var extra=histories.pythonOperations(run.getId());if(extra.size()>1000)throw new ServiceException("Python历史原模型操作超过预算",413);operations.addAll(extra);
        var types=new HashSet<String>();for(Object raw:ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("dsl").getJSONArray("nodes"))types.add(ModelNodeSemantics.type((JSONObject)raw));
        if(types.contains("plugin"))bytes=addCost(bytes,histories.pythonToolCost(run.getId()),1000);
        if(types.contains("database"))bytes=addCost(bytes,histories.pythonDataCost(run.getId()),1000);
        if(types.contains("node-variable"))bytes=addCost(bytes,histories.pythonVariableCost(run.getId()),1024);
        return bytes;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject readPython(String id,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt"),Set.of());RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        var run=pythonActive(id,worker,body,false);var prior=histories.pythonSnapshot(id,run.getAttempt());
        if(prior!=null)return pythonAck(prior,checkedPythonSnapshot(run,prior));
        var allowed=new ArrayList<String>();var task=ModelMessageSources.sourceObject(run.getAssignmentJson());
        for(Object raw:task.getJSONObject("dsl").getJSONArray("nodes")){var node=(JSONObject)raw;if(PythonModelHistorySemantics.legacy(node)){pythonNode(run,node.getString("id"));if(PythonModelHistorySemantics.readsLegacy(node))allowed.add(node.getString("id"));}}
        var context=histories.context(id);var rows=allowed.isEmpty()?List.<Map<String,Object>>of():histories.pythonRecent(run.getSpaceId(),run.getWorkflowId(),run.getOwnerUserId(),(String)context.get("appId"),(String)context.get("chatId"),allowed);
        if(rows.size()>1000)throw new ServiceException("Python历史窗口超过1000条预算",413);
        var proofs=new HashMap<Long,JSONObject>();var runIds=new LinkedHashSet<String>();var operations=new LinkedHashSet<String>();long bytes=0;
        for(var row:rows){long rowId=number(row.get("id"));var evidence=histories.pythonEvidence((String)row.get("runId"),(int)number(row.get("attempt")),(String)row.get("requestKeyHash"));var proof=checkedEvidence(evidence);proofs.put(rowId,proof);
            runIds.add((String)row.get("runId"));operations.add(proof.getString("modelOperationId"));bytes=addCost(bytes,Map.of("bytes",number(row.get("questionBytes"))+number(row.get("answerBytes"))+((String)evidence.get("evidenceJson")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"count",0),0);}
        var verifiedRuns=new HashMap<String,RunRecord>();
        for(String old:runIds){bytes=addCost(bytes,histories.pythonRunCost(old),1000);
            var record=runs.find(old);if(record==null)throw new ServiceException("Python历史原运行缺失",403);verifiedRuns.put(old,record);
            // 全原Run模型操作覆盖中间模型来源；不能只计最终LLM自身的两帧。
            bytes=pythonAuxiliaryCost(record,bytes,operations);
        }
        for(String operation:operations)bytes=addCost(bytes,histories.pythonOperationCost(operation),ModelOperationJson.MAX_FRAMES);
        var inputsCache=new HashMap<String,LinkedHashMap<String,Object>>();var completedCache=new HashMap<String,JSONObject>();
        Map<String,List<JSONObject>> grouped=new LinkedHashMap<>();
        for(var row:rows){String oldId=(String)row.get("runId");var old=verifiedRuns.computeIfAbsent(oldId,key->runs.find(key));
            if(old==null || !Objects.equals(old.getSpaceId(),run.getSpaceId()) || !Objects.equals(old.getWorkflowId(),run.getWorkflowId()) || !Objects.equals(old.getOwnerUserId(),run.getOwnerUserId()) || number(row.get("attempt"))!=old.getAttempt())throw new ServiceException("Python历史原运行scope不一致",403);
            var oldContext=histories.context(oldId);if(oldContext==null)throw new ServiceException("Python历史原上下文缺失",409);requireContext(old,oldContext);if(!Objects.equals(oldContext.get("appId"),context.get("appId")) || !Objects.equals(oldContext.get("chatId"),context.get("chatId")))throw new ServiceException("Python历史原会话不一致",403);
            var node=pythonNode(old,(String)row.get("nodeId"));String cacheKey=oldId+":"+node.getString("id");
            var inputs=inputsCache.computeIfAbsent(cacheKey,key->pythonInputs(old,node));var completed=completedCache.computeIfAbsent(cacheKey,key->source().pythonHistory(old,node,inputs));
            var item=histories.pythonItem(number(row.get("id")));if(item==null)throw new ServiceException("Python历史正文缺失",409);
            var question=message(ModelMessageSources.sourceObject((String)item.get("questionJson")),"user");var answer=message(ModelMessageSources.sourceObject((String)item.get("answerJson")),"assistant");
            String requestHash=RunJsonBoundary.hashFull(JSONObject.of("nodeId",node.get("id"),"question",question,"answer",answer));
            if(!Objects.equals(requestHash,row.get("requestHash")) || !ToolSchema.same(question,completed.get("question")) || !ToolSchema.same(answer,completed.get("answer")) || !ToolSchema.same(proofs.get(number(row.get("id"))),pythonEvidence(old,node,inputs,requestHash,completed.getString("operationId"))))throw new ServiceException("Python历史正文偏离旧运行真实模型事实",403);
            var messages=grouped.computeIfAbsent(node.getString("id"),key->new ArrayList<>());messages.add(answer);messages.add(question);
        }
        var history=new JSONArray();for(var entry:grouped.entrySet()){Collections.reverse(entry.getValue());history.add(JSONObject.of("nodeID",entry.getKey(),"chat_history",new JSONArray(entry.getValue())));}
        var snapshot=pythonBinding(run,history);String ref=UUID.randomUUID().toString(),hash=RunJsonBoundary.hashFull(snapshot);
        var row=Map.<String,Object>of("historyRef",ref,"snapshotHash",hash);var ack=pythonAck(row,snapshot);
        if(histories.freezePython(ref,id,run.getLeaseId(),run.getAttempt(),RunJsonBoundary.canonicalFull(snapshot),hash)!=1)throw new ServiceException("Python历史冻结保存失败",500);
        return ack;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject read(String id,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt"),Set.of());RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        var run=active(id,worker,body,false);var c=histories.context(id);
        var allowed=new ArrayList<String>();
        for(Object raw:JSON.parseObject(run.getAssignmentJson()).getJSONObject("dsl").getJSONArray("nodes")) {
            if(raw instanceof JSONObject node && node.get("id") instanceof String nodeId &&
                    (nodeId.startsWith("spark-llm::") || nodeId.startsWith("decision-making::")) &&
                    node.getJSONObject("data")!=null && node.getJSONObject("data").getJSONObject("nodeParam")!=null &&
                    node.getJSONObject("data").getJSONObject("nodeParam").getBooleanValue("enableChatHistory"))allowed.add(nodeId);
        }
        // 先限定当前不可变DSL，再按节点取窗口；已删除节点不能占用LIMIT或导致Worker拒绝新版本。
        var rows=allowed.isEmpty()?List.<Map<String,Object>>of():histories.recent(run.getSpaceId(),run.getWorkflowId(),run.getOwnerUserId(),
                (String)c.get("appId"),(String)c.get("chatId"),allowed);
        if(rows.size()>1000)throw new ServiceException("历史读取超过当前窗口预算",413);
        Map<String,List<JSONObject>> grouped=new LinkedHashMap<>();
        for(var row:rows) {
            String node=(String)row.get("nodeId");var values=grouped.computeIfAbsent(node,k->new ArrayList<>());
            // 原每节点最多十轮，由新到旧选取，再统一恢复时间顺序。
            if(values.size()>=20)continue;
            values.add(message(JSON.parseObject((String)row.get("answerJson")),"assistant"));
            values.add(message(JSON.parseObject((String)row.get("questionJson")),"user"));
        }
        var history=new JSONArray();for(var entry:grouped.entrySet()) {
            Collections.reverse(entry.getValue());history.add(JSONObject.of("nodeID",entry.getKey(),"chat_history",new JSONArray(entry.getValue())));
        }
        // 原V2取请求附带HistoryItem；未有正式消息/附件绑定时不从显示记录猜造V2。
        var response=JSONObject.of("history",history,"historyV2",new JSONArray());RunJsonBoundary.check(response,RunProtocolLimits.ASSIGNMENT_BYTES);return response;
    }

    /**
     * 原Java节点先读旧窗口，再建立当前SYSTEM/USER半轮。Worker只声明当前固定节点，
     * 消息、身份及会话均由中心重建；同一节点重放使用原快照，不再次挤出旧轮。
     */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject openJava(String id,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","nodeId"),Set.of());
        RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        if(spaces==null || javaSources==null)throw new ServiceException("Java历史来源端口尚未装配",503);
        var before=runs.find(id);if(before==null)throw new ServiceException("运行不存在",404);
        // 与模型调用保持空间→Run的锁序；同空间并发开轮取得一个确定的旧窗口。
        if(spaces.lock(before.getSpaceId())==null)throw new ServiceException("历史空间不存在",403);
        var run=active(id,worker,body,true);
        if(!Objects.equals(before.getSpaceId(),run.getSpaceId()) || !"java".equals(run.getEngineProfile()))
            throw new ServiceException("Java历史不属于当前固定引擎",403);
        String nodeId=ModelOperationJson.text(body.get("nodeId"),255,"nodeId");
        var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        var node=javaNode(assignment,nodeId);var context=histories.context(id);requireContext(run,context);
        var existing=histories.javaRound(id,run.getAttempt(),nodeId);
        if(existing!=null)return javaResource(run,nodeId,existing,context);
        Integer rounds=JavaModelSemantics.historyLimit(node);
        if(rounds==null)throw new ServiceException("Java模型启用历史时轮数不能为null",400);
        var old=new JSONArray();
        var window=histories.javaRecent(run.getSpaceId(),run.getWorkflowId(),run.getOwnerUserId(),
                (String)context.get("appId"),(String)context.get("chatId"),nodeId);
        if(window.size()>10)throw new ServiceException("Java历史窗口超过原十轮范围",409);
        Collections.reverse(window);
        for(var row:window) {
            var item=javaItem(row);
            if(!Objects.equals(item.get("chatId"),context.get("chatId")) || !Objects.equals(item.get("nodeId"),nodeId))
                throw new ServiceException("Java历史轮次偏离会话或节点",409);
            old.add(item);
        }
        // 保留原int减法（包括Integer.MIN_VALUE溢出），非法subList不吞成空历史。
        int from=Math.max(0,old.size()-rounds);
        if(from>old.size())throw new ServiceException("Java原历史轮数使窗口索引无效",400);
        var selected=rounds==0?new JSONArray():new JSONArray(old.subList(from,old.size()));
        var source=javaSources.getIfAvailable();if(source==null)throw new ServiceException("Java可信输入来源未装配",503);
        var accepted=new JSONArray();
        var rows=runs.eventsThrough(id,0,run.getLastEventSeq(),1001);
        if(rows.size()>1000)throw new ServiceException("Java历史输入来源超过预算",413);
        for(var row:rows) {
            var event=ModelMessageSources.sourceObject(row.eventJson());
            if(event.getLongValue("seq")!=row.seq() || !Objects.equals(event.get("runId"),id))
                throw new ServiceException("Java历史输入来源偏离中心事件",403);
            if(Objects.equals(event.getInteger("attempt"),run.getAttempt()))accepted.add(event);
        }
        var inputs=source.values(run,assignment.getJSONObject("dsl"),node,accepted);
        var current=JavaModelSemantics.messages(node,inputs,new JSONArray());
        var messages=JavaModelSemantics.messages(node,inputs,selected);
        long timestamp=System.currentTimeMillis();var userInputs=new JSONArray();
        for(Object raw:current) {
            var message=(JSONObject)raw;userInputs.add(JSONObject.of("role",message.getString("role").toUpperCase(Locale.ROOT),
                    "content",message.get("content"),"timestamp",timestamp));
        }
        var item=JSONObject.of("chatId",context.get("chatId"),"nodeId",nodeId,"userInputs",userInputs,
                "llmThinking",new JSONArray(),"llmResponses",new JSONArray());
        var snapshot=javaBinding(run,nodeId,context);snapshot.put("history",selected);snapshot.put("messages",messages);
        String inputHash=ModelOperationJson.sha(ModelMessageSources.verifiedInputsJson(inputs));snapshot.put("inputHash",inputHash);
        RunJsonBoundary.check(snapshot,RunProtocolLimits.ASSIGNMENT_BYTES);RunJsonBoundary.check(item,RunProtocolLimits.RESULT_BYTES);
        if(histories.openJava(id,run.getLeaseId(),run.getAttempt(),nodeId,run.getWorkflowVersionId(),assignment.getString("dslHash"),inputHash,
                RunJsonBoundary.canonicalFull(snapshot),RunJsonBoundary.hashFull(snapshot),RunJsonBoundary.canonicalFull(item),RunJsonBoundary.hashFull(item))!=1)
            throw new ServiceException("Java历史开轮保存失败",500);
        return javaResource(run,nodeId,histories.javaRound(id,run.getAttempt(),nodeId),context);
    }

    private static JSONObject javaNode(JSONObject assignment,String nodeId) {
        if(!"java".equals(assignment.get("engineProfile")) || !DslCodec.V2.equals(assignment.getOrDefault("executionCodec",DslCodec.V2)))
            throw new ServiceException("Java历史固定版本无效",409);
        var node=DataWorkerOperationService.find(assignment.getJSONObject("dsl"),nodeId);
        if(node==null || !"spark-llm".equals(ModelNodeSemantics.type(node)))throw new ServiceException("Java历史节点不属于固定模型图",403);
        return node;
    }
    private static JSONObject javaBinding(RunRecord run,String node,Map<String,Object> context) {
        var task=ModelMessageSources.sourceObject(run.getAssignmentJson());
        return JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"nodeId",node,
                "engineProfile","java",
                "spaceId",run.getSpaceId(),"workflowId",run.getWorkflowId(),"ownerUserId",run.getOwnerUserId(),
                "workflowVersionId",run.getWorkflowVersionId(),"dslHash",task.get("dslHash"),
                "appId",context.get("appId"),"chatId",context.get("chatId"));
    }
    private static JSONObject javaItem(Map<String,Object> row) {
        var item=JSON.parseObject((String)row.get("itemJson"));
        if(item==null || !Objects.equals(RunJsonBoundary.hashFull(item),row.get("itemHash"))
                || !item.keySet().equals(Set.of("chatId","nodeId","userInputs","llmThinking","llmResponses"))
                || !(item.get("chatId") instanceof String) || !(item.get("nodeId") instanceof String))
            throw new ServiceException("Java历史轮次事实无效",409);
        for(String key:List.of("userInputs","llmThinking","llmResponses")) {
            if(!(item.get(key) instanceof JSONArray values))throw new ServiceException("Java历史消息列表无效",409);
            for(Object raw:values) {
                if(!(raw instanceof JSONObject m) || !m.keySet().equals(Set.of("role","content","timestamp"))
                        || !(m.get("content") instanceof String) || !(m.get("timestamp") instanceof Number)
                        || !(m.get("role") instanceof String role)
                        || !(key.equals("userInputs")?Set.of("SYSTEM","USER"):key.equals("llmThinking")?Set.of("THINKING"):Set.of("ASSISTANT")).contains(role))
                    throw new ServiceException("Java历史角色或文本无效",409);
            }
        }
        return item;
    }
    private JSONObject javaSnapshot(RunRecord run,String node,Map<String,Object> row,Map<String,Object> context) {
        if(row==null || !Objects.equals(row.get("runId"),run.getId()) || !Objects.equals(row.get("leaseId"),run.getLeaseId())
                || number(row.get("attempt"))!=run.getAttempt() || !Objects.equals(row.get("nodeId"),node)
                || number(row.get("workflowVersionId"))!=run.getWorkflowVersionId())
            throw new ServiceException("Java历史快照不属于当前运行租约",409);
        var snapshot=JSON.parseObject((String)row.get("snapshotJson"));
        if(snapshot==null || !Objects.equals(RunJsonBoundary.hashFull(snapshot),row.get("snapshotHash")))
            throw new ServiceException("Java历史快照hash不一致",409);
        for(var entry:javaBinding(run,node,context).entrySet())if(!ToolSchema.same(entry.getValue(),snapshot.get(entry.getKey())))
            throw new ServiceException("Java历史快照作用域已变化",409);
        if(!Objects.equals(row.get("dslHash"),snapshot.get("dslHash")) || !Objects.equals(row.get("inputHash"),snapshot.get("inputHash")))
            throw new ServiceException("Java历史固定输入或图hash不一致",409);
        javaItem(row);return snapshot;
    }
    private JSONObject javaResource(RunRecord run,String node,Map<String,Object> row,Map<String,Object> context) {
        var snapshot=javaSnapshot(run,node,row,context);
        var response=JSONObject.of("kind","conversation-history","nodeId",node,"runId",run.getId(),"leaseId",run.getLeaseId(),
                "attempt",run.getAttempt(),"chatId",context.get("chatId"),"historyRef",String.valueOf(row.get("id")),
                "historyHash",row.get("snapshotHash"),"history",snapshot.get("history"),"snapshot",snapshot);
        // 包含顶层历史、完整快照和Ajax信封的实际响应预算；初次超限回滚本轮入队。
        RunJsonBoundary.check(response,RunProtocolLimits.ASSIGNMENT_BYTES);return response;
    }
    /** 私有候选与完成来源读取已经冻结的事实，不再查询会话recent。 */
    boolean hasJavaRound(RunRecord run,String nodeId) {
        return histories.javaRound(run.getId(),run.getAttempt(),nodeId)!=null;
    }
    private Map<String,Object> checkedJavaRound(RunRecord run,String nodeId) {
        var context=histories.context(run.getId());if(context==null)throw new ServiceException("Java历史作用域缺失",503);
        requireContext(run,context);var row=histories.javaRound(run.getId(),run.getAttempt(),nodeId);
        javaSnapshot(run,nodeId,row,context);return row;
    }
    boolean isJavaRoundOpen(RunRecord run,String nodeId) {
        var row=checkedJavaRound(run,nodeId);
        if(!Set.of("OPEN","COMPLETED").contains(row.get("state")))throw new ServiceException("Java历史轮次状态无效",409);
        return "OPEN".equals(row.get("state"));
    }
    void requireCompletedJava(RunRecord run,String nodeId,String operationId) {
        var row=checkedJavaRound(run,nodeId);
        if(!"COMPLETED".equals(row.get("state")) || !Objects.equals(operationId,row.get("modelOperationId")))
            throw new ServiceException("Java历史尚未完成同次模型回填",403);
    }
    public JSONObject javaSnapshot(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs) {
        var context=histories.context(run.getId());if(context==null)throw new ServiceException("Java历史作用域缺失",503);
        requireContext(run,context);var fixed=javaNode(ModelMessageSources.sourceObject(run.getAssignmentJson()),node.getString("id"));
        if(!ToolSchema.same(fixed,node))throw new ServiceException("Java历史读取不是当前固定节点",403);
        var row=histories.javaRound(run.getId(),run.getAttempt(),node.getString("id"));
        var snapshot=javaSnapshot(run,node.getString("id"),row,context);
        if(!Objects.equals(row.get("inputHash"),ModelOperationJson.sha(ModelMessageSources.verifiedInputsJson(verifiedInputs)))
                || !ToolSchema.same(snapshot.get("messages"),JavaModelSemantics.messages(node,verifiedInputs,snapshot.getJSONArray("history"))))
            throw new ServiceException("Java历史快照与真实固定输入不一致",403);
        return JSONObject.of("historyRef",String.valueOf(row.get("id")),"historyHash",row.get("snapshotHash"),
                "history",snapshot.get("history"),"messages",snapshot.get("messages"));
    }

    /** 只读正式模型账本及全部原帧回填，不接受Worker声明答案；调用失败保留OPEN半轮。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void completeJava(String runId,String nodeId,String operationId) {
        if(spaces==null || models==null)throw new ServiceException("Java历史完成来源未装配",503);
        var before=runs.find(runId);if(before==null || spaces.lock(before.getSpaceId())==null)throw new ServiceException("历史运行或空间不存在",404);
        var run=runs.lock(runId);if(run==null || !Objects.equals(before.getSpaceId(),run.getSpaceId()) || !"java".equals(run.getEngineProfile()))
            throw new ServiceException("Java历史完成运行不一致",409);
        var context=histories.context(runId);if(context==null)throw new ServiceException("Java历史作用域缺失",503);requireContext(run,context);
        var node=javaNode(ModelMessageSources.sourceObject(run.getAssignmentJson()),nodeId);
        var row=histories.javaRound(runId,run.getAttempt(),nodeId);var snapshot=javaSnapshot(run,nodeId,row,context);
        var operation=models.lock(operationId);
        if(operation==null || !Objects.equals(operation.getRunId(),runId) || !Objects.equals(operation.getLeaseId(),run.getLeaseId())
                || !Objects.equals(operation.getAttempt(),run.getAttempt()) || !Objects.equals(operation.getNodeId(),nodeId)
                || operation.getIterationId()!=null || !Objects.equals(operation.getWorkflowVersionId(),run.getWorkflowVersionId())
                || !Objects.equals(operation.getSpaceId(),run.getSpaceId()) || !Objects.equals(operation.getOwnerUserId(),run.getOwnerUserId())
                || !Objects.equals(operation.getDslHash(),snapshot.get("dslHash")) || !"openai".equals(operation.getProvider())
                || !("COMPLETED".equals(operation.getState()) || "FAILED".equals(operation.getState()) && "provider".equals(operation.getFailureKind()))
                || operation.getFinishSeq()==null || operation.getLastSeq()==null || operation.getFinishSeq()<1 || operation.getLastSeq()>ModelOperationJson.MAX_FRAMES
                || operation.getFinishSeq()>operation.getLastSeq() || !Integer.valueOf(1).equals(operation.getStopConfirmed())
                || operation.getStopProofId()==null || !ToolSchema.same(JSON.parseObject(operation.getParametersJson()).get("messages"),snapshot.get("messages")))
            throw new ServiceException("Java历史缺少同次模型完整完成账本",403);
        var source=javaSources==null?null:javaSources.getIfAvailable();if(source==null)throw new ServiceException("Java历史原帧完成来源缺失",503);
        var accepted=new JSONArray();var currentEvents=runs.eventsThrough(runId,0,run.getLastEventSeq(),1001);
        if(currentEvents.size()>1000)throw new ServiceException("Java历史完成输入来源超过预算",413);
        for(var event:currentEvents) {
            var value=ModelMessageSources.sourceObject(event.eventJson());
            if(value.getLongValue("seq")!=event.seq() || !Objects.equals(value.get("runId"),runId))throw new ServiceException("Java历史完成来源偏离中心事件",403);
            if(Objects.equals(value.getInteger("attempt"),run.getAttempt()))accepted.add(value);
        }
        var inputs=source.values(run,ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("dsl"),node,accepted);
        var proof=javaSnapshot(run,node,inputs);
        var projection=source.javaModelHistory(run,node,inputs,operationId,proof);
        if("COMPLETED".equals(row.get("state"))) {
            if(!Objects.equals(row.get("modelOperationId"),operationId))throw new ServiceException("Java历史轮次已有不同模型操作",409);
            var item=javaItem(row);
            if(item.getJSONArray("llmResponses").size()!=1 || !Objects.equals(item.getJSONArray("llmResponses").getJSONObject(0).get("content"),projection.content())
                    || !ToolSchema.same(item.getJSONArray("llmThinking").stream().map(v->((JSONObject)v).get("content")).toList(),
                            projection.thinking().isBlank()?List.of():List.of(projection.thinking())))throw new ServiceException("Java历史回填偏离原模型帧",409);
            return;
        }
        var item=javaItem(row);long timestamp=System.currentTimeMillis();
        item.getJSONArray("llmResponses").add(JSONObject.of("role","ASSISTANT","content",projection.content(),"timestamp",timestamp));
        if(!projection.thinking().isBlank())item.getJSONArray("llmThinking").add(JSONObject.of("role","THINKING","content",projection.thinking(),"timestamp",timestamp));
        RunJsonBoundary.check(item,RunProtocolLimits.RESULT_BYTES);
        if(histories.completeJava(runId,run.getAttempt(),nodeId,number(row.get("revision")),operationId,
                RunJsonBoundary.canonicalFull(item),RunJsonBoundary.hashFull(item))!=1)throw new ServiceException("Java历史回填版本已变化",409);
    }
}
