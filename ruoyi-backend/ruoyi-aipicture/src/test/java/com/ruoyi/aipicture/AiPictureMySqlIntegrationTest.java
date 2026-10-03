package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;
import com.ruoyi.aipicture.worker.*;

/** 显式 opt-in；仅已批准现有库 aip_* 的随机 UUID 记录，不建库/账号/DDL。 */
@EnabledIfEnvironmentVariable(named="AIPICTURE_MYSQL_INTEGRATION",matches="true")
class AiPictureMySqlIntegrationTest {
    @TempDir Path files;
    final long owner=1;
    final List<String> assetIds=new ArrayList<>(),sessionIds=new ArrayList<>(),runIds=new ArrayList<>(),batchIds=new ArrayList<>(),planIds=new ArrayList<>(),evaluationIds=new ArrayList<>(),exportIds=new ArrayList<>(),keys=new ArrayList<>();
    AiPictureRepository db;AssetService assets;SessionService sessions;RunService runs;BatchExportEvaluationService business;AiPictureProperties props;TransactionTemplate tx;
    Map<String,Object> metadata(Long expected) {String key="integration:"+Json.id();keys.add(key);var p=Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey",key);if(expected!=null) p.put("expectedVersion",expected);return p;}
    void openServices() throws Exception {
        String url=System.getenv("RUOYI_DB_URL"),username=System.getenv("RUOYI_DB_USERNAME"),password=System.getenv("RUOYI_DB_PASSWORD");
        assertNotNull(url,"真实 MySQL 检查必须显式注入 RUOYI_DB_URL");assertNotNull(username,"需显式注入现有账号，不猜测默认账号");assertNotNull(password,"需显式注入托管密钥，测试不输出密码");
        var ds=new DriverManagerDataSource(url,username,password);ds.setDriverClassName("com.mysql.cj.jdbc.Driver");db=new AiPictureRepository(new NamedParameterJdbcTemplate(ds));tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        props=new AiPictureProperties();props.setStorageRoot(files.toAbsolutePath().toString());props.setPublicInternalBaseUrl("http://127.0.0.1:18218");var registry=new ToolRegistry(props);var storage=new AiPictureStorage(props,new AiPictureWorkerClient(props));assets=new AssetService(db,storage);var commands=new CommandIdempotency(db,tx);sessions=new SessionService(db,assets,tx,registry,commands);runs=new RunService(db,sessions,assets,registry,storage,props,tx);business=new BatchExportEvaluationService(db,runs,assets,registry,storage,tx,commands);var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("batch",business);runs.batchProvider(factory.getBeanProvider(BatchExportEvaluationService.class));new AiPictureSchemaGuard(db).afterSingletonsInstantiated();
    }
    byte[] png(int color) throws Exception {return png(color,40,60);}
    byte[] png(int color,int width,int height) throws Exception {var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<height;y++) for(int x=0;x<width;x++) image.setRGB(x,y,color);var output=new ByteArrayOutputStream();ImageIO.write(image,"PNG",output);return output.toByteArray();}
    Map<String,Object> trackRun(Map<String,Object> run) {runIds.add(Json.string(run.get("id")));return run;}
    // 与正在运行的本通道 Worker 共用原库：仅隔离本 fixture，不能抢别的任务或改生产调度。
    void reserveDispatch(String id) {db.execute("UPDATE aip_dispatch d JOIN aip_run r ON r.id=d.run_id SET d.available_at=:future WHERE r.user_id=:owner AND (r.id=:id OR r.batch_id=:id) AND d.status='pending'",Json.of("id",id,"owner",owner,"future",System.currentTimeMillis()+3600000));}
    Map<String,Object> reservedSubmit(long user,String session,String tool,Map<String,Object> params,Map<String,Object> request) {assertEquals(owner,user);return tx.execute(status->{var run=runs.submit(user,session,tool,params,request);reserveDispatch(Json.string(run.get("id")));return run;});}
    Map<String,Object> reservedBatch(Map<String,Object> request) {return tx.execute(status->{var batch=business.createBatch(owner,request);reserveDispatch(Json.string(batch.get("id")));return batch;});}
    Map<String,Object> reservedEvaluation(Map<String,Object> request) {return tx.execute(status->{var evaluation=business.createEvaluation(owner,request);reserveDispatch(Json.string(evaluation.get("run_id")));return evaluation;});}
    void reservedPlanAction(PlanService plans,String session,String turn,String action,Map<String,Object> request) {tx.execute(status->{plans.action(owner,session,turn,action,request);for(var step:db.query("SELECT s.run_id FROM aip_plan_step s JOIN aip_agent_turn t ON t.id=s.turn_id WHERE t.id=:turn AND t.user_id=:owner",Json.of("turn",turn,"owner",owner))) if(step.get("run_id")!=null) reserveDispatch(Json.string(step.get("run_id")));return null;});}
    Map<String,Object> controlledClaim(String id) {return tx.execute(status->{db.owned("aip_run",id,owner,true);db.execute("UPDATE aip_dispatch SET available_at=:now WHERE run_id=:id AND status='pending'",Json.of("id",id,"now",System.currentTimeMillis()));return runs.claimForRun("integration-worker",id);});}
    long version(String session) {return Json.number(sessions.detail(owner,session).get("row_version"));}
    double x(String session) {return Json.decimal(Json.map(DocumentCommandService.resolve(Json.map(sessions.detail(owner,session).get("document")),"base",false).get("transform")).get("x"),0);}
    @Test void realOwnerCasHistorySnapshotsRestartLeaseAndIdempotentReceipt() throws Exception {
        openServices();
        try {
            var a=assets.upload(owner,png(0xff223388));assetIds.add(Json.string(a.get("id")));var b=assets.upload(owner,png(0xff339922));assetIds.add(Json.string(b.get("id")));
            var create=metadata(null);create.putAll(Json.of("current_asset_id",a.get("id"),"asset_ids",List.of(b.get("id")),"title","技术检查-"+Json.id()));var session=sessions.create(owner,create);String id=Json.string(session.get("id"));sessionIds.add(id);assertEquals(id,sessions.create(owner,create).get("id"));
            assertThrows(AiPictureException.class,()->assets.get(owner+1,Json.string(a.get("id"))));assertThrows(AiPictureException.class,()->sessions.detail(owner+1,id));
            var move=metadata(0L);var first=trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",3),move));assertEquals("succeeded",first.get("status"));assertEquals(3,x(id));assertEquals(first.get("id"),runs.submit(owner,id,"move_layer",Json.of("dx",3),move).get("id"));assertEquals(2,sessions.history(owner,id).size());
            assertThrows(AiPictureException.class,()->runs.submit(owner,id,"move_layer",Json.of("dx",99),move));assertThrows(AiPictureException.class,()->runs.submit(owner,id,"move_layer",Json.of("dx",1),metadata(0L)));
            long revision=Json.number(sessions.detail(owner,id).get("revision"));var undo=metadata(version(id));sessions.historyMove(owner,id,undo,true);assertEquals(0,x(id));assertEquals(0,x(Json.string(sessions.historyMove(owner,id,undo,true).get("id"))));assertTrue(Json.number(sessions.detail(owner,id).get("revision"))>revision);
            long noopVersion=version(id);trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",0.0),metadata(noopVersion)));assertEquals(noopVersion,version(id));assertEquals(true,sessions.detail(owner,id).get("can_redo"));sessions.historyMove(owner,id,metadata(version(id)),false);assertEquals(3,x(id));
            var switchB=metadata(version(id));switchB.put("current_asset_id",b.get("id"));sessions.patch(owner,id,switchB);trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",6),metadata(version(id))));
            var switchA=metadata(version(id));switchA.put("current_asset_id",a.get("id"));sessions.patch(owner,id,switchA);assertEquals(3,x(id));
            for(int i=0;i<25;i++) trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",1),metadata(version(id))));assertEquals(20,sessions.history(owner,id).size());
            var switchBack=metadata(version(id));switchBack.put("current_asset_id",b.get("id"));sessions.patch(owner,id,switchBack);assertEquals(6,x(id),"切回资产必须读取独立文档快照，不能依赖已淘汰历史");
            openServices();assertEquals(6,x(id),"重建持久服务后正式文档仍在MySQL");
            var job=trackRun(reservedSubmit(owner,id,"adjust_image",Json.of("brightness",0.1),metadata(version(id))));var claim=controlledClaim(Json.string(job.get("id")));assertEquals(job.get("id"),claim.get("run_id"));assertEquals(version(id),Json.number(claim.get("input_version")));assertNotNull(claim.get("lease_token"));
            var lease=Json.of("run_id",claim.get("run_id"),"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));assertArrayEquals(assets.download(owner,Json.string(b.get("id"))),runs.inputAsset(Json.string(b.get("id")),lease));assertThrows(AiPictureException.class,()->runs.inputAsset(Json.string(a.get("id")),lease));
            var heartbeat=Json.copy(lease);heartbeat.put("progress",70);heartbeat.put("stage","受控协议检查");runs.heartbeat(heartbeat);heartbeat.put("progress",20);runs.heartbeat(heartbeat);assertEquals(70,Json.number(runs.get(owner,Json.string(job.get("id"))).get("progress")));
            String outputId=Json.id();var artifact=Json.copy(lease);artifact.putAll(Json.of("id",outputId,"content_base64",Base64.getEncoder().encodeToString(png(0xff663399)),"kind","generated","source","tool","format","png"));assetIds.add(outputId);runs.uploadArtifact(artifact);assertEquals(outputId,runs.uploadArtifact(artifact).get("id"));
            String tinyId=Json.id();var tiny=Json.copy(lease);tiny.putAll(Json.of("id",tinyId,"content_base64",Base64.getEncoder().encodeToString(png(0xffabcdef,8,10)),"kind","subject","source","tool","format","png"));assetIds.add(tinyId);var tinySaved=runs.uploadArtifact(tiny);assertEquals(8,tinySaved.get("width"));
            var document=Json.copy(Json.map(sessions.detail(owner,id).get("document")));DocumentCommandService.resolve(document,"base",false).put("asset_id",outputId);var tinyDoc=DocumentCommandService.documentOf(tinySaved);var tinyLayer=Json.map(Json.list(tinyDoc.get("layers")).get(0));tinyLayer.put("id","tiny-subject");Json.list(document.get("layers")).add(tinyLayer);var receipt=Json.copy(lease);receipt.putAll(Json.of("schema_version","1","tool_registry_version","2026.10-r2","status","succeeded","result",Json.of("asset_ids",List.of(outputId,tinyId),"adopt_asset_id",outputId,"document",document),"provider",Json.of("mode","controlled-protocol-test")));
            runs.receipt(receipt);long committedVersion=version(id);assertEquals(outputId,sessions.detail(owner,id).get("current_asset_id"));assertEquals(committedVersion,Json.number(sessions.detail(owner,id).get("row_version")));runs.receipt(receipt);assertEquals(committedVersion,version(id));assertEquals(1,db.count("SELECT COUNT(*) FROM aip_asset WHERE id=:id",Json.of("id",outputId)));
            var stale=Json.copy(receipt);stale.put("attempt",Json.number(claim.get("attempt"))+1);assertThrows(AiPictureException.class,()->runs.receipt(stale));openServices();assertEquals(outputId,sessions.detail(owner,id).get("current_asset_id"));assertEquals(8,Json.number(DocumentCommandService.resolve(Json.map(sessions.detail(owner,id).get("document")),"tiny-subject",false).get("width")));
            // 不调用任何 Provider；用两轮明确失败验证实际 MySQL 的逐项恢复计数和公开请求幂等。
            var batchRequest=metadata(null);batchRequest.putAll(Json.of("asset_ids",List.of(a.get("id")),"operations",List.of(Json.of("tool","adjust_image","params",Json.of("brightness",0.1))),"formats",List.of("png")));var batch=reservedBatch(batchRequest);String batchId=Json.string(batch.get("id"));batchIds.add(batchId);runIds.add(batchId);var item=Json.map(Json.list(batch.get("items")).get(0));String itemId=Json.string(item.get("id"));String child1=Json.string(item.get("run_id"));runIds.add(child1);controlledFailure(child1);assertEquals("failed",business.batch(owner,batchId).get("status"));
            var retryRequest=metadata(null);var retried=business.retryItem(owner,batchId,itemId,retryRequest);String child2=Json.string(Json.map(Json.list(retried.get("items")).get(0)).get("run_id"));runIds.add(child2);assertNotEquals(child1,child2);controlledFailure(child2);assertEquals(2,Json.number(Json.map(Json.list(business.batch(owner,batchId).get("items")).get(0)).get("attempt")),"第二个子run attempt=1不能倒写累计重试次数");
            long childCount=db.count("SELECT COUNT(*) FROM aip_run WHERE batch_id=:id AND user_id=:owner",Json.of("id",batchId,"owner",owner));business.retryItem(owner,batchId,itemId,retryRequest);assertEquals(childCount,db.count("SELECT COUNT(*) FROM aip_run WHERE batch_id=:id AND user_id=:owner",Json.of("id",batchId,"owner",owner)),"同一公开retry幂等键不能再派发一次");
            var third=business.retryItem(owner,batchId,itemId,metadata(null));String child3=Json.string(Json.map(Json.list(third.get("items")).get(0)).get("run_id"));runIds.add(child3);assertNotEquals(child2,child3,"新的显式重试必须登记新子run，不得复用旧失败run");runs.cancel(owner,child3);
        } finally {cleanup();}
    }
    void controlledFailure(String child) {
        var claim=controlledClaim(child);assertNotNull(claim);runs.receipt(Json.of("run_id",child,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"),"status","failed","result",Map.of(),"error_code","CONTROLLED_FAILURE","message","受控协议失败，不调用模型"));
    }
    void controlledSuccess(String child) throws Exception {
        var claim=controlledClaim(child);assertNotNull(claim);String output=Json.id();assetIds.add(output);var lease=Json.of("run_id",child,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));var artifact=Json.copy(lease);artifact.putAll(Json.of("id",output,"content_base64",Base64.getEncoder().encodeToString(png(0xff345678)),"kind","generated","source","tool","format","png"));runs.uploadArtifact(artifact);var receipt=Json.copy(lease);receipt.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",List.of(output)),"provider",Json.of("mode","controlled-protocol-test")));runs.receipt(receipt);
    }
    @Test void realPlanPartialBatchRetriesOnlyFailedItemAndContinuesNextDocumentStep() throws Exception {
        openServices();var user=new LoginUser();user.setUserId(owner);user.setPermissions(Set.of("*:*:*"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        try {
            var a=assets.upload(owner,png(0xff228899));assetIds.add(Json.string(a.get("id")));var b=assets.upload(owner,png(0xff883322));assetIds.add(Json.string(b.get("id")));var create=metadata(null);create.putAll(Json.of("current_asset_id",a.get("id"),"asset_ids",List.of(b.get("id")),"title","计划恢复技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            var planner=mock(AiPictureWorkerClient.class);when(planner.call(eq("plan"),anyMap())).thenReturn(Json.of("result",Json.of("reply","受控计划，不调用模型","steps",List.of(Json.of("id","batch-step","tool","batch_process","params",Json.of("asset_ids",List.of(a.get("id"),b.get("id")),"operations",List.of(Json.of("tool","adjust_image","params",Json.of("brightness",0.1)))),"depends_on",List.of()),Json.of("id","move-step","tool","move_layer","params",Json.of("layer_id","base","dx",1),"depends_on",List.of("batch-step"))))));
            var plans=new PlanService(db,runs,sessions,new ToolRegistry(props),planner,tx,new CommandIdempotency(db,tx));var send=metadata(version(session));send.put("text","受控批量处理后移动底图");var turn=plans.send(owner,session,send);String turnId=Json.string(turn.get("id"));planIds.add(turnId);assertEquals("waiting_confirmation",turn.get("status"));var confirm=metadata(version(session));confirm.put("plan_hash",turn.get("plan_hash"));reservedPlanAction(plans,session,turnId,"confirm",confirm);
            var step=db.one("SELECT run_id FROM aip_plan_step WHERE turn_id=:turn AND step_id='batch-step'",Json.of("turn",turnId));String batchId=Json.string(step.get("run_id"));batchIds.add(batchId);runIds.add(batchId);var items=Json.list(business.batch(owner,batchId).get("items"));String successItem=Json.string(Json.map(items.get(0)).get("id")),failedItem=Json.string(Json.map(items.get(1)).get("id"));String successRun=Json.string(Json.map(items.get(0)).get("run_id")),failedRun=Json.string(Json.map(items.get(1)).get("run_id"));runIds.add(successRun);runIds.add(failedRun);controlledSuccess(successRun);controlledFailure(failedRun);assertEquals("partial_success",business.batch(owner,batchId).get("status"));assertEquals("failed",plans.list(owner,session).get(0).get("status"));
            var retry=metadata(version(session));reservedPlanAction(plans,session,turnId,"retry",retry);var resumed=Json.list(business.batch(owner,batchId).get("items"));assertEquals(successRun,Json.string(Json.map(resumed.get(0)).get("run_id")),"成功项不能重复派发");assertEquals("succeeded",Json.map(resumed.get(0)).get("status"));assertEquals(successItem,Json.map(resumed.get(0)).get("id"));assertEquals(failedItem,Json.map(resumed.get(1)).get("id"));String retriedRun=Json.string(Json.map(resumed.get(1)).get("run_id"));runIds.add(retriedRun);assertNotEquals(failedRun,retriedRun);controlledSuccess(retriedRun);
            var finalSteps=db.query("SELECT run_id,status FROM aip_plan_step WHERE turn_id=:turn ORDER BY position",Json.of("turn",turnId));for(var row:finalSteps) if(row.get("run_id")!=null&&!runIds.contains(Json.string(row.get("run_id")))) runIds.add(Json.string(row.get("run_id")));assertTrue(finalSteps.stream().allMatch(row->"succeeded".equals(row.get("status"))));assertEquals("succeeded",plans.list(owner,session).get(0).get("status"));assertEquals(1,x(session));assertEquals(batchId,Json.string(finalSteps.get(0).get("run_id")),"恢复保留同一正式batch母任务");
        } finally {SecurityContextHolder.clearContext();cleanup();}
    }
    @Test void realEvaluationReceiptPreservesProviderMetadata() throws Exception {
        openServices();
        try {
            var input=assets.upload(owner,png(0xff556677));assetIds.add(Json.string(input.get("id")));var request=metadata(null);request.put("manifest",List.of(Json.of("id","provider-report-case","task","encode","input_asset_id",input.get("id"),"params",Json.of("format","png"),"metrics",List.of("size"))));var evaluation=reservedEvaluation(request);String evaluationId=Json.string(evaluation.get("id")),runId=Json.string(evaluation.get("run_id"));evaluationIds.add(evaluationId);runIds.add(runId);
            var claim=controlledClaim(runId);assertNotNull(claim);var provider=Json.of("algorithm","pillow","runtime_version","2026.10-r2","offline_test",false,"mode","controlled-protocol-test");runs.receipt(Json.of("run_id",runId,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"),"schema_version","1","tool_registry_version","2026.10-r2","status","succeeded","provider",provider,"result",Json.of("cases",List.of(Json.of("id","provider-report-case","task","encode","passed",true)),"metrics",Json.of("passed",1,"total",1))));
            var run=runs.get(owner,runId);var report=business.evaluation(owner,evaluationId);assertEquals(provider,Json.map(run.get("provider")),"任务必须保存实际回执中的算法/版本/模式");assertEquals(provider,Json.map(report.get("provider")),"评测报告必须与已保存run保留同一份实际provider元数据");
        } finally {cleanup();}
    }
    @Test void realWallOnlyReceiptRecordsResultWithoutChangingCanvasAndPreservesTrueNoop() throws Exception {
        openServices();
        try {
            var input=assets.upload(owner,png(0xff557799));assetIds.add(Json.string(input.get("id")));var create=metadata(null);create.putAll(Json.of("current_asset_id",input.get("id"),"title","图片墙历史技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            trackRun(reservedSubmit(owner,session,"move_layer",Json.of("dx",2),metadata(version(session))));sessions.historyMove(owner,session,metadata(version(session)),true);assertEquals(true,sessions.detail(owner,session).get("can_redo"));
            var before=sessions.detail(owner,session);long revision=Json.number(before.get("revision")),beforeVersion=version(session);var job=trackRun(reservedSubmit(owner,session,"prepare_delivery_sizes",Map.of(),metadata(beforeVersion)));String run=Json.string(job.get("id"));var claim=controlledClaim(run);var lease=Json.of("run_id",run,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));String output=Json.id();assetIds.add(output);var artifact=Json.copy(lease);artifact.putAll(Json.of("id",output,"content_base64",Base64.getEncoder().encodeToString(png(0xff113355)),"kind","generated","source","tool","format","png"));runs.uploadArtifact(artifact);var result=Json.of("asset_ids",List.of(output),"variants",List.of(Json.of("asset_id",output,"ratio","1:1","kind","delivery")));var receipt=Json.copy(lease);receipt.putAll(Json.of("status","succeeded","result",result));runs.receipt(receipt);
            var after=sessions.detail(owner,session);assertEquals(2,Json.number(after.get("history_seq")),"成功投放只上图片墙时也必须建立历史，并截断原重做分支");assertEquals(revision,Json.number(after.get("revision")),"新增图片墙结果不能改变画布revision");assertEquals(beforeVersion+1,version(session));assertTrue(Json.equivalent(before.get("document"),after.get("document")));assertEquals(false,after.get("can_redo"));var history=sessions.history(owner,session);assertEquals(2,history.size());var entry=history.get(0);assertEquals("prepare_delivery_sizes",entry.get("action"));assertEquals(List.of(output),Json.list(Json.map(entry.get("result")).get("asset_ids")));assertTrue(Json.equivalent(result.get("variants"),Json.map(entry.get("result")).get("variants")));assertEquals(revision,Json.number(Json.map(entry.get("result")).get("revision")));assertEquals(input.get("id"),Json.map(entry.get("result")).get("current_asset_id"));assertTrue(Json.equivalent(before.get("document"),Json.map(entry.get("result")).get("document")));
            runs.receipt(receipt);assertEquals(beforeVersion+1,version(session));assertEquals(2,sessions.history(owner,session).size(),"重复回执不能再写历史");openServices();assertEquals("prepare_delivery_sizes",sessions.history(owner,session).get(0).get("action"),"重建服务后图片墙历史仍从MySQL读取");
            // 已附加产物没有新的墙变更，必须保留 redo；不能仅凭非空 asset_ids 写历史。
            sessions.historyMove(owner,session,metadata(version(session)),true);long noopVersion=version(session),noopRevision=Json.number(sessions.detail(owner,session).get("revision"));var noopJob=trackRun(reservedSubmit(owner,session,"prepare_delivery_sizes",Map.of(),metadata(noopVersion)));var noopClaim=controlledClaim(Json.string(noopJob.get("id")));var noopLease=Json.of("run_id",noopJob.get("id"),"attempt",noopClaim.get("attempt"),"lease_token",noopClaim.get("lease_token"));String alreadyAttached=Json.id();assetIds.add(alreadyAttached);var noopArtifact=Json.copy(noopLease);noopArtifact.putAll(Json.of("id",alreadyAttached,"content_base64",Base64.getEncoder().encodeToString(png(0xff668899)),"kind","generated","source","tool","format","png"));runs.uploadArtifact(noopArtifact);/* 模拟正式已采用且已上墙的同一结果；暂存素材不能作为此历史 noop 的输入。 */db.execute("UPDATE aip_asset SET status='ready' WHERE id=:id AND user_id=:owner",Json.of("id",alreadyAttached,"owner",owner));sessions.addWall(owner,session,List.of(alreadyAttached));var noopReceipt=Json.copy(noopLease);noopReceipt.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",List.of(alreadyAttached))));runs.receipt(noopReceipt);assertEquals(noopVersion,version(session),"没有真实新增图片墙时不增长版本");assertEquals(noopRevision,Json.number(sessions.detail(owner,session).get("revision")));assertEquals(true,sessions.detail(owner,session).get("can_redo"));assertEquals("prepare_delivery_sizes",sessions.history(owner,session).get(0).get("action"));
            sessions.historyMove(owner,session,metadata(version(session)),false);long historySeq=Json.number(sessions.detail(owner,session).get("history_seq"));var selectionJob=trackRun(reservedSubmit(owner,session,"selection",Json.of("points",List.of(Json.of("x",0.5,"y",0.5)),"revision",sessions.detail(owner,session).get("revision")),metadata(version(session))));var selectionClaim=controlledClaim(Json.string(selectionJob.get("id")));var selectionLease=Json.of("run_id",selectionJob.get("id"),"attempt",selectionClaim.get("attempt"),"lease_token",selectionClaim.get("lease_token"));String mask=Json.id();assetIds.add(mask);var maskArtifact=Json.copy(selectionLease);maskArtifact.putAll(Json.of("id",mask,"content_base64",Base64.getEncoder().encodeToString(png(0xffffffff)),"kind","mask","source","selection","format","png"));runs.uploadArtifact(maskArtifact);var selectionReceipt=Json.copy(selectionLease);selectionReceipt.putAll(Json.of("status","succeeded","result",Json.of("mask_asset_id",mask,"asset_ids",List.of(mask),"markers",List.of(Json.of("index",1,"x",0.5,"y",0.5)))));runs.receipt(selectionReceipt);assertEquals(historySeq,Json.number(sessions.detail(owner,session).get("history_seq")),"选区不得进入图片墙历史");
            var exportRequest=metadata(version(session));exportRequest.put("format","png");var exportJob=trackRun(tx.execute(status->{var exportRun=business.exportSession(owner,session,exportRequest);reserveDispatch(Json.string(exportRun.get("id")));return exportRun;}));var exportClaim=controlledClaim(Json.string(exportJob.get("id")));var exportLease=Json.of("run_id",exportJob.get("id"),"attempt",exportClaim.get("attempt"),"lease_token",exportClaim.get("lease_token"));String exported=Json.id();assetIds.add(exported);var exportArtifact=Json.copy(exportLease);exportArtifact.putAll(Json.of("id",exported,"content_base64",Base64.getEncoder().encodeToString(png(0xff999999)),"kind","export","source","export","format","png"));runs.uploadArtifact(exportArtifact);var exportReceipt=Json.copy(exportLease);exportReceipt.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",List.of(exported),"manifest",List.of())));var exportedRun=runs.receipt(exportReceipt);exportIds.add(Json.string(Json.map(exportedRun.get("result")).get("export_id")));assertEquals(historySeq,Json.number(sessions.detail(owner,session).get("history_seq")),"导出不得进入图片墙历史");assertFalse(assets.wall(owner,session).stream().anyMatch(a->Set.of(mask,exported).contains(Json.string(a.get("id")))));
        } finally {cleanup();}
    }
    @Test void realPublicBatchAndAgentCancellationCascadeAndLateReceiptsCannotReviveMother() throws Exception {
        openServices();var user=new LoginUser();user.setUserId(owner);user.setPermissions(Set.of("*:*:*"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        try {
            var a=assets.upload(owner,png(0xff228855));assetIds.add(Json.string(a.get("id")));var b=assets.upload(owner,png(0xff885522));assetIds.add(Json.string(b.get("id")));var create=metadata(null);create.putAll(Json.of("current_asset_id",a.get("id"),"asset_ids",List.of(b.get("id")),"title","批量取消技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);long beforeVersion=version(session);
            var params=Json.of("asset_ids",List.of(a.get("id"),b.get("id")),"operations",List.of(Json.of("tool","adjust_image","params",Json.of("brightness",0.1))));var parent=trackRun(reservedSubmit(owner,session,"batch_process",params,metadata(beforeVersion)));String batchId=Json.string(parent.get("id"));batchIds.add(batchId);var items=Json.list(business.batch(owner,batchId).get("items"));String runningChild=Json.string(Json.map(items.get(0)).get("run_id")),queuedChild=Json.string(Json.map(items.get(1)).get("run_id"));runIds.addAll(List.of(runningChild,queuedChild));var claim=controlledClaim(runningChild);assertNotNull(claim,"定向领取失败，当前子任务状态="+runs.get(owner,runningChild).get("status")+"，worker="+runs.get(owner,runningChild).get("worker_id"));var lease=Json.of("run_id",runningChild,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));String output=Json.id();assetIds.add(output);var artifact=Json.copy(lease);artifact.putAll(Json.of("id",output,"content_base64",Base64.getEncoder().encodeToString(png(0xff123456)),"kind","generated","source","tool","format","png"));runs.uploadArtifact(artifact);
            runs.cancel(owner,batchId);var late=Json.copy(lease);late.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",List.of(output))));var lateResult=runs.receipt(late);assertAll("公共母任务取消必须完整停止子任务且保持母任务终态",
                ()->assertEquals("cancelled",runs.get(owner,runningChild).get("status"),"执行中子任务必须取消"),()->assertEquals("cancelled",runs.get(owner,queuedChild).get("status"),"排队子任务必须取消"),()->assertEquals("cancelled",lateResult.get("status"),"迟到成功回执不得采用"),()->assertEquals("cancelled",runs.get(owner,batchId).get("status"),"迟到子回执不得复活母任务"),()->assertEquals("cancelled",business.batch(owner,batchId).get("status")),()->assertEquals(beforeVersion,version(session)),()->assertNull(sessions.detail(owner,session).get("active_run_id")),()->assertNull(controlledClaim(queuedChild),"已取消子任务不可领取"),()->assertEquals("staged",db.owned("aip_asset",output,owner,false).get("status")),()->assertThrows(AiPictureException.class,()->assets.record(owner,output)),()->assertFalse(assets.wall(owner,session).stream().anyMatch(image->output.equals(image.get("id")))));
            runs.refreshBatch(batchId,owner);runs.cancel(owner,batchId);assertEquals("cancelled",runs.get(owner,batchId).get("status"));
            // 同一母任务路径也被 Agent 使用；规划使用受控响应，不调用模型。
            var planner=mock(AiPictureWorkerClient.class);when(planner.call(eq("plan"),anyMap())).thenReturn(Json.of("result",Json.of("reply","受控取消计划","steps",List.of(Json.of("id","batch-step","tool","batch_process","params",params,"depends_on",List.of()),Json.of("id","move-step","tool","move_layer","params",Json.of("dx",1),"depends_on",List.of("batch-step"))))));var plans=new PlanService(db,runs,sessions,new ToolRegistry(props),planner,tx,new CommandIdempotency(db,tx));var send=metadata(version(session));send.put("text","受控批量取消");var turn=plans.send(owner,session,send);String turnId=Json.string(turn.get("id"));planIds.add(turnId);var confirm=metadata(version(session));confirm.put("plan_hash",turn.get("plan_hash"));reservedPlanAction(plans,session,turnId,"confirm",confirm);String agentBatch=Json.string(db.one("SELECT run_id FROM aip_plan_step WHERE turn_id=:turn AND step_id='batch-step'",Json.of("turn",turnId)).get("run_id"));batchIds.add(agentBatch);runIds.add(agentBatch);for(Object item:Json.list(business.batch(owner,agentBatch).get("items"))) runIds.add(Json.string(Json.map(item).get("run_id")));plans.action(owner,session,turnId,"cancel",metadata(version(session)));assertEquals("cancelled",plans.list(owner,session).get(0).get("status"));assertEquals("cancelled",runs.get(owner,agentBatch).get("status"));assertTrue(Json.list(business.batch(owner,agentBatch).get("items")).stream().allMatch(item->"cancelled".equals(Json.map(item).get("status"))));assertNull(sessions.detail(owner,session).get("active_run_id"));
            // 独立批量页取消保留成功项及其产物，但停止所有尚未完成的项。
            var batchRequest=metadata(null);batchRequest.putAll(params);var separate=reservedBatch(batchRequest);String separateId=Json.string(separate.get("id"));batchIds.add(separateId);runIds.add(separateId);var separateItems=Json.list(separate.get("items"));String successfulChild=Json.string(Json.map(separateItems.get(0)).get("run_id")),pendingChild=Json.string(Json.map(separateItems.get(1)).get("run_id"));runIds.addAll(List.of(successfulChild,pendingChild));controlledSuccess(successfulChild);business.cancelBatch(owner,separateId);assertEquals("cancelled",runs.get(owner,separateId).get("status"));assertEquals("succeeded",runs.get(owner,successfulChild).get("status"));assertEquals("cancelled",runs.get(owner,pendingChild).get("status"));assertFalse(Json.list(Json.map(runs.get(owner,separateId).get("result")).get("asset_ids")).isEmpty(),"取消其余任务仍保留成功输出供下载");
        } finally {SecurityContextHolder.clearContext();cleanup();}
    }
    @Test void realCancelledBatchExplicitRetryResumesOnlyKnownFailedItemAndIsIdempotent() throws Exception {
        openServices();
        try {
            var inputs=new ArrayList<String>();for(int color:List.of(0xff112233,0xff334455,0xff556677)) {var asset=assets.upload(owner,png(color));String id=Json.string(asset.get("id"));assetIds.add(id);inputs.add(id);}var request=metadata(null);request.putAll(Json.of("asset_ids",inputs,"operations",List.of(Json.of("tool","adjust_image","params",Json.of("brightness",0.1)))));var batch=reservedBatch(request);String batchId=Json.string(batch.get("id"));batchIds.add(batchId);runIds.add(batchId);var items=Json.list(batch.get("items"));for(Object item:items) runIds.add(Json.string(Json.map(item).get("run_id")));String success=Json.string(Json.map(items.get(0)).get("run_id")),failed=Json.string(Json.map(items.get(1)).get("run_id")),cancelled=Json.string(Json.map(items.get(2)).get("run_id")),failedItem=Json.string(Json.map(items.get(1)).get("id"));controlledSuccess(success);controlledFailure(failed);runs.cancel(owner,batchId);assertEquals("cancelled",runs.get(owner,batchId).get("status"));
            var retry=metadata(null);var resumed=tx.execute(status->{var result=business.retryItem(owner,batchId,failedItem,retry);reserveDispatch(batchId);return result;});String retried=Json.string(Json.map(Json.list(resumed.get("items")).get(1)).get("run_id"));runIds.add(retried);assertEquals("running",runs.get(owner,batchId).get("status"),"用户明确重试已知失败项必须恢复母任务轮询状态");assertNotEquals(failed,retried);assertEquals("succeeded",runs.get(owner,success).get("status"));assertEquals("cancelled",runs.get(owner,cancelled).get("status"));controlledFailure(retried);assertEquals("partial_success",runs.get(owner,batchId).get("status"));long count=db.count("SELECT COUNT(*) FROM aip_run WHERE batch_id=:id AND user_id=:owner",Json.of("id",batchId,"owner",owner));business.retryItem(owner,batchId,failedItem,retry);assertEquals(count,db.count("SELECT COUNT(*) FROM aip_run WHERE batch_id=:id AND user_id=:owner",Json.of("id",batchId,"owner",owner)));assertEquals("partial_success",runs.get(owner,batchId).get("status"),"重复retry幂等键不能再次恢复母状态或派发");
        } finally {cleanup();}
    }
    @Test void realConcurrentBatchCancelAndReceiptDoNotDeadlockOrReviveMother() throws Exception {
        openServices();var receiptThread=Executors.newSingleThreadExecutor(r->new Thread(r,"aipicture-receipt-fixture"));var cancelThread=Executors.newSingleThreadExecutor(r->new Thread(r,"aipicture-cancel-fixture"));var childHeld=new CountDownLatch(1);var motherHeld=new CountDownLatch(1);var interceptChild=new AtomicBoolean(true);var interceptMother=new AtomicBoolean(true);
        try {
            var inputs=new ArrayList<String>();for(int color:List.of(0xff123455,0xff567899)) {var asset=assets.upload(owner,png(color));String id=Json.string(asset.get("id"));assetIds.add(id);inputs.add(id);}var request=metadata(null);request.putAll(Json.of("asset_ids",inputs,"operations",List.of(Json.of("tool","adjust_image","params",Json.of("brightness",0.1)))));var batch=reservedBatch(request);String batchId=Json.string(batch.get("id"));batchIds.add(batchId);runIds.add(batchId);var items=Json.list(batch.get("items"));for(Object item:items) runIds.add(Json.string(Json.map(item).get("run_id")));String child=Json.string(Json.map(items.get(0)).get("run_id"));var claim=controlledClaim(child);assertNotNull(claim);var receipt=Json.of("run_id",child,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"),"status","failed","error_code","CONTROLLED_FAILURE","message","并发协议检查，不调用模型","result",Map.of());
            var watched=spy(db);var deadlocks=new AtomicInteger();
            doAnswer(call->{try {return call.callRealMethod();} catch(DataAccessException failure) {for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql&&sql.getErrorCode()==1213) {deadlocks.incrementAndGet();break;}throw failure;}}).when(watched).query(anyString(),anyMap());
            doAnswer(call->{Object value=call.callRealMethod();if(Thread.currentThread().getName().equals("aipicture-receipt-fixture")&&child.equals(Json.map(call.getArgument(1)).get("id"))&&interceptChild.compareAndSet(true,false)) {childHeld.countDown();assertTrue(motherHeld.await(5,TimeUnit.SECONDS),"测试应先确认取消事务已锁住母任务");}return value;}).when(watched).one(eq("SELECT * FROM aip_run WHERE id=:id FOR UPDATE"),anyMap());
            doAnswer(call->{Object value=call.callRealMethod();if(Thread.currentThread().getName().equals("aipicture-cancel-fixture")&&batchId.equals(call.getArgument(1))&&Boolean.TRUE.equals(call.getArgument(3))&&interceptMother.compareAndSet(true,false)) {motherHeld.countDown();assertTrue(childHeld.await(5,TimeUnit.SECONDS),"测试应先确认回执事务已锁住子任务");}return value;}).when(watched).owned(eq("aip_run"),eq(batchId),eq(owner),eq(true));
            var storage=new AiPictureStorage(props,new AiPictureWorkerClient(props));var concurrentRuns=new RunService(watched,sessions,assets,new ToolRegistry(props),storage,props,tx);var received=receiptThread.submit(()->concurrentRuns.receipt(receipt));var cancelled=cancelThread.submit(()->concurrentRuns.cancel(owner,batchId));var cancelledResult=cancelled.get(15,TimeUnit.SECONDS);var receivedResult=received.get(15,TimeUnit.SECONDS);assertTrue(deadlocks.get()>0,"实际MySQL死锁必须已触发，不能用串行通过冒充并发恢复");System.out.println("CONTROLLED_MYSQL_DEADLOCKS="+deadlocks.get());assertEquals("cancelled",cancelledResult.get("status"));assertTrue(Set.of("failed","cancelled").contains(Json.string(receivedResult.get("status"))));assertEquals("cancelled",runs.get(owner,batchId).get("status"));assertTrue(Json.list(business.batch(owner,batchId).get("items")).stream().noneMatch(item->Set.of("queued","running").contains(Json.string(Json.map(item).get("status")))));
        } finally {childHeld.countDown();motherHeld.countDown();receiptThread.shutdownNow();cancelThread.shutdownNow();assertTrue(receiptThread.awaitTermination(20,TimeUnit.SECONDS));assertTrue(cancelThread.awaitTermination(20,TimeUnit.SECONDS));cleanup();}
    }
    @Test void realBatchExportRetainsInputRatioAndFormatOrderAfterOutOfOrderCompletion() throws Exception {
        openServices();
        try {
            var inputs=new ArrayList<String>();
            for(int color:List.of(0xff334455,0xff556677,0xff778899)) {var asset=assets.upload(owner,png(color));String id=Json.string(asset.get("id"));assetIds.add(id);inputs.add(id);}
            inputs.sort(Comparator.reverseOrder());
            var request=metadata(null);request.putAll(Json.of("asset_ids",inputs,"operations",List.of(Json.of("tool","prepare_delivery_sizes","params",Json.of("ratios",List.of("1:1","4:5")))),"formats",List.of("png","jpg")));
            var batch=reservedBatch(request);String batchId=Json.string(batch.get("id"));batchIds.add(batchId);runIds.add(batchId);
            var items=Json.list(batch.get("items")).stream().map(Json::map).toList();for(var item:items) runIds.add(Json.string(item.get("run_id")));
            assertEquals(inputs,items.stream().map(item->Json.string(item.get("source_asset_id"))).toList());
            var outputByPosition=new HashMap<Integer,List<String>>();
            // 第二来源先完成，中间来源失败；顺序由正式输入决定，而非完成时间、UUID或查询选用的索引。
            for(int position:List.of(2,0)) {
                String child=Json.string(items.get(position).get("run_id"));var claim=controlledClaim(child);assertNotNull(claim);var lease=Json.of("run_id",child,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));var outputs=new ArrayList<String>();
                for(int height:List.of(40,50)) for(String format:List.of("png","jpg")) {
                    String id=Json.id();assetIds.add(id);outputs.add(id);var image=new BufferedImage(40,height,BufferedImage.TYPE_INT_RGB);for(int y=0;y<height;y++) for(int x=0;x<40;x++) image.setRGB(x,y,0xff224466+position);var encoded=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,encoded));
                    var artifact=Json.copy(lease);artifact.putAll(Json.of("id",id,"content_base64",Base64.getEncoder().encodeToString(encoded.toByteArray()),"kind","export","source","tool","format",format));var asset=runs.uploadArtifact(artifact);
                    assertEquals(format,asset.get("format"));assertEquals(40,Json.number(asset.get("width")));assertEquals(height,Json.number(asset.get("height")));
                }
                outputByPosition.put(position,outputs);var receipt=Json.copy(lease);receipt.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",outputs),"provider",Json.of("mode","controlled-protocol-test")));runs.receipt(receipt);
            }
            controlledFailure(Json.string(items.get(1).get("run_id")));assertEquals("partial_success",business.batch(owner,batchId).get("status"));
            var expected=new ArrayList<String>();expected.addAll(outputByPosition.get(0));expected.addAll(outputByPosition.get(2));
            var exported=tx.execute(status->{var run=business.exportBatch(owner,batchId,metadata(null));reserveDispatch(Json.string(run.get("id")));return run;});trackRun(exported);String exportRun=Json.string(exported.get("id"));
            assertEquals(expected,Json.list(Json.map(exported.get("params")).get("asset_ids")),"冻结ZIP选择顺序必须与详情中成功来源×比例×格式一致");
            var claim=controlledClaim(exportRun);assertNotNull(claim);assertNull(claim.get("session_id"));assertNull(claim.get("document"));assertEquals(expected,Json.list(Json.map(claim.get("params")).get("asset_ids")));assertEquals(new HashSet<>(expected),Json.list(claim.get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).collect(java.util.stream.Collectors.toSet()));
            assertEquals(8,expected.size());assertTrue(expected.stream().allMatch(id->"ready".equals(assets.record(owner,id).get("status"))));assertEquals(List.of(),Json.list(Json.map(Json.list(business.batch(owner,batchId).get("items")).get(1)).get("output_ids")),"失败来源不得进入成功项清单");
            runs.cancel(owner,exportRun);
        } finally {cleanup();}
    }
    @Test void realDefaultEraseQueuesWithAnOwnedFrozenInputWithoutCallingProvider() throws Exception {
        openServices();
        try {
            var input=assets.upload(owner,png(0xff334466));String asset=Json.string(input.get("id"));assetIds.add(asset);
            var create=metadata(null);create.putAll(Json.of("current_asset_id",asset,"title","默认区域消除参数技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            var run=trackRun(reservedSubmit(owner,session,"erase_region",Json.of("layer_id","base"),metadata(version(session))));String id=Json.string(run.get("id"));
            assertEquals("queued",run.get("status"));assertEquals("",Json.map(run.get("params")).get("prompt"));assertEquals(id,sessions.detail(owner,session).get("active_run_id"));
            var claim=controlledClaim(id);assertNotNull(claim);assertEquals("erase_region",claim.get("tool"));assertEquals("",Json.map(claim.get("params")).get("prompt"));assertEquals("base",Json.map(claim.get("params")).get("layer_id"));assertEquals(asset,Json.map(Json.list(Json.map(claim.get("document")).get("layers")).get(0)).get("asset_id"));
            assertEquals(List.of(asset),Json.list(claim.get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).toList());assertEquals(0,Json.number(claim.get("input_version")));assertEquals(0,Json.number(claim.get("input_revision")));assertArrayEquals(assets.download(owner,asset),runs.inputAsset(asset,Json.of("run_id",id,"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"))));
            runs.cancel(owner,id);assertEquals("cancelled",runs.get(owner,id).get("status"));assertNull(sessions.detail(owner,session).get("active_run_id"));
        } finally {cleanup();}
    }
    @Test void realFailedCancelledAndOldAttemptArtifactsStayIsolatedUntilSuccessfulReceipt() throws Exception {
        openServices();
        try {
            var input=assets.upload(owner,png(0xff224466));String original=Json.string(input.get("id"));assetIds.add(original);
            var create=metadata(null);create.putAll(Json.of("current_asset_id",original,"title","产物正式采用隔离技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            var failedRun=trackRun(reservedSubmit(owner,session,"adjust_image",Map.of(),metadata(version(session))));String failed=Json.string(failedRun.get("id"));var failedClaim=controlledClaim(failed);String failedOutput=uploadControlledArtifact(failedClaim,0xff335577);
            assertEquals(failedOutput,runs.uploadArtifact(controlledArtifact(failedClaim,failedOutput,0xff335577)).get("id"),"私有产物重交必须保持ID");
            var diagnostics=Json.of("asset_ids",List.of(failedOutput),"diagnostic","受控回执的已知暂存ID，不猜UUID");var failedReceipt=Json.copy(leaseOf(failedClaim));failedReceipt.putAll(Json.of("status","failed","result",diagnostics,"error_code","CONTROLLED_FAILURE","message","失败产物应隔离"));var failure=runs.receipt(failedReceipt);
            assertEquals("failed",failure.get("status"));assertEquals(diagnostics,failure.get("result"));
            assertAll("失败任务可读而未采用产物不可使用",()->assertTrue(Json.list(failure.get("candidates")).isEmpty()),()->assertIsolatedArtifact(failedOutput));
            var cancelledRun=trackRun(reservedSubmit(owner,session,"adjust_image",Map.of(),metadata(version(session))));String cancelled=Json.string(cancelledRun.get("id"));var cancelledClaim=controlledClaim(cancelled);String cancelledOutput=uploadControlledArtifact(cancelledClaim,0xff557799);runs.cancel(owner,cancelled);assertEquals("cancelled",runs.get(owner,cancelled).get("status"));assertIsolatedArtifact(cancelledOutput);
            var recovering=trackRun(reservedSubmit(owner,session,"adjust_image",Map.of(),metadata(version(session))));String recoveringId=Json.string(recovering.get("id"));var priorClaim=controlledClaim(recoveringId);String priorOutput=uploadControlledArtifact(priorClaim,0xff7799bb);
            tx.execute(status->{db.execute("UPDATE aip_run SET lease_expires_at=:expired WHERE id=:id AND user_id=:owner",Json.of("expired",System.currentTimeMillis()-1,"id",recoveringId,"owner",owner));runs.claimForRun("integration-recovery",recoveringId);reserveDispatch(recoveringId);return null;});
            var currentClaim=controlledClaim(recoveringId);assertNotNull(currentClaim);assertTrue(Json.number(currentClaim.get("attempt"))>Json.number(priorClaim.get("attempt")));assertIsolatedArtifact(priorOutput);String accepted=uploadControlledArtifact(currentClaim,0xff99bbdd);
            assertEquals(accepted,runs.uploadArtifact(controlledArtifact(currentClaim,accepted,0xff99bbdd)).get("id"));var receipt=Json.copy(leaseOf(currentClaim));receipt.putAll(Json.of("status","succeeded","result",Json.of("asset_ids",List.of(accepted)),"provider",Json.of("mode","controlled-protocol-test")));runs.receipt(receipt);
            assertEquals("ready",assets.record(owner,accepted).get("status"));assertArrayEquals(png(0xff99bbdd),assets.download(owner,accepted));assertIsolatedArtifact(priorOutput);
            assertEquals(accepted,Json.map(Json.list(runs.get(owner,recoveringId).get("candidates")).get(0)).get("id"));assertEquals(List.of(accepted),Json.list(runs.freeze(owner,null,Json.of("reference_asset_ids",List.of(accepted)),"generate_image").get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).toList());
            var formal=metadata(null);formal.putAll(Json.of("current_asset_id",accepted,"title","正式产物可采用"));String adopted=Json.string(sessions.create(owner,formal).get("id"));sessionIds.add(adopted);assertEquals(accepted,sessions.detail(owner,adopted).get("current_asset_id"));
            var batchRequest=metadata(null);batchRequest.putAll(Json.of("asset_ids",List.of(accepted),"operations",List.of(Json.of("tool","adjust_image","params",Map.of()))));var batch=reservedBatch(batchRequest);String batchId=Json.string(batch.get("id"));batchIds.add(batchId);runIds.add(batchId);for(Object item:Json.list(batch.get("items"))) runIds.add(Json.string(Json.map(item).get("run_id")));assertEquals(accepted,Json.map(Json.map(Json.list(batch.get("items")).get(0)).get("source")).get("id"));runs.cancel(owner,batchId);
            var evaluated=tx.execute(status->{var request=metadata(null);request.put("manifest",List.of(Json.of("id","accepted-image","task","encode","input_asset_id",accepted)));var created=business.createEvaluation(owner,request);evaluationIds.add(Json.string(created.get("id")));String id=Json.string(created.get("run_id"));runIds.add(id);reserveDispatch(id);return created;});var evaluationClaim=controlledClaim(Json.string(evaluated.get("run_id")));assertNotNull(evaluationClaim);assertEquals(List.of(accepted),Json.list(evaluationClaim.get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).toList());runs.cancel(owner,Json.string(evaluated.get("run_id")));
            assertThrows(AiPictureException.class,()->assets.get(owner+1,accepted));assertThrows(AiPictureException.class,()->runs.uploadArtifact(controlledArtifact(priorClaim,priorOutput,0xff7799bb)));
        } finally {cleanup();}
    }
    Map<String,Object> leaseOf(Map<String,Object> claim) {return Json.of("run_id",claim.get("run_id"),"attempt",claim.get("attempt"),"lease_token",claim.get("lease_token"));}
    Map<String,Object> controlledArtifact(Map<String,Object> claim,String id,int color) throws Exception {var artifact=leaseOf(claim);artifact.putAll(Json.of("id",id,"content_base64",Base64.getEncoder().encodeToString(png(color)),"kind","generated","source","tool","format","png"));return artifact;}
    String uploadControlledArtifact(Map<String,Object> claim,int color) throws Exception {String output=Json.id();assetIds.add(output);runs.uploadArtifact(controlledArtifact(claim,output,color));return output;}
    void assertIsolatedArtifact(String output) {
        assertAll("仅ready产物可成为正式输入",()->assertThrows(AiPictureException.class,()->assets.get(owner,output)),()->assertThrows(AiPictureException.class,()->assets.download(owner,output)),
            ()->assertThrows(AiPictureException.class,()->runs.freeze(owner,null,Json.of("reference_asset_ids",List.of(output)),"generate_image")),
            ()->assertThrows(AiPictureException.class,()->{var create=metadata(null);create.putAll(Json.of("current_asset_id",output,"title","未采用产物拒绝检查"));var created=sessions.create(owner,create);sessionIds.add(Json.string(created.get("id")));}),
            ()->assertThrows(AiPictureException.class,()->{var request=metadata(null);request.putAll(Json.of("asset_ids",List.of(output),"operations",List.of(Json.of("tool","adjust_image","params",Map.of()))));var created=reservedBatch(request);String id=Json.string(created.get("id"));batchIds.add(id);runIds.add(id);for(Object item:Json.list(created.get("items"))) runIds.add(Json.string(Json.map(item).get("run_id")));}),
            ()->assertThrows(AiPictureException.class,()->tx.execute(status->{var request=metadata(null);request.put("manifest",List.of(Json.of("id","isolated-output","task","encode","input_asset_id",output)));var created=business.createEvaluation(owner,request);evaluationIds.add(Json.string(created.get("id")));String run=Json.string(created.get("run_id"));runIds.add(run);reserveDispatch(run);return created;})));
    }
    @Test void realCroppedBaseRestoresItsNativeFrameAndKeepsComplexAssetSnapshots() throws Exception {
        openServices();
        try {
            var a=assets.upload(owner,png(0xff123456));var b=assets.upload(owner,png(0xff654321));String first=Json.string(a.get("id")),other=Json.string(b.get("id"));assetIds.addAll(List.of(first,other));
            var create=metadata(null);create.putAll(Json.of("current_asset_id",first,"asset_ids",List.of(other),"title","裁剪原画幅恢复技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            trackRun(reservedSubmit(owner,session,"crop_canvas",Json.of("ratio","1:1"),metadata(version(session))));var cropped=sessions.detail(owner,session);assertEquals(40,Json.number(Json.map(cropped.get("document")).get("height")));
            var maskAsset=assets.upload(owner,png(0xffeeeeee,40,40));String mask=Json.string(maskAsset.get("id"));assetIds.add(mask);db.execute("UPDATE aip_asset SET kind='mask' WHERE id=:id AND user_id=:owner",Json.of("id",mask,"owner",owner));
            db.execute("INSERT INTO aip_selection(id,session_id,user_id,revision,mask_asset_id,markers,strokes,expires_at,state) VALUES(:id,:session,:owner,:revision,:mask,'[]','[]',:expires,'ready')",Json.of("id",Json.id(),"session",session,"owner",owner,"revision",cropped.get("revision"),"mask",mask,"expires",System.currentTimeMillis()+60000));
            var sameRequest=metadata(version(session));sameRequest.put("current_asset_id",first);var same=sessions.patch(owner,session,sameRequest);var sameSelection=sessions.selection(owner,session);
            trackRun(reservedSubmit(owner,session,"crop_canvas",Json.of("ratio","1:1"),metadata(version(session))));
            var switchOther=metadata(version(session));switchOther.put("current_asset_id",other);sessions.patch(owner,session,switchOther);var switchBack=metadata(version(session));switchBack.put("current_asset_id",first);var returned=sessions.patch(owner,session,switchBack);
            assertAll("原裁剪恢复规则：点当前A和A→B→A都恢复原画幅",()->assertEquals(60,Json.number(Json.map(same.get("document")).get("height"))),()->assertNull(sameSelection),()->assertTrue(Json.number(same.get("revision"))>Json.number(cropped.get("revision"))),()->assertEquals(60,Json.number(Json.map(returned.get("document")).get("height"))));
            // 切回A是独立可撤销操作；之后明确裁剪再恢复，同样保留完整快照。
            trackRun(reservedSubmit(owner,session,"crop_canvas",Json.of("ratio","1:1"),metadata(version(session))));var restore=metadata(version(session));restore.put("current_asset_id",first);sessions.patch(owner,session,restore);sessions.historyMove(owner,session,metadata(version(session)),true);assertEquals(40,Json.number(Json.map(sessions.detail(owner,session).get("document")).get("height")));sessions.historyMove(owner,session,metadata(version(session)),false);assertEquals(60,Json.number(Json.map(sessions.detail(owner,session).get("document")).get("height")));
            for(boolean multiple:List.of(true,false)) {
                var complex=Json.copy(DocumentCommandService.documentOf(a));complex.put("height",40);var layers=new ArrayList<>(Json.list(complex.get("layers")));
                if(multiple) {var extra=Json.copy(Json.map(Json.list(DocumentCommandService.documentOf(b).get("layers")).get(0)));extra.put("id","subject-layer");layers.add(extra);}else Json.map(layers.get(0)).put("id","custom-image");complex.put("layers",layers);
                tx.execute(status->{sessions.saveDocument(owner,db.owned("aip_session",session,owner,true),complex,first,"controlled_snapshot",Map.of());return null;});
                var toOther=metadata(version(session));toOther.put("current_asset_id",other);sessions.patch(owner,session,toOther);var back=metadata(version(session));back.put("current_asset_id",first);var recovered=sessions.patch(owner,session,back);assertTrue(Json.equivalent(complex,recovered.get("document")),"多层或非BASE文档不得因尺寸小于原图而重置");
            }
        } finally {cleanup();}
    }
    @Test void realLegacyStagedWallRelationIsHiddenWithoutDeletingFailedDiagnostics() throws Exception {
        openServices();
        try {
            var input=assets.upload(owner,png(0xff223344));String original=Json.string(input.get("id"));assetIds.add(original);var create=metadata(null);create.putAll(Json.of("current_asset_id",original,"title","旧暂存关联隔离技术检查"));String session=Json.string(sessions.create(owner,create).get("id"));sessionIds.add(session);
            var job=trackRun(reservedSubmit(owner,session,"adjust_image",Map.of(),metadata(version(session))));String run=Json.string(job.get("id"));var claim=controlledClaim(run);String output=uploadControlledArtifact(claim,0xff556677);var receipt=leaseOf(claim);receipt.putAll(Json.of("status","failed","result",Json.of("asset_ids",List.of(output)),"message","失败诊断保留"));runs.receipt(receipt);
            // 只在本 fixture 中模拟修前 Session.create 可留下的旧关系，不清理或迁移用户资料。
            var internal=db.owned("aip_asset",output,owner,false);db.execute("INSERT INTO aip_session_asset(session_id,asset_id,user_id,position,latest_document) VALUES(:session,:asset,:owner,1,:document)",Json.of("session",session,"asset",output,"owner",owner,"document",Json.write(DocumentCommandService.documentOf(internal))));
            assertAll("历史坏关系不能绕过正式采用守卫",
                ()->assertEquals(List.of(original),assets.wall(owner,session).stream().map(a->Json.string(a.get("id"))).toList()),
                ()->assertEquals(List.of(original),Json.list(sessions.detail(owner,session).get("assets")).stream().map(a->Json.string(Json.map(a).get("id"))).toList()),
                ()->assertEquals(List.of(original),Json.list(assets.library(owner).stream().filter(g->session.equals(g.get("session_id"))).findFirst().orElseThrow().get("assets")).stream().map(a->Json.string(Json.map(a).get("id"))).toList()));
            assertEquals(1,db.count("SELECT COUNT(*) FROM aip_session_asset WHERE session_id=:session AND asset_id=:asset AND user_id=:owner",Json.of("session",session,"asset",output,"owner",owner)));assertEquals("staged",db.owned("aip_asset",output,owner,false).get("status"));var failure=runs.get(owner,run);assertEquals("failed",failure.get("status"));assertEquals(List.of(output),Json.list(Json.map(failure.get("result")).get("asset_ids")));assertTrue(Json.list(failure.get("candidates")).isEmpty());assertThrows(AiPictureException.class,()->assets.download(owner,output));
        } finally {cleanup();}
    }
    @Test void realLibraryHonorsOriginalLimitAndKeepsOrphansSeparate() throws Exception {
        openServices();var user=new LoginUser();user.setUserId(owner);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        try {
            var first=assets.upload(owner,png(0xff115599));assetIds.add(Json.string(first.get("id")));
            var second=assets.upload(owner,png(0xff559911));assetIds.add(Json.string(second.get("id")));
            var orphan=assets.upload(owner,png(0xff991155));assetIds.add(Json.string(orphan.get("id")));
            var a=metadata(null);a.putAll(Json.of("current_asset_id",first.get("id"),"title","查询数量技术检查-"+Json.id()));var sa=sessions.create(owner,a);sessionIds.add(Json.string(sa.get("id")));
            var b=metadata(null);b.putAll(Json.of("current_asset_id",second.get("id"),"title","查询数量技术检查-"+Json.id()));var sb=sessions.create(owner,b);sessionIds.add(Json.string(sb.get("id")));
            long time=System.currentTimeMillis()+60000000;
            db.execute("UPDATE aip_session SET updated_at=:time WHERE id=:id AND user_id=:owner",Json.of("id",sa.get("id"),"owner",owner,"time",time+1));
            db.execute("UPDATE aip_session SET updated_at=:time WHERE id=:id AND user_id=:owner",Json.of("id",sb.get("id"),"owner",owner,"time",time));
            db.execute("UPDATE aip_asset SET created_at=:time WHERE id=:id AND user_id=:owner",Json.of("id",orphan.get("id"),"owner",owner,"time",time));
            var controller=new com.ruoyi.aipicture.controller.AiPictureController(assets,sessions,runs,mock(PlanService.class),business,new ToolRegistry(props),mock(AiPictureWorkerClient.class));
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.ruoyi.aipicture.controller.AiPictureErrors()).build();
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/aipicture/assets/library").param("limit","1")).andReturn().getResponse();
            assertEquals(200,response.getStatus());var groups=Json.list(Json.map(response.getContentAsString()).get("data"));
            assertEquals(2,groups.size(),"原limit分别限制会话组与孤立素材，不忽略为200");
            assertEquals(sa.get("id"),Json.map(groups.get(0)).get("session_id"));assertNull(Json.map(groups.get(1)).get("session_id"));
            assertEquals(1,Json.list(Json.map(groups.get(1)).get("assets")).size());assertEquals(orphan.get("id"),Json.map(Json.map(groups.get(1)).get("cover")).get("id"));
            for(String invalid:List.of("0","201")) assertEquals(422,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/aipicture/assets/library").param("limit",invalid)).andReturn().getResponse().getStatus(),"源接口拒绝越界数量");
        } finally {SecurityContextHolder.clearContext();cleanup();}
    }
    @Test void realSessionListPreservesOriginalHistorySequenceAfterEdit() throws Exception {
        openServices();
        try {
            var asset=assets.upload(owner,png(0xff335577));assetIds.add(Json.string(asset.get("id")));var create=metadata(null);create.putAll(Json.of("current_asset_id",asset.get("id"),"title","历史序号技术检查-"+Json.id()));
            var session=sessions.create(owner,create);String id=Json.string(session.get("id"));sessionIds.add(id);
            trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",2),metadata(0L)));
            db.execute("UPDATE aip_session SET updated_at=:time WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner,"time",System.currentTimeMillis()+60000000));
            var listed=sessions.list(owner,0,1);assertEquals(1,listed.size());assertEquals(id,listed.get(0).get("id"));
            assertEquals(2L,listed.get(0).get("history_seq"),"原会话列表必填历史序号，应与真实详情一致");
            assertEquals(sessions.detail(owner,id).get("history_seq"),listed.get(0).get("history_seq"));
        } finally {cleanup();}
    }
    @Test void realInitialHistoryMatchesOriginalCreationAndNewestFirst() throws Exception {
        openServices();
        try {
            var asset=assets.upload(owner,png(0xff446688));assetIds.add(Json.string(asset.get("id")));var create=metadata(null);create.putAll(Json.of("current_asset_id",asset.get("id"),"title","原创建历史技术检查-"+Json.id()));
            var initial=sessions.create(owner,create);String id=Json.string(initial.get("id"));sessionIds.add(id);
            assertEquals(1L,initial.get("history_seq"),"原创建会话本身就是首条非撤销历史");assertEquals(false,initial.get("can_undo"));assertEquals(false,initial.get("can_redo"));assertNull(initial.get("previous_document"));
            var start=sessions.history(owner,id);assertEquals(1,start.size());assertEquals("create_session",start.get(0).get("action"));assertTrue(Json.equivalent(initial.get("document"),Json.map(start.get(0).get("result")).get("document")));
            assertEquals(409,assertThrows(AiPictureException.class,()->sessions.historyMove(owner,id,metadata(version(id)),true)).status());
            trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",3),metadata(version(id))));trackRun(runs.submit(owner,id,"move_layer",Json.of("dx",2),metadata(version(id))));
            assertEquals(List.of(3L,2L,1L),sessions.history(owner,id).stream().map(row->Json.number(row.get("seq"))).toList(),"保留创建记录，最近操作在上");
            assertEquals("create_session",sessions.history(owner,id).get(2).get("action"));sessions.historyMove(owner,id,metadata(version(id)),true);assertEquals(3,x(id));sessions.historyMove(owner,id,metadata(version(id)),true);assertEquals(0,x(id));assertEquals(false,sessions.detail(owner,id).get("can_undo"));assertNull(sessions.detail(owner,id).get("previous_document"));
            sessions.historyMove(owner,id,metadata(version(id)),false);assertEquals(3,x(id));sessions.historyMove(owner,id,metadata(version(id)),false);assertEquals(5,x(id));assertEquals(3,sessions.history(owner,id).size());
            assertEquals(id,sessions.create(owner,create).get("id"));assertEquals(3,sessions.history(owner,id).size(),"重交创建命令不重复写历史");
        } finally {cleanup();}
    }
    @Test void realAssessmentFreezesOwnedFormalOutputAndReviewWithoutResubmittingSource() throws Exception {
        openServices();
        try {
            var source=trackRun(reservedSubmit(owner,null,"generate_image",Json.of("prompt","受控来源协议检查，不调用供应商","ratio","1:1","count",1),metadata(null)));String sourceId=Json.string(source.get("id"));controlledSuccess(sourceId);
            var formal=runs.get(owner,sourceId);String output=Json.uuid(Json.list(Json.map(formal.get("result")).get("asset_ids")).get(0));
            var sample=Json.of("id","正式输出与人工意见","task","assess_result","suite","generation","source_run_id",sourceId,"output_asset_id",output,"thresholds",Json.of("size",1),"human_review",Json.of("status","pending","notes","等待真实内容质量核对，不计业务通过"));var request=metadata(null);request.put("manifest",List.of(sample));
            var evaluation=reservedEvaluation(request);String evaluationId=Json.string(evaluation.get("id")),evaluationRun=Json.string(evaluation.get("run_id"));evaluationIds.add(evaluationId);runIds.add(evaluationRun);
            var saved=Json.map(Json.list(evaluation.get("manifest")).get(0));var assessment=Json.map(saved.get("assessment"));assertEquals("generate_image",assessment.get("source_tool"));assertEquals("controlled-protocol-test",Json.map(assessment.get("source_provider")).get("mode"));assertEquals("pending",Json.map(saved.get("human_review")).get("status"));assertFalse(sample.containsKey("assessment"));
            assertEquals(evaluationId,reservedEvaluation(request).get("id"));assertEquals(1,db.count("SELECT COUNT(*) FROM aip_evaluation WHERE run_id=:id AND user_id=:owner",Json.of("id",evaluationRun,"owner",owner)));
            assertEquals(1,Json.number(runs.get(owner,sourceId).get("attempt")),"评测不增加原任务收费尝试次数");assertEquals("succeeded",runs.get(owner,sourceId).get("status"));
            var claim=controlledClaim(evaluationRun);assertEquals("evaluation",claim.get("tool"));assertEquals(List.of(output),Json.list(claim.get("assets")).stream().map(value->Json.map(value).get("id")).toList(),"Worker仅获得本人正式输出引用");
            assertEquals(404,assertThrows(AiPictureException.class,()->business.createEvaluation(owner+1,request)).status());var self=Json.copy(sample);self.put("expect_asset_id",output);var invalid=metadata(null);invalid.put("manifest",List.of(self));assertEquals(422,assertThrows(AiPictureException.class,()->business.createEvaluation(owner,invalid)).status());
            assertNull(business.evaluation(owner,evaluationId).get("metrics"),"未执行质量评分不伪造成功指标");
        } finally {cleanup();}
    }
    void cleanup() {
        if(db==null) return;
        tx.execute(status->{
            for(String id:sessionIds) for(String table:List.of("aip_selection","aip_history","aip_session_asset")) db.execute("DELETE FROM "+table+" WHERE session_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
            for(String id:evaluationIds) db.execute("DELETE FROM aip_evaluation WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
            for(String id:exportIds) db.execute("DELETE FROM aip_export WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
            for(String id:planIds) {db.execute("DELETE FROM aip_plan_step WHERE turn_id=:id",Json.of("id",id));db.execute("DELETE FROM aip_agent_turn WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));}
            for(String id:batchIds) {var items=db.query("SELECT id FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));for(var item:items) db.execute("DELETE FROM aip_command WHERE command=:command AND user_id=:owner",Json.of("command","batch:retry:"+id+":"+item.get("id"),"owner",owner));db.execute("DELETE FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));db.execute("DELETE FROM aip_batch WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));}
            for(String id:runIds) {db.execute("DELETE FROM aip_run_event WHERE run_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));db.execute("DELETE FROM aip_dispatch WHERE run_id=:id",Json.of("id",id));db.execute("DELETE FROM aip_run WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));}
            for(String id:sessionIds) db.execute("DELETE FROM aip_session WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
            for(String id:assetIds) db.execute("DELETE FROM aip_asset WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
            for(String key:keys) db.execute("DELETE FROM aip_command WHERE idempotency_key=:key AND user_id=:owner",Json.of("key",key,"owner",owner));return null;
        });
    }
}
