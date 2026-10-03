package com.ruoyi.aippt.migration;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.DateTimeException;
import java.util.*;
/** 只消费经离线核账的清单；逐项目事务、批次检查点和保护后续写入的撤回。 */
public final class AipptLegacyImportCli  {
    private static final Set<String> TABLES=Set.of("project","source","source_section","outline","slide","asset","audit");
    private static final Set<String> SECRETS=Set.of("password","hashedpassword","passwordhash","token","accesstoken","refreshtoken","jwtsecret","llmapikey","imageapikey","unsplashaccesskey","imageworkspaceid","cossecretid","cossecretkey","workerkey","apikey","accesskey","secret","secretid","secretkey");
    public record Row(String table,JSONObject body)  {
    }
    public record ObjectCopy(String key,Path source,String mime,String sha256,long size)  {
    }
    public record ProjectPlan(String id,String checkpoint,List<Row> rows,List<ObjectCopy> objects)  {
    }
    public record Plan(String batch,String sourceHash,List<ProjectPlan> projects)  {
    }
    private AipptLegacyImportCli()  {
    }
    public static void main(String[] args)throws Exception  {
        Map<String,String> options=options(args);
        Path manifest=Path.of(required(options,"--manifest")).toRealPath();
        Path root=Path.of(required(options,"--object-root")).toRealPath();
        AipptProperties properties=new AipptProperties();
        CatalogService catalog=new CatalogService(properties);
        catalog.verifyManifest();
        JSONObject owners=JSON.parseObject(Files.readString(Path.of(required(options,"--owners"))));
        JSONObject manifestBody=JSON.parseObject(Files.readString(manifest));
        byte[] sourceSnapshot=Files.readAllBytes(Path.of(required(options,"--source-snapshot")));
        AipptRules.require(AssetService.bytesHash(sourceSnapshot).equals(manifestBody.getString("source_sha256")),"源快照与离线清单哈希不同");
        Plan plan=prepare(manifestBody,root,catalog,owners);
        if(!options.containsKey("--apply")&&!options.containsKey("--rollback"))  {
            System.out.println("清单校验通过：batch="+plan.batch()+"，项目="+plan.projects().size()+"；未连接数据库或复制对象。");
            return;
        }
        AipptSchemaCli.requireGate("AIPPT_IMPORT_ENABLED");
        if(options.containsKey("--apply")&&options.containsKey("--rollback"))throw new IllegalArgumentException("apply与rollback不能并用");
        try(Connection connection=AipptSchemaCli.connect())  {
            if(!AipptSchemaCli.tableExists(connection,"aippt_project")||!AipptSchemaCli.tableExists(connection,"aippt_schema_history"))throw new IllegalStateException("先审核并显式应用独立迁移，导入不能创建表或自动baseline");
            if(options.containsKey("--rollback"))rollback(connection,plan);
            else  {
                try(AutoCloseStorage storage=storage())  {
                    for(ProjectPlan project:plan.projects())apply(connection,storage.port,plan,project,manifest.resolveSibling("aippt-import-compensation.jsonl"));
                }
            }
        }
    }
    public static Plan prepare(JSONObject manifest,Path objectRoot,CatalogService catalog,JSONObject owners)throws Exception  {
        rejectSecrets(manifest);
        rejectSecrets(owners);
        AipptRules.require("aippt-import-1".equals(manifest.getString("schema_version"))&&manifest.getBooleanValue("complete"),"清单版本无效或核账未完整通过");
        AipptRules.require(catalog.version().equals(manifest.getString("catalog_version")),"导入目录版本不同");
        String batch=uuid(manifest.getString("import_batch"));
        String hash=manifest.getString("source_sha256");
        AipptRules.require(hash!=null&&hash.matches("[a-f0-9]{64}"),"源快照哈希无效");
        Path root=objectRoot.toRealPath();
        List<ProjectPlan> plans=new ArrayList<>();
        Set<String> projectIds=new HashSet<>();
        JSONArray entries=manifest.getJSONArray("projects");
        AipptRules.require(entries!=null&&!entries.isEmpty(),"导入项目为空");
        for(Object raw:entries)  {
            JSONObject entry=(JSONObject)raw,project=AipptRules.copy(entry.getJSONObject("project"));
            rejectSecrets(project);
            String id=uuid(project.getString("id"));
            AipptRules.require(projectIds.add(id),"重复项目ID");
            long owner=ownerId(project.get("owner_id"));
            AipptRules.require(owner>0,"项目没有明确若依owner映射");
            String legacyOwner=project.getString("legacy_owner_id");
            AipptRules.require(legacyOwner!=null&&ownerId(owners.get(legacyOwner))==owner,"项目owner与显式owner映射不同");
            AipptRules.require(batch.equals(project.getString("import_batch")),"项目与清单批次不同");
            JSONArray sources=project.getJSONArray("sources"),slides=project.getJSONArray("slides");
            if(sources==null)sources=new JSONArray();
            if(slides==null)slides=new JSONArray();
            AipptRules.require(slides.size()<=40,"人工页面最多40页");
            Map<String,String> assetIds=new HashMap<>();
            List<ObjectCopy> copies=new ArrayList<>();
            List<Row> rows=new ArrayList<>();
            JSONArray assets=entry.getJSONArray("assets");
            if(assets==null)assets=new JSONArray();
            for(Object item:assets)  {
                JSONObject asset=(JSONObject)item;
                String oldId=uuid(asset.getString("asset_id"));
                String newId=UUID.nameUUIDFromBytes((id+":"+oldId).getBytes(StandardCharsets.UTF_8)).toString();
                AipptRules.require(assetIds.put(oldId,newId)==null,"重复资产ID");
                Path path=Path.of(asset.getString("path")).toRealPath();
                AipptRules.require(path.startsWith(root),"导入对象越出明确的对象根目录");
                long size=Files.size(path);
                AipptRules.require(size>0&&size<=100*1024*1024L&&size==asset.getLongValue("size"),"资产大小不符");
                byte[] bytes=Files.readAllBytes(path);
                AipptRules.require(AssetService.bytesHash(bytes).equals(asset.getString("sha256")),"资产字节哈希不符");
                String filename=path.getFileName().toString(),role="image";
                for(Object sourceRaw:sources)  {
                    JSONObject source=(JSONObject)sourceRaw;
                    if(oldId.equals(source.getString("asset_id")))  {
                        role="source";
                        filename=source.getString("filename");
                    }
                }
                String mime=AssetService.validate(bytes,filename,role);
                AipptRules.require(mime.equals(asset.getString("mime")),"资产真实类型与清单不同");
                JSONObject center=base(id,owner,"ready",newId,1,batch,hash);
                center.putAll(JSONObject.of("object_key",id+"/"+newId,"filename",filename,"mime",mime,"size",size,"sha256",asset.getString("sha256"),"role",role,"ref_count",0));
                rows.add(new Row("asset",center));
                copies.add(new ObjectCopy(center.getString("object_key"),path,mime,asset.getString("sha256"),size));
            }
            rewriteAssets(project,assetIds);
            JSONObject outline=project.getJSONObject("outline");
            project.remove("sources");
            project.remove("slides");
            project.remove("outline");
            project.remove("user_id");
            project.remove("owner_id");
            businessBase(project,id,owner,project.getString("status")==null?"draft":project.getString("status"),id,Math.max(1,project.getLongValue("revision")),batch,hash);
            project.putIfAbsent("owner_dept_id",0);
            project.putIfAbsent("input_revision",1);
            project.putIfAbsent("theme_revision",1);
            project.putIfAbsent("deck_revision",1);
            Map<String,Object> defaults=Map.of("title","","tone","professional","content_density","medium","page_count",slides.isEmpty()?10:slides.size(),"layout_mode","flex","theme_id","ivory");
            defaults.forEach((key,value)->{if(!project.containsKey(key))project.put(key,value);});
            if(!project.containsKey("audience"))project.put("audience",null);
            AipptRules.project(project,false);
            catalog.theme(project.getString("theme_id"));
            Set<String> refs=new HashSet<>(),ids=new HashSet<>();
            for(Object sourceRaw:sources)  {
                JSONObject source=AipptRules.copy((JSONObject)sourceRaw);
                String sourceId=uuid(source.getString("id"));
                AipptRules.require(ids.add(sourceId),"重复材料ID");
                businessBase(source,id,owner,source.getString("parse_status")==null?"ready":source.getString("parse_status"),sourceId,Math.max(1,source.getLongValue("revision")),batch,hash);
                source.putIfAbsent("parse_status","ready");
                source.putIfAbsent("parser_version","legacy-import-1");
                JSONArray sections=source.getJSONArray("sections");
                if(sections==null)sections=new JSONArray();
                for(Object sectionRaw:sections)  {
                    JSONObject section=AipptRules.copy((JSONObject)sectionRaw);
                    String sectionId=uuid(section.getString("id"));
                    AipptRules.require(refs.add(sectionId),"重复稳定小节ID");
                    AipptRules.text(section.getString("text"),0,200000,"小节正文");
                    section.put("ref",sectionId);
                    businessBase(section,id,owner,"ready",sectionId,1,batch,hash);
                    section.put("source_id",sourceId);
                    rows.add(new Row("source_section",section));
                }
                rows.add(new Row("source",source));
                source.putIfAbsent("char_count",sections.stream().map(JSONObject.class::cast).mapToInt(s->s.getString("text")==null?0:s.getString("text").length()).sum());
            }
            if(outline!=null&&!outline.isEmpty())  {
                String oldStatus=outline.getString("status");
                if(oldStatus==null)oldStatus="draft";
                AipptRules.require(Set.of("draft","confirmed","failed","generating").contains(oldStatus),"旧提纲状态无效");
                JSONArray pages=AipptRules.array(outline,"pages");
                boolean previouslyConfirmed="confirmed".equals(oldStatus)||"confirmed".equals(outline.getString("legacy_status"));
                AipptRules.require(pages.isEmpty()&&!previouslyConfirmed||pages.size()>=1&&pages.size()<=40,"旧提纲页数无效");
                if(!pages.isEmpty()) {
                    AipptRules.outline(pages,refs,1,40);
                    if(previouslyConfirmed)AipptRules.require(pages.size()==project.getIntValue("page_count"),"旧确认提纲页数须与项目页数相同");
                }
                if(pages!=null)for(Object pageRaw:pages)  {
                    JSONObject page=(JSONObject)pageRaw;
                    catalog.layout(page.getString("layout_id"));
                    for(Object ref:page.getJSONArray("source_refs"))AipptRules.require(refs.contains(ref.toString()),"提纲小节引用缺失");
                }
                String outlineId=outline.getString("id");
                if(outlineId==null)outlineId=UUID.nameUUIDFromBytes((id+":outline").getBytes(StandardCharsets.UTF_8)).toString();
                if(Set.of("confirmed","generating").contains(oldStatus))outline.put("legacy_status",oldStatus);
                businessBase(outline,id,owner,"failed".equals(oldStatus)?"failed":"draft",uuid(outlineId),Math.max(1,outline.getLongValue("revision")),batch,hash);
                outline.put("requires_reconfirmation",!"failed".equals(oldStatus));
                outline.remove("input_signature");
                List<JSONObject> ready=rows.stream().filter(r->r.table().equals("source")&&"ready".equals(r.body().getString("parse_status"))).map(Row::body).toList();
                outline.put("input_signature",AipptRules.fingerprint(project,ready));
                outline.remove("job_id");
                rows.add(new Row("outline",outline));
            }
            Set<String> slideIds=new HashSet<>();
            List<JSONObject> ordered=slides.stream().map(JSONObject.class::cast).sorted(Comparator.comparingInt(s->s.getIntValue("position"))).toList();
            int position=0;
            for(JSONObject original:ordered)  {
                JSONObject slide=AipptRules.copy(original);
                String slideId=uuid(slide.getString("id"));
                AipptRules.require(slideIds.add(slideId),"重复页面ID");
                String legacyStatus=slide.getString("status");
                AipptRules.require(Set.of("pending","generating","failed","ready").contains(legacyStatus),"旧页面状态无效");
                if("generating".equals(legacyStatus))slide.put("legacy_status",legacyStatus);
                businessBase(slide,id,owner,"generating".equals(legacyStatus)?"pending":legacyStatus,slideId,Math.max(1,slide.getLongValue("revision")),batch,hash);
                slide.put("position",++position);
                slide.remove("active_task_id");
                slide.remove("job_id");
                JSONArray oldBlocks=AipptRules.array(slide,"blocks");
                boolean resumableStub=!"ready".equals(legacyStatus)&&oldBlocks.isEmpty()&&slide.get("layout_tree")==null;
                if(!resumableStub)  {
                    AipptRules.slide(slide);
                    JSONObject layout=catalog.layout(slide.getString("layout_id"));
                    if("fixed".equals(slide.getString("layout_mode")))validateSlots(slide,layout);
                    validateImages(slide,assetIds.values());
                }
                rows.add(new Row("slide",slide));
            }
            rows.add(0,new Row("project",project));
            JSONObject audit=base(id,owner,"ready",UUID.nameUUIDFromBytes((batch+":"+id+":audit").getBytes(StandardCharsets.UTF_8)).toString(),1,batch,hash);
            audit.put("action","legacy_import");
            audit.put("metadata",entry.getJSONObject("audit"));
            rows.add(new Row("audit",audit));
            // 资产引用计数从正式行重新计算，导入不信任旧元数据。
            for(Row row:rows)if(row.table().equals("asset"))  {
                String assetId=row.body().getString("id");
                long count=rows.stream().filter(r->!r.table().equals("asset")).filter(r->r.body().toJSONString().contains(assetId)).count();
                row.body().put("ref_count",count);
            }
            JSONObject checkpointEntry=AipptRules.copy(entry);
            if(checkpointEntry.getJSONArray("assets")!=null)for(Object assetRaw:checkpointEntry.getJSONArray("assets"))((JSONObject)assetRaw).remove("path");
            String checkpoint=AipptRules.hash(JSON.toJSONString(checkpointEntry,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
            project.put("import_checkpoint",checkpoint);
            plans.add(new ProjectPlan(id,checkpoint,List.copyOf(rows),List.copyOf(copies)));
        }
        return new Plan(batch,hash,List.copyOf(plans));
    }
    private static JSONObject base(String project,long owner,String status,String id,long revision,String batch,String sourceHash)  {
        String importedAt=Instant.now().toString();
        return JSONObject.of("id",id,"project_id",project,"owner_user_id",Long.toString(owner),"status",status==null?"pending":status,"revision",revision,"created_at",importedAt,"updated_at",importedAt,"imported_at",importedAt,"import_batch",batch,"import_source_sha256",sourceHash,"import_revision",revision);
    }
    /** 原业务时间保留原字符串和瞬间，迁入审计另记时间，不猜旧时区。 */
    private static void businessBase(JSONObject row,String project,long owner,String status,String id,long revision,String batch,String sourceHash) {
        JSONObject metadata=base(project,owner,status,id,revision,batch,sourceHash);
        for(String field:List.of("created_at","updated_at"))if(row.containsKey(field)) {
            importInstant(row.get(field));
            metadata.put(field,row.get(field));
        }
        row.putAll(metadata);
    }
    private static Instant importInstant(Object value) {
        AipptRules.require(value instanceof String,"导入日期必须是带时区的ISO字符串");
        try {
            Instant instant=OffsetDateTime.parse((String)value).toInstant();
            AipptRules.require(!instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))&&instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z")),"导入日期超出MySQL日期范围");
            return instant;
        } catch(DateTimeException invalid) {
            throw AipptException.invalid("导入日期无效或缺少明确时区");
        }
    }
    private static void validateImages(JSONObject slide,Collection<String> assets)  {
        for(Object raw:slide.getJSONArray("blocks"))  {
            JSONObject block=(JSONObject)raw;
            if("image".equals(block.getString("type"))&&block.getString("url")!=null)AipptRules.require(assets.contains(block.getString("url").substring("/aippt/assets/".length())),"页面引用未登记私有资产");
        }
    }
    private static void validateSlots(JSONObject slide,JSONObject layout)  {
        Set<String> used=new HashSet<>();
        for(Object raw:slide.getJSONArray("blocks"))  {
            JSONObject block=(JSONObject)raw;
            JSONObject slot=layout.getJSONArray("slots").stream().map(JSONObject.class::cast).filter(s->Objects.equals(s.getString("id"),block.getString("slot_id"))).findFirst().orElseThrow(()->AipptException.invalid("导入固定槽位不存在"));
            AipptRules.require(used.add(slot.getString("id"))&&slot.getJSONArray("accepts").contains(block.getString("type")),"导入固定槽位重复/类型不符");
        }
        for(Object raw:layout.getJSONArray("slots"))  {
            JSONObject slot=(JSONObject)raw;
            AipptRules.require(!slot.getBooleanValue("required")||used.contains(slot.getString("id")),"导入固定页缺必需槽位");
        }
    }
    private static void rewriteAssets(Object node,Map<String,String> ids)  {
        if(node instanceof JSONObject object)for(String key:new ArrayList<>(object.keySet()))  {
            Object value=object.get(key);
            if(value instanceof String text)  {
                if(key.equals("asset_id"))  {
                    AipptRules.require(ids.containsKey(text),"材料资产未登记");
                    object.put(key,ids.get(text));
                }
                else if(key.equals("url")&&text.startsWith("/aippt/assets/"))  {
                    String old=text.substring("/aippt/assets/".length());
                    AipptRules.require(ids.containsKey(old),"图片资产未登记");
                    object.put(key,"/aippt/assets/"+ids.get(old));
                }
            }
            else rewriteAssets(value,ids);
        }
        else if(node instanceof JSONArray array)for(Object value:array)rewriteAssets(value,ids);
    }
    private static void rejectSecrets(Object node)  {
        if(node instanceof JSONObject object)  {
            for(String key:object.keySet())  {
                String normalized=key.toLowerCase(Locale.ROOT).replace("_","").replace("-","");
                boolean credential=SECRETS.contains(normalized)||normalized.endsWith("apikey")||normalized.endsWith("accesstoken")||normalized.endsWith("refreshtoken")||normalized.endsWith("secretkey")||normalized.endsWith("secretid")||normalized.endsWith("password");
                AipptRules.require(!credential,"导入清单含禁止迁移的凭据字段");
                rejectSecrets(object.get(key));
            }
        }
        else if(node instanceof JSONArray array)for(Object item:array)rejectSecrets(item);
    }
    private static String uuid(String id)  {
        AipptRules.require(id!=null&&id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"),"导入ID不是UUID");
        return UUID.fromString(id).toString();
    }
    private static long ownerId(Object value)  {
        AipptRules.require(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger,"owner必须是JSON整数");
        java.math.BigInteger integer=new java.math.BigInteger(value.toString());
        AipptRules.require(integer.signum()>0&&integer.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))<=0,"owner超出正式用户ID范围");
        return integer.longValueExact();
    }
    static void apply(Connection connection,AipptStoragePort storage,Plan plan,ProjectPlan project,Path compensationLog)throws Exception  {
        JSONObject projectBody=project.rows().stream().filter(r->r.table().equals("project")).findFirst().orElseThrow().body();
        try(PreparedStatement owner=connection.prepareStatement("SELECT dept_id FROM sys_user WHERE user_id=? AND del_flag='0'"))  {
            owner.setLong(1,projectBody.getLongValue("owner_user_id"));
            try(ResultSet user=owner.executeQuery())  {
                AipptRules.require(user.next(),"明确映射的若依用户不存在或已删除");
                projectBody.put("owner_dept_id",user.getLong(1));
            }
        }
        if(checkpointExists(connection,plan,project))  {
            System.out.println("检查点已存在，跳过项目 "+project.id());
            return;
        }
        List<String> written=new ArrayList<>();
        List<Row> stagedRows=project.rows().stream().map(r->new Row(r.table(),AipptRules.copy(r.body()))).toList();
        try  {
            for(ObjectCopy object:project.objects())  {
                byte[] bytes=Files.readAllBytes(object.source());
                AipptRules.require(bytes.length==object.size()&&AssetService.bytesHash(bytes).equals(object.sha256()),"验证后对象字节发生变化");
                String stageKey=project.id()+"/"+UUID.randomUUID();
                journal(compensationLog,JSONObject.of("state","stage_started","import_batch",plan.batch(),"project_id",project.id(),"object_key",stageKey,"sha256",object.sha256(),"size",object.size(),"recorded_at",Instant.now().toString()));
                written.add(stageKey);
                storage.put(stageKey,bytes,object.mime());
                stagedRows.stream().filter(r->r.table().equals("asset")&&object.key().equals(r.body().getString("object_key"))).findFirst().orElseThrow().body().put("object_key",stageKey);
            }
            // 对象已在事务外暂存；中心 gate 防止两次导入并发提交同一项目。
            connection.setAutoCommit(false);
            try  {
                try(Statement gate=connection.createStatement())  {
                    gate.executeQuery("SELECT id FROM aippt_dispatch_gate WHERE id=1 FOR UPDATE").close();
                }
                if(checkpointExists(connection,plan,project))  {
                    connection.rollback();
                    compensate(storage,written,compensationLog,plan,project);
                    written.clear();
                    System.out.println("并发检查点已存在，跳过项目 "+project.id());
                    return;
                }
                for(Row row:stagedRows)insert(connection,row);
                connection.commit();
                List<String> committed=List.copyOf(written);
                written.clear();
                for(String key:committed)journal(compensationLog,JSONObject.of("state","committed","import_batch",plan.batch(),"project_id",project.id(),"object_key",key,"recorded_at",Instant.now().toString()));
                System.out.println("项目导入及核账检查点已提交 "+project.id());
            }
            catch(Exception failure)  {
                connection.rollback();
                throw failure;
            }
            finally  {
                connection.setAutoCommit(true);
            }
        }
        catch(Exception failure)  {
            compensate(storage,written,compensationLog,plan,project);
            throw failure;
        }
    }
    private static boolean checkpointExists(Connection connection,Plan plan,ProjectPlan project)throws SQLException  {
        try(PreparedStatement query=connection.prepareStatement("SELECT body FROM aippt_project WHERE id=?"))  {
            query.setString(1,project.id());
            try(ResultSet result=query.executeQuery())  {
                if(!result.next())return false;
                JSONObject existing=JSON.parseObject(result.getString(1));
                long owner=project.rows().get(0).body().getLongValue("owner_user_id");
                AipptRules.require(plan.batch().equals(existing.getString("import_batch"))&&plan.sourceHash().equals(existing.getString("import_source_sha256"))&&project.checkpoint().equals(existing.getString("import_checkpoint"))&&owner==existing.getLongValue("owner_user_id"),"项目导入检查点/owner/对象清单冲突");
                return true;
            }
        }
    }
    private static void compensate(AipptStoragePort storage,List<String> keys,Path log,Plan plan,ProjectPlan project)throws java.io.IOException  {
        for(String key:keys)try  {
            storage.delete(key);
        }
        catch(Exception failure)  {
            JSONObject pending=JSONObject.of("import_batch",plan.batch(),"project_id",project.id(),"object_key",key,"state","cleanup_pending","recorded_at",Instant.now().toString());
            journal(log,pending);
        }
    }
    private static void journal(Path log,JSONObject entry)throws java.io.IOException  {
        byte[] bytes=(entry.toJSONString()+System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try(var channel=java.nio.channels.FileChannel.open(log,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND))  {
            var buffer=java.nio.ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining())channel.write(buffer);
            channel.force(true);
        }
    }
    private static void insert(Connection connection,Row row)throws SQLException  {
        AipptRules.require(TABLES.contains(row.table()),"禁止导入表");
        JSONObject body=row.body();
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO aippt_"+row.table()+"(id,project_id,owner_user_id,status,revision,body,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"))  {
            insert.setString(1,body.getString("id"));
            insert.setString(2,body.getString("project_id"));
            insert.setLong(3,body.getLongValue("owner_user_id"));
            insert.setString(4,body.getString("status"));
            insert.setLong(5,body.getLongValue("revision"));
            insert.setString(6,JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue));
            Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            insert.setTimestamp(7,Timestamp.from(importInstant(body.get("created_at"))),utc);
            insert.setTimestamp(8,Timestamp.from(importInstant(body.get("updated_at"))),utc);
            insert.executeUpdate();
        }
    }
    static void rollback(Connection connection,Plan plan)throws SQLException  {
        connection.setAutoCommit(false);
        try  {
            for(ProjectPlan project:plan.projects())  {
                // 锁定项目后审核所有正式行；任一新行或 revision 变化均拒绝整个批次撤回。
                try(PreparedStatement lock=connection.prepareStatement("SELECT id FROM aippt_project WHERE id=? FOR UPDATE"))  {
                    lock.setString(1,project.id());
                    lock.executeQuery().close();
                }
                for(String table:List.of("project","source","source_section","outline","slide","asset","audit","task","proposal","export","snapshot","event","outbox","idempotency"))  {
                    try(PreparedStatement query=connection.prepareStatement("SELECT revision,body FROM aippt_"+table+" WHERE project_id=?"))  {
                        query.setString(1,project.id());
                        try(ResultSet result=query.executeQuery())  {
                            while(result.next())  {
                                JSONObject body=JSON.parseObject(result.getString(2));
                                AipptRules.require(plan.batch().equals(body.getString("import_batch"))&&plan.sourceHash().equals(body.getString("import_source_sha256"))&&result.getLong(1)==body.getLongValue("import_revision"),"导入后已有新写入，禁止批次撤回");
                            }
                        }
                    }
                }
            }
            for(ProjectPlan project:plan.projects())for(String table:TABLES)try(PreparedStatement delete=connection.prepareStatement("DELETE FROM aippt_"+table+" WHERE project_id=?"))  {
                delete.setString(1,project.id());
                delete.executeUpdate();
            }
            connection.commit();
            System.out.println("批次正式行已撤回；私有对象保留，按审核后的对象清单单独清理，避免不可逆误删。");
        }
        catch(RuntimeException|SQLException failure)  {
            connection.rollback();
            throw failure;
        }
        finally  {
            connection.setAutoCommit(true);
        }
    }
    private static AutoCloseStorage storage()  {
        String driver=System.getenv().getOrDefault("AIPPT_STORAGE_DRIVER","local");
        if(driver.equals("local"))return new AutoCloseStorage(new LocalAipptStorage(requiredEnv("AIPPT_STORAGE_PATH")));
        if(driver.equals("cos"))return new AutoCloseStorage(new CosAipptStorage(requiredEnv("AIPPT_COS_BUCKET"),requiredEnv("AIPPT_COS_REGION"),requiredEnv("AIPPT_COS_SECRET_ID"),requiredEnv("AIPPT_COS_SECRET_KEY")));
        throw new IllegalStateException("未知存储驱动");
    }
    private static final class AutoCloseStorage implements AutoCloseable  {
        final AipptStoragePort port;
        AutoCloseStorage(AipptStoragePort port)  {
            this.port=port;
        }
        public void close()  {
            if(port instanceof CosAipptStorage cos)cos.close();
        }
    }
    private static Map<String,String> options(String[] args)  {
        Map<String,String> values=new HashMap<>();
        for(int i=0;i<args.length;i++)  {
            String key=args[i];
            AipptRules.require(Set.of("--manifest","--object-root","--owners","--source-snapshot","--apply","--rollback").contains(key),"未知导入参数");
            boolean flag=key.equals("--apply")||key.equals("--rollback");
            AipptRules.require(flag||i+1<args.length,"导入参数缺少值");
            values.put(key,flag?"true":args[++i]);
        }
        return values;
    }
    private static String required(Map<String,String> options,String key)  {
        String value=options.get(key);
        if(value==null)throw new IllegalArgumentException("缺少 "+key);
        return value;
    }
    private static String requiredEnv(String key)  {
        String value=System.getenv(key);
        if(value==null||value.isBlank())throw new IllegalStateException("缺少环境变量 "+key);
        return value;
    }
}
