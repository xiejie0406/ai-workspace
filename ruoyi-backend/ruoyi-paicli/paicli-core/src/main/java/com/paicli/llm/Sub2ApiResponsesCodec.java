package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;

/** 独立 Responses 消息/终态转换，不将其事件交给 Chat Completions decoder。 */
final class Sub2ApiResponsesCodec {
    private Sub2ApiResponsesCodec() {}

    static ObjectNode request(String model, List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                              ModelProfile profile) throws IOException {
        ObjectNode body=mapper.createObjectNode(); body.put("model",model); body.put("stream",true); body.put("store",false);
        body.putArray("include").add("reasoning.encrypted_content");
        ArrayNode input=body.putArray("input");
        for (LlmClient.Message message : messages) {
            if (message.hasImageContent()) throw new IOException("Sub2API 本期仅支持文本，请移除图片附件后再发送");
            if (message.responseItems()!=null && !message.responseItems().isEmpty()) {
                if (!"assistant".equals(message.role())) throw new IOException("Responses 历史协议项角色非法");
                for (JsonNode item : message.responseItems()) { validateItem(item); input.add(item.deepCopy()); }
                continue;
            }
            if ("tool".equals(message.role())) {
                if (message.toolCallId()==null || message.toolCallId().isBlank()) throw new IOException("工具回执缺少 call_id");
                input.addObject().put("type","function_call_output").put("call_id",message.toolCallId()).put("output",message.content()==null?"":message.content());
                continue;
            }
            if (!List.of("system","developer","user","assistant").contains(message.role())) throw new IOException("Responses 消息角色非法");
            if (message.content()!=null && !message.content().isEmpty()) {
                ObjectNode text=input.addObject().put("role",message.role());
                text.putArray("content").addObject().put("type","assistant".equals(message.role())?"output_text":"input_text").put("text",message.content());
            }
            if (message.toolCalls()!=null) for (LlmClient.ToolCall call : message.toolCalls()) {
                input.addObject().put("type","function_call").put("call_id",call.id()).put("name",call.function().name()).put("arguments",call.function().arguments());
            }
        }
        if (tools!=null && !tools.isEmpty()) {
            ArrayNode schema=body.putArray("tools");
            for (LlmClient.Tool tool : tools) {
                ObjectNode function=schema.addObject().put("type","function").put("name",tool.name()).put("description",tool.description()).put("strict",false);
                function.set("parameters",tool.parameters());
            }
        }
        if (profile!=null && profile.reasoningEffort()!=null) body.putObject("reasoning").put("effort",profile.reasoningEffort());
        Integer limit=LlmRequestLimit.current();
        if (profile!=null && profile.maxOutputTokens()!=null) limit=limit==null?profile.maxOutputTokens():Math.min(limit,profile.maxOutputTokens());
        if (limit!=null) body.put("max_output_tokens",limit);
        return body;
    }

    static LlmClient.ChatResponse response(JsonNode root) throws IOException {
        if (!"completed".equals(root.path("status").asText())) throw new IOException("Sub2API Responses 未完成、已拒绝或已截断");
        if (!root.path("output").isArray()) throw new IOException("Sub2API Responses 缺少 output");
        StringBuilder content=new StringBuilder(),reasoning=new StringBuilder();
        List<LlmClient.ToolCall> calls=new ArrayList<>(); List<JsonNode> items=new ArrayList<>(); HashSet<String> ids=new HashSet<>();
        for (JsonNode item : root.path("output")) {
            validateItem(item);
            if (item.has("status") && !"completed".equals(item.path("status").asText())) throw new IOException("Sub2API 输出项未完成");
            String type=item.path("type").asText();
            if (type.equals("message")) {
                for (JsonNode part : item.path("content")) {
                    if (!"output_text".equals(part.path("type").asText())) throw new IOException("Sub2API 返回拒绝或非文本内容");
                    content.append(part.path("text").asText());
                }
            } else if (type.equals("function_call")) {
                String id=item.path("call_id").asText(),name=item.path("name").asText(),arguments=item.path("arguments").asText();
                JsonNode parsed=mapper.readTree(arguments);
                if (id.isBlank() || name.isBlank() || !ids.add(id) || parsed==null || !parsed.isObject()) throw new IOException("Sub2API 工具调用参数或标识非法");
                calls.add(new LlmClient.ToolCall(id,new LlmClient.ToolCall.Function(name,arguments)));
            } else if (type.equals("reasoning")) {
                if (item.path("encrypted_content").asText().isBlank()) throw new IOException("Sub2API 推理项缺少可回传的加密内容");
                for (JsonNode summary : item.path("summary")) reasoning.append(summary.path("text").asText());
            }
            items.add(item.deepCopy());
        }
        if (content.isEmpty() && calls.isEmpty()) throw new IOException("Sub2API Responses 返回空文本结果");
        JsonNode usage=root.path("usage");
        boolean known=counter(usage.get("input_tokens")) && counter(usage.get("output_tokens"));
        if (!usage.isMissingNode() && !usage.isNull() && !known) throw new IOException("Sub2API Responses 用量字段非法或不完整");
        int cached=0;
        if (known && usage.path("input_tokens_details").has("cached_tokens")) {
            JsonNode value=usage.path("input_tokens_details").get("cached_tokens");
            if (!counter(value) || value.intValue()>usage.path("input_tokens").intValue()) throw new IOException("Sub2API 缓存用量非法");
            cached=value.intValue();
        }
        return new LlmClient.ChatResponse("assistant",content.toString(),reasoning.toString(),List.copyOf(calls),
                known?usage.path("input_tokens").intValue():0,known?usage.path("output_tokens").intValue():0,cached,
                root.path("model").isTextual()?root.path("model").asText():null,known,List.copyOf(items));
    }

    private static boolean counter(JsonNode value) { return value!=null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=0; }
    private static void validateItem(JsonNode item) throws IOException {
        if (!item.isObject() || !List.of("message","function_call","reasoning").contains(item.path("type").asText()))
            throw new IOException("Sub2API Responses 返回未支持的输出项");
    }
}
