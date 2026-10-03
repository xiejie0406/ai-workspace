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

/** 原参数缺省必须能穿过正式任务登记，必填字段仍由唯一工具契约拒绝。 */
class PromptSubmissionTest {
    AiPictureRepository db;RunService runs;SessionService sessions;AssetService assets;
    String session,asset,mask;Map<String,Object> inserted;
    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);when(db.one(anyString(),anyMap())).thenReturn(null);sessions=mock(SessionService.class);assets=mock(AssetService.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        runs=new RunService(db,sessions,assets,new ToolRegistry(new AiPictureProperties()),mock(AiPictureStorage.class),new AiPictureProperties(),new TransactionTemplate(manager));
        session=Json.id();asset=Json.id();mask=Json.id();
        var document=DocumentCommandService.documentOf(Json.of("id",asset,"width",400,"height",300));
        when(sessions.lock(11,session,4)).thenReturn(Json.of("id",session,"document",document,"current_asset_id",asset,"row_version",4,"revision",2));
        when(sessions.selection(11,session)).thenReturn(Json.of("mask_asset_id",mask));
        when(assets.record(eq(11L),anyString())).thenAnswer(call->Json.of("id",call.getArgument(1),"width",400,"height",300,"format","png","sha256","controlled-hash","status","ready"));
        when(db.execute(startsWith("INSERT INTO aip_run("),anyMap())).thenAnswer(call->{var values=Json.copy(call.getArgument(1));inserted=Json.of("id",values.get("id"),"user_id",11,"tool",values.get("tool"),"params",Json.map(values.get("params")),"result",Map.of(),"status","queued","event_seq",0,"input_snapshot",Json.map(values.get("snapshot")));return 1;});
        when(db.owned(eq("aip_run"),anyString(),eq(11L),eq(false))).thenAnswer(call->inserted);
    }
    Map<String,Object> metadata() {return Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey",Json.id(),"expectedVersion",4);}
    @Test void defaultErasePromptQueuesAndFreezesTheFormalLayerAndSelection() {
        var run=runs.submit(11,session,"erase_region",Json.of("layer_id","base"),metadata());
        assertEquals("queued",run.get("status"));assertEquals("",Json.map(run.get("params")).get("prompt"));assertEquals("base",Json.map(run.get("params")).get("layer_id"));assertEquals(mask,Json.map(run.get("params")).get("mask_asset_id"));
        var frozen=Json.map(inserted.get("input_snapshot"));assertEquals(Set.of(asset,mask),Json.list(frozen.get("assets")).stream().map(ref->Json.string(Json.map(ref).get("id"))).collect(java.util.stream.Collectors.toSet()));assertNotNull(frozen.get("document"));
        verify(db).execute(startsWith("INSERT INTO aip_dispatch("),anyMap());
    }
    @Test void OptionalPromptFieldsKeepTheSourceEmptyStringSemantics() {
        for(String tool:List.of("erase_region","expand_canvas","prepare_delivery_sizes")) {
            var params=Json.of("prompt","  \t ");if(tool.equals("expand_canvas")) params.put("ratio","1:1");
            var run=runs.submit(11,session,tool,params,metadata());assertEquals("queued",run.get("status"));assertEquals("",Json.map(run.get("params")).get("prompt"));
        }
    }
    @Test void RequiredPromptFieldsRejectBlankBeforeAnyFormalWrite() {
        for(String tool:List.of("generate_image","replace_background","replace_region")) {
            assertThrows(AiPictureException.class,()->runs.submit(11,session,tool,Json.of("prompt","  \t "),metadata()),tool);
            assertThrows(AiPictureException.class,()->runs.submit(11,session,tool,Json.of("prompt",""),metadata()),tool);
        }
        verify(db,never()).execute(anyString(),anyMap());verify(sessions,never()).lock(anyLong(),anyString(),anyLong());
    }
}
