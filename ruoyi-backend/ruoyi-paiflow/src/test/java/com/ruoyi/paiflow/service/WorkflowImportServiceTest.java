package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import com.ruoyi.paiflow.mapper.AgentMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离验证不连接业务库；mock创建仅用于核对确认的协议边界，不证明事务或业务已通过。 */
public class WorkflowImportServiceTest {
    private DefaultPersonalSpaceService defaults;
    private ResourceAccessService access;
    private WorkflowService workflows;
    private AgentService agents;
    private WorkflowImportService imports;

    @BeforeEach void setup() {
        defaults = mock(DefaultPersonalSpaceService.class);
        access = mock(ResourceAccessService.class);
        workflows = mock(WorkflowService.class);
        agents = mock(AgentService.class);
        when(access.requirePermission(anyString())).thenReturn(7L);
        when(defaults.select(nullable(String.class), isNull(), anyString())).thenAnswer(call -> {
            String header = call.getArgument(0);
            return new DefaultPersonalSpaceService.Selection(7L, header == null ? null : Long.valueOf(header), header == null);
        });
        when(defaults.existing(any(), anyString())).thenReturn(11L);
        when(agents.legacyCreate(nullable(String.class), eq(3), any())).thenReturn(JSONObject.of("workflowId", 91L));
        when(workflows.detail(91L, 11L)).thenReturn(JSONObject.of("revision", 0L));
        when(workflows.save(eq(91L), eq(11L), eq(0L), any())).thenReturn(JSONObject.of("id", 91L, "flowId", "new-flow", "revision", 1L));
        imports = new WorkflowImportService(defaults, access, workflows, agents);
    }

    private static JSONObject node(String id, JSONObject params) {
        return JSONObject.of("id", id, "position", JSONObject.of("x", 1.25, "y", -2),
            "data", JSONObject.of("label", id, "nodeParam", params));
    }

    private static JSONObject packet() {
        return JSONObject.of("flowMeta", JSONObject.of("name", "导入验证", "description", "隔离样本", "dslVersion", "v1"),
            "flowData", JSONObject.of("nodes", new JSONArray(java.util.List.of(node("node-start::s", new JSONObject()),
                node("node-end::e", new JSONObject()))), "edges", new JSONArray(java.util.List.of(
                    JSONObject.of("source", "node-start::s", "target", "node-end::e", "sourceHandle", "output", "targetHandle", "input")))));
    }

    private static byte[] bytes(JSONObject packet) {
        return packet.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
    }

    private JSONObject savedBody() {
        var argument = ArgumentCaptor.forClass(JSONObject.class);
        verify(workflows).save(eq(91L), eq(11L), eq(0L), argument.capture());
        return argument.getValue();
    }

    @Test void previewDoesNotMaterializeOrCreateAnything() {
        when(defaults.existing(any(), anyString())).thenReturn(null);
        var preview = imports.preview(bytes(packet()), "flow.YAML", null);
        assertTrue(preview.saveable());
        assertEquals("not_verified", preview.executionStatus());
        verify(defaults, never()).materialize(any(), anyString());
        verifyNoInteractions(agents, workflows);
    }

    @Test void changedFileOrSelectedSpaceCannotReuseConfirmation() {
        JSONObject packet = packet();
        String receipt = imports.preview(bytes(packet), "flow.yml", null).previewHash();
        packet.getJSONObject("flowMeta").put("description", "修改后的文件");
        assertEquals(409, assertThrows(ServiceException.class,
            () -> imports.confirm(bytes(packet), "flow.yml", null, receipt)).getCode());
        byte[] original = bytes(packet());
        assertEquals(409, assertThrows(ServiceException.class,
            () -> imports.confirm(original, "flow.yml", "12", receipt)).getCode());
        verifyNoInteractions(agents, workflows);
    }

    @Test void revokedPermissionBeforeConfirmationDoesNotCreateDraft() {
        byte[] original = bytes(packet());
        String receipt = imports.preview(original, "flow.yml", null).previewHash();
        when(access.requirePermission("paiflow:agent:edit")).thenThrow(new ServiceException("权限撤销", 403));
        assertEquals(403, assertThrows(ServiceException.class,
            () -> imports.confirm(original, "flow.yml", null, receipt)).getCode());
        verifyNoInteractions(agents, workflows);
    }

