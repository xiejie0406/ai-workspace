package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;

/** 调用真实领取/恢复及公开批量详情；仓储替身不连接 MySQL、Worker 或 Provider。 */
class BatchItemLifecycleTest {
    private static final long OWNER=17;
    private AiPictureRepository db;
    private RunService runs;
    private BatchExportEvaluationService business;
    private final String batch=Json.id(),child=Json.id(),item=Json.id(),waitingItem=Json.id();
    private final Map<String,Map<String,Object>> runRows=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> itemRows=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> dispatchRows=new LinkedHashMap<>();
    private Map<String,Object> batchRow;

    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);var assets=mock(AssetService.class);var storage=mock(AiPictureStorage.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());var tx=new TransactionTemplate(manager);
        var properties=new AiPictureProperties();properties.setPublicInternalBaseUrl("http://unused.invalid");var registry=new ToolRegistry(properties);
        runs=new RunService(db,mock(SessionService.class),assets,registry,storage,properties,tx);
        business=new BatchExportEvaluationService(db,runs,assets,registry,storage,tx,new CommandIdempotency(db,tx));
        batchRow=Json.of("id",batch,"user_id",OWNER,"run_id",batch,"status","running");
        runRows.put(batch,runRow(batch));runRows.put(child,runRow(child));
        runRows.get(child).putAll(Json.of("status","queued","batch_id",batch,"batch_item_id",item));
        itemRows.put(item,Json.of("id",item,"batch_id",batch,"user_id",OWNER,"position",0,"source_asset_id",Json.id(),"run_id",child,"status","queued","output_ids",List.of(),"attempt",0));
        itemRows.put(waitingItem,Json.of("id",waitingItem,"batch_id",batch,"user_id",OWNER,"position",1,"source_asset_id",Json.id(),"run_id",Json.id(),"status","queued","output_ids",List.of(),"attempt",0));
        dispatchRows.put(child,Json.of("run_id",child,"status","pending","available_at",0));
        when(db.owned(anyString(),anyString(),anyLong(),anyBoolean())).thenAnswer(call->{
            String table=call.getArgument(0),id=call.getArgument(1);long owner=call.getArgument(2);
            var row=table.equals("aip_batch")?batchRow:runRows.get(id);
            if(row==null||Json.number(row.get("user_id"))!=owner) throw AiPictureException.missing();return Json.copy(row);
        });
        when(assets.get(anyLong(),anyString())).thenAnswer(call->Json.of("id",call.getArgument(1),"kind","original"));
        when(db.one(anyString(),anyMap())).thenAnswer(call->readOne(call.getArgument(0),call.getArgument(1)));
        when(db.query(anyString(),anyMap())).thenAnswer(call->readMany(call.getArgument(0),call.getArgument(1)));
        when(db.execute(anyString(),anyMap())).thenAnswer(call->write(call.getArgument(0),call.getArgument(1)));
    }
    private Map<String,Object> runRow(String id) {
        return Json.of("id",id,"user_id",OWNER,"tool","batch_process","status","running","attempt",0,"event_seq",0,"progress",0,"params",Json.of("operations",List.of(Json.of("tool","adjust_image","params",Map.of()))),"result",Map.of(),"input_snapshot",Json.of("assets",List.of()));
    }
    private Map<String,Object> readOne(String sql,Map<String,Object> p) {
        if(sql.contains("SELECT r.* FROM aip_dispatch")) for(var row:runRows.values()) {
            var dispatch=dispatchRows.get(Json.string(row.get("id")));
            if(dispatch!=null&&"pending".equals(dispatch.get("status"))&&"queued".equals(row.get("status"))&&Json.number(dispatch.get("available_at"))<=Json.number(p.get("now"))&&(p.get("requested")==null||p.get("requested").equals(row.get("id")))) return Json.copy(row);
        }
        return null;
    }
    private List<Map<String,Object>> readMany(String sql,Map<String,Object> p) {
        if(sql.contains("WHERE status='running' AND lease_expires_at")) return runRows.values().stream().filter(row->"running".equals(row.get("status"))&&row.get("lease_expires_at")!=null&&Json.number(row.get("lease_expires_at"))<Json.number(p.get("now"))&&(p.get("requested")==null||p.get("requested").equals(row.get("id")))).map(Json::copy).toList();
        if(sql.contains("FROM aip_batch_item")) return itemRows.values().stream().filter(row->p.get("id").equals(row.get("batch_id"))&&Json.number(p.get("owner"))==Json.number(row.get("user_id"))).sorted(Comparator.comparingLong(row->Json.number(row.get("position")))).map(Json::copy).toList();
        return List.of();
    }
    private int write(String sql,Map<String,Object> p) {
        if(sql.startsWith("UPDATE aip_run")) {
            var row=runRows.get(Json.string(p.get("id")));
            if(sql.contains("event_seq=event_seq+1")) row.put("event_seq",Json.number(row.get("event_seq"))+1);
            else if(sql.contains("status='running'")) row.putAll(Json.of("status","running","attempt",p.get("attempt"),"lease_token",p.get("lease"),"lease_expires_at",p.get("expires")));
            else {if(p.containsKey("status")) row.put("status",p.get("status"));if(p.containsKey("result")) row.put("result",Json.map(p.get("result")));if(p.containsKey("progress")) row.put("progress",p.get("progress"));}
        } else if(sql.startsWith("UPDATE aip_dispatch")) {
            var dispatch=dispatchRows.get(Json.string(p.get("id")));dispatch.put("status",sql.contains("status='claimed'")?"claimed":p.get("status"));if(p.containsKey("now")) dispatch.put("available_at",p.get("now"));
        } else if(sql.startsWith("UPDATE aip_batch_item")) {
            var row=itemRows.get(Json.string(p.get("item")));if(row==null||Json.number(row.get("user_id"))!=Json.number(p.get("owner"))) return 0;
            if(p.containsKey("prior")) {
                // 仓储替身按公开更新条件匹配，防止测试无条件写状态掩盖旧 run/owner/CAS 缺失。
                assertTrue(sql.contains("user_id=:owner")&&sql.contains("run_id=:run")&&sql.contains("status=:prior"));
                if(!Objects.equals(row.get("run_id"),p.get("run"))||!Objects.equals(row.get("status"),p.get("prior"))) return 0;
            }
            row.put("status",p.get("status"));if(p.containsKey("outputs")) row.put("output_ids",Json.list(com.alibaba.fastjson2.JSON.parse(Json.string(p.get("outputs")))));
        } else if(sql.startsWith("UPDATE aip_batch SET")) batchRow.put("status",p.get("status"));
        return 1;
    }
    private Map<String,Object> publicItem(String id) {
        return Json.list(business.batch(OWNER,batch).get("items")).stream().map(Json::map).filter(row->id.equals(row.get("id"))).findFirst().orElseThrow();
    }
    private void expireChild(String operation) {
        runRows.get(child).putAll(Json.of("status","running","attempt",1,"lease_expires_at",System.currentTimeMillis()-1000,"params",Json.of("operations",List.of(Json.of("tool",operation,"params",Map.of())))));
        dispatchRows.get(child).put("status","claimed");
    }
    @Test void claimedItemIsRunningInPublicDetailWhileUnstartedItemWaits() {
        assertEquals("queued",publicItem(item).get("status"));var claimed=runs.claimForRun("fixture-worker",child);assertEquals(child,claimed.get("run_id"));
        assertEquals("running",publicItem(item).get("status"),"已领取项必须显示处理中");assertEquals("queued",publicItem(waitingItem).get("status"),"未领取的图片继续排队");
    }
    @Test void expiredDeterministicItemReturnsToQueueBeforeNextClaim() {
        itemRows.get(item).put("status","running");expireChild("adjust_image");assertNull(runs.claimForRun("fixture-worker",child),"恢复项有原5000ms退避，此次不立即领取");
        assertEquals("queued",runRows.get(child).get("status"));assertEquals("queued",publicItem(item).get("status"),"允许恢复的纯计算项回到排队状态");assertEquals("queued",publicItem(waitingItem).get("status"));
    }
    @Test void expiredPaidItemStaysUnknownAndSuccessfulSiblingKeepsOutputs() {
        String output=Json.id();itemRows.get(item).put("status","running");itemRows.get(waitingItem).putAll(Json.of("status","succeeded","output_ids",List.of(output)));expireChild("replace_background");assertNull(runs.claimForRun("fixture-worker",child));
        assertEquals("external_result_unknown",publicItem(item).get("status"));assertEquals("succeeded",publicItem(waitingItem).get("status"));assertEquals(output,Json.map(Json.list(publicItem(waitingItem).get("outputs")).get(0)).get("id"));
        assertEquals("partial_success",Json.map(business.batch(OWNER,batch).get("run")).get("status"),"未知付费项不能被当成可恢复排队项，原成功结果仍保留");
    }
    @Test void obsoleteRunCannotChangeItemReboundToNewRetry() {
        itemRows.get(item).put("run_id",Json.id());runs.claimForRun("fixture-worker",child);assertEquals("queued",publicItem(item).get("status"));
        itemRows.get(item).put("status","running");expireChild("adjust_image");runs.claimForRun("fixture-worker",child);assertEquals("running",publicItem(item).get("status"),"旧run恢复不得倒写新run所属项");
    }
    @Test void otherOwnerCannotChangeItemDuringClaimOrRecovery() {
        itemRows.get(item).put("user_id",OWNER+1);runs.claimForRun("fixture-worker",child);assertEquals("queued",itemRows.get(item).get("status"));
        itemRows.get(item).put("status","running");expireChild("adjust_image");runs.claimForRun("fixture-worker",child);assertEquals("running",itemRows.get(item).get("status"));assertEquals(1,Json.list(business.batch(OWNER,batch).get("items")).size());
    }
    @ParameterizedTest @ValueSource(strings={"succeeded","cancelled","external_result_unknown"})
    void terminalItemCannotBeReopenedDuringClaimOrRecovery(String terminal) {
        itemRows.get(item).put("status",terminal);runs.claimForRun("fixture-worker",child);assertEquals(terminal,publicItem(item).get("status"));
        expireChild("adjust_image");runs.claimForRun("fixture-worker",child);assertEquals(terminal,publicItem(item).get("status"));
    }
}
