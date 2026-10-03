package com.ruoyi.aden.application.collection;

import com.ruoyi.aden.application.error.*;
import com.ruoyi.aden.application.idempotency.AdenRequestFingerprint;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.application.task.*;
import com.ruoyi.aden.application.workspace.*;
import com.ruoyi.aden.domain.task.*;
import com.ruoyi.aden.domain.workspace.*;
import com.ruoyi.aden.infrastructure.persistence.AdenCollectionStore;
import com.ruoyi.aden.infrastructure.storage.AdenCollectionFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.aden.application.collection.AdenCollectionService.*;

/** 无数据库的业务边界测试；记录 SQL 参数并用内存快照模拟持久化回读。 */
class AdenCollectionUiServiceTest {
    static final String WS="11111111-1111-4111-8111-111111111111";
    final ObjectMapper json=new ObjectMapper();
    final List<String> queries=new ArrayList<>();
    final List<Object[]> queryArguments=new ArrayList<>();
    final Map<String,Map<String,Object>> snapshots=new LinkedHashMap<>();
    final Map<String,AdenTask> tasks=new HashMap<>();
    Map<String,Object> item;
    Map<String,Object> export;
    long[] counts={0,0,0}; int countIndex;
    final AdenTaskLedgerRepository ledger=mock(AdenTaskLedgerRepository.class);
    final AdenTaskCasRepository cas=mock(AdenTaskCasRepository.class);
    final AdenWorkspaceRepository members=mock(AdenWorkspaceRepository.class);
    AdenCollectionService service; AdenCollectionFiles files;
    final AdenOperatorPrincipal operator=new AdenOperatorPrincipal(42,"operator",Set.of("aden:collection:read","aden:collection:capture","aden:collection:export"));
    @BeforeEach void setup(@TempDir Path directory){
        Instant now=Instant.parse("2026-10-01T00:00:00Z");
        when(members.findActiveMembership(new AdenWorkspaceId(WS),42)).thenReturn(Optional.of(new AdenWorkspaceMembership(
                new AdenWorkspace(new AdenWorkspaceId(WS),"范围",AdenWorkspaceStatus.ACTIVE,1,42,now,now),42,AdenWorkspaceRole.OPERATOR,AdenWorkspaceMemberStatus.ACTIVE,now)));
        var db=mock(AdenCollectionStore.class,invocation->{
            if(invocation.getMethod().getName().equals("toString"))return "memory-collection";
            String sql=(String)invocation.getRawArguments()[0];Object[] args=(Object[])invocation.getRawArguments()[1];
            queries.add(sql);queryArguments.add(args);
            switch(invocation.getMethod().getName()){
                case "rows":return List.of();
                case "one":
                    if(sql.startsWith("select count"))return map("n",counts[Math.min(countIndex++,counts.length-1)]);
                    if(sql.contains("from aden_collection_export"))return export;
                    if(sql.contains("from aden_collection_snapshot"))return snapshots.get(str(args[1]));
                    if(sql.contains("from aden_collection_item"))return item;
                    return null;
                case "update":
                    if(sql.startsWith("insert into aden_collection_item"))item=map("workspace_id",args[0],"item_id",args[1],"identity_key",args[2],"platform",args[3],"sku",args[4],"title",args[5],"snapshot_id",args[6],"generation",args[7],"created_at",args[8],"updated_at",args[9],"version",1,"deleted",false);
                    if(sql.startsWith("insert into aden_collection_snapshot"))snapshots.put(str(args[1]),map("workspace_id",args[0],"snapshot_id",args[1],"item_id",args[2],"core_task_id",args[3],"request_hash",args[4],"actor_id",args[5],"generation",args[6],"source",args[7],"content_json",args[8],"manifest_json",args[9],"created_at",args[10],"status","SAVING","manifest_version",1));
                    if(sql.startsWith("update aden_collection_snapshot set status="))snapshots.get(str(args[2])).put("status",args[0]);
                    return 1;
                default:return null;
            }
        });
        doAnswer(call->{var task=(AdenTask)call.getArgument(0);tasks.put(task.id().value(),task);return null;}).when(ledger).insertTask(any(),anyString(),any(),anyString());
        doAnswer(call->{var task=(AdenTask)call.getArgument(1);tasks.put(task.id().value(),task);return null;}).when(cas).updateState(any(),any());
        when(ledger.findTaskForUpdate(any(),any())).thenAnswer(call->{var task=tasks.get(((AdenTaskId)call.getArgument(1)).value());return task==null?Optional.empty():Optional.of(new AdenTaskLedgerRepository.StoredTask(task,"{}"));});
        files=new AdenCollectionFiles(directory.toString());
        service=new AdenCollectionService(db,files,new AdenWorkspaceAccessGuard(members,mock(AdenWorkspaceSecurityAuditPort.class)),ledger,cas,json,new AdenRequestFingerprint(json),1073741824);
    }
    @Test void overviewHasConsistentReadBoundaryIndependentCountsAndNoWrites() throws Exception {
        counts=new long[]{12,8,2};var result=service.overview(operator,WS);
        assertEquals(12L,result.get("savedProductCount"));assertEquals(8L,result.get("todayCaptureCount"));assertEquals(2L,result.get("pendingTaskCount"));assertEquals("Asia/Shanghai",result.get("timezone"));
        assertTrue(queries.stream().allMatch(sql->sql.startsWith("select")));assertTrue(queries.stream().anyMatch(sql->sql.contains("s.status='PARTIAL'")&&sql.contains("t.task_state='FAILED'")));
        verifyNoInteractions(ledger,cas);
        var transaction=AdenCollectionService.class.getMethod("overview",AdenOperatorPrincipal.class,String.class).getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());assertEquals(Isolation.REPEATABLE_READ,transaction.isolation());
    }
    @Test void dateFilterUsesInclusiveShanghaiDaysAndRejectsBadRange(){
        service.tasks(operator,WS,"SKU","ALL",1,20,"2026-10-01","2026-10-01");
        Object[] args=queryArguments.get(0);assertEquals(Instant.parse("2026-09-30T16:00:00Z"),((Timestamp)args[5]).toInstant());assertEquals(Instant.parse("2026-10-01T16:00:00Z"),((Timestamp)args[6]).toInstant());
        assertThrows(IllegalArgumentException.class,()->service.tasks(operator,WS,"","ALL",1,20,"2026-02-30",""));
        assertThrows(IllegalArgumentException.class,()->service.tasks(operator,WS,"","ALL",1,20,"2026-10-02","2026-10-01"));
    }
    @Test void missingSkuPersistsPendingCaptureNotGuessedProductAndCompletesAsPartial(){
        var body=AdenCollectionExtractionTest.payload();body.put("captureId",UUID.randomUUID().toString());object(body.get("fields"));var fields=object(body.get("fields"));fields.remove("sku");body.put("fields",fields);
        var saved=service.capture(operator,WS,body);assertNull(item.get("sku"));assertEquals("JD_PENDING:"+body.get("captureId"),item.get("identity_key"));assertEquals(true,saved.get("needsReview"));
        assertEquals(body.get("extraction"),withoutLegacy(object(saved.get("extraction"))));
        var complete=service.complete(operator,WS,str(body.get("captureId")),map("generation",1,"failures",List.of()));assertEquals("PARTIAL",complete.get("status"));
    }
    @Test void sameConfirmationReplaysOnceButChangedPayloadConflicts(){
        var body=AdenCollectionExtractionTest.payload();body.put("captureId",UUID.randomUUID().toString());var first=service.capture(operator,WS,body);var replay=service.capture(operator,WS,body);
        assertEquals(first.get("snapshotId"),replay.get("snapshotId"));assertEquals(1,snapshots.size());verify(ledger,times(1)).insertTask(any(),anyString(),any(),anyString());
        var ex=object(body.get("extraction"));ex.put("confirmationRevision","2");body.put("extraction",ex);assertThrows(AdenIdempotencyKeyReusedException.class,()->service.capture(operator,WS,body));
    }
    @Test void confirmedContentMissingStaysPartialEvenWithNoFailedImages(){
        var body=AdenCollectionExtractionTest.payload();body.put("captureId",UUID.randomUUID().toString());var extraction=object(body.get("extraction"));var section=AdenCollectionExtractionTest.section();section.put("missingReason","参数尚未完全加载");section.put("missingConfirmed",true);extraction.put("sections",List.of(section));body.put("extraction",extraction);
        service.capture(operator,WS,body);var saved=service.complete(operator,WS,str(body.get("captureId")),map("generation",1,"failures",List.of()));assertEquals("PARTIAL",saved.get("status"));assertEquals(false,saved.get("needsReview"));
    }
    @Test void normalizedImageProvenanceIsPersistedAsOneResource(){
        var body=AdenCollectionExtractionTest.payload();body.put("captureId",UUID.randomUUID().toString());var fields=object(body.get("fields"));fields.remove("parameters");body.put("fields",fields);
        var extraction=object(body.get("extraction"));extraction.put("selectedSections",List.of("main","gallery"));extraction.put("parameterRows",List.of());
        var main=AdenCollectionExtractionTest.section();main.put("sectionId","main");main.put("sourceIds",List.of("image:1"));var gallery=new LinkedHashMap<>(main);gallery.put("sectionId","gallery");extraction.put("sections",List.of(main,gallery));body.put("extraction",extraction);
        body.put("images",List.of(map("imageId","i1","sourceId","image:1","group","MAIN","url","https://img10.360buyimg.com/sample.png","sectionIds",List.of("main")),map("imageId","i2","sourceId","image:1","group","GALLERY","url","https://img10.360buyimg.com/sample.png","sectionIds",List.of("gallery"))));
        var images=objects(service.capture(operator,WS,body).get("images"));assertEquals(1,images.size());assertEquals("i1",images.get(0).get("imageId"));assertEquals(List.of("main","gallery"),images.get(0).get("sectionIds"));
    }
    @Test void excludedParameterCannotBePersistedThroughOldDisplayField(){
        var body=AdenCollectionExtractionTest.payload();body.put("captureId",UUID.randomUUID().toString());var extraction=object(body.get("extraction"));var rows=objects(extraction.get("parameterRows"));rows.get(1).put("excluded",true);extraction.put("parameterRows",rows);body.put("extraction",extraction);
        assertThrows(IllegalArgumentException.class,()->service.capture(operator,WS,body));assertTrue(snapshots.isEmpty());assertNull(item);verify(ledger,never()).insertTask(any(),anyString(),any(),anyString());verifyNoInteractions(cas);
        var fields=object(body.get("fields"));fields.put("parameters","材质：棉");body.put("fields",fields);var saved=service.capture(operator,WS,body);
        assertEquals("材质：棉",object(saved.get("fields")).get("parameters"));assertEquals(true,objects(object(saved.get("extraction")).get("parameterRows")).get(1).get("excluded"));
    }
    @Test void revokedAndCrossWorkspaceReadsFailBeforeCollectionQueries(){
        assertThrows(AdenNotFoundException.class,()->service.overview(operator,"22222222-2222-4222-8222-222222222222"));assertTrue(queries.isEmpty());
        assertThrows(AdenAccessDeniedException.class,()->service.overview(new AdenOperatorPrincipal(42,"denied",Set.of()),WS));assertTrue(queries.isEmpty());
    }
    @Test void exportRecordIsFrozenAndBecomesUnavailableAfterDeletion(){
        String id=UUID.randomUUID().toString();item=map("generation",1,"deleted",false);
        export=map("export_id",id,"file_name","result.zip","mime_type","application/zip","byte_size",3,"created_at",Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")),"selection_json",json.writeValueAsString(List.of(map("itemId","old-item","generation",1,"snapshot",map("snapshotId","old-snapshot","assetManifestVersion",1,"fields",map("title","冻结标题"),"images",List.of(map("imageId","saved","status","SAVED"),map("imageId","failed","status","FAILED"))),"curation",map("revision",2)))));
        files.write(WS,id+".export",new byte[]{1,2,3});var record=service.exportDetail(operator,WS,id);assertEquals("COMPLETED",record.get("status"));assertEquals(true,record.get("downloadAvailable"));assertEquals(1L,record.get("imageSaved"));assertEquals(1L,record.get("imageMissing"));
        assertEquals("冻结标题",objects(record.get("selections")).get(0).get("title"));item.put("deleted",true);record=service.exportDetail(operator,WS,id);assertEquals("UNAVAILABLE",record.get("status"));assertEquals(false,record.get("downloadAvailable"));
    }
    private static Map<String,Object> withoutLegacy(Map<String,Object> extraction){var result=new LinkedHashMap<>(extraction);result.remove("legacy");result.remove("ranges");return result;}
}
