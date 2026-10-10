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
            if (message == null) throw new IOException("Responses 消息不能为空");
            if (message.hasContentParts()) validateContentParts(message, profile);
            if (message.responseItems()!=null && !message.responseItems().isEmpty()) {
                if (!"assistant".equals(message.role())) throw new IOException("Responses 历史协议项角色非法");
                if (message.hasContentParts()) throw new IOException("Responses 历史协议项不能混合内容块");
                for (JsonNode item : message.responseItems()) { validateItem(item); input.add(item.deepCopy()); }
                continue;
            }
            if ("tool".equals(message.role())) {
                if (message.toolCallId()==null || message.toolCallId().isBlank()) throw new IOException("工具回执缺少 call_id");
                input.addObject().put("type","function_call_output").put("call_id",message.toolCallId()).put("output",message.content()==null?"":message.content());
                continue;
            }
            if (!List.of("system","developer","user","assistant").contains(message.role())) throw new IOException("Responses 消息角色非法");
            if (message.hasContentParts()) {
                ObjectNode item=input.addObject().put("role",message.role());
                ArrayNode parts=item.putArray("content");
                for (LlmClient.ContentPart part : message.contentParts()) {
                    if (part.isText()) {
                        parts.addObject().put("type","assistant".equals(message.role())?"output_text":"input_text").put("text",part.text());
                    } else {
                        parts.addObject().put("type","input_image").put("image_url","data:"+part.mimeType()+";base64,"+part.imageBase64());
                    }
                }
            } else if (message.content()!=null && !message.content().isEmpty()) {
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

    /** 图片仅从已授权内容块传输，拒绝URL、文件路径、伪造格式及未知能力。 */
    private static void validateContentParts(LlmClient.Message message, ModelProfile profile) throws IOException {
        int images=0;
        for (LlmClient.ContentPart part : message.contentParts()) {
            if (part==null) throw new IOException("Responses 内容块不能为空");
            if (part.isText()) {
                if (part.text()==null || part.imageBase64()!=null || part.imageUrl()!=null)
                    throw new IOException("Responses 文字内容块非法");
                continue;
            }
            if (!"image_base64".equals(part.type())) throw new IOException("Responses 不支持该内容块或远程图片引用");
            if (!"user".equals(message.role())) throw new IOException("Responses 图片仅接受用户输入角色");
            if (profile==null || !Boolean.TRUE.equals(profile.imageInput())) throw new IOException("当前模型未声明图片能力，请换模型或删除附件");
            if (++images>10) throw new IOException("Responses 单条消息最多10张图片");
            if (!List.of("image/png","image/jpeg").contains(part.mimeType()) || part.text()!=null || part.imageUrl()!=null)
                throw new IOException("Responses 图片仅支持PNG/JPEG Base64内容");
            String encoded=part.imageBase64();
            int maximum=5*1024*1024;
            if (encoded==null || encoded.isBlank() || encoded.length()>((maximum+2)/3)*4)
                throw new IOException("Responses 图片为空或超过5MiB上限");
            byte[] bytes;
            try { bytes=java.util.Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException invalid) { throw new IOException("Responses 图片Base64非法"); }
            if (bytes.length==0 || bytes.length>maximum) throw new IOException("Responses 图片为空或超过5MiB上限");
            // 复用原有实际解码、声明MIME一致性、像素上限检查，不凭扩展名判图片。
            com.paicli.image.ImageProcessor.fromBase64(encoded,part.mimeType());
        }
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
                JsonNode parsed=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
                if (id.isBlank() || name.isBlank() || !ids.add(id) || parsed==null || !parsed.isObject()) throw new IOException("Sub2API 工具调用参数或标识非法");
                calls.add(new LlmClient.ToolCall(id,new LlmClient.ToolCall.Function(name,arguments)));
            } else if (type.equals("reasoning")) {
                if (item.path("encrypted_content").asText().isBlank()) throw new IOException("Sub2API 推理项缺少可回传的加密内容");
                for (JsonNode summary : item.path("summary")) reasoning.append(summary.path("text").asText());
            }
            items.add(item.deepCopy());
        }
        if (content.isEmpty() && calls.isEmpty()) throw new IOException("Sub2API Responses 返回空文本结果");
        var usage=tokenUsage(root.path("usage"));
        return new LlmClient.ChatResponse("assistant",content.toString(),reasoning.toString(),List.copyOf(calls),
                usage==null?0:usage.inputTokens(),usage==null?0:usage.outputTokens(),usage==null?0:usage.cachedInputTokens(),
                root.path("model").isTextual()?root.path("model").asText():null,usage!=null,List.copyOf(items));
    }

    /** 终态与计量分别核对，失败响应中的合法计量仍可作为部分用量证据保留。 */
    static Sub2ApiRequestException.TokenUsage tokenUsage(JsonNode usage) throws IOException {
        if (usage==null || usage.isMissingNode() || usage.isNull()) return null;
        boolean known=counter(usage.get("input_tokens")) && counter(usage.get("output_tokens"));
        if (!known) throw new IOException("Sub2API Responses 用量字段非法或不完整");
        int cached=0;
        if (known && usage.path("input_tokens_details").has("cached_tokens")) {
            JsonNode value=usage.path("input_tokens_details").get("cached_tokens");
            if (!counter(value) || value.intValue()>usage.path("input_tokens").intValue()) throw new IOException("Sub2API 缓存用量非法");
            cached=value.intValue();
        }
        if (known && usage.path("output_tokens_details").has("reasoning_tokens")) {
            JsonNode value=usage.path("output_tokens_details").get("reasoning_tokens");
            if (!counter(value) || value.intValue()>usage.path("output_tokens").intValue()) throw new IOException("Sub2API 推理用量非法");
        }
        if (known && usage.has("total_tokens")) {
            JsonNode total=usage.get("total_tokens");
            if (!counter(total) || total.longValue()!=(long)usage.path("input_tokens").intValue()+usage.path("output_tokens").intValue())
                throw new IOException("Sub2API 总用量非法或不一致");
        }
        return new Sub2ApiRequestException.TokenUsage(usage.path("input_tokens").intValue(),usage.path("output_tokens").intValue(),cached);
    }

    private static boolean counter(JsonNode value) { return value!=null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=0; }
    private static void validateItem(JsonNode item) throws IOException {
        if (!item.isObject() || !List.of("message","function_call","reasoning").contains(item.path("type").asText()))
            throw new IOException("Sub2API Responses 返回未支持的输出项");
        if("message".equals(item.path("type").asText())&&(!"assistant".equals(item.path("role").asText())||!item.path("content").isArray()))
            throw new IOException("Sub2API Responses 输出角色或内容非法");
    }
}
