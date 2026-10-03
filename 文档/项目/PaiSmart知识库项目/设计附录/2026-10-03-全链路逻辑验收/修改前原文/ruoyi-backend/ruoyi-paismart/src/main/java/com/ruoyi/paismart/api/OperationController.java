package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.paismart.application.PaismartIdentity;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业与审计入口；系统初始化、搬迁和清理均需可审核计划，不能直接清空共享库。 */
@RestController
@RequestMapping("/paismart")
@PaismartEnabled
public class OperationController {
    private final JdbcTemplate jdbc;private final JobService jobs;private final boolean operationsEnabled;private final com.ruoyi.paismart.infrastructure.SchemaReadiness schema;private final com.ruoyi.paismart.application.intelligence.DependencyStatusService dependencies;private final com.ruoyi.paismart.application.operations.OperationAccessService access;private final com.ruoyi.paismart.application.documents.UploadService uploads;
    public OperationController(@Qualifier("paismartJdbc")JdbcTemplate jdbc,JobService jobs,@Value("${paismart.operations.execute-enabled:false}")boolean enabled,com.ruoyi.paismart.infrastructure.SchemaReadiness schema,com.ruoyi.paismart.application.intelligence.DependencyStatusService dependencies,com.ruoyi.paismart.application.operations.OperationAccessService access,com.ruoyi.paismart.application.documents.UploadService uploads){this.jdbc=jdbc;this.jobs=jobs;this.operationsEnabled=enabled;this.schema=schema;this.dependencies=dependencies;this.access=access;this.uploads=uploads;}
    private com.ruoyi.paismart.application.spaces.SpaceService.Scope actor(){var a=PaismartIdentity.current();return new com.ruoyi.paismart.application.spaces.SpaceService.Scope(a.userId(),a.administrator());}
    @GetMapping("/operations/status") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')")
    public AjaxResult status(){
        List<Map<String,Object>> components=new ArrayList<>();List<String> errors=new ArrayList<>();
        try{List<String> missing=schema.missing();components.add(Map.of("name","业务数据库","state",missing.isEmpty()?"READY":"NOT_READY","reason",missing.isEmpty()?"已读回必要表结构":"缺少知识库必要表"));if(!missing.isEmpty())errors.add("缺少表："+String.join(",",missing));}catch(org.springframework.dao.DataAccessException ex){components.add(Map.of("name","业务数据库","state","UNAVAILABLE","reason","数据库连接未就绪"));}
        components.addAll(dependencies.components());components.add(Map.of("name","运维真实写入","state",operationsEnabled?"ENABLED":"DISABLED","reason","开启仍需已批准的不可变操作清单","tested",false,"scope","CONFIGURATION"));
        return AjaxResult.success(Map.of("components",components,"configurationErrors",errors));
    }
    @GetMapping("/operations/jobs") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')")
    public TableDataInfo jobs(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String state){int size=Math.min(100,Math.max(1,pageSize));String filter=state.isBlank()?"":" WHERE state=?";var scope=actor();List<String> ids=jdbc.queryForList("SELECT id FROM ps_job"+filter+" ORDER BY created_at DESC,id DESC",String.class,state.isBlank()?new Object[]{}:new Object[]{state});var rows=ids.stream().map(jobs::get).filter(j->access.job(scope,j)).toList();long start=Math.min(rows.size(),Math.max(0L,(long)pageNum-1)*size);return PaismartTables.page(rows.subList((int)start,(int)Math.min(rows.size(),start+size)),rows.size());}
    @GetMapping("/operations/jobs/{id}") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')")
    public AjaxResult job(@PathVariable String id){var job=jobs.get(id);access.requireJob(actor(),job);return AjaxResult.success(job);}
    @PostMapping("/operations/jobs/{id}/retry") @PreAuthorize("@ss.hasPermi('paismart:operation:execute')") @Log(title="知识库任务重试",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult retry(@PathVariable String id){var job=jobs.get(id);access.requireJob(actor(),job);jobs.retry(id,job.rowVersion());return AjaxResult.success(jobs.get(id));}
    @PostMapping("/operations/jobs/{id}/cancel") @PreAuthorize("@ss.hasPermi('paismart:operation:execute')") @Log(title="知识库任务取消",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult cancel(@PathVariable String id){var job=jobs.get(id);access.requireJob(actor(),job);jobs.cancel(id,job.rowVersion());return AjaxResult.success(jobs.get(id));}
    @GetMapping("/jobs/{id}") @PreAuthorize("@ss.hasAnyPermi('paismart:document:read,paismart:chat:send,paismart:index:list,paismart:eval:list,paismart:operation:plan')")
    public AjaxResult ownJob(@PathVariable String id){var job=jobs.get(id);if(job.userId()!=PaismartIdentity.userId())throw new org.springframework.security.access.AccessDeniedException("任务不属于当前用户");return AjaxResult.success(job);}
    @GetMapping("/audit/activities") @PreAuthorize("@ss.hasPermi('paismart:conversation:audit')")
    public TableDataInfo activities(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){int size=Math.max(1,Math.min(100,pageSize));var scope=actor();String where=scope.administrator()?"":" WHERE a.user_id=?";Object[] args=scope.administrator()?new Object[]{size,Math.max(0,pageNum-1)*size}:new Object[]{scope.userId(),size,Math.max(0,pageNum-1)*size};List<Map<String,Object>>rows=jdbc.queryForList("SELECT a.id,a.user_id AS userId,u.user_name AS userName,a.action,a.resource_id AS target,a.reason,a.created_at AS createdAt FROM ps_audit_event a LEFT JOIN sys_user u ON u.user_id=a.user_id"+where+" ORDER BY a.created_at DESC,a.id LIMIT ? OFFSET ?",args);return PaismartTables.page(rows,jdbc.queryForObject("SELECT COUNT(*) FROM ps_audit_event a"+where,Long.class,scope.administrator()?new Object[]{}:new Object[]{scope.userId()}));}
    @PostMapping("/operations/{kind}") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')") @Log(title="知识库运维计划",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult plan(@PathVariable String kind,@RequestBody Map<String,Object>b){
        if(!Set.of("cleanup-plans","object-migrations","bootstrap-import").contains(kind))throw new IllegalArgumentException("不支持的运维计划类型");
        var scope=actor();String reason=Objects.toString(b.get("reason"),"");com.ruoyi.paismart.application.billing.QuotaService.text(reason,500);
        if("cleanup-plans".equals(kind)){
            var candidates=jdbc.queryForList("SELECT id,object_key AS objectKey,sha256,byte_size AS byteSize,deleted_at AS deletedAt FROM ps_document WHERE deleted_at IS NOT NULL AND deleted_at<? ORDER BY id",java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(30))));
            b=new LinkedHashMap<>(b);b.put("documents",candidates.stream().filter(r->access.document(scope,r.get("id").toString())).limit(1000).toList());
            var documentCount=((List<?>)b.get("documents")).size();b.put("uploads",uploads.cleanupCandidates(scope).stream().limit(1000-documentCount).toList());
        }
        String json=Jsons.write(b);if(json.length()>2_000_000)throw new IllegalArgumentException("计划负载过大");
        access.requirePlan(scope,Map.of("payload_json",json,"type",kind,"created_by",scope.userId()));
        String id=UUID.randomUUID().toString(),hash=sha(json);jdbc.update("INSERT INTO ps_operation_plan(id,type,state,payload_json,manifest_hash,reason,created_by) VALUES(?,?,'PENDING_APPROVAL',?,?,?,?)",id,kind,json,hash,reason,scope.userId());
        return AjaxResult.success(Map.of("id",id,"state","PENDING_APPROVAL","planVersion",hash,"manifest",b,"rowVersion",1L));
    }
    @GetMapping("/operations/plans/{id}") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')")
    public AjaxResult plan(@PathVariable String id){var p=jdbc.queryForMap("SELECT * FROM ps_operation_plan WHERE id=?",id);access.requirePlan(actor(),p);return AjaxResult.success(Map.of("id",id,"type",p.get("type"),"state",p.get("state"),"manifest",Jsons.map(p.get("payload_json").toString()),"planVersion",p.get("manifest_hash"),"rowVersion",p.get("row_version")));}
    @GetMapping("/operations/plans") @PreAuthorize("@ss.hasPermi('paismart:operation:plan')")
    public TableDataInfo plans(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String state){
        var scope=actor();int size=Math.max(1,Math.min(100,pageSize));
        String selected=state==null?"":state.trim().toUpperCase(Locale.ROOT);if(!selected.isEmpty()&&!Set.of("PENDING_APPROVAL","APPROVED","QUEUED","EXECUTING","SUCCEEDED","FAILED").contains(selected))throw new IllegalArgumentException("运维计划状态无效");
        var visible=jdbc.queryForList("SELECT * FROM ps_operation_plan"+(selected.isEmpty()?"":" WHERE state=?")+" ORDER BY created_at DESC,id DESC",selected.isEmpty()?new Object[]{}:new Object[]{selected}).stream().filter(p->access.plan(scope,p)).map(p->{Map<String,Object> r=new LinkedHashMap<>();r.put("id",p.get("id"));r.put("type",p.get("type"));r.put("state",p.get("state"));r.put("reason",p.get("reason"));r.put("createdBy",p.get("created_by"));r.put("approvedBy",p.get("approved_by"));r.put("planVersion",p.get("manifest_hash"));r.put("rowVersion",p.get("row_version"));r.put("createdAt",p.get("created_at"));r.put("jobId",p.get("job_id"));return r;}).toList();
        long start=Math.min(visible.size(),Math.max(0L,(long)pageNum-1)*size);return PaismartTables.page(visible.subList((int)start,(int)Math.min(visible.size(),start+size)),visible.size());
    }
    @PostMapping("/operations/plans/{id}/approve") @PreAuthorize("@ss.hasPermi('paismart:operation:execute')") @Log(title="知识库运维计划批准",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult approve(@PathVariable String id,@RequestBody Map<String,Object>b){var scope=actor();access.requirePlan(scope,jdbc.queryForMap("SELECT * FROM ps_operation_plan WHERE id=?",id));long uid=scope.userId();if(jdbc.update("UPDATE ps_operation_plan SET state='APPROVED',approved_by=?,row_version=row_version+1 WHERE id=? AND state='PENDING_APPROVAL' AND manifest_hash=? AND created_by<>?",uid,id,b.get("planVersion"),uid)!=1)throw new IllegalStateException("计划已变更、已批准或需要另一名获授权操作人批准");return plan(id);}
    @PostMapping("/operations/plans/{id}/execute") @PreAuthorize("@ss.hasPermi('paismart:operation:execute')") @Log(title="知识库执行运维计划",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    @org.springframework.transaction.annotation.Transactional("paismartTransactionManager")
    public AjaxResult execute(@PathVariable String id,@RequestBody Map<String,Object>b){if(!operationsEnabled)throw new IllegalStateException("真实运维写入尚未启用");var p=jdbc.queryForMap("SELECT * FROM ps_operation_plan WHERE id=? FOR UPDATE",id);access.requirePlan(actor(),p);if(!Objects.equals(p.get("manifest_hash"),b.get("planVersion")))throw new IllegalStateException("计划版本不一致");if(p.get("job_id")!=null)return AjaxResult.success(jobs.get(p.get("job_id").toString()));if(!"APPROVED".equals(p.get("state")))throw new IllegalStateException("计划尚未批准");var payload=Jsons.map(p.get("payload_json").toString());payload.put("planId",id);payload.put("kind",p.get("type"));var job=jobs.enqueue(PaismartIdentity.userId(),"OPERATION_EXECUTE",payload,"plan:"+id);jdbc.update("UPDATE ps_operation_plan SET state='QUEUED',job_id=?,row_version=row_version+1 WHERE id=?",job.id(),id);return AjaxResult.success(job);}
    private static String sha(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