    @Test void oldResourceAndCredentialPathsAreRemovedButBusinessFieldsAndLayoutSurvive() {
        JSONObject packet = packet();
        JSONObject params = JSONObject.of("uid", "old-user", "appId", "old-app", "modelId", 9123,
            "apiKey", "not-a-real-credential", "temperature", 0.4, "customParameter", JSONObject.of("uid", "business-value"));
        JSONObject model = node("spark-llm::m", params);
        packet.getJSONObject("flowData").getJSONArray("nodes").add(model);
        byte[] original = bytes(packet);
        var preview = imports.preview(original, "flow.yml", null);
        assertTrue(preview.saveable());
        assertEquals("requires_binding", preview.executionStatus());
        assertEquals("model", preview.unresolvedResources().get(0).kind());
        assertFalse(JSON.toJSONString(preview).contains("not-a-real-credential"));
        JSONObject result = imports.confirm(original, "flow.yml", null, preview.previewHash());
        assertEquals("new-flow", result.get("flowId"));
        JSONObject imported = savedBody().getJSONObject("data").getJSONArray("nodes").getJSONObject(2);
        assertEquals(model.getJSONObject("position"), imported.getJSONObject("position"));
        JSONObject importedParams = imported.getJSONObject("data").getJSONObject("nodeParam");
        assertFalse(importedParams.containsKey("modelId"));
        assertFalse(importedParams.containsKey("apiKey"));
        assertFalse(importedParams.containsKey("uid"));
        assertEquals(0.4, importedParams.getDoubleValue("temperature"));
        assertEquals("business-value", importedParams.getJSONObject("customParameter").get("uid"));
        verify(defaults, never()).materialize(any(), anyString());
        var order = inOrder(agents, workflows);
        order.verify(agents).legacyCreate(isNull(), eq(3), any());
        order.verify(workflows).detail(91L, 11L);
        order.verify(workflows).save(eq(91L), eq(11L), eq(0L), any());
    }

    @Test void unknownBusinessSecretKeyIsReportedRatherThanSilentlyDeleted() {
        JSONObject packet = packet();
        packet.getJSONObject("flowData").getJSONArray("nodes").getJSONObject(0).getJSONObject("data")
            .put("businessSchema", JSONObject.of("token", JSONObject.of("type", "string")));
        byte[] original = bytes(packet);
        var preview = imports.preview(original, "flow.yml", null);
        assertFalse(preview.saveable());
        assertTrue(preview.blockingReasons().stream().anyMatch(text -> text.contains("businessSchema/token")));
        assertTrue(preview.differences().stream().noneMatch(change -> change.field().contains("businessSchema")));
        assertEquals(422, assertThrows(ServiceException.class,
            () -> imports.confirm(original, "flow.yml", null, preview.previewHash())).getCode());
        verifyNoInteractions(agents, workflows);
    }

    @Test void unknownNodesAndNullExtensionsAreKeptWithoutClaimingTheyCanRun() {
        JSONObject packet = packet();
        packet.getJSONObject("flowMeta").put("futureNull", null);
        packet.getJSONObject("flowMeta").put("ext", JSONObject.of("botId", 800, "businessLayout", "kept"));
        packet.put("futureRoot", JSONObject.of("nullable", null));
        packet.getJSONObject("flowData").getJSONArray("nodes").add(node("future-node::x", JSONObject.of("custom", "kept")));
        byte[] original = bytes(packet);
        var preview = imports.preview(original, "flow.yml", null);
        assertTrue(preview.saveable());
        assertFalse(preview.compatibility().valid());
        assertEquals("structure_invalid", preview.executionStatus());
        imports.confirm(original, "flow.yml", null, preview.previewHash());
        JSONObject saved = savedBody();
        JSONObject sourceMetadata = JSON.parseObject(saved.getString("importSourceMetadata"));
        assertTrue(sourceMetadata.containsKey("futureNull"));
        assertNull(sourceMetadata.get("futureNull"));
        assertEquals("kept", saved.getJSONObject("ext").get("businessLayout"));
        assertFalse(saved.getJSONObject("ext").containsKey("botId"));
        assertTrue(JSON.parseObject(saved.getString("importPackageExtensions")).getJSONObject("futureRoot").containsKey("nullable"));
        assertEquals("kept", saved.getJSONObject("data").getJSONArray("nodes").getJSONObject(2)
            .getJSONObject("data").getJSONObject("nodeParam").get("custom"));
    }

