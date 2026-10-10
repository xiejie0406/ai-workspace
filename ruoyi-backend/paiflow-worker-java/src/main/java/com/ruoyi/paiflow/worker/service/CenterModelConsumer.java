package com.ruoyi.paiflow.worker.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmCallback;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmResVo;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiStreamFunctionCallingHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原Java OpenAI风格模型消费者。原Spring AI负责解码/usage，中心独占供应商连接和账本。 */
public final class CenterModelConsumer {
    private static final Set<String> RESOURCE = Set.of("kind","provider","version","modelName","enabled","capabilities");
    private static final Set<String> BINDING = Set.of("runId","leaseId","attempt","nodeId","iterationId","workflowVersionId","dslHash","operationId","resourceId","resourceVersion","provider");
    private static final Set<String> FRAME_FIELDS = Set.of("state","operationState","frames","nextCursor","done","lastSeq","finishSeq","failureKind");
    private static final Set<String> STATES = Set.of("RUNNING","COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private static final Set<String> FAILURES = Set.of("provider","authorization","protocol","transport","deadline","lease","cancelled","budget");
    private final ExecutionScope scope;
    private final CenterClient center;
    private final Assignment assignment;
    private final Map<String, Call> calls = new ConcurrentHashMap<>();
    private final AtomicBoolean unknown = new AtomicBoolean();
    private static final class Call {
        final Map<String,Object> binding;
        volatile String id;
        volatile boolean pending = true;
        long cursor, bytes;
        Long finishSeq;
        String finishReason;
        final Queue<OpenAiApi.ChatCompletionChunk> tail=new ArrayDeque<>();
        Call(Map<String,Object> binding) { this.binding=binding; }
    }
    CenterModelConsumer(ExecutionScope scope, CenterClient center, Assignment assignment) {
        this.scope=scope; this.center=center; this.assignment=assignment;
    }

    public static void validateAssignment(Assignment task) {
        Set<String> used = new HashSet<>();
        for(Object raw : list(task.dsl().get("nodes"))) {
            Map<?,?> node=map(raw); Object id=node.get("id");
            if(!(id instanceof String nodeId) || !nodeId.startsWith("spark-llm::")) continue;
            Map<?,?> p=map(map(node.get("data")).get("nodeParam"));
            String ref=text(p.get("modelResourceId"),256); validateResource(task.resourceSnapshots().get(ref));
            Map<String,Object> descriptor=task.resourceSnapshots().get(ref);
            if(!Objects.equals(p.get("domain"),descriptor.get("modelName"))) throw invalid("原Java domain与固定模型不一致");
            if(p.get("enableChatHistoryV2") instanceof Map<?,?> history && "true".equals(String.valueOf(history.get("isEnabled")))
                    && history.get("rounds") instanceof Integer rounds && rounds!=0 && task.executionContext()==null) {
                throw new WorkerException("RESOURCE_UNAVAILABLE","原Java历史中心资源尚未接通，须保留此模式待迁移");
            }
            for(String credential:List.of("url","apiKey","apiSecret")) if(p.get(credential)!=null) throw invalid("Worker固定模型不能携带供应商连接或凭据");
            if(!task.effectPolicy().allowedOperations().contains("model.invoke") || !"approved_write".equals(task.effectPolicy().mode())) {
                throw new WorkerException("TOOL_POLICY_DENIED","原Java模型费用未获中心运行许可");
            }
            used.add(ref);
        }
        Set<String> models=new HashSet<>(); task.resourceSnapshots().forEach((id,r)->{if("model".equals(r.get("kind")))models.add(id);});
        if(!used.equals(models)) throw invalid("原Java模型快照与节点固定引用不一致");
    }
    private static void validateResource(Map<String,Object> r) {
        if(r==null || !r.keySet().equals(RESOURCE) || !"model".equals(r.get("kind")) || !"openai".equals(r.get("provider"))
                || !Boolean.TRUE.equals(r.get("enabled")) || !List.of("chat").equals(r.get("capabilities"))) throw invalid("原Java只消费固定OpenAI风格chat资源");
        text(r.get("version"),256); text(r.get("modelName"),256);
    }
    /** 公开八字段对应原SDK最终有效选项，nullable按原SDK省略，不发送原始extra字典。 */
    public static Map<String,Object> options(LlmReqBo req) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("maxTokens",req.getMaxTokens());
        for(String name:List.of("temperature","topP","presencePenalty","frequencyPenalty","n")) result.put(name,null);
        if(req.getExtraParams()!=null) {
            for(String name:List.of("temperature","topP","presencePenalty","frequencyPenalty","maxTokens","n")) {
                Object value=req.getExtraParams().get(name); if(value==null)continue;
                try {
                    if(Set.of("maxTokens","n").contains(name)) result.put(name,Integer.parseInt(value.toString()));
                    else { double number=Double.parseDouble(value.toString()); if(!Double.isFinite(number))throw invalid("模型选项必须是有限数值"); result.put(name,number); }
                } catch(NumberFormatException ignored) { /* 原SDK忽略无法转换的字段。 */ }
            }
        }
        result.put("streamUsage",true);result.put("enableThinking",true);return result;
    }
    public static List<Map<String,Object>> messages(LlmReqBo req) {
        List<Map<String,Object>> result=new ArrayList<>();
        if(req.getSystemMsg()!=null && !req.getSystemMsg().isBlank())result.add(Map.of("role","system","content",req.getSystemMsg()));
        if(req.getHistory()!=null) for(var item:req.getHistory()) {
            for(var old:item.userInputs()) if(old.role()!=com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.SYSTEM)
                result.add(Map.of("role","user","content",old.content()));
            if(item.llmResponses()!=null&&!item.llmResponses().isEmpty())result.add(Map.of("role","assistant","content",item.llmResponses().get(0).content()));
        }
        if(req.getUserMsg()==null)throw invalid("原Java模型缺少用户提示词");
        result.add(Map.of("role","user","content",req.getUserMsg())); return result;
    }
    public LlmResVo call(LlmReqBo req, LlmCallback callback) {
        scope.check(); validateAssignment(assignment);
        Map<?,?> node=list(assignment.dsl().get("nodes")).stream().map(CenterModelConsumer::map)
                .filter(n->req.getNodeId().equals(n.get("id"))).findFirst().orElseThrow(()->invalid("模型节点不属于固定图"));
        String ref=text(map(map(node.get("data")).get("nodeParam")).get("modelResourceId"),256);
        Map<String,Object> resource=scope.boundResource(ref,Map.of("kind","model","provider","openai","modelName",req.getModel()));
        Map<String,Object> parameters=new LinkedHashMap<>(); parameters.put("resourceId",ref);parameters.put("resourceVersion",resource.get("version"));
        parameters.put("provider","openai");parameters.put("modelName",req.getModel());parameters.put("mode","chat");
        List<Map<String,Object>> messages=messages(req);scope.verifyJavaHistory(req,messages);
        parameters.put("messages",messages);parameters.put("options",options(req));
        Map<String,Object> binding=new LinkedHashMap<>();binding.put("runId",assignment.runId());binding.put("leaseId",assignment.leaseId());binding.put("attempt",assignment.attempt());
        binding.put("workflowVersionId",assignment.workflowVersionId());binding.put("dslHash",assignment.dslHash());binding.put("nodeId",req.getNodeId());binding.put("iterationId",null);
        binding.put("resourceId",ref);binding.put("resourceVersion",resource.get("version"));binding.put("provider","openai");
        Call call=new Call(binding);
        if(calls.putIfAbsent(req.getNodeId(),call)!=null)throw unknown("同一固定模型节点不能重新派发费用操作",null);
        String key=assignment.runId()+":"+assignment.attempt()+":"+req.getNodeId()+":model.invoke:"+hash(parameters);
        StringBuilder content=new StringBuilder(),reason=new StringBuilder();
        try {
            Map<String,Object> start=center.operation(lease(),req.getNodeId(),"model.invoke",parameters,key);
            checkBinding(call,start,Set.of("state"));
            if(!"RUNNING".equals(start.get("state")))throw unknown("模型初始许可状态无效",null);
            call.id=text(start.get("operationId"),128); call.binding.put("operationId",call.id);
            Map<String,Object> opts=options(req);
            OpenAiChatOptions.Builder builder=OpenAiChatOptions.builder().model(req.getModel()).streamUsage(true).extraBody(Map.of("enable_thinking",true));
            if(opts.get("maxTokens")!=null)builder.maxTokens((Integer)opts.get("maxTokens"));
            if(opts.get("temperature")!=null)builder.temperature((Double)opts.get("temperature"));
            if(opts.get("topP")!=null)builder.topP((Double)opts.get("topP"));
            if(opts.get("presencePenalty")!=null)builder.presencePenalty((Double)opts.get("presencePenalty"));
            if(opts.get("frequencyPenalty")!=null)builder.frequencyPenalty((Double)opts.get("frequencyPenalty"));
            if(opts.get("n")!=null)builder.N((Integer)opts.get("n"));
            List<Message> prompt=new ArrayList<>();for(Map<String,Object> m:messages) prompt.add(switch((String)m.get("role")) {
                case "system" -> new SystemMessage((String)m.get("content"));
                case "assistant" -> new AssistantMessage((String)m.get("content"));
                default -> new UserMessage((String)m.get("content"));
            });
            OpenAiChatModel sdk=OpenAiChatModel.builder().openAiApi(new CentreApi(call)).defaultOptions(builder.build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            ChatResponse last=sdk.stream(new Prompt(prompt)).doOnNext(response->{
                if(!response.getResults().isEmpty()) {
                    var output=response.getResult().getOutput();Object reasoning=output.getMetadata().get("reasoningContent");
                    if(reasoning!=null)reason.append(reasoning);
                    if(output.getText()!=null && !output.getText().isBlank())content.append(output.getText());
                    callback.onResponse(response);
                }
            }).blockLast();
            if(call.pending)throw unknown("原SDK退出但中心模型尚未完成",null);
            scope.check(); return new LlmResVo(last==null?new EmptyUsage():last.getMetadata().getUsage(),content.toString(),reason.toString());
        } catch(RuntimeException error) {
            if(call.pending && !(error instanceof WorkerException w && Set.of("RUN_CANCELLED","LEASE_LOST","DEPENDENCY_TIMEOUT").contains(w.getCode())))unknown.set(true);
            throw error;
        } finally { if(call.pending && call.id!=null)stop(call); }
    }

    private final class CentreApi extends OpenAiApi {
        private final Call call;
        CentreApi(Call call) {
            // 所有外部入口覆写拒绝；此占位URL不会由SDK发送，只供原解码器装配。
            super("https://centre.invalid",new NoopApiKey(),new LinkedMultiValueMap<>(),"/chat/completions","/embeddings",RestClient.builder(),WebClient.builder(),new DefaultResponseErrorHandler());this.call=call;
        }
        @Override public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest r) { return chatCompletionStream(r,new LinkedMultiValueMap<>()); }
        @Override public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest r, MultiValueMap<String,String> h) {
            Flux<ChatCompletionChunk> raw=Flux.generate(()->new ArrayDeque<ChatCompletionChunk>(),(queue,sink)->{
                try {
                    while(queue.isEmpty() && call.pending) {
                        scope.check(); Map<String,Object> page=center.operationFrames(lease(),call.id,call.cursor,64); scope.check();
                        readPage(call,page,queue);
                        if(queue.isEmpty() && call.pending)Thread.sleep(25);
                    }
                    if(!queue.isEmpty())sink.next(queue.removeFirst());else sink.complete();
                } catch(InterruptedException e){Thread.currentThread().interrupt();sink.error(new WorkerException("RUN_CANCELLED","等待模型原帧时请求停止",e));}
                catch(Throwable error){sink.error(error);}return queue;
            });
            // 复用OpenAiApi原公开合并器，保留原normal首choice投影与tool-call分组，而非自行重写ChatResponse。
            var merger=new OpenAiStreamFunctionCallingHelper(); var streamingTool=new AtomicBoolean();
            return raw.map(chunk->{if(merger.isStreamingToolFunctionCall(chunk))streamingTool.set(true);return chunk;})
                    .windowUntil(chunk->{if(streamingTool.get()&&merger.isStreamingToolFunctionCallFinish(chunk)){streamingTool.set(false);return true;}return !streamingTool.get();})
                    .concatMap(window->window.reduce(new ChatCompletionChunk(null,null,null,null,null,null,null,null),merger::merge));
        }
        @Override public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest r) {throw invalid("模型消费者禁止直连Provider");}
        @Override public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest r,MultiValueMap<String,String> h) {throw invalid("模型消费者禁止直连Provider");}
        @Override public <T> ResponseEntity<EmbeddingList<Embedding>> embeddings(EmbeddingRequest<T> r) {throw invalid("模型消费者未接多模态/embedding");}
    }
    private void readPage(Call call, Map<String,Object> page, Queue<OpenAiApi.ChatCompletionChunk> queue) {
        checkBinding(call,page,FRAME_FIELDS);
        String state=text(page.get("state"),32),actual=text(page.get("operationState"),32);
        Object failure=page.get("failureKind"); long next=integer(page.get("nextCursor"),0,10000),last=integer(page.get("lastSeq"),0,10000);
        List<?> frames=list(page.get("frames")); boolean done=Boolean.TRUE.equals(page.get("done"));
        Long finish=page.get("finishSeq")==null?null:integer(page.get("finishSeq"),1,last);
        if(!STATES.contains(state)||!STATES.contains(actual)||!(page.get("done") instanceof Boolean)||frames.size()>64||next!=call.cursor+frames.size()||next>last
                ||failure!=null&&!FAILURES.contains(failure)||"FAILED".equals(actual)&&failure==null
                ||done!=(next==last&&!"RUNNING".equals(actual))||!state.equals(done?actual:"RUNNING")
                ||"COMPLETED".equals(actual)&&(finish==null||failure!=null))throw unknown("模型分页状态/游标无效",null);
        if(Set.of("CANCELLED","EFFECT_UNKNOWN").contains(actual)||"FAILED".equals(actual)&&!("provider".equals(failure)&&finish!=null))throw unknown("中心模型未确认完整原流",null);
        ObjectMapper mapper=new ObjectMapper();
        for(Object raw:frames) {
            Map<?,?> frame=map(raw);byte[] bytes=JSON.toJSONBytes(frame,JSONWriter.Feature.WriteMapNullValue);call.bytes+=bytes.length;
            if(bytes.length>65536||call.bytes>8*1024*1024)throw unknown("模型原帧超出预算",null);
            long sequence=++call.cursor; List<?> choices=list(frame.get("choices"));
            if(choices.isEmpty()) {
                Map<?,?> usage=map(frame.get("usage"));for(String key:List.of("prompt_tokens","completion_tokens","total_tokens"))integer(usage.get(key),0,Integer.MAX_VALUE);
            } else {
                if(call.finishSeq!=null)throw unknown("真实模型finish后仍返回内容帧",null);
                Map<?,?> first=map(choices.get(0));Object reason=first.get("finish_reason");
                if(reason!=null) {
                    if(!(reason instanceof String value)||!Set.of("stop","length","content_filter","function_call","tool_calls").contains(value))throw unknown("原模型finish类型无效",null);
                    call.finishSeq=sequence;call.finishReason=(String)reason;
                    // 真实finish及其尾usage先获得中心原账本确认，才交原SDK。
                    if(!Objects.equals(finish,sequence))throw unknown("终帧尚未取得中心完成位置确认",null);
                }
            }
            try{var chunk=mapper.readValue(bytes,OpenAiApi.ChatCompletionChunk.class);if(call.finishSeq!=null)call.tail.add(chunk);else queue.add(chunk);}catch(Exception error){throw unknown("原Spring AI无法消费此模型帧",error);}
        }
        if(done) {
            if(call.finishSeq==null||!Objects.equals(call.finishSeq,finish)||call.cursor!=last
                    ||!state.equals("stop".equals(call.finishReason)?"COMPLETED":"FAILED")||!Objects.equals(failure,"stop".equals(call.finishReason)?null:"provider"))throw unknown("模型finish/尾usage/中心终态不一致",null);
            call.pending=false;
            // 原Java SDK正常length等终帧也返回已生成文本；账本FAILED/provider仍保留，不伪改中心终态。
            queue.addAll(call.tail);call.tail.clear();
        }
    }
    private void checkBinding(Call call,Map<String,Object> ack,Set<String> extra) {
        Set<String> fields=new HashSet<>(BINDING);fields.addAll(extra);
        if(ack==null||!ack.keySet().equals(fields)||JSON.toJSONBytes(ack,JSONWriter.Feature.WriteMapNullValue).length>2*1024*1024)throw unknown("模型回执字段/预算无效",null);
        for(var entry:call.binding.entrySet()) {
            if("attempt".equals(entry.getKey())){if(integer(ack.get("attempt"),1,Integer.MAX_VALUE)!=assignment.attempt())throw unknown("模型attempt不一致",null);}
            else if(!Objects.equals(entry.getValue(),ack.get(entry.getKey())))throw unknown("模型完整回执绑定不一致",null);
        }
        text(ack.get("operationId"),128);
    }
    boolean hasPending(){return calls.values().stream().anyMatch(c->c.pending);}
    boolean hasUnknown(){return unknown.get();}
    void stopPending(){for(Call c:calls.values())if(c.pending&&c.id!=null)stop(c);}
    private void stop(Call call) {
        try {
            Map<String,Object> ack=center.cancelOperation(lease(),call.id);checkBinding(call,ack,Set.of("state","stopped","proofId"));
            if(!(ack.get("stopped") instanceof Boolean)||!STATES.contains(ack.get("state")))throw unknown("模型停止回执无效",null);
            if(Boolean.TRUE.equals(ack.get("stopped"))&&Set.of("COMPLETED","FAILED","CANCELLED").contains(ack.get("state"))
                    &&ack.get("proofId") instanceof String p&&!p.isBlank()){call.pending=false;return;}
        }catch(RuntimeException ignored){ /* 未确认必须保留pending，不能因本地dispose假称供应商停止。 */ }
        unknown.set(true);
    }
    private Lease lease(){return new Lease(assignment.runId(),assignment.leaseId(),assignment.attempt());}
    private static long integer(Object v,long min,long max){if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<min||((Number)v).longValue()>max)throw unknown("模型整数字段无效",null);return ((Number)v).longValue();}
    private static String text(Object v,int max){if(!(v instanceof String s)||s.isBlank()||s.length()>max)throw invalid("模型固定字符串无效");return s;}
    private static Map<?,?> map(Object v){if(!(v instanceof Map<?,?> m))throw invalid("模型对象字段无效");return m;}
    private static List<?> list(Object v){if(!(v instanceof List<?> l))throw invalid("模型数组字段无效");return l;}
    private static String hash(Map<String,Object> p){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONBytes(p,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static WorkerException invalid(String m){return new WorkerException("VERSION_CONFLICT",m);}
    private static WorkerException unknown(String m,Throwable e){return new WorkerException("EFFECT_UNKNOWN",m,e);}
}
