package com.ruoyi.aden.application.collection;

import com.ruoyi.aden.application.error.*;
import com.ruoyi.aden.application.idempotency.*;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.application.task.*;
import com.ruoyi.aden.application.workspace.AdenWorkspaceAccessGuard;
import com.ruoyi.aden.domain.task.*;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceId;
import com.ruoyi.aden.infrastructure.persistence.AdenCollectionStore;
import com.ruoyi.aden.infrastructure.storage.AdenCollectionFiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 采集库的事务入口。Workspace 行串行化写入、幂等、清单修订与 CORE ledger 一起提交。 */
@Transactional(transactionManager="adenTransactionManager")
public class AdenCollectionService {
    private final AdenCollectionStore db;
    private final AdenCollectionFiles files;
    private final AdenWorkspaceAccessGuard guard;
    private final AdenTaskLedgerRepository ledger;
    private final AdenTaskCasRepository cas;
    private final ObjectMapper json;
    private final AdenRequestFingerprint fingerprint;
    private final long quota;
    private final String fixtureOrigin;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TASK_JOIN = " from aden_collection_snapshot s join aden_collection_item i on i.workspace_id=s.workspace_id and i.item_id=s.item_id " +
            "join aden_task t on t.workspace_id=s.workspace_id and t.task_id=s.core_task_id ";
    private static final String TASK_BOUNDARY = "where s.workspace_id=? and s.source='JD' and t.task_type='JD_DETAIL_CAPTURE' ";
    private static final String TASK_COLUMNS = "select s.*,i.title,i.sku,i.platform,i.deleted,t.task_state,t.error_code,t.error_message";
    private static final String PENDING_CONDITION = "(s.status='PARTIAL' or t.task_state='FAILED' or json_extract(s.content_json,'$.needsReview')=true)";
    private static final Set<String> IMAGE_HOSTS=Set.of("img10.360buyimg.com","img11.360buyimg.com","img12.360buyimg.com","img13.360buyimg.com","img14.360buyimg.com","img30.360buyimg.com");
    public AdenCollectionService(AdenCollectionStore db, AdenCollectionFiles files, AdenWorkspaceAccessGuard guard,
            AdenTaskLedgerRepository ledger, AdenTaskCasRepository cas, ObjectMapper json,
            AdenRequestFingerprint fingerprint, long quota) {
        this(db,files,guard,ledger,cas,json,fingerprint,quota,"");
    }
    public AdenCollectionService(AdenCollectionStore db, AdenCollectionFiles files, AdenWorkspaceAccessGuard guard,
            AdenTaskLedgerRepository ledger, AdenTaskCasRepository cas, ObjectMapper json,
            AdenRequestFingerprint fingerprint, long quota,String fixtureOrigin) {
        this.db=db; this.files=files; this.guard=guard; this.ledger=ledger; this.cas=cas; this.json=json; this.fingerprint=fingerprint;
        if(quota<1) throw new IllegalArgumentException("采集库配额必须为正数"); this.quota=quota;
        if(!fixtureOrigin.isBlank() && !fixtureOrigin.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}"))throw new IllegalArgumentException("fixture-origin只允许明确端口的loopback origin");
        this.fixtureOrigin=fixtureOrigin;
    }
    private com.ruoyi.aden.domain.workspace.AdenWorkspaceMembership access(AdenOperatorPrincipal p,String ws,String operation,boolean write) {
        var member=guard.requireWorkspace(p,"aden:collection:"+operation,new AdenWorkspaceId(ws),
            write?AdenWorkspaceAccessGuard.WRITE_ROLES:AdenWorkspaceAccessGuard.READ_ROLES,UUID.randomUUID().toString());
        if(write) ledger.lockWorkspaceEventSequence(new AdenWorkspaceId(ws));
        return member;
    }
    public Map<String,Object> list(AdenOperatorPrincipal p,String ws,String q,boolean deleted,int page,int size) {
        access(p,ws,"read",false);
        if(page<1 || page>100000 || size<1 || size>100 || q.length()>200) throw new IllegalArgumentException("分页参数无效");
        String search="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        Object total=db.one("select count(*) as n from aden_collection_item where workspace_id=? and deleted=? and (sku is not null or platform='MANUAL') and (title like ? escape '!' or sku like ? escape '!')",ws,deleted,search,search).get("n");
        var rows=db.rows("select * from aden_collection_item where workspace_id=? and deleted=? and (sku is not null or platform='MANUAL') and (title like ? escape '!' or sku like ? escape '!') order by updated_at desc,item_id limit ? offset ?",ws,deleted,search,search,size,(page-1)*size);
        return map("items",rows.stream().map(this::summary).toList(),"total",total,"page",page,"pageSize",size);
    }
    public Map<String,Object> detail(AdenOperatorPrincipal p,String ws,String id) {
        access(p,ws,"read",false); return detailInternal(ws,id);
    }
    /** 一次用户确认保存对应一个 CORE 任务；从权威任务与其快照联合读取。 */
    public Map<String,Object> tasks(AdenOperatorPrincipal p,String ws,String q,String status,int page,int size) {
        return tasks(p,ws,q,status,page,size,"","");
    }
    @Transactional(transactionManager="adenTransactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> tasks(AdenOperatorPrincipal p,String ws,String q,String status,int page,int size,String fromDate,String toDate) {
        access(p,ws,"read",false);
        if(page<1 || page>100000 || size<1 || size>100 || q.length()>200 ||
                !Set.of("ALL","SAVING","SAVED","PARTIAL").contains(status)) throw new IllegalArgumentException("采集任务筛选参数无效");
        String search="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        LocalDate start=date(fromDate), end=date(toDate);
        if(start!=null && end!=null && start.isAfter(end)) throw new IllegalArgumentException("起始日期不能晚于结束日期");
        String where=TASK_JOIN+TASK_BOUNDARY+
            "and (json_unquote(json_extract(s.content_json,'$.fields.title')) like ? escape '!' " +
            "or json_unquote(json_extract(s.content_json,'$.fields.sku')) like ? escape '!') and (?='ALL' or s.status=?)";
        var parameters=new ArrayList<Object>(Arrays.asList(ws,search,search,status,status));
        if(start!=null){where+=" and s.created_at>=?";parameters.add(Timestamp.from(start.atStartOfDay(BUSINESS_ZONE).toInstant()));}
        if(end!=null){where+=" and s.created_at<?";parameters.add(Timestamp.from(end.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));}
        Object total=db.one("select count(*) as n"+where,parameters.toArray()).get("n");
        parameters.add(size); parameters.add((page-1)*size);
        var rows=db.rows(TASK_COLUMNS+where+" order by s.created_at desc,s.snapshot_id desc limit ? offset ?",parameters.toArray());
        return map("items",rows.stream().map(this::taskSummary).toList(),"total",total,"page",page,"pageSize",size);
    }

