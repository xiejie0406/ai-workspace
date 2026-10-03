package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 只按中心所属对象清单清理；先标记不可读，外部对象操作失败保留补偿记录。 */
@Service @AipptEnabled @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="aippt.cleanup",name="enabled",havingValue="true") public class AssetCleanupService  {
    private final AipptRepository db;
    private final AssetService assets;
    private final TransactionTemplate tx;
    private final com.ruoyi.aippt.configuration.AipptProperties props;
    public AssetCleanupService(AipptRepository d, AssetService a, @Qualifier("aipptTransactions") TransactionTemplate t, com.ruoyi.aippt.configuration.AipptProperties p)  {
        db = d;
        assets = a;
        tx = t;
        props = p;
    }
    @Scheduled(fixedDelay=60000) public void sweep()  {
        if(!props.getCleanup().isEnabled())return;
        assets.enqueueCompensations();
        expireExportsAndSnapshots();
        db.purgeEvents(props.getCleanup().getEventDays());
        for (JSONObject candidate : db.pendingOutbox())  {
            if (candidate.getString("not_before") == null || Instant.parse(candidate.getString("not_before")).isAfter(Instant.now())) continue;
            String type = candidate.getString("type"), project = candidate.getString("project_id");
            if (!List.of("asset_cleanup", "asset_orphan", "asset_stage_cleanup").contains(type)) continue;
            try  {
                JSONObject p = db.get("project", project);
                if (p == null) continue;
                if("asset_stage_cleanup".equals(type))assets.cleanCompensation(candidate);
                else if ("asset_cleanup".equals(type) && "deleted".equals(p.getString("status")))  {
                    for (JSONObject asset : db.children("asset", project)) assets.purge(project, asset.getString("id"), true);
                }
                else if ("asset_orphan".equals(type))  {
                    String assetId=candidate.getString("asset_id");
                    JSONObject asset=db.get("asset",assetId);
                    String taskId=asset==null?null:asset.getString("task_id");
                    JSONObject task=taskId==null?null:db.get("task",taskId);
                    if(task!=null&&List.of("queued","running").contains(task.getString("status")))continue;
                    assets.purge(project,assetId,false);
                }
                tx.execute(status ->  {
                    JSONObject out = db.lock("outbox", candidate.getString("id"));
                    out.put("status", "completed");
                    db.save("outbox", out, out.getLongValue("revision"));
                    return null;
                }
                );
            }
            catch (RuntimeException failure)  {
                tx.execute(status ->  {
                    JSONObject out = db.lock("outbox", candidate.getString("id"));
                    int attempts = out.getIntValue("attempts") + 1;
                    out.put("attempts", attempts);
                    out.put("error", "所属私有对象清理失败，等待重试");
                    out.put("not_before", Instant.now().plusSeconds(Math.min(3600, 60L * attempts)).toString());
                    db.save("outbox", out, out.getLongValue("revision"));
                    return null;
                }
                );
            }
        }
    }
    private void expireExportsAndSnapshots()  {
        int days=props.getCleanup().getExportDays();
        if(days<1)throw new IllegalStateException("清理保留天数必须大于0");
        for(String table:List.of("export","snapshot"))for(JSONObject candidate:table.equals("export")?db.agedExports(days):db.agedSnapshots(days))  {
            tx.execute(status->  {
                String project=candidate.getString("project_id");
                JSONObject p=db.lock("project",project);
                if(p==null)return null;
                // 活跃任务持有不可变快照，在完成或硬期限回收之前保留全部对象。
                if(db.children("task",project).stream().anyMatch(task->List.of("queued","running").contains(task.getString("status"))))return null;
                JSONObject current=db.lock(table,candidate.getString("id"));
                if(current==null||"deleted".equals(current.getString("status")))return null;
                current.put("status","deleted");
                db.save(table,current,current.getLongValue("revision"));
                assets.reconcile(project);
                return null;
            }
            );
        }
    }
}