    @Test void allOriginalNodeTypesCanBePreviewedAndResourceTypesStayUnbound() {
        JSONObject packet = packet();
        JSONArray nodes = new JSONArray();
        for (NodeCatalog.Entry entry : NodeCatalog.entries()) nodes.add(node(entry.type() + "::sample", new JSONObject()));
        packet.getJSONObject("flowData").put("nodes", nodes);
        packet.getJSONObject("flowData").put("edges", new JSONArray());
        var preview = imports.preview(bytes(packet), "all-nodes.yaml", null);
        assertEquals(21, preview.nodes());
        assertTrue(preview.unresolvedResources().stream().anyMatch(resource -> resource.kind().equals("rpa")));
        assertTrue(preview.unresolvedResources().stream().anyMatch(resource -> resource.kind().equals("data-table")));
        assertTrue(preview.unresolvedResources().stream().anyMatch(resource -> resource.kind().equals("tool")));
        verifyNoInteractions(agents, workflows);
    }

    @Test void yamlDuplicateCollectionsAliasesTagsAndNonJsonValuesAreRejected() {
        for (String source : java.util.List.of("flowMeta: {}\nflowMeta: {}\nflowData: {}",
            "flowMeta: &a {name: original}\nflowData: *a", "!!java.lang.Runtime {}",
            "flowMeta: {name: original, date: 2026-10-05}\nflowData: {nodes: [], edges: []}",
            "flowMeta: {name: original, value: .NaN}\nflowData: {nodes: [], edges: []}")) {
            assertThrows(ServiceException.class, () -> imports.preview(source.getBytes(StandardCharsets.UTF_8), "flow.yml", null));
        }
        verifyNoInteractions(agents, workflows);
    }

    @Test void exactKnowledgeAgentAndRpaPathsAreReboundWhileUnknownSiblingFieldsSurvive() {
        JSONObject packet = packet();
        JSONArray nodes = packet.getJSONObject("flowData").getJSONArray("nodes");
        nodes.add(node("knowledge-pro-base::k", JSONObject.of("repoIds", java.util.List.of("old-repo"),
            "docIds", java.util.List.of("old-doc"), "score", 0.3, "custom", "kept")));
        nodes.add(node("agent::a", JSONObject.of("plugin", JSONObject.of("mcpServerIds", java.util.List.of("old-mcp"),
            "mcpServerUrls", java.util.List.of("https://untrusted.invalid"), "workflowIds", java.util.List.of("old-flow"),
            "businessSetting", "kept"))));
        nodes.add(node("rpa::r", JSONObject.of("projectId", "old-rpa", "header",
            JSONObject.of("apiKey", "not-real-rpa-key", "businessFlag", "kept"))));
        byte[] original = bytes(packet);
        var preview = imports.preview(original, "flow.yml", null);
        assertTrue(preview.saveable());
        assertTrue(preview.differences().stream().anyMatch(item -> item.field().endsWith("/header/apiKey")));
        assertFalse(JSON.toJSONString(preview).contains("not-real-rpa-key"));
        imports.confirm(original, "flow.yml", null, preview.previewHash());
        JSONArray saved = savedBody().getJSONObject("data").getJSONArray("nodes");
        JSONObject knowledge = saved.getJSONObject(2).getJSONObject("data").getJSONObject("nodeParam");
        assertFalse(knowledge.containsKey("repoIds"));
        assertFalse(knowledge.containsKey("docIds"));
        assertEquals(0.3, knowledge.getDoubleValue("score"));
        JSONObject plugin = saved.getJSONObject(3).getJSONObject("data").getJSONObject("nodeParam").getJSONObject("plugin");
        assertFalse(plugin.containsKey("mcpServerIds"));
        assertFalse(plugin.containsKey("mcpServerUrls"));
        assertFalse(plugin.containsKey("workflowIds"));
        assertEquals("kept", plugin.getString("businessSetting"));
        JSONObject header = saved.getJSONObject(4).getJSONObject("data").getJSONObject("nodeParam").getJSONObject("header");
        assertFalse(header.containsKey("apiKey"));
        assertEquals("kept", header.getString("businessFlag"));
    }