    /** 三个计数及近期列表从同一个只读一致快照取得，不累加客户端事件。 */
    @Transactional(transactionManager="adenTransactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> overview(AdenOperatorPrincipal p,String ws) {
        access(p,ws,"read",false);
        Instant now=Instant.now(); LocalDate today=now.atZone(BUSINESS_ZONE).toLocalDate();
        long saved=number(db.one("select count(*) as n from aden_collection_item where workspace_id=? and deleted=false and (sku is not null or platform='MANUAL')",ws).get("n"));
        long captures=number(db.one("select count(*) as n"+TASK_JOIN+TASK_BOUNDARY+"and s.created_at>=? and s.created_at<?",ws,
                Timestamp.from(today.atStartOfDay(BUSINESS_ZONE).toInstant()),Timestamp.from(today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())).get("n"));
        long pending=number(db.one("select count(*) as n"+TASK_JOIN+TASK_BOUNDARY+"and "+PENDING_CONDITION,ws).get("n"));
        var items=db.rows("select * from aden_collection_item where workspace_id=? and deleted=false and (sku is not null or platform='MANUAL') order by updated_at desc,item_id desc limit 5",ws);
        var recent=db.rows(TASK_COLUMNS+TASK_JOIN+TASK_BOUNDARY+"order by s.created_at desc,s.snapshot_id desc limit 5",ws);
        var waiting=db.rows(TASK_COLUMNS+TASK_JOIN+TASK_BOUNDARY+"and "+PENDING_CONDITION+" order by s.created_at desc,s.snapshot_id desc limit 5",ws);
        return map("savedProductCount",saved,"todayCaptureCount",captures,"pendingTaskCount",pending,
                "recentItems",items.stream().map(this::summary).toList(),"recentTasks",recent.stream().map(this::taskSummary).toList(),
                "pendingTasks",waiting.stream().map(this::taskSummary).toList(),"updatedAt",now.toString(),"timezone",BUSINESS_ZONE.getId());
    }

