package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只共享现有凭据/profile/HTTP设施，独立消费 Responses 语义事件。 */
public final class Sub2ApiResponsesClient extends AbstractOpenAiCompatibleClient {
    private static final int MAX_EVENT_CHARS=1_048_576,MAX_RESPONSE_BYTES=8_388_608;
    private final String key,model,url;
    private final OkHttpClient http=SHARED_HTTP_CLIENT.newBuilder().dispatcher(new Dispatcher()).build();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final ThreadLocal<Sub2ApiRequestException.TokenUsage> reportedUsage=new ThreadLocal<>();

    public Sub2ApiResponsesClient(String key,String model,String root) {
        this(key,model,root,null);
    }
    public Sub2ApiResponsesClient(String key,String model,String root,String approvedHttpRoot) {
        if (!ModelCatalog.validModelId(model)) throw new IllegalArgumentException("Sub2API 缺少有效的精确模型 ID");
        this.key=key;this.model=model;this.url=Sub2ApiEndpoint.endpoint(root,"responses",approvedHttpRoot);
    }
    protected String getApiUrl(){return url;} protected String getModel(){return model;} protected String getApiKey(){return key;}
    public String getModelName(){return model;} public String getProviderName(){return "sub2api";}
    public boolean supportsImageInput(){return modelProfile()!=null && Boolean.TRUE.equals(modelProfile().imageInput());} protected OkHttpClient httpClient(){return http;}
    @Override public void cancelInFlightCalls(){cancelled.set(true);http.dispatcher().cancelAll();}

    @Override public ChatResponse chat(List<Message> messages,List<Tool> tools,StreamListener listener) throws IOException {
        reportedUsage.remove();
        try {
            return execute(messages,tools,listener);
        } catch(IOException | RuntimeException failure) {
            throw new Sub2ApiRequestException(reportedUsage.get(),cancelled.get() || com.paicli.runtime.CancellationContext.isCancelled() || Thread.currentThread().isInterrupted());
        } finally { reportedUsage.remove(); }
    }

    private ChatResponse execute(List<Message> messages,List<Tool> tools,StreamListener listener) throws IOException {
        if(cancelled.get())throw new IOException("Sub2API 请求已取消");
        var body=Sub2ApiResponsesCodec.request(model,messages,tools,modelProfile());
        Request request=new Request.Builder().url(url).header("Authorization","Bearer "+effectiveApiKey())
                .header("Accept","text/event-stream").post(RequestBody.create(body.toString(),MediaType.get("application/json"))).build();
        StreamListener sink=listener==null?StreamListener.NO_OP:listener;
        try(var watch=new Sub2ApiCancellation(this::cancelInFlightCalls);Response response=http.newCall(request).execute()){
            if(!response.isSuccessful())throw new IOException("Sub2API 请求失败，HTTP "+response.code());
            if(response.body()==null)throw new IOException("Sub2API 返回空响应体");
            if(cancelled.get())throw new IOException("Sub2API 请求已取消");
            String type=response.header("Content-Type","");
            if(type.toLowerCase(java.util.Locale.ROOT).contains("application/json")){
                byte[] bytes=response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES+1);
                if(bytes.length>MAX_RESPONSE_BYTES)throw new IOException("Sub2API 响应超过限制");
                JsonNode root=mapper.readTree(bytes);rememberUsage(root==null?null:root.get("usage"));
                ChatResponse result=Sub2ApiResponsesCodec.response(root);
                if(cancelled.get())throw new IOException("Sub2API 请求已取消");
                return result;
            }
            if(!type.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream"))throw new IOException("Sub2API 响应不是 Responses JSON/SSE");
            StringBuilder data=new StringBuilder(),emitted=new StringBuilder();long total=0;
            var source=response.body().source();
            while(!source.exhausted()){
                if(cancelled.get())throw new IOException("Sub2API 请求已取消");
                // 单行和总响应均有界，避免不可信中转无限增长。
                String line=source.readUtf8LineStrict(MAX_EVENT_CHARS);total+=line.length();
                if(total>MAX_RESPONSE_BYTES)throw new IOException("Sub2API 流响应超过限制");
                if(line.isEmpty()){
                    if(!data.isEmpty()){
                        ChatResponse result=event(data.toString(),sink,emitted);data.setLength(0);if(result!=null){if(cancelled.get())throw new IOException("Sub2API 请求已取消");return result;}
                    }
                }else if(line.startsWith("data:")){
                    if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());
                    if(data.length()>MAX_EVENT_CHARS)throw new IOException("Sub2API SSE事件超过限制");
                }
            }
            if(!data.isEmpty()){ChatResponse result=event(data.toString(),sink,emitted);if(result!=null){if(cancelled.get())throw new IOException("Sub2API 请求已取消");return result;}}
            throw new IOException("Sub2API 流在 response.completed 前中断，结果未知");
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IOException("Sub2API 返回无效的协议 JSON");}
    }

    private ChatResponse event(String data,StreamListener sink,StringBuilder emitted)throws IOException{
        if(data.equals("[DONE]"))throw new IOException("Sub2API Responses 缺少真实完成事件");
        JsonNode event=mapper.readTree(data);if(event==null)throw new IOException("Sub2API SSE空事件");
        if(event.path("response").isObject())rememberUsage(event.path("response").get("usage"));
        return switch(event.path("type").asText()){
            case "response.output_text.delta"->{String delta=event.path("delta").asText();emitted.append(delta);sink.onContentDelta(delta);yield null;}
            case "response.reasoning_summary_text.delta"->{sink.onReasoningDelta(event.path("delta").asText());yield null;}
            case "response.completed"->{ChatResponse result=Sub2ApiResponsesCodec.response(event.path("response"));
                if(!emitted.isEmpty()&&!emitted.toString().equals(result.content()))throw new IOException("Sub2API完成正文与流式增量不一致");yield result;}
            case "error","response.failed","response.incomplete","response.refusal.delta","response.refusal.done"->throw new IOException("Sub2API Responses 失败、拒绝或截断");
            default->null;
        };
    }
    private void rememberUsage(JsonNode usage)throws IOException{
        var known=Sub2ApiResponsesCodec.tokenUsage(usage);if(known!=null)reportedUsage.set(known);
    }
}