    public static java.sql.Timestamp utcNow() { return java.sql.Timestamp.from(java.time.Instant.now()); }
    public static java.sql.Timestamp utcNow(int precision) { return utcNow(); }

    /** 沿既有AgentPersistenceTest的真Mapper/事务设施，只在随机H2内存库转换MySQL方言。 */
    private static final class Persistence implements AutoCloseable {
        private final JdbcDataSource source = new JdbcDataSource();
        private final java.sql.Connection connection;
        private final WorkflowImportService imports;
        private final WorkflowExportService exports;
        private final WorkflowService workflows;
        private final WorkflowMapper workflowMapper;
        private final AgentMapper agentMapper;
        private final SpaceMapper spaces;

        @SuppressWarnings("unchecked") private Persistence() throws Exception {
            source.setURL("jdbc:h2:mem:import-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");
            connection = source.getConnection();
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.WorkflowImportServiceTest.utcNow'");
                for (String file : java.util.List.of("001-paiflow-schema.sql", "002-paiflow-workflow.sql",
                    "004-paiflow-membership.sql", "007-paiflow-agent.sql", "010-paiflow-default-personal.sql",
                    "011-paiflow-agent-owner-guard.sql", "012-paiflow-workflow-model-snapshots.sql",
                    "015-paiflow-execution-codec.sql", "018-paiflow-workflow-debug-snapshot.sql")) {
                    String ddl = Files.readString(Path.of("sql", file), StandardCharsets.UTF_8)
                        .replaceAll("(?m)^--.*$", "").replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                        .replace(" STORED", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                        .replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)",
                            "ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                        .replace("DROP CHECK ", "DROP CONSTRAINT ")
                        .replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)",
                            "ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                    for (String statement : mysqlIfToCase(ddl).split(";")) if (!statement.isBlank()) sql.execute(statement);
                }
            }
            Configuration config = new Configuration(new Environment("import-isolated", new SpringManagedTransactionFactory(), source));
            for (String mapper : java.util.List.of("SpaceMapper", "WorkflowMapper", "AgentMapper")) {
                try (var xml = WorkflowImportServiceTest.class.getResourceAsStream("/mapper/paiflow/" + mapper + ".xml")) {
                    assertNotNull(xml);
                    new XMLMapperBuilder(xml, config, mapper, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            spaces = sessions.getMapper(SpaceMapper.class);
            workflowMapper = sessions.getMapper(WorkflowMapper.class);
            agentMapper = sessions.getMapper(AgentMapper.class);
            IdentityMappingService identities = mock(IdentityMappingService.class);
            SysUser user = new SysUser(); user.setUserId(7L); user.setUserName("import-fixture");
            when(identities.current()).thenReturn(new LoginUser(7L, 8L, user, java.util.Set.of()));
            PermissionService permissions = mock(PermissionService.class);
            when(permissions.hasPermi(anyString())).thenReturn(true);
            ResourceAccessService access = new ResourceAccessService(identities, permissions, spaces);
            access.useCurrentSession(sessions);
            WorkflowService workflowTarget = new WorkflowService(workflowMapper, access, identities);
            workflows = transactional(workflowTarget, WorkflowService.class);
            DefaultPersonalSpaceService defaults = transactional(new DefaultPersonalSpaceService(spaces, identities, access),
                DefaultPersonalSpaceService.class);
            AgentService agentTarget = new AgentService(agentMapper, access, identities, workflows);
            agentTarget.useDefaultSpaces(defaults);
            AgentService agents = transactional(agentTarget, AgentService.class);
            ObjectProvider<WorkflowService.AgentBinding> bindings = mock(ObjectProvider.class);
            when(bindings.getIfAvailable()).thenReturn(agents);
            workflowTarget.useAgentBindings(bindings);
            imports = transactional(new WorkflowImportService(defaults, access, workflows, agents), WorkflowImportService.class);
            exports = transactional(new WorkflowExportService(workflows, defaults, access), WorkflowExportService.class);
        }

        private <T> T transactional(T target, Class<T> type) {
            ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            return type.cast(proxy.getProxy());
        }

        private long count(String table) throws Exception {
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next(); return rows.getLong(1);
            }
        }

        @Override public void close() throws Exception {
            try (var sql = connection.createStatement()) { sql.execute("SHUTDOWN"); }
            finally { connection.close(); }
        }

        private static String mysqlIfToCase(String text) {
            int start = text.indexOf("IF("); if (start < 0) return text;
            int depth = 1, end = start + 3, partStart = end; boolean quoted = false;
            var parts = new java.util.ArrayList<String>();
            for (; end < text.length(); end++) {
                char c = text.charAt(end);
                if (c == '\'') {
                    if (quoted && end + 1 < text.length() && text.charAt(end + 1) == '\'') { end++; continue; }
                    quoted = !quoted; continue;
                }
                if (quoted) continue;
                if (c == '(') depth++;
                else if (c == ')') { if (--depth == 0) { parts.add(text.substring(partStart, end)); break; } }
                else if (c == ',' && depth == 1) { parts.add(text.substring(partStart, end)); partStart = end + 1; }
            }
            if (parts.size() != 3 || depth != 0) throw new IllegalArgumentException("H2 IF adaptation");
            return mysqlIfToCase(text.substring(0, start) + "CASE WHEN " + mysqlIfToCase(parts.get(0))
                + " THEN " + mysqlIfToCase(parts.get(1)) + " ELSE " + mysqlIfToCase(parts.get(2))
                + " END" + text.substring(end + 1));
        }
    }

