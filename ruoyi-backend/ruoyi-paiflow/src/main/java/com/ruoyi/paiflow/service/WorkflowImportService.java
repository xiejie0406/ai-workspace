package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 原YAML包的只读预览和显式草稿确认。资源目录尚未完整迁移，本入口不授权旧ID、
 * 不创建供应商App、不发布或运行；准确的待绑定项与结构检查分别返回。
 */
@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class WorkflowImportService {
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_DRAFT_BYTES = 16 * 1024 * 1024;
    private static final String POLICY = "paiflow-yaml-draft-unbound-v2";
    private static final String CREATE = "paiflow:workflow:create";
    private static final List<String> PERMISSIONS = List.of(CREATE, "paiflow:workflow:edit",
        "paiflow:workflow:query", "paiflow:agent:create", "paiflow:agent:query", "paiflow:agent:edit");
    private static final Set<String> CONTROLLED_META = Set.of("id", "flowId", "spaceId", "uid", "userId",
        "ownerUserId", "appId", "appKey", "appSecret", "botId", "agentRevision", "agentBindingStatus",
        "revision", "latestVersionId", "status", "isPublic", "canPublish", "createBy", "updateBy",
        "createTime", "updateTime", "deleted", "publishResult", "publishChannel", "publicationStatus", "bindingStatus");
    private static final Map<String, String> RESOURCE_KINDS = Map.ofEntries(
        Map.entry("spark-llm", "model"), Map.entry("decision-making", "model"),
        Map.entry("extractor-parameter", "model"), Map.entry("question-answer", "model"),
        Map.entry("plugin", "tool"), Map.entry("flow", "workflow"),
        Map.entry("knowledge-base", "knowledge"), Map.entry("knowledge-pro-base", "knowledge"),
        Map.entry("database", "data-table"), Map.entry("rpa", "rpa"), Map.entry("agent", "agent"));
    // 仅处理原nodeParam的已知认证/受控绑定位置；不递归删除业务schema、default或输入对象。
    private static final List<String> PRIVATE_NODE_FIELDS = List.of("apiKey", "apiSecret", "appKey", "appSecret",
        "api_key", "api_secret", "credentialRef", "credentialVersion", "secretRef", "secretRefs",
        "modelResourceId", "endpoint");
    private final DefaultPersonalSpaceService defaults;
    private final ResourceAccessService access;
    private final WorkflowService workflows;
    private final AgentService agents;

    public WorkflowImportService(DefaultPersonalSpaceService defaults, ResourceAccessService access,
                                 WorkflowService workflows, AgentService agents) {
        this.defaults = defaults;
        this.access = access;
        this.workflows = workflows;
        this.agents = agents;
    }

    public record Difference(String field, String code, String message) {}
    public record UnresolvedResource(String nodeId, String field, String kind, String reason) {}
    public record Preview(String previewHash, String fileHash, String policyVersion, String name,
                          int nodes, int edges, List<Difference> differences,
                          DslValidationService.Report compatibility, List<UnresolvedResource> unresolvedResources,
                          boolean saveable, List<String> blockingReasons, String executionStatus) {}
    private record Prepared(JSONObject body, DefaultPersonalSpaceService.Selection selection, Preview preview) {}

    /** 只读权限核对和解析；新用户的默认个人域不在此建立。 */
    public Preview preview(byte[] bytes, String filename, String header) {
        return prepare(bytes, filename, header).preview();
    }

    /**
     * 创建请求不是自动重试协议。每次显式确认创建一份新草稿；客户端应防重复提交，
     * 响应丢失后先查询草稿，不将同一POST自动重发。本事务失败则整笔回滚。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JSONObject confirm(byte[] bytes, String filename, String header, String expectedHash) {
        Prepared prepared = prepare(bytes, filename, header);
        Preview preview = prepared.preview();
        if (expectedHash == null || !expectedHash.matches("[a-f0-9]{64}")
            || !MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                preview.previewHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ServiceException("导入文件、主体、空间或转换规则已变化，请重新预览", 409);
        }
        if (!preview.saveable()) {
            throw new ServiceException("导入草稿不能保存：" + String.join("；", preview.blockingReasons()), 422);
        }
        // 复用creatorGuard→ownerScope→space→Workflow→Agent顺序，不先materialize空间。
        JSONObject body = new JSONObject(prepared.body());
        String base = preview.name();
        String stem = base.substring(0, base.offsetByCodePoints(0, Math.min(6, base.codePointCount(0, base.length()))))
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String name = stem;
        // 只读既有域名称，不抢先建立域或改变创建锁顺序；并发竞争仍由原唯一约束整笔回滚。
        Long existingSpace = defaults.existing(prepared.selection(), CREATE);
        for (int suffix = 2; existingSpace != null && workflows.nameExistsForAgent(existingSpace, name); suffix++) {
            if (suffix > 100) throw new ServiceException("同名导入草稿过多，请稍后重试", 409);
            name = stem + "_" + suffix;
        }
        JSONObject request = JSONObject.of("name", name, "description", body.get("description"),
            "avatar", body.get("avatarIcon"), "engineProfile", "python");
        // 先创建真实绑定，再保存完整画布，避免把16MiB图塞入Agent的1MiB配置预算。
        JSONObject agent = agents.legacyCreate(header, 3, request);
        long id = agent.getLongValue("workflowId");
        Long space = defaults.existing(prepared.selection(), "paiflow:workflow:edit");
        if (space == null || id < 1) throw new ServiceException("导入工作流绑定未建立，创建已回滚", 409);
        JSONObject draft = workflows.detail(id, space);
        body.put("name", name);
        JSONObject saved = workflows.save(id, space, draft.getLong("revision"), body);
        saved.put("spaceId", prepared.selection().defaultPersonal() ? null : space);
        saved.put("importFileHash", preview.fileHash());
        saved.put("importPolicyVersion", POLICY);
        saved.put("importDifferences", preview.differences());
        saved.put("importCompatibility", preview.compatibility());
        saved.put("unresolvedResources", preview.unresolvedResources());
        saved.put("importExecutionStatus", preview.executionStatus());
        return saved;
    }

    private Prepared prepare(byte[] bytes, String filename, String header) {
        DefaultPersonalSpaceService.Selection selection = defaults.select(header, null, CREATE);
        for (String permission : PERMISSIONS) {
            if (access.requirePermission(permission) != selection.ownerUserId())
                throw new ServiceException("导入主体已变化", 403);
            defaults.existing(selection, permission);
        }
        JSONObject root = load(bytes, filename);
        JSONObject metadata = object(root.get("flowMeta"), "flowMeta");
        JSONObject graph = object(root.get("flowData"), "flowData");
        if (!(graph.get("nodes") instanceof JSONArray nodes) || !(graph.get("edges") instanceof JSONArray edges))
            throw new ServiceException("flowData必须包含nodes和edges数组", 400);
        if (nodes.size() > 4096 || edges.size() > 16384)
            throw new ServiceException("导入预览最多4096个节点和16384条连线", 413);
        List<Difference> differences = new ArrayList<>();
        List<UnresolvedResource> unresolved = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        JSONObject body = new JSONObject(metadata);
        if (body.containsKey("importSourceMetadata"))
            throw new ServiceException("flowMeta/importSourceMetadata是本次导入的保全位置，不能由包覆盖", 400);
        for (String field : CONTROLLED_META) remove(body, field, "flowMeta", "SERVER_OWNED", differences);
        if (body.get("ext") instanceof JSONObject || body.get("ext") instanceof String) {
            boolean textExtension = body.get("ext") instanceof String;
            JSONObject extension;
            try { extension = object(body.get("ext"), "flowMeta/ext"); }
            catch (ServiceException nonObject) { extension = null; }
            if (extension != null) {
                for (String key : List.of("botId", "agentRevision", "agentBindingStatus"))
                    remove(extension, key, "flowMeta/ext", "SERVER_OWNED", differences);
                body.put("ext", textExtension ? extension.toJSONString(JSONWriter.Feature.WriteMapNullValue) : extension);
            }
        }
        if (body.containsKey("data")) {
            throw new ServiceException("flowMeta/data与flowData重复，请使用原包根字段flowData", 400);
        }
        Object originalEngine = body.remove("engineProfile");
        if (originalEngine != null && !"python".equals(originalEngine))
            differences.add(new Difference("flowMeta/engineProfile", "ENGINE_PROFILE", "原包按Python节点目录导入草稿，实际引擎运行须另行核对"));
        body.put("engineProfile", "python");
        if (body.get("dslVersion") != null && !"v1".equals(body.get("dslVersion")))
            blocking.add("不支持的flowMeta/dslVersion，原字段保全但不能确认保存");
        for (String config : List.of("advancedConfig", "flowConfig")) {
            if (body.get(config) != null) body.put(config, object(body.get(config), "flowMeta/" + config));
        }
        // 顶层未知扩展保全在草稿opaque元数据，原包字段和值不被丢弃。
        JSONObject extensions = new JSONObject(root);
        extensions.remove("flowMeta");
        extensions.remove("flowData");
        if (!extensions.isEmpty()) {
            if (body.containsKey("importPackageExtensions"))
                throw new ServiceException("flowMeta/importPackageExtensions与根扩展保全位置冲突", 400);
            // 当前opaque metadata落盘采用默认JSON策略；用JSON文本保全扩展的显式null。
            body.put("importPackageExtensions", extensions.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            differences.add(new Difference("/", "EXTENSIONS_PRESERVED", "包根扩展保存在草稿importPackageExtensions，不参与身份或资源授权"));
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (!(nodes.get(i) instanceof JSONObject node))
                throw new ServiceException("flowData/nodes/" + i + "必须为对象", 400);
            inspectNode(node, "flowData/nodes/" + i, differences, unresolved);
        }
        body.put("data", graph);
        String name = text(body.get("name"), "flowMeta/name", 200, false);
        if (name == null || name.isBlank()) name = "workflow";
        body.put("name", name);
        text(body.get("description"), "flowMeta/description", 200, true);
        text(body.get("avatarIcon"), "flowMeta/avatarIcon", 2560, true);
        JSONObject sourceMetadata = new JSONObject(body);
        sourceMetadata.remove("data");
        sourceMetadata.remove("importPackageExtensions");
        body.put("importSourceMetadata", sourceMetadata.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        // 复用现有唯一凭据/JSON预算边界；未知路径的疑似凭据明确受阻，绝不递归静默删。
        try { WorkflowJsonBoundary.check(body, "import", MAX_DRAFT_BYTES); }
        catch (ServiceException ex) { blocking.add(ex.getMessage()); }
        if (body.get("advancedConfig") instanceof JSONObject advanced) {
            JSONObject derived = new JSONObject();
            if (advanced.get("prologue") instanceof JSONObject prologue) {
                if (prologue.containsKey("prologueText")) derived.put("prologue", prologue.get("prologueText"));
                if (prologue.containsKey("inputExample")) derived.put("inputExample", prologue.get("inputExample"));
            }
            if (advanced.containsKey("backgroundPic")) derived.put("appBackground", advanced.get("backgroundPic"));
            try { WorkflowJsonBoundary.check(derived, "agent/config", 1024 * 1024); }
            catch (ServiceException ex) { blocking.add(ex.getMessage()); }
        }
        DslValidationService.Report compatibility = new DslValidationService().validate(graph, "python");
        String fileHash = hash(bytes);
        String receipt = hash((fileHash + "\n" + selection.ownerUserId() + "\n" + selection.spaceId()
            + "\n" + selection.defaultPersonal() + "\n" + POLICY).getBytes(StandardCharsets.UTF_8));
        String executionStatus = !compatibility.valid() ? "structure_invalid"
            : !unresolved.isEmpty() ? "requires_binding" : "not_verified";
        Preview preview = new Preview(receipt, fileHash, POLICY, name, nodes.size(), edges.size(),
            List.copyOf(differences), compatibility, List.copyOf(unresolved), blocking.isEmpty(),
            List.copyOf(blocking), executionStatus);
        return new Prepared(body, selection, preview);
    }

    private static void inspectNode(JSONObject node, String path, List<Difference> differences,
                                    List<UnresolvedResource> unresolved) {
        String id = node.get("id") instanceof String value ? value : null;
        String type = NodeCatalog.typeOf(id);
        remove(node, "flowId", path, "SERVER_OWNED", differences);
        if (NodeCatalog.find(type) == null) {
            differences.add(new Difference(path + "/id", "UNKNOWN_NODE", "未知节点及扩展字段保全，只可作为待编辑草稿；现有中心验证禁止运行"));
            unresolved.add(new UnresolvedResource(id, path, "unknown-node", "未知节点无可授权执行协议"));
            return;
        }
        if (!(node.get("data") instanceof JSONObject data)) return;
        if (data.get("nodeParam") instanceof String) {
            data.put("nodeParam", object(data.get("nodeParam"), path + "/data/nodeParam"));
            differences.add(new Difference(path + "/data/nodeParam", "JSON_OBJECT", "原JSON参数字符串转换为同义对象"));
        }
        JSONObject params = data.get("nodeParam") instanceof JSONObject object ? object : null;
        String parameterPath = path + "/data/nodeParam";
        if (params != null) remove(params, "uid", parameterPath, "SERVER_OWNED", differences);
        String kind = RESOURCE_KINDS.get(type);
        if (kind == null) {
            if (type.equals("node-variable") || type.equals("ifly-code"))
                unresolved.add(new UnresolvedResource(id, parameterPath, type,
                    "变量作用域/代码沙箱须由当前中心端口核对，本入口不授予运行能力"));
            return;
        }
        if (params != null) {
            remove(params, "appId", parameterPath, "SERVER_OWNED", differences);
            for (String field : PRIVATE_NODE_FIELDS) remove(params, field, parameterPath, "SECRET_REBIND", differences);
            List<String> fields = switch (type) {
                case "spark-llm", "decision-making", "extractor-parameter", "question-answer" ->
                    List.of("llmId", "modelId", "serviceId", "domain", "url", "source", "patchId", "patch_id");
                case "plugin" -> List.of("pluginId", "toolId");
                case "flow" -> List.of("flowId", "workflowId", "workflowVersionId");
                case "knowledge-base", "knowledge-pro-base" ->
                    List.of("repoList", "repoId", "repoIds", "docIds", "llmId", "modelId", "serviceId", "domain", "url");
                case "database" -> List.of("dbId", "tableId");
                case "rpa" -> List.of("projectId", "rpaId", "runnerId");
                case "agent" -> List.of("agentId", "botId", "llmId", "modelId", "serviceId");
                default -> List.of();
            };
            for (String field : fields) remove(params, field, parameterPath, "RESOURCE_REBIND", differences);
            if (type.equals("agent")) {
                if (params.get("modelConfig") instanceof JSONObject config) {
                    for (String field : List.of("domain", "api", "apiKey", "apiSecret", "appId", "credentialRef", "credentialVersion", "endpoint"))
                        remove(config, field, parameterPath + "/modelConfig", "RESOURCE_REBIND", differences);
                }
                if (params.get("plugin") instanceof JSONObject plugin) {
                    for (String field : List.of("tools", "toolsList", "knowledge", "mcpServerIds", "mcpServerUrls", "workflowIds"))
                        remove(plugin, field, parameterPath + "/plugin", "RESOURCE_REBIND", differences);
                }
            }
            if (type.equals("rpa") && params.get("header") instanceof JSONObject header)
                remove(header, "apiKey", parameterPath + "/header", "SECRET_REBIND", differences);
            if (type.equals("question-answer")) {
                try { StaticQuestionAnswerOptions.effective(params); return; }
                catch (ServiceException ignored) { /* 非静态分支或尚未验证的参数仍标待核对。 */ }
            }
        }
        unresolved.add(new UnresolvedResource(id, parameterPath, kind,
            "原资源引用不带入授权；当前资源目录/固定版本尚未完整接通，保存后须重新绑定，既有中心准入拒绝缺失绑定的运行"));
    }

    private static void remove(JSONObject object, String key, String path, String code, List<Difference> changes) {
        if (!object.containsKey(key)) return;
        object.remove(key);
        changes.add(new Difference(path + "/" + key, code,
            code.equals("SERVER_OWNED") ? "原身份、App或状态不带入；由当前平台主体及中心关系决定"
                : "旧资源或凭据绑定移除，须使用当前空间已授权的资源重新绑定"));
    }

    private static JSONObject load(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES)
            throw new ServiceException("导入文件须为非空且不超过20MiB的YAML", 413);
        if (filename == null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.ya?ml"))
            throw new ServiceException("只接受yml/yaml工作流文件", 400);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(48);
        options.setCodePointLimit(MAX_FILE_BYTES);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(new ByteArrayInputStream(bytes)); }
        catch (RuntimeException ex) { throw new ServiceException("工作流YAML无效、重复字段、别名或超出解析限制", 400); }
        Object json = jsonValue(loaded, "", 0);
        if (!(json instanceof JSONObject object)) throw new ServiceException("工作流包必须为对象", 400);
        return object;
    }

    /** SafeConstructor也会产生Date/binary等类型；拒绝非JSON值，不将其静默重新解释。 */
    private static Object jsonValue(Object value, String path, int depth) {
        if (depth > 48) throw new ServiceException("工作流包嵌套过深", 400);
        if (value instanceof Map<?, ?> map) {
            JSONObject result = new JSONObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new ServiceException("YAML对象键必须为字符串：" + path, 400);
                result.put(key, jsonValue(entry.getValue(), path + "/" + key.replace("~", "~0").replace("/", "~1"), depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> values) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < values.size(); i++) result.add(jsonValue(values.get(i), path + "/" + i, depth + 1));
            return result;
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
            || value instanceof Float floatValue && !Float.isFinite(floatValue))
            throw new ServiceException("YAML数值必须有限：" + path, 400);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new ServiceException("YAML只能包含JSON兼容值：" + path, 400);
    }

    private static JSONObject object(Object value, String path) {
        if (value instanceof JSONObject object) return object;
        if (value instanceof String text) {
            try { Object parsed = JSON.parse(text); if (parsed instanceof JSONObject object) return object; }
            catch (RuntimeException ignored) { /* 不回显解析器正文或输入值。 */ }
        }
        throw new ServiceException("导入字段必须为对象：" + path, 400);
    }

    private static String text(Object value, String path, int maximum, boolean nullable) {
        if (value == null && nullable) return null;
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > maximum)
            throw new ServiceException(path + "须为不超过" + maximum + "字的字符串", 400);
        return text;
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("运行环境缺少SHA-256", ex); }
    }
}
