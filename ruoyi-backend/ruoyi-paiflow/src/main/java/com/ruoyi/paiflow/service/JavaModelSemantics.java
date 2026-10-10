package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 原 Java LLMNodeExecutor/LlmReqBo/VariableTemplateRender 与 Spring AI 1.1.2 的纯语义。
 * 固定节点、输入、历史及原帧必须先由中心核实；本类不核许可、不读库、不确认停止。
 */
public final class JavaModelSemantics {
    private JavaModelSemantics() {}
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final Set<String> FINISH = Set.of("stop", "length", "content_filter", "function_call", "tool_calls");
    private static final com.fasterxml.jackson.databind.ObjectMapper JAVA_HISTORY_READER=new com.fasterxml.jackson.databind.ObjectMapper();

    /** 原 SDK 的有效八字段；null 表示未设置，不替换为 Python 默认值。 */
    public static JSONObject options(JSONObject node) {
        JSONObject params = params(node);
        var result = new JSONObject();
        result.put("maxTokens", params.getInteger("maxTokens"));
        for (String key : List.of("temperature", "topP", "presencePenalty", "frequencyPenalty", "n")) result.put(key, null);
        JSONObject extra = params.getJSONObject("extraParams");
        if (extra != null) for (String key : List.of("temperature", "topP", "presencePenalty", "frequencyPenalty", "maxTokens", "n")) {
            Object value = extra.get(key);
            if (value == null) continue;
            try {
                if (key.equals("maxTokens") || key.equals("n")) result.put(key, Integer.parseInt(String.valueOf(value)));
                else {
                    double number = Double.parseDouble(String.valueOf(value));
                    // 原 SDK 在此只 Double.parseDouble；有限性/预算由中心输入与批准边界判定。
                    result.put(key, number);
                }
            } catch (NumberFormatException ignored) { /* 原 SDK 忽略转换失败，保留此前有效值。 */ }
        }
        result.put("streamUsage", true);
        result.put("enableThinking", true);
        return result;
    }

