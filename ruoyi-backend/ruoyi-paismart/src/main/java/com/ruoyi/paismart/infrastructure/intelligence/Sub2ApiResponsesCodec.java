package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import java.util.*;
import java.util.function.Consumer;

/** 无状态 Responses 编解码；协议项只在服务端回合及调用账本保留。 */
public final class Sub2ApiResponsesCodec {
    public static final String ITEMS="_responsesItems";
    public static final String USAGE_DIAGNOSTICS="_responsesUsage";
    private static final int TEXT_LIMIT=2*1024*1024,ITEM_LIMIT=524288;
    private Sub2ApiResponsesCodec() {}

    public static Map<String,Object> request(String model,List<Map<String,Object>> messages,List<Map<String,Object>> tools,int max,boolean stream){
        List<Map<String,Object>> input=new ArrayList<>();Set<String> calls=new HashSet<>(),results=new HashSet<>();
        for(Map<String,Object> message:messages){
            String role=Values.text(message.get("role"));
            if("tool".equals(role)){
                String id=required(message,"tool_call_id",200);if(!calls.contains(id)||!results.add(id))throw new IllegalArgumentException("Responses 工具结果关联无效或重复");
                input.add(Map.of("type","function_call_output","call_id",id,"output",bounded(Values.text(message.get("content")),TEXT_LIMIT,"工具结果")));continue;
            }
            if(!Set.of("system","developer","user","assistant").contains(role))throw new IllegalArgumentException("Responses 消息角色无效");
            if(message.containsKey(ITEMS)){
                if(!"assistant".equals(role))throw new IllegalArgumentException("Responses 协议项仅属于服务端 assistant 回合");
                List<Object> items=Values.list(message.get(ITEMS));if(items.isEmpty()||items.size()>64)throw new IllegalArgumentException("Responses 回传项无效或超过预算");
                for(Object raw:items){Map<String,Object> item=replayItem(Values.map(raw));if("function_call".equals(item.get("type"))&&!calls.add(Values.text(item.get("call_id"))))throw new IllegalArgumentException("Responses 工具 call_id 重复");input.add(item);}continue;
            }
            String text=bounded(Values.text(message.get("content")),TEXT_LIMIT,"输入正文");
            if(!text.isEmpty())input.add(Map.of("role",role,"content",text));
            for(Object raw:Values.list(message.get("tool_calls"))){
                if(!"assistant".equals(role))throw new IllegalArgumentException("函数调用必须属于 assistant");Map<String,Object> call=Values.map(raw),f=Values.map(call.get("function"));String id=required(call,"id",200);
                if(!calls.add(id)||!"function".equals(call.get("type")))throw new IllegalArgumentException("Responses 函数调用类型或关联无效");
                input.add(Map.of("type","function_call","call_id",id,"name",required(f,"name",200),"arguments",required(f,"arguments",65536)));
            }
        }
        if(!calls.equals(results))throw new IllegalArgumentException("Responses 历史函数调用缺少对应结果");
        if(input.size()>256||Jsons.write(input).length()>4*1024*1024)throw new IllegalArgumentException("Responses 输入超过预算");
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("input",input);body.put("max_output_tokens",max);body.put("store",false);body.put("include",List.of("reasoning.encrypted_content"));if(stream)body.put("stream",true);
        if(!tools.isEmpty()){
            List<Map<String,Object>> functions=new ArrayList<>();for(var tool:tools){if(!"function".equals(tool.get("type")))throw new IllegalArgumentException("Responses 仅允许已注册函数工具");Map<String,Object> f=Values.map(tool.get("function"));functions.add(Map.of("type","function","name",required(f,"name",200),"description",Values.text(f.get("description")),"parameters",Values.map(f.get("parameters")),"strict",false));}body.put("tools",functions);
        }
        return body;
    }

