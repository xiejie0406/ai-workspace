package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.*;
import com.ruoyi.paiflow.mapper.WorkflowDialogMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 实际Agent/WF/build/Run与显示Mapper、类代理事务和MVC；仅随机H2，不执行真实业务库。 */
class WorkflowDialogPersistenceTest extends WorkflowDebugTestFixture {
    private static final String BASE="/paiflow/legacy/workflow";
    private static final Set<String> VIEW_FIELDS=Set.of("id","uid","workflowId","question","answer","data","sid",
            "questionItem","answerItem","createTime","deleted","type","chatId");
    private static final String QUESTION="[{\"name\":\"question\",\"default\":\"中文\\n原行\",\"schema\":{\"type\":\"string\",\"value\":{\"content\":\"中文\"}},\"required\":false,\"zero\":0,\"future\":null}]";
    private static final String ANSWER="{\"messageContent\":\"\",\"reasoningContent\":\"推理\",\"content\":\"回复\\n下一行\",\"option\":null,\"zero\":0,\"flag\":false,\"future\":{\"nil\":null}}";
    private WorkflowDialogMapper dialogMapper;
    private WorkflowDialogService dialogs;
    private LegacyDebugBridgeService bridge;
    private JSONObject agent,built,chat;
    private LegacyDebugBridgeService.Attachment attachment;

