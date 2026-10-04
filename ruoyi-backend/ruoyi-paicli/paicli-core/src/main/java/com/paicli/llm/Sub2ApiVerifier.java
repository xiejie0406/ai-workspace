package com.paicli.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;

/** 两次有预算的合成请求，工具仅在内存回显，绝不调用实际业务工具。 */
public final class Sub2ApiVerifier {
    private Sub2ApiVerifier() {}
    public static Map<String,Object> verify(LlmClient client) throws IOException {
        var parameters=mapper.readTree("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"enum\":[\"probe\"]}},\"required\":[\"value\"],\"additionalProperties\":false}");
        var tool=new LlmClient.Tool("paicli_probe_echo","仅在验证器内存中回显probe，不执行业务工具",parameters);
        List<LlmClient.Message> history=new ArrayList<>(List.of(LlmClient.Message.system(
                "This is a compatibility probe. First call paicli_probe_echo with value probe exactly once. After the tool result, reply with exactly probe and do not call another tool."),LlmClient.Message.user("Run the probe.")));
        LlmClient.ChatResponse first=client.chat(history,List.of(tool));
        if(!first.usagePresent()||first.toolCalls()==null||first.toolCalls().size()!=1)throw new IOException("Sub2API工具/用量验证失败");
        LlmClient.ToolCall call=first.toolCalls().get(0);var args=mapper.readTree(call.function().arguments());
        if(!tool.name().equals(call.function().name())||args==null||args.size()!=1||!"probe".equals(args.path("value").asText()))throw new IOException("Sub2API工具参数验证失败");
        history.add(LlmClient.Message.assistant(first));history.add(LlmClient.Message.tool(call.id(),"probe"));
        AtomicBoolean streamed=new AtomicBoolean();
        LlmClient.ChatResponse second=client.chat(history,List.of(tool),new LlmClient.StreamListener(){
            public void onContentDelta(String delta){if(delta!=null&&!delta.isEmpty())streamed.set(true);}
        });
        if(!second.usagePresent()||second.hasToolCalls()||!"probe".equals(second.content().trim())||!streamed.get())throw new IOException("Sub2API文本/流式/工具回灌验证失败");
        return Map.of("text",true,"tools",true,"stream",true,"usage",true,"model",client.getModelName(),"calls",2);
    }
}
