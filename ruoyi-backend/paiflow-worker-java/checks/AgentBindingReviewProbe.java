package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.h2.jdbcx.JdbcDataSource;

/** 仅加载既有测试的随机H2内存库；不启动服务，不连接业务数据库。 */
public final class AgentBindingReviewProbe {
    private static Object field(Object source,String name)throws Exception { Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source); }
    public static void main(String[] args)throws Exception {
        var fixture=new AgentPersistenceTest();
        Method setup=fixture.getClass().getDeclaredMethod("setup"),close=fixture.getClass().getDeclaredMethod("close");setup.setAccessible(true);close.setAccessible(true);setup.invoke(fixture);
        try {
            JdbcDataSource data=(JdbcDataSource)field(fixture,"data");if(!data.getURL().startsWith("jdbc:h2:mem:"))throw new IllegalStateException("非隔离内存库");
            var agents=(AgentService)field(fixture,"agents");var workflows=(WorkflowService)field(fixture,"workflows");long space=(long)field(fixture,"space");
            var agent=agents.create(space,1,JSONObject.of("name","真实指令型fixture"));long agentId=agent.getLongValue("botId");
            var workflow=workflows.create(space,JSONObject.of("name","独立画布fixture","ext",JSONObject.of("botId",agentId),"botId",agentId));
            System.out.println(JSONObject.of("database","H2-memory","agentId",agentId,"agentWorkflowId",agent.get("workflowId"),"unboundWorkflowId",workflow.get("id"),"returnedExtBotId",JSONObject.parseObject(workflow.getString("ext")).get("botId"),"returnedTopLevelBotId",workflow.get("botId"),"bindingStatus",workflow.get("bindingStatus"),"agentBindingStatus",workflow.get("agentBindingStatus")).toJSONString());
            var updated=workflows.save(workflow.getLongValue("id"),space,0L,JSONObject.of("ext",JSONObject.of("botId",agentId+100)));
            System.out.println(JSONObject.of("probe","unbound-save","returnedExtBotId",JSONObject.parseObject(updated.getString("ext")).get("botId"),"revision",updated.get("revision"),"network",false).toJSONString());
        } finally {close.invoke(fixture);}
    }
}
