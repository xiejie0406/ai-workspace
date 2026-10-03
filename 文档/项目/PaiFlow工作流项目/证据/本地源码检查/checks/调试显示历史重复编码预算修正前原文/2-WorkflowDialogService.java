package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService;
import com.ruoyi.paiflow.domain.WorkflowDialogRecord;
import com.ruoyi.paiflow.mapper.WorkflowDialogMapper;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 原调试显示记录；Run关联由中心核对，显示正文不成为执行结果或正式会话。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowDialogService {
    private static final String QUERY="paiflow:workflow:query",EDIT="paiflow:workflow:edit";
    private static final JsonMapper DISPLAY_JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(Long id,String uid,Long workflowId,String question,String answer,String data,String sid,
            String questionItem,String answerItem,Date createTime,Boolean deleted,Integer type,String chatId) {}
    private record Scope(long spaceId,long workflowId,long ownerUserId) {}
    private final WorkflowDialogMapper mapper;
    private final LegacyDebugBridgeService debug;
    private final WorkflowService workflows;
    private final DefaultPersonalSpaceService defaults;
    private final ResourceAccessService access;
    public WorkflowDialogService(WorkflowDialogMapper mapper,LegacyDebugBridgeService debug,WorkflowService workflows,
            DefaultPersonalSpaceService defaults,ResourceAccessService access) {
        this.mapper=mapper;this.debug=debug;this.workflows=workflows;this.defaults=defaults;this.access=access;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public String save(JSONObject body,String header) {
        if(body==null)throw bad("显示记录请求不能为空");
        // 原DTO未知字段被忽略；客户端的id/uid/空间/版本/时间/删除状态不能覆盖中心关联。
        if(JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8).length>RunProtocolLimits.RESULT_BYTES)
            throw new ServiceException("显示记录正文超出允许大小",413);
        type(body.get("type"),true);
        long workflowId=positiveId(body.get("workflowId"));
        String sid=text(body.get("sid"),"SID",128,false),chatId=text(body.get("chatId"),"显示分组",100,true);
        String question=display(body.get("question"),true),answer=display(body.get("answer"),false);
        String questionItem=text(body.get("questionItem"),"问题显示项",RunProtocolLimits.RESULT_BYTES,true);
        String answerItem=text(body.get("answerItem"),"答案显示项",RunProtocolLimits.RESULT_BYTES,true);
        String data=text(body.get("data"),"显示数据",RunProtocolLimits.RESULT_BYTES,true);
        var bound=debug.requireDisplayRun(workflowId,sid,header);
        var row=new WorkflowDialogRecord();row.setSpaceId(bound.spaceId());row.setOwnerUserId(bound.ownerUserId());
        row.setWorkflowId(bound.workflowId());row.setWorkflowVersionId(bound.workflowVersionId());row.setRunId(bound.runId());
        row.setEventWatermark(bound.eventWatermark());row.setSid(bound.runId());row.setChatId(chatId);row.setType(1);row.setDeleted(false);
        row.setQuestion(question);row.setAnswer(answer);row.setQuestionItem(questionItem);row.setAnswerItem(stripOuterQuotes(answerItem));row.setData(data);
        sameActor(new Scope(bound.spaceId(),workflowId,bound.ownerUserId()),EDIT);
        // 每个POST都是原页面的一次显示段；相同Run或相同内容不代表重试键。
        if(mapper.insert(row)!=1 || row.getId()==null)throw new ServiceException("显示记录保存失败",500);
        sameActor(new Scope(bound.spaceId(),workflowId,bound.ownerUserId()),EDIT);
        if(access.requirePermission("paiflow:run:query")!=bound.ownerUserId())throw new ServiceException("显示记录主体已变化",403);
        if(access.requirePermission(QUERY)!=bound.ownerUserId())throw new ServiceException("显示记录主体已变化",403);
        return chatId;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public List<View> list(long workflowId,Integer type,String header) {
        var scope=scope(workflowId,header,QUERY,false);Integer selected=type(type,false);
        var rows=selected==null?List.<View>of():mapper.list(scope.spaceId(),workflowId,scope.ownerUserId(),selected).stream().map(WorkflowDialogService::view).toList();
        sameActor(scope,QUERY);return rows;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public int clear(long workflowId,Integer type,String header) {
        var scope=scope(workflowId,header,EDIT,true);Integer selected=type(type,false);sameActor(scope,EDIT);
        int affected=selected==null?0:mapper.clear(scope.spaceId(),workflowId,scope.ownerUserId(),selected);
        sameActor(scope,EDIT);return affected;
    }
    private Scope scope(long workflowId,String header,String permission,boolean lock) {
        if(workflowId<1)throw bad("工作流ID须为内部正整数");
        long actor=access.requirePermission(permission);
        Long space=defaults.existing(defaults.select(header,null,permission),permission);
        if(space==null)throw new ServiceException("显示记录工作流不存在或不可访问",404);
        var row=workflows.require(workflowId,space,permission,3,lock);
        if(access.requirePermission(permission)!=actor)throw new ServiceException("显示记录主体已变化",403);
        return new Scope(row.getSpaceId(),row.getId(),actor);
    }
    private void sameActor(Scope scope,String permission) {
        access.requireSpace(scope.spaceId(),permission,3,false);
        if(access.requirePermission(permission)!=scope.ownerUserId())throw new ServiceException("显示记录主体已变化",403);
    }
    private static View view(WorkflowDialogRecord row) {
        return new View(row.getId(),IdentityMappingService.legacyUid(row.getOwnerUserId()),row.getWorkflowId(),row.getQuestion(),row.getAnswer(),
                row.getData(),row.getSid(),row.getQuestionItem(),row.getAnswerItem(),row.getCreateTime(),row.getDeleted(),row.getType(),row.getChatId());
    }
    private static Integer type(Object value,boolean required) {
        if(value==null){if(required)throw bad("保存显示记录须明确type=1");return null;}
        long parsed=positiveId(value);
        if(parsed==2)throw new ServiceException("正式App对话历史尚未接入",503);
        if(parsed!=1)throw bad("显示记录类型无效");return 1;
    }
    private static long positiveId(Object value) {
        if(!(value instanceof String || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || !value.toString().matches("[1-9][0-9]{0,18}"))throw bad("工作流ID和类型须为规范正整数");
        try{return Long.parseLong(value.toString());}catch(NumberFormatException ex){throw bad("工作流ID和类型超出范围");}
    }
    private static String text(Object raw,String name,int max,boolean nullable) {
        if(raw==null && nullable)return null;
        if(!(raw instanceof String value) || (!nullable && value.isEmpty()) || value.codePointCount(0,value.length())>max)
            throw bad(name+"须为允许长度的字符串");
        unicode(value);return value;
    }
    private static String display(Object raw,boolean question) {
        String value=text(raw,question?"问题":"答案",RunProtocolLimits.RESULT_BYTES,true);if(value==null)return null;
        Object parsed;
        try { parsed=DISPLAY_JSON.readValue(value,Object.class); }
        catch(RuntimeException ex){throw bad("显示记录须包含有效的JSON字符串");}
        if(parsed!=null && (question?!(parsed instanceof List<?>):!(parsed instanceof Map<?,?>)))
            throw bad(question?"问题显示须为原输入数组或null":"答案显示须为原显示对象或null");
        displayValues(parsed,0);
        if(question && parsed instanceof List<?> items)for(Object item:items) {
            if(item instanceof Map<?,?> input) {
                if(items.size()>1)requireTextChild(input.get("name"));
                Object defaultValue=input.get("default");
                if(!truthy(input.get("fileType")))requireStringConversion(defaultValue);
                else if(!("image".equals(input.get("fileType")) && "string".equals(input.get("type")))
                        && defaultValue instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof Map<?,?> file)
                    requireTextChild(file.get("name"));
            }
        }
        if(!question && parsed instanceof Map<?,?> answer)requireAnswerDisplay(answer);
        return value;
    }
    private static void requireAnswerDisplay(Map<?,?> answer) {
        boolean shown=false;
        for(String key:List.of("messageContent","reasoningContent","content")) {
            Object value=answer.get(key);
            if(truthy(value)) {
                if(!(value instanceof String))throw bad("答案显示文本须为字符串或原空值");
                shown=true;
            }
        }
        Object options=answer.get("option");
        // 与原三文本/选项双guard一致；falsy原值不转成空数组，不给原无展示内容造选项。
        if(!shown || !truthy(options))return;
        if(!(options instanceof List<?> items))throw bad("答案显示选项须为原数组");
        for(Object item:items)if(item instanceof Map<?,?> option) {
            requireTextChild(option.get("id"));
            if(!"image".equals(option.get("content_type")))requireTextChild(option.get("text"));
            else requireStringConversion(option.get("text"));
        }
    }
    private static void requireTextChild(Object value) {
        if(value instanceof Map<?,?>)throw bad("答案选项文字不能包含对象显示项");
        if(value instanceof List<?> items)for(Object item:items)requireTextChild(item);
    }
    /** 原非文件default直接用模板字符串；仅拒会实际触发TypeError的自身toString覆盖。 */
    private static void requireStringConversion(Object value) {
        if(value instanceof Map<?,?> map && map.containsKey("toString"))throw bad("问题默认值不能覆盖显示字符串转换方法");
        if(value instanceof List<?> list)for(Object item:list)requireStringConversion(item);
    }
    private static boolean truthy(Object value) {
        if(value==null || Boolean.FALSE.equals(value))return false;
        if(value instanceof String text)return !text.isEmpty();
        if(value instanceof Number number)return number.doubleValue()!=0;
        return true;
    }
    private static void displayValues(Object value,int depth) {
        if(depth>48)throw bad("显示JSON嵌套过深");
        if(value instanceof String text)unicode(text);
        else if(value instanceof Map<?,?> map)for(var entry:map.entrySet()){unicode((String)entry.getKey());displayValues(entry.getValue(),depth+1);}
        else if(value instanceof List<?> values)for(var item:values)displayValues(item,depth+1);
    }
    private static void unicode(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i)))throw bad("显示字符串Unicode无效");}
            else if(Character.isLowSurrogate(c))throw bad("显示字符串Unicode无效");
        }
    }
    private static String stripOuterQuotes(String value) {
        return value!=null && value.length()>=2 && value.startsWith("\"") && value.endsWith("\"")?value.substring(1,value.length()-1):value;
    }
    private static ServiceException bad(String message){return new ServiceException(message,400);}
}
