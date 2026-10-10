package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 一个项目锁串行短事务；不在事务内执行模型、解析或对象网络请求。 */
@Service @AipptEnabled public class AipptService  {
    private static final Set<String> FONT_TASK_TYPES=Set.of("generate_slide","ai_edit","relayout","quality","export");
    public static final String TASK_READ_PERMISSIONS="aippt:source:query,aippt:outline:query,aippt:deck:query,aippt:ai:edit,aippt:export:check,aippt:export:download,aippt:design:query";
    private final AipptRepository db;
    private final TransactionTemplate tx;
    private final AipptProperties props;
    private final CatalogService catalog;
    public AipptService(AipptRepository d,@Qualifier("aipptTransactions")TransactionTemplate t,AipptProperties p,CatalogService c)  {
        db=d;
        tx=t;
        props=p;
        catalog=c;
    }
    public JSONObject record(String project,long owner,String status)  {
        return JSONObject.of("id",UUID.randomUUID().toString(),"project_id",project,"owner_user_id",Long.toString(owner),"status",status,"revision",1,"created_at",Instant.now().toString(),"updated_at",Instant.now().toString());
    }
    public JSONObject owned(String p,long owner,boolean lock)  {
        JSONObject project=lock?db.lock("project",p):db.get("project",p);
        if(project==null||project.getLongValue("owner_user_id")!=owner||"deleted".equals(project.getString("status")))throw AipptException.missing();
        return project;
    }
    public JSONObject child(String t,String p,String id)  {
        JSONObject j=db.get(t,id);
        if(j==null||!p.equals(j.getString("project_id")))throw AipptException.missing();
        return j;
    }
    public JSONObject getProject(String p,long owner)  {
        return owned(p,owner,false);
    }
    public JSONObject list(long owner,String status,String q,int page,int size)  {
        AipptRules.require(page>0&&size>0&&size<=100,"分页参数无效");
        return JSONObject.of("rows",db.projects(owner,status,q,size,(page-1)*size),"total",db.count(owner,status,q));
    }
    public JSONObject command(String project,long owner,String key,String scope,JSONObject body,Function<JSONObject,JSONObject> operation)  {
        return command(project,owner,key,scope,body,operation,false);
    }
    private JSONObject command(String project,long owner,String key,String scope,JSONObject body,Function<JSONObject,JSONObject> operation,boolean sampleExport) {
        AipptRules.text(key,1,128,"Idempotency-Key");
        String id=AipptRules.hash(owner+":"+scope+":"+key).substring(0,36),digest=AipptRules.hash(JSON.toJSONString(body,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
        return tx.execute(s->  {
            if(project==null)db.lockDispatch();
            JSONObject p;
            if (project == null) p = null;
            else if ("delete".equals(scope)) {
                p = db.lock("project", project);
                if (p == null || p.getLongValue("owner_user_id") != owner) throw AipptException.missing();
            }
            else p = owned(project,owner,true);
            if(p!=null&&"sample".equals(p.getString("status"))&&!(sampleExport&&"export".equals(scope))) {
                throw new AipptException(403,"SAMPLE_READ_ONLY","目录样例只允许检查和专用样例导出，不能通过项目命令修改");
            }
            JSONObject cached=db.idempotent(id);
            if (p != null && "deleted".equals(p.getString("status")) && cached == null) throw AipptException.missing();
            if(cached!=null)  {
                if (project != null && !Objects.equals(project, cached.getString("project_id"))) throw AipptException.conflict("同一幂等键不能用于其他项目",0);
                if(!digest.equals(cached.getString("digest")))throw AipptException.conflict("同一幂等键不能提交不同请求",0);
                return cached.getJSONObject("response");
            }
            JSONObject result=operation.apply(p);
            if(project!=null)reconcileAssets(project);
            JSONObject row=record(project,owner,"ready");
            row.put("id",id);
            row.put("digest",digest);
            row.put("response",result);
            db.insert("idempotency",row);
            if(project!=null)event(project,owner,"changed",JSONObject.of("scope",scope));
            return result;
        }
        );
    }
    private void bumpProject(JSONObject p,String field)  {
        p.put(field,p.getLongValue(field)+1);
        p.put("updated_at",Instant.now().toString());
        db.save("project",p,p.getLongValue("revision"));
    }
    public JSONObject create(long owner,long dept,String key,JSONObject body)  {
        return command(null,owner,key,"create",body,p->  {
            JSONObject j=record(null,owner,"draft");
            j.put("project_id",j.getString("id"));
            j.put("owner_dept_id",Long.toString(dept));
            j.putAll(JSONObject.of("title","","audience",null,"tone","professional","page_count",10,"content_density","medium","layout_mode","flex","theme_id","ivory","theme_overrides",null,"input_revision",1,"theme_revision",1,"deck_revision",1,"event_seq",0));
            copyFields(body,j,Set.of("title","audience","tone","page_count","content_density","layout_mode","theme_id"));
            AipptRules.project(j,true);
            catalog.theme(j.getString("theme_id"));
            db.insert("project",j);
            return j;
        }
        );
    }
    public JSONObject update(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"project",body,j->  {
            AipptRules.expected(j,body,"input_revision");
            unconfirmed(p);
            idle(p);
            copyFields(body,j,Set.of("title","audience","tone","page_count","content_density","layout_mode"));
            AipptRules.project(j,false);
            if(body.containsKey("page_count"))AipptRules.require(j.getIntValue("page_count")>=5&&j.getIntValue("page_count")<=20,"内容设置目标页数须5–20");
            bumpProject(j,"input_revision");
            return j;
        }
        );
    }
    private static void copyFields(JSONObject from,JSONObject to,Set<String> fields)  {
        for(String k:fields)if(from.containsKey(k))to.put(k,from.get(k));
    }
    private void idle(String p)  {
        if(db.children("task",p).stream().anyMatch(t->Set.of("queued","running").contains(t.getString("status"))&&Set.of("generate_slide","generate_outline").contains(t.getString("type"))))throw new AipptException(409,"TASK_ACTIVE","生成期间不能修改",null);
    }
    private void unconfirmed(String p)  {
        JSONObject o=outlineRecord(p);
        if(o!=null&&"confirmed".equals(o.getString("status")))throw new AipptException(409,"OUTLINE_CONFIRMED","请先取消提纲确认",o.getLongValue("revision"));
    }
    public JSONObject deleteProject(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"delete",body,j->  {
            AipptRules.expected(j,body,"input_revision");
            j.put("status","deleted");
            j.put("deleted_at",Instant.now().toString());
            db.save("project",j,j.getLongValue("revision"));
            for(JSONObject task:db.children("task",p))if(Set.of("queued","running").contains(task.getString("status")))  {
                task.put("status","cancelled");
                task.put("cancel_requested",true);
                db.save("task",task,task.getLongValue("revision"));
            }
            JSONObject cleanup=record(p,owner,"pending");
            cleanup.put("type","asset_cleanup");
            cleanup.put("not_before",Instant.now().plus(Duration.ofDays(props.getCleanup().getDeletedDays())).toString());
            db.insert("outbox",cleanup);
            return JSONObject.of("id",p,"status","deleted","cleanup_id",cleanup.getString("id"));
        }
        );
    }
    public List<JSONObject> sources(String p,long owner)  {
        owned(p,owner,false);
        return db.children("source",p).stream().filter(source->!"deleted".equals(source.getString("status"))).toList();
    }
    /** 删除保留审计行，但业务查询与新的删除命令不再接受该材料。 */
    private JSONObject activeSource(String project, String sourceId) {
        JSONObject source = child("source", project, sourceId);
        if ("deleted".equals(source.getString("status"))) throw AipptException.missing();
        return source;
    }
    public JSONArray sourceSections(String project, String sourceId, long owner) {
        owned(project, owner, false);
        return activeSource(project, sourceId).getJSONArray("sections");
    }
    public JSONObject addSource(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"source:add",body,j->  {
            AipptRules.expected(j,body,"input_revision");
            unconfirmed(p);
            idle(p);
            String kind=body.getString("kind");
            AipptRules.require(Set.of("topic","text","document").contains(kind),"来源类型无效");
            JSONObject document=null;
            if(!"document".equals(kind))AipptRules.text(body.get("text"),1,200000,"来源文字");
            else  {
                JSONObject asset=child("asset",p,body.getString("asset_id"));
                AipptRules.require("source".equals(asset.getString("role"))&&"ready".equals(asset.getString("status")),"材料必须引用就绪文档资产");
                body.put("filename",asset.getString("filename"));
                document=asset;
            }
            JSONObject source=record(p,owner,"pending");
            source.putAll(JSONObject.of("kind",kind,"text",body.getString("text"),"filename",body.getString("filename"),"asset_id",body.getString("asset_id"),"parse_status","pending","char_count",0,"sections",new JSONArray(),"warnings",new JSONArray()));
            source.put("content_type",document==null?null:document.getString("mime"));
            source.put("size_bytes",document==null?null:document.getLongValue("size"));
            source.put("sha256",document==null?null:document.getString("sha256"));
            db.insert("source",source);
            JSONObject task=enqueue(j,"parse_source",JSONObject.of("project",j,"source",source),JSONObject.of("source_id",source.getString("id"),"expected_input_revision",j.getLongValue("input_revision")+1));
            source.put("job_id",task.getString("id"));
            db.save("source",source,source.getLongValue("revision"));
            bumpProject(j,"input_revision");
            return source;
        }
        );
    }
    public JSONObject removeSource(String p,String s,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"source:remove:"+s,body,j->  {
            AipptRules.expected(j,body,"input_revision");
            unconfirmed(p);
            idle(p);
            JSONObject source=activeSource(p,s);
            source.put("status","deleted");
            source.put("parse_status","deleted");
            db.save("source",source,source.getLongValue("revision"));
            for(JSONObject task:db.children("task",p))if(s.equals(task.getString("source_id"))&&Set.of("queued","running").contains(task.getString("status")))  {
                task.put("status","cancelled");
                task.put("cancel_requested",true);
                db.save("task",task,task.getLongValue("revision"));
            }
            for(JSONObject section:db.children("source_section",p))if(s.equals(section.getString("source_id")))db.delete("source_section",section.getString("id"));
            bumpProject(j,"input_revision");
            return JSONObject.of("id",s,"input_revision",j.getLongValue("input_revision"));
        }
        );
    }
    private List<JSONObject> readySources(String p)  {
        return db.children("source",p).stream().filter(s->"ready".equals(s.getString("parse_status"))).toList();
    }
    private Set<String> refs(String p)  {
        Set<String> refs=new HashSet<>();
        for(JSONObject s:readySources(p))for(Object raw:s.getJSONArray("sections"))  {
            JSONObject section=(JSONObject)raw;
            refs.add(section.getString("id"));
            refs.add(section.getString("ref"));
        }
        return refs;
    }
    private JSONObject outlineRecord(String p)  {
        return db.children("outline",p).stream().findFirst().orElse(null);
    }
    public JSONObject outline(String p,long owner)  {
        JSONObject j=owned(p,owner,false),o=outlineRecord(p);
        if(o==null)return JSONObject.of("project_id",p,"pages",new JSONArray(),"warnings",new JSONArray(),"revision",0,"status","empty","confirmed",false,"is_stale",false);
        if(!o.containsKey("warnings")||o.get("warnings")==null)o.put("warnings",new JSONArray());
        o.put("confirmed","confirmed".equals(o.getString("status")));
        o.put("is_stale",!AipptRules.fingerprint(j,readySources(p)).equals(o.getString("input_signature")));
        return o;
    }
    public JSONObject generateOutline(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"outline:generate",body,j->  {
            AipptRules.expected(j,body,"input_revision");
            unconfirmed(p);
            idle(p);
            requireProvider();
            List<JSONObject> sources=readySources(p);
            AipptRules.require(!sources.isEmpty()&&sources.stream().anyMatch(s->s.getIntValue("char_count")>0),"请先完成有效材料解析");
            JSONObject task=enqueue(j,"generate_outline",JSONObject.of("project",j,"sources",sources),JSONObject.of("expected_input_revision",j.getLongValue("input_revision"),"input_signature",AipptRules.fingerprint(j,sources)));
            JSONObject outline=outlineRecord(p);
            if(outline==null) {
                outline=record(p,owner,"generating");
                outline.put("pages",new JSONArray());
                outline.put("job_id",task.getString("id"));
                outline.put("error",null);
                db.insert("outline",outline);
            }
            else {
                outline.put("status","generating");
                outline.put("job_id",task.getString("id"));
                outline.put("error",null);
                db.save("outline",outline,outline.getLongValue("revision"));
            }
            return accepted(task);
        }
        );
    }
    public JSONObject saveOutline(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"outline:save",body,j->  {
            unconfirmed(p);
            idle(p);
            JSONObject o=outlineRecord(p);
            if(o==null)throw AipptException.missing();
            AipptRules.expected(o,body,"revision");
            if(body.containsKey("input_revision"))AipptRules.expected(j,body,"input_revision");
            JSONArray pages=AipptRules.array(body,"pages");
            AipptRules.outline(pages,refs(p));
            for(Object raw:pages)catalog.layout(((JSONObject)raw).getString("layout_id"));
            o.put("pages",pages);
            o.put("status","draft");
            db.save("outline",o,o.getLongValue("revision"));
            j.put("page_count",pages.size());
            db.save("project",j,j.getLongValue("revision"));
            return o;
        }
        );
    }
    private JSONObject confirm(JSONObject j,JSONObject body)  {
        String p=j.getString("id");
        JSONObject o=outlineRecord(p);
        if(o==null)throw AipptException.missing();
        AipptRules.expected(o,body,"revision");
        AipptRules.expected(j,body,"input_revision");
        idle(p);
        AipptRules.require("draft".equals(o.getString("status")),"只有草稿提纲可确认");
        AipptRules.outline(o.getJSONArray("pages"),refs(p),1,40);
        for(Object raw:o.getJSONArray("pages"))catalog.layout(((JSONObject)raw).getString("layout_id"));
        AipptRules.require(o.getJSONArray("pages").size()==j.getIntValue("page_count"),"提纲页数须与目标页数相同");
        if(!AipptRules.fingerprint(j,readySources(p)).equals(o.getString("input_signature")))throw new AipptException(409,"OUTLINE_STALE","材料或内容设置已变化，请重新生成提纲");
        o.put("status","confirmed");
        db.save("outline",o,o.getLongValue("revision"));
        j.put("status","outline_ready");
        db.save("project",j,j.getLongValue("revision"));
        return o;
    }
    public JSONObject confirmOutline(String p,long owner,String key,JSONObject body,boolean generate)  {
        return command(p,owner,key,"outline:confirm:"+generate,body,j->  {
            JSONObject o=confirm(j,body);
            return generate?generate(j,body):o;
        }
        );
    }
    public JSONObject unconfirm(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"outline:unconfirm",body,j->  {
            idle(p);
            JSONObject o=outlineRecord(p);
            if(o==null)throw AipptException.missing();
            AipptRules.expected(o,body,"revision");
            AipptRules.require("confirmed".equals(o.getString("status")),"只有已确认提纲可取消确认");
            o.put("status","draft");
            db.save("outline",o,o.getLongValue("revision"));
            j.put("status","draft");
            db.save("project",j,j.getLongValue("revision"));
            return o;
        }
        );
    }
    public JSONObject theme(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"theme",body,j->  {
            AipptRules.expected(j,body,"theme_revision");
            AipptRules.require(body.get("theme_id")!=null||body.containsKey("overrides")||body.containsKey("theme_overrides"),"主题命令须包含主题或覆盖值");
            String id=body.getString("theme_id");
            if(id!=null)  {
                catalog.theme(id);
                if(!id.equals(j.getString("theme_id")))j.put("theme_overrides",null);
                j.put("theme_id",id);
            }
            if(body.containsKey("overrides")||body.containsKey("theme_overrides")) {
                Object raw=body.containsKey("overrides")?body.get("overrides"):body.get("theme_overrides");
                JSONObject overrides=raw==null?null:AipptRules.object(raw,"主题覆盖");
                validateTheme(overrides);
                j.put("theme_overrides",overrides);
            }
            bumpProject(j,"theme_revision");
            return j;
        }
        );
    }
    private void validateTheme(JSONObject o)  {
        if(o==null)return;
        for(String key:o.keySet())AipptRules.require(Set.of("palette","fonts","text_styles","shape").contains(key),"非法主题覆盖");
        JSONObject palette=o.get("palette")==null?null:AipptRules.object(o.get("palette"),"主题颜色");
        if(palette!=null)for(String k:palette.keySet()) {
            AipptRules.require(Set.of("background","surface","ink","ink_soft","ink_muted","accent","accent_soft","line","line_strong").contains(k),"非法主题颜色字段");
            Object value=palette.get(k);
            AipptRules.require(value==null||value instanceof String&&((String)value).matches("#[0-9a-fA-F]{6}"),"非法主题颜色");
        }
        JSONObject fonts=o.get("fonts")==null?null:AipptRules.object(o.get("fonts"),"主题字体");
        if(fonts!=null) {
            JSONObject completeFonts=CatalogService.fontDefaults(fonts);
            AipptRules.require(catalog.directory("themes").stream().anyMatch(t->t.getJSONObject("fonts").equals(completeFonts)),"字体对不在主题白名单内");
            o.put("fonts",completeFonts);
        }
        JSONObject styles=o.get("text_styles")==null?null:AipptRules.object(o.get("text_styles"),"主题字号");
        if(styles!=null)for(String k:styles.keySet())  {
            AipptRules.require(Set.of("display","title","body","bullet").contains(k),"非法字号样式");
            JSONObject style=AipptRules.object(styles.get(k),"主题字号覆盖");
            AipptRules.require(style.keySet().stream().allMatch("size_pt"::equals),"主题仅允许字号覆盖");
            AipptRules.style(style);
        }
        JSONObject shape=o.get("shape")==null?null:AipptRules.object(o.get("shape"),"主题形状");
        if(shape!=null)  {
            for(String k:shape.keySet())AipptRules.require(Set.of("radius_pt","bullet_marker").contains(k),"非法形状覆盖");
            if(shape.containsKey("radius_pt"))AipptRules.style(JSONObject.of("radius_pt",shape.get("radius_pt")));
            if(shape.get("bullet_marker")!=null)AipptRules.require(Set.of("rule","dot","index").contains(shape.get("bullet_marker")),"非法要点符号");
        }
    }
    public JSONObject deck(String p,long owner)  {
        JSONObject j=owned(p,owner,false);
        return deckView(j);
    }
    private List<JSONObject> slides(String p)  {
        return db.children("slide",p).stream().sorted(Comparator.comparingInt(s->s.getIntValue("position"))).toList();
    }
    private JSONObject deckView(JSONObject j)  {
        List<JSONObject> slides=slides(j.getString("id"));
        long ready=slides.stream().filter(s->"ready".equals(s.getString("status"))).count(),failed=slides.stream().filter(s->"failed".equals(s.getString("status"))).count();
        boolean running=db.children("task",j.getString("id")).stream().anyMatch(t->"generate_slide".equals(t.getString("type"))&&Set.of("queued","running").contains(t.getString("status")));
        return JSONObject.of("project_id",j.getString("id"),"title",j.getString("title"),"theme_id",j.getString("theme_id"),"theme_overrides",j.get("theme_overrides"),"theme_revision",j.getLongValue("theme_revision"),"deck_revision",j.getLongValue("deck_revision"),"status",running?"generating":slides.isEmpty()?"idle":ready==slides.size()?"ready":"partial","total",slides.size(),"ready",ready,"failed",failed,"slides",slides);
    }
    private void requireProvider()  {
        if(!props.getProvider().isEnabled())throw new AipptException(503,"PROVIDER_DISABLED","模型调用未启用；已有页面仍可编辑和导出");
        if(!props.getWorker().isEnabled())throw new AipptException(503,"WORKER_DISABLED","算法 Worker 未启用");
    }
    public JSONObject generateDeck(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"deck:generate",body,j->  {
            AipptRules.expected(j,body,"deck_revision");
            return generate(j,body);
        }
        );
    }
    private JSONObject generate(JSONObject j,JSONObject body)  {
        requireProvider();
        String p=j.getString("id");
        idle(p);
        JSONObject o=outlineRecord(p);
        AipptRules.require(o!=null&&"confirmed".equals(o.getString("status")),"请先确认提纲");
        AipptRules.require(AipptRules.fingerprint(j,readySources(p)).equals(o.getString("input_signature")),"确认提纲已过期");
        boolean all=body.getBooleanValue("regenerate_all");
        if(all)  {
            AipptRules.require(body.getBooleanValue("overwrite_acknowledged"),"整份重做需要确认覆盖");
            snapshot(j,"regenerate");
        }
        String batch=UUID.randomUUID().toString();
        JSONArray tasks=new JSONArray();
        List<JSONObject> current=slides(p);
        Set<String> pageIds=new HashSet<>();
        for(Object raw:o.getJSONArray("pages"))pageIds.add(((JSONObject)raw).getString("id"));
        boolean deckChanged=false;
        for(JSONObject slide:current)if(!pageIds.contains(slide.getString("outline_page_id"))) {
            db.delete("slide",slide.getString("id"));
            deckChanged=true;
        }
        String projectMode=j.getString("layout_mode");
        for(Object raw:o.getJSONArray("pages"))  {
            JSONObject page=(JSONObject)raw;
            JSONObject slide=current.stream().filter(s->page.getString("id").equals(s.getString("outline_page_id"))).findFirst().orElse(null);
            int position=o.getJSONArray("pages").indexOf(page)+1;
            if(slide==null)  {
                slide=record(p,j.getLongValue("owner_user_id"),"pending");
                slide.put("outline_page_id",page.getString("id"));
                slide.put("position",position);
                slide.put("title",page.getString("title"));
                slide.put("layout_mode",projectMode);
                slide.put("layout_id",page.getString("layout_id"));
                resetGenerationContent(slide,projectMode);
                db.insert("slide",slide);
                deckChanged=true;
            }
            else {
                boolean metadataChanged=slide.getIntValue("position")!=position||!Objects.equals(slide.getString("title"),page.getString("title"));
                boolean reset=all||!Objects.equals(slide.getString("layout_id"),page.getString("layout_id"))
                    ||("flex".equals(projectMode)&&"ready".equals(slide.getString("status"))
                    &&(!"flex".equals(slide.getString("layout_mode"))||slide.getJSONObject("layout_tree")==null))
                    ||!"ready".equals(slide.getString("status"));
                slide.put("position",position);
                slide.put("title",page.getString("title"));
                if(reset) {
                    slide.put("layout_id",page.getString("layout_id"));
                    resetGenerationContent(slide,projectMode);
                }
                if(metadataChanged||reset) {
                    db.save("slide",slide,slide.getLongValue("revision"));
                    deckChanged=true;
                }
            }
            if(!all&&"ready".equals(slide.getString("status")))continue;
            JSONObject task=enqueue(j,"generate_slide",JSONObject.of("project",j,"outline",o,"outline_page",page,"slide",slide,"sources",readySources(p),"neighbors",neighborTitles(o,page)),JSONObject.of("slide_id",slide.getString("id"),"expected_slide_revision",slide.getLongValue("revision"),"batch_id",batch));
            slide.put("status","generating");
            slide.put("active_task_id",task.getString("id"));
            db.save("slide",slide,slide.getLongValue("revision"));
            task.put("expected_slide_revision",slide.getLongValue("revision"));
            db.save("task",task,task.getLongValue("revision"));
            tasks.add(task.getString("id"));
        }
        if(deckChanged)bumpProject(j,"deck_revision");
        j.put("status",tasks.isEmpty()?"ready":"generating");
        db.save("project",j,j.getLongValue("revision"));
        return JSONObject.of("jobId",tasks.isEmpty()?null:tasks.getString(0),"job_id",tasks.isEmpty()?null:tasks.getString(0),"batch_id",batch,"task_ids",tasks,"status",tasks.isEmpty()?"ready":"generating","pending",tasks.size());
    }
    private void resetGenerationContent(JSONObject slide,String mode) {
        slide.put("status","pending");
        slide.put("layout_mode",mode);
        slide.put("layout_tree",null);
        slide.put("blocks",new JSONArray());
        slide.put("issues",new JSONArray());
        slide.put("error",null);
        slide.remove("geometry");
    }
    private List<String> neighborTitles(JSONObject outline,JSONObject page)  {
        JSONArray pages=outline.getJSONArray("pages");
        int index=pages.indexOf(page);
        List<String> titles=new ArrayList<>();
        if(index>0)titles.add(pages.getJSONObject(index-1).getString("title"));
        if(index+1<pages.size())titles.add(pages.getJSONObject(index+1).getString("title"));
        return titles;
    }
    public JSONObject retrySlide(String p,String s,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"slide:retry:"+s,body,j->  {
            idle(p);
            requireProvider();
            JSONObject slide=child("slide",p,s);
            AipptRules.expected(slide,body,"revision");
            JSONObject o=outlineRecord(p);
            AipptRules.require(o!=null&&"confirmed".equals(o.getString("status")),"请确认提纲");
            JSONObject page=o.getJSONArray("pages").stream().map(JSONObject.class::cast).filter(x->slide.getString("outline_page_id").equals(x.getString("id"))).findFirst().orElseThrow(AipptException::missing);
            snapshot(j,"retry");
            JSONObject task=enqueue(j,"generate_slide",JSONObject.of("project",j,"outline",o,"outline_page",page,"slide",slide,"sources",readySources(p),"neighbors",neighborTitles(o,page)),JSONObject.of("slide_id",s,"expected_slide_revision",slide.getLongValue("revision")+1));
            slide.put("status","generating");
            slide.put("active_task_id",task.getString("id"));
            db.save("slide",slide,slide.getLongValue("revision"));
            j.put("status","generating");
            db.save("project",j,j.getLongValue("revision"));
            return accepted(task);
        }
        );
    }
    public JSONObject cancel(String p,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"deck:cancel",body,j->  {
            AipptRules.expected(j,body,"deck_revision");
            for(JSONObject task:db.children("task",p))if(Set.of("queued","running").contains(task.getString("status"))&&Set.of("generate_slide","generate_outline").contains(task.getString("type")))  {
                task.put("cancel_requested",true);
                if("queued".equals(task.getString("status")))  {
                    task.put("status","cancelled");
                    if("generate_outline".equals(task.getString("type")))failedOutput(task,"提纲生成已取消，可重试");
                    String sid=task.getString("slide_id");
                    if(sid!=null)  {
                        JSONObject slide=child("slide",p,sid);
                        slide.put("status",slide.getJSONArray("blocks").isEmpty()?"pending":"failed");
                        slide.put("error","生成已取消，可续跑");
                        db.save("slide",slide,slide.getLongValue("revision"));
                    }
                }
                db.save("task",task,task.getLongValue("revision"));
            }
            settle(j);
            return deckView(j);
        }
        );
    }
    private JSONObject snapshot(JSONObject p,String reason)  {
        JSONObject s=record(p.getString("id"),p.getLongValue("owner_user_id"),"ready");
        s.put("reason",reason);
        s.put("project",AipptRules.copy(p));
        s.put("slides",slides(p.getString("id")));
        s.put("catalog_version",catalog.version());
        s.put("font_manifest_hash",props.getFontManifestHash());
        JSONObject roleMap=new JSONObject(),sourceMap=new JSONObject();
        JSONObject outline=outlineRecord(p.getString("id"));
        if(outline!=null)  {
            Map<String,String> sourceTexts=new HashMap<>();
            for(JSONObject source:readySources(p.getString("id")))for(Object raw:source.getJSONArray("sections"))  {
                JSONObject section=(JSONObject)raw;
                List<String> parts=new ArrayList<>();
                for(String field:List.of("heading","text")) {
                    String value=section.getString(field);
                    if(value!=null&&!value.isEmpty())parts.add(value);
                }
                sourceTexts.put(section.getString("id"),String.join("\n",parts));
            }
            for(JSONObject slide:slides(p.getString("id")))  {
                JSONObject page=outline.getJSONArray("pages").stream().map(JSONObject.class::cast).filter(x->Objects.equals(x.getString("id"),slide.getString("outline_page_id"))).findFirst().orElse(null);
                if(page!=null)  {
                    roleMap.put(slide.getString("id"),page.getString("page_role"));
                    JSONArray texts=new JSONArray();
                    for(Object ref:page.getJSONArray("source_refs")) {
                        String text=sourceTexts.get(String.valueOf(ref));
                        if(text!=null&&!text.isEmpty())texts.add(text);
                    }
                    sourceMap.put(slide.getString("id"),String.join("\n",texts.toJavaList(String.class)));
                }
            }
        }
        s.put("slide_roles",roleMap);
        s.put("slide_sources",sourceMap);
        db.insert("snapshot",s);
        return s;
    }
    public JSONObject pages(String p,String sid,String action,long owner,String key,JSONObject body)  {
        return command(p,owner,key,"pages:"+action+":"+sid,body,j->  {
            idle(p);
            AipptRules.expected(j,body,"deck_revision");
            if(outlineRecord(p)==null)throw new AipptException(422,"OUTLINE_MISSING","尚未生成提纲，无法调整页面");
            List<JSONObject> pages=new ArrayList<>(slides(p));
            JSONObject copiedPage=null;
            if("order".equals(action))  {
                JSONArray order=body.getJSONArray("slide_ids");
                if(order==null)order=body.getJSONArray("ids");
                AipptRules.require(order!=null&&order.size()==pages.size()&&new HashSet<>(order).size()==pages.size(),"排序必须包含完整页集合");
                Map<String,JSONObject> map=new HashMap<>();
                pages.forEach(s->map.put(s.getString("id"),s));
                List<JSONObject> next=new ArrayList<>();
                for(Object id:order)  {
                    JSONObject slide=map.get(String.valueOf(id));
                    AipptRules.require(slide!=null,"排序含未知页面");
                    next.add(slide);
                }
                pages=next;
            }
            else if("remove".equals(action))  {
                AipptRules.require(pages.size()>1,"不能删除最后一页");
                JSONObject old=child("slide",p,sid);
                db.delete("slide",sid);
                pages.removeIf(s->sid.equals(s.getString("id")));
            }
            else  {
                AipptRules.require(pages.size()<40,"最多40页");
                JSONObject added=record(p,owner,"ready");
                JSONObject content="duplicate".equals(action)?AipptRules.copy(child("slide",p,sid)):AipptRules.blank();
                content.remove("id");
                content.remove("revision");
                content.remove("active_task_id");
                for(String field:List.of("created_at","updated_at","project_id","owner_user_id","outline_page_id","position","geometry","error","issues"))content.remove(field);
                if("duplicate".equals(action))  {
                    Map<String,String> ids=new HashMap<>();
                    for(Object raw:content.getJSONArray("blocks"))  {
                        JSONObject b=(JSONObject)raw;
                        String old=b.getString("id"),fresh=UUID.randomUUID().toString();
                        ids.put(old,fresh);
                        b.put("id",fresh);
                        if(old.equals(b.getString("slot_id")))b.put("slot_id",fresh);
                    }
                    if(content.getJSONObject("layout_tree")!=null)reIdTree(content.getJSONObject("layout_tree"),ids);
                }
                added.putAll(content);
                added.put("outline_page_id",UUID.randomUUID().toString());
                added.put("issues",new JSONArray());
                added.put("error",null);
                if("duplicate".equals(action))copiedPage=copiedOutlinePage(p,child("slide",p,sid),added.getString("outline_page_id"));
                int at=pages.size();
                String after="duplicate".equals(action)?sid:body.getString("after_slide_id");
                if(after!=null)  {
                    at=-1;
                    for(int i=0;i<pages.size();i++)if(after.equals(pages.get(i).getString("id")))at=i+1;
                    AipptRules.require(at>=0,"插入位置不存在");
                }
                added.put("position",at);
                db.insert("slide",added);
                pages.add(at,added);
            }
            syncPages(j,pages,copiedPage);
            return deckView(j);
        }
        );
    }
    private void reIdTree(JSONObject node,Map<String,String> ids)  {
        node.put("id",UUID.randomUUID().toString());
        if("block".equals(node.getString("type")))node.put("block_id",ids.get(node.getString("block_id")));
        else for(Object child:node.getJSONArray("children"))reIdTree((JSONObject)child,ids);
    }
    private JSONObject copiedOutlinePage(String project,JSONObject source,String id) {
        JSONObject outline=outlineRecord(project);
        if(outline!=null)for(Object raw:outline.getJSONArray("pages")) {
            JSONObject page=(JSONObject)raw;
            if(Objects.equals(page.getString("id"),source.getString("outline_page_id"))) {
                JSONObject copied=AipptRules.copy(page);
                copied.put("id",id);
                return copied;
            }
        }
        return null;
    }
    private void syncPages(JSONObject j,List<JSONObject> pages,JSONObject copiedPage)  {
        JSONObject o=outlineRecord(j.getString("id"));
        Map<String,JSONObject> originals=new HashMap<>();
        if(o!=null)for(Object raw:o.getJSONArray("pages"))  {
            JSONObject page=(JSONObject)raw;
            originals.put(page.getString("id"),page);
        }
        if(copiedPage!=null)originals.put(copiedPage.getString("id"),copiedPage);
        JSONArray outlinePages=new JSONArray();
        for(int i=0;i<pages.size();i++)  {
            JSONObject slide=pages.get(i);
            slide.put("position",i+1);
            db.save("slide",slide,slide.getLongValue("revision"));
            JSONObject page=originals.get(slide.getString("outline_page_id"));
            if(page==null)page=JSONObject.of("id",slide.getString("outline_page_id"),"title",slide.getString("title"),"objective","补充这一页想讲清楚的事","key_points",JSONArray.of("待补充要点","待补充要点"),"source_refs",new JSONArray(),"layout_id",slide.getString("layout_id"),"page_role","content","visual",null);
            outlinePages.add(page);
        }
        if(o!=null)  {
            o.put("pages",outlinePages);
            db.save("outline",o,o.getLongValue("revision"));
        }
        j.put("page_count",pages.size());
        bumpProject(j,"deck_revision");
    }
    public JSONObject edit(String p,String sid,long owner,String key,String action,String bid,JSONObject body)  {
        return command(p,owner,key,"slide:"+sid+":"+action+":"+bid,body,j->  {
            idle(p);
            JSONObject slide=child("slide",p,sid);
            AipptRules.expected(slide,body,"revision");
            AipptRules.require("ready".equals(slide.getString("status")),"仅就绪页面可编辑");
            JSONObject before=AipptRules.copy(slide);
            JSONArray blocks=slide.getJSONArray("blocks");
            JSONObject block=bid==null?null:blocks.stream().map(JSONObject.class::cast).filter(b->bid.equals(b.getString("id"))).findFirst().orElseThrow(AipptException::missing);
            switch(action)  {
                case "content" ->  {
                    AipptRules.require(block.getString("type").equals(body.getString("type")),"人工内容编辑不能改变块类型");
                    JSONObject incoming=AipptRules.copy(body);
                    incoming.remove("revision");
                    incoming.remove("locked");
                    incoming.remove("id");
                    incoming.remove("slot_id");
                    incoming.remove("style");
                    block.putAll(incoming);
                    block.put("locked",true);
                }
                case "style" ->  {
                    AipptRules.require(!"chart".equals(block.getString("type")),"不支持图表元素样式");
                    JSONObject style=body.get("style")==null?null:AipptRules.object(body.get("style"),"样式");
                    if(style!=null)AipptRules.style(style);
                    block.put("style",style);
                }
                case "image" ->  {
                    AipptRules.require("image".equals(block.getString("type")),"目标不是图片");
                    JSONObject asset=child("asset",p,body.getString("asset_id"));
                    AipptRules.require(asset.getString("mime").startsWith("image/"),"资产不是图片");
                    block.putAll(JSONObject.of("url","/aippt/assets/"+asset.getString("id"),"source","upload","alt",body.getOrDefault("alt",block.getString("alt")),"credit",body.getString("credit"),"locked",true));
                }
                case "flex-layout" ->  {
                    JSONObject incomingTree=AipptRules.object(body.get("layout_tree"),"布局树");
                    clearOverrides(incomingTree);
                    AipptRules.require("flex".equals(slide.getString("layout_mode")),"仅灵活页面可修改树");
                    slide.put("layout_tree",incomingTree);
                }
                case "flex-state" ->  {
                    slide.put("blocks",AipptRules.array(body,"blocks"));
                    slide.put("layout_tree",body.get("layout_tree")==null?null:AipptRules.object(body.get("layout_tree"),"布局树"));
                    if(body.containsKey("layout_mode"))slide.put("layout_mode",body.getString("layout_mode"));
                    if(body.containsKey("layout_id"))slide.put("layout_id",body.getString("layout_id"));
                    if("fixed".equals(slide.getString("layout_mode")))  {
                        AipptRules.require(slide.getJSONObject("layout_tree")==null,"固定页不能携带布局树");
                        validateFixed(slide);
                    }
                }
                case "add-block" ->  {
                    clearOverrides(slide.getJSONObject("layout_tree"));
                    AipptRules.require("flex".equals(slide.getString("layout_mode")),"固定页不允许增块");
                    JSONObject added=body.get("block")==null?null:AipptRules.object(body.get("block"),"内容块");
                    if(added==null)added=AipptRules.defaultBlock(body.getString("type"));
                    added=AipptRules.copy(added);
                    added.put("id",UUID.randomUUID().toString());
                    added.put("slot_id",added.getString("id"));
                    added.put("locked",true);
                    blocks.add(added);
                    int index=body.containsKey("index")?(int)AipptRules.integer(body.get("index"),0,200,"插入下标"):0;
                    AipptRules.require(FlexEditing.insertLeaf(slide.getJSONObject("layout_tree"),body.getString("parent_id"),index,FlexEditing.leaf(added.getString("id"),added.getString("type"))),"目标容器不存在");
                }
                case "remove-block" ->  {
                    clearOverrides(slide.getJSONObject("layout_tree"));
                    AipptRules.require("flex".equals(slide.getString("layout_mode"))&&blocks.size()>1,"仅灵活页可删块，且须保留一块");
                    blocks.remove(block);
                    removeLeaf(slide.getJSONObject("layout_tree"),bid);
                }
                case "layout" ->  {
                    JSONObject layout=catalog.layout(body.getString("layout_id"));
                    JSONObject compatibility=catalog.compatible(slide,layout);
                    AipptRules.require(compatibility.getBooleanValue("compatible"),compatibility.getString("reason"));
                    for(Object raw:compatibility.getJSONArray("assignments"))  {
                        JSONObject a=(JSONObject)raw;
                        blocks.stream().map(JSONObject.class::cast).filter(b->a.getString("block_id").equals(b.getString("id"))).findFirst().orElseThrow().put("slot_id",a.getString("slot_id"));
                    }
                    slide.put("layout_id",layout.getString("id"));
                    slide.put("layout_mode","fixed");
                    slide.put("layout_tree",null);
                }
                case "unlock-flex" ->  {
                    AipptRules.require("fixed".equals(slide.getString("layout_mode")),"页面已经为灵活布局");
                    JSONObject layout=catalog.layout(slide.getString("layout_id"));
                    JSONObject tree=FlexEditing.fromFixed(layout,blocks);
                    slide.put("layout_mode","flex");
                    slide.put("layout_tree",tree);
                    slide.put("unlock_geometry",layout.getJSONArray("slots"));
                }
                default -> throw AipptException.invalid("未知编辑操作");
            }
            AipptRules.protect(before,slide,false);
            AipptRules.slide(slide);
            if("fixed".equals(slide.getString("layout_mode")))validateFixed(slide);
            validateAssets(p,slide);
            db.save("slide",slide,slide.getLongValue("revision"));
            bumpProject(j,"deck_revision");
            audit(j,"slide_edit",JSONObject.of("slide_id",sid,"action",action));
            return slide;
        }
        );
    }
    private void clearOverrides(JSONObject node)  {
        if(node==null)return;
        node.remove("rect_override");
        if(node.getJSONArray("children")!=null)for(Object child:node.getJSONArray("children"))clearOverrides((JSONObject)child);
    }
    private JSONObject findNode(JSONObject n,String id)  {
        if(n==null||id==null)return null;
        if(id.equals(n.getString("id")))return n;
        if(n.getJSONArray("children")!=null)for(Object child:n.getJSONArray("children"))  {
            JSONObject found=findNode((JSONObject)child,id);
            if(found!=null)return found;
        }
        return null;
    }
    private void removeLeaf(JSONObject n,String id)  {
        ProposalOperations.removeLeaf(n,id);
    }
    public void validateFixed(JSONObject slide)  {
        JSONObject layout=catalog.layout(slide.getString("layout_id"));
        Set<String> used=new HashSet<>();
        for(Object raw:slide.getJSONArray("blocks"))  {
            JSONObject b=(JSONObject)raw;
            JSONObject slot=layout.getJSONArray("slots").stream().map(JSONObject.class::cast).filter(x->Objects.equals(x.getString("id"),b.getString("slot_id"))).findFirst().orElseThrow(()->AipptException.invalid("固定槽位不存在"));
            AipptRules.require(used.add(slot.getString("id"))&&slot.getJSONArray("accepts").contains(b.getString("type")),"槽位重复或类型不兼容");
        }
        for(Object raw:layout.getJSONArray("slots"))  {
            JSONObject slot=(JSONObject)raw;
            AipptRules.require(!slot.getBooleanValue("required")||used.contains(slot.getString("id")),"缺少固定必填槽位");
        }
    }
    private void validateAssets(String p,JSONObject slide)  {
        for(Object raw:slide.getJSONArray("blocks"))  {
            JSONObject b=(JSONObject)raw;
            if("image".equals(b.getString("type"))&&b.getString("url")!=null)  {
                String id=b.getString("url").substring("/aippt/assets/".length());
                JSONObject asset=child("asset",p,id);
                AipptRules.require("ready".equals(asset.getString("status"))&&"image".equals(asset.getString("role"))&&asset.getString("mime").startsWith("image/"),"资产不是就绪图片");
            }
        }
    }
    public List<JSONObject> layouts(String p,String sid,long owner)  {
        owned(p,owner,false);
        JSONObject slide=child("slide",p,sid);
        return catalog.directory("layouts").stream().map(l->  {
            JSONObject c=catalog.compatible(slide,l);
            c.put("name",l.getString("name"));
            return c;
        }
        ).toList();
    }
    public JSONObject propose(String p,String sid,long owner,String key,String type,JSONObject body)  {
        return command(p,owner,key,type+":"+sid,body,j->  {
            idle(p);
            requireProvider();
            JSONObject slide=child("slide",p,sid);
            AipptRules.expected(slide,body,"revision");
            if("ai_edit".equals(type))  {
                AipptRules.text(body.getString("instruction"),1,500,"AI修改要求");
                JSONArray history=body.getJSONArray("history");
                AipptRules.require(history==null||history.size()<=5,"编辑历史最多5轮");
            }
            return accepted(enqueue(j,type,JSONObject.of("project",j,"slide",slide,"instruction",body.getString("instruction"),"history",body.getJSONArray("history")),JSONObject.of("slide_id",sid,"expected_slide_revision",slide.getLongValue("revision"))));
        }
        );
    }
    public JSONObject apply(String p,String sid,long owner,String key,String type,JSONObject body)  {
        return command(p,owner,key,"proposal:apply:"+sid,body,j->  {
            idle(p);
            JSONObject slide=child("slide",p,sid);
            AipptRules.expected(slide,body,"revision");
            JSONObject proposal=child("proposal",p,body.getString("proposal_id"));
            AipptRules.require(sid.equals(proposal.getString("slide_id"))&&type.equals(proposal.getString("type")),"候选不属于当前页面");
            if(proposal.getLongValue("expected_slide_revision")!=slide.getLongValue("revision"))throw AipptException.conflict("候选已过期，请重新生成",slide.getLongValue("revision"));
            JSONObject next=AipptRules.copy(slide);
            JSONObject output=proposal.getJSONObject("output");
            if("relayout".equals(type))  {
                String id=body.getString("candidate_id");
                JSONObject candidate=output.getJSONArray("candidates").stream().map(JSONObject.class::cast).filter(c->id.equals(c.getString("id"))).findFirst().orElseThrow(AipptException::missing);
                next.put("layout_tree",candidate.getJSONObject("layout_tree"));
                next.put("layout_mode","flex");
            }
            else  {
                String opId=body.getString("op_id");
                JSONObject op=output.getJSONArray("operations").stream().map(JSONObject.class::cast).filter(o->opId.equals(o.getString("id"))).findFirst().orElseThrow(AipptException::missing);
                boolean undo="before".equals(body.getString("side"));
                if("discard".equals(body.getString("side")))  {
                    op.put("discarded",true);
                    db.save("proposal",proposal,proposal.getLongValue("revision"));
                    return slide;
                }
                AipptRules.require(!op.getBooleanValue("discarded"),"建议已放弃");
                ProposalOperations.apply(next,op,undo);
                op.put("applied",!undo);
            }
            AipptRules.protect(slide,next,true);
            AipptRules.slide(next);
            validateAssets(p,next);
            db.save("slide",next,slide.getLongValue("revision"));
            proposal.put("expected_slide_revision",next.getLongValue("revision"));
            db.save("proposal",proposal,proposal.getLongValue("revision"));
            bumpProject(j,"deck_revision");
            audit(j,"proposal_apply",JSONObject.of("proposal_id",proposal.getString("id"),"slide_id",sid,"side",body.getString("side")));
            return next;
        }
        );
    }
    public JSONObject quality(String p,long owner)  {
        return tx.execute(s->  {
            JSONObject j=owned(p,owner,true);
            String key=qualityKey(j);
            JSONObject cached=j.getJSONObject("quality");
            if(cached!=null&&key.equals(cached.getString("version_key")))return cached;
            JSONObject existing=db.children("task",p).stream().filter(t->"quality".equals(t.getString("type"))&&key.equals(t.getString("version_key"))&&Set.of("queued","running").contains(t.getString("status"))).findFirst().orElse(null);
            if(existing!=null)return accepted(existing);
            JSONObject snap=snapshot(j,"quality");
            return accepted(enqueue(j,"quality",JSONObject.of("snapshot",snap),JSONObject.of("version_key",key)));
        }
        );
    }
    private String qualityKey(JSONObject j)  {
        return AipptRules.hash(j.getLongValue("deck_revision")+":"+j.getLongValue("theme_revision")+":"+j.getLongValue("input_revision")+":"+catalog.version()+":"+props.getFontManifestHash());
    }
    public JSONObject export(String p,long owner,String key,JSONObject body)  {
        return export(p,owner,key,body,false);
    }
    private JSONObject export(String p,long owner,String key,JSONObject body,boolean sampleExport) {
        return command(p,owner,key,"export",body,j->  {
            idle(p);
            AipptRules.expected(j,body,"deck_revision");
            AipptRules.expected(j,body,"theme_revision");
            AipptRules.require(slides(p).size()>0&&slides(p).stream().allMatch(s->"ready".equals(s.getString("status"))),"所有页面须生成完成后导出");
            JSONObject quality=j.getJSONObject("quality");
            AipptRules.require(quality!=null&&qualityKey(j).equals(quality.getString("version_key")),"请先完成当前版本质量检查");
            AipptRules.require(quality.getBooleanValue("can_export"),"质量错误阻止导出");
            JSONArray issues=quality.getJSONArray("issues");
            if(issues!=null&&issues.stream().map(JSONObject.class::cast).anyMatch(i->"warning".equals(i.getString("severity"))))AipptRules.require(body.getBooleanValue("acknowledge_warnings"),"请确认质量警告");
            JSONObject snapshot=snapshot(j,"export"),export=record(p,owner,"queued");
            export.put("snapshot_id",snapshot.getString("id"));
            export.put("deck_revision",j.getLongValue("deck_revision"));
            export.put("theme_revision",j.getLongValue("theme_revision"));
            db.insert("export",export);
            JSONObject task=enqueue(j,"export",JSONObject.of("snapshot",snapshot),JSONObject.of("export_id",export.getString("id")));
            export.put("job_id",task.getString("id"));
            export.put("jobId",task.getString("id"));
            db.save("export",export,export.getLongValue("revision"));
            return export;
        },sampleExport
        );
    }
    public JSONObject sampleIssues(long owner,String themeId)  {
        return tx.execute(status->  {
            JSONObject p=sampleProject(owner,themeId);
            JSONObject mapping=sampleSlideIds(p);
            JSONObject response=AipptRules.copy(quality(p.getString("id"),owner));
            response.put("sample_slide_ids",mapping);
            response.put("catalog_version",catalog.version());
            return response;
        }
        );
    }
    /** 只接受Worker已有的预算数值摘要；不把来源正文或任意诊断文字作为提示持久化。 */
    private JSONObject outlineSourceMetadata(JSONObject task,JSONObject output) {
        boolean warningsPresent=output.containsKey("warnings"),budgetPresent=output.containsKey("source_budget");
        if(!warningsPresent&&!budgetPresent)return JSONObject.of("warnings",new JSONArray());
        AipptRules.require(warningsPresent&&budgetPresent,"来源裁剪提示与预算须同时提供");
        JSONArray warnings=AipptRules.array(output,"warnings");
        AipptRules.require(warnings.size()<=1,"来源裁剪提示最多一项");
        JSONObject budget=AipptRules.object(output.get("source_budget"),"来源预算");
        AipptRules.require(budget.keySet().equals(Set.of("original_chars","used_chars")),"来源预算字段不完整或含未知字段");
        long original=outlineBudgetInteger(budget.get("original_chars"),1,Integer.MAX_VALUE,"来源原字符数");
        long used=outlineBudgetInteger(budget.get("used_chars"),1,12000,"来源实际字符数");
        AipptRules.require(used<=original,"来源实际字符数不能大于原字符数");
        long snapshotCharacters=0;
        JSONArray sources=task.getJSONObject("payload").getJSONArray("sources");
        AipptRules.require(sources!=null,"任务缺少来源快照");
        for(Object sourceRaw:sources) {
            JSONObject source=AipptRules.object(sourceRaw,"任务来源");
            for(Object sectionRaw:AipptRules.array(source,"sections")) {
                JSONObject section=AipptRules.object(sectionRaw,"任务来源小节");
                Object textRaw=section.get("text");
                AipptRules.require(textRaw instanceof String,"任务来源正文须为文字");
                String text=(String)textRaw;
                snapshotCharacters+=text.codePointCount(0,text.length());
            }
        }
        AipptRules.require(original==snapshotCharacters,"来源原字符数与任务快照不符");
        JSONArray safeWarnings=new JSONArray();
        if(used<original) {
            AipptRules.require(warnings.size()==1,"已裁剪来源须提供提示");
            Object raw=warnings.get(0);
            AipptRules.require(raw instanceof String,"来源裁剪提示须为文字");
            String warning=(String)raw;
            AipptRules.require(warning.codePointCount(0,warning.length())>=1&&warning.codePointCount(0,warning.length())<=1000,"来源裁剪提示长度超限");
            String expected="来源模型输入按预算裁剪："+original+"→"+used+"字";
            AipptRules.require(expected.equals(warning),"来源裁剪提示与预算不符");
            safeWarnings.add(expected);
        }
        else AipptRules.require(warnings.isEmpty(),"未裁剪来源不能带裁剪提示");
        return JSONObject.of("warnings",safeWarnings,"source_budget",JSONObject.of("original_chars",original,"used_chars",used));
    }
    private static long outlineBudgetInteger(Object raw,long minimum,long maximum,String field) {
        AipptRules.require(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long||raw instanceof java.math.BigInteger,field+"须为整数");
        return AipptRules.integer(raw,minimum,maximum,field);
    }
    /** 私有样例只按已登记目录版本及原顺序关联，不改正式报告或猜测内容。 */
    private JSONObject sampleSlideIds(JSONObject project) {
        if(!catalog.version().equals(project.get("catalog_version"))) {
            throw new AipptException(409,"SAMPLE_CATALOG_MISMATCH","私有样例目录版本缺失或已变化，无法确认样例页面关系");
        }
        Object rawSlides=catalog.sample().get("slides");
        if(!(rawSlides instanceof JSONArray originals)||originals.isEmpty()||originals.size()>40) {
            throw sampleMappingInvalid();
        }
        List<JSONObject> stored=db.children("slide",project.getString("id"));
        try {
            if(stored.size()!=originals.size()||AipptRules.integer(project.get("page_count"),1,40,"样例页数")!=originals.size())throw sampleMappingInvalid();
        } catch(AipptException invalid) { throw sampleMappingInvalid(); }
        Set<String> catalogIds=new HashSet<>();
        for(Object raw:originals) {
            if(!(raw instanceof JSONObject original)||!(original.get("id") instanceof String id)||id.isBlank()||id.length()>128||!catalogIds.add(id)) {
                throw sampleMappingInvalid();
            }
        }
        JSONObject mapping=new JSONObject();
        Set<Long> positions=new HashSet<>();
        for(JSONObject slide:stored) {
            Object rawId=slide.get("id");
            if(!(rawId instanceof String id)||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||mapping.containsKey(id)) {
                throw sampleMappingInvalid();
            }
            long position;
            try { position=AipptRules.integer(slide.get("position"),1,originals.size(),"样例页面位置"); }
            catch(AipptException invalid) { throw sampleMappingInvalid(); }
            if(!positions.add(position)||!project.getString("id").equals(slide.get("project_id")))throw sampleMappingInvalid();
            mapping.put(id,originals.getJSONObject((int)position-1).getString("id"));
        }
        return mapping;
    }
    private static AipptException sampleMappingInvalid() {
        return new AipptException(409,"SAMPLE_MAPPING_INVALID","私有样例与当前目录的页面身份或位置不完整，无法定位质量问题");
    }
    public JSONObject sampleExport(long owner,String key,JSONObject body)  {
        String theme=body.getString("theme_id")==null?"ivory":body.getString("theme_id");
        return tx.execute(status->  {
            JSONObject p=sampleProject(owner,theme);
            sampleSlideIds(p);
            JSONObject request=AipptRules.copy(body);
            request.put("deck_revision",p.getLongValue("deck_revision"));
            request.put("theme_revision",p.getLongValue("theme_revision"));
            return export(p.getString("id"),owner,key,request,true);
        }
        );
    }
    private JSONObject sampleProject(long owner,String theme)  {
        catalog.theme(theme);
        JSONObject existing=db.sample(owner,theme);
        if(existing!=null)return owned(existing.getString("id"),owner,true);
        JSONObject sample=catalog.sample();
        JSONObject p=record(null,owner,"sample");
        p.put("project_id",p.getString("id"));
        p.put("catalog_version",catalog.version());
        p.putAll(JSONObject.of("title",sample.getString("title"),"audience","","tone","professional","page_count",sample.getJSONArray("slides").size(),"content_density","medium","layout_mode","fixed","theme_id",theme,"input_revision",1,"theme_revision",1,"deck_revision",1,"theme_overrides",null));
        db.insert("project",p);
        int i=1;
        for(Object raw:sample.getJSONArray("slides"))  {
            JSONObject slide=record(p.getString("id"),owner,"ready");
            JSONObject content=AipptRules.copy((JSONObject)raw);
            content.remove("id");
            content.remove("revision");
            slide.putAll(content);
            slide.put("layout_mode",content.getOrDefault("layout_mode","fixed"));
            slide.put("position",i++);
            slide.put("outline_page_id",UUID.randomUUID().toString());
            slide.put("title",sample.getString("title"));
            for(Object b:slide.getJSONArray("blocks"))  {
                JSONObject block=(JSONObject)b;
                if("image".equals(block.getString("type"))&&block.getString("url")!=null)  {
                    block.put("url",null);
                    block.put("source","placeholder");
                }
            }
            AipptRules.slide(slide);
            db.insert("slide",slide);
        }
        return p;
    }
    public JSONObject getExport(String id,long owner)  {
        JSONObject e=db.get("export",id);
        if(e==null)throw AipptException.missing();
        owned(e.getString("project_id"),owner,false);
        return e;
    }
    public JSONObject task(String id,long owner)  {
        return task(id,owner,permission->true);
    }
    /** 公共 HTTP 入口传平台权限裁决；内部 SSE 读取仍须保持已核实 owner。 */
    public JSONObject task(String id,long owner,Predicate<String> allowed) {
        JSONObject t=db.get("task",id);
        if(t==null)throw AipptException.missing();
        JSONObject project=owned(t.getString("project_id"),owner,false);
        if(!(t.get("type") instanceof String))throw new AipptException(403,"TASK_FORBIDDEN","无该类型任务读取权限");
        String permission=switch(t.getString("type")) {
            case "parse_source" -> "aippt:source:query";
            case "generate_outline" -> "aippt:outline:query";
            case "generate_slide" -> "aippt:deck:query";
            case "ai_edit","relayout" -> "aippt:ai:edit";
            case "quality" -> "sample".equals(project.getString("status"))?"aippt:design:query":"aippt:export:check";
            case "export" -> "aippt:export:download";
            default -> throw new AipptException(403,"TASK_FORBIDDEN","无该类型任务读取权限");
        };
        if(!allowed.test(permission))throw new AipptException(403,"TASK_FORBIDDEN","无该类型任务读取权限");
        JSONObject result=new JSONObject();
        copyFields(t,result,Set.of("id","project_id","type","status","attempt","cancel_requested","progress","error","output","verification_failure","created_at","updated_at","revision"));
        result.put("result",t.get("output"));
        return result;
    }
    public JSONObject enqueue(JSONObject p,String type,JSONObject payload,JSONObject metadata)  {
        payload.put("image_enabled",props.getImage().isEnabled());
        if(FONT_TASK_TYPES.contains(type)) {
            String fontHash=props.getFontManifestHash();
            if(fontHash==null||(!"unavailable".equals(fontHash)&&!fontHash.matches("[0-9a-f]{64}"))) {
                throw new AipptException(503,"FONT_MANIFEST_INVALID","字体清单标识须为 unavailable 或正式SHA-256");
            }
            payload.put("font_manifest_hash",fontHash);
        }
        if(!props.getWorker().isEnabled())throw new AipptException(503,"WORKER_DISABLED","算法 Worker 未启用");
        JSONObject task=record(p.getString("id"),p.getLongValue("owner_user_id"),"queued");
        task.putAll(JSONObject.of("type",type,"payload",payload,"attempt",0,"fencing_token",0,"catalog_version",catalog.version(),"deadline",Instant.now().plusSeconds(900).toString(),"cancel_requested",false));
        task.putAll(metadata);
        db.insert("task",task);
        JSONObject out=record(p.getString("id"),p.getLongValue("owner_user_id"),"pending");
        out.put("task_id",task.getString("id"));
        out.put("type","task_created");
        db.insert("outbox",out);
        event(p.getString("id"),p.getLongValue("owner_user_id"),"queued",JSONObject.of("task_id",task.getString("id"),"task_type",type));
        return task;
    }
    public JSONObject accepted(JSONObject task)  {
        return JSONObject.of("jobId",task.getString("id"),"job_id",task.getString("id"),"state",task.getString("status"),"status",task.getString("status"));
    }
    public void event(String p,long owner,String type,JSONObject data)  {
        JSONObject project=db.lock("project",p);
        if(project==null)return;
        long seq=db.advanceSequence(p);
        JSONObject e=record(p,owner,"ready");
        e.put("revision",seq);
        e.put("eventSeq",seq);
        e.put("type",type);
        e.put("data",data);
        JSONObject deck=deckView(project);
        e.put("deck_state",deck.getString("status"));
        e.put("completed",deck.getLongValue("ready"));
        e.put("total",deck.getLongValue("total"));
        String taskId=data.getString("task_id");
        if(taskId!=null)  {
            JSONObject task=db.get("task",taskId);
            if(task!=null)  {
                JSONObject publicTask=new JSONObject();
                copyFields(task,publicTask,Set.of("id","project_id","type","status","attempt","progress","error","output","revision"));
                publicTask.put("result",task.get("output"));
                e.put("taskId",taskId);
                e.put("task_state",task.getString("status"));
                e.put("task_data",publicTask);
            }
        }
        db.insert("event",e);
        JSONObject notice=record(p,owner,"pending");
        notice.put("type","event_ready");
        notice.put("event_seq",seq);
        db.insert("outbox",notice);
    }
    public void audit(JSONObject p,String action,JSONObject metadata)  {
        JSONObject a=record(p.getString("id"),p.getLongValue("owner_user_id"),"ready");
        a.put("action",action);
        a.put("metadata",metadata);
        db.insert("audit",a);
    }
    public List<JSONObject> events(String p,long owner,long after)  {
        owned(p,owner,false);
        return db.events(p,after);
    }
    public void settle(JSONObject j)  {
        boolean active=db.children("task",j.getString("id")).stream().anyMatch(t->"generate_slide".equals(t.getString("type"))&&Set.of("queued","running").contains(t.getString("status")));
        if(!active)  {
            j=db.lock("project",j.getString("id"));
            List<JSONObject> pages=slides(j.getString("id"));
            if(pages.isEmpty())return;
            j.put("status",pages.stream().allMatch(s->"ready".equals(s.getString("status")))?"ready":"outline_ready");
            db.save("project",j,j.getLongValue("revision"));
        }
    }
    public JSONObject workerProject(String p)  {
        JSONObject j=db.lock("project",p);
        if(j==null||"deleted".equals(j.getString("status")))throw new AipptException(409,"PROJECT_DELETED","项目已删除，拒绝任务结果");
        return j;
    }
    public void completeOutput(JSONObject task,JSONObject output,JSONArray assets)  {
        if(FONT_TASK_TYPES.contains(task.getString("type"))) {
            AipptRules.fontManifest(task.getJSONObject("payload").getString("font_manifest_hash"),output);
        }
        validateManifest(task,assets);
        String p=task.getString("project_id"),type=task.getString("type");
        JSONObject j=workerProject(p);
        switch(type)  {
            case "parse_source" ->  {
                JSONObject source=child("source",p,task.getString("source_id"));
                AipptRules.require(!"deleted".equals(source.getString("status")),"材料已删除");
                JSONArray sections=output.getJSONArray("sections");
                AipptRules.require(sections!=null,"解析结果缺少章节");
                Set<String> ids=new HashSet<>();
                JSONArray safeSections=new JSONArray();
                for(Object raw:sections)  {
                    JSONObject section=(JSONObject)raw;
                    String sid=section.getString("id");
                    if(sid==null)sid=UUID.randomUUID().toString();
                    AipptRules.require(sid.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"),"章节ID必须为稳定UUID");
                    section.put("id",sid);
                    section.put("ref",sid);
                    AipptRules.require(ids.add(sid),"重复章节ID");
                    AipptRules.text(section.getString("text"),0,200000,"章节正文");
                    JSONObject row=record(p,j.getLongValue("owner_user_id"),"ready");
                    copyFields(section,row,Set.of("heading","level","text","locator","ref"));
                    row.put("id",sid);
                    row.put("source_id",source.getString("id"));
                    db.insert("source_section",row);
                    JSONObject safe=new JSONObject();
                    copyFields(section,safe,Set.of("id","ref","heading","level","text","locator"));
                    safeSections.add(safe);
                }
                copyFields(output,source,Set.of("warnings","filename","char_count","parser_version"));
                source.put("sections",safeSections);
                source.put("parse_status","ready");
                source.put("status","ready");
                db.save("source",source,source.getLongValue("revision"));
                bumpProject(j,"input_revision");
            }
            case "generate_outline" ->  {
                if(task.getLongValue("expected_input_revision")!=j.getLongValue("input_revision"))throw AipptException.conflict("材料版本已变化，拒绝旧提纲",j.getLongValue("input_revision"));
                JSONArray pages=AipptRules.array(output,"pages");
                AipptRules.outline(pages,refs(p));
                AipptRules.require(pages.size()==j.getIntValue("page_count"),"生成提纲页数不符");
                for(Object raw:pages)catalog.layout(((JSONObject)raw).getString("layout_id"));
                JSONObject o=outlineRecord(p);
                if(o==null||!task.getString("id").equals(o.getString("job_id")))throw AipptException.conflict("提纲任务已被替换",o==null?0:o.getLongValue("revision"));
                JSONObject sourceMetadata=outlineSourceMetadata(task,output);
                o.put("pages",pages);
                o.remove("source_budget");
                o.putAll(sourceMetadata);
                o.put("status","draft");
                o.put("input_signature",task.getString("input_signature"));
                o.put("job_id",task.getString("id"));
                o.put("error",null);
                db.save("outline",o,o.getLongValue("revision"));
                j.put("status","outline_ready");
                db.save("project",j,j.getLongValue("revision"));
                JSONObject result=new JSONObject();
                copyFields(o,result,Set.of("id","pages","status","revision","input_signature","warnings","source_budget"));
                task.put("output",result);
            }
            case "generate_slide" ->  {
                JSONObject slide=child("slide",p,task.getString("slide_id"));
                if(slide.getLongValue("revision")!=task.getLongValue("expected_slide_revision")||!task.getString("id").equals(slide.getString("active_task_id")))throw AipptException.conflict("页面已变化，拒绝旧生成结果",slide.getLongValue("revision"));
                JSONObject generated=output.getJSONObject("slide");
                AipptRules.require(generated!=null,"缺少生成页面");
                copyFields(generated,slide,Set.of("title","layout_id","layout_mode","layout_tree","blocks","speaker_notes"));
                slide.put("issues",output.getJSONArray("issues"));
                slide.put("geometry",output.getJSONArray("geometry"));
                slide.put("status","ready");
                slide.put("error",null);
                AipptRules.slide(slide);
                if("fixed".equals(slide.getString("layout_mode")))validateFixed(slide);
                validateAssets(p,slide);
                db.save("slide",slide,slide.getLongValue("revision"));
                bumpProject(j,"deck_revision");
                task.put("output",JSONObject.of("slide_id",slide.getString("id"),"revision",slide.getLongValue("revision")));
            }
            case "ai_edit","relayout" ->  {
                JSONObject slide=child("slide",p,task.getString("slide_id"));
                if(slide.getLongValue("revision")!=task.getLongValue("expected_slide_revision"))throw AipptException.conflict("页面已变化，候选已过期",slide.getLongValue("revision"));
                JSONObject proposal=record(p,j.getLongValue("owner_user_id"),"ready");
                proposal.put("slide_id",slide.getString("id"));
                proposal.put("type",type);
                proposal.put("expected_slide_revision",slide.getLongValue("revision"));
                proposal.put("before_slide",AipptRules.copy(slide));
                if("ai_edit".equals(type))  {
                    JSONObject candidate=output.getJSONObject("candidate_slide");
                    AipptRules.require(candidate!=null,"缺少候选页面");
                    AipptRules.protect(slide,candidate,true);
                    AipptRules.slide(candidate);
                    validateAssets(p,candidate);
                    JSONArray operations=output.getJSONArray("operations");
                    AipptRules.require(operations!=null,"缺少候选操作");
                    for(Object raw:operations)  {
                        JSONObject op=(JSONObject)raw;
                        op.put("id",UUID.randomUUID().toString());
                        op.put("op_id",op.getString("id"));
                    }
                }
                else  {
                    JSONArray candidates=output.getJSONArray("candidates");
                    AipptRules.require(candidates!=null,"缺少排布候选");
                    for(Object raw:candidates)  {
                        JSONObject candidate=(JSONObject)raw,check=AipptRules.copy(slide);
                        check.put("layout_mode","flex");
                        check.put("layout_tree",candidate.getJSONObject("layout_tree"));
                        AipptRules.slide(check);
                    }
                }
                proposal.put("output",output);
                db.insert("proposal",proposal);
                task.put("output",JSONObject.of("proposal_id",proposal.getString("id")));
            }
            case "quality" ->  {
                output.put("version_key",task.getString("version_key"));
                if(task.getString("version_key").equals(qualityKey(j)))  {
                    j.put("quality",output);
                    db.save("project",j,j.getLongValue("revision"));
                }
                task.put("output",output);
            }
            case "export" ->  {
                JSONObject e=child("export",p,task.getString("export_id"));
                JSONObject verify=output.getJSONObject("verify");
                AipptRules.require(verify!=null&&(verify.getBooleanValue("ok")||verify.getBooleanValue("passed")),"PPTX回读未通过，禁止下载");
                int expected=task.getJSONObject("payload").getJSONObject("snapshot").getJSONArray("slides").size();
                AipptRules.require(verify.getIntValue("slide_count")==expected&&verify.getIntValue("expected_slide_count")==expected,"导出回读页数与不可变快照不同");
                AipptRules.require(assets!=null&&!assets.isEmpty(),"导出资产缺失");
                JSONObject asset=assets.stream().map(JSONObject.class::cast).filter(a->"export".equals(a.getString("role"))).findFirst().orElseThrow(()->AipptException.invalid("缺少PPTX资产"));
                JSONObject stored=child("asset",p,asset.getString("asset_id")==null?asset.getString("uploadId"):asset.getString("asset_id"));
                AipptRules.require(output.getString("sha256").equals(stored.getString("sha256")),"导出哈希不符");
                e.put("status","ready");
                e.put("asset_id",stored.getString("id"));
                e.put("verify",verify);
                e.put("filename",output.getString("filename"));
                e.put("sha256",output.getString("sha256"));
                db.save("export",e,e.getLongValue("revision"));
                JSONObject result=new JSONObject();
                copyFields(e,result,Set.of("id","asset_id","status","filename","verify","sha256","revision"));
                task.put("output",result);
            }
            default -> throw AipptException.invalid("不支持任务类型");
        }
    }
    private void validateManifest(JSONObject task,JSONArray assets)  {
        if(assets==null)return;
        for(Object raw:assets)  {
            JSONObject submitted=(JSONObject)raw;
            String id=submitted.getString("asset_id")==null?submitted.getString("uploadId"):submitted.getString("asset_id");
            JSONObject asset=child("asset",task.getString("project_id"),id);
            AipptRules.require(task.getString("id").equals(asset.getString("task_id"))&&task.getIntValue("attempt")==asset.getIntValue("attempt")&&task.getLongValue("fencing_token")==asset.getLongValue("fencing_token"),"资产不属于本次任务租约");
            for(String field:List.of("mime","sha256","role"))AipptRules.require(Objects.equals(submitted.getString(field),asset.getString(field)),"资产清单字段不符 "+field);
            AipptRules.require(submitted.getLongValue("size")==asset.getLongValue("size"),"资产大小不符");
        }
    }
    public void reconcileAssets(String project)  {
        Map<String,Integer> counts=new HashMap<>();
        for(String table:List.of("source","slide","snapshot","export","proposal"))for(JSONObject row:db.children(table,project))  {
            if("deleted".equals(row.getString("status")))continue;
            Set<String> ids=new HashSet<>();
            AssetService.collectIds(row,ids);
            for(String id:ids)counts.merge(id,1,Integer::sum);
        }
        for(JSONObject asset:db.children("asset",project))  {
            int before=asset.getIntValue("ref_count"),next=counts.getOrDefault(asset.getString("id"),0);
            if(before!=next)  {
                asset.put("ref_count",next);
                db.save("asset",asset,asset.getLongValue("revision"));
                if(before>0&&next==0)  {
                    JSONObject out=record(project,asset.getLongValue("owner_user_id"),"pending");
                    out.put("type","asset_orphan");
                    out.put("asset_id",asset.getString("id"));
                    out.put("not_before",Instant.now().plusSeconds(props.getCleanup().getOrphanHours()*3600L).toString());
                    db.insert("outbox",out);
                }
            }
        }
    }
    public void failedOutput(JSONObject task,String error)  {
        String p=task.getString("project_id");
        JSONObject j=db.get("project",p);
        if(j==null||"deleted".equals(j.getString("status")))return;
        String sid=task.getString("slide_id");
        if("generate_outline".equals(task.getString("type"))) {
            JSONObject outline=outlineRecord(p);
            if(outline!=null&&task.getString("id").equals(outline.getString("job_id"))) {
                outline.put("status","failed");
                outline.put("error",error);
                db.save("outline",outline,outline.getLongValue("revision"));
            }
        }
        if("generate_slide".equals(task.getString("type"))&&sid!=null)  {
            JSONObject slide=child("slide",p,sid);
            if(task.getString("id").equals(slide.getString("active_task_id")))  {
                slide.put("status","failed");
                slide.put("error",error);
                db.save("slide",slide,slide.getLongValue("revision"));
            }
        }
        if("parse_source".equals(task.getString("type")))  {
            JSONObject source=child("source",p,task.getString("source_id"));
            if("deleted".equals(source.getString("status"))||!task.getString("id").equals(source.getString("job_id")))return;
            source.put("status","failed");
            source.put("parse_status","failed");
            source.put("error",error);
            db.save("source",source,source.getLongValue("revision"));
        }
        if("export".equals(task.getString("type")))  {
            JSONObject e=child("export",p,task.getString("export_id"));
            e.put("status","failed");
            e.put("error",error);
            if(task.getJSONObject("verification_failure")!=null)e.put("verify",AipptRules.copy(task.getJSONObject("verification_failure")));
            db.save("export",e,e.getLongValue("revision"));
        }
    }
}
