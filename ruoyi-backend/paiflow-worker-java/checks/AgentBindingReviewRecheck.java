package com.ruoyi.paiflow.service;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.h2.jdbcx.JdbcDataSource;

/** 同原隔离fixture复验；历史opaque元数据保全，页面身份只由真实绑定生成。 */
public final class AgentBindingReviewRecheck {
    private static Object field(Object source,String name)throws Exception{Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source);}
    public static void main(String[] args)throws Exception {
        var fixture=new AgentPersistenceTest();Method setup=fixture.getClass().getDeclaredMethod("setup"),close=fixture.getClass().getDeclaredMethod("close");setup.setAccessible(true);close.setAccessible(true);setup.invoke(fixture);
        try {
            JdbcDataSource data=(JdbcDataSource)field(fixture,"data");if(!data.getURL().startsWith("jdbc:h2:mem:"))throw new IllegalStateException("非隔离库");
            var agents=(AgentService)field(fixture,"agents");var workflows=(WorkflowService)field(fixture,"workflows");long space=(long)field(fixture,"space");
            var agent=agents.create(space,1,JSONObject.of("name","实际指令fixture"));long id=agent.getLongValue("botId");
            int createCode=0;try{workflows.create(space,JSONObject.of("name","伪绑定fixture","ext",JSONObject.of("botId",id),"botId",id));}catch(ServiceException rejected){createCode=rejected.getCode();}if(createCode!=409)throw new AssertionError("公开创建仍接受伪绑定");
            var created=workflows.create(space,JSONObject.of("name","合法独立fixture"));long workflowId=created.getLongValue("id");
            var historical=JSONObject.of("ext",JSONObject.of("botId",id,"opaque","keep"),"botId",id,"agentRevision",90,"agentBindingStatus","bound");
            try(var connection=data.getConnection();var update=connection.prepareStatement("UPDATE paiflow_workflow SET metadata_json=? WHERE id=?")){update.setString(1,historical.toJSONString());update.setLong(2,workflowId);if(update.executeUpdate()!=1)throw new AssertionError();}
            var detail=workflows.detail(workflowId,space);var ext=JSONObject.parseObject(detail.getString("ext"));
            if(detail.get("botId")!=null || detail.get("agentRevision")!=null || detail.get("agentBindingStatus")!=null || ext.get("botId")!=null || !"keep".equals(ext.getString("opaque")))throw new AssertionError("历史metadata输出身份未清除");
            int saveCode=0;try{workflows.save(workflowId,space,0L,JSONObject.of("ext",JSONObject.of("botId",id+100)));}catch(ServiceException rejected){saveCode=rejected.getCode();}if(saveCode!=409)throw new AssertionError("公开保存仍接受伪绑定");
            var bound=agents.create(space,3,JSONObject.of("name","实际工作流fixture"));var actual=workflows.detail(bound.getLongValue("workflowId"),space);if(!bound.getLong("botId").equals(JSONObject.parseObject(actual.getString("ext")).getLong("botId")))throw new AssertionError("真实绑定丢失");
            System.out.println(JSONObject.of("database","H2-memory","forgedCreateCode",createCode,"forgedSaveCode",saveCode,"historicalIdentityHidden",true,"historicalOpaquePreserved",true,"actualBindingGenerated",true,"network",false).toJSONString());
        }finally{close.invoke(fixture);}
    }
}
