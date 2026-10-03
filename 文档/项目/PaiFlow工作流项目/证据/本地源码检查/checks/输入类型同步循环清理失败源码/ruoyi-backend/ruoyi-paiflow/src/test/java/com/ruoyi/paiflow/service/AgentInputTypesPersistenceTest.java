package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.LegacyAgentWorkflowController;
import com.ruoyi.paiflow.mapper.AgentMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同一实际平台User/Menu与业务Mapper会话；复用既有隔离Fixture，不改QA/Run源。 */
class AgentInputTypesPersistenceTest extends StaticQuestionAnswerTestFixture {
    AgentMapper agentsMapper;AgentService agents;DefaultPersonalSpaceService defaults;
    @BeforeEach void setupAgent() throws Exception {
        try(var s=schema.createStatement()){
            for(String file:List.of("004-paiflow-membership.sql","007-paiflow-agent.sql","010-paiflow-default-personal.sql","011-paiflow-agent-owner-guard.sql")){
                String ddl=Files.readString(Path.of("sql",file)).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("DROP CHECK","DROP CONSTRAINT")
                    .replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                    .replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:mysqlIfToCase(ddl).split(";"))if(!statement.isBlank())s.execute(statement);
            }
            int id=200;for(String permission:List.of("paiflow:agent:query","paiflow:agent:create","paiflow:agent:edit","paiflow:agent:copy","paiflow:agent:remove")){id++;s.execute("INSERT INTO sys_menu VALUES("+id+",'"+permission+"','0')");s.execute("INSERT INTO sys_role_menu VALUES(88,"+id+")");}
        }
        var configuration=sessions.getSqlSessionFactory().getConfiguration();try(var xml=getClass().getResourceAsStream("/mapper/paiflow/AgentMapper.xml")){assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"AgentMapper",configuration.getSqlFragments()).parse();}agentsMapper=sessions.getMapper(AgentMapper.class);
        defaults=proxy(new DefaultPersonalSpaceService(spaces,identities,access));var target=new AgentService(agentsMapper,access,identities,workflows);target.useDefaultSpaces(defaults);agents=proxy(target);
        var beans=new StaticListableBeanFactory();beans.addBean("agentBindings",agents);workflows.useAgentBindings(beans.getBeanProvider(WorkflowService.AgentBinding.class));
    }
    JSONArray fields() {return JSONArray.of(JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string"),"required",true),JSONObject.of("name","document","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of("PDF","image"),"fileType","file","required",true));}
    JSONObject createAgent(JSONArray fields){var graph=WorkflowInputTypesPolicyTest.graph(fields);graph.put("future",JSONObject.of("nullValue",null,"keep",true));String raw=" \n"+graph.toJSONString(JSONWriter.Feature.WriteMapNullValue)+" \n";return agents.create(space,3,JSONObject.of("name","输入同步fixture","data",raw,"futureConfig",JSONObject.of("nullValue",null,"keep",true)));}
    AgentService.InputTypesResult sync(long id){return agents.legacyInputTypes(id,Long.toString(space));}
    JSONObject config(long id){sessions.clearCache();return JSON.parseObject(agentsMapper.find(id).getConfigJson());}
    @Test void actualOrderedResponseMetadataSourceAndRevisionAreBoundWithoutChangingCanvas() {
        var created=createAgent(fields());long id=created.getLongValue("id"),wf=created.getLongValue("workflowId");String raw=workflowMapper.find(wf).getCanvasJson();long wfRevision=workflowMapper.find(wf).getRevision();var result=sync(id);
        assertEquals(2,result.fields().size());assertEquals("AGENT_USER_INPUT",result.fields().getJSONObject(0).get("name"));assertEquals(2,result.agentRevision());assertEquals(wfRevision,result.workflowRevision());var config=config(id);assertEquals(1,JSON.parseArray(config.getString("extraInputsConfig")).size());assertEquals("pdf",JSON.parseObject(config.getString("extraInputs")).get("type"));assertEquals(1,config.getIntValue("supportUpload"));assertEquals(wf,config.getLongValue("inputTypesWorkflowId"));assertEquals(wfRevision,config.getLongValue("inputTypesWorkflowRevision"));assertTrue(config.getJSONObject("futureConfig").containsKey("nullValue"));assertEquals(raw,workflowMapper.find(wf).getCanvasJson());assertEquals(wfRevision,workflowMapper.find(wf).getRevision());
    }
    @Test void repeatedSyncOfSameSourceAndMetadataIsReadOnlyIdempotent() {var created=createAgent(fields());long id=created.getLongValue("id");var first=sync(id);String config=agentsMapper.find(id).getConfigJson();var second=sync(id);assertEquals(first.agentRevision(),second.agentRevision());assertEquals(config,agentsMapper.find(id).getConfigJson());}
    @Test void laterCanvasRevisionIsRecordedOnlyByExplicitSyncAndKeepsUnknownConfigNull() {
        var created=createAgent(fields());long id=created.getLongValue("id"),wf=created.getLongValue("workflowId");assertEquals("unconfigured",agents.detail(id,space).get("inputTypesSynchronizationStatus"));sync(id);assertEquals("current",agents.detail(id,space).get("inputTypesSynchronizationStatus"));var raw=WorkflowInputTypesPolicyTest.graph(JSONArray.of(JSONObject.of("name","picture","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of("image"))));workflows.save(wf,space,0L,JSONObject.of("data",raw));assertEquals("stale",agents.detail(id,space).get("inputTypesSynchronizationStatus"));assertEquals(0,config(id).getLongValue("inputTypesWorkflowRevision"));assertTrue(config(id).getJSONObject("futureConfig").containsKey("nullValue"));var synced=sync(id);assertEquals(1,synced.workflowRevision());assertEquals("current",agents.detail(id,space).get("inputTypesSynchronizationStatus"));assertEquals(1,config(id).getLongValue("inputTypesWorkflowRevision"));assertEquals(2,config(id).getIntValue("supportUpload"));
    }
    @Test void onlyAgentEditAndWorkflowQueryAreNeededAndMissingAgentEditWritesNothing() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");revoke("paiflow:workflow:edit");assertEquals(2,sync(id).agentRevision());revoke("paiflow:agent:edit");String before=agentsMapper.find(id).getConfigJson();assertEquals(403,denied(()->sync(id)));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(2,agentsMapper.find(id).getRevision());
    }
    @Test void currentAccountAndMembershipRevocationRefuseWithNoMetadataWrite() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");String before=agentsMapper.find(id).getConfigJson();assertEquals(1,sql("UPDATE sys_user SET status='1' WHERE user_id=42"));assertEquals(401,denied(()->sync(id)));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(1,sql("UPDATE sys_user SET status='0' WHERE user_id=42"));assertEquals(1,sql("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space+" AND user_id=42"));assertEquals(404,denied(()->sync(id)));assertEquals(before,agentsMapper.find(id).getConfigJson());
    }
    @Test void emptyNullFirstAndSchemaLessFilesAllRejectWithZeroDerivedUpdate() {
        for(JSONArray fields:List.of(JSONArray.of(JSONObject.of("name","file","schema",JSONObject.of("type","string"),"allowedFileType",new JSONArray())),JSONArray.of(JSONObject.of("name","file","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of(null,"image"))),JSONArray.of(JSONObject.of("name","file","schema",null,"allowedFileType",JSONArray.of("image"))))){var created=createAgent(fields);long id=created.getLongValue("id");String before=agentsMapper.find(id).getConfigJson();assertEquals(400,denied(()->sync(id)));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(1,agentsMapper.find(id).getRevision());agents.delete(id,space,1L,0L);}
    }
    @Test void metadataBudgetFailureDoesNotPatchAgentOrWorkflow() {
        var created=createAgent(JSONArray.of(JSONObject.of("name","file","schema",JSONObject.of("type","string","description","x".repeat(300000)),"allowedFileType",JSONArray.of("pdf"))));long id=created.getLongValue("id"),wf=created.getLongValue("workflowId");String raw=workflowMapper.find(wf).getCanvasJson(),config=agentsMapper.find(id).getConfigJson();assertEquals(413,denied(()->sync(id)));assertEquals(config,agentsMapper.find(id).getConfigJson());assertEquals(1,agentsMapper.find(id).getRevision());assertEquals(raw,workflowMapper.find(wf).getCanvasJson());
    }
    @Test void databaseCasConstraintFailureRollsBackAllDerivedMetadata() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");String before=agentsMapper.find(id).getConfigJson();try(var s=schema.createStatement()){s.execute("ALTER TABLE paiflow_agent ADD CONSTRAINT fixture_input_sync_fail CHECK(config_json NOT LIKE '%inputTypesWorkflowId%')");}var error=assertThrows(RuntimeException.class,()->sync(id));String causes="";for(Throwable t=error;t!=null;t=t.getCause())causes+=t.getMessage();assertTrue(causes.toLowerCase().contains("fixture_input_sync_fail"));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(1,agentsMapper.find(id).getRevision());
    }
    @Test void copiesClearDerivedSourcesAndMetadataWithoutRecompilingOrChangingCopiedCanvas(){
        var created=createAgent(fields());long id=created.getLongValue("id"),wf=created.getLongValue("workflowId");sync(id);var copied=agents.copy(id,space);var value=config(copied.getLongValue("id"));assertEquals("[]",value.get("extraInputsConfig"));assertEquals("{}",value.get("extraInputs"));assertEquals(0,value.getIntValue("supportUpload"));assertFalse(value.containsKey("inputTypesWorkflowId"));assertFalse(value.containsKey("inputTypesWorkflowRevision"));assertEquals(workflowMapper.find(wf).getCanvasJson(),workflowMapper.find(copied.getLongValue("workflowId")).getCanvasJson());
    }
    @Test void workflowFormCanEchoButCannotOverwriteDerivedMetadataAndPromptCannotForgeSource(){
        var created=createAgent(fields());long id=created.getLongValue("id");sync(id);var value=config(id);var echo=JSONObject.of("name","同值回显","extraInputs",value.get("extraInputs"),"extraInputsConfig",value.get("extraInputsConfig"),"supportUpload",value.get("supportUpload"),"inputTypesWorkflowId",value.get("inputTypesWorkflowId"),"inputTypesWorkflowRevision",value.get("inputTypesWorkflowRevision"));assertEquals(3,agents.update(id,space,2L,0L,echo).getLongValue("revision"));
        for(String key:List.of("extraInputs","extraInputsConfig","supportUpload","inputTypesWorkflowId","inputTypesWorkflowRevision")){var request=JSONObject.of("name","不得伪造",key,key.equals("supportUpload")?99:"forged");assertEquals(409,denied(()->agents.update(id,space,3L,1L,request)));assertEquals(3,agentsMapper.find(id).getRevision());}
        assertEquals(409,denied(()->agents.create(space,1,JSONObject.of("name","伪造指令来源","inputTypesWorkflowId",99))));var prompt=agents.create(space,1,JSONObject.of("name","原指令上传字段","supportUpload",2,"extraInputs","legacy"));assertEquals(2,prompt.getIntValue("supportUpload"));assertEquals(409,denied(()->agents.update(prompt.getLongValue("id"),space,0L,null,JSONObject.of("inputTypesWorkflowRevision",1))));assertEquals(2,prompt.getIntValue("supportUpload"));
    }
    @Test void defaultScopeIsExactAndForeignDomainOrUnboundPromptCannotSync(){
        var created=createAgent(fields());long id=created.getLongValue("id");assertEquals(404,denied(()->agents.legacyInputTypes(id,"")));assertNull(spaces.defaultPersonal(42));var prompt=agents.create(space,1,JSONObject.of("name","无画布指令"));assertEquals(409,denied(()->sync(prompt.getLongValue("id"))));assertEquals(0,agentsMapper.find(prompt.getLongValue("id")).getRevision());
    }
    @Test void emptyAndAbsentHeadersResolveOnlyTheActorsActualDefaultBinding(){
        var created=agents.legacyCreate("",3,JSONObject.of("name","默认域输入同步","data",WorkflowInputTypesPolicyTest.graph(fields())));long id=created.getLongValue("id");assertNull(created.get("spaceId"));var domain=spaces.defaultPersonal(42);assertNotNull(domain);assertEquals("default_personal",domain.getKind());var first=agents.legacyInputTypes(id,"");var second=agents.legacyInputTypes(id,null);assertEquals(2,first.agentRevision());assertEquals(first.agentRevision(),second.agentRevision());assertEquals(domain.getId(),agentsMapper.find(id).getSpaceId());assertEquals(404,denied(()->agents.legacyInputTypes(id,Long.toString(space))));
    }
    @Test void realMvcOriginalBotIdOnlyReturnsTwelveNullableFieldsAndBoundHeaders() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");var mvc=MockMvcBuilders.standaloneSetup(new LegacyAgentWorkflowController(agents)).build();String request="{\"botId\":"+id+"}";var response=mvc.perform(post("/paiflow/legacy/workflow/bot/get-inputs-type").header("space-id",Long.toString(space)).contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].name").value("AGENT_USER_INPUT")).andExpect(header().string("ETag","\"2\"")).andExpect(header().string("X-PaiFlow-Agent-Id",Long.toString(id))).andReturn().getResponse();byte[] bytes=response.getContentAsByteArray();var body=JSON.parseObject(new String(bytes,StandardCharsets.UTF_8));var data=body.getJSONArray("data");assertEquals(12,data.getJSONObject(0).size());assertTrue(data.getJSONObject(0).containsKey("allowedFileType"));assertNull(data.getJSONObject(0).get("allowedFileType"));assertEquals(2,agentsMapper.find(id).getRevision());
        // 只由实际隔离H2+Mapper+Service+MockMVC检查通过后保存原响应字节，供原消费者只读核对。
        var artifact=JSONObject.of("scope","isolated H2 and MockMVC; not live centre","request",JSONObject.of("method","POST","path","/paiflow/legacy/workflow/bot/get-inputs-type","space-id",Long.toString(space),"body",request),"response",JSONObject.of("status",response.getStatus(),"ETag",response.getHeader("ETag"),"X-PaiFlow-Agent-Id",response.getHeader("X-PaiFlow-Agent-Id"),"rawBodyBase64",Base64.getEncoder().encodeToString(bytes),"rawBodySha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"body",body));Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/input-types-actual-response.json"),artifact.toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    @Test void reservedMalformedMetadataFieldsRemainNullableInActualMvcButNeverEnterSavedExtras() throws Exception {
        var reserved=List.of(JSONObject.of("name","AGENT_USER_INPUT","schema",null),JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string"),"allowedFileType",new JSONArray()),JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of(null,"pdf")));var mvc=MockMvcBuilders.standaloneSetup(new LegacyAgentWorkflowController(agents)).build();
        for(JSONObject row:reserved){var created=createAgent(JSONArray.of(row,fields().get(1)));long id=created.getLongValue("id"),wf=created.getLongValue("workflowId");String before=workflowMapper.find(wf).getCanvasJson();var response=mvc.perform(post("/paiflow/legacy/workflow/bot/get-inputs-type").header("space-id",Long.toString(space)).contentType("application/json").content("{\"botId\":"+id+"}")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(header().string("ETag","\"2\"")).andReturn().getResponse();var returned=JSON.parseObject(new String(response.getContentAsByteArray(),StandardCharsets.UTF_8)).getJSONArray("data");assertEquals(2,returned.size());assertEquals(12,returned.getJSONObject(0).size());assertEquals("AGENT_USER_INPUT",returned.getJSONObject(0).get("name"));assertEquals(row.get("schema"),returned.getJSONObject(0).get("schema"));assertEquals(row.get("allowedFileType"),returned.getJSONObject(0).get("allowedFileType"));var saved=config(id);assertEquals(1,JSON.parseArray(saved.getString("extraInputsConfig")).size());assertEquals("document",JSON.parseObject(saved.getString("extraInputs")).get("name"));assertEquals(1,saved.getIntValue("supportUpload"));assertEquals(before,workflowMapper.find(wf).getCanvasJson());agents.delete(id,space,2L,0L);}
    }
    @Test void actualMenuRevokedWhileWaitingForSpaceLockIsReadAgainBeforeAnyWrite() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");String before=agentsMapper.find(id).getConfigJson();var checked=new CountDownLatch(1);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var permission=spy(new PermissionService());doAnswer(call->{checked.countDown();return call.callRealMethod();}).when(permission).hasPermi("paiflow:agent:edit");var observedAccess=new ResourceAccessService(identities,permission,spaces);observedAccess.useCurrentSession(sessions);var observed=new AgentService(agentsMapper,observedAccess,identities,workflows);observed.useDefaultSpaces(defaults);var observedAgents=proxy(observed);var pool=Executors.newFixedThreadPool(2);
        try{var holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(db)).execute(tx->{spaces.lock(space);locked.countDown();await(release);return null;}));assertTrue(locked.await(5,TimeUnit.SECONDS));var waiter=pool.submit(()->{login();return denied(()->observedAgents.legacyInputTypes(id,Long.toString(space)));});assertTrue(checked.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiter.get(100,TimeUnit.MILLISECONDS));revoke("paiflow:agent:edit");release.countDown();holder.get(5,TimeUnit.SECONDS);assertEquals(403,waiter.get(5,TimeUnit.SECONDS));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(1,agentsMapper.find(id).getRevision());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void actorChangeAfterFirstPermissionReadCannotUseAnotherAuthorizedMembersIdentity() throws Exception {
        var created=createAgent(fields());long id=created.getLongValue("id");String before=agentsMapper.find(id).getConfigJson();assertEquals(1,spaces.insertMember(space,43,3,"fixture"));assertEquals(1,sql("INSERT INTO sys_user_role VALUES(43,88)"));
        var observedIdentity=spy(identities);var calls=new java.util.concurrent.atomic.AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==2){var user=users.selectUserById(43L);var principal=new LoginUser(43L,8L,user,Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));}return call.callRealMethod();}).when(observedIdentity).current();
        var observedAccess=new ResourceAccessService(observedIdentity,new PermissionService(),spaces);observedAccess.useCurrentSession(sessions);var observedDefaults=proxy(new DefaultPersonalSpaceService(spaces,observedIdentity,observedAccess));var target=new AgentService(agentsMapper,observedAccess,observedIdentity,workflows);target.useDefaultSpaces(observedDefaults);var observed=proxy(target);
        assertEquals(403,denied(()->observed.legacyInputTypes(id,Long.toString(space))));assertTrue(calls.get()>2);assertEquals(43L,identities.current().getUserId());assertTrue(new PermissionService().hasPermi("paiflow:agent:edit"));assertEquals(before,agentsMapper.find(id).getConfigJson());assertEquals(1,agentsMapper.find(id).getRevision());
    }
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}
    /** 与既有AgentPersistenceTest相同的H2方言适配，不改MySQL草案或生成列约束。 */
    private static String mysqlIfToCase(String text) {
        int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,partStart=end;boolean quoted=false;var parts=new java.util.ArrayList<String>();
        for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){parts.add(text.substring(partStart,end));break;}}else if(c==',' && depth==1){parts.add(text.substring(partStart,end));partStart=end+1;}}
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");return mysqlIfToCase(text.substring(0,start)+"CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END"+text.substring(end+1));
    }
}
