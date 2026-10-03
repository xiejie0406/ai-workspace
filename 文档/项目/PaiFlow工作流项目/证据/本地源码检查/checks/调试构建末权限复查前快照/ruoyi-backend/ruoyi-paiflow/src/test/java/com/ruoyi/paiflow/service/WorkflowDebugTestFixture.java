package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowScopeFacade;
import com.ruoyi.paiflow.mapper.AgentMapper;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** 与静态QA/Run同库同会话的真实Agent关系夹具；无@Test，不复制造成重复计数。 */
public abstract class WorkflowDebugTestFixture extends StaticQuestionAnswerTestFixture {
    public AgentMapper agentsMapper;
    public AgentService agents;
    public DefaultPersonalSpaceService defaults;
    public LegacyWorkflowScopeFacade facade;
    @BeforeEach void setupDebug() throws Exception {
        try(var statement=schema.createStatement()) {
            for(String file:List.of("004-paiflow-membership.sql","007-paiflow-agent.sql","010-paiflow-default-personal.sql","011-paiflow-agent-owner-guard.sql")) {
                String ddl=Files.readString(Path.of("sql",file)).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("DROP CHECK","DROP CONSTRAINT")
                    .replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                    .replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String sql:mysqlIfToCase(ddl).split(";"))if(!sql.isBlank())statement.execute(sql);
            }
            int id=300;for(String permission:List.of("paiflow:agent:query","paiflow:agent:create","paiflow:agent:edit","paiflow:agent:copy","paiflow:agent:remove","paiflow:workflow:remove")){id++;statement.execute("INSERT INTO sys_menu VALUES("+id+",'"+permission+"','0')");statement.execute("INSERT INTO sys_role_menu VALUES(88,"+id+")");}
        }
        var configuration=sessions.getSqlSessionFactory().getConfiguration();try(var xml=getClass().getResourceAsStream("/mapper/paiflow/AgentMapper.xml")){assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"AgentMapper",configuration.getSqlFragments()).parse();}
        agentsMapper=sessions.getMapper(AgentMapper.class);defaults=proxy(new DefaultPersonalSpaceService(spaces,identities,access));
        var target=new AgentService(agentsMapper,access,identities,workflows);target.useDefaultSpaces(defaults);agents=proxy(target);
        var beans=new StaticListableBeanFactory();beans.addBean("realAgentBindings",agents);workflows.useAgentBindings(beans.getBeanProvider(WorkflowService.AgentBinding.class));
        facade=proxy(new LegacyWorkflowScopeFacade(workflows,defaults,access));
    }
    public JSONObject createAgent(JSONObject graph){return createAgent("debug_"+UUID.randomUUID().toString().substring(0,8),graph);}
    public JSONObject createAgent(String name,JSONObject graph){return agents.create(space,3,JSONObject.of("name",name,"data",graph.toJSONString(JSONWriter.Feature.WriteMapNullValue),"futureConfig",JSONObject.of("nullValue",null)));}
    public static JSONObject buildBody(JSONObject agent,JSONObject graph){return JSONObject.of("id",agent.get("workflowId"),"flowId",agent.get("flowId"),"name",agent.get("name"),"description",agent.get("description"),"data",graph);}
    public static JSONObject receipt(JSONObject built){return built.getJSONObject("debugReceipt");}
    public JSONObject ordinary(JSONObject agent,JSONObject graph,String key){return workflows.debugBuild(agent.getLongValue("workflowId"),space,workflowMapper.find(agent.getLongValue("workflowId")).getRevision(),buildBody(agent,graph),"ordinary",null,key);}
    public void loginAs(long id){org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));var user=users.selectUserById(id);var principal=new LoginUser(id,8L,user,Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));}
    private static String mysqlIfToCase(String text) {
        int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,partStart=end;boolean quoted=false;var parts=new ArrayList<String>();
        for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){parts.add(text.substring(partStart,end));break;}}else if(c==',' && depth==1){parts.add(text.substring(partStart,end));partStart=end+1;}}
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");return mysqlIfToCase(text.substring(0,start)+"CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END"+text.substring(end+1));
    }
}