    @BeforeEach void setupDialogs() throws Exception {
        String ddl=Files.readString(Path.of("sql/019-paiflow-workflow-dialog.sql"),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        // 沿父夹具保持创建CHECK的schema connection存活；不删除或放宽019正式约束。
        try(var statement=schema.createStatement()){for(String sql:ddl.split(";"))if(!sql.isBlank())statement.execute(sql);}
        var configuration=sessions.getSqlSessionFactory().getConfiguration();
        try(var input=getClass().getResourceAsStream("/mapper/paiflow/WorkflowDialogMapper.xml")) {
            assertNotNull(input);
            // 父夹具UTC_TIMESTAMP alias只有0参数；DATETIME(3)保留DB精度。其余Mapper原SQL不改。
            String xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("UTC_TIMESTAMP(3)","UTC_TIMESTAMP()");
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),configuration,
                    "WorkflowDialogMapper-isolated-utc-alias",configuration.getSqlFragments()).parse();
        }
        dialogMapper=sessions.getMapper(WorkflowDialogMapper.class);
        bridge=proxy(new LegacyDebugBridgeService(workflows,defaults,access,runs,runMapper,checkpoints,checkpointMapper));
        dialogs=proxy(new WorkflowDialogService(dialogMapper,bridge,workflows,defaults,access));
    }

    private void start() {
        var graph=canvas();agent=createAgent(graph);built=ordinary(agent,graph,"dialog_build_"+UUID.randomUUID());
        chat=chatBody(built);attachment=bridge.chat(chat,header(),null);
    }
    private static JSONObject chatBody(JSONObject built) {
        var body=JSONObject.of("flow_id",built.get("flowId"),"inputs",JSONObject.of("question","实际原题面","opaqueNull",null),
                "chatId","dialog-chat","regen",false,"debugReceipt",receipt(built),"requestKey","dialog_run_"+UUID.randomUUID().toString().replace("-",""));
        if("comparison".equals(receipt(built).get("mode")))body.putAll(JSONObject.of("version",receipt(built).get("comparisonId"),"promptDebugger",true));
        return body;
    }
    private String header(){return Long.toString(space);}
    private long workflow(){return attachment.receipt().getLongValue("workflowId");}
    private JSONObject request() {
        return JSONObject.of("chatId","dialog-chat","type",1,"workflowId",Long.toString(workflow()),"sid",attachment.runId(),
                "questionItem",QUESTION,"answerItem","\"a\\nb\"","question",QUESTION,"answer",ANSWER);
    }
    private JSONObject claimAndBegin() {
        var task=leases.claim(worker);assertEquals(attachment.runId(),task.get("runId"));
        assertTrue(leases.beginExecution(attachment.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));return task;
    }
    private JSONArray image(String table,String order) throws Exception {
        var result=new JSONArray();
        try(var connection=db.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table+" ORDER BY "+order)) {
            int columns=rows.getMetaData().getColumnCount();while(rows.next()){var row=new JSONArray();for(int i=1;i<=columns;i++)row.add(rows.getString(i));result.add(row);}
        }
        return result;
    }
    private JSONObject runtimeTruth() throws Exception {
        return JSONObject.of("run",image("paiflow_run","id"),"events",image("paiflow_run_event","run_id,seq"),
                "checkpoint",image("paiflow_run_checkpoint","id"));
    }
    private void assertRejectedWithoutDisplayWrite(JSONObject body,int code) throws Exception {
        long count=rows("paiflow_workflow_dialog");var truth=runtimeTruth();
        assertEquals(code,denied(()->dialogs.save(body,header())));assertEquals(count,rows("paiflow_workflow_dialog"));assertEquals(truth,runtimeTruth());
    }
    private void joinOtherMember() throws Exception {
        assertEquals(1,sql("INSERT INTO sys_user_role VALUES(43,88)"));assertEquals(1,spaces.insertMember(space,43,3,"isolated-fixture"));
    }
    private void changeRun(String column,Object value) throws Exception {
        assertTrue(Set.of("owner_user_id","space_id","workflow_version_id","assignment_json").contains(column));
        try(var connection=db.getConnection();var statement=connection.prepareStatement("UPDATE paiflow_run SET "+column+"=? WHERE id=?")) {
            statement.setObject(1,value);statement.setString(2,attachment.runId());assertEquals(1,statement.executeUpdate());
        }
        sessions.clearCache();
    }

    @Test void repeatedOriginalPostWhileWaitingAppendsTwoDisplaySegmentsWithoutChangingExecutionTruth() throws Exception {
        start();var task=claimAndBegin();leases.append(attachment.runId(),worker,event(task,new JSONObject()));checkpoint(task);
        assertEquals("RUNNING",runMapper.find(attachment.runId()).getState());assertEquals(1,rows("paiflow_run_checkpoint"));
        var truth=runtimeTruth();var original=request();assertEquals("dialog-chat",dialogs.save(original,header()));assertEquals("dialog-chat",dialogs.save(copy(original),header()));
        assertEquals(2,rows("paiflow_workflow_dialog"));var rows=dialogMapper.list(space,workflow(),42,1);assertEquals(2,rows.size());assertNotEquals(rows.get(0).getId(),rows.get(1).getId());
        for(var row:rows){assertEquals(attachment.runId(),row.getRunId());assertEquals(row.getRunId(),row.getSid());assertEquals(attachment.workflowVersionId(),row.getWorkflowVersionId());assertEquals(1L,row.getEventWatermark());assertEquals(42L,row.getOwnerUserId());assertEquals(space,row.getSpaceId());assertFalse(row.getDeleted());}
        assertEquals(truth,runtimeTruth());
    }
    @Test void latestTenUseStableIdOrderWhenTheRealDatabaseTimesTie() throws Exception {
        start();for(int i=0;i<13;i++){var body=request();body.put("answerItem","segment-"+i);dialogs.save(body,header());}
        assertEquals(13,rows("paiflow_workflow_dialog"));assertEquals(13,sql("UPDATE paiflow_workflow_dialog SET create_time=TIMESTAMP '2026-10-03 00:00:00.123'"));
        var latest=dialogs.list(workflow(),1,header());assertEquals(10,latest.size());
        for(int i=0;i<10;i++)assertEquals("segment-"+(12-i),latest.get(i).answerItem());
        for(int i=1;i<latest.size();i++)assertTrue(latest.get(i-1).id()>latest.get(i).id());
    }
    @Test void clearDeletesOnlyCallerDisplayRowsAndKeepsAnotherRealMemberRunAndHistory() throws Exception {
        start();dialogs.save(request(),header());dialogs.save(request(),header());long sharedWorkflow=workflow();var firstAttachment=attachment;
        joinOtherMember();loginAs(43);
        var comparison=workflows.debugBuild(sharedWorkflow,space,workflowMapper.find(sharedWorkflow).getRevision(),buildBody(agent,canvas()),
                "comparison",UUID.randomUUID().toString(),"member_dialog_build");attachment=bridge.chat(chatBody(comparison),header(),null);
        assertEquals(43L,runMapper.find(attachment.runId()).getOwnerUserId());assertEquals(sharedWorkflow,attachment.receipt().getLongValue("workflowId"));dialogs.save(request(),header());
        assertEquals(1,dialogs.list(sharedWorkflow,1,header()).size());login();attachment=firstAttachment;var truth=runtimeTruth();
        assertEquals(2,dialogs.clear(sharedWorkflow,1,header()));assertEquals(0,dialogs.clear(sharedWorkflow,1,header()));assertEquals(0,dialogs.list(sharedWorkflow,1,header()).size());
        assertEquals(3,rows("paiflow_workflow_dialog"));assertEquals(2,sqlCount("SELECT COUNT(*) FROM paiflow_workflow_dialog WHERE owner_user_id=42 AND deleted=1"));
        assertEquals(1,sqlCount("SELECT COUNT(*) FROM paiflow_workflow_dialog WHERE owner_user_id=43 AND deleted=0"));assertEquals(truth,runtimeTruth());
        loginAs(43);assertEquals(1,dialogs.list(sharedWorkflow,1,header()).size());
    }
    @Test void serverBindingIgnoresForgedManagementEchoWithoutReplacingOpaqueDisplayStrings() throws Exception {
        start();var body=request();body.putAll(JSONObject.of("id",999,"uid","forged-owner","deleted",true,"createTime","1900-01-01",
                "spaceId",999,"ownerUserId",43,"workflowVersionId",999,"eventWatermark",999,"runId","forged","futureManagement",JSONObject.of("x",true)));
        body.put("data","opaque display: {unparsed}");long before=System.currentTimeMillis();dialogs.save(body,header());
        var row=dialogMapper.list(space,workflow(),42,1).get(0);assertNotEquals(999L,row.getId());assertEquals(42L,row.getOwnerUserId());assertEquals(space,row.getSpaceId());
        assertEquals(attachment.workflowVersionId(),row.getWorkflowVersionId());assertEquals(attachment.runId(),row.getSid());assertEquals(0L,row.getEventWatermark());assertFalse(row.getDeleted());
        assertTrue(row.getCreateTime().getTime()>=before-1000);assertEquals(QUESTION,row.getQuestion());assertEquals(ANSWER,row.getAnswer());assertEquals("opaque display: {unparsed}",row.getData());
        var view=dialogs.list(workflow(),1,header()).get(0);assertEquals(IdentityMappingService.legacyUid(42),view.uid());assertEquals("a\\nb",view.answerItem());
    }
    @Test void nullableDisplayFieldsAndLiteralJsonNullRemainDistinctAndQuoteHandlingDoesNotUnescape() throws Exception {
        start();for(String value:Arrays.asList(null,"null")){var body=request();body.put("question",value);body.put("answer",value);body.put("questionItem",null);body.put("answerItem",null);body.put("chatId",null);
            assertNull(dialogs.save(body,header()));var row=dialogMapper.list(space,workflow(),42,1).get(0);assertEquals(value,row.getQuestion());assertEquals(value,row.getAnswer());assertNull(row.getQuestionItem());assertNull(row.getChatId());assertNull(row.getAnswerItem());}
        var cases=new LinkedHashMap<String,String>();cases.put("","" );cases.put("\"\"","");cases.put("\"a\\nb\"","a\\nb");cases.put("[\"a\"]","[\"a\"]");cases.put("\"a\"tail","\"a\"tail");
        for(var item:cases.entrySet()){var body=request();body.put("answerItem",item.getKey());dialogs.save(body,header());assertEquals(item.getValue(),dialogMapper.list(space,workflow(),42,1).get(0).getAnswerItem());}
        var absent=request();for(String key:List.of("question","answer","questionItem","answerItem","chatId","data"))absent.remove(key);assertNull(dialogs.save(absent,header()));
        var row=dialogMapper.list(space,workflow(),42,1).get(0);assertNull(row.getQuestion());assertNull(row.getAnswer());assertNull(row.getData());
    }
    @Test void invalidStandardJsonAndUnsafeOriginalInputConversionsRejectBeforeAnyWrite() throws Exception {
        start();for(String bad:List.of("{}","'text'","[] trailing","[{'name':'question'}]","1","true","\"text\"")) {var body=request();body.put("question",bad);assertRejectedWithoutDisplayWrite(body,400);}
        for(String bad:List.of("[]","{'content':'single quote'}","{} {}","true","\"string\"")){var body=request();body.put("answer",bad);assertRejectedWithoutDisplayWrite(body,400);}
        var optionObject=request();optionObject.put("answer","{\"content\":\"有内容\",\"option\":{}}");assertRejectedWithoutDisplayWrite(optionObject,400);
        for(String field:List.of("messageContent","reasoningContent","content"))for(Object invalid:List.of(new JSONObject(),new JSONArray(),true,1)) {
            var body=request();var answer=JSON.parseObject(ANSWER);answer.put(field,invalid);body.put("answer",answer.toJSONString(JSONWriter.Feature.WriteMapNullValue));assertRejectedWithoutDisplayWrite(body,400);
        }
        var optionCounterexamples=List.of(JSONObject.of("id",new JSONObject(),"text","正常"),JSONObject.of("id",JSONArray.of(new JSONObject()),"text","正常"),
                JSONObject.of("id","A","text",new JSONObject()),JSONObject.of("id","A","text",JSONArray.of(new JSONObject())),
                JSONObject.of("id","A","content_type","image","text",JSONObject.of("toString",1)),JSONObject.of("id","A","content_type","image","text",JSONArray.of(JSONObject.of("toString",null))));
        for(var option:optionCounterexamples){var body=request();body.put("answer",JSONObject.of("content","有内容","option",JSONArray.of(option)).toJSONString(JSONWriter.Feature.WriteMapNullValue));assertRejectedWithoutDisplayWrite(body,400);}
        for(String bad:List.of("[{\"name\":\"question\",\"default\":{\"toString\":1}}]","[{\"name\":\"question\",\"default\":[{\"toString\":null}]}]")){var body=request();body.put("question",bad);assertRejectedWithoutDisplayWrite(body,400);}
        // 原MessageAsk只在多输入时渲染name；文件分支仅image/string隐藏首文件名。
        for(String bad:List.of("[{\"name\":{\"key\":\"x\"},\"default\":\"正文\"},{\"name\":\"B\",\"default\":\"\"}]",
                "[{\"name\":[{\"key\":\"x\"}],\"default\":\"正文\"},{\"name\":\"B\",\"default\":\"\"}]",
                "[{\"fileType\":\"file\",\"default\":[{\"name\":{\"key\":\"x\"}}]}]",
                "[{\"fileType\":\"file\",\"default\":[{\"name\":[{\"key\":\"x\"}]}]}]",
                "[{\"fileType\":\"image\",\"type\":\"array\",\"default\":[{\"name\":{\"key\":\"x\"}}]}]")) {
            var body=request();body.put("question",bad);assertRejectedWithoutDisplayWrite(body,400);
        }
        for(String allowed:List.of("[{\"name\":{\"key\":\"x\"},\"default\":\"正文\"}]",
                "[{\"name\":null,\"default\":\"正文\"},{\"name\":\"B\",\"default\":\"\"}]",
                "[{\"name\":false,\"default\":\"正文\"},{\"name\":0,\"default\":\"\"}]",
                "[{\"name\":[\"A\",0,false,null],\"default\":\"正文\"},{\"name\":\"B\",\"default\":\"\"}]",
                "[{\"fileType\":\"file\",\"default\":[{\"name\":[\"文件\",0,false,null]}]}]",
                "[{\"fileType\":\"file\",\"default\":[{\"name\":null}]}]",
                "[{\"fileType\":\"file\",\"default\":[null]}]",
                "[{\"fileType\":\"image\",\"type\":\"string\",\"default\":[{\"name\":{\"key\":\"x\"},\"url\":\"private-no-fetch\"}]}]",
                "[{\"fileType\":false,\"default\":[{\"name\":{\"key\":\"x\"}}]}]")) {
            var body=request();body.put("question",allowed);dialogs.save(body,header());assertEquals(allowed,dialogs.list(workflow(),1,header()).get(0).question());
        }
        var escaped=request();escaped.put("answer","{\"content\":\"\\ud800\"}");assertRejectedWithoutDisplayWrite(escaped,400);
        var outside=request();outside.put("answerItem",String.valueOf((char)0xd800));assertRejectedWithoutDisplayWrite(outside,400);
        var file=request();file.put("question","[{\"name\":\"file\",\"fileType\":\"pdf\",\"default\":[{\"toString\":\"opaque\",\"url\":\"fixture-only\"}]}]");dialogs.save(file,header());assertEquals(file.get("question"),dialogs.list(workflow(),1,header()).get(0).question());
        for(Object falsy:Arrays.asList(0,false,null,"")) {
            var body=request();body.put("answer",JSONObject.of("messageContent",falsy,"reasoningContent",falsy,"content",falsy,"option",falsy,"future",JSONObject.of("nil",null)).toJSONString(JSONWriter.Feature.WriteMapNullValue));
            dialogs.save(body,header());assertEquals(body.get("answer"),dialogs.list(workflow(),1,header()).get(0).answer());
        }
        var hiddenOptions=request();hiddenOptions.put("answer","{\"messageContent\":\"\",\"reasoningContent\":null,\"content\":false,\"option\":{},\"future\":null}");
        dialogs.save(hiddenOptions,header());assertEquals(hiddenOptions.get("answer"),dialogs.list(workflow(),1,header()).get(0).answer());
        var primitiveOptions=request();primitiveOptions.put("answer","{\"content\":\"有内容\",\"option\":[null,false,0,\"unused\",{\"id\":null,\"text\":false},{\"id\":[0,false,null,\"A\"],\"text\":[0,false,\"中文\"]}]}");
        dialogs.save(primitiveOptions,header());assertEquals(primitiveOptions.get("answer"),dialogs.list(workflow(),1,header()).get(0).answer());
    }
    @Test void saveTypeIsExplicitAndOptionalQueryTypeNeverBecomesAnAllTypesReadOrClear() throws Exception {
        start();dialogs.save(request(),header());var truth=runtimeTruth();
        for(Object invalid:Arrays.asList(null,0,3,-1,true,1.0,"01")){var body=request();body.put("type",invalid);assertRejectedWithoutDisplayWrite(body,400);}
        var missing=request();missing.remove("type");assertRejectedWithoutDisplayWrite(missing,400);var app=request();app.put("type",2);assertRejectedWithoutDisplayWrite(app,503);
        assertEquals(List.of(),dialogs.list(workflow(),null,header()));assertEquals(0,dialogs.clear(workflow(),null,header()));assertEquals(1,dialogs.list(workflow(),1,header()).size());
        assertEquals(503,denied(()->dialogs.list(workflow(),2,header())));assertEquals(503,denied(()->dialogs.clear(workflow(),2,header())));
        assertEquals(400,denied(()->dialogs.list(workflow(),0,header())));assertEquals(400,denied(()->dialogs.clear(workflow(),3,header())));assertEquals(truth,runtimeTruth());
        assertEquals(404,denied(()->dialogs.list(workflow()+1000,null,header())));assertEquals(404,denied(()->dialogs.clear(workflow()+1000,null,header())));
    }
    @Test void callerScopeWorkflowAndSidCannotBeGuessedFromTheCurrentOrLatestRun() throws Exception {
        start();for(String sid:List.of("","old-spark-sid",UUID.randomUUID().toString())){var body=request();body.put("sid",sid);assertRejectedWithoutDisplayWrite(body,sid.isEmpty()?400:404);}
        var nullSid=request();nullSid.put("sid",null);assertRejectedWithoutDisplayWrite(nullSid,400);
        var other=createAgent(canvas());ordinary(other,canvas(),"other_dialog_build");var wrong=request();wrong.put("workflowId",other.get("workflowId"));assertRejectedWithoutDisplayWrite(wrong,404);
        assertEquals(404,denied(()->dialogs.save(request(),Long.toString(space+1000))));assertEquals(0,rows("paiflow_workflow_dialog"));
        joinOtherMember();loginAs(43);assertEquals(404,denied(()->dialogs.save(request(),header())));assertEquals(0,rows("paiflow_workflow_dialog"));
    }
    @Test void corruptedRunOwnerSpaceOrVersionCannotBecomeAuthoritativeDisplayBinding() throws Exception {
        start();long ownVersion=attachment.workflowVersionId();changeRun("owner_user_id",43);assertRejectedWithoutDisplayWrite(request(),404);changeRun("owner_user_id",42);
        changeRun("space_id",space+1000);assertRejectedWithoutDisplayWrite(request(),404);changeRun("space_id",space);
        var other=createAgent(canvas());var foreign=ordinary(other,canvas(),"foreign_display_version");changeRun("workflow_version_id",receipt(foreign).getLongValue("workflowVersionId"));assertRejectedWithoutDisplayWrite(request(),404);
        changeRun("workflow_version_id",ownVersion);assertEquals("dialog-chat",dialogs.save(request(),header()));assertEquals(1,rows("paiflow_workflow_dialog"));
    }
    @Test void fixedAssignmentDamageFailsClosedInsteadOfSavingARecordForMatchingColumns() throws Exception {
        start();String original=runMapper.find(attachment.runId()).getAssignmentJson();var assignment=JSON.parseObject(original);
        for(String field:List.of("dslHash","dslCanonicalBase64","engineProfile","workflowVersionId","resourceSnapshots","dsl","input")) {
            var tampered=copy(assignment);
            switch(field){case "dslHash"->tampered.put(field,"0".repeat(64));case "dslCanonicalBase64"->tampered.put(field,"e30=");case "engineProfile"->tampered.put(field,"java");case "workflowVersionId"->tampered.put(field,"999999");case "resourceSnapshots"->tampered.put(field,JSONObject.of("forged",new JSONObject()));case "dsl"->tampered.getJSONObject(field).put("forged",true);case "input"->tampered.put(field,null);default->fail();}
            changeRun("assignment_json",tampered.toJSONString(JSONWriter.Feature.WriteMapNullValue));assertRejectedWithoutDisplayWrite(request(),409);changeRun("assignment_json",original);
        }
        assertEquals(0,rows("paiflow_workflow_dialog"));
    }
    @Test void currentPlatformPermissionsAndMembershipRevocationPreventWritesAndReads() throws Exception {
        start();dialogs.save(request(),header());var truth=runtimeTruth();
        for(String permission:List.of("paiflow:workflow:edit","paiflow:workflow:query","paiflow:run:query")){revoke(permission);assertEquals(403,denied(()->dialogs.save(request(),header())));assertEquals(1,rows("paiflow_workflow_dialog"));assertEquals(1,sql("UPDATE sys_menu SET status='0' WHERE perms='"+permission+"'"));}
        revoke("paiflow:workflow:query");assertEquals(403,denied(()->dialogs.list(workflow(),null,header())));assertEquals(1,sql("UPDATE sys_menu SET status='0' WHERE perms='paiflow:workflow:query'"));
        revoke("paiflow:workflow:edit");assertEquals(403,denied(()->dialogs.clear(workflow(),null,header())));assertEquals(1,sql("UPDATE sys_menu SET status='0' WHERE perms='paiflow:workflow:edit'"));
        assertEquals(1,sql("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space+" AND user_id=42"));
        assertEquals(404,denied(()->dialogs.save(request(),header())));assertEquals(404,denied(()->dialogs.list(workflow(),1,header())));assertEquals(404,denied(()->dialogs.clear(workflow(),1,header())));
        assertEquals(1,sqlCount("SELECT COUNT(*) FROM paiflow_workflow_dialog WHERE deleted=0"));assertEquals(truth,runtimeTruth());
        assertEquals(1,sql("UPDATE paiflow_space_member SET status='active' WHERE space_id="+space+" AND user_id=42"));
        // 实际Mapper写入后，独立已提交菜单撤销使末尾实时检查失败；本次insert必须随事务回滚。
        var inserted=new java.util.concurrent.atomic.AtomicBoolean();
        WorkflowDialogMapper revokeAfterRealInsert=new WorkflowDialogMapper() {
            public int insert(com.ruoyi.paiflow.domain.WorkflowDialogRecord row) {
                int affected=dialogMapper.insert(row);assertEquals(1,affected);inserted.set(true);
                try {revoke("paiflow:run:query");}catch(Exception ex){throw new AssertionError(ex);}return affected;
            }
            public List<com.ruoyi.paiflow.domain.WorkflowDialogRecord> list(long s,long w,long o,int t){return dialogMapper.list(s,w,o,t);}
            public int clear(long s,long w,long o,int t){return dialogMapper.clear(s,w,o,t);}
        };
        var raced=proxy(new WorkflowDialogService(revokeAfterRealInsert,bridge,workflows,defaults,access));
        assertEquals(403,denied(()->raced.save(request(),header())));assertTrue(inserted.get());assertEquals(1,rows("paiflow_workflow_dialog"));assertEquals(truth,runtimeTruth());
        assertEquals(1,sqlCount("SELECT COUNT(*) FROM sys_menu WHERE perms='paiflow:run:query' AND status='1'"));
    }
    @Test void displayBudgetUsesUtf8TotalResultLimitAndDoesNotBorrowSmallerRunInputLimit() throws Exception {
        start();var task=claimAndBegin();String answer="a".repeat(1_100_000);
        var actualResult=result(task,"COMPLETED",JSONObject.of("answer",answer));byte[] resultBytes=actualResult.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
        assertTrue(resultBytes.length<RunProtocolLimits.RESULT_BYTES);var resultAck=leases.result(attachment.runId(),worker,actualResult);
        assertTrue(resultAck.getBooleanValue("accepted"));assertEquals("COMPLETED",resultAck.get("state"));assertEquals("COMPLETED",runMapper.find(attachment.runId()).getState());
        assertEquals(answer,JSON.parseObject(runMapper.find(attachment.runId()).getResultJson()).getJSONObject("output").get("answer"));
        var beforeDisplay=runtimeTruth();var original=request();original.put("answer",JSONObject.of("content",answer).toJSONString());original.put("answerItem",JSON.toJSONString(answer));
        String originalWire=original.toJSONString(JSONWriter.Feature.WriteMapNullValue);byte[] originalBytes=originalWire.getBytes(StandardCharsets.UTF_8);
        assertTrue(originalBytes.length>RunProtocolLimits.RESULT_BYTES);assertTrue(originalBytes.length<20*1024*1024);
        var converter=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        var mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowDialogController(dialogs)).setMessageConverters(converter).build();
        var response=mvc.perform(post(BASE+"/dialog").header("space-id",header()).contentType(MediaType.APPLICATION_JSON).content(originalWire))
                .andExpect(status().isOk()).andReturn().getResponse();assertEquals(0,json(response).getIntValue("code"));assertEquals("dialog-chat",json(response).get("data"));
        var saved=dialogMapper.list(space,workflow(),42,1).get(0);assertEquals(original.get("question"),saved.getQuestion());assertEquals(original.get("answer"),saved.getAnswer());assertEquals(answer,saved.getAnswerItem());
        assertEquals(attachment.runId(),saved.getRunId());assertEquals(attachment.workflowVersionId(),saved.getWorkflowVersionId());assertEquals(1L,saved.getEventWatermark());assertEquals(beforeDisplay,runtimeTruth());
        for(String small:List.of("x".repeat(300*1024),"😀".repeat(100))) {
            var body=request();body.put("questionItem",small);dialogs.save(body,header());assertEquals(small,dialogs.list(workflow(),1,header()).get(0).questionItem());
        }
        var oversizedBody=request();oversizedBody.put("questionItem","b".repeat(11*1024*1024));oversizedBody.put("data","c".repeat(11*1024*1024));
        byte[] oversizedBodyBytes=oversizedBody.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
        assertTrue(oversizedBodyBytes.length>20*1024*1024);assertRejectedWithoutDisplayWrite(oversizedBody,413);
        var oversizedField=request();String multibyte="中".repeat(6_000_000);oversizedField.put("data",multibyte);
        int fieldBytes=multibyte.getBytes(StandardCharsets.UTF_8).length;byte[] oversizedFieldBodyBytes=oversizedField.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
        assertTrue(fieldBytes>16*1024*1024-1);assertTrue(oversizedFieldBodyBytes.length<20*1024*1024);assertRejectedWithoutDisplayWrite(oversizedField,413);
        assertEquals(2*1024*1024,RunProtocolLimits.RESULT_BYTES);assertEquals(16*1024,RunProtocolLimits.CONTROL_BYTES);
        var beforeRejectedResult=runtimeTruth();assertEquals(413,denied(()->leases.result(attachment.runId(),worker,result(task,"COMPLETED",JSONObject.of("answer","d".repeat(RunProtocolLimits.RESULT_BYTES))))));
        assertEquals(413,denied(()->RunJsonBoundary.check(JSONObject.of("overflow","e".repeat(RunProtocolLimits.CONTROL_BYTES)),RunProtocolLimits.CONTROL_BYTES)));assertEquals(beforeRejectedResult,runtimeTruth());
        var budgetEvidence=JSONObject.of("scope","isolated H2 actual Run result and real Mapper/Service/MockMVC; large wire deliberately omitted",
                "runResultUTF8Bytes",resultBytes.length,"runResultSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resultBytes)),
                "originalEightFieldDisplayUTF8Bytes",originalBytes.length,"originalEightFieldDisplaySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes)),
                "storedAnswerUTF8Bytes",saved.getAnswer().getBytes(StandardCharsets.UTF_8).length,"storedAnswerSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.getAnswer().getBytes(StandardCharsets.UTF_8))),
                "storedAnswerItemUTF8Bytes",saved.getAnswerItem().getBytes(StandardCharsets.UTF_8).length,"storedAnswerItemSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.getAnswerItem().getBytes(StandardCharsets.UTF_8))),
                "actualBoundRow",JSONObject.of("id",saved.getId(),"runId",saved.getRunId(),"workflowId",saved.getWorkflowId(),"workflowVersionId",saved.getWorkflowVersionId(),"spaceId",saved.getSpaceId(),"ownerUserId",saved.getOwnerUserId(),"eventWatermark",saved.getEventWatermark()),
                "storedOriginalAnswerEqual",true,"storedOriginalAnswerItemAfterOuterQuoteRemovalEqual",true,"wholeBodyRejectedUTF8Bytes",oversizedBodyBytes.length,
                "fieldRejectedUTF8Bytes",fieldBytes,"fieldRejectedWholeBodyUTF8Bytes",oversizedFieldBodyBytes.length,"runResultLimitUnchanged",RunProtocolLimits.RESULT_BYTES,
                "controlLimitUnchanged",RunProtocolLimits.CONTROL_BYTES,"runtimeTruthUnchangedByDisplay",true,"businessVerified",false);
        Path path=Path.of("target/workflow-dialog-budget-actual-evidence.json");Files.createDirectories(path.getParent());Files.writeString(path,budgetEvidence.toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    @Test void defaultScopeReadsDoNotMaterializeAndRealDefaultRunMapsWithoutNamedSpaceFallback() throws Exception {
        start();long before=rows("paiflow_space");assertEquals(404,denied(()->dialogs.list(workflow(),null,"")));assertEquals(404,denied(()->dialogs.clear(workflow(),null,null)));assertEquals(before,rows("paiflow_space"));
        var graph=canvas();var defaultAgent=agents.legacyCreate("",3,JSONObject.of("name","default_dialog_fixture","data",graph.toJSONString(JSONWriter.Feature.WriteMapNullValue)));
        long id=defaultAgent.getLongValue("workflowId"),defaultSpace=workflowMapper.find(id).getSpaceId();assertNotEquals(space,defaultSpace);
        var fixed=workflows.debugBuild(id,defaultSpace,0L,buildBody(defaultAgent,graph),"ordinary",null,"default_dialog_build");
        attachment=bridge.chat(chatBody(fixed),"",null);var body=request();assertEquals("dialog-chat",dialogs.save(body,null));assertEquals(1,dialogs.list(id,1,"").size());
        assertEquals(defaultSpace,dialogMapper.list(defaultSpace,id,42,1).get(0).getSpaceId());assertEquals(404,denied(()->dialogs.list(id,1,header())));
    }
    @Test void actualMvcOriginalRequestReturnsOnlyThirteenNullableFieldsAndClearCountWithoutRuntimeMutation() throws Exception {
        start();var task=claimAndBegin();checkpoint(task);var before=runtimeTruth();
        var converter=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        var mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowDialogController(dialogs)).setMessageConverters(converter).build();
        var original=request();original.put("chatId",null);
        var saved=mvc.perform(post(BASE+"/dialog").header("space-id",header()).contentType(MediaType.APPLICATION_JSON).content(original.toJSONString(JSONWriter.Feature.WriteMapNullValue)))
                .andExpect(status().isOk()).andReturn().getResponse();assertEquals(0,json(saved).getIntValue("code"));assertTrue(json(saved).containsKey("data"));assertNull(json(saved).get("data"));
        long originalId=dialogMapper.list(space,workflow(),42,1).get(0).getId();var nullableCases=new JSONArray();
        for(boolean literal:List.of(true,false)) {
            var sample=request();sample.put("question",literal?"null":null);sample.put("questionItem",null);sample.put("answerItem",null);sample.put("chatId",literal?"falsy-group":"nullable-group");
            sample.put("answer",literal?"{\"messageContent\":0,\"reasoningContent\":false,\"content\":null,\"option\":false,\"future\":{\"nil\":null}}":null);
            String requestWire=sample.toJSONString(JSONWriter.Feature.WriteMapNullValue);
            var response=mvc.perform(post(BASE+"/dialog").header("space-id",header()).contentType(MediaType.APPLICATION_JSON).content(requestWire))
                    .andExpect(status().isOk()).andReturn().getResponse();assertEquals(sample.get("chatId"),json(response).get("data"));
            nullableCases.add(JSONObject.of("requestBody",sample,"saveResponse",json(response),"requestUTF8Base64",Base64.getEncoder().encodeToString(requestWire.getBytes(StandardCharsets.UTF_8)),
                    "responseUTF8Base64",Base64.getEncoder().encodeToString(response.getContentAsByteArray())));
        }
        var listed=mvc.perform(get(BASE+"/dialog/list").header("space-id",header()).param("workflowId",Long.toString(workflow())).param("type","1"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        var result=json(listed);assertEquals(0,result.getIntValue("code"));var views=result.getJSONArray("data");assertEquals(3,views.size());
        for(Object item:views){var value=(JSONObject)item;assertEquals(VIEW_FIELDS,value.keySet());assertEquals(workflow(),value.getLongValue("workflowId"));assertEquals(IdentityMappingService.legacyUid(42),value.get("uid"));assertEquals(attachment.runId(),value.get("sid"));}
        var view=views.stream().map(item->(JSONObject)item).filter(item->item.getLongValue("id")==originalId).findFirst().orElseThrow();
        assertEquals(QUESTION,view.get("question"));assertEquals(ANSWER,view.get("answer"));assertEquals("a\\nb",view.get("answerItem"));assertNull(view.get("chatId"));assertNull(view.get("data"));assertNotNull(view.get("createTime"));assertEquals(Boolean.FALSE,view.get("deleted"));
        var afterDisplay=runtimeTruth();assertEquals(before,afterDisplay);
        var cleared=mvc.perform(get(BASE+"/dialog/clear").header("space-id",header()).param("workflowId",Long.toString(workflow())).param("type","1"))
                .andExpect(status().isOk()).andReturn().getResponse();assertEquals(3,json(cleared).getIntValue("data"));
        var clearAgain=mvc.perform(get(BASE+"/dialog/clear").header("space-id",header()).param("workflowId",Long.toString(workflow())).param("type","1"))
                .andExpect(status().isOk()).andReturn().getResponse();assertEquals(0,json(clearAgain).getIntValue("data"));
        var afterClearList=mvc.perform(get(BASE+"/dialog/list").header("space-id",header()).param("workflowId",Long.toString(workflow())).param("type","1"))
                .andExpect(status().isOk()).andReturn().getResponse();assertEquals(new JSONArray(),json(afterClearList).getJSONArray("data"));var afterClear=runtimeTruth();assertEquals(before,afterClear);
        byte[] bytes=listed.getContentAsByteArray();var headers=new JSONObject();for(String name:listed.getHeaderNames())headers.put(name,new JSONArray(listed.getHeaders(name)));
        var artifact=JSONObject.of("scope","isolated H2+actual Mapper/Service/MockMVC; no live Centre/JWT/Redis/browser", "sourceGolden","b948df26f7d350d51345e89cf7cdce6ea97367f6329e636c953575521f08bf9d",
                "fixtureLimits","parent fixture uses current platform SQL/Menu and H2; Mapper UTC_TIMESTAMP(3) adapted only to existing zero-argument UTC alias; no mock login token or actual JWT",
                "actualRequestBody",original,"actualSaveReply",json(saved),"saveResponseUTF8Base64",Base64.getEncoder().encodeToString(saved.getContentAsByteArray()),
                "nullFalsySavedCases",nullableCases,"listResponse",result,"actualClearReply",json(cleared),"clearAgainResponse",json(clearAgain),"listAfterClearResponse",json(afterClearList),
                "runtimeSnapshots",JSONObject.of("beforeDisplay",before,"afterSaveAndList",afterDisplay,"afterClear",afterClear),
                "actualBinding",JSONObject.of("runId",attachment.runId(),"workflowId",workflow(),"workflowVersionId",attachment.workflowVersionId(),"spaceId",space,"ownerUserId",runMapper.find(attachment.runId()).getOwnerUserId(),"eventWatermark",runMapper.find(attachment.runId()).getLastEventSeq()),
                "actualStatus",listed.getStatus(),"actualContentType",listed.getContentType(),
                "actualHeaders",headers,"bodyUTF8Base64",Base64.getEncoder().encodeToString(bytes),"bodySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "body",result,"runId",attachment.runId(),"workflowId",workflow(),"workflowVersionId",attachment.workflowVersionId(),"runtimeTruthUnchanged",true,"businessVerified",false);
        Path path=Path.of("target/workflow-dialog-actual-response.json");Files.createDirectories(path.getParent());Files.writeString(path,artifact.toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    private long sqlCount(String query) throws Exception {try(var connection=db.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertTrue(rows.next());return rows.getLong(1);}}
    private static JSONObject json(MockHttpServletResponse response) throws Exception {return JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8));}
}