    @Test void confirmationPersistsRealBindingNullPreservationAndUnknownNodeRunRefusal() throws Exception {
        try (Persistence persistence = new Persistence()) {
            JSONObject packet = packet();
            packet.getJSONObject("flowMeta").put("futureNull", null);
            packet.getJSONObject("flowMeta").put("ext", JSONObject.of("botId", 555, "businessLayout", "kept"));
            packet.put("futureRoot", JSONObject.of("nullable", null));
            packet.getJSONObject("flowData").getJSONArray("nodes").add(node("future-node::x", JSONObject.of("business", "kept")));
            byte[] original = bytes(packet);
            var preview = persistence.imports.preview(original, "flow.yml", null);
            assertEquals(0, persistence.count("paiflow_space"));
            assertEquals(0, persistence.count("paiflow_agent"));
            JSONObject result = persistence.imports.confirm(original, "flow.yml", null, preview.previewHash());
            long id = result.getLongValue("id");
            var workflow = persistence.workflowMapper.find(id);
            var agent = persistence.agentMapper.byWorkflow(id);
            assertNotNull(agent);
            assertEquals(id, agent.getWorkflowId());
            assertEquals(7L, workflow.getOwnerUserId());
            assertEquals(1L, workflow.getRevision());
            assertEquals(agent.getId(), JSON.parseObject(result.getString("ext")).getLong("botId"));
            JSONObject metadata = JSON.parseObject(workflow.getMetadataJson());
            assertTrue(JSON.parseObject(metadata.getString("importSourceMetadata")).containsKey("futureNull"));
            assertTrue(JSON.parseObject(metadata.getString("importPackageExtensions")).getJSONObject("futureRoot").containsKey("nullable"));
            assertEquals("kept", metadata.getJSONObject("ext").getString("businessLayout"));
            assertEquals("future-node::x", JSON.parseObject(workflow.getCanvasJson()).getJSONArray("nodes").getJSONObject(2).getString("id"));
            assertEquals(422, assertThrows(ServiceException.class, () -> persistence.workflows.createVersion(id,
                workflow.getSpaceId(), workflow.getRevision(), JSONObject.of("name", "不可运行未知节点"))).getCode());
            assertEquals(0, persistence.count("paiflow_workflow_version"));
        }
    }

    @Test void failedFinalSaveRollsBackDefaultSpaceAgentAndWorkflowTogether() throws Exception {
        try (Persistence persistence = new Persistence()) {
            try (var sql = persistence.connection.createStatement()) {
                sql.execute("ALTER TABLE paiflow_workflow ADD CONSTRAINT fixture_import_save_fail CHECK (revision = 0)");
            }
            byte[] original = bytes(packet());
            var preview = persistence.imports.preview(original, "flow.yml", null);
            assertThrows(RuntimeException.class, () -> persistence.imports.confirm(original, "flow.yml", null, preview.previewHash()));
            assertEquals(0, persistence.count("paiflow_space"));
            assertEquals(0, persistence.count("paiflow_agent"));
            assertEquals(0, persistence.count("paiflow_workflow"));
        }
    }