    private static LocalDate date(String value) {
        if(value==null || value.isBlank())return null;
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("日期必须为YYYY-MM-DD");
        try{return LocalDate.parse(value);}catch(DateTimeParseException invalid){throw new IllegalArgumentException("日期无效",invalid);}
    }
    public Map<String,Object> taskDetail(AdenOperatorPrincipal p,String ws,String taskId) {
        access(p,ws,"read",false);
        var row=db.one("select s.*,i.title,i.sku,i.platform,i.deleted,t.task_state,t.error_code,t.error_message " +
            "from aden_collection_snapshot s join aden_collection_item i on i.workspace_id=s.workspace_id and i.item_id=s.item_id " +
            "join aden_task t on t.workspace_id=s.workspace_id and t.task_id=s.core_task_id " +
            "where s.workspace_id=? and s.core_task_id=? and s.source='JD' and t.task_type='JD_DETAIL_CAPTURE'",ws,taskId);
        if(row==null) throw new AdenNotFoundException();
        var result=taskSummary(row);
        result.put("fields",decode(row.get("content_json")).get("fields"));
        result.put("blocks",decode(row.get("content_json")).get("blocks"));
        result.put("images",decode(row.get("manifest_json")).get("images"));
        result.put("parserVersion",decode(row.get("content_json")).get("parserVersion"));
        result.put("completeness",decode(row.get("content_json")).get("completeness"));
        result.put("extraction",decode(row.get("content_json")).get("extraction"));
        return result;
    }
    private Map<String,Object> taskSummary(Map<String,Object> row) {
        var content=decode(row.get("content_json")); var fields=object(content.get("fields"));
        var images=objects(decode(row.get("manifest_json")).get("images"));
        return map("taskId",row.get("core_task_id"),"captureId",row.get("snapshot_id"),"snapshotId",row.get("snapshot_id"),
            "itemId",row.get("item_id"),"title",fields.get("title"),"sku",fields.get("sku"),"platform",row.get("platform"),
            "source",row.get("source"),"sourceUrl",fields.get("sourceUrl"),"specification",fields.get("specification"),
            "createdAt",time(row.get("created_at")),"taskState",row.get("task_state"),"status",row.get("status"),
            "contentSaved",true,"imageSaved",images.stream().filter(i->"SAVED".equals(i.get("status"))).count(),
            "imageFailed",images.stream().filter(i->"FAILED".equals(i.get("status"))).count(),"imageTotal",images.size(),
            "blockCount",listValue(content.get("blocks")).size(),"deleted",bool(row.get("deleted")),
            "needsReview",Boolean.TRUE.equals(content.get("needsReview")),"pendingReason",pendingReason(content,images,row),
            "errorCode",row.get("error_code"),"errorMessage",row.get("error_message"));
    }
    private static String pendingReason(Map<String,Object> content,List<Map<String,Object>> images,Map<String,Object> row) {
        if(!str(content.get("pendingReason")).isBlank())return str(content.get("pendingReason"));
        if(!str(row.get("error_message")).isBlank())return str(row.get("error_message"));
        for(var section:objects(object(content.get("extraction")).get("sections")))if(!str(section.get("missingReason")).isBlank())return str(section.get("missingReason"));
        for(var image:images)if("FAILED".equals(image.get("status")))return str(image.getOrDefault("error","图片未保存，请查看详情"));
        return "PARTIAL".equals(row.get("status"))?"部分资料未保存，请查看详情":"";
    }
    public Map<String,Object> lookup(AdenOperatorPrincipal p,String ws,String sku) {
        access(p,ws,"read",false); if(!sku.matches("[0-9]{1,32}"))throw new IllegalArgumentException("SKU无效");
        var item=db.one("select * from aden_collection_item where workspace_id=? and identity_key=?",ws,"JD:"+sku);
        return item==null?map("exists",false):map("exists",true,"itemId",item.get("item_id"),"deleted",bool(item.get("deleted")));
    }
    private Map<String,Object> detailInternal(String ws,String id) {
        var item=item(ws,id); var result=summary(item);
        var current=snapshot(findSnapshot(ws,str(item.get("snapshot_id"))));
        result.put("currentSnapshot",current);
        // 兼容初版客户端；完整历史通过有界摘要页与单版本接口读取。
        result.put("snapshots",List.of(current));
        result.put("snapshotCount",db.one("select count(*) as n from aden_collection_snapshot where workspace_id=? and item_id=?",ws,id).get("n"));
        result.put("curation",curation(ws,id)); return result;
    }
    public Map<String,Object> snapshots(AdenOperatorPrincipal p,String ws,String itemId,int page,int pageSize) {
        access(p,ws,"read",false); item(ws,itemId);
        if(page<1 || page>100000 || pageSize<1 || pageSize>50)throw new IllegalArgumentException("版本分页参数无效");
        var rows=db.rows("select snapshot_id,source,status,manifest_version,created_at from aden_collection_snapshot where workspace_id=? and item_id=? order by created_at desc,snapshot_id desc limit ? offset ?",ws,itemId,pageSize,(page-1)*pageSize);
        var items=rows.stream().map(row->map("snapshotId",row.get("snapshot_id"),"source",row.get("source"),"status",row.get("status"),"assetManifestVersion",number(row.get("manifest_version")),"createdAt",time(row.get("created_at")))).toList();
        return map("items",items,"total",db.one("select count(*) as n from aden_collection_snapshot where workspace_id=? and item_id=?",ws,itemId).get("n"),"page",page,"pageSize",pageSize);
    }
    public Map<String,Object> itemSnapshot(AdenOperatorPrincipal p,String ws,String itemId,String snapshotId) {
        access(p,ws,"read",false); item(ws,itemId); var row=findSnapshot(ws,snapshotId);
        if(!itemId.equals(row.get("item_id")))throw new AdenNotFoundException(); return snapshot(row);
    }
    private Map<String,Object> summary(Map<String,Object> row) {
        var snap=db.one("select * from aden_collection_snapshot where workspace_id=? and snapshot_id=?",row.get("workspace_id"),row.get("snapshot_id"));
        var result=map("itemId",row.get("item_id"),"title",row.get("title"),"platform",row.get("platform"),"sku",row.get("sku"),
            "snapshotId",row.get("snapshot_id"),"generation",number(row.get("generation")),"version",number(row.get("version")),"deleted",bool(row.get("deleted")),
            "updatedAt",time(row.get("updated_at")),"status",snap==null?"SAVING":snap.get("status"),"needsReview",row.get("sku")==null && "JD".equals(row.get("platform")));
        if(snap!=null) {
            var fields=object(decode(snap.get("content_json")).get("fields")); var images=objects(decode(snap.get("manifest_json")).get("images"));
            var curation=curation(str(row.get("workspace_id")),str(row.get("item_id")));
            boolean applies=Objects.equals(curation.get("snapshotId"),row.get("snapshot_id"));
            Set<Object> removed=applies?new HashSet<>(listValue(curation.get("removedImageIds"))):Set.of();
            var available=images.stream().filter(i->"SAVED".equals(i.get("status"))&&!removed.contains(i.get("imageId"))).toList();
            var primary=available.stream().filter(i->applies&&Objects.equals(i.get("imageId"),curation.get("mainImageId"))).findFirst().orElse(available.isEmpty()?Map.of():available.get(0));
            result.putAll(map("price",fields.get("price"),"priceText",fields.get("priceText"),"specification",fields.get("specification"),"thumbnailAssetId",primary.get("assetId"),"imageSaved",images.stream().filter(i->"SAVED".equals(i.get("status"))).count(),"imageTotal",images.size()));
        }
        return result;
    }
    private Map<String,Object> snapshot(Map<String,Object> row) {
        var result=decode(row.get("content_json"));
        result.putAll(map("snapshotId",row.get("snapshot_id"),"captureId",row.get("snapshot_id"),"itemId",row.get("item_id"),"coreTaskId",row.get("core_task_id"),
            "generation",number(row.get("generation")),"source",row.get("source"),"status",row.get("status"),"createdAt",time(row.get("created_at")),
            "assetManifestVersion",number(row.get("manifest_version")),"images",decode(row.get("manifest_json")).get("images")));
        return result;
    }
    public Map<String,Object> capture(AdenOperatorPrincipal p,String ws,Map<String,Object> body) {
        access(p,ws,"capture",true); return captureInternal(p,ws,body,false);
    }
    public Map<String,Object> manual(AdenOperatorPrincipal p,String ws,Map<String,Object> body) {
        access(p,ws,"create",true);
        String key=required(body,"idempotencyKey",128);
        String captureId=UUID.nameUUIDFromBytes((p.userId()+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
        var fields=map("title",body.get("title"),"price",body.get("price"),"sourceUrl",body.get("sourceUrl"));
        var request=map("captureId",captureId,"source","MANUAL","fields",fields,"blocks",List.of(),"images",List.of(),"notes",body.get("notes"));
        var capture=captureInternal(p,ws,request,true);
        if("SAVING".equals(capture.get("status"))) complete(p,ws,captureId,map("generation",capture.get("generation"),"failures",List.of()));
        return detailInternal(ws,str(capture.get("itemId")));
    }
    private Map<String,Object> captureInternal(AdenOperatorPrincipal p,String ws,Map<String,Object> body,boolean manual) {
        String id=uuid(required(body,"captureId",36));
        if(encoded(body).getBytes(StandardCharsets.UTF_8).length>196608) throw new IllegalArgumentException("商品内容超过192KiB，请缩小范围");
        String hash=fingerprint.hashValue(body);
        var prior=db.one("select * from aden_collection_snapshot where workspace_id=? and snapshot_id=?",ws,id);
        if(prior!=null) {
            if(number(prior.get("actor_id"))!=p.userId() || !hash.equals(prior.get("request_hash"))) throw new AdenIdempotencyKeyReusedException();
            requireLive(item(ws,str(prior.get("item_id"))),number(prior.get("generation")));
            return snapshot(prior);
        }
        var fields=object(body.get("fields")); String title=required(fields,"title",500);
        validatePrice(fields.get("price"));
        String sku=str(fields.get("sku"));
        if(!manual && !sku.isBlank() && !sku.matches("[0-9]{1,32}")) throw new IllegalArgumentException("京东SKU格式无效");
        boolean needsReview=!manual && sku.isBlank();
        Map<String,Object> extraction=manual?map("legacy",true):AdenCollectionExtraction.validate(body);
        String sourceUrl=str(fields.get("sourceUrl"));
        boolean sourceFixture=false;
        if(!sourceUrl.isBlank()) {
            URI uri=URI.create(sourceUrl);
            sourceFixture=isFixture(uri);
            if((!"https".equals(uri.getScheme())&&!sourceFixture) || uri.getHost()==null || uri.getRawUserInfo()!=null) throw new IllegalArgumentException("来源链接无效");
            if(!manual && !sourceFixture && (!"item.jd.com".equals(uri.getHost()) || uri.getPort()!=-1
                    || (needsReview?!uri.getPath().matches("/[0-9]{1,32}\\.html"):!uri.getPath().equals("/"+sku+".html")))) throw new IllegalArgumentException("京东详情链接与SKU不匹配");
            fields.put("sourceUrl", uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath());
        } else if(!manual) throw new IllegalArgumentException("京东详情链接缺失");
        Set<String> allowed=Set.of("title","sku","price","priceText","sourceUrl","specification","shop","parameters","stockText","deliveryText","promotionText");
        fields.keySet().retainAll(allowed);
        List<Map<String,Object>> images=objects(body.get("images"));
        if(images.size()>50) throw new IllegalArgumentException("每次最多50张图片");
        images=AdenCollectionExtraction.validateImageSources(images,extraction);
        Set<String> imageIds=new HashSet<>();
        for(var image:images) {
            String imageId=required(image,"imageId",128);
            if(!imageId.matches("[A-Za-z0-9_-]+") || !imageIds.add(imageId)) throw new IllegalArgumentException("图片标识重复或无效");
            image.keySet().retainAll(Set.of("imageId","url","group","order","sourceId","sectionIds"));
            String url=str(image.get("url"));
            if(!url.isBlank()) { var uri=URI.create(url); if((!"https".equals(uri.getScheme())&&!isFixture(uri)) || uri.getHost()==null || uri.getRawUserInfo()!=null) throw new IllegalArgumentException("图片URL不合法"); image.put("url",uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()); }
            image.put("status","PENDING"); image.put("source",manual?"MANUAL":"JD");
            if(!manual && !url.isBlank()) {
                URI uri=URI.create(url);
                if(!isFixture(uri) && (!IMAGE_HOSTS.contains(uri.getHost()) || uri.getPort()!=-1)) {
                    image.put("url",""); image.put("status","FAILED"); image.put("error","图片来源未批准"); image.put("sourceDenied",true);
                }
            }
        }
        List<Map<String,Object>> blocks=objects(body.get("blocks"));
        if(blocks.size()>1000) throw new IllegalArgumentException("内容区块超限");
        blocks.forEach(b->b.keySet().retainAll(Set.of("type","text","imageId","sourceId","sectionIds")));
        blocks=AdenCollectionExtraction.normalizeBlocks(blocks,extraction);
        String identity=manual?"MANUAL:"+id:needsReview?"JD_PENDING:"+id:"JD:"+sku;
        var existing=db.one("select * from aden_collection_item where workspace_id=? and identity_key=? for update",ws,identity);
        if(existing!=null && bool(existing.get("deleted"))) throw conflict("商品位于回收站，请先恢复后采集");
        Instant now=Instant.now(); String itemId=existing==null?newId():str(existing.get("item_id"));
        long generation=existing==null?1:number(existing.get("generation"));
        if(existing!=null) {
            var previous=findSnapshot(ws,str(existing.get("snapshot_id")));
            if("SAVING".equals(previous.get("status"))) {
                var oldTask=ledger.findTaskForUpdate(new AdenWorkspaceId(ws),new AdenTaskId(str(previous.get("core_task_id")))).orElseThrow(AdenNotFoundException::new).task();
                if(oldTask.state()==AdenTaskState.RUNNING) { oldTask=advance(oldTask,AdenTaskActor.OPERATOR,AdenTaskCommand.REQUEST_CANCEL,p.userId()); advance(oldTask,AdenTaskActor.COORDINATOR,AdenTaskCommand.CONFIRM_CANCELED,p.userId()); }
                var oldImages=objects(decode(previous.get("manifest_json")).get("images"));
                for(var image:oldImages)if(!"SAVED".equals(image.get("status")))image.putAll(map("status","FAILED","error","已开始新快照，旧资源保存已停止"));
                saveManifest(ws,previous,oldImages);
                db.update("update aden_collection_snapshot set status='PARTIAL' where workspace_id=? and snapshot_id=?",ws,previous.get("snapshot_id"));
                db.update("update aden_task_step set step_state='CANCELED',finished_at=?,updated_at=?,version=version+1 where workspace_id=? and task_id=? and step_state='RUNNING'",Timestamp.from(now),Timestamp.from(now),ws,oldTask.id().value());
            }
        }
        String taskId=newId();
        createTask(p,ws,taskId,title,id,manual,now);
        if(existing==null) db.update("insert into aden_collection_item(workspace_id,item_id,identity_key,platform,sku,title,snapshot_id,generation,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",ws,itemId,identity,manual?"MANUAL":"JD",manual||needsReview?null:sku,title,id,generation,Timestamp.from(now),Timestamp.from(now));
        else db.update("update aden_collection_item set title=?,snapshot_id=?,version=version+1,updated_at=? where workspace_id=? and item_id=?",title,id,Timestamp.from(now),ws,itemId);
        var content=map("fields",fields,"blocks",blocks,"documentId",str(body.get("documentId")),"parserVersion",str(body.get("parserVersion")),"completeness",body.getOrDefault("completeness","UNKNOWN"));
        content.put("sourceFixture",sourceFixture);
        content.put("extraction",extraction);
        content.put("needsReview",needsReview);
        if(needsReview)content.put("pendingReason","未确认SKU，请核对来源后重新提取");
        String manifest=encoded(map("images",images));
        db.update("insert into aden_collection_snapshot(workspace_id,snapshot_id,item_id,core_task_id,request_hash,actor_id,generation,source,content_json,manifest_json,status,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",ws,id,itemId,taskId,hash,p.userId(),generation,manual?"MANUAL":"JD",encoded(content),manifest,"SAVING",Timestamp.from(now));
        db.update("insert into aden_collection_manifest(workspace_id,snapshot_id,version,manifest_json,created_at) values(?,?,1,?,?)",ws,id,manifest,Timestamp.from(now));
        if(existing==null) db.update("insert into aden_collection_curation(workspace_id,item_id,revision,content_json,created_at) values(?,?,0,?,?)",ws,itemId,encoded(map("revision",0,"notes",str(body.get("notes")),"tags",List.of(),"overrides",Map.of(),"removedImageIds",List.of(),"imageOrder",List.of(),"mainImageId","","snapshotId",id)),Timestamp.from(now));
        return snapshot(findSnapshot(ws,id));
    }
    public Map<String,Object> captureStatus(AdenOperatorPrincipal p,String ws,String id) { access(p,ws,"read",false); return snapshot(findSnapshot(ws,id)); }
    public Map<String,Object> upload(AdenOperatorPrincipal p,String ws,String captureId,String imageId,Map<String,Object> body) {
        var snap=findSnapshot(ws,captureId); access(p,ws,"MANUAL".equals(snap.get("source"))?"create":"capture",true);
        return uploadInternal(ws,captureId,imageId,body,false);
    }
    public Map<String,Object> uploadManualImage(AdenOperatorPrincipal p,String ws,String itemId,Map<String,Object> body) {
        access(p,ws,"edit",true); var item=item(ws,itemId);
        if(!str(item.get("snapshot_id")).equals(required(body,"snapshotId",36))) throw conflict("当前快照已变化，请重载商品后重试");
        return uploadInternal(ws,str(item.get("snapshot_id")),required(body,"imageId",128),body,true);
    }
    private Map<String,Object> uploadInternal(String ws,String captureId,String imageId,Map<String,Object> body,boolean manualAddition) {
        for(String field:List.of("generation","offset","totalSize","sha256","mimeType","dataBase64"))if(!body.containsKey(field) || body.get(field)==null)throw new IllegalArgumentException("分片缺少"+field);
        if(!imageId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("图片ID无效");
        var snap=findSnapshot(ws,captureId); var item=item(ws,str(snap.get("item_id")));
        requireLive(item,number(body.get("generation")));
        if(!manualAddition && number(snap.get("generation"))!=number(item.get("generation"))) throw conflict("采集代次已失效");
        if(!captureId.equals(item.get("snapshot_id"))) throw conflict("历史快照不可再上传，请采集新版本");
        var task=ledger.findTaskForUpdate(new AdenWorkspaceId(ws),new AdenTaskId(str(snap.get("core_task_id")))).orElseThrow(AdenNotFoundException::new).task();
        if(!manualAddition && (task.state()==AdenTaskState.CANCELED || task.state()==AdenTaskState.CANCEL_REQUESTED || task.state()==AdenTaskState.FAILED)) throw conflict("采集任务已终止");
        long offset=number(body.get("offset")), total=number(body.get("totalSize"));
        String hash=required(body,"sha256",64), mime=required(body,"mimeType",64);
        if(offset<0 || total<1 || total>10*1024*1024 || !hash.matches("[0-9a-f]{64}") || !Set.of("image/png","image/jpeg","image/webp").contains(mime)) throw new IllegalArgumentException("图片资源参数不合法");
        String base64=required(body,"dataBase64",180000); byte[] bytes=Base64.getDecoder().decode(base64);
        if(bytes.length==0 || bytes.length>128*1024 || offset+bytes.length>total) throw new IllegalArgumentException("分片大小不合法");
        var manifest=objects(decode(snap.get("manifest_json")).get("images"));
        var image=manifest.stream().filter(i->imageId.equals(i.get("imageId"))).findFirst().orElse(null);
        if(manualAddition && image!=null && !"MANUAL".equals(image.get("source"))) throw new IllegalArgumentException("人工附件必须使用新的图片ID，不能冒充采集图片");
        if(image==null) {
            if(!manualAddition && !"MANUAL".equals(snap.get("source"))) throw new IllegalArgumentException("图片不在捕获清单中");
            if(manifest.size()>=50) throw new IllegalArgumentException("每次最多50张图片");
            image=map("imageId",imageId,"group","manual","order",manifest.size(),"source","MANUAL","status","PENDING"); manifest.add(image);
        }
        if(Boolean.TRUE.equals(image.get("sourceDenied"))) throw new IllegalArgumentException("图片来源未批准，拒绝上传");
        var upload=db.one("select * from aden_collection_upload where workspace_id=? and snapshot_id=? and image_id=?",ws,captureId,imageId);
        if(upload==null) {
            if(offset!=0) throw conflict("上传必须从offset=0开始");
            long used=number(db.one("select coalesce(sum(total_size),0) as n from aden_collection_upload where workspace_id=?",ws).get("n"));
            long captureBytes=number(db.one("select coalesce(sum(total_size),0) as n from aden_collection_upload where workspace_id=? and snapshot_id=?",ws,captureId).get("n"));
            if(used+total>quota || captureBytes+total>100L*1024*1024) throw conflict("采集库或单次采集配额不足");
            String assetId=newId();
            db.update("insert into aden_collection_upload(workspace_id,snapshot_id,image_id,asset_id,total_size,received_size,sha256,mime_type) values(?,?,?,?,?,0,?,?)",ws,captureId,imageId,assetId,total,hash,mime);
            upload=db.one("select * from aden_collection_upload where workspace_id=? and snapshot_id=? and image_id=?",ws,captureId,imageId);
        }
        String assetId=str(upload.get("asset_id")); long received=number(upload.get("received_size"));
        if(total!=number(upload.get("total_size")) || !hash.equals(upload.get("sha256")) || !mime.equals(upload.get("mime_type"))) throw new AdenIdempotencyKeyReusedException();
        if(offset<received) {
            boolean matches=bool(upload.get("complete"))?files.matchesAsset(ws,hash,offset,bytes):files.matches(ws,assetId,offset,bytes);
            if(offset+bytes.length>received || !matches) throw new AdenIdempotencyKeyReusedException();
            return map("offset",received,"complete",bool(upload.get("complete")),"assetId",bool(upload.get("complete"))?assetId:null);
        }
        if(offset!=received) throw conflict("分片offset不连续");
        files.chunk(ws,assetId,offset,bytes); long next=offset+bytes.length; boolean complete=next==total;
        if(complete) {
            try { var info=files.finish(ws,assetId,hash,mime,total); image.put("width",info.width()); image.put("height",info.height()); image.put("thumbnailAssetId",assetId); }
            catch(RuntimeException failure) { if(received==0)files.discardTemporary(ws,assetId); throw failure; }
            files.discardTemporaryAfterCommit(ws,assetId);
            image.putAll(map("status","SAVED","assetId",assetId,"sha256",hash,"mimeType",mime,"size",total)); image.remove("error");
        }
        db.update("update aden_collection_upload set received_size=?,complete=? where workspace_id=? and snapshot_id=? and image_id=?",next,complete,ws,captureId,imageId);
        saveManifest(ws,snap,manifest);
        return map("offset",next,"complete",complete,"assetId",complete?assetId:null);
    }
    public Map<String,Object> complete(AdenOperatorPrincipal p,String ws,String id,Map<String,Object> body) {
        var snap=findSnapshot(ws,id); access(p,ws,"MANUAL".equals(snap.get("source"))?"create":"capture",true);
        snap=findSnapshot(ws,id); var item=item(ws,str(snap.get("item_id"))); requireLive(item,number(body.get("generation")));
        if(number(snap.get("generation"))!=number(item.get("generation"))) throw conflict("采集代次已失效");
        var images=objects(decode(snap.get("manifest_json")).get("images"));
        for(var failure:objects(body.get("failures"))) {
            String imageId=required(failure,"imageId",128); var image=images.stream().filter(i->imageId.equals(i.get("imageId"))).findFirst().orElseThrow(()->new IllegalArgumentException("未知图片"));
            if(!"SAVED".equals(image.get("status"))) image.putAll(map("status","FAILED","error",required(failure,"error",500)));
        }
        for(var image:images) if("PENDING".equals(image.get("status"))) image.putAll(map("status","FAILED","error","资源尚未保存"));
        saveManifest(ws,snap,images);
        String status=completedStatus(snap,images);
        db.update("update aden_collection_snapshot set status=? where workspace_id=? and snapshot_id=?",status,ws,id);
        var task=ledger.findTaskForUpdate(new AdenWorkspaceId(ws),new AdenTaskId(str(snap.get("core_task_id")))).orElseThrow(AdenNotFoundException::new).task();
        if(task.state()==AdenTaskState.RUNNING) {
            advance(task,AdenTaskActor.COLLECTOR,AdenTaskCommand.COMPLETE,p.userId());
            db.update("update aden_task set result_json=? where workspace_id=? and task_id=?",encoded(map("itemId",snap.get("item_id"),"snapshotId",id,"contentSaved",true)),ws,snap.get("core_task_id"));
        }
        else if(task.state()!=AdenTaskState.SUCCEEDED) throw conflict("任务已经取消或终止");
        audit(p,ws,task,"COLLECTION_SAVED",map("status",status));
        db.update("update aden_task_step set step_state='SUCCEEDED',finished_at=?,updated_at=?,version=version+1 where workspace_id=? and task_id=? and step_state='RUNNING'",Timestamp.from(Instant.now()),Timestamp.from(Instant.now()),ws,snap.get("core_task_id"));
        return snapshot(findSnapshot(ws,id));
    }
    private void saveManifest(String ws,Map<String,Object> snap,List<Map<String,Object>> images) {
        String value=encoded(map("images",images));
        if(fingerprint.hashJson(value).equals(fingerprint.hashJson(str(snap.get("manifest_json"))))) return;
        long next=number(snap.get("manifest_version"))+1;
        db.update("insert into aden_collection_manifest(workspace_id,snapshot_id,version,manifest_json,created_at) values(?,?,?,?,?)",ws,snap.get("snapshot_id"),next,value,Timestamp.from(Instant.now()));
        db.update("update aden_collection_snapshot set manifest_json=?,manifest_version=? where workspace_id=? and snapshot_id=?",value,next,ws,snap.get("snapshot_id"));
        if(!"SAVING".equals(snap.get("status"))) db.update("update aden_collection_snapshot set status=? where workspace_id=? and snapshot_id=?",completedStatus(snap,images),ws,snap.get("snapshot_id"));
    }
    private String completedStatus(Map<String,Object> snap,List<Map<String,Object>> images) {
        var content=decode(snap.get("content_json"));
        return !Boolean.TRUE.equals(content.get("needsReview")) && !AdenCollectionExtraction.hasMissing(object(content.get("extraction")))
                && images.stream().allMatch(i->"SAVED".equals(i.get("status")))?"SAVED":"PARTIAL";
    }
    public Map<String,Object> curate(AdenOperatorPrincipal p,String ws,String id,Map<String,Object> body) {
        access(p,ws,"edit",true); var item=item(ws,id); requireLive(item,number(item.get("generation")));
        var current=curation(ws,id); long revision=number(current.get("revision"));
        if(!body.containsKey("revision"))throw new AdenApplicationException("ADEN_PRECONDITION_REQUIRED","需要人工修订revision");
        if(number(body.get("revision"))!=revision) throw new AdenVersionConflictException(Long.toString(number(body.get("revision"))),Long.toString(revision));
        if(encoded(body).length()>32768) throw new IllegalArgumentException("人工补充内容超限");
        var result=new LinkedHashMap<>(body); result.keySet().retainAll(Set.of("notes","tags","overrides","removedImageIds","mainImageId","imageOrder","snapshotId"));
        if(str(result.get("notes")).length()>5000)throw new IllegalArgumentException("备注超长");
        List<Object> tags=listValue(result.get("tags")); if(tags.size()>50 || tags.stream().anyMatch(t->!(t instanceof String)||str(t).length()>100))throw new IllegalArgumentException("标签无效或超限");
        var overrides=object(result.get("overrides")); overrides.keySet().retainAll(Set.of("title","price","priceText","specification","shop","parameters","stockText","deliveryText","promotionText")); result.put("overrides",overrides);
        String snapshotId=str(result.getOrDefault("snapshotId",item.get("snapshot_id")));
        var snapshot=findSnapshot(ws,snapshotId); if(!id.equals(snapshot.get("item_id"))) throw new AdenNotFoundException();
        Set<String> valid=new HashSet<>(); objects(decode(snapshot.get("manifest_json")).get("images")).forEach(i->valid.add(str(i.get("imageId"))));
        for(String field:List.of("removedImageIds","imageOrder")) for(Object value:listValue(result.get(field))) if(!valid.contains(str(value))) throw new IllegalArgumentException("图片不属于所选快照");
        if(!str(result.get("mainImageId")).isEmpty() && !valid.contains(str(result.get("mainImageId")))) throw new IllegalArgumentException("主图不属于所选快照");
        if(listValue(result.get("removedImageIds")).contains(result.get("mainImageId")))result.put("mainImageId","");
        validatePrice(object(result.get("overrides")).get("price")); result.put("snapshotId",snapshotId); result.put("revision",revision+1);
        db.update("insert into aden_collection_curation(workspace_id,item_id,revision,content_json,created_at) values(?,?,?,?,?)",ws,id,revision+1,encoded(result),Timestamp.from(Instant.now()));
        audit(p,ws,null,"COLLECTION_CURATED",map("itemId",id,"revision",revision+1)); return result;
    }
    public Map<String,Object> trash(AdenOperatorPrincipal p,String ws,List<String> ids,boolean restore) {
        access(p,ws,restore?"restore":"delete",true); if(ids.isEmpty() || ids.size()>100) throw new IllegalArgumentException("选择1至100项");
        var results=new ArrayList<Map<String,Object>>();
        for(String id:new LinkedHashSet<>(ids)) {
            var item=db.one("select * from aden_collection_item where workspace_id=? and item_id=?",ws,id);
            if(item==null) { results.add(map("itemId",id,"success",false,"error","记录不存在或不可访问")); continue; }
            if(bool(item.get("deleted"))!=!restore) {
                db.update("update aden_collection_item set deleted=?,generation=generation+1,version=version+1,updated_at=? where workspace_id=? and item_id=?",!restore,Timestamp.from(Instant.now()),ws,id);
                if(!restore) for(var snap:db.rows("select * from aden_collection_snapshot where workspace_id=? and item_id=?",ws,id)) {
                    var task=ledger.findTaskForUpdate(new AdenWorkspaceId(ws),new AdenTaskId(str(snap.get("core_task_id")))).orElseThrow(AdenNotFoundException::new).task();
                    if(task.state()==AdenTaskState.RUNNING) { task=advance(task,AdenTaskActor.OPERATOR,AdenTaskCommand.REQUEST_CANCEL,p.userId()); advance(task,AdenTaskActor.COORDINATOR,AdenTaskCommand.CONFIRM_CANCELED,p.userId()); db.update("update aden_task_step set step_state='CANCELED',finished_at=?,updated_at=?,version=version+1 where workspace_id=? and task_id=? and step_state='RUNNING'",Timestamp.from(Instant.now()),Timestamp.from(Instant.now()),ws,task.id().value()); }
                }
                audit(p,ws,null,restore?"COLLECTION_RESTORED":"COLLECTION_TRASHED",map("itemId",id));
            }
            results.add(map("itemId",id,"success",true));
        } return map("results",results);
    }
    public Binary asset(AdenOperatorPrincipal p,String ws,String assetId) {
        return asset(p,ws,assetId,false);
    }
    public Binary asset(AdenOperatorPrincipal p,String ws,String assetId,boolean thumbnail) {
        access(p,ws,"read",false);
        ledger.lockWorkspaceEventSequence(new AdenWorkspaceId(ws));
        var upload=db.one("select u.*,s.item_id from aden_collection_upload u join aden_collection_snapshot s on s.workspace_id=u.workspace_id and s.snapshot_id=u.snapshot_id where u.workspace_id=? and u.asset_id=? and u.complete=true",ws,assetId);
        if(upload==null) throw new AdenNotFoundException(); var item=item(ws,str(upload.get("item_id"))); if(bool(item.get("deleted"))) throw new AdenNotFoundException();
        return new Binary(files.read(ws,upload.get("sha256")+(thumbnail?".thumb.png":".asset")),thumbnail?"image/png":str(upload.get("mime_type")),assetId+(thumbnail?".png":""));
    }
    public Map<String,Object> export(AdenOperatorPrincipal p,String ws,Map<String,Object> body) {
        access(p,ws,"export",true); List<Object> ids=listValue(body.get("itemIds"));
        if(ids.isEmpty() || ids.size()>100) throw new IllegalArgumentException("一次导出选择1至100个商品");
        String format=required(body,"format",8); if(!Set.of("XLSX","ZIP").contains(format)) throw new IllegalArgumentException("导出格式无效");
        var selections=new ArrayList<Map<String,Object>>(); long assetBudget=0;
        var versions=object(body.get("snapshotIds"));
        for(Object value:new LinkedHashSet<>(ids)) {
            String id=str(value); var item=item(ws,id); requireLive(item,number(item.get("generation")));
            String snapshotId=str(versions.getOrDefault(id,item.get("snapshot_id"))); var snap=findSnapshot(ws,snapshotId);
            if(!id.equals(snap.get("item_id"))) throw new AdenNotFoundException();
            var curation=curation(ws,id); var selection=map("itemId",id,"generation",item.get("generation"),"snapshot",snapshot(snap),"curation",curation);
            selections.add(selection);
            for(var image:objects(snapshot(snap).get("images"))) if("SAVED".equals(image.get("status"))) assetBudget+=number(image.get("size"));
        }
        if(assetBudget>100L*1024*1024) throw new IllegalArgumentException("导出图片超过100MiB，请减少选择");
        byte[] bytes=AdenCollectionExport.build(format,selections,(assetId)->asset(p,ws,assetId).bytes());
        String id=newId(), name="aden-collection-"+id+ (format.equals("ZIP")?".zip":".xlsx");
        String mime=format.equals("ZIP")?"application/zip":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        files.write(ws,id+".export",bytes);
        db.update("insert into aden_collection_export(workspace_id,export_id,selection_json,file_name,mime_type,byte_size,created_at) values(?,?,?,?,?,?,?)",ws,id,encoded(selections),name,mime,bytes.length,Timestamp.from(Instant.now()));
        audit(p,ws,null,"COLLECTION_EXPORTED",map("exportId",id,"count",selections.size())); return map("exportId",id,"fileName",name,"mimeType",mime,"size",bytes.length);
    }
    public Binary download(AdenOperatorPrincipal p,String ws,String id) {
        access(p,ws,"export",true);
        var row=db.one("select * from aden_collection_export where workspace_id=? and export_id=?",ws,id); if(row==null) throw new AdenNotFoundException();
        for(var selection:objects(json.readValue(str(row.get("selection_json")),List.class))) requireLive(item(ws,str(selection.get("itemId"))),number(selection.get("generation")));
        return new Binary(files.read(ws,id+".export"),str(row.get("mime_type")),str(row.get("file_name")));
    }

    @Transactional(transactionManager="adenTransactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> exports(AdenOperatorPrincipal p,String ws,int page,int pageSize) {
        var member=access(p,ws,"export",false);
        if(page<1 || page>100000 || pageSize<1 || pageSize>100)throw new IllegalArgumentException("导出分页参数无效");
        Object total=db.one("select count(*) as n from aden_collection_export where workspace_id=?",ws).get("n");
        var rows=db.rows("select * from aden_collection_export where workspace_id=? order by created_at desc,export_id desc limit ? offset ?",ws,pageSize,(page-1)*pageSize);
        boolean canDownload=member.role()!=com.ruoyi.aden.domain.workspace.AdenWorkspaceRole.VIEWER;
        return map("items",rows.stream().map(row->exportRecord(ws,row,canDownload,false)).toList(),"total",total,"page",page,"pageSize",pageSize);
    }

    @Transactional(transactionManager="adenTransactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> exportDetail(AdenOperatorPrincipal p,String ws,String id) {
        var member=access(p,ws,"export",false);
        var row=db.one("select * from aden_collection_export where workspace_id=? and export_id=?",ws,uuid(id));
        if(row==null)throw new AdenNotFoundException();
        return exportRecord(ws,row,member.role()!=com.ruoyi.aden.domain.workspace.AdenWorkspaceRole.VIEWER,true);
    }

    private Map<String,Object> exportRecord(String ws,Map<String,Object> row,boolean canDownload,boolean detail) {
        var selections=objects(json.readValue(str(row.get("selection_json")),List.class));
        boolean live=true; long saved=0,missing=0,removedCount=0;
        for(var selection:selections) {
            var current=db.one("select * from aden_collection_item where workspace_id=? and item_id=?",ws,selection.get("itemId"));
            if(current==null || bool(current.get("deleted")) || number(current.get("generation"))!=number(selection.get("generation")))live=false;
            var snapshot=object(selection.get("snapshot")); var curation=object(selection.get("curation"));
            Set<Object> removed=Objects.equals(curation.get("snapshotId"),snapshot.get("snapshotId"))?new HashSet<>(listValue(curation.get("removedImageIds"))):Set.of();
            for(var image:objects(snapshot.get("images"))) {
                if(removed.contains(image.get("imageId")))removedCount++;
                else if("SAVED".equals(image.get("status")))saved++;
                else missing++;
            }
        }
        boolean file=files.exists(ws,str(row.get("export_id"))+".export");
        String reason=!live?"商品状态已变化，请重新生成导出":!file?"导出文件已不可用，请重新生成":!canDownload?"当前角色仅可查看导出记录":"";
        var result=map("exportId",row.get("export_id"),"fileName",row.get("file_name"),"mimeType",row.get("mime_type"),
                "size",number(row.get("byte_size")),"format","application/zip".equals(row.get("mime_type"))?"ZIP":"XLSX",
                "status",live&&file?"COMPLETED":"UNAVAILABLE","createdAt",time(row.get("created_at")),"itemCount",selections.size(),
                "imageSaved",saved,"imageMissing",missing,"imageRemoved",removedCount,"downloadAvailable",live&&file&&canDownload);
        if(!reason.isBlank())result.put("unavailableReason",reason);
        if(detail)result.put("selections",selections.stream().map(selection->{
            var snapshot=object(selection.get("snapshot"));
            return map("itemId",selection.get("itemId"),"snapshotId",snapshot.get("snapshotId"),
                    "title",object(snapshot.get("fields")).get("title"),"createdAt",snapshot.get("createdAt"),
                    "curationRevision",str(object(selection.get("curation")).get("revision")),"assetManifestVersion",str(snapshot.get("assetManifestVersion")));
        }).toList());
        return result;
    }
    private void createTask(AdenOperatorPrincipal p,String ws,String taskId,String title,String captureId,boolean manual,Instant now) {
        var workspace=new AdenWorkspaceId(ws); String safeTitle=title.replace('\n',' ').replace('\r',' '); if(safeTitle.length()>120) safeTitle=safeTitle.substring(0,120);
        AdenTask task=new AdenTask(workspace,new AdenTaskId(taskId),manual?AdenTaskType.MANUAL_COLLECTION_ENTRY:AdenTaskType.JD_DETAIL_CAPTURE,AdenCapabilityCode.COL,safeTitle,AdenTaskState.DRAFT,new AdenTaskVersion(1),new AdenCorrelationId(newId()),p.userId(),now,now);
        ledger.insertTask(task,encoded(map("captureId",captureId,"source",manual?"MANUAL":"JD")),new AdenIdempotencyKey("collection:"+captureId),fingerprint.hashValue(map("captureId",captureId)));
        ledger.insertStep(workspace,task.id(),new AdenTaskStep(new AdenTaskStepId(newId()),1,1,AdenTaskStepState.PENDING,0),encoded(map("captureId",captureId)),now);
        event(task,"aden.task.created.v1",p.userId(),map("to","DRAFT"));
        task=advance(task,AdenTaskActor.OPERATOR,AdenTaskCommand.SUBMIT_FOR_VALIDATION,p.userId());
        task=advance(task,AdenTaskActor.VALIDATOR,AdenTaskCommand.VALIDATION_PASSED,p.userId());
        task=advance(task,AdenTaskActor.COLLECTOR,AdenTaskCommand.START,p.userId());
        db.update("update aden_task_step set step_type='ATTENDED_COLLECTION',step_state='RUNNING',started_at=?,updated_at=?,version=version+1 where workspace_id=? and task_id=?",Timestamp.from(now),Timestamp.from(now),ws,taskId);
        audit(p,ws,task,"COLLECTION_CAPTURED",map("captureId",captureId));
    }
    private AdenTask advance(AdenTask task,AdenTaskActor actor,AdenTaskCommand command,long user) {
        var transition=task.transition(actor,command,Instant.now()); cas.updateState(task,transition.task());
        event(transition.task(),"aden.task.state-changed.v1",user,map("from",task.state().name(),"to",transition.task().state().name(),"reasonCode",command.name())); return transition.task();
    }
    private void event(AdenTask task,String type,long user,Object data) {
        long seq=ledger.lockWorkspaceEventSequence(task.workspaceId()); Instant now=Instant.now(); String eventId=newId();
        ledger.advanceWorkspaceEventSequence(task.workspaceId(),seq,seq+1,now);
        ledger.insertEvent(new AdenTaskLedgerRepository.LedgerEvent(task.workspaceId(),eventId,seq+1,task.id(),task.version().value(),type,encoded(data),"SYSTEM",Long.toString(user),task.correlationId().value(),now));
        ledger.insertOutbox(new AdenTaskLedgerRepository.OutboxMessage(task.workspaceId(),newId(),eventId,"OPERATOR_SSE",now));
    }
    private void audit(AdenOperatorPrincipal p,String ws,AdenTask task,String action,Object data) {
        if(task!=null) ledger.insertAudit(new AdenTaskLedgerRepository.TaskAudit(new AdenWorkspaceId(ws),newId(),action,task.id(),"OPERATOR",Long.toString(p.userId()),newId(),encoded(data),Instant.now()));
        else db.update("insert into aden_audit_event(workspace_id,audit_event_id,scope_type,action_code,resource_type,resource_id,actor_type,actor_id,correlation_id,outcome,details_json,occurred_at,created_at) values(?,?,'WORKSPACE',?,'COLLECTION',?,'OPERATOR',?,?,'SUCCEEDED',?,?,?)",ws,newId(),action,ws,Long.toString(p.userId()),newId(),encoded(data),Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
    }
    private Map<String,Object> item(String ws,String id) { var row=db.one("select * from aden_collection_item where workspace_id=? and item_id=?",ws,id); if(row==null) throw new AdenNotFoundException(); return row; }
    private Map<String,Object> findSnapshot(String ws,String id) { var row=db.one("select * from aden_collection_snapshot where workspace_id=? and snapshot_id=?",ws,id); if(row==null) throw new AdenNotFoundException(); return row; }
    private Map<String,Object> curation(String ws,String id) { var row=db.one("select content_json from aden_collection_curation where workspace_id=? and item_id=? order by revision desc limit 1",ws,id); return row==null?map("revision",0):decode(row.get("content_json")); }
    private void requireLive(Map<String,Object> item,long generation) { if(bool(item.get("deleted")) || number(item.get("generation"))!=generation) throw conflict("商品已删除或保存代次失效"); }
    private Map<String,Object> decode(Object value) { return object(json.readValue(str(value),Map.class)); }
    private String encoded(Object value) { return json.writeValueAsString(value); }
    public static Map<String,Object> object(Object value) { if(value==null) return new LinkedHashMap<>(); if(!(value instanceof Map<?,?> m)) throw new IllegalArgumentException("需要JSON对象"); var out=new LinkedHashMap<String,Object>(); m.forEach((k,v)->out.put(str(k),v)); return out; }
    public static List<Object> listValue(Object value) { if(value==null) return List.of(); if(!(value instanceof List<?> l)) throw new IllegalArgumentException("需要JSON数组"); return new ArrayList<>(l); }
    public static List<Map<String,Object>> objects(Object value) { return listValue(value).stream().map(AdenCollectionService::object).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
    public static Map<String,Object> map(Object... values) { var m=new LinkedHashMap<String,Object>(); for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]); return m; }
    public static String str(Object value) { return value==null?"":value.toString(); }
    private static String required(Map<String,Object> m,String key,int max) { String value=str(m.get(key)); if(value.isBlank() || value.length()>max) throw new IllegalArgumentException(key+"缺失或超长"); return value; }
    public static long number(Object value) { if(value==null) return 0; try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("必须为整数",e);} }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    private static String time(Object value) { return value instanceof Timestamp t?t.toInstant().toString():str(value); }
    private static String uuid(String value) { if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("UUID无效");return value; }
    private static String newId() { return UUID.randomUUID().toString(); }
    private boolean isFixture(URI uri) { return !fixtureOrigin.isEmpty() && fixtureOrigin.equals(uri.getScheme()+"://"+uri.getRawAuthority()) && uri.getRawUserInfo()==null; }
    private static AdenApplicationException conflict(String message) { return new AdenApplicationException("ADEN_STATE_TRANSITION_DENIED",message); }
    public static void validatePrice(Object value) { if(value==null || str(value).isBlank())return; var price=new BigDecimal(str(value)); if(price.signum()<0 || price.scale()>4 || price.precision()>18)throw new IllegalArgumentException("价格必须为非负十进制且最多4位小数"); }
    public record Binary(byte[] bytes,String mimeType,String fileName) { }
}
