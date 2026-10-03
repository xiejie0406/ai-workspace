package com.ruoyi.paismart.billing;

import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.application.billing.*;
import com.ruoyi.paismart.application.jobs.*;
import com.ruoyi.paismart.application.spaces.SpaceService;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 内存库验证事务边界，不能作为真实 MySQL/支付联机证据。 */
class BillingBoundaryTest {
    @Test void invitationFilterCountsOnlyTheAuthorizedEnabledRows(){
        var invitations=new InvitationService(db,tx,quotas,mock(SpaceService.class));var enabled=invitations.create(Map.of("code","enabled-a"),2);var disabled=invitations.create(Map.of("code","disabled-a","enabled",false),2);invitations.create(Map.of("code","other-user"),3);
        assertEquals(2,invitations.countAuthorized(2));assertEquals(1,invitations.countAuthorized(2,true));assertEquals(1,invitations.countAuthorized(2,false));assertEquals(List.of(disabled.id()),invitations.listAuthorized(2,0,20,false).stream().map(InvitationService.Invite::id).toList());assertEquals(List.of(enabled.id()),invitations.listAuthorized(2,0,20,true).stream().map(InvitationService.Invite::id).toList());assertTrue(invitations.listAuthorized(2,1,20,true).isEmpty());
    }
    @Test void unusedInviteCanBeEditedAndUsedInviteKeepsIdentity(){
        var invitations=new InvitationService(db,tx,quotas,mock(SpaceService.class));var original=invitations.create(Map.of("code","old-code","expiresAt",Instant.now().plusSeconds(3600).toString()),2);
        var updated=invitations.updateAuthorized(original.id(),Map.of("code"," New-Code ","maxUses",2),original.rowVersion(),2);assertEquals("NEW-CODE",updated.code());assertEquals(original.expiresAt(),updated.expiresAt());assertThrows(IllegalArgumentException.class,()->invitations.validate("old-code"));invitations.validate(" new-code ");invitations.redeem(2,"new-code");
        var used=invitations.get(original.id());assertThrows(IllegalArgumentException.class,()->invitations.updateAuthorized(used.id(),Map.of("code","another-code"),used.rowVersion(),2));var stopped=invitations.updateAuthorized(used.id(),Map.of("enabled",false),used.rowVersion(),2);assertFalse(stopped.enabled());assertEquals(used.expiresAt(),stopped.expiresAt());
    }
    @Test void duplicateInviteEditAndExpiredCreateDoNotMutateOriginal(){
        var invitations=new InvitationService(db,tx,quotas,mock(SpaceService.class));var original=invitations.create(Map.of("code","first-code"),2);invitations.create(Map.of("code","second-code"),2);
        assertThrows(IllegalArgumentException.class,()->invitations.updateAuthorized(original.id(),Map.of("code","second-code"),original.rowVersion(),2));assertEquals(original.code(),invitations.get(original.id()).code());assertEquals(original.rowVersion(),invitations.get(original.id()).rowVersion());assertThrows(IllegalArgumentException.class,()->invitations.create(Map.of("code","past-code","expiresAt",Instant.now().minusSeconds(60).toString()),2));assertEquals(2,invitations.countAuthorized(2));
    }
    @Test void orderStatusFilteringPreservesOwnerAndFilteredTotal(){
        var service=new RechargeService(db,tx,quotas,mock(WechatPaymentGateway.class));insertOrder();
        db.update("INSERT INTO ps_recharge_order(id,user_id,request_key,trade_no,snapshot_json,amount_fen,llm_tokens,embedding_tokens,state,expires_at) VALUES('order2',2,'paid-key','paid-trade','{}',100,500,200,'SUCCEEDED',?)",Timestamp.from(Instant.now().plusSeconds(60)));
        db.update("INSERT INTO ps_recharge_order(id,user_id,request_key,trade_no,snapshot_json,amount_fen,llm_tokens,embedding_tokens,state,expires_at) VALUES('order3',3,'other-key','other-trade','{}',100,500,200,'SUCCEEDED',?)",Timestamp.from(Instant.now().plusSeconds(60)));
        assertEquals(2,service.orderCount(2));assertEquals(1,service.orderCount(2,"succeed"));assertEquals(List.of("paid-trade"),service.orders(2,0,20,"SUCCEEDED").stream().map(RechargeService.Order::tradeNo).toList());assertEquals(0,service.orderCount(2,"FAIL"));assertTrue(service.orders(2,1,20,"SUCCEEDED").isEmpty());assertThrows(IllegalArgumentException.class,()->service.orders(2,0,20,"ANY"));
    }
    @Test void packageBenefitAndSortArePreservedInOriginalOrderSnapshot(){
        var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);when(gateway.prepay(anyString(),anyString(),anyLong(),any())).thenReturn("weixin://fixture");var service=new RechargeService(db,tx,quotas,gateway);
        var pack=service.save(null,Map.of("name","权益套餐","amountFen",100,"llmTokens",500,"embeddingTokens",100,"description","原说明","benefit","原权益A\n原权益B","sortOrder",7),null);var order=service.create(2,pack.id(),null,"benefit-order");
        assertEquals("原权益A\n原权益B",pack.benefit());assertEquals(7,pack.sortOrder());var changed=service.save(pack.id(),Map.of("name","新套餐","amountFen",200,"llmTokens",600,"embeddingTokens",100,"description","新说明","benefit","新权益","sortOrder",2),pack.rowVersion());assertEquals("新权益",changed.benefit());
        @SuppressWarnings("unchecked") var frozen=(Map<String,Object>)order.snapshot().get("package");assertEquals("原权益A\n原权益B",frozen.get("benefit"));assertEquals(100,order.amountFen());
    }
    @Test void queryWhilePrepayPendingDoesNotLoseQrOrRegressSuccess()throws Exception{
        var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.prepay(anyString(),anyString(),anyLong(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "weixin://confirmed";});
        when(gateway.query(anyString())).thenAnswer(call->new WechatPaymentGateway.Payment("query-before-code",call.getArgument(0),"wx1","NOTPAY",100,"mch","app","CNY",null));
        var service=new RechargeService(db,tx,quotas,gateway);var pack=service.save(null,Map.of("name","测试套餐","amountFen",100L,"llmTokens",500L,"embeddingTokens",100L),null);
        var pool=Executors.newSingleThreadExecutor();try{var created=pool.submit(()->service.create(2,pack.id(),null,"query-prepay-race"));assertTrue(entered.await(5,TimeUnit.SECONDS));String trade=db.queryForObject("SELECT trade_no FROM ps_recharge_order",String.class);assertEquals("NOT_PAY",service.query(trade,2).state());release.countDown();assertEquals("weixin://confirmed",created.get(5,TimeUnit.SECONDS).codeUrl());assertEquals("NOT_PAY",service.get(trade,2,false).state());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void successfulCallbackBeforePrepayReplyRemainsTerminal()throws Exception{
        var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.prepay(anyString(),anyString(),anyLong(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "weixin://late";});
        var service=new RechargeService(db,tx,quotas,gateway);var pack=service.save(null,Map.of("name","测试套餐","amountFen",100L,"llmTokens",500L,"embeddingTokens",100L),null);var pool=Executors.newSingleThreadExecutor();
        try{var created=pool.submit(()->service.create(2,pack.id(),null,"callback-prepay-race"));assertTrue(entered.await(5,TimeUnit.SECONDS));String trade=db.queryForObject("SELECT trade_no FROM ps_recharge_order",String.class);service.accept(new WechatPaymentGateway.Payment("early-paid",trade,"tx-early","SUCCESS",100,"mch","app","CNY",null));release.countDown();var order=created.get(5,TimeUnit.SECONDS);assertEquals("SUCCEEDED",order.state());assertEquals("weixin://late",order.codeUrl());assertEquals(500,account().available());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void fractionalFinancialInputDoesNotCreatePackage(){var gateway=mock(WechatPaymentGateway.class);var service=new RechargeService(db,tx,quotas,gateway);assertThrows(IllegalArgumentException.class,()->service.save(null,Map.of("name","拒绝小数","amountFen",100.9,"llmTokens",500L,"embeddingTokens",0L),null));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_recharge_package",Integer.class));assertThrows(IllegalArgumentException.class,()->com.ruoyi.paismart.infrastructure.Numbers.integer(new java.math.BigInteger("9223372036854775808")));}
    @Test void uncertainPrepayReturnsRecoverableOrderWithoutResending(){var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);when(gateway.prepay(anyString(),anyString(),anyLong(),any())).thenThrow(new IllegalStateException("fixture network timeout"));var service=new RechargeService(db,tx,quotas,gateway);var pack=service.save(null,Map.of("name","测试套餐","amountFen",100L,"llmTokens",500L,"embeddingTokens",100L),null);var order=service.create(2,pack.id(),null,"request-uncertain");assertEquals("UNCERTAIN",order.state());assertNotNull(order.tradeNo());assertEquals(order.tradeNo(),service.create(2,pack.id(),null,"request-uncertain").tradeNo());verify(gateway,times(1)).prepay(anyString(),anyString(),anyLong(),any());assertEquals(1,service.orders(2,0,20).size());assertEquals(0,service.orders(3,0,20).size());}
    @Test void parallelSameOrderOnlySendsOnePrepay()throws Exception{var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);when(gateway.prepay(anyString(),anyString(),anyLong(),any())).thenReturn("weixin://fixture");var service=new RechargeService(db,tx,quotas,gateway);var pack=service.save(null,Map.of("name","测试套餐","amountFen",100L,"llmTokens",500L,"embeddingTokens",100L),null);var pool=Executors.newFixedThreadPool(5);try{List<Callable<RechargeService.Order>> calls=new ArrayList<>();for(int i=0;i<5;i++)calls.add(()->service.create(2,pack.id(),null,"parallel-payment"));Set<String> ids=new HashSet<>();for(var f:pool.invokeAll(calls))ids.add(f.get().tradeNo());assertEquals(1,ids.size());verify(gateway,times(1)).prepay(anyString(),anyString(),anyLong(),any());assertEquals(1,service.orderCount(2));}finally{pool.shutdownNow();}}
    @Test void invitationBatchIsIdempotentAndScopeControlsReadEditDelete(){var spaces=mock(SpaceService.class);var invites=new InvitationService(db,tx,quotas,spaces);var body=Map.<String,Object>of("maxUses",1);var first=invites.createIdempotent(body,2,"batch-key",2);var replay=invites.createIdempotent(body,2,"batch-key",2);assertEquals(first,replay);assertEquals(2,invites.countAuthorized(2));assertEquals(0,invites.countAuthorized(3));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->invites.updateAuthorized(first.get(0).id(),Map.of("maxUses",3),1,3));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->invites.deleteAuthorized(first.get(0).id(),1,3));assertThrows(IllegalStateException.class,()->invites.createIdempotent(Map.of("maxUses",2),2,"batch-key",2));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ps_invite",Integer.class));}
    @Test void batchCreationRejectsCustomCodeWithoutCreatingReceiptsOrInvites(){
        var invites=new InvitationService(db,tx,quotas,mock(SpaceService.class));
        assertThrows(IllegalArgumentException.class,()->invites.createIdempotent(Map.of("code","CUSTOM01"),2,"custom-batch",2));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_invite",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_mutation_request",Integer.class));
        assertEquals(2,invites.createIdempotent(Map.of("code","  "),2,"blank-batch",2).size());
    }
    @Test void invitationAndConcurrentMemberChangeCompleteWithConsistentLockOrder()throws Exception{
        db.update("INSERT INTO ps_member(user_id,status) VALUES(2,'INACTIVE')");
        var spaces=mock(SpaceService.class);var invites=new InvitationService(db,tx,quotas,spaces);
        var invite=invites.create(Map.of("code","join-org","initialLlmTokens",10),2);
        db.update("UPDATE ps_invite SET space_id='org' WHERE id=?",invite.id());
        var grantEntered=new CountDownLatch(1);var changeStarted=new CountDownLatch(1);var changeLockedUser=new CountDownLatch(1);
        doAnswer(call->{
            grantEntered.countDown();assertTrue(changeStarted.await(5,TimeUnit.SECONDS));
            // 新顺序下并发分配等待平台行；旧顺序下它先拿平台行，双方随后反向等待。
            changeLockedUser.await(500,TimeUnit.MILLISECONDS);
            db.queryForObject("SELECT user_id FROM sys_user WHERE user_id=2 FOR UPDATE",Long.class);
            return null;
        }).when(spaces).grantInvitedMembership(2,"org");
        var pool=Executors.newFixedThreadPool(2);
        try{
            var redeem=pool.submit(()->invites.redeem(2,invite.code()));assertTrue(grantEntered.await(5,TimeUnit.SECONDS));
            var memberChange=pool.submit(()->tx.executeWithoutResult(status->{
                changeStarted.countDown();
                db.queryForObject("SELECT user_id FROM sys_user WHERE user_id=2 FOR UPDATE",Long.class);changeLockedUser.countDown();
                db.update("UPDATE ps_member SET membership_version=membership_version+1 WHERE user_id=2");
            }));
            redeem.get(15,TimeUnit.SECONDS);memberChange.get(15,TimeUnit.SECONDS);
            assertEquals("ACTIVE",db.queryForObject("SELECT status FROM ps_member WHERE user_id=2",String.class));
            assertEquals(1,db.queryForObject("SELECT membership_version FROM ps_member WHERE user_id=2",Long.class));
            assertEquals(1,invites.get(invite.id()).usedCount());assertEquals(10,account().available());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_invite_redemption",Integer.class));
        }finally{changeStarted.countDown();pool.shutdownNow();}
    }
    JdbcTemplate db;TransactionTemplate tx;QuotaService quotas;JobService jobs;
    @BeforeEach void setup()throws Exception{
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));quotas=new QuotaService(db,tx,Clock.fixed(Instant.parse("2026-10-02T16:10:00Z"),ZoneOffset.UTC));jobs=new JobService(db,tx);
        db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1),user_name VARCHAR(20),nick_name VARCHAR(20))");db.update("INSERT INTO sys_user VALUES(2,'0','0','user2','用户甲'),(3,'0','0','user3','用户乙')");
    }
    void credit(long n){quotas.credit(2,"LLM",n,"grant","授权测试额度",1);}
    QuotaService.Account account(){return quotas.accounts(2).stream().filter(a->a.scope().equals("LLM")).findFirst().orElseThrow();}
    @Test void freezesAndSettlesActualExactlyOnce(){credit(200);var r=quotas.reserve(2,"request1","LLM",120);assertEquals(80,account().available());assertEquals(120,account().frozen());quotas.settle(r.id(),100);quotas.settle(r.id(),100);assertEquals(100,account().available());assertEquals(0,account().frozen());assertEquals(100,account().consumed());assertThrows(IllegalStateException.class,()->quotas.settle(r.id(),99));assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM ps_token_ledger",Integer.class));}
    @Test void duplicateReserveAndReleaseAreIdempotent(){credit(100);var r=quotas.reserve(2,"op","LLM",60);assertEquals(r.id(),quotas.reserve(2,"op","LLM",60).id());assertThrows(IllegalStateException.class,()->quotas.reserve(2,"op","LLM",61));quotas.release(r.id());quotas.release(r.id());assertEquals(100,account().available());assertEquals(0,account().frozen());assertThrows(IllegalStateException.class,()->quotas.settle(r.id(),10));}
    @Test void concurrentReservationsCannotOverspend()throws Exception{credit(100);ExecutorService executor=Executors.newFixedThreadPool(8);try{List<Callable<Boolean>> tasks=new ArrayList<>();for(int i=0;i<8;i++){final int n=i;tasks.add(()->{try{quotas.reserve(2,"parallel"+n,"LLM",30);return true;}catch(IllegalStateException ex){return false;}});}int won=0;for(Future<Boolean> f:executor.invokeAll(tasks))if(f.get())won++;assertEquals(3,won);assertEquals(10,account().available());assertEquals(90,account().frozen());}finally{executor.shutdownNow();}}
    @Test void dailyPolicyCountsReservationsAndUsesApprovedDay(){quotas.savePolicy(2,"DAILY",100,100,0);var r=quotas.reserve(2,"first","LLM",60);assertEquals(LocalDate.of(2026,10,3),r.day());assertThrows(IllegalStateException.class,()->quotas.reserve(2,"second","LLM",50));quotas.settle(r.id(),40);quotas.reserve(2,"third","LLM",60);assertEquals(0,account().available());assertEquals(40,account().consumed());}
    @Test void actualOverestimateCreatesExplicitDebtAndBlocksNextCall(){credit(100);var r=quotas.reserve(2,"over","LLM",50);quotas.settle(r.id(),200);assertEquals(0,account().available());assertEquals(100,account().debt());assertEquals(200,account().consumed());assertThrows(IllegalStateException.class,()->quotas.reserve(2,"next","LLM",1));quotas.credit(2,"LLM",150,"repay","追加并偿还已发生用量",1);assertEquals(0,account().debt());assertEquals(50,account().available());}
    @Test void creditSameKeyWithChangedAmountIsRejected(){credit(100);quotas.credit(2,"LLM",100,"grant","授权测试额度",1);assertEquals(100,account().available());assertThrows(IllegalStateException.class,()->quotas.credit(2,"LLM",101,"grant","授权测试额度",1));}
    @Test void callbackAndQueryShareUniqueSettlement(){var gateway=mock(WechatPaymentGateway.class);RechargeService service=new RechargeService(db,tx,quotas,gateway);insertOrder();var callback=payment("callback:wx1",100,"SUCCESS");service.accept(callback);service.accept(payment("query:wx1",100,"SUCCESS"));assertEquals(500,account().available());assertEquals(200,quotas.accounts(2).stream().filter(a->a.scope().equals("EMBEDDING")).findFirst().orElseThrow().available());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_payment_event",Integer.class));service.accept(payment("late",100,"NOTPAY"));assertEquals("SUCCEEDED",db.queryForObject("SELECT state FROM ps_recharge_order",String.class));}
    @Test void unpaidWechatStatesPreserveTheirActualMeaningWithoutCredit(){var gateway=mock(WechatPaymentGateway.class);RechargeService service=new RechargeService(db,tx,quotas,gateway);insertOrder();Map<String,String> expected=Map.of("NOTPAY","NOT_PAY","USERPAYING","PAYING","CLOSED","CLOSED","REVOKED","REVOKED","PAYERROR","PAYERROR","REFUND","FAILED");for(var entry:expected.entrySet()){db.update("UPDATE ps_recharge_order SET state='NOT_PAY' WHERE trade_no='trade1'");service.accept(payment("state:"+entry.getKey(),100,entry.getKey()));assertEquals(entry.getValue(),service.get("trade1",2,false).state());}db.update("UPDATE ps_recharge_order SET state='NOT_PAY' WHERE trade_no='trade1'");assertThrows(IllegalArgumentException.class,()->service.accept(payment("unknown",100,"UNKNOWN")));assertEquals("NOT_PAY",service.get("trade1",2,false).state());assertTrue(quotas.accounts(2).isEmpty());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_payment_event",Integer.class));assertEquals(1,service.orderCount(2,"NOT_PAY"));}
    @Test void terminalUnpaidOrdersDoNotReopenOrRequery(){var gateway=mock(WechatPaymentGateway.class);when(gateway.enabled()).thenReturn(true);RechargeService service=new RechargeService(db,tx,quotas,gateway);insertOrder();for(String state:List.of("CLOSED","FAILED","REVOKED","PAYERROR")){db.update("UPDATE ps_recharge_order SET state=? WHERE trade_no='trade1'",state);service.accept(payment("late:"+state,100,"NOTPAY"));assertEquals(state,service.query("trade1",2).state());}verify(gateway,never()).query(anyString());assertTrue(quotas.accounts(2).isEmpty());}
    @Test void paymentAmountMismatchNeverCredits(){var gateway=mock(WechatPaymentGateway.class);RechargeService service=new RechargeService(db,tx,quotas,gateway);insertOrder();assertThrows(IllegalArgumentException.class,()->service.accept(payment("callback",101,"SUCCESS")));assertTrue(quotas.accounts(2).isEmpty());assertEquals("NOT_PAY",db.queryForObject("SELECT state FROM ps_recharge_order",String.class));}
    @Test void exhaustedInvitationDoesNotCreateMember(){var spaces=mock(SpaceService.class);InvitationService invites=new InvitationService(db,tx,quotas,spaces);var invite=invites.create(Map.of("code","testcode","maxUses",1,"initialLlmTokens",10L),1);invites.redeem(2,invite.code());invites.redeem(2,invite.code());assertThrows(IllegalArgumentException.class,()->invites.redeem(3,invite.code()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_member",Integer.class));assertEquals(10,account().available());}
    @Test void jobSubmissionDoesNotGrantBusinessEligibility(){var first=jobs.submit(2,"DOCUMENT_PROCESS","doc1","upload1");assertEquals(first.id(),jobs.submit(2,"DOCUMENT_PROCESS","doc1","upload1").id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_member",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_outbox",Integer.class));assertThrows(IllegalStateException.class,()->jobs.submit(2,"DOCUMENT_PROCESS","otherdoc","upload1"));}
    @Test void canceledOrExpiredLeaseCannotCommitResult(){var job=jobs.submit(2,"DOCUMENT_PROCESS","doc1","up");var claim=jobs.claimById(job.id(),30).orElseThrow();jobs.cancel(claim.id(),claim.rowVersion());assertThrows(IllegalStateException.class,()->jobs.complete(claim.id(),claim.leaseToken(),Map.of("ok",true)));var second=jobs.submit(2,"DOCUMENT_PROCESS","doc2","up2");var c=jobs.claimById(second.id(),30).orElseThrow();db.update("UPDATE ps_job SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),c.id());assertThrows(IllegalStateException.class,()->jobs.complete(c.id(),c.leaseToken(),Map.of()));assertNotEquals(c.leaseToken(),jobs.claimById(c.id(),30).orElseThrow().leaseToken());}
    @Test void failedInvitationRollsBackPlatformRegistration(){var spaces=mock(SpaceService.class);InvitationService invites=new InvitationService(db,tx,quotas,spaces);var invite=invites.create(Map.of("code","joincode","maxUses",1),1);PaismartRegistrationExtension extension=new PaismartRegistrationExtension(invites,tx);var user=new com.ruoyi.common.core.domain.entity.SysUser();user.setUserId(4L);var body=new com.ruoyi.common.core.domain.model.RegisterBody();body.setInvitationCode(invite.code());assertThrows(IllegalArgumentException.class,()->extension.register(user,body,()->{db.update("INSERT INTO sys_user VALUES(4,'1','0','user4','未启用账户')");return true;}));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=4",Integer.class));assertEquals(0,db.queryForObject("SELECT used_count FROM ps_invite",Integer.class));}
    void insertOrder(){db.update("INSERT INTO ps_recharge_order(id,user_id,request_key,trade_no,snapshot_json,amount_fen,llm_tokens,embedding_tokens,state,expires_at) VALUES('order1',2,'key','trade1','{}',100,500,200,'NOT_PAY',?)",Timestamp.from(Instant.now().plusSeconds(60)));}
    WechatPaymentGateway.Payment payment(String event,long amount,String state){return new WechatPaymentGateway.Payment(event,"trade1","wx1",state,amount,"mch","app","CNY","2026-10-03T01:00:00+08:00");}
}
