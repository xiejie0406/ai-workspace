package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.tool.*;

class SessionHistoryTest {
    AiPictureRepository db;AssetService assets;SessionService sessions;Map<String,Object> doc;String id,asset;
    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);assets=mock(AssetService.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        sessions=new SessionService(db,assets,new TransactionTemplate(manager),new ToolRegistry(new AiPictureProperties()),mock(CommandIdempotency.class));
        id=Json.id();asset=Json.id();doc=DocumentCommandService.documentOf(Json.of("id",asset,"width",400,"height",300));
    }
    Map<String,Object> state(long seq,long floor) {return Json.of("id",id,"document",doc,"current_asset_id",asset,"row_version",5,"revision",8,"history_seq",seq,"history_floor",floor);}
    @Test void oldVersionCannotModifySession() {when(db.owned("aip_session",id,9,true)).thenReturn(state(0,0));var e=assertThrows(AiPictureException.class,()->sessions.lock(9,id,4));assertEquals(409,e.status());assertEquals(5,e.currentVersion());verify(db,never()).execute(anyString(),anyMap());}
    @Test void noOpNeverTruncatesRedoOrAddsHistory() {var moved=new DocumentCommandService().apply("move_layer",doc,Json.of("dx",0.0));sessions.saveDocument(9,state(3,0),moved,asset,"move_layer",Json.of("dx",0.0));verify(db,never()).execute(anyString(),anyMap());}
    @Test void historyWindowKeeps20AndSnapshotsAreStoredOutsideHistory() {
        when(db.owned("aip_session",id,9,false)).thenReturn(state(20,0));
        var changed=new DocumentCommandService().apply("move_layer",doc,Json.of("dx",2));sessions.saveDocument(9,state(20,0),changed,asset,"move_layer",Json.of("dx",2));
        var sql=ArgumentCaptor.forClass(String.class);var args=ArgumentCaptor.forClass(Map.class);verify(db,atLeastOnce()).execute(sql.capture(),args.capture());
        boolean floor=false,snapshot=false,history=false,revision=false;
        for(int i=0;i<sql.getAllValues().size();i++) {String query=sql.getAllValues().get(i);var p=args.getAllValues().get(i);if(query.contains("history_floor=:floor")) {assertEquals(1L,p.get("floor"));assertEquals(21L,p.get("seq"));floor=true;revision=query.contains("revision=revision+1");}if(query.startsWith("UPDATE aip_session_asset")) snapshot=true;if(query.startsWith("INSERT INTO aip_history")) history=true;}
        assertTrue(floor&&snapshot&&history&&revision);
    }
    @Test void inFlightPixelLeaseRejectsParallelDocumentWrite() {var state=state(0,0);state.put("active_run_id",Json.id());assertThrows(AiPictureException.class,()->sessions.ensureIdle(state));}
}