    public static Sub2ApiTextAdapter.Result decode(Map<String,Object> response,List<Map<String,Object>> tools){
        long tokens=Sub2ApiTextAdapter.supplierUsage(response,"input_tokens","output_tokens");Map<String,Object> partial=new LinkedHashMap<>();partial.put("role","assistant");partial.put("content",partialText(response));partial.put(USAGE_DIAGNOSTICS,usageDiagnostics(response));
        try{
            String status=Values.text(response.get("status"));if(!"completed".equals(status))throw new IllegalStateException("PROVIDER_UNAVAILABLE:Responses 未完整完成（"+safeStatus(status)+"）");
            List<Object> output=Values.list(response.get("output"));if(output.isEmpty()||output.size()>64)throw new IllegalStateException("PROVIDER_UNAVAILABLE:Responses 输出为空或超过预算");
            StringBuilder content=new StringBuilder();List<Map<String,Object>> calls=new ArrayList<>(),items=new ArrayList<>();Set<String> ids=new HashSet<>();boolean refusal=false;
            for(Object raw:output){Map<String,Object> item=Values.map(raw);String type=Values.text(item.get("type"));
                if("message".equals(type)){
                    if(!"assistant".equals(item.get("role")))throw new IllegalStateException("Responses 输出角色无效");requireCompleteItem(item);
                    for(Object part:Values.list(item.get("content"))){Map<String,Object> p=Values.map(part);if("output_text".equals(p.get("type"))){if(!(p.get("text") instanceof String))throw new IllegalStateException("Responses 输出文字字段无效");append(content,Values.text(p.get("text")),TEXT_LIMIT,"输出正文");}else if("refusal".equals(p.get("type")))refusal=true;else throw new IllegalStateException("Responses 返回了未批准的非文字内容");}
                    partial.put("content",content.toString());items.add(replayItem(item));
                }else if("function_call".equals(type)){
                    requireCompleteItem(item);String id=required(item,"call_id",200);required(item,"id",200);if(!ids.add(id))throw new IllegalStateException("Responses 工具 call_id 重复");
                    Map<String,Object> call=Map.of("id",id,"type","function","function",Map.of("name",required(item,"name",200),"arguments",required(item,"arguments",65536)));Sub2ApiTextAdapter.validateTool(call,tools);calls.add(call);items.add(replayItem(item));
                }else if("reasoning".equals(type)){items.add(replayItem(item));}
                else throw new IllegalStateException("Responses 返回了未批准的工具或输出类型");
            }
            if(refusal)throw new IllegalStateException("PROVIDER_UNAVAILABLE:模型拒绝回答");
            if(content.isEmpty()&&calls.isEmpty())throw new IllegalStateException("PROVIDER_UNAVAILABLE:模型没有返回文字或函数调用");
            if(Jsons.write(items).length()>TEXT_LIMIT)throw new IllegalStateException("Responses 协议资料超过预算");
            Map<String,Object> message=new LinkedHashMap<>();message.put("role","assistant");message.put("content",content.toString());message.put(ITEMS,items);message.put(USAGE_DIAGNOSTICS,partial.get(USAGE_DIAGNOSTICS));if(!calls.isEmpty())message.put("tool_calls",calls);
            return new Sub2ApiTextAdapter.Result(message,tokens,tokens<0);
        }catch(RuntimeException failure){throw new Sub2ApiTextAdapter.ProtocolFailure(safeFailure(failure),new Sub2ApiTextAdapter.Result(partial,tokens,tokens<0));}
    }

