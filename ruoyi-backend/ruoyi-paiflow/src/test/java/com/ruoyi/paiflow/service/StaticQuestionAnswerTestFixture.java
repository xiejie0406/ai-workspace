package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

/** 随机H2、实际平台User/Menu及业务Mapper；类代理事务，无Provider/真实DB/服务。 */
public abstract class StaticQuestionAnswerTestFixture {
    static final List<String> PERMISSIONS=List.of("paiflow:space:query","paiflow:workflow:query","paiflow:workflow:create","paiflow:workflow:edit","paiflow:run:create","paiflow:run:query","paiflow:run:resume","paiflow:run:cancel");
    JdbcDataSource db;Connection schema;SqlSessionTemplate sessions;SpaceMapper spaces;WorkflowMapper workflowMapper;RunMapper runMapper;CheckpointMapper checkpointMapper;
    SysUserServiceImpl users;SysPermissionService permissions;IdentityMappingService identities;ResourceAccessService access;
    WorkflowService workflows;RunService runs;RunLeaseService leases;CheckpointService checkpoints;long space;final MutableClock clock=new MutableClock();
    final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("static-qa-isolated","python");
    static class MutableClock extends Clock {Instant value=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);public Instant instant(){return value;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    @SuppressWarnings("unchecked") <T>T proxy(T value){var p=new ProxyFactory(value);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=db.getConnection();
        // H2 CHECK常量集合的DDL session保持到AfterEach；不放宽真实MySQL草案约束。
        try(var s=schema.createStatement()) {
            s.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.StaticQuestionAnswerTestFixture.utcNow'");
            for(String file:List.of("001-paiflow-schema.sql","002-paiflow-workflow.sql","003-paiflow-run.sql","005-paiflow-checkpoint.sql","006-paiflow-execution-start.sql","012-paiflow-workflow-model-snapshots.sql","013-paiflow-model-operation.sql","015-paiflow-execution-codec.sql","016-paiflow-static-question-answer-codec.sql","017-paiflow-run-json-codec.sql","018-paiflow-workflow-debug-snapshot.sql")) {
                String ddl=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","").replace("DROP CHECK","DROP CONSTRAINT")
                    .replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END");
                for(String statement:ddl.split(";"))if(!statement.isBlank())s.execute(statement);
            }
            s.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,user_name VARCHAR(64),nick_name VARCHAR(64),email VARCHAR(64),avatar VARCHAR(64),phonenumber VARCHAR(32),password VARCHAR(64),sex VARCHAR(1),status VARCHAR(1),del_flag VARCHAR(1),login_ip VARCHAR(64),login_date TIMESTAMP,pwd_update_date TIMESTAMP,create_by VARCHAR(64),create_time TIMESTAMP,update_by VARCHAR(64),update_time TIMESTAMP,remark VARCHAR(255))");
            s.execute("CREATE TABLE sys_dept(dept_id BIGINT PRIMARY KEY,parent_id BIGINT,ancestors VARCHAR(64),dept_name VARCHAR(64),order_num INT,leader VARCHAR(64),status VARCHAR(1))");
            s.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_name VARCHAR(64),role_key VARCHAR(64),role_sort INT,data_scope VARCHAR(1),status VARCHAR(1))");
            s.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(128),status VARCHAR(1))");s.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");s.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
            s.execute("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(42,'fixture42','隔离用户42','0','0'),(43,'fixture43','隔离用户43','0','0')");
            s.execute("INSERT INTO sys_role VALUES(88,'fixture','fixture',1,'1','0')");s.execute("INSERT INTO sys_user_role VALUES(42,88)");int menuId=0;for(String permission:PERMISSIONS){menuId++;s.execute("INSERT INTO sys_menu VALUES("+menuId+",'"+permission+"','0')");s.execute("INSERT INTO sys_role_menu VALUES(88,"+menuId+")");}

        }
        var config=new Configuration(new Environment("static-question-answer-isolated",new SpringManagedTransactionFactory(),db));
        config.getTypeAliasRegistry().registerAlias("SysUser",SysUser.class);config.getTypeAliasRegistry().registerAlias("SysRole",SysRole.class);config.getTypeAliasRegistry().registerAlias("SysDept",SysDept.class);config.getTypeAliasRegistry().registerAlias("SysMenu",SysMenu.class);
        for(String name:List.of("SpaceMapper","IdentityMapper","WorkflowMapper","RunMapper","CheckpointMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        for(String name:List.of("SysUserMapper","SysMenuMapper"))try(var xml=Files.newInputStream(Path.of(System.getProperty("paiflow.systemSource","../ruoyi-system"),"src/main/resources/mapper/system/"+name+".xml"))){new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));spaces=sessions.getMapper(SpaceMapper.class);workflowMapper=sessions.getMapper(WorkflowMapper.class);runMapper=sessions.getMapper(RunMapper.class);checkpointMapper=sessions.getMapper(CheckpointMapper.class);
        users=new SysUserServiceImpl();ReflectionTestUtils.setField(users,"userMapper",sessions.getMapper(SysUserMapper.class));var menu=new SysMenuServiceImpl();ReflectionTestUtils.setField(menu,"menuMapper",sessions.getMapper(SysMenuMapper.class));permissions=new SysPermissionService();ReflectionTestUtils.setField(permissions,"menuService",menu);
        identities=new IdentityMappingService(sessions.getMapper(IdentityMapper.class),users);identities.useCurrentSession(sessions);identities.useCurrentPermissions(permissions);access=new ResourceAccessService(identities,new PermissionService(),spaces);access.useCurrentSession(sessions);
        var record=new SpaceRecord();record.setKind("personal");record.setName("static question fixture");record.setOwnerUserId(42L);record.setCreateBy("fixture");record.setUpdateBy("fixture");assertEquals(1,spaces.insert(record));space=record.getId();assertEquals(1,spaces.insertOwner(space,42,"fixture"));login();
        workflows=proxy(new WorkflowService(workflowMapper,access,identities));var lease=new RunLeaseService(runMapper,spaces,users,permissions,clock);lease.useCurrentSession(sessions);leases=proxy(lease);
        checkpoints=proxy(new CheckpointService(checkpointMapper,runMapper,leases,access,identities,permissions,clock));var runner=new RunService(workflows,runMapper,access,identities);var beans=new StaticListableBeanFactory();beans.addBean("checkpoints",checkpoints);runner.useCheckpoints(beans.getBeanProvider(CheckpointService.class));runs=proxy(runner);
    }
    @AfterEach void close() throws Exception {SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();if(schema!=null){try(var s=schema.createStatement()){s.execute("SHUTDOWN");}finally{schema.close();}}}
    void login(){org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));var user=users.selectUserById(42L);var principal=new LoginUser(42L,8L,user,Set.copyOf(PERMISSIONS));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));}
    int sql(String command)throws Exception{try(var c=db.getConnection();var s=c.createStatement()){return s.executeUpdate(command);}}
    long rows(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    void revoke(String permission)throws Exception{assertEquals(1,sql("UPDATE sys_menu SET status='1' WHERE perms='"+permission+"'"));}
    static JSONObject copy(JSONObject value){return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    static JSONObject data(JSONObject graph,String id){return graph.getJSONArray("nodes").stream().map(v->(JSONObject)v).filter(v->id.equals(v.get("id"))).findFirst().orElseThrow().getJSONObject("data");}
    static JSONObject params(JSONObject graph){return data(graph,"question-answer::q").getJSONObject("nodeParam");}
    static JSONObject canvas(){var graph=JSON.parseObject("{\"nodes\":[{\"id\":\"node-start::s\",\"data\":{\"nodeMeta\":{\"nodeType\":\"node-start\",\"aliasName\":\"开始\"},\"inputs\":[],\"outputs\":[{\"id\":\"q-in\",\"name\":\"question\",\"type\":\"string\",\"required\":true}],\"nodeParam\":{}}},{\"id\":\"question-answer::q\",\"data\":{\"nodeMeta\":{\"nodeType\":\"question-answer\",\"aliasName\":\"静态问答\"},\"inputs\":[{\"id\":\"q-bind\",\"name\":\"question\",\"type\":\"string\",\"value\":{\"type\":\"ref\",\"content\":{\"nodeId\":\"node-start::s\",\"name\":\"question\"}}}],\"outputs\":[{\"id\":\"q-out\",\"name\":\"content\",\"type\":\"string\"}],\"nodeParam\":{\"question\":\"请回答{{question}}\",\"answerType\":\"direct\",\"timeout\":\"1.0\",\"needReply\":\"false\",\"directAnswer\":{\"handleResponse\":\"false\",\"maxRetryCounts\":true},\"remarkVisible\":\"false\",\"opaqueNull\":null}}},{\"id\":\"node-end::e\",\"data\":{\"nodeMeta\":{\"nodeType\":\"node-end\",\"aliasName\":\"结束\"},\"inputs\":[{\"id\":\"answer-ref\",\"name\":\"answer\",\"type\":\"string\",\"value\":{\"type\":\"ref\",\"content\":{\"nodeId\":\"question-answer::q\",\"name\":\"content\"}}}],\"outputs\":[],\"nodeParam\":{\"outputMode\":1}}}],\"edges\":[{\"source\":\"node-start::s\",\"target\":\"question-answer::q\"},{\"source\":\"question-answer::q\",\"target\":\"node-end::e\"}]}");for(Object raw:graph.getJSONArray("nodes")){var data=((JSONObject)raw).getJSONObject("data");for(String direction:List.of("inputs","outputs"))for(Object item:data.getJSONArray(direction)){var parameter=(JSONObject)item;var schema=new JSONObject();for(String key:List.of("type","value","properties","default","description"))if(parameter.containsKey(key))schema.put(key,parameter.remove(key));parameter.put("schema",schema);}}return graph;}
    long version(JSONObject canvas){var wf=workflows.create(space,JSONObject.of("name","qa_"+UUID.randomUUID(),"data",canvas));return workflows.createVersion(wf.getLongValue("id"),space,0L,new JSONObject()).getLongValue("id");}
    JSONObject task(long version){var view=runs.create(version,"python",JSONObject.of("question","本地实际题面"),"qa_"+UUID.randomUUID().toString().replace("-",""),300);var task=leases.claim(worker);assertEquals(view.runId(),task.get("runId"));return task;}
    JSONObject begun(JSONObject canvas){var task=task(version(canvas));assertTrue(leases.beginExecution(task.getString("runId"),worker,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));return task;}
    JSONObject checkpoint(JSONObject task){var p=params(task.getJSONObject("dsl"));var question=JSONObject.of("type",p.get("answerType"),"content","请回答本地实际题面");if("option".equals(p.get("answerType")) && !p.getJSONArray("optionAnswer").isEmpty()){var options=new JSONArray();for(Object raw:p.getJSONArray("optionAnswer")){var option=(JSONObject)raw;options.add(JSONObject.of("id",option.get("name"),"text",option.get("content"),"content_type",option.get("content_type")));}question.put("option",options);}return checkpoints.create(task.getString("runId"),worker,JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"nodeId","question-answer::q","ordinal",1,"question",question,"needReply",p.get("needReply"),"timeoutSeconds",p.getIntValue("timeout")*60,"idempotencyKey","create_checkpoint_key"));}
    JSONObject reply(JSONObject task,JSONObject checkpoint,String event,String content){return checkpoints.reply(task.getString("runId"),checkpoint.getString("checkpointId"),JSONObject.of("expectedRevision",checkpoint.get("revision"),"idempotencyKey","reply_checkpoint_key","event_type",event,"content",content));}
    JSONObject consumeBody(JSONObject task,JSONObject checkpoint){return JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"expectedRevision",checkpoint.get("revision"),"idempotencyKey","consume_checkpoint_key");}
    JSONObject result(JSONObject task,String state,Object output){return JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"state",state,"output",output);}
    JSONObject event(JSONObject task,Object payload){return JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.get("runId"),"leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",1L,"eventType","node.completed","nodeId","question-answer::q","timestamp",clock.instant().toString(),"payload",payload);}
    static int denied(org.junit.jupiter.api.function.Executable action){return assertThrows(ServiceException.class,action).getCode();}
}
