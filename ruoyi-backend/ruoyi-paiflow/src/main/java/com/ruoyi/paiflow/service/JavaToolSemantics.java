package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/**
 * 原 Java AbstractNodeExecutor/VariablePool/AiToolsIntegration/Tool 的纯语义适配。
 * 输入池与 Provider 输出须先由调用方核实固定 Run、前驱和现有操作账本。
 * 本类不授予执行许可，不以 Worker 完成事件建立可信输出，也不保存第二套状态。
 */
public final class JavaToolSemantics {
    private JavaToolSemantics() {}
    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\].]+)((?:\\[\\d+\\])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");
    private static final String TTS = "tool@8b2262bef821000";

    /** 原 TaskRunner 的 Jackson 输入是普通 Map；VariablePool 再转换每个顶层值。 */
    public static Map<String,Object> startPool(Map<String,Object> fixedRunInput) {
        if (fixedRunInput == null) throw error("Java 开始节点缺少固定 Run.input",403);
        var result = new HashMap<String,Object>();
        fixedRunInput.forEach((name,value) -> result.put(name,poolValue(plain(value))));
        return result;
    }

    /** 原 storeOutputs 不写顶层 null，其余值按 VariablePool.set 保存；不补默认字段。 */
    public static Map<String,Object> outputPool(Map<String,Object> ledgerVerifiedOutputs) {
        if (ledgerVerifiedOutputs == null) throw error("Java 工具缺少真实账本输出",403);
        var result = new HashMap<String,Object>();
        ledgerVerifiedOutputs.forEach((name,value) -> { if (value != null) result.put(name,poolValue(value)); });
        return result;
    }

    private static Object poolValue(Object value) {
        if (value == null || value instanceof Number || value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof JSONObject || value instanceof JSONArray || value instanceof UUID) return value;
        if (value instanceof List<?>) return JSON.parseArray(JSON.toJSONString(value));
        return JSON.parseObject(JSON.toJSONString(value));
    }

    /** 保持原 Jackson 的对象/数组容器，不把字面字符串解释为 Python/JSON 常量。 */
    static Object plain(Object value) {
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key,item) -> result.put((String)key,plain(item)));
            return result;
        }
        if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(JavaToolSemantics::plain).toList());
        // 原 Assignment/Jackson 的无类型 JSON 小数为 Double；整数仍按 Integer/Long/BigInteger 保留。
        if (value instanceof java.math.BigDecimal decimal) return decimal.doubleValue();
        return value;
    }

    /** verifiedPool 仅可包含中心已核实的开始输入及上游输出，不读取 Worker 自报事件。 */
    public static Map<String,Object> inputs(JSONObject node, Map<String,? extends Map<String,Object>> verifiedPool) {
        if (verifiedPool == null) throw error("Java 输入来源池缺失",403);
        var data = data(node);
        var result = new HashMap<String,Object>();
        boolean start = "node-start".equals(type(node));
        if (start) {
            Map<String,Object> fixed = verifiedPool.get(node.getString("id"));
            if (fixed == null) throw error("Java 开始节点没有固定 Run.input 来源",403);
            result.putAll(fixed);
            // doExecute 对 START 直接 variablePool.get(id)，不会进入 resolveInputs 补声明值。
            return result;
        }
        var fields = data.getJSONArray("inputs");
        if (fields == null) return result;
        for (Object raw : fields) {
            if (!(raw instanceof JSONObject field) || !(field.get("name") instanceof String name)) throw error("Java 固定输入形状无效",422);
            var schema = field.getJSONObject("schema");
            var binding = schema == null ? null : schema.getJSONObject("value");
            if (binding == null) continue;
            if ("ref".equals(binding.get("type"))) {
                if (binding.get("content") instanceof JSONObject ref && ref.get("nodeId") instanceof String source && ref.get("name") instanceof String path) {
                    // 引用节点是否已完成、是否为前驱由中心来源服务核验；缺字段仍遵从原 null。
                    var values = verifiedPool.get(source);
                    if (values == null) throw error("Java 引用尚无可信来源："+source,403);
                    result.put(name,path(values,path));
                }
            } else result.put(name,plain(binding.get("content")));
        }
        return result;
    }

    private static Object path(Map<String,Object> values,String path) {
        if (path == null || path.isBlank()) return null;
        Object current = values;
        for (String part : path.split("\\.",-1)) {
            var segment = SEGMENT.matcher(part);
            if (!segment.matches()) throw error("Java 变量路径无效",422);
            if (!(current instanceof Map<?,?> map)) return null;
            current = map.get(segment.group(1));
            var indices = INDEX.matcher(segment.group(2));
            while (indices.find()) {
                int index;
                try { index = Integer.parseInt(indices.group(1)); }
                catch (NumberFormatException invalid) { throw error("Java 变量数组索引无效",422); }
                if (!(current instanceof List<?> list) || index >= list.size()) return null;
                current = list.get(index);
            }
            if (current == null) return null;
        }
        return current;
    }

    /** 原普通 Link 工具四位置参数；固定目录校验、权限及写许可仍由现有服务执行。 */
    public static JSONObject expected(JSONObject node, Map<String,Object> verifiedInputs, JSONObject descriptor) {
        if (!"plugin".equals(type(node)) || verifiedInputs == null || descriptor == null) throw error("Java 普通工具固定事实缺失",403);
        var params = data(node).getJSONObject("nodeParam");
        if (params == null) throw error("Java 工具 nodeParam 缺失",422);
        String plugin = string(params.get("pluginId")), operation = string(params.get("operationId")), app = string(params.get("appId"));
        String version = string(params.get("version"));
        if (version == null || version.isEmpty()) version = "V1.0";
        if (plugin == null || plugin.isEmpty() || operation == null || operation.isEmpty() || app == null || app.isEmpty()) throw error("Java 原工具身份缺失",422);
        if (TTS.equals(plugin)) throw error("Java TTS 尚未接通普通工具生产端",422);
        if (!"tool".equals(descriptor.get("kind")) || !"spark-link".equals(descriptor.get("provider"))
                || !Objects.equals(plugin,descriptor.get("pluginId")) || !Objects.equals(operation,descriptor.get("operationId"))
                || !Objects.equals(app,descriptor.get("legacyAppId")) || !Objects.equals(version,descriptor.get("legacyVersion"))) throw error("Java 工具节点与固定快照不一致",409);
        var fixed = ToolSchema.operations(descriptor.getJSONObject("openApiSchema")).get(operation);
        if (fixed == null) throw error("Java 固定工具 operation 不存在",409);
        var action = new HashMap<>(verifiedInputs);
        var business = business(action,params.get("businessInput"));
        var positions = new LinkedHashMap<String,Map<String,Object>>();
        for (String position : List.of("header","query","path")) positions.put(position,new HashMap<>());
        var parameters = fixed.schema().getJSONArray("parameters");
        if (parameters != null) for (Object raw : parameters) {
            var item = (JSONObject)raw;
            String name = item.getString("name");
            var schema = item.getJSONObject("schema");
            var source = fromBusiness(schema.get("x-from")) ? business : action;
            if (name != null) positions.get(item.getString("in")).put(name,source.getOrDefault(name,schema.get("default")));
        }
        var request = fixed.schema().getJSONObject("requestBody");
        if (request != null) {
            var bodySchema = request.getJSONObject("content").getJSONObject("application/json").getJSONObject("schema");
            positions.put("body",body(bodySchema,action,business));
        }
        var message = new JSONObject();
        for (var position : positions.entrySet()) if (!position.getValue().isEmpty()) {
            RunJsonBoundary.check(position.getValue(),ModelOperationJson.REQUEST_BYTES);
            // 原 FastJSON 默认省略 Map null；非空 Map 即使编码成 {} 仍发送该位置。
            message.put(position.getKey(),Base64.getEncoder().encodeToString(JSON.toJSONString(position.getValue()).getBytes(StandardCharsets.UTF_8)));
        }
        return JSONObject.of("header",JSONObject.of("app_id",app),"parameter",JSONObject.of("tool_id",plugin,"operation_id",operation,"version",version),"payload",JSONObject.of("message",message));
    }

    private static String string(Object raw) { return raw == null ? null : String.valueOf(raw); }
    private static boolean fromBusiness(Object from) { return from instanceof Number number && number.intValue() == 1; }

    private static Map<String,Object> business(Map<String,Object> action,Object names) {
        var result = new HashMap<String,Object>();
        if (!(names instanceof List<?> list)) return result;
        for (Object raw : list) {
            if (!(raw instanceof String name) || name.isEmpty()) continue;
            var queue = new ArrayDeque<Object>();
            queue.add(action);
            while (!queue.isEmpty()) {
                Object value = queue.remove();
                if (value instanceof Map<?,?> map) {
                    if (map.containsKey(name)) { result.put(name,map.get(name)); break; }
                    for (Object child : map.values()) if (child instanceof Map<?,?> || child instanceof List<?>) queue.add(child);
                } else if (value instanceof List<?> children) for (Object child : children) if (child instanceof Map<?,?> || child instanceof List<?>) queue.add(child);
            }
        }
        return result;
    }

    private static Map<String,Object> body(JSONObject schema,Map<String,Object> action,Map<String,Object> business) {
        var result = new HashMap<String,Object>();
        var properties = schema == null ? null : schema.getJSONObject("properties");
        if (properties == null) return result;
        for (var field : properties.entrySet()) {
            var definition = (JSONObject)field.getValue();
            String name = field.getKey();
            if ("object".equals(definition.get("type"))) {
                Object child = action.get(name);
                @SuppressWarnings("unchecked")
                var nested = child instanceof Map<?,?> map ? (Map<String,Object>)map : new HashMap<String,Object>();
                result.put(name,body(definition,nested,business));
            } else {
                Object value = (fromBusiness(definition.get("x-from")) ? business : action).get(name);
                if (value == null) value = definition.get("default");
                if (value != null) result.put(name,value);
            }
        }
        return result;
    }

    /** 原 PluginNodeExecutor 整 Map 输出；不筛声明、不注入 errorCode/errorMessage、不转换类型。 */
    public static JSONObject project(Object decodedProviderOutput) {
        if (!(decodedProviderOutput instanceof Map<?,?>)) throw error("Java 工具原输出必须为对象",409);
        RunJsonBoundary.check(decodedProviderOutput,ModelOperationJson.REQUEST_BYTES);
        return JSON.parseObject(JSON.toJSONString(decodedProviderOutput,JSONWriter.Feature.WriteMapNullValue));
    }

    /** 仅提取当前已接受 Java 完成事件；调用方还须将其与真实账本/固定输入重建结果比较。 */
    public static JSONObject eventOutputs(RunRecord run,JSONObject node,JSONObject acceptedEvent) {
        if (run == null || !"java".equals(run.getEngineProfile()) || run.getId() == null || run.getLeaseId() == null
                || run.getAttempt() == null || run.getAttempt() < 1 || run.getLastEventSeq() == null || run.getLastEventSeq() < 1
                || acceptedEvent == null) throw error("Java 完成来源没有当前中心事实",403);
        data(node);
        if (!Objects.equals(run.getId(),acceptedEvent.get("runId")) || !Objects.equals(run.getLeaseId(),acceptedEvent.get("leaseId"))
                || ModelOperationJson.integer(acceptedEvent.get("attempt"),1,Integer.MAX_VALUE,"attempt") != run.getAttempt()
                || !"paiflow.events.v1".equals(acceptedEvent.get("schemaVersion")) || !"node.completed".equals(acceptedEvent.get("eventType"))
                || acceptedEvent.get("iterationId") != null || !Objects.equals(node.get("id"),acceptedEvent.get("nodeId"))) throw error("Java 完成事件 scope 与固定节点不一致",403);
        ModelOperationJson.integer(acceptedEvent.get("seq"),1,run.getLastEventSeq(),"seq");
        var payload = acceptedEvent.getJSONObject("payload");
        var result = payload == null ? null : payload.getJSONObject("result");
        if (payload == null || !Objects.equals(node.get("id"),payload.get("nodeId")) || result == null
                || !"SUCCESS".equals(result.get("status")) || !result.containsKey("error") || result.get("error") != null
                || !(result.get("inputs") instanceof JSONObject) || !(result.get("outputs") instanceof JSONObject outputs)) throw error("Java 完成事件不是原成功结果",403);
        // 完成事件同时携带原输入与输出，沿用正式事件预算；单独输出仍由project限制。
        RunJsonBoundary.check(acceptedEvent,RunProtocolLimits.EVENT_BYTES);
        return project(outputs);
    }

    /** expectedInputs/Outputs 必须来自固定输入/原算法/真实 Provider 账本，不能传 Worker 自报值。 */
    public static void verifyEvent(RunRecord run,JSONObject node,JSONObject acceptedEvent,Map<String,Object> expectedInputs,Map<String,Object> expectedOutputs) {
        JSONObject actual = eventOutputs(run,node,acceptedEvent);
        if (expectedInputs == null || expectedOutputs == null) throw error("Java 完成事件缺少可信重建值",403);
        Map<String,Object> wantedInputs = expectedInputs, wantedOutputs = expectedOutputs;
        switch (type(node)) {
            case "node-start" -> { wantedOutputs = Map.of(); }
            case "node-end" -> { wantedInputs = Map.of(); wantedOutputs = expectedInputs; }
            case "plugin", "spark-llm" -> { }
            default -> throw error("Java 节点输出语义尚未接通",422);
        }
        Object actualInputs = acceptedEvent.getJSONObject("payload").getJSONObject("result").get("inputs");
        if (!ToolSchema.same(actualInputs,wantedInputs) || !ToolSchema.same(actual,wantedOutputs)) throw error("Java 完成结果偏离真实中心输入或原工具输出",409);
    }

    /** 投影必须由同次调用账本的完整原帧重建，不能用完成事件本身生成。 */
    public static void verifyModelEvent(RunRecord run,JSONObject node,JSONObject acceptedEvent,
            Map<String,Object> expectedInputs,JavaModelSemantics.Projection projection) {
        if (!"spark-llm".equals(type(node)) || projection == null) throw error("Java 模型完成缺少原帧投影",403);
        verifyEvent(run,node,acceptedEvent,expectedInputs,JavaModelSemantics.outputs(node,projection));
        var result = acceptedEvent.getJSONObject("payload").getJSONObject("result");
        if (!Objects.equals(result.get("rawOutput"),projection.content()) || !ToolSchema.same(result.get("tokenCost"),projection.usage())
                || !ToolSchema.same(result.get("errorOutputs"),Map.of()) || !"".equals(result.get("nodeAnswerContent"))
                || !"".equals(result.get("nodeAnswerReasoningContent"))) throw error("Java 模型完成原文本或用量偏离真实原帧",409);
    }

    private static JSONObject data(JSONObject node) {
        if (node == null || !(node.get("id") instanceof String id) || id.isEmpty() || !(node.get("data") instanceof JSONObject data)) throw error("Java 固定节点形状无效",422);
        return data;
    }
    private static String type(JSONObject node) {
        data(node);
        // 原 Node.getNodeType 从 ID 的 :: 前缀选择执行器；nodeMeta 是展示元数据。
        return NodeCatalog.typeOf(node.getString("id"));
    }
}
