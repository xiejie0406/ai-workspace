import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.astron.console.hub.config.JacksonConfig;
import com.iflytek.astron.console.toolkit.entity.table.workflow.WorkflowDialog;
import java.util.Arrays;
import java.util.List;

/** 仅执行原 DTO 和原 JacksonConfig；没有原 Service、Mapper、HTTP、数据库或授权检查。 */
public class WorkflowDialogueDtoSourceProbe {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] ignored) throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        ObjectNode report = mapper.createObjectNode();
        List<String> fields = Arrays.stream(WorkflowDialog.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic()).map(f -> f.getName()).sorted().toList();
        require(fields.size() == 13, "原实体应有13字段");
        report.set("declaredFields", mapper.valueToTree(fields));
        ArrayNode cases = report.putArray("cases");
        WorkflowDialog empty = mapper.readValue("{}", WorkflowDialog.class);
        JsonNode emptyTree = mapper.valueToTree(empty);
        require(emptyTree.size() == 13, "原Jackson默认必须序列化13个nullable属性");
        for (String field : fields) require(emptyTree.has(field) && emptyTree.get(field).isNull(), field);
        cases.addObject().put("id", "empty-object-all-null").set("serialized", emptyTree);
        WorkflowDialog numeric = mapper.readValue("{\"workflowId\":123,\"type\":1,\"sid\":\"\",\"chatId\":\"group\",\"question\":\"[]\",\"answer\":\"{}\",\"unknown\":7,\"flowId\":\"uuid-is-not-workflowId\"}", WorkflowDialog.class);
        require(numeric.getWorkflowId().equals(123L), "workflowId是Long");
        require(numeric.getSid().isEmpty() && numeric.getType().equals(1), "不能补造sid/type");
        JsonNode numericTree = mapper.valueToTree(numeric);
        require(!numericTree.has("flowId") && !numericTree.has("unknown"), "原JacksonConfig忽略未知字段");
        cases.addObject().put("id", "numeric-workflow-id-empty-sid-unknown-ignored").set("serialized", numericTree);
        WorkflowDialog decimal = mapper.readValue("{\"workflowId\":\"123\"}", WorkflowDialog.class);
        require(decimal.getWorkflowId().equals(123L), "原默认数字字符串转Long");
        cases.addObject().put("id", "decimal-string-workflow-id").set("serialized", mapper.valueToTree(decimal));
        ObjectNode invalid = cases.addObject().put("id", "uuid-cannot-be-workflow-id");
        try {
            mapper.readValue("{\"workflowId\":\"550e8400-e29b-41d4-a716-446655440000\"}", WorkflowDialog.class);
            throw new AssertionError("flowUUID不能绑定Long workflowId");
        } catch (com.fasterxml.jackson.databind.exc.InvalidFormatException expected) {
            invalid.put("rejected", true).put("exception", expected.getClass().getSimpleName());
        }
        WorkflowDialog nullable = mapper.readValue("{\"workflowId\":null,\"type\":null,\"sid\":null,\"chatId\":null,\"questionItem\":null,\"answerItem\":null}", WorkflowDialog.class);
        require(nullable.getWorkflowId() == null && nullable.getType() == null && nullable.getSid() == null, "原DTO没有必填/default");
        cases.addObject().put("id", "explicit-null-no-default").set("serialized", mapper.valueToTree(nullable));
        WorkflowDialog editable = mapper.readValue("{\"id\":999,\"uid\":\"client-owner\",\"deleted\":true,\"data\":\"opaque\",\"question\":\"not-json\",\"answer\":\"not-json\",\"answerItem\":\"\\\"a\\\\nb\\\"\"}", WorkflowDialog.class);
        require(editable.getId().equals(999L) && editable.getDeleted(), "原DTO本身不会保护管理字段");
        require(editable.getQuestion().equals("not-json"), "原DTO不解码question/answer内部JSON");
        cases.addObject().put("id", "untrusted-fields-and-inner-json-unvalidated-by-dto").set("serialized", mapper.valueToTree(editable));
        report.put("caseCount", cases.size()).put("serviceExecuted", false).put("mapperExecuted", false)
                .put("httpExecuted", false).put("databaseAccessed", false);
        System.out.println(mapper.writeValueAsString(report));
    }
}