    /** 原模板取同名输入，缺失或显式 null 均保留占位；Map 的实际容器语义由调用方保全。 */
    public static String render(String template, Map<String,Object> inputs) {
        if (template == null || template.isEmpty()) return template;
        if (inputs == null) throw invalid("Java 模型可信输入缺失");
        var matcher = VARIABLE.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            Object value = inputs.get(matcher.group(1));
            if (value == null) continue;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 原 V2 历史配置：转换失败回到 0；启用且 rounds=null 原样返回 null，不吞掉原配置失败。 */
    public static Integer historyLimit(JSONObject node) {
        try {
            // 原Worker的Map由Jackson从固定JSON解码。Fastjson把-2147483648读成Long，
            // 直接强转会误走原catch0；先按原JSON读取恢复Integer/Double/Long边界。
            Map<?,?> history = JAVA_HISTORY_READER.readValue(com.alibaba.fastjson2.JSON.toJSONString(params(node).get("enableChatHistoryV2")),Map.class);
            if (history != null && "true".equals(String.valueOf(history.get("isEnabled")))) return (Integer) history.get("rounds");
            return 0;
        } catch (RuntimeException | java.io.IOException invalid) { return 0; }
    }

    /** history 为中心已核实、已按原轮数选好的 ChatItem 数组；不把显示记录当成原消息事实。 */
    public static JSONArray messages(JSONObject node, Map<String,Object> verifiedInputs, JSONArray history) {
        JSONObject params = params(node);
        Object userTemplate = params.get("template");
        if (userTemplate == null) throw invalid("Java 模型缺少原用户模板");
        String user = render(String.valueOf(jacksonPlain(userTemplate)), verifiedInputs);
        Object systemTemplate = params.get("systemTemplate");
        String system = systemTemplate == null ? null : render(String.valueOf(jacksonPlain(systemTemplate)), verifiedInputs);
        var result = new JSONArray();
        if (system != null && !system.isBlank()) result.add(message("system", system));
        if (history != null) for (Object raw : history) {
            if (!(raw instanceof JSONObject item) || !(item.get("userInputs") instanceof JSONArray users)) throw invalid("Java 原历史项结构无效");
            for (Object rawMessage : users) {
                if (!(rawMessage instanceof JSONObject old) || !(old.get("role") instanceof String role)) throw invalid("Java 原历史消息结构无效");
                // 原 buildPrompt 跳过历史 SYSTEM，其余 userInputs 都构造 UserMessage。
                if (!"SYSTEM".equals(role)) result.add(message("user", content(old)));
            }
            JSONArray responses = item.getJSONArray("llmResponses");
            if (responses != null && !responses.isEmpty()) result.add(message("assistant", content(responses.getJSONObject(0))));
        }
        result.add(message("user", user));
        return result;
    }

    public record Projection(String content, String thinking, JSONObject usage, String finishReason) {}

    /**
     * 已核实完整 OpenAI chat 原帧的原 Java文本投影。原公开 merger 对每帧取首 choice，
     * CenterModelConsumer 累积非 blank 文本和 reasoning，SDK 最后响应的 usage 作为 Node tokenCost。
     * length 等结束原因仍保全，不把它改成 stop 或中心成功。
     */
    public static Projection projection(JSONArray rawFrames) {
        if (rawFrames == null || rawFrames.isEmpty()) throw invalid("Java 模型原帧缺失");
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        String finish = null;
        JSONObject lastUsage = null;
        for (Object raw : rawFrames) {
            if (!(raw instanceof JSONObject frame) || !(frame.get("choices") instanceof JSONArray choices)) throw invalid("Java 模型原帧结构无效");
            lastUsage = frame.getJSONObject("usage");
            if (choices.isEmpty()) continue;
            if (finish != null) throw invalid("Java 模型结束后仍含内容帧");
            JSONObject first = choices.getJSONObject(0);
            JSONObject delta = first.getJSONObject("delta");
            if (delta == null) throw invalid("Java 模型缺少原 delta");
            if (delta.getJSONArray("tool_calls") != null && !delta.getJSONArray("tool_calls").isEmpty())
                throw new ServiceException("Java SDK工具回调来源尚未接通，不能投影为普通模型完成", 503);
            Object text = delta.get("content"), reason = delta.get("reasoning_content");
            if (text != null && !(text instanceof String) || reason != null && !(reason instanceof String)) throw invalid("Java 模型原文本结构无效");
            if (text instanceof String s && !s.isBlank()) content.append(s);
            if (reason instanceof String s) thinking.append(s);
            Object ended = first.get("finish_reason");
            if (ended != null) {
                if (!(ended instanceof String value) || !FINISH.contains(value)) throw invalid("Java 模型原结束原因无效");
                finish = (String) ended;
            }
        }
        if (finish == null) throw invalid("Java 模型尚无真实结束帧");
        int prompt = token(lastUsage, "prompt_tokens"), completion = token(lastUsage, "completion_tokens");
        int total = lastUsage == null || lastUsage.get("total_tokens") == null ? prompt + completion : token(lastUsage, "total_tokens");
        return new Projection(content.toString(), thinking.toString(),
                JSONObject.of("prompt_tokens", prompt, "completion_tokens", completion, "total_tokens", total), finish);
    }

    public static JSONObject outputs(JSONObject node, JSONArray rawFrames) { return outputs(node, projection(rawFrames)); }

    /** 原 formatOutputs：首声明接文本，首个 case-insensitive reason 接思考，其他声明不补默认。 */
    public static JSONObject outputs(JSONObject node, Projection projection) {
        var result = new JSONObject();
        JSONArray fields = data(node).getJSONArray("outputs");
        if (fields == null || fields.isEmpty()) result.put("output", projection.content());
        else {
            result.put(outputName(fields.getJSONObject(0)), projection.content());
            for (Object raw : fields) {
                String name = outputName((JSONObject) raw);
                if ("reason".equalsIgnoreCase(name)) { result.put(name, projection.thinking()); break; }
            }
        }
        return result;
    }

    private static JSONObject message(String role, String content) { return JSONObject.of("role", role, "content", content); }
    /** Worker 的 WorkflowDSL.nodeParam 由 Jackson 转成普通 Map/List，而非 Fastjson 容器。 */
    private static Object jacksonPlain(Object value) {
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key,item) -> result.put((String) key, jacksonPlain(item)));
            return result;
        }
        if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(JavaModelSemantics::jacksonPlain).toList());
        if (value instanceof java.math.BigDecimal decimal) return decimal.doubleValue();
        return value;
    }
    private static String content(JSONObject message) {
        if (message == null || !(message.get("content") instanceof String text)) throw invalid("Java 原历史文本缺失");
        return text;
    }
    private static String outputName(JSONObject field) {
        if (field == null || !(field.get("name") instanceof String name)) throw invalid("Java 原输出声明无效");
        return name;
    }
    private static int token(JSONObject usage, String key) { return usage == null || usage.get(key) == null ? 0 : usage.getInteger(key); }
    private static JSONObject params(JSONObject node) {
        JSONObject params = data(node).getJSONObject("nodeParam");
        if (params == null) throw invalid("Java 模型固定参数缺失");
        return params;
    }
    private static JSONObject data(JSONObject node) {
        if (node == null || !(node.get("data") instanceof JSONObject data)) throw invalid("Java 模型固定节点缺失");
        return data;
    }
    private static ServiceException invalid(String message) { return new ServiceException(message, 400); }
}
