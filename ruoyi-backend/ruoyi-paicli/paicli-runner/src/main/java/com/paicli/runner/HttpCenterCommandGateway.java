package com.paicli.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.center.CenterCommandGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 终端身份沿用若依 JWT，终端命令由中心按权限和状态裁决。 */
public final class HttpCenterCommandGateway implements CenterCommandGateway {
    private final CenterHttpClient center;
    private final String fixedModelId;
    public HttpCenterCommandGateway() {
        this(new CenterHttpClient(requiredEnv("PAICLI_CENTER_URL"), requiredEnv("PAICLI_USER_TOKEN"), false));
    }
    public HttpCenterCommandGateway(String fixedModelId){this(new CenterHttpClient(requiredEnv("PAICLI_CENTER_URL"),requiredEnv("PAICLI_USER_TOKEN"),false),fixedModelId);}
    public HttpCenterCommandGateway(CenterHttpClient center) { this(center,null); }
    public HttpCenterCommandGateway(CenterHttpClient center,String fixedModelId) { this.center = center; this.fixedModelId=fixedModelId; }
    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量 " + name);
        return value;
    }
    @Override public Session open(OpenSession request) throws IOException {
        JsonNode value;
        if (request.sessionId() != null && !request.sessionId().isBlank()) value = center.get("/paicli/v1/sessions/" + id(request.sessionId()));
        else {
            ObjectNode body = CanonicalJson.MAPPER.createObjectNode(); body.put("workspaceId", id(request.workspaceId()));
            if (request.runnerId() == null || request.runnerId().isBlank()) throw new IOException("新会话必须指定 --runner 或 PAICLI_RUNNER_ID");
            body.put("runnerId", id(request.runnerId()));
            body.put("modelId", id(fixedModelId==null?requiredEnv("PAICLI_MODEL_ID"):fixedModelId));
            body.put("title", "PaiCLI 终端会话"); body.put("mode", "REACT"); body.put("approvalMode", "ASK");
            value = center.post("/paicli/v1/sessions", body, UUID.randomUUID().toString());
        }
        if (!request.workspaceId().equals(value.path("workspaceId").asText())) throw new IOException("会话不属于指定工作区");
        if(request.runnerId()!=null&&!request.runnerId().isBlank()&&!request.runnerId().equals(value.path("runnerId").asText()))throw new IOException("会话不属于指定Runner");
        checkModel(value);
        return new Session(value.path("id").asText(), value.path("workspaceId").asText(), value.path("runnerId").asText(),
            presentationMode(value), value.path("provider").asText(), value.path("model").asText(),value.path("modelId").asText(), value.path("eventCursor").asLong());
    }
    @Override public Turn submit(String sessionId, String text, String mode, List<Attachment> attachments) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("消息不能为空");
        JsonNode session = center.get("/paicli/v1/sessions/" + id(sessionId));
        checkModel(session);
        ObjectNode body = CanonicalJson.MAPPER.createObjectNode(); body.put("text", text);
        if(fixedModelId!=null)body.put("modelId",fixedModelId);
        String selection = mode == null ? "ASK" : mode.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("AUTO", "ASK", "PLAN", "TEAM").contains(selection)) throw new IOException("终端模式应为auto、ask、plan或team");
        String approvalMode="TEAM".equals(selection)?session.path("approvalMode").asText("ASK"):selection;
        if(!List.of("AUTO","ASK","PLAN").contains(approvalMode))throw new IOException("中心会话审批模式无效");
        body.put("mode", "TEAM".equals(selection)?"TEAM":"PLAN".equals(selection)?"PLAN":"REACT");
        body.put("approvalMode", approvalMode); body.put("version", session.path("version").asLong());
        var artifactIds = body.putArray("attachments");
        for (Attachment image : attachments == null ? List.<Attachment>of() : attachments) {
            byte[] bytes;
            try { bytes = java.util.Base64.getDecoder().decode(image.base64Data()); } catch (IllegalArgumentException e) { throw new IOException("图片base64无效", e); }
            JsonNode artifact = center.uploadImage(id(session.path("workspaceId").asText()), image.name(), image.mimeType(), bytes, UUID.randomUUID().toString());
            artifactIds.add(id(artifact.path("id").asText()));
        }
        JsonNode value = center.mutate("POST", "/paicli/v1/sessions/" + id(sessionId) + "/turns", body, UUID.randomUUID().toString(), session.path("version").asText());
        return new Turn(value.path("taskId").asText(), "ENQUEUED");
    }
    private void checkModel(JsonNode session)throws IOException{
        if(fixedModelId!=null&&!fixedModelId.equals(session.path("modelId").asText()))throw new IOException("会话模型与Runtime固定模型不一致");
    }
    private static String presentationMode(JsonNode session){
        String execution=session.path("mode").asText("REACT");
        if("PLAN".equals(execution)||"TEAM".equals(execution))return execution.toLowerCase(java.util.Locale.ROOT);
        return session.path("approvalMode").asText("ASK").toLowerCase(java.util.Locale.ROOT);
    }
    @Override public CommandResult command(String sessionId, String name, String arguments) throws IOException {
        JsonNode session = center.get("/paicli/v1/sessions/" + id(sessionId));
        ObjectNode body = CanonicalJson.MAPPER.createObjectNode(); body.put("name", name); body.put("arguments", arguments == null ? "" : arguments); body.put("version", session.path("version").asLong());
        JsonNode value = center.mutate("POST", "/paicli/v1/sessions/" + id(sessionId) + "/commands", body, UUID.randomUUID().toString(), session.path("version").asText());
        return new CommandResult(value.path("successful").asBoolean(), value.path("message").asText(), map(value.path("data")));
    }
    @Override public List<Event> events(String sessionId, long afterSequence, int limit) throws IOException {
        if (afterSequence < 0 || limit < 1 || limit > 500) throw new IOException("事件游标或批量大小非法");
        JsonNode data = center.get("/paicli/v1/sessions/" + id(sessionId) + "/events?after=" + afterSequence + "&limit=" + limit + "&waitSeconds=20");
        List<Event> events = new ArrayList<>();
        for (JsonNode item : data.path("events")) {
            Map<String,Object> payload=new java.util.LinkedHashMap<>(map(item.path("payload")));
            if(item.hasNonNull("taskId"))payload.put("taskId",item.path("taskId").asText());
            events.add(new Event(item.path("sequence").asLong(), item.path("type").asText(), payload, item.path("terminal").asBoolean() || item.path("type").asText().matches("TASK_(COMPLETED|FAILED|CANCELLED)")));
        }
        return List.copyOf(events);
    }
    @Override public List<Event> taskEvents(String taskId,long afterSequence,int limit)throws IOException{
        if(afterSequence<0||limit<1||limit>200)throw new IOException("任务事件游标或批量大小非法");
        JsonNode data=center.get("/paicli/v1/tasks/"+id(taskId)+"/events?after="+afterSequence+"&limit="+limit);
        if(!data.isObject()||!data.path("events").isArray()||!data.path("hasMore").isBoolean()
                ||!data.path("terminal").isBoolean()||!data.path("endSequence").isIntegralNumber()
                ||!data.path("endSequence").canConvertToLong()||!data.path("cursor").asText().matches("0|[1-9][0-9]{0,18}"))
            throw new IOException("中心任务事件分页响应字段非法");
        long cursor,end=data.path("endSequence").asLong(),previous=afterSequence;
        try{cursor=Long.parseLong(data.path("cursor").asText());}catch(NumberFormatException invalid){throw new IOException("中心任务事件游标超出范围",invalid);}
        if(end<afterSequence||cursor<afterSequence||cursor>end||data.path("events").size()>limit)
            throw new IOException("中心任务事件分页范围不符");
        List<Event> result=new ArrayList<>();
        for(JsonNode item:data.path("events")){
            if(!item.isObject()||!item.path("sequence").isIntegralNumber()||!item.path("sequence").canConvertToLong()
                    ||!taskId.equals(item.path("taskId").asText())||!item.path("payload").isObject()
                    ||!taskId.equals(item.path("payload").path("taskId").asText())
                    ||!item.path("type").isTextual()||item.path("type").asText().isBlank())
                throw new IOException("中心任务事件归属或字段不符");
            long sequence=item.path("sequence").asLong();String type=item.path("type").asText();
            if(sequence<=previous||sequence>end)throw new IOException("中心任务事件序列重复、倒退或超过截止点");
            Map<String,Object> payload=new java.util.LinkedHashMap<>(map(item.path("payload")));
            payload.put("taskId",taskId);result.add(new Event(sequence,type,payload,type.matches("TASK_(COMPLETED|FAILED|CANCELLED)")));
            previous=sequence;
        }
        if(cursor!=previous||data.path("hasMore").asBoolean()&&(result.isEmpty()||cursor>=end))
            throw new IOException("中心任务事件游标未对应实际完整批次");
        return List.copyOf(result);
    }
    @Override public CommandResult readLedger(String sessionId,long afterSequence,long endSequence,int limit)throws IOException{
        if(afterSequence<0||endSequence<afterSequence||limit<1||limit>200)throw new IOException("原始账本游标、截止序列或批量大小非法");
        JsonNode data=center.get("/paicli/v1/sessions/"+id(sessionId)+"/ledger?afterSequence="+afterSequence+"&endSequence="+endSequence+"&limit="+limit);
        if(!data.isObject()||!data.path("entries").isArray()||data.path("endSequence").asLong(-1)!=endSequence)throw new IOException("中心账本分页响应与固定截止序列不符");
        return new CommandResult(true,"账本分页已读取",map(data));
    }
    @Override public CommandResult readArtifactText(String sessionId,String artifactId,long offset,int limit)throws IOException{
        if(offset<0||limit<4||limit>1024*1024)throw new IOException("产物字节游标或分段上限非法");
        JsonNode data=center.get("/paicli/v1/artifacts/"+id(artifactId)+"/text?sessionId="+id(sessionId)+"&offset="+offset+"&limit="+limit);
        long size=data.path("size").asLong(-1),next=data.path("nextOffset").asLong(-1);
        String text=data.path("text").asText();
        if(!data.isObject()||!data.path("text").isTextual()||!artifactId.equals(data.path("artifactId").asText())
                ||!sessionId.equals(data.path("sessionId").asText())||!data.path("sha256").asText().matches("[a-f0-9]{64}")
                ||!data.path("size").isIntegralNumber()||!data.path("offset").isIntegralNumber()||!data.path("nextOffset").isIntegralNumber()
                ||data.path("offset").asLong(-1)!=offset||size<offset||size>128L*1024*1024
                ||next<offset||next>size||next-offset>limit||next-offset!=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                ||!data.path("hasMore").isBoolean()||data.path("hasMore").asBoolean()!=(next<size)
                ||next==offset&&next<size)throw new IOException("中心产物分页响应与会话、摘要或字节游标不符");
        return new CommandResult(true,"产物分段已读取",map(data));
    }
    @Override public void cancel(String sessionId, String turnId) throws IOException {
        JsonNode task = center.get("/paicli/v1/tasks/" + id(turnId));
        if (!id(sessionId).equals(task.path("sessionId").asText())) throw new IOException("任务不属于当前会话");
        ObjectNode body = CanonicalJson.MAPPER.createObjectNode(); body.put("version", task.path("version").asLong());
        center.mutate("POST", "/paicli/v1/tasks/" + id(turnId) + "/cancel", body, UUID.randomUUID().toString(), task.path("version").asText());
    }
    private static String id(String id) { if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("无效中心标识"); return id; }
    private static Map<String,Object> map(JsonNode value) { return value.isObject() ? CanonicalJson.MAPPER.convertValue(value, new TypeReference<Map<String,Object>>() {}) : Map.of(); }
    @Override public void close() { center.close(); }
}