    @Test void actualOriginalPodcastYamlConfirmsIntoNewCentreBindingAndRefusesUnboundVersion() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("../../参考项目/PaiFlow-main/docker/PaiFlow/AI 播客.yml"));
        try (Persistence persistence = new Persistence()) {
            var preview = persistence.imports.preview(source, "AI 播客.yml", null);
            assertTrue(preview.saveable());
            assertTrue(preview.unresolvedResources().stream().anyMatch(item -> item.kind().equals("model")));
            assertTrue(preview.unresolvedResources().stream().anyMatch(item -> item.kind().equals("tool")));
            JSONObject saved = persistence.imports.confirm(source, "AI 播客.yml", null, preview.previewHash());
            var row = persistence.workflowMapper.find(saved.getLongValue("id"));
            assertEquals("AI 播客", JSON.parseObject(row.getMetadataJson()).getString("importSourceMetadata") == null
                ? null : JSON.parseObject(JSON.parseObject(row.getMetadataJson()).getString("importSourceMetadata")).getString("name"));
            assertEquals(preview.nodes(), JSON.parseObject(row.getCanvasJson()).getJSONArray("nodes").size());
            assertNotNull(persistence.agentMapper.byWorkflow(row.getId()));
            assertEquals(503, assertThrows(ServiceException.class, () -> persistence.workflows.createVersion(row.getId(),
                row.getSpaceId(), row.getRevision(), JSONObject.of("name", "未绑定播客"))).getCode());
            assertEquals(0, persistence.count("paiflow_workflow_version"));
        }
    }

    @Test void importedDraftExportsAndReimportsUnknownFieldsAndNullsFromActualRows() throws Exception {
        try (Persistence persistence = new Persistence()) {
            JSONObject packet = packet();
            packet.put("futureRoot", JSONObject.of("nullable", null, "label", "根扩展"));
            packet.getJSONObject("flowMeta").put("futureNull", null);
            packet.getJSONObject("flowMeta").put("ext", JSONObject.of("businessLayout", "保留", "nullable", null, "botId", 555));
            JSONObject graph = packet.getJSONObject("flowData");
            graph.put("canvasExtension", JSONObject.of("nullable", null));
            graph.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").put("layoutExtension", JSONObject.of("nullable", null));
            byte[] input = bytes(packet);
            var preview = persistence.imports.preview(input, "flow.yml", null);
            JSONObject first = persistence.imports.confirm(input, "flow.yml", null, preview.previewHash());
            byte[] exported = persistence.exports.export(first.getLongValue("id"), null).bytes();
            var rePreview = persistence.imports.preview(exported, "exported.yml", null);
            assertTrue(rePreview.saveable());
            JSONObject second = persistence.imports.confirm(exported, "exported.yml", null, rePreview.previewHash());
            assertNotEquals(first.getLongValue("id"), second.getLongValue("id"));
            assertNotEquals(first.getString("flowId"), second.getString("flowId"));
            var stored = persistence.workflowMapper.find(second.getLongValue("id"));
            JSONObject meta = JSON.parseObject(stored.getMetadataJson());
            assertTrue(meta.containsKey("futureNull"));
            assertNull(meta.get("futureNull"));
            assertEquals("保留", meta.getJSONObject("ext").getString("businessLayout"));
            assertTrue(meta.getJSONObject("ext").containsKey("nullable"));
            JSONObject root = JSON.parseObject(meta.getString("importPackageExtensions"));
            assertTrue(root.getJSONObject("futureRoot").containsKey("nullable"));
            assertEquals("根扩展", root.getJSONObject("futureRoot").getString("label"));
            JSONObject canvas = JSON.parseObject(stored.getCanvasJson());
            assertTrue(canvas.getJSONObject("canvasExtension").containsKey("nullable"));
            assertTrue(canvas.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("layoutExtension").containsKey("nullable"));
            assertNotNull(persistence.agentMapper.byWorkflow(stored.getId()));
            assertEquals(2, persistence.count("paiflow_workflow"));
            assertEquals(2, persistence.count("paiflow_agent"));
        }
    }
}