    /** 只留用量白名单；异常数字也保留，不改变原供应商总数选择及计费语义。 */
    private static Map<String,Object> usageDiagnostics(Map<String,Object> response){
        Map<String,Object> diagnostic=new LinkedHashMap<>(),usage=new LinkedHashMap<>(),invalid=new LinkedHashMap<>();
        diagnostic.put("usagePresent",response.containsKey("usage"));Map<String,Object> source=Values.map(response.get("usage"));
        for(String field:List.of("input_tokens","output_tokens","total_tokens"))copyUsageNumber(source,usage,invalid,field);
        Map<String,Object> details=new LinkedHashMap<>();copyUsageNumber(Values.map(source.get("output_tokens_details")),details,invalid,"reasoning_tokens");
        if(!details.isEmpty())usage.put("output_tokens_details",details);
        diagnostic.put("usage",usage);if(!invalid.isEmpty())diagnostic.put("invalidFields",invalid);
        if(response.containsKey("usage")&&!(response.get("usage") instanceof Map))diagnostic.put("invalidUsageType",response.get("usage")==null?"null":"non_object");
        return diagnostic;
    }
    private static void copyUsageNumber(Map<String,Object> source,Map<String,Object> target,Map<String,Object> invalid,String field){
        if(!source.containsKey(field))return;Object value=source.get(field);
        boolean finite=!(value instanceof Double d&&!Double.isFinite(d))&&!(value instanceof Float f&&!Float.isFinite(f));
        if(value==null||(value instanceof Number&&finite)||(value instanceof String s&&s.length()<=128&&s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")))target.put(field,value);
        else invalid.put(field,value instanceof String?"non_numeric_string":"non_numeric_type");
    }

    private static String partialText(Map<String,Object> response){StringBuilder out=new StringBuilder();for(Object raw:Values.list(response.get("output"))){Map<String,Object> item=Values.map(raw);if(!"message".equals(item.get("type")))continue;for(Object part:Values.list(item.get("content"))){Map<String,Object> content=Values.map(part);if("output_text".equals(content.get("type"))&&content.get("text") instanceof String text){int remaining=TEXT_LIMIT-out.length();if(remaining<=0)return out.toString();out.append(text,0,Math.min(remaining,text.length()));}}}return out.toString();}

    /** 只回传 Responses 认可的最小字段，不携带上游内置工具或可执行内容。 */
    private static Map<String,Object> replayItem(Map<String,Object> item){
        String type=Values.text(item.get("type"));Map<String,Object> result=new LinkedHashMap<>();result.put("type",type);String id=Values.text(item.get("id"));if(!id.isBlank())result.put("id",bounded(id,200,"协议项 id"));
        if("function_call".equals(type)){result.put("call_id",required(item,"call_id",200));result.put("name",required(item,"name",200));String arguments=required(item,"arguments",65536);parseArguments(arguments);result.put("arguments",arguments);}
        else if("message".equals(type)){
            if(!"assistant".equals(item.get("role")))throw new IllegalStateException("Responses 回传角色无效");result.put("role","assistant");result.put("status","completed");List<Map<String,Object>> parts=new ArrayList<>();
            for(Object raw:Values.list(item.get("content"))){Map<String,Object> p=Values.map(raw);if(!"output_text".equals(p.get("type")))throw new IllegalStateException("Responses 回传内容不是文字");parts.add(Map.of("type","output_text","text",bounded(Values.text(p.get("text")),TEXT_LIMIT,"回传正文"),"annotations",List.of()));}result.put("content",parts);
        }else if("reasoning".equals(type)){
            String encrypted=required(item,"encrypted_content",ITEM_LIMIT);result.put("encrypted_content",encrypted);result.put("summary",List.of());
        }else throw new IllegalStateException("Responses 回传项类型未批准");
        if(Jsons.write(result).length()>TEXT_LIMIT)throw new IllegalStateException("Responses 回传项超过预算");return result;
    }
    private static void requireCompleteItem(Map<String,Object> item){String status=Values.text(item.get("status"));if(!status.isBlank()&&!"completed".equals(status))throw new IllegalStateException("Responses 输出项未完成");}
    static Map<String,Object> parseArguments(String value){try{return Jsons.map(value);}catch(RuntimeException malformed){throw new IllegalStateException("模型工具参数不是完整 JSON 对象");}}
    private static String safeStatus(String status){return Set.of("failed","incomplete","in_progress","queued","cancelled","completed").contains(status)?status:"unknown";}
    private static String safeFailure(RuntimeException e){return e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"PROVIDER_UNAVAILABLE:Responses 协议无效";}
    static String required(Map<String,Object> item,String key,int limit){Object value=item.get(key);if(!(value instanceof String text)||text.isBlank()||text.length()>limit)throw new IllegalStateException("模型协议字段 "+key+" 缺失或超过预算");return text;}
    static String bounded(String value,int limit,String label){if(value.length()>limit)throw new IllegalStateException(label+"超过预算");return value;}
    static void append(StringBuilder target,String part,int limit,String label){if((long)target.length()+part.length()>limit)throw new IllegalStateException(label+"超过预算");target.append(part);}

    public static final class Stream {
        private final List<Map<String,Object>> tools;private final Consumer<String> token;private final StringBuilder text=new StringBuilder();private final Map<Integer,FunctionState> functions=new TreeMap<>();private final Map<Integer,String> itemIds=new HashMap<>();private final Map<String,StringBuilder> textParts=new HashMap<>();private String responseId="";private long tokens=-1;private boolean completed=false,refusal=false;private Sub2ApiTextAdapter.Result result;private Map<String,Object> diagnostics=usageDiagnostics(Map.of());
        public Stream(List<Map<String,Object>> tools,Consumer<String> token){this.tools=tools;this.token=token;}
        public boolean accept(ControlledHttp.SseFrame frame){
            if(completed)throw failure("Responses 终态后仍有事件");if("[DONE]".equals(frame.data().trim()))throw failure("Responses 缺少语义完成事件");Map<String,Object> data;try{data=Jsons.map(frame.data());}catch(RuntimeException invalid){throw failure("Responses 事件不是有效 JSON");}
            String type=Values.text(data.get("type"));if(type.isBlank()||(!frame.event().isBlank()&&!frame.event().equals(type)))throw failure("Responses SSE 事件类型不一致");
            String eventId=Values.text(data.get("response_id"));if(!eventId.isBlank())bindResponse(eventId);
            Map<String,Object> response=Values.map(data.get("response"));if(!response.isEmpty()){String id=Values.text(response.get("id"));if(!id.isBlank())bindResponse(id);if(Set.of("response.completed","response.failed","response.incomplete").contains(type)){diagnostics=usageDiagnostics(response);long usage=Sub2ApiTextAdapter.supplierUsage(response,"input_tokens","output_tokens");if(usage>=0)tokens=usage;}}
            if("response.output_text.delta".equals(type)){
                int index=index(data);checkItem(data,index);int part=Values.range(data.get("content_index"),0,0,63);String delta=requiredDelta(data,"delta");String key=index+":"+part;append(textParts.computeIfAbsent(key,k->new StringBuilder()),delta,TEXT_LIMIT,"正文片段");append(text,delta,TEXT_LIMIT,"流式正文");if(!delta.isEmpty())token.accept(delta);
            }else if("response.output_text.done".equals(type)){
                int index=index(data);checkItem(data,index);String key=index+":"+Values.range(data.get("content_index"),0,0,63);String full=requiredDelta(data,"text");String emitted=textParts.getOrDefault(key,new StringBuilder()).toString();if(!full.startsWith(emitted))throw failure("Responses 正文完成资料与增量不一致");String suffix=full.substring(emitted.length());if(!suffix.isEmpty()){append(text,suffix,TEXT_LIMIT,"流式正文");token.accept(suffix);}textParts.put(key,new StringBuilder(full));
            }else if("response.output_item.added".equals(type)||"response.output_item.done".equals(type)){
                Map<String,Object> item=Values.map(data.get("item"));String itemType=Values.text(item.get("type"));int outputIndex=index(data);String id=required(item,"id",200),prior=itemIds.putIfAbsent(outputIndex,id);if(prior!=null&&!prior.equals(id))throw failure("Responses 输出项 id 变化");if("function_call".equals(itemType)){FunctionState f=functions.computeIfAbsent(outputIndex,k->new FunctionState());f.bind(item);if("response.output_item.done".equals(type)){f.finish(item);}}
                else if(!Set.of("message","reasoning").contains(itemType))throw failure("Responses 返回未批准的输出类型");
            }else if("response.function_call_arguments.delta".equals(type)){
                FunctionState f=function(data);if(f.done)throw failure("Responses 函数完成后仍有参数增量");append(f.arguments,requiredDelta(data,"delta"),65536,"工具参数");
            }else if("response.function_call_arguments.done".equals(type)){
                FunctionState f=function(data);String full=required(data,"arguments",65536);f.finishArguments(full);f.done=true;
            }else if("response.refusal.delta".equals(type)||"response.refusal.done".equals(type)){refusal=true;}
            else if("response.completed".equals(type)){
                if(refusal)throw failure("模型拒绝回答");Sub2ApiTextAdapter.Result decoded;try{decoded=decode(response,tools);}catch(Sub2ApiTextAdapter.ProtocolFailure invalid){throw failure(invalid.getMessage());}String full=Values.text(decoded.message().get("content"));if(!full.startsWith(text.toString()))throw failure("Responses 最终正文与流式增量不一致");
                Map<String,Map<String,Object>> finalCalls=new HashMap<>();for(Object raw:Values.list(decoded.message().get("tool_calls"))){Map<String,Object> call=Values.map(raw);finalCalls.put(Values.text(call.get("id")),call);}
                Map<String,String> finalIds=new HashMap<>();for(Object raw:Values.list(decoded.message().get(ITEMS))){Map<String,Object> item=Values.map(raw);if("function_call".equals(item.get("type")))finalIds.put(Values.text(item.get("call_id")),Values.text(item.get("id")));}
                List<Object> finalItems=Values.list(decoded.message().get(ITEMS));for(var observed:itemIds.entrySet())if(observed.getKey()>=finalItems.size()||!observed.getValue().equals(Values.text(Values.map(finalItems.get(observed.getKey())).get("id"))))throw failure("Responses 最终输出项顺序或 id 不一致");
                for(FunctionState f:functions.values()){Map<String,Object> call=finalCalls.get(f.callId);if(call==null||!f.id.equals(finalIds.get(f.callId)))throw failure("Responses 完成事件遗漏或变更已生成函数");Map<String,Object> fn=Values.map(call.get("function"));String arguments=Values.text(fn.get("arguments"));if(!f.name.equals(fn.get("name"))||!arguments.startsWith(f.arguments.toString())||(f.done&&!arguments.equals(f.arguments.toString())))throw failure("Responses 最终工具参数与增量不一致");}
                String suffix=full.substring(text.length());if(!suffix.isEmpty()){append(text,suffix,TEXT_LIMIT,"流式正文");token.accept(suffix);}result=decoded;tokens=decoded.tokens();completed=true;return true;
            }else if(Set.of("response.failed","response.incomplete","error").contains(type)){throw failure("Responses 未完整完成（"+type+"）");}
            else if(!Set.of("response.created","response.in_progress","response.content_part.added","response.content_part.done","response.output_text.annotation.added","response.reasoning_summary_part.added","response.reasoning_summary_part.done","response.reasoning_summary_text.delta","response.reasoning_summary_text.done","response.reasoning_text.delta","response.reasoning_text.done").contains(type))throw failure("Responses 事件类型未批准");
            return false;
        }
        public Sub2ApiTextAdapter.Result result(){if(!completed)throw failure("Responses 未完成");return result;}
        public Sub2ApiTextAdapter.Result partial(){if(result!=null)return result;Map<String,Object> message=new LinkedHashMap<>(Map.of("role","assistant","content",text.toString()));message.put(USAGE_DIAGNOSTICS,diagnostics);return new Sub2ApiTextAdapter.Result(message,tokens,tokens<0);}
        private Sub2ApiTextAdapter.ProtocolFailure failure(String message){return new Sub2ApiTextAdapter.ProtocolFailure("PROVIDER_UNAVAILABLE:"+message,partial());}
        private int index(Map<String,Object> data){if(!data.containsKey("output_index"))throw failure("Responses 输出事件缺少 index");return Values.range(data.get("output_index"),0,0,63);}
        private void bindResponse(String id){bounded(id,200,"response id");if(!responseId.isBlank()&&!responseId.equals(id))throw failure("Responses 同次请求 response id 变化");responseId=id;}
        private void checkItem(Map<String,Object> data,int index){if(!required(data,"item_id",200).equals(itemIds.get(index)))throw failure("Responses 正文事件 item_id 未注册或变化");}
        private FunctionState function(Map<String,Object> data){FunctionState f=functions.get(index(data));if(f==null)throw failure("Responses 参数事件缺少函数项");String id=required(data,"item_id",200);if(!f.id.equals(id))throw failure("Responses 参数 item_id 不一致");return f;}
        private String requiredDelta(Map<String,Object> data,String key){Object value=data.get(key);if(!(value instanceof String s))throw failure("Responses 文字事件缺少字符串字段");return bounded(s,TEXT_LIMIT,"正文事件");}
        private static final class FunctionState {
            String id="",callId="",name="";StringBuilder arguments=new StringBuilder();boolean done=false;
            void bind(Map<String,Object> item){String itemId=required(item,"id",200),call=required(item,"call_id",200),function=required(item,"name",200);if((!id.isEmpty()&&!id.equals(itemId))||(!callId.isEmpty()&&!callId.equals(call))||(!name.isEmpty()&&!name.equals(function)))throw new IllegalStateException("Responses 工具标识发生变化");id=itemId;callId=call;name=function;String initial=Values.text(item.get("arguments"));if(arguments.isEmpty()&&!done&&!initial.isEmpty())append(arguments,initial,65536,"工具参数");}
            void finishArguments(String full){if(!full.startsWith(arguments.toString())||(done&&!full.equals(arguments.toString())))throw new IllegalStateException("Responses 工具参数完成资料不一致");parseArguments(full);arguments=new StringBuilder(full);}
            void finish(Map<String,Object> item){finishArguments(required(item,"arguments",65536));done=true;}
        }
    }
}
