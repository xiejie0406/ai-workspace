package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

/** 仅文字 Sub2API 协议适配；不拥有任务、权限、秘密、额度或重试规则。 */
public final class Sub2ApiTextAdapter {
    private static final int TEXT_LIMIT=2*1024*1024;
    private Sub2ApiTextAdapter() {}
    public record Prepared(String path,Map<String,Object> body,String protocol,List<Map<String,Object>> tools) {}
    /** tokens=-1 表示供应商没有完整可靠用量，Gateway 保留原估算/待核对语义。 */
    public record Result(Map<String,Object> message,long tokens,boolean estimated) {}
    public static final class ProtocolFailure extends IllegalStateException {
        private final Result partial;
        public ProtocolFailure(String message,Result partial){super(message);this.partial=partial;}
        public Result partial(){return partial;}
    }
    /** 当前仅开放已批准的 Responses low；省略字段保持旧请求行为。 */
    public static void validateReasoning(Map<String,Object> config){
        if(!config.containsKey("reasoningEffort"))return;
        if(!"responses".equals(config.get("apiProtocol"))||!"low".equals(config.get("reasoningEffort")))
            throw new IllegalArgumentException("reasoningEffort仅允许Responses的low参数");
    }
    public static Prepared prepare(Map<String,Object> config,String model,List<Map<String,Object>> messages,List<Map<String,Object>> tools,int methodMaxTokens,boolean stream){
        validateReasoning(config);
        int requested=Values.range(methodMaxTokens,0,1,16000),configured=Values.range(config.get("maxOutputTokens"),0,1,16000),max=Math.min(requested,configured);
        if(tools.size()>32||messages.size()>256)throw new IllegalArgumentException("Sub2API 消息或函数数量超过预算");validateSchemas(tools);
        String protocol=Values.text(config.get("apiProtocol"));Map<String,Object> body;
        if("responses".equals(protocol)){
            body=Sub2ApiResponsesCodec.request(model,messages,tools,max,stream);
            if(config.containsKey("reasoningEffort"))body.put("reasoning",Map.of("effort",config.get("reasoningEffort")));
        }
        else if("chat_completions".equals(protocol)){
            String parameter=Values.text(config.get("outputTokenParameter"));if(!Set.of("max_tokens","max_completion_tokens").contains(parameter))throw new IllegalArgumentException("Sub2API Chat 输出参数必须显式选择");
            body=new LinkedHashMap<>();body.put("model",model);body.put("messages",chatMessages(messages));body.put(parameter,max);body.put("store",false);if(!tools.isEmpty())body.put("tools",tools);if(stream){body.put("stream",true);body.put("stream_options",Map.of("include_usage",true));}
        }else throw new IllegalArgumentException("Sub2API 调用协议无效");
        if(Jsons.write(body).length()>4*1024*1024)throw new IllegalArgumentException("Sub2API 请求超过预算");return new Prepared("responses".equals(protocol)?"/responses":"/chat/completions",body,protocol,List.copyOf(tools));
    }
    public static Result decode(Prepared prepared,Map<String,Object> response){return "responses".equals(prepared.protocol())?Sub2ApiResponsesCodec.decode(response,prepared.tools()):decodeChat(response,prepared.tools());}
    private static List<Map<String,Object>> chatMessages(List<Map<String,Object>> messages){
        List<Map<String,Object>> out=new ArrayList<>();Set<String> calls=new HashSet<>(),results=new HashSet<>();for(var message:messages){String role=Values.text(message.get("role"));if(!Set.of("system","developer","user","assistant","tool").contains(role))throw new IllegalArgumentException("Sub2API Chat 消息角色无效");if(message.containsKey(Sub2ApiResponsesCodec.ITEMS))throw new IllegalArgumentException("Sub2API 协议发生变化，不能混用 Responses 回合");
            Map<String,Object> m=new LinkedHashMap<>();m.put("role",role);m.put("content",Sub2ApiResponsesCodec.bounded(Values.text(message.get("content")),TEXT_LIMIT,"输入正文"));
            if(message.get("reasoning_content")!=null)m.put("reasoning_content",Sub2ApiResponsesCodec.bounded(Values.text(message.get("reasoning_content")),TEXT_LIMIT,"推理资料"));
            if(message.get("tool_calls")!=null){if(!"assistant".equals(role))throw new IllegalArgumentException("工具调用不属于 assistant");List<Object> rawCalls=Values.list(message.get("tool_calls"));if(rawCalls.size()>32)throw new IllegalArgumentException("工具调用超过预算");for(Object raw:rawCalls){Map<String,Object> call=Values.map(raw);String id=Sub2ApiResponsesCodec.required(call,"id",200);if(!calls.add(id)||!"function".equals(call.get("type")))throw new IllegalArgumentException("工具调用关联或类型无效");Map<String,Object> f=Values.map(call.get("function"));Sub2ApiResponsesCodec.required(f,"name",200);Sub2ApiResponsesCodec.parseArguments(Sub2ApiResponsesCodec.required(f,"arguments",65536));}m.put("tool_calls",rawCalls);}
            if("tool".equals(role)){String id=Sub2ApiResponsesCodec.required(message,"tool_call_id",200);if(!calls.contains(id)||!results.add(id))throw new IllegalArgumentException("工具回灌关联缺失或重复");m.put("tool_call_id",id);}out.add(m);
        }if(!calls.equals(results))throw new IllegalArgumentException("历史工具回合缺少结果");return out;
    }
    private static Result decodeChat(Map<String,Object> response,List<Map<String,Object>> tools){
        long usage=supplierUsage(response,"prompt_tokens","completion_tokens");List<Object> choices=Values.list(response.get("choices"));Map<String,Object> choice=choices.size()==1?Values.map(choices.get(0)):Map.of(),message=Values.map(choice.get("message"));String partialText=message.get("content") instanceof String text?text:"";Map<String,Object> partial=Map.of("role","assistant","content",partialText.substring(0,Math.min(partialText.length(),TEXT_LIMIT)));
        try{if(choices.size()!=1||(choice.containsKey("index")&&Values.number(choice.get("index"),-1)!=0))throw new IllegalStateException("Sub2API Chat 未返回唯一结果");Map<String,Object> answer=validatedMessage(message,Values.text(choice.get("finish_reason")),tools);return new Result(answer,usage,usage<0);}catch(RuntimeException invalid){throw new ProtocolFailure(protocolError(invalid),new Result(partial,usage,usage<0));}
    }
    private static Map<String,Object> validatedMessage(Map<String,Object> message,String finish,List<Map<String,Object>> tools){
        String role=Values.text(message.get("role"));if(!role.isBlank()&&!"assistant".equals(role))throw new IllegalStateException("Sub2API Chat 输出角色无效");
        if(!Values.text(message.get("refusal")).isBlank()||"content_filter".equals(finish))throw new IllegalStateException("模型拒绝回答");
        if("length".equals(finish))throw new IllegalStateException("模型输出被长度限制截断");if(!Set.of("stop","tool_calls").contains(finish))throw new IllegalStateException("模型没有合法完成原因");
        if(message.get("content")!=null&&!(message.get("content") instanceof String))throw new IllegalStateException("Sub2API Chat 返回了非文字内容");String text=Sub2ApiResponsesCodec.bounded(Values.text(message.get("content")),TEXT_LIMIT,"输出正文");Map<String,Object> out=new LinkedHashMap<>();out.put("role","assistant");out.put("content",text);
        String reasoning=Values.text(message.get("reasoning_content"));if(!reasoning.isEmpty())out.put("reasoning_content",Sub2ApiResponsesCodec.bounded(reasoning,TEXT_LIMIT,"推理资料"));
        List<Object> calls=Values.list(message.get("tool_calls"));if(calls.size()>32)throw new IllegalStateException("模型函数数量超过预算");if("tool_calls".equals(finish)&&calls.isEmpty())throw new IllegalStateException("模型函数终态缺少函数项");if("stop".equals(finish)&&!calls.isEmpty())throw new IllegalStateException("模型函数项与终态不一致");
        Set<String> ids=new HashSet<>();for(Object raw:calls){Map<String,Object> call=Values.map(raw);validateTool(call,tools);if(!ids.add(Values.text(call.get("id"))))throw new IllegalStateException("模型函数 id 重复");}if(!calls.isEmpty())out.put("tool_calls",calls);
        if(text.isBlank()&&calls.isEmpty())throw new IllegalStateException("模型未返回文字或函数调用");return out;
    }
    public static StreamTurn accumulator(Prepared prepared,Consumer<String> token){return new StreamTurn(prepared,token);}
    public static final class StreamTurn {
        private final Prepared prepared;private final Consumer<String> token;private final Sub2ApiResponsesCodec.Stream responses;private final StringBuilder content=new StringBuilder(),reasoning=new StringBuilder();private final SortedMap<Integer,ToolDelta> tools=new TreeMap<>();private String finish="";private boolean refusal,done;private long usage=-1;private Result result;
        private StreamTurn(Prepared prepared,Consumer<String> token){this.prepared=prepared;this.token=token;responses="responses".equals(prepared.protocol())?new Sub2ApiResponsesCodec.Stream(prepared.tools(),token):null;}
        public boolean accept(ControlledHttp.SseFrame frame){
            if(responses!=null)return responses.accept(frame);if(done)throw failure("Chat 完成后仍有事件");
            if("[DONE]".equals(frame.data().trim())){try{Map<String,Object> answer=validatedMessage(message(),finish,prepared.tools());result=new Result(answer,usage,usage<0);done=true;return true;}catch(RuntimeException invalid){throw failure(protocolError(invalid));}}
            if(!frame.event().isBlank()&&!Set.of("message","chat.completion.chunk").contains(frame.event()))throw failure("Chat SSE 事件类型无效");
            Map<String,Object> data;try{data=Jsons.map(frame.data());}catch(RuntimeException invalid){throw failure("Chat 事件不是有效 JSON");}if(data.containsKey("error"))throw failure("Chat 上游返回错误");
            List<Object> choices=Values.list(data.get("choices"));long actual=supplierUsage(data,"prompt_tokens","completion_tokens");if(actual>=0&&(choices.isEmpty()||!Values.text(Values.map(choices.get(0)).get("finish_reason")).isBlank()))usage=actual;if(choices.isEmpty())return false;if(choices.size()!=1)throw failure("Chat 流式结果不是唯一 choice");Map<String,Object> choice=Values.map(choices.get(0));if(choice.containsKey("index")&&Values.number(choice.get("index"),-1)!=0)throw failure("Chat choice index 无效");
            boolean finished=!finish.isBlank();String end=Values.text(choice.get("finish_reason"));if(!end.isBlank()){if(finished&&!finish.equals(end))throw failure("Chat 完成原因发生变化");finish=end;}
            Map<String,Object> delta=Values.map(choice.get("delta"));if(delta.get("content")!=null&&!(delta.get("content") instanceof String))throw failure("Chat 返回非文字增量");String part=Values.text(delta.get("content"));if(finished&&(!part.isEmpty()||!Values.list(delta.get("tool_calls")).isEmpty()||!Values.text(delta.get("reasoning_content")).isEmpty()))throw failure("Chat 完成原因后仍返回增量");if(!part.isEmpty()){Sub2ApiResponsesCodec.append(content,part,TEXT_LIMIT,"流式正文");token.accept(part);}Sub2ApiResponsesCodec.append(reasoning,Values.text(delta.get("reasoning_content")),TEXT_LIMIT,"推理资料");if(!Values.text(delta.get("refusal")).isBlank())refusal=true;
            for(Object raw:Values.list(delta.get("tool_calls"))){Map<String,Object> f=Values.map(raw);if(!f.containsKey("index"))throw failure("Chat 工具增量缺少 index");int index=Values.range(f.get("index"),0,0,31);ToolDelta tool=tools.computeIfAbsent(index,k->new ToolDelta());String id=Values.text(f.get("id"));if(!id.isBlank()){Sub2ApiResponsesCodec.bounded(id,200,"工具 id");if(!tool.id.isBlank()&&!tool.id.equals(id))throw failure("Chat 工具 id 变化");tool.id=id;}
                String type=Values.text(f.get("type"));if(!type.isBlank()&&!"function".equals(type))throw failure("Chat 工具类型未批准");Map<String,Object> function=Values.map(f.get("function"));Sub2ApiResponsesCodec.append(tool.name,Values.text(function.get("name")),200,"工具名称");Sub2ApiResponsesCodec.append(tool.arguments,Values.text(function.get("arguments")),65536,"工具参数");}
            return false;
        }
        public Result result(){if(responses!=null)return responses.result();if(!done)throw failure("Chat 未完成");return result;}
        public Result partial(){return responses!=null?responses.partial():result!=null?result:new Result(Map.of("role","assistant","content",content.toString()),usage,usage<0);}
        private ProtocolFailure failure(String reason){return new ProtocolFailure("PROVIDER_UNAVAILABLE:"+reason,partial());}
        private Map<String,Object> message(){Map<String,Object> m=new LinkedHashMap<>();m.put("role","assistant");m.put("content",content.toString());if(!reasoning.isEmpty())m.put("reasoning_content",reasoning.toString());if(refusal)m.put("refusal","refused");List<Map<String,Object>> calls=new ArrayList<>();for(ToolDelta t:tools.values())calls.add(Map.of("id",t.id,"type","function","function",Map.of("name",t.name.toString(),"arguments",t.arguments.toString())));if(!calls.isEmpty())m.put("tool_calls",calls);return m;}
        private static final class ToolDelta {String id="";final StringBuilder name=new StringBuilder(),arguments=new StringBuilder();}
    }
    private static String protocolError(RuntimeException invalid){return invalid instanceof IllegalStateException||invalid instanceof IllegalArgumentException?invalid.getMessage():"Sub2API 模型响应协议无效";}
    public static long supplierUsage(Map<String,Object> response,String inputName,String outputName){Map<String,Object> usage=Values.map(response.get("usage"));Long total=nonnegative(usage.get("total_tokens"));if(total!=null)return total;Long input=nonnegative(usage.get(inputName)),output=nonnegative(usage.get(outputName));if(input==null||output==null)return -1;try{return Math.addExact(input,output);}catch(ArithmeticException overflow){return -1;}}
    private static Long nonnegative(Object raw){if(raw==null)return null;try{long value=new BigDecimal(raw.toString()).longValueExact();return value>=0?value:null;}catch(NumberFormatException|ArithmeticException invalid){return null;}}
    private static void validateSchemas(List<Map<String,Object>> tools){Set<String> names=new HashSet<>();for(var tool:tools){if(!"function".equals(tool.get("type")))throw new IllegalArgumentException("仅允许函数工具");Map<String,Object> function=Values.map(tool.get("function"));String name=Sub2ApiResponsesCodec.required(function,"name",200);if(!names.add(name)||!"object".equals(Values.map(function.get("parameters")).get("type")))throw new IllegalArgumentException("工具名称重复或 schema 无效");}}
    public static void validateTool(Map<String,Object> call,List<Map<String,Object>> tools){
        Sub2ApiResponsesCodec.required(call,"id",200);if(!"function".equals(call.get("type")))throw new IllegalStateException("模型返回未批准工具类型");Map<String,Object> fn=Values.map(call.get("function"));String name=Sub2ApiResponsesCodec.required(fn,"name",200),arguments=Sub2ApiResponsesCodec.required(fn,"arguments",65536);Map<String,Object> schema=null;for(var tool:tools){Map<String,Object> f=Values.map(tool.get("function"));if(name.equals(f.get("name"))){schema=Values.map(f.get("parameters"));break;}}if(schema==null)throw new IllegalStateException("模型返回未注册函数");Map<String,Object> values=Sub2ApiResponsesCodec.parseArguments(arguments);validateValue(values,schema,0);
    }
    private static void validateValue(Object value,Map<String,Object> schema,int depth){
        if(depth>8)throw new IllegalStateException("模型工具参数嵌套超过预算");String type=Values.text(schema.get("type"));boolean valid=switch(type){case "object"->value instanceof Map<?,?>;case "array"->value instanceof List<?>;case "string"->value instanceof String;case "boolean"->value instanceof Boolean;case "number"->value instanceof Number;case "integer"->value instanceof Number&&nonnegativeIntegerType(value);case "null"->value==null;default->false;};if(!valid)throw new IllegalStateException("模型工具参数类型不符合 schema");
        List<Object> allowed=Values.list(schema.get("enum"));if(!allowed.isEmpty()&&!allowed.contains(value))throw new IllegalStateException("模型工具参数枚举无效");
        if(value instanceof Number number){BigDecimal numeric=new BigDecimal(number.toString());if(schema.get("minimum")!=null&&numeric.compareTo(new BigDecimal(schema.get("minimum").toString()))<0)throw new IllegalStateException("模型工具数值小于 schema 下限");if(schema.get("maximum")!=null&&numeric.compareTo(new BigDecimal(schema.get("maximum").toString()))>0)throw new IllegalStateException("模型工具数值超过 schema 上限");}
        if(value instanceof String text){int length=text.codePointCount(0,text.length());if(schema.get("minLength")!=null&&length<Values.number(schema.get("minLength"),0))throw new IllegalStateException("模型工具文字短于 schema 下限");if(schema.get("maxLength")!=null&&length>Values.number(schema.get("maxLength"),Long.MAX_VALUE))throw new IllegalStateException("模型工具文字超过 schema 上限");}
        if(value instanceof Map<?,?> raw){Map<String,Object> object=Values.map(raw),properties=Values.map(schema.get("properties"));if(object.size()>128)throw new IllegalStateException("模型工具参数字段超过预算");for(Object key:Values.list(schema.get("required")))if(!object.containsKey(Values.text(key)))throw new IllegalStateException("模型工具参数缺少必填字段");for(var entry:object.entrySet()){if(!properties.containsKey(entry.getKey())){if(Boolean.FALSE.equals(schema.get("additionalProperties")))throw new IllegalStateException("模型工具参数包含未注册字段");}else validateValue(entry.getValue(),Values.map(properties.get(entry.getKey())),depth+1);}}
        if(value instanceof List<?> list){if(list.size()>1024)throw new IllegalStateException("模型工具数组超过预算");Map<String,Object> items=Values.map(schema.get("items"));if(!items.isEmpty())for(Object item:list)validateValue(item,items,depth+1);}
    }
    private static boolean nonnegativeIntegerType(Object raw){try{new BigDecimal(raw.toString()).longValueExact();return true;}catch(NumberFormatException|ArithmeticException invalid){return false;}}
}
