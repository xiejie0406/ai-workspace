package com.ruoyi.paismart.api;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.billing.QuotaService;
import java.sql.Timestamp;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实H2查询与平台登录身份，验证流水展示关联及原用户范围，不访问原业务库。 */
class UsageRecordsBoundaryTest {
    private JdbcTemplate db;
    private UsageController controller;
    @BeforeEach void prepare(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:usage_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V001__control_billing_jobs.sql")).execute(source);db=new JdbcTemplate(source);
        db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),password VARCHAR(100))");
        db.update("INSERT INTO sys_user VALUES(2,'original-user','never-expose-password'),(3,'other-user','never-expose-password')");
        ledger("own-old",2,"LLM","CREDIT","2026-10-03 23:59:59");
        ledger("own-early",2,"LLM","RESERVE","2026-10-04 00:00:00");
        ledger("own-late",2,"LLM","SETTLE","2026-10-04 23:59:59");
        ledger("own-vector",2,"EMBEDDING","SETTLE","2026-10-05 00:00:00");
        ledger("other",3,"LLM","SETTLE","2026-10-04 12:00:00");
        controller=new UsageController(db,mock(QuotaService.class),mock(MembershipService.class));login(false);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void login(boolean audit){var login=new LoginUser();login.setUserId(2L);var user=new SysUser();user.setUserId(2L);login.setUser(user);login.setPermissions(audit?Set.of("paismart:usage:list","paismart:usage:audit"):Set.of("paismart:usage:list"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));}
    private void ledger(String id,long user,String scope,String action,String at){db.update("INSERT INTO ps_token_ledger(id,user_id,scope,operation_key,action,tokens,available_after,frozen_after,debt_after,reason,operator_id,created_at) VALUES(?,?,?,?,?,8,92,0,0,'合成测试',2,?)",id,user,scope,"operation:"+id,action,Timestamp.valueOf(at));}
    @SuppressWarnings("unchecked") private Map<String,Object> row(TableDataInfo page,int index){return (Map<String,Object>)page.getRows().get(index);}
    @Test void ownRowsShowOriginalPlatformUserAndLedgerCorrelationWithoutOtherUsers(){
        var page=controller.records(null,1,20,"","","");assertEquals(200,page.getCode());assertEquals(4,page.getTotal());
        for(int i=0;i<page.getRows().size();i++){var record=row(page,i);assertEquals(2L,record.get("userId"));assertEquals("original-user",record.get("userName"));assertEquals("operation:"+record.get("id"),record.get("correlationId"));assertFalse(record.containsKey("password"));assertNotEquals("other",record.get("id"));}
    }
    @Test void ordinaryUserCannotSelectAnotherUser(){assertThrows(AccessDeniedException.class,()->controller.records(3L,1,20,"","",""));}
    @Test void auditPermissionSelectsOnlyRequestedUser(){login(true);var page=controller.records(3L,1,20,"","","");assertEquals(1,page.getTotal());assertEquals("other",row(page,0).get("id"));assertEquals("other-user",row(page,0).get("userName"));assertEquals("operation:other",row(page,0).get("correlationId"));}
    @Test void typeDatesAndPaginationUseSameLedgerPredicateAfterUserJoin(){
        var first=controller.records(null,1,1,"LLM","2026-10-04","2026-10-04");var second=controller.records(null,2,1,"LLM","2026-10-04","2026-10-04");
        assertEquals(2,first.getTotal());assertEquals(2,second.getTotal());assertEquals("own-late",row(first,0).get("id"));assertEquals("own-early",row(second,0).get("id"));
        var credit=controller.records(null,1,20,"CREDIT","","");assertEquals(1,credit.getTotal());assertEquals("own-old",row(credit,0).get("id"));
    }
    @Test void missingPlatformUserDoesNotDropAuthorizedHistoricalLedgerRows(){db.update("DELETE FROM sys_user WHERE user_id=2");var page=controller.records(null,1,20,"","","");assertEquals(4,page.getTotal());assertNull(row(page,0).get("userName"));assertEquals("operation:"+row(page,0).get("id"),row(page,0).get("correlationId"));}
    @Test void invalidTypeAndDateStillReject(){assertThrows(IllegalArgumentException.class,()->controller.records(null,1,20,"UNKNOWN","",""));assertThrows(IllegalArgumentException.class,()->controller.records(null,1,20,"","invalid-date",""));}
}
