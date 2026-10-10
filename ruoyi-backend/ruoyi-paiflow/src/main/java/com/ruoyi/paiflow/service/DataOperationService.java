package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.DataMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 同一运行租约事务内的中心数据Producer；固定表列授权+真实行变更+持久幂等回执。 */
@Service @ConditionalOnProperty(prefix="paiflow",name={"enabled","data.enabled"},havingValue="true")
public class DataOperationService {
    private final DataRuntimeCatalog catalog;private final DataResourceService rows;private final DataMapper mapper;
    public DataOperationService(DataRuntimeCatalog catalog,DataResourceService rows,DataMapper mapper){this.catalog=catalog;this.rows=rows;this.mapper=mapper;}
    /** 真实用户提交运行时核对全部数据节点；不触发任何行操作或建立新的业务资源。 */
    public Set<String> admit(long space,long owner,JSONObject resources,JSONObject execution,int env){DataResourceService.environment(env);var operations=new HashSet<String>();var nodes=execution.getJSONArray("nodes");if(nodes==null)throw DataResourceService.fail("固定执行图缺少节点数组",422);for(Object node:nodes)admitNode(space,owner,resources,node,env,operations);return Set.copyOf(operations);}
    private void admitNode(long space,long owner,JSONObject resources,Object value,int env,Set<String> operations){
        if(value instanceof JSONObject object){if(object.get("id") instanceof String id&&"database".equals(com.ruoyi.paiflow.domain.NodeCatalog.typeOf(id))){var data=object.getJSONObject("data");var p=data==null?null:data.getJSONObject("nodeParam");if(p==null)throw DataResourceService.fail("数据节点缺少固定参数",422);String resource=p.getString("dataResourceId");var descriptor=resources.getJSONObject(resource);catalog.resolvePinned(space,owner,resource,descriptor);int mode=Math.toIntExact(DataResourceService.idOrZero(p.get("mode")));var plan=mode==0?DataSqlService.parse(previewSql(p.getString("sql"))):null;int actual=plan==null?mode:plan.mode();if(actual<1||actual>4)throw DataResourceService.fail("数据节点模式无效",422);catalog.requireOwner(space,owner,actual!=3);String table=DataResourceService.identifier(plan==null?p.get("tableName"):plan.table());var grant=descriptor.getJSONObject("tables").getJSONObject(table);if(grant==null||!grant.getJSONArray("allowedModes").contains(mode)||!grant.getJSONArray("execEnvs").contains(env))throw DataResourceService.fail("数据节点逻辑表、模式或环境未获授权",403);var columns=new HashSet<String>();for(Object c:grant.getJSONArray("columns"))columns.add(String.valueOf(c));if(plan!=null){DataSqlService.validate(plan.where(),columns);columns(plan.assignments().keySet(),columns);for(var e:plan.assignments().values())DataSqlService.validate(e,columns);for(var projection:plan.projection())DataSqlService.validate(projection.getExpr(),columns);for(var order:plan.order())DataSqlService.validate(order.getExpr(),columns);}else{if(p.get("assignmentList") instanceof JSONArray values)for(Object c:values)columns(Set.of(String.valueOf(c)),columns);if(p.get("orderData") instanceof JSONArray order)for(Object c:order)columns(Set.of(((JSONObject)c).getString("fieldName")),columns);if(p.get("cases") instanceof JSONArray cases&&!cases.isEmpty())for(Object raw:cases.getJSONObject(0).getJSONArray("conditions"))columns(Set.of(((JSONObject)raw).getString("fieldName")),columns);}operations.add(actual==3?"data.query":"data.mutate");}}
    }
    /** 仅用于静态结构准入；真实请求会再次解析实际值，不将预览替代值用于执行。 */
    static String previewSql(String template){if(template==null)throw DataResourceService.fail("CUSTOM SQL为空",422);var match=java.util.regex.Pattern.compile("\\{\\{[\\p{L}\\p{N}_]+\\}\\}").matcher(template);var output=new StringBuilder();int prior=0;boolean quoted=false;while(match.find()){String segment=template.substring(prior,match.start());output.append(segment);for(int i=0;i<segment.length();i++)if(segment.charAt(i)=='\''){if(quoted&&i+1<segment.length()&&segment.charAt(i+1)=='\'')i++;else quoted=!quoted;}output.append(quoted?"value":"NULL");prior=match.end();}return output.append(template.substring(prior)).toString();}
    /** 调用入口先锁空间→运行并核对有效租约、固定节点/资源和副作用策略；该服务继续锁库→表。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,propagation=Propagation.MANDATORY)
    public JSONObject execute(RunRecord run,String resource,JSONObject descriptor,String kind,JSONObject payload,String requestKey){
        if(run==null || !"RUNNING".equals(run.getState()) || requestKey==null || requestKey.isBlank() || requestKey.length()>4096)throw DataResourceService.fail("数据运行请求无效",400);
        RunLeaseService.requireExecutionBegun(run);RunJsonBoundary.check(payload,RunProtocolLimits.INPUT_BYTES);
        var fixed=catalog.resolvePinned(run.getSpaceId(),run.getOwnerUserId(),resource,descriptor);
        int requested=payload.getIntValue("mode");DataSqlService.Plan plan=requested==0?DataSqlService.parse(payload.getString("sql")):null;int mode=plan==null?requested:plan.mode();boolean edit=mode!=3;
        if(!Objects.equals(kind,edit?"data.mutate":"data.query"))throw DataResourceService.fail("数据操作种类与真实SQL不一致",400);
        catalog.requireOwner(run.getSpaceId(),run.getOwnerUserId(),edit);
        var assignment=JSON.parseObject(run.getAssignmentJson());var effects=assignment.getJSONObject("effectPolicy");if(effects==null || effects.getJSONArray("allowedOperations")==null || !effects.getJSONArray("allowedOperations").contains(kind) || edit&&!"approved_write".equals(effects.getString("mode")))throw DataResourceService.fail("数据运行未获对应操作或副作用授权",403);
        int env=DataResourceService.environment(payload.get("execDev"));if(assignment.get("dataEnvironment")==null || env!=DataResourceService.environment(assignment.get("dataEnvironment")))throw DataResourceService.fail("数据环境与中心固定运行不一致",409);String tableName=DataResourceService.identifier(plan==null?payload.get("table"):plan.table());var grant=descriptor.getJSONObject("tables").getJSONObject(tableName);
        if(grant==null||!grant.getJSONArray("allowedModes").contains(requested)||!grant.getJSONArray("execEnvs").contains(env))throw DataResourceService.fail("固定版本未授权此表、操作或环境",403);
        String hash=RunJsonBoundary.hashFull(JSONObject.of("resource",resource,"kind",kind,"payload",payload)),key=DataRuntimeCatalog.sha(requestKey);var old=mapper.request(run.getId(),run.getAttempt(),key);if(old!=null){if(!hash.equals(old.get("requestHash")))throw DataResourceService.fail("数据幂等标识已用于不同请求",409);return JSON.parseObject(String.valueOf(old.get("responseJson")));}
        var db=mapper.lockDatabase(fixed.database().getId());var table=mapper.lockTable(DataResourceService.id(grant.get("tableId")));if(db==null||db.getDeleted()!=0||table==null||table.getDeleted()!=0||!Objects.equals(table.getDbId(),db.getId()))throw DataResourceService.fail("数据资源已删除",409);
        catalog.resolvePinned(run.getSpaceId(),run.getOwnerUserId(),resource,descriptor);var allowed=new HashSet<String>();for(Object column:grant.getJSONArray("columns"))allowed.add(String.valueOf(column));
        var result=plan==null?structured(table,env,mode,payload,allowed,run.getOwnerUserId()):custom(table,env,plan,allowed,run.getOwnerUserId());
        catalog.requireOwner(run.getSpaceId(),run.getOwnerUserId(),edit);var response=JSONObject.of("code",0,"message","success","data",JSONObject.of("exec_success",new JSONArray(result)));RunJsonBoundary.check(response,RunProtocolLimits.RESULT_BYTES);
        if(mapper.record(run.getId(),run.getAttempt(),key,hash,RunJsonBoundary.canonicalFull(response))!=1)throw DataResourceService.fail("数据回执保存失败",500);return response;
    }
    /** 旧通用回执没有节点事实时不重新归属；新请求及同节点重试才继续行操作。 */
    public void requireCompletionKey(RunRecord run,JSONObject node,String iterationId,String requestKey){
        requireCompletionScope(run,node);String key=DataRuntimeCatalog.sha(requestKey);
        var sources=mapper.completedSources(run.getId(),run.getLeaseId(),run.getAttempt(),node.getString("id"),iterationId==null?"":iterationId);
        if(sources!=null && (sources.size()>1 || sources.size()==1 && !Objects.equals(key,sources.get(0).get("requestKeyHash"))))throw DataResourceService.fail("当前数据节点已完成，不能换请求标识再次执行",409);
        var request=mapper.request(run.getId(),run.getAttempt(),key);var row=mapper.completion(run.getId(),run.getAttempt(),key);
        if(request==null){if(row!=null)throw DataResourceService.fail("数据节点完成事实缺少原操作",409);return;}
        if(row==null)throw DataResourceService.fail("旧数据回执没有节点来源，不能重新归属",409);
        var complete=ModelMessageSources.sourceObject(String.valueOf(row.get("completionJson")));
        if(complete==null || !Objects.equals(RunJsonBoundary.hashFull(complete),row.get("completionHash")) || !Objects.equals(run.getId(),complete.get("runId")) || !Objects.equals(run.getLeaseId(),complete.get("leaseId")) || !Objects.equals(run.getAttempt(),complete.getInteger("attempt")) || !Objects.equals(node.get("id"),complete.get("nodeId")) || !Objects.equals(iterationId,complete.get("iterationId")) || !Objects.equals(RunJsonBoundary.hashFull(node),complete.get("nodeHash")))throw DataResourceService.fail("数据回执不属于当前节点或租约",409);
    }
    /** 调用者仍持有空间/运行锁：节点来源与已保存的真实行操作回执一同提交。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,propagation=Propagation.MANDATORY)
    public void recordCompleted(RunRecord run,JSONObject node,String iterationId,String resource,JSONObject descriptor,String kind,JSONObject payload,String requestKey,JSONObject response){
        requireCompletionScope(run,node);
        String key=DataRuntimeCatalog.sha(requestKey),requestHash=RunJsonBoundary.hashFull(JSONObject.of("resource",resource,"kind",kind,"payload",payload));
        var receipt=mapper.request(run.getId(),run.getAttempt(),key);
        if(receipt==null || !Objects.equals(requestHash,receipt.get("requestHash")) || !sameResponse(response,receipt.get("responseJson")))throw DataResourceService.fail("数据完成来源缺少同事务真实操作回执",409);
        var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        var pinned=assignment.getJSONObject("resourceSnapshots").getJSONObject(resource);
        if(pinned==null || !Objects.equals(resource,node.getJSONObject("data").getJSONObject("nodeParam").get("dataResourceId")) || !RunJsonBoundary.canonicalFull(pinned).equals(RunJsonBoundary.canonicalFull(descriptor)))throw DataResourceService.fail("数据完成来源与固定资源不一致",409);
        var complete=JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"nodeId",node.getString("id"),"iterationId",iterationId,
            "resourceId",resource,"resourceVersion",descriptor.get("version"),"kind",kind,"env",DataResourceService.environment(payload.get("execDev")),
            "requestHash",requestHash,"responseJson",response,"responseHash",RunJsonBoundary.hashFull(response),"nodeHash",RunJsonBoundary.hashFull(node),"descriptorHash",RunJsonBoundary.hashFull(descriptor));
        if(!Objects.equals(complete.get("env"),DataResourceService.environment(assignment.get("dataEnvironment"))))throw DataResourceService.fail("数据完成来源环境变化",409);
        RunJsonBoundary.check(complete,RunProtocolLimits.RESULT_BYTES+RunProtocolLimits.CONTROL_BYTES);
        String hash=RunJsonBoundary.hashFull(complete);var previous=mapper.completion(run.getId(),run.getAttempt(),key);
        if(previous!=null){if(!Objects.equals(hash,previous.get("completionHash")) || !sameResponse(complete,previous.get("completionJson")))throw DataResourceService.fail("数据完成幂等标识已属于不同节点或租约",409);return;}
        var record=new HashMap<String,Object>();record.put("run",run.getId());record.put("lease",run.getLeaseId());record.put("attempt",run.getAttempt());record.put("node",node.getString("id"));record.put("iteration",iterationId==null?"":iterationId);record.put("key",key);record.put("json",complete.toJSONString(JSONWriter.Feature.WriteMapNullValue));record.put("hash",hash);
        if(mapper.recordCompletion(record)!=1)throw DataResourceService.fail("数据节点完成事实保存失败",500);
    }
    /** 原数据节点下游读取完成时刻的结果，不重新查询后来可能变化的业务行。 */
    public JSONObject completed(RunRecord run,JSONObject node,String iterationId){
        requireCompletionScope(run,node);
        var sources=mapper.completedSources(run.getId(),run.getLeaseId(),run.getAttempt(),node.getString("id"),iterationId==null?"":iterationId);
        if(sources==null || sources.size()!=1)throw DataResourceService.fail("数据节点缺少唯一中心完成事实",403);
        var row=sources.get(0);var complete=ModelMessageSources.sourceObject(String.valueOf(row.get("completionJson")));
        if(complete==null || !Objects.equals(RunJsonBoundary.hashFull(complete),row.get("completionHash")))throw DataResourceService.fail("数据完成事实摘要不一致",409);
        var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());String resource=node.getJSONObject("data").getJSONObject("nodeParam").getString("dataResourceId");
        var descriptor=assignment.getJSONObject("resourceSnapshots").getJSONObject(resource);
        if(descriptor==null || !Objects.equals(run.getId(),complete.get("runId")) || !Objects.equals(run.getLeaseId(),complete.get("leaseId")) || !Objects.equals(run.getAttempt(),complete.getInteger("attempt")) || !Objects.equals(node.get("id"),complete.get("nodeId")) || !Objects.equals(iterationId,complete.get("iterationId")) || !Objects.equals(resource,complete.get("resourceId")) || !Objects.equals(descriptor.get("version"),complete.get("resourceVersion")) || !Objects.equals(RunJsonBoundary.hashFull(node),complete.get("nodeHash")) || !Objects.equals(RunJsonBoundary.hashFull(descriptor),complete.get("descriptorHash")) || complete.getIntValue("env")!=DataResourceService.environment(assignment.get("dataEnvironment")))throw DataResourceService.fail("数据完成事实不属于当前固定运行",409);
        var receipt=mapper.request(run.getId(),run.getAttempt(),String.valueOf(row.get("requestKeyHash")));var response=complete.getJSONObject("responseJson");
        if(receipt==null || !Objects.equals(receipt.get("requestHash"),complete.get("requestHash")) || response==null || !Objects.equals(RunJsonBoundary.hashFull(response),complete.get("responseHash")) || !sameResponse(response,receipt.get("responseJson")))throw DataResourceService.fail("数据完成事实与持久操作回执不一致",409);
        return complete;
    }
    private static boolean sameResponse(JSONObject expected,Object json){var actual=ModelMessageSources.sourceObject(String.valueOf(json));return expected!=null && actual!=null && RunJsonBoundary.canonicalFull(expected).equals(RunJsonBoundary.canonicalFull(actual));}
    private static void requireCompletionScope(RunRecord run,JSONObject node){
        if(run==null || run.getLeaseId()==null || run.getLeaseId().isBlank() || run.getAttempt()==null || run.getAttempt()<1 || node==null || !"database".equals(com.ruoyi.paiflow.domain.NodeCatalog.typeOf(node.getString("id"))))throw DataResourceService.fail("数据完成节点作用域无效",409);
        RunLeaseService.requireExecutionBegun(run);var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        var fixed=assignment==null?null:DataWorkerOperationService.find(assignment.get("dsl"),node.getString("id"));
        if(fixed==null || assignment.getJSONObject("resourceSnapshots")==null || !RunJsonBoundary.canonicalFull(fixed).equals(RunJsonBoundary.canonicalFull(node)))throw DataResourceService.fail("数据完成节点不是当前固定节点",409);
    }
    private List<JSONObject> structured(DataRecord table,int env,int mode,JSONObject p,Set<String> allowed,long owner){
        if(mode<1||mode>4||p.get("sql")!=null)throw DataResourceService.fail("结构化数据操作无效",400);
        var values=p.getJSONObject("values");if(values==null)values=new JSONObject();columns(values.keySet(),allowed);
        var where=p.getJSONObject("where");var conditions=where==null?new JSONArray():where.getJSONArray("conditions");if(conditions==null)throw DataResourceService.fail("条件须为数组",400);if(conditions.size()>100)throw DataResourceService.fail("条件过多",400);
        for(Object item:conditions){var c=(JSONObject)item;columns(Set.of(c.getString("fieldName")),allowed);operator(c.getString("operator"));}
        if((mode==2||mode==4)&&conditions.isEmpty())throw DataResourceService.fail("结构化更新或删除必须带原首组条件",400);
        String logic=where==null?"and":where.getString("logicalOperator");if(!Set.of("and","or").contains(logic))throw DataResourceService.fail("条件逻辑无效",400);
        var matched=rows.loadRows(table.getId(),env).stream().filter(r->matches(r,conditions,logic)).map(r->JSON.parseObject(r.toJSONString())).toList();
        if(mode==1)return List.of(rows.insert(table,env,values,owner));
        if(mode==2||mode==4){var out=new ArrayList<JSONObject>();for(var row:matched){if(mode==4)rows.mutateRow(table,env,4,JSONObject.of("id",row.get("id")),owner);else{var change=JSON.parseObject(values.toJSONString());change.put("id",row.get("id"));out.add(rows.mutateRow(table,env,2,change,owner));}}return mode==4?List.of(JSONObject.of("affected_rows",matched.size())):out;}
        var order=p.getJSONArray("order");var sorted=new ArrayList<>(matched);if(order!=null){for(Object item:order)columns(Set.of(((JSONObject)item).getString("fieldName")),allowed);sorted.sort((a,b)->{for(Object item:order){var spec=(JSONObject)item;String field=spec.getString("fieldName");int comparison=compareNullable(a.get(field),b.get(field));if(comparison!=0)return Boolean.TRUE.equals(spec.get("isDesc"))||"desc".equalsIgnoreCase(spec.getString("order"))?-comparison:comparison;}return 0;});}
        int limit=p.get("limit")==null?1000:Math.toIntExact(DataResourceService.idOrZero(p.get("limit")));if(limit>10000)throw DataResourceService.fail("查询数量超出10000",400);var selected=p.getJSONArray("columns");if(selected!=null)for(Object item:selected)columns(Set.of(String.valueOf(item)),allowed);var result=new ArrayList<JSONObject>();for(var row:sorted.stream().limit(limit).toList()){if(selected==null||selected.isEmpty())result.add(row);else{var projection=new JSONObject();for(Object item:selected)projection.put(String.valueOf(item),row.get(String.valueOf(item)));result.add(projection);}}return result;
    }
    private List<JSONObject> custom(DataRecord table,int env,DataSqlService.Plan plan,Set<String> allowed,long owner){
        DataSqlService.validate(plan.where(),allowed);columns(plan.assignments().keySet(),allowed);for(var expr:plan.assignments().values())DataSqlService.validate(expr,allowed);for(var values:plan.inserts())for(var expr:values)DataSqlService.validate(expr,Set.of());for(var projection:plan.projection())DataSqlService.validate(projection.getExpr(),allowed);for(var order:plan.order())DataSqlService.validate(order.getExpr(),allowed);
        var matched=new ArrayList<JSONObject>();for(var row:rows.loadRows(table.getId(),env))if(Boolean.TRUE.equals(DataSqlService.eval(plan.where(),row,allowed)))matched.add(row);
        if(plan.mode()==1){var out=new ArrayList<JSONObject>();for(var expressions:plan.inserts()){var value=new JSONObject();int index=0;for(var name:plan.assignments().keySet())value.put(name,DataSqlService.eval(expressions.get(index++),new JSONObject(),Set.of()));out.add(rows.insert(table,env,value,owner));}return out;}
        if(plan.mode()==2||plan.mode()==4){var out=new ArrayList<JSONObject>();for(var row:matched){var value=JSONObject.of("id",row.get("id"));if(plan.mode()==2)for(var assignment:plan.assignments().entrySet())value.put(assignment.getKey(),DataSqlService.eval(assignment.getValue(),row,allowed));var changed=rows.mutateRow(table,env,plan.mode()==4?4:2,value,owner);if(plan.mode()==2)out.add(changed);}return plan.mode()==4?List.of(JSONObject.of("affected_rows",matched.size())):out;}
        if(!plan.order().isEmpty())matched.sort((a,b)->{for(var order:plan.order()){int c=compareNullable(DataSqlService.eval(order.getExpr(),a,allowed),DataSqlService.eval(order.getExpr(),b,allowed));if(c!=0)return order.getType()==SQLOrderingSpecification.DESC?-c:c;}return 0;});
        if(plan.projection().size()==1&&plan.projection().get(0).getExpr() instanceof SQLAggregateExpr){var item=plan.projection().get(0);return List.of(JSONObject.of(item.getAlias()==null?"count":item.getAlias(),matched.size()));}
        if(plan.projection().stream().anyMatch(x->x.getExpr() instanceof SQLAggregateExpr))throw DataResourceService.fail("聚合不能与普通投影混用",400);
        var result=new ArrayList<JSONObject>();for(var row:matched.stream().skip(plan.offset()).limit(plan.limit()).toList()){var value=new JSONObject();for(var item:plan.projection()){if(item.getExpr() instanceof SQLAllColumnExpr)value.putAll(row);else{String name=item.getAlias()==null?item.getExpr() instanceof SQLIdentifierExpr?DataSqlService.column(item.getExpr()):item.getExpr().toString():item.getAlias();value.put(name,DataSqlService.eval(item.getExpr(),row,allowed));}}result.add(value);}return result;
    }
    private static void columns(Collection<String> fields,Set<String> allowed){if(!allowed.containsAll(fields))throw DataResourceService.fail("数据列未获固定版本授权",403);}
    private static int compareNullable(Object a,Object b){return a==null?b==null?0:1:b==null?-1:DataSqlService.compare(a,b);}
    private static void operator(String value){if(!Set.of("=","!=","<>",">",">=","<","<=","like","not like","null","not null","in","not in").contains(value))throw DataResourceService.fail("数据条件操作无效",400);}
    private static boolean zeroEquals(Object value,Object zero){return value instanceof Number && zero instanceof Number?DataSqlService.compare(value,zero)==0:Objects.equals(value,zero);}
    private static boolean matches(JSONObject row,JSONArray conditions,String logic){if(conditions.isEmpty())return true;boolean result="and".equals(logic);for(Object raw:conditions){var c=(JSONObject)raw;Object left=row.get(c.getString("fieldName")),right=c.get("value");String op=c.getString("operator");boolean matched;if("null".equals(op)||"not null".equals(op)){matched=left==null||c.containsKey("zeroEquivalent")&&zeroEquals(left,c.get("zeroEquivalent"));if("not null".equals(op))matched=!matched;}else if(left==null||right==null)matched=false;else matched=switch(op){case "="->DataSqlService.compare(left,right)==0;case "!=","<>"->DataSqlService.compare(left,right)!=0;case ">"->DataSqlService.compare(left,right)>0;case ">="->DataSqlService.compare(left,right)>=0;case "<"->DataSqlService.compare(left,right)<0;case "<="->DataSqlService.compare(left,right)<=0;case "like"->DataSqlService.like(left,right);case "not like"->!DataSqlService.like(left,right);case "in","not in"->{if(!(right instanceof JSONArray values))throw DataResourceService.fail("IN条件须为数组",400);boolean found=values.stream().anyMatch(v->v!=null&&DataSqlService.compare(left,v)==0);yield "in".equals(op)?found:!found;}default->false;};result="and".equals(logic)?result&&matched:result||matched;}return result;}
}

