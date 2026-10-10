package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** OpenAI兼容协议与持久调用账本；已完成响应重放，不重复发送收费请求。 */
@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class ModelGateway {
    private final ProviderService providers;private final ControlledHttp http;private final IntelligenceSettings settings;private final ModelCallService calls;
    public ModelGateway(ProviderService providers,ControlledHttp http,IntelligenceSettings settings,ModelCallService calls){this.providers=providers;this.http=http;this.settings=settings;this.calls=calls;}
    public static long estimate(String text){return Math.max(1,(text.codePointCount(0,text.length())+1)/2);}
    public record Embedding(List<List<Double>> vectors,long tokens){}
    private String preflight(ProviderService.Provider p,String path){settings.requireExternal();http.validated(p.endpoint()+path);return providers.secret(p);}
    public Embedding embed(long user,String operation,List<String> inputs,ProviderService.Provider provider){
        if(inputs.isEmpty()||inputs.size()>32)throw new IllegalArgumentException("Embedding批次必须为1到32");
        String key=preflight(provider,"/embeddings");long estimated=inputs.stream().mapToLong(ModelGateway::estimate).sum();
        Map<String,Object> body=Map.of("model",provider.model(),"input",inputs);
        ModelCallService.Call call=calls.begin(user,operation,"EMBEDDING",Map.of("provider",providers.snapshot(provider),"body",body),estimated);Map<String,Object> response;
        if("COMPLETED".equals(call.status()))response=Jsons.map(call.response());
        else try{response=http.json("POST",provider.endpoint()+"/embeddings","Bearer "+key,body);
            long actual=Values.number(Values.map(response.get("usage")).get("total_tokens"),estimated);
            calls.complete(call,response,actual,!Values.map(response.get("usage")).containsKey("total_tokens"),false);
        }catch(RuntimeException failure){calls.uncertain(call,Values.safeError(failure),Map.of());throw failure;}
        List<Object> data=new ArrayList<>(Values.list(response.get("data")));
        data.sort(Comparator.comparingLong(v->Values.number(Values.map(v).get("index"),0)));
        List<List<Double>> out=new ArrayList<>();
        for(Object item:data){
            List<Double> vector=Values.list(Values.map(item).get("embedding")).stream().map(v->{double d=((Number)v).doubleValue();if(!Double.isFinite(d))throw new IllegalStateException("Embedding含非有限值");return d;}).toList();
            if(vector.size()!=provider.dimension())throw new IllegalStateException("INDEX_MODEL_MISMATCH:Embedding维度不一致");out.add(vector);
        }
        if(out.size()!=inputs.size())throw new IllegalStateException("Embedding结果数量不一致");
        return new Embedding(out,Values.number(Values.map(response.get("usage")).get("total_tokens"),estimated));
    }
    public Map<String,Object> complete(long user,String operation,List<Map<String,Object>> messages,List<Map<String,Object>> tools,int maxTokens,ProviderService.Provider provider){
        return complete(user,operation,messages,tools,maxTokens,provider,90);
    }
    public Map<String,Object> complete(long user,String operation,List<Map<String,Object>> messages,List<Map<String,Object>> tools,int maxTokens,ProviderService.Provider provider,int timeoutSeconds){
        Values.range(timeoutSeconds,90,1,90);
        String key=preflight(provider,"/chat/completions");Map<String,Object> body=new LinkedHashMap<>();body.put("model",provider.model());body.put("messages",messages);body.put("max_tokens",maxTokens);if(!tools.isEmpty())body.put("tools",tools);
        long input=estimateInput(messages,tools);var call=calls.begin(user,operation,"LLM",Map.of("provider",providers.snapshot(provider),"body",body),input+maxTokens);Map<String,Object> response;
        if("COMPLETED".equals(call.status()))response=Jsons.map(call.response());
        else try{response=timeoutSeconds==90?http.json("POST",provider.endpoint()+"/chat/completions","Bearer "+key,body):http.json("POST",provider.endpoint()+"/chat/completions","Bearer "+key,body,timeoutSeconds);
            List<Object> choices=Values.list(response.get("choices"));Map<String,Object> answer=choices.isEmpty()?Map.of():Values.map(Values.map(choices.get(0)).get("message"));
            long actual=supplierUsage(response);
            calls.complete(call,response,actual>=0?actual:input+estimateOutput(answer),actual<0,false);
        }catch(RuntimeException failure){calls.uncertain(call,Values.safeError(failure),Map.of());throw failure;}
        List<Object> choices=Values.list(response.get("choices"));if(choices.isEmpty())throw new IllegalStateException("LLM返回空choices");return Values.map(Values.map(choices.get(0)).get("message"));
    }
    public void stream(long user,String operation,List<Map<String,Object>> messages,Consumer<String> token,BooleanSupplier cancel,ProviderService.Provider provider){
        if(cancel.getAsBoolean())throw new CancellationException();
        String key=preflight(provider,"/chat/completions");int max=Values.range(settings.number("chat.max-output-tokens",4096),4096,1,16000);long input=estimateInput(messages,List.of());
        Map<String,Object> body=Map.of("model",provider.model(),"messages",messages,"stream",true,"stream_options",Map.of("include_usage",true),"max_tokens",max);
        var call=calls.begin(user,operation,"LLM",Map.of("provider",providers.snapshot(provider),"body",body),input+max);
        if(Set.of("COMPLETED","STOPPED").contains(call.status())){token.accept(Values.text(Jsons.map(call.response()).get("content")));return;}
        StringBuilder output=new StringBuilder();AtomicLong usage=new AtomicLong(-1);
        try{
            http.sse(provider.endpoint()+"/chat/completions",key,body,data->{
                long actual=supplierUsage(data);if(actual>=0)usage.set(actual);
                List<Object> choices=Values.list(data.get("choices"));
                if(!choices.isEmpty()){String part=Values.text(Values.map(Values.map(choices.get(0)).get("delta")).get("content"));if(!part.isEmpty()){output.append(part);token.accept(part);}}
            },cancel);
            calls.complete(call,Map.of("content",output.toString()),usage.get()>=0?usage.get():input+estimate(output.toString()),usage.get()<0,false);
        }catch(RuntimeException failure){
            if(usage.get()>=0)calls.complete(call,Map.of("content",output.toString()),usage.get(),false,true);
            else calls.uncertain(call,Values.safeError(failure),Map.of("content",output.toString()));
            throw failure;
        }
    }
    /** 原 ReAct 的流式回合：正文即时发送，工具参数按 index 合并；同次调用使用同一正式费用账本。 */
    public Map<String,Object> streamTurn(long user,String operation,List<Map<String,Object>> messages,List<Map<String,Object>> tools,Consumer<String> token,BooleanSupplier cancel,ProviderService.Provider provider){
        if(cancel.getAsBoolean())throw new CancellationException();
        String key=preflight(provider,"/chat/completions");int max=Values.range(settings.number("chat.react-max-output-tokens",2000),2000,1,16000);
        long input=estimateInput(messages,tools);Map<String,Object> body=new LinkedHashMap<>();body.put("model",provider.model());body.put("messages",messages);body.put("max_tokens",max);body.put("stream",true);body.put("stream_options",Map.of("include_usage",true));if(!tools.isEmpty())body.put("tools",tools);
        var call=calls.begin(user,operation,"LLM",Map.of("provider",providers.snapshot(provider),"body",body),input+max);
        if(Set.of("COMPLETED","STOPPED").contains(call.status())){
            Map<String,Object> stored=Jsons.map(call.response());if(cancel.getAsBoolean())throw new CancellationException();String content=Values.text(stored.get("content"));if(!content.isEmpty())token.accept(content);
            if("STOPPED".equals(call.status())||cancel.getAsBoolean())throw new CancellationException("该模型回合已停止");return stored;
        }
        StreamTurn turn=new StreamTurn();AtomicLong usage=new AtomicLong(-1);
        try{
            http.sse(provider.endpoint()+"/chat/completions",key,body,data->{
                if(cancel.getAsBoolean())throw new CancellationException();long actual=supplierUsage(data);if(actual>=0)usage.set(actual);
                List<Object> choices=Values.list(data.get("choices"));if(choices.isEmpty())return;
                Map<String,Object> choice=Values.map(choices.get(0)),delta=Values.map(choice.get("delta"));turn.append(delta);
                String part=Values.text(delta.get("content"));if(!part.isEmpty())token.accept(part);
            },cancel);
            if(cancel.getAsBoolean())throw new CancellationException();Map<String,Object> answer=turn.message(true);
            calls.complete(call,answer,usage.get()>=0?usage.get():input+estimateOutput(answer),usage.get()<0,false);return answer;
        }catch(RuntimeException failure){
            Map<String,Object> partial=turn.message(false);
            if(usage.get()>=0)calls.complete(call,partial,usage.get(),false,true);
            else calls.uncertain(call,Values.safeError(failure),partial);throw failure;
        }
    }
    private static final class StreamTurn {
        private final StringBuilder content=new StringBuilder(),reasoning=new StringBuilder();private final SortedMap<Integer,ToolDelta> tools=new TreeMap<>();
        void append(Map<String,Object> delta){
            appendBounded(content,Values.text(delta.get("content")),2*1024*1024,"流式正文");appendBounded(reasoning,Values.text(delta.get("reasoning_content")),2*1024*1024,"推理资料");
            for(Object raw:Values.list(delta.get("tool_calls"))){Map<String,Object> fragment=Values.map(raw);int index=Values.range(fragment.get("index"),tools.size(),0,31);ToolDelta tool=tools.computeIfAbsent(index,k->new ToolDelta());
                String id=Values.text(fragment.get("id"));if(!id.isBlank()){if(id.length()>200||(!tool.id.isBlank()&&!tool.id.equals(id)))throw new IllegalStateException("模型工具调用 id 变化或超过预算");tool.id=id;}
                String type=Values.text(fragment.get("type"));if(!type.isBlank()&&!"function".equals(type))throw new IllegalStateException("模型工具调用类型未注册");Map<String,Object> function=Values.map(fragment.get("function"));
                appendBounded(tool.name,Values.text(function.get("name")),200,"工具名称");appendBounded(tool.arguments,Values.text(function.get("arguments")),65536,"工具参数");
            }
        }
        Map<String,Object> message(boolean complete){Map<String,Object> result=new LinkedHashMap<>();result.put("role","assistant");result.put("content",content.toString());if(!reasoning.isEmpty())result.put("reasoning_content",reasoning.toString());
            List<Map<String,Object>> calls=new ArrayList<>();for(var entry:tools.entrySet()){ToolDelta t=entry.getValue();if(complete&&t.name.isEmpty())throw new IllegalStateException("模型工具调用未完成名称");calls.add(Map.of("id",t.id.isBlank()?"call_"+entry.getKey():t.id,"type","function","function",Map.of("name",t.name.toString(),"arguments",t.arguments.isEmpty()?"{}":t.arguments.toString())));}if(!calls.isEmpty())result.put("tool_calls",calls);return result;
        }
    }
    private static final class ToolDelta {String id="";final StringBuilder name=new StringBuilder(),arguments=new StringBuilder();}
    private static void appendBounded(StringBuilder target,String part,int limit,String name){if((long)target.length()+part.length()>limit)throw new IllegalStateException(name+"超过预算");target.append(part);}
    /** 与原 router 一致，工具 schema、已规划参数、reasoning 及 tool_call_id 都参与回退估算。 */
    static long estimateInput(List<Map<String,Object>> messages,List<Map<String,Object>> tools){long total=0;for(var message:messages){total+=8+estimate(Values.text(message.get("role")))+estimate(Values.text(message.get("content")));if(message.get("reasoning_content")!=null)total+=estimate(Values.text(message.get("reasoning_content")));if(message.get("tool_calls")!=null)total+=estimate(Jsons.write(message.get("tool_calls")));if(message.get("tool_call_id")!=null)total+=estimate(Values.text(message.get("tool_call_id")));}for(var tool:tools){Map<String,Object> function=Values.map(tool.get("function"));total+=estimate(Values.text(function.get("name")))+estimate(Values.text(function.get("description")))+estimate(Jsons.write(function.getOrDefault("parameters",Map.of())));}return Math.max(1,total);}
    private static long estimateOutput(Map<String,Object> message){long result=estimate(Values.text(message.get("content")));if(message.get("reasoning_content")!=null)result+=estimate(Values.text(message.get("reasoning_content")));if(message.get("tool_calls")!=null)result+=estimate(Jsons.write(message.get("tool_calls")));return result;}
    private static long supplierUsage(Map<String,Object> response){Map<String,Object> usage=Values.map(response.get("usage"));Long total=nonnegativeInteger(usage.get("total_tokens"));if(total!=null)return total;Long prompt=nonnegativeInteger(usage.get("prompt_tokens")),completion=nonnegativeInteger(usage.get("completion_tokens"));if(prompt==null||completion==null)return -1;try{return Math.addExact(prompt,completion);}catch(ArithmeticException invalid){return -1;}}
    private static Long nonnegativeInteger(Object value){if(value==null)return null;try{long n=new java.math.BigDecimal(value.toString()).longValueExact();return n>=0?n:null;}catch(NumberFormatException|ArithmeticException invalid){return null;}}
    public String text(long user,String operation,String system,String prompt,int max,ProviderService.Provider provider){return Values.text(complete(user,operation,List.of(Map.of("role","system","content",system),Map.of("role","user","content",prompt)),List.of(),max,provider).get("content"));}
    public String text(long user,String operation,String system,String prompt,int max,ProviderService.Provider provider,int timeoutSeconds){return Values.text(complete(user,operation,List.of(Map.of("role","system","content",system),Map.of("role","user","content",prompt)),List.of(),max,provider,timeoutSeconds).get("content"));}
    public Map<String,Object> rerank(long user,String operation,String query,List<String> documents,ProviderService.Provider provider){String key=preflight(provider,"/rerank");long estimate=estimate(query)+documents.stream().mapToLong(ModelGateway::estimate).sum();Map<String,Object> body=Map.of("model",provider.model(),"query",query,"documents",documents,"top_n",documents.size());var call=calls.begin(user,operation,"LLM",Map.of("provider",providers.snapshot(provider),"body",body),estimate);if("COMPLETED".equals(call.status()))return Jsons.map(call.response());try{Map<String,Object> result=http.json("POST",provider.endpoint()+"/rerank","Bearer "+key,body);Map<String,Object> usage=Values.map(result.get("usage"));calls.complete(call,result,Values.number(usage.get("total_tokens"),estimate),!usage.containsKey("total_tokens"),false);return result;}catch(RuntimeException failure){calls.uncertain(call,Values.safeError(failure),Map.of());throw failure;}}
    public ProviderService.Provider active(String scope){return providers.active(scope);}
}

