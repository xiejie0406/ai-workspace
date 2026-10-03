package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;

class WorkerCommitTest {
    AiPictureRepository db;RunService runs;SessionService sessions;AssetService assets;String id,lease;
    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        sessions=mock(SessionService.class);assets=mock(AssetService.class);runs=new RunService(db,sessions,assets,new ToolRegistry(new AiPictureProperties()),mock(AiPictureStorage.class),new AiPictureProperties(),new TransactionTemplate(manager));id=Json.id();lease=Json.id();
    }
    Map<String,Object> run(String state) {return Json.of("id",id,"user_id",11,"status",state,"tool","adjust_image","attempt",2,"lease_token",lease,"lease_expires_at",System.currentTimeMillis()+60000,"result",Json.of("asset_ids",List.of()),"params",Map.of(),"progress",100);}
    @Test void failedRunKeepsItsDiagnosticsWithoutPromotingStagedCandidates() throws Exception {
        String output=Json.id();var failed=run("failed");failed.put("message","受控失败");failed.put("result",Json.of("asset_ids",List.of(output),"diagnostic","保留正式失败结果"));
        when(db.owned("aip_run",id,11,false)).thenReturn(failed);when(db.owned("aip_asset",output,11,false)).thenReturn(Json.of("id",output,"status","staged","kind","generated","format","png"));
        var guardedAssets=new AssetService(db,mock(AiPictureStorage.class));var guardedRuns=new RunService(db,sessions,guardedAssets,new ToolRegistry(new AiPictureProperties()),mock(AiPictureStorage.class),new AiPictureProperties(),new TransactionTemplate(mock(PlatformTransactionManager.class)));
        var result=guardedRuns.get(11,id);assertEquals("failed",result.get("status"));assertEquals("受控失败",result.get("error"));assertEquals(failed.get("result"),result.get("result"));assertTrue(Json.list(result.get("candidates")).isEmpty(),"未采用产物不能成为可点击候选");
    }
    @Test void duplicateTerminalReceiptReturnsOriginalResultWithoutAnyNewWrite() {
        var original=run("succeeded");when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(original);when(db.owned("aip_run",id,11,false)).thenReturn(original);
        var result=runs.receipt(Json.of("run_id",id,"attempt",2,"lease_token",lease,"status","failed","result",Json.of("unexpected","tamper")));
        assertEquals("succeeded",result.get("status"));assertFalse(Json.map(result.get("result")).containsKey("unexpected"));verify(db,never()).execute(anyString(),anyMap());
    }
    @Test void oldAttemptCannotApplyOrUploadResults() {when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(run("running"));var e=assertThrows(AiPictureException.class,()->runs.receipt(Json.of("run_id",id,"attempt",1,"lease_token",lease,"status","succeeded")));assertEquals("STALE_ATTEMPT",e.errorCode());verify(db,never()).execute(anyString(),anyMap());}
    @Test void leaseExpiryCannotApplyLateResults() {var row=run("running");row.put("lease_expires_at",System.currentTimeMillis()-1);when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(row);assertThrows(AiPictureException.class,()->runs.receipt(Json.of("run_id",id,"attempt",2,"lease_token",lease,"status","succeeded")));verify(db,never()).execute(anyString(),anyMap());}
    @Test void lateHeartbeatCannotResurrectExpiredLease() {var row=run("running");row.put("lease_expires_at",System.currentTimeMillis()-1);when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(row);var error=assertThrows(AiPictureException.class,()->runs.heartbeat(Json.of("run_id",id,"attempt",2,"lease_token",lease,"progress",80)));assertEquals("LEASE_EXPIRED",error.errorCode());verify(db,never()).execute(anyString(),anyMap());}
    @Test void evaluationReceiptUsesNewlySavedProviderInsteadOfPriorRunMetadata() {
        var row=run("running");row.put("tool","evaluation");row.put("provider",Json.of("runtime_version","old"));when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(row);when(db.owned("aip_run",id,11,false)).thenReturn(row);var provider=Json.of("algorithm","pillow","runtime_version","2026.10-r2","offline_test",false);
        runs.receipt(Json.of("run_id",id,"attempt",2,"lease_token",lease,"status","succeeded","provider",provider,"result",Json.of("metrics",Json.of("passed",1,"total",1))));verify(db).execute(startsWith("UPDATE aip_evaluation SET"),argThat(params->provider.equals(Json.map(params.get("provider")))));
    }
    @Test void onlyHeightMismatchAlsoRejectsMask() {assertThrows(AiPictureException.class,()->RunService.validateMaskDimensions(Json.of("width",400,"height",299),Json.of("width",400,"height",300)));assertThrows(AiPictureException.class,()->RunService.validateMaskDimensions(Json.of("width",399,"height",300),Json.of("width",400,"height",300)));assertDoesNotThrow(()->RunService.validateMaskDimensions(Json.of("width",400,"height",300),Json.of("width",400L,"height",300L)));}
    @Test void knownGenerationTaskRequeuesSameRunWithoutReleasingSessionOrCallingProvider() {
        var row=run("running");row.put("tool","generate_image");row.put("session_id",Json.id());when(db.one(contains("SELECT * FROM aip_run WHERE id="),anyMap())).thenReturn(row);when(db.owned("aip_run",id,11,false)).thenReturn(row);
        runs.receipt(Json.of("run_id",id,"attempt",2,"lease_token",lease,"status","external_result_unknown","provider_task_id","original-provider-task","error_code","PROVIDER_TASK_PENDING"));
        verify(db).execute(startsWith("UPDATE aip_run SET status='queued'"),argThat(p->"original-provider-task".equals(p.get("provider"))));verify(db).execute(startsWith("UPDATE aip_dispatch SET status='pending'"),anyMap());verify(db,never()).execute(contains("active_run_id=NULL"),anyMap());
    }
    @Test void exportFreezesAll200SuccessesAndCurrentCanvasWithoutUnusedMask() {
        var ids=new ArrayList<String>();for(int i=0;i<200;i++) ids.add(Json.id());
        when(assets.record(eq(11L),anyString())).thenAnswer(invocation->Json.of("id",invocation.getArgument(1),"width",40,"height",60,"format","png","sha256","hash"));
        var current=ids.get(0);var session=Json.of("id",Json.id(),"current_asset_id",current,"revision",7,"document",Json.of("width",40,"height",60,"layers",List.of(Json.of("asset_id",current))));var params=Json.of("asset_ids",ids,"format","zip");
        var frozen=runs.freeze(11,session,params,"export");assertEquals(200,Json.list(frozen.get("assets")).size());assertEquals(current,params.get("current_asset_id"));assertFalse(params.containsKey("mask_asset_id"));verify(sessions,never()).selection(anyLong(),anyString());
        assertThrows(AiPictureException.class,()->runs.freeze(11,null,Json.of("asset_ids",ids),"adjust_image"));ids.add(Json.id());assertThrows(AiPictureException.class,()->runs.freeze(11,null,Json.of("asset_ids",ids),"export"));
    }
    @Test void sessionZipFreezes200SelectedOutputsWithoutUnselectedCanvasAssets() {
        var selected=new ArrayList<String>();for(int i=0;i<200;i++) selected.add(Json.id());String current=Json.id();
        when(assets.record(eq(11L),anyString())).thenAnswer(invocation->Json.of("id",invocation.getArgument(1),"width",40,"height",60,"format","png","sha256","hash"));
        var session=Json.of("id",Json.id(),"current_asset_id",current,"revision",3,"document",DocumentCommandService.documentOf(Json.of("id",current,"width",40,"height",60)));var params=Json.of("asset_ids",selected,"format","zip");var frozen=runs.freeze(11,session,params,"export");
        assertEquals(200,Json.list(frozen.get("assets")).size());assertNull(frozen.get("document"),"纯非当前资产ZIP无需冻结画布");verify(assets,never()).record(11,current);assertEquals(selected,Json.list(params.get("asset_ids")));
    }
    @Test void sessionZipIncludes200SelectionsAnd64NecessaryCanvasDependencies() {
        var selected=new ArrayList<String>();for(int i=0;i<200;i++) selected.add(Json.id());String current=selected.get(0);var layers=new ArrayList<Object>();
        for(int i=0;i<64;i++) {var layer=Json.map(Json.list(DocumentCommandService.documentOf(Json.of("id",Json.id(),"width",40,"height",60)).get("layers")).get(0));layer.put("id","dependency-"+i);layers.add(layer);}
        when(assets.record(eq(11L),anyString())).thenAnswer(invocation->Json.of("id",invocation.getArgument(1),"width",40,"height",60,"format","png","sha256","hash"));
        var document=Json.of("width",40,"height",60,"layers",layers);var session=Json.of("id",Json.id(),"current_asset_id",current,"revision",3,"document",document);var params=Json.of("asset_ids",selected,"format","zip");var frozen=runs.freeze(11,session,params,"export");
        assertEquals(264,Json.list(frozen.get("assets")).size());assertEquals(document,frozen.get("document"));assertEquals(200,Json.list(params.get("asset_ids")).size());assertEquals(current,params.get("current_asset_id"));
    }
    @Test void canvasPngUsesOnlyDocumentReferencesAndIgnoresMaterialSelections() {
        var selected=new ArrayList<String>();for(int i=0;i<200;i++) selected.add(Json.id());String current=Json.id();when(assets.record(eq(11L),anyString())).thenAnswer(invocation->Json.of("id",invocation.getArgument(1),"width",40,"height",60,"format","png","sha256","hash"));var document=DocumentCommandService.documentOf(Json.of("id",current,"width",40,"height",60));
        var session=Json.of("id",Json.id(),"current_asset_id",current,"revision",3,"document",document);var params=Json.of("asset_ids",selected,"format","png");var frozen=runs.freeze(11,session,params,"export");assertEquals(1,Json.list(frozen.get("assets")).size());assertTrue(Json.list(params.get("asset_ids")).isEmpty());assertEquals(document,frozen.get("document"));for(String unused:selected) verify(assets,never()).record(11,unused);
    }
    @Test void selectedCanvasAssetsDoNotConsumeThe64AdditionalDependencyBudget() {
        var selected=new ArrayList<String>();for(int i=0;i<200;i++) selected.add(Json.id());var layers=new ArrayList<Object>();var references=new ArrayList<String>(selected);for(int i=0;i<64;i++) references.add(Json.id());for(int i=0;i<references.size();i++) {var layer=Json.map(Json.list(DocumentCommandService.documentOf(Json.of("id",references.get(i),"width",40,"height",60)).get("layers")).get(0));layer.put("id","dependency-"+i);layers.add(layer);}when(assets.record(eq(11L),anyString())).thenAnswer(invocation->Json.of("id",invocation.getArgument(1),"width",40,"height",60,"format","png","sha256","hash"));
        var document=Json.of("width",40,"height",60,"layers",layers);var session=Json.of("id",Json.id(),"current_asset_id",selected.get(0),"revision",3,"document",document);assertEquals(264,Json.list(runs.freeze(11,session,Json.of("asset_ids",selected,"format","zip"),"export").get("assets")).size());var extra=Json.map(Json.list(DocumentCommandService.documentOf(Json.of("id",Json.id(),"width",40,"height",60)).get("layers")).get(0));extra.put("id","dependency-265");layers.add(extra);assertThrows(AiPictureException.class,()->runs.freeze(11,session,Json.of("asset_ids",selected,"format","zip"),"export"));
    }

    @Test void sessionExportDefaultsToOwnedMaterialsInWallOrderThroughRealFreeze() throws Exception {
        var fixture=new SessionExportFixture(true);
        var result=fixture.business.exportSession(fixture.owner,fixture.sessionId,fixture.metadata());
        var params=Json.map(result.get("params"));var frozen=Json.map(fixture.registered.get("input_snapshot"));
        assertEquals("queued",result.get("status"));
        assertEquals(List.of(fixture.delivery,fixture.marketing),Json.list(params.get("asset_ids")),"默认只选投放/营销，顺序沿原图片墙");
        assertEquals(List.of(fixture.delivery,fixture.marketing),frozenIds(frozen));
        assertEquals(List.of("export","marketing"),Json.list(frozen.get("assets")).stream().map(ref->Json.map(ref).get("kind")).toList());
        assertNull(frozen.get("document"),"仅导出非当前物料时不冻结无用画布");
        assertEquals("zip",params.get("format"));assertEquals("white",params.get("background_mode"));
        assertEquals("本人完整物料",params.get("title"));assertEquals(fixture.original,params.get("current_asset_id"));
        verify(fixture.runs).freeze(eq(fixture.owner),anyMap(),anyMap(),eq("export"));
        verify(fixture.db,never()).owned("aip_asset",fixture.foreign,fixture.owner,false);
    }

    @Test void sessionExportWithoutMaterialsFreezesTheCompleteCurrentDocument() throws Exception {
        var fixture=new SessionExportFixture(false);var expected=Json.copy(fixture.document);
        var request=fixture.metadata();request.put("backgroundMode","transparent");
        var result=fixture.business.exportSession(fixture.owner,fixture.sessionId,request);
        var params=Json.map(result.get("params"));var frozen=Json.map(fixture.registered.get("input_snapshot"));
        assertTrue(Json.list(params.get("asset_ids")).isEmpty(),"普通生成候选不能成为默认ZIP物料");
        assertEquals("transparent",params.get("background_mode"));assertEquals(fixture.original,params.get("current_asset_id"));
        assertTrue(Json.equivalent(expected,frozen.get("document")),"空物料ZIP必须冻结当前完整图层/顺序/文字/变换");
        assertEquals(List.of(fixture.original,fixture.generated),frozenIds(frozen));
        assertEquals(List.of("original","generated"),Json.list(frozen.get("assets")).stream().map(ref->Json.map(ref).get("kind")).toList());
        // 正式登记的JSON必须保持提交时快照，后续工作区文档不能倒改导出输入。
        DocumentCommandService.resolve(fixture.document,"overlay",false).put("opacity",0.9);
        assertFalse(Json.equivalent(fixture.document,expected));assertTrue(Json.equivalent(expected,frozen.get("document")));
        assertEquals(4,Json.number(params.get("revision")));assertEquals(9,Json.number(fixture.registered.get("input_version")));
        verify(fixture.runs).freeze(eq(fixture.owner),anyMap(),anyMap(),eq("export"));
    }

    @Test void sessionExportRejectsAnotherOwnersMaterialBeforeRegisteringAnyRun() throws Exception {
        var fixture=new SessionExportFixture(true);var request=fixture.metadata();request.put("asset_ids",List.of(fixture.foreign));
        var error=assertThrows(AiPictureException.class,()->fixture.business.exportSession(fixture.owner,fixture.sessionId,request));
        assertEquals(422,error.status());assertNull(fixture.registered);
        verify(fixture.runs,never()).submit(anyLong(),anyString(),anyString(),anyMap(),anyMap());
        verify(fixture.db,never()).execute(anyString(),anyMap());
    }

    @Test void sessionExportRejectsAnotherOwnerBeforeReadingItsWall() throws Exception {
        var fixture=new SessionExportFixture(true);
        var error=assertThrows(AiPictureException.class,()->fixture.business.exportSession(fixture.owner+1,fixture.sessionId,fixture.metadata()));
        assertEquals(404,error.status());assertNull(fixture.registered);
        verify(fixture.db,never()).query(anyString(),anyMap());verify(fixture.db,never()).execute(anyString(),anyMap());
    }

    private List<String> frozenIds(Map<String,Object> frozen) {
        return Json.list(frozen.get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).toList();
    }

    /** 只提供已有仓储替身的读取行与INSERT参数捕获，不模拟历史SQL或计算算法。 */
    private static final class SessionExportFixture {
        final long owner=37;
        final String sessionId=Json.id(),original=Json.id(),generated=Json.id(),delivery=Json.id(),marketing=Json.id(),foreign=Json.id();
        final AiPictureRepository db=mock(AiPictureRepository.class);
        final Map<String,Map<String,Object>> assetRows=new LinkedHashMap<>();
        final Map<String,Object> document,session;
        final RunService runs;final BatchExportEvaluationService business;
        Map<String,Object> registered;

        SessionExportFixture(boolean materials) throws Exception {
            assetRows.put(original,asset(original,"original",owner));assetRows.put(generated,asset(generated,"generated",owner));
            assetRows.put(delivery,asset(delivery,"export",owner));assetRows.put(marketing,asset(marketing,"marketing",owner));
            assetRows.put(foreign,asset(foreign,"marketing",owner+1));
            document=Json.copy(DocumentCommandService.documentOf(assetRows.get(original)));
            var overlay=Json.copy(Json.map(Json.list(DocumentCommandService.documentOf(assetRows.get(generated)).get("layers")).get(0)));
            overlay.putAll(Json.of("id","overlay","width",120,"height",80,"opacity",0.4,"locked",false,"transform",Json.of("x",17,"y",-8,"scale_x",-1.5,"scale_y",0.75,"rotation",23)));
            var text=Json.copy(overlay);text.putAll(Json.of("id","caption","kind","text","asset_id",null,"name","商品说明","text","保留完整文字","font_size",32,"fill","#223344"));
            Json.list(document.get("layers")).add(overlay);Json.list(document.get("layers")).add(text);
            session=Json.of("id",sessionId,"user_id",owner,"title","本人完整物料","current_asset_id",original,"document",document,"row_version",9,"revision",4);
            var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            var tx=new TransactionTemplate(manager);var properties=new AiPictureProperties();var registry=new ToolRegistry(properties);registry.document(document);
            var storage=mock(AiPictureStorage.class);var assets=new AssetService(db,storage);
            var sessions=new SessionService(db,assets,tx,registry,new CommandIdempotency(db,tx));
            runs=spy(new RunService(db,sessions,assets,registry,storage,properties,tx));
            business=new BatchExportEvaluationService(db,runs,assets,registry,storage,tx,new CommandIdempotency(db,tx));
            when(db.owned(anyString(),anyString(),anyLong(),anyBoolean())).thenAnswer(call->{
                String table=call.getArgument(0),id=call.getArgument(1);long requestedOwner=call.getArgument(2);
                Map<String,Object> row=switch(table) {case "aip_session"->sessionId.equals(id)?session:null;case "aip_asset"->assetRows.get(id);case "aip_run"->registered;default->null;};
                if(row==null||Json.number(row.get("user_id"))!=requestedOwner) throw AiPictureException.missing();return Json.copy(row);
            });
            var wall=materials?List.of(original,generated,delivery,marketing,foreign):List.of(original,generated,foreign);
            when(db.query(anyString(),anyMap())).thenAnswer(call->{
                String sql=call.getArgument(0);Map<String,Object> p=call.getArgument(1);
                assertTrue(sql.contains("FROM aip_session_asset")&&sql.contains("a.user_id=sa.user_id")&&sql.contains("sa.user_id=:owner"),"真实素材墙查询必须带关系/owner过滤");
                assertEquals(sessionId,p.get("session"));long requestedOwner=Json.number(p.get("owner"));
                return wall.stream().map(assetRows::get).filter(row->Json.number(row.get("user_id"))==requestedOwner).map(Json::copy).toList();
            });
            when(db.one(anyString(),anyMap())).thenAnswer(call->{assertTrue(((String)call.getArgument(0)).contains("FROM aip_run WHERE user_id=:owner AND idempotency_key=:key"));return null;});
            when(db.execute(startsWith("INSERT INTO aip_run("),anyMap())).thenAnswer(call->{
                Map<String,Object> p=call.getArgument(1);registered=Json.of("id",p.get("id"),"user_id",p.get("owner"),"session_id",p.get("session"),"tool",p.get("tool"),"status","queued","params",Json.map(p.get("params")),"input_snapshot",Json.map(p.get("snapshot")),"input_version",p.get("version"),"input_revision",p.get("revision"),"result",Map.of(),"event_seq",0);return 1;
            });
        }
        Map<String,Object> metadata() {return Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey","r16:"+Json.id(),"expectedVersion",9);}
        private Map<String,Object> asset(String id,String kind,long assetOwner) {return Json.of("id",id,"user_id",assetOwner,"kind",kind,"source","tool","status","ready","width",320,"height",240,"format","png","sha256",RunService.hash(Json.of("fixture_asset",id)),"created_at",0);}
    }
}
