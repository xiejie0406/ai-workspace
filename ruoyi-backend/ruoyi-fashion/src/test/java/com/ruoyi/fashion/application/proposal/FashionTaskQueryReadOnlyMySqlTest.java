package com.ruoyi.fashion.application.proposal;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 显式启用后仅在原库执行 SELECT；不运行迁移、建表或业务写入。 */
@EnabledIfEnvironmentVariable(named="FASHION_DB_READONLY_CHECK",matches="true")
class FashionTaskQueryReadOnlyMySqlTest {
    @Test void mixedCollationTaskSourcesMatchIndependentOwnerRestrictedReads() throws Exception {
        var env=System.getenv();
        try(var connection=DriverManager.getConnection(env.get("RUOYI_DB_URL"),env.get("RUOYI_DB_USERNAME"),env.get("RUOYI_DB_PASSWORD"))){
            connection.setReadOnly(true);connection.setAutoCommit(false);
            assertTrue(connection.isReadOnly());
            var jdbc=new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection,true));
            assertEquals("ry-vue",jdbc.queryForObject("select database()",Map.of(),String.class));
            long owner=1;var params=Map.of("owner",owner);
            long runs=jdbc.queryForObject("select count(*) from fq_ai_run r join fq_ai_conversation c on c.id=r.conversation_id where c.owner_user_id=:owner",params,Long.class);
            long nonQuoteRuns=jdbc.queryForObject("select count(*) from fq_ai_run r join fq_ai_conversation c on c.id=r.conversation_id where c.owner_user_id=:owner and r.quote_id is null",params,Long.class);
            long images=jdbc.queryForObject("select count(*) from fq_proposal_image_task t join fq_selection_pool p on p.id=t.pool_id where t.owner_id=:owner and p.owner_id=:owner",params,Long.class);
            var query=new FashionTaskQuery(jdbc);
            var combined=query.list(owner,true,true,1,100);
            assertEquals(runs+images,combined.get("total"));
            var rows=(List<Map<String,Object>>)combined.get("items");
            assertEquals(Math.min(100L,runs+images),rows.size());
            for(var row:rows){
                assertInstanceOf(String.class,row.get("id"));
                assertTrue(Set.of("ai_run","try_on").contains(row.get("kind")));
                assertTrue(row.containsKey("ownerName"));
                assertInstanceOf(Boolean.class,row.get("adopted"));
                // Connector/J 对 DATETIME 返回 LocalDateTime；查询保持时间类型，不伪造时区。
                if(row.get("createdAt")!=null)assertTrue(row.get("createdAt") instanceof String||row.get("createdAt") instanceof java.time.LocalDateTime);
            }
            assertEquals(nonQuoteRuns,query.list(owner,true,false,1,100).get("total"));
            assertEquals(images,query.list(owner,false,true,1,100).get("total"));
            assertTrue(((List<?>)query.list(owner,true,true,1000000,100).get("items")).isEmpty());
            connection.rollback();
        }
    }
}
