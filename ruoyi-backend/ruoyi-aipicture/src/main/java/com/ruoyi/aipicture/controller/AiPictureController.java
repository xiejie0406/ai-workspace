package com.ruoyi.aipicture.controller;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.tool.*;
import com.ruoyi.aipicture.worker.*;

/** 公开路由统一复用若依登录、ss 权限与操作日志；所有数据查询仍加当前 owner。 */
@RestController @RequestMapping("/aipicture") @AiPictureEnabled
public class AiPictureController extends BaseController {
    private final AssetService assets;private final SessionService sessions;private final RunService runs;private final PlanService plans;
    private final BatchExportEvaluationService business;private final ToolRegistry registry;private final AiPictureWorkerClient worker;
    public AiPictureController(AssetService assets,SessionService sessions,RunService runs,PlanService plans,BatchExportEvaluationService business,ToolRegistry registry,AiPictureWorkerClient worker) {this.assets=assets;this.sessions=sessions;this.runs=runs;this.plans=plans;this.business=business;this.registry=registry;this.worker=worker;}
    private int limit(int size) {if(size<1||size>200) throw AiPictureException.invalid("每页数量需要 1–200");return size;}
    private int offset(int page,int size) {if(page<1||page>100000) throw AiPictureException.invalid("页码无效");return (page-1)*size;}
    private TableDataInfo table(List<?> rows,long total) {var result=new TableDataInfo(rows,total);result.setCode(200);result.setMsg("查询成功");return result;}
    @PostMapping("/assets") @PreAuthorize("@ss.hasPermi('aipicture:asset:upload')") @Log(title="图片素材上传",businessType=BusinessType.INSERT)
    public AjaxResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        if(file.getSize()>20L*1024*1024) throw new AiPictureException(413,"IMAGE_TOO_LARGE","图片不得超过 20 MB");return success(assets.upload(getUserId(),file.getBytes()));
    }
    @GetMapping("/assets") @PreAuthorize("@ss.hasPermi('aipicture:asset:list')")
    public TableDataInfo assets(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {return table(assets.list(getUserId(),offset(pageNum,limit(pageSize)),pageSize),assets.count(getUserId()));}
    @GetMapping("/assets/library") @PreAuthorize("@ss.hasPermi('aipicture:asset:list')")
    public AjaxResult library(@RequestParam(defaultValue="50") int limit) {return success(assets.library(getUserId(),limit(limit)));}
    @GetMapping("/assets/{id}") @PreAuthorize("@ss.hasPermi('aipicture:asset:query')")
    public AjaxResult asset(@PathVariable String id) {return success(assets.get(getUserId(),id));}
    @GetMapping("/assets/{id}/download") @PreAuthorize("@ss.hasPermi('aipicture:asset:query')")
    public ResponseEntity<byte[]> assetDownload(@PathVariable String id) throws IOException {var asset=assets.get(getUserId(),id);String format=Json.string(asset.get("format"));return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/"+(format.equals("jpg")?"jpeg":format))).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(assets.download(getUserId(),id));}
    @PostMapping("/generations") @PreAuthorize("@ss.hasPermi('aipicture:image:generate')") @Log(title="图片创作",businessType=BusinessType.INSERT)
    public AjaxResult generate(@RequestBody Map<String,Object> request) {return success(runs.submit(getUserId(),null,"generate_image",BatchExportEvaluationService.parameters(request),request));}
    @GetMapping("/runs/{id}") @PreAuthorize("@ss.hasAnyPermi('aipicture:image:generate,aipicture:session:query,aipicture:batch:list,aipicture:evaluation:list')")
    public AjaxResult run(@PathVariable String id) {return success(runs.get(getUserId(),id));}
    @PostMapping("/runs/{id}/cancel") @PreAuthorize("@ss.hasAnyPermi('aipicture:plan:cancel,aipicture:tool:execute')") @Log(title="取消图片任务",businessType=BusinessType.UPDATE)
    public AjaxResult cancelRun(@PathVariable String id) {return success(runs.cancel(getUserId(),id));}
    @GetMapping("/runs/{id}/events") @PreAuthorize("@ss.hasPermi('aipicture:session:query')")
    public AjaxResult events(@PathVariable String id,@RequestParam(defaultValue="0") long after) {return success(runs.events(getUserId(),id,after));}
    @GetMapping("/sessions") @PreAuthorize("@ss.hasPermi('aipicture:session:list')")
    public TableDataInfo sessions(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {return table(sessions.list(getUserId(),offset(pageNum,limit(pageSize)),pageSize),sessions.count(getUserId()));}
    @PostMapping("/sessions") @PreAuthorize("@ss.hasPermi('aipicture:session:create')") @Log(title="创建图片会话",businessType=BusinessType.INSERT)
    public AjaxResult createSession(@RequestBody Map<String,Object> p) {return success(sessions.create(getUserId(),p));}
    @GetMapping("/sessions/{id}") @PreAuthorize("@ss.hasPermi('aipicture:session:query')")
    public AjaxResult session(@PathVariable String id) {return success(sessions.detail(getUserId(),id));}
    @PatchMapping("/sessions/{id}") @PreAuthorize("@ss.hasPermi('aipicture:session:edit')") @Log(title="修改图片会话",businessType=BusinessType.UPDATE)
    public AjaxResult patch(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(sessions.patch(getUserId(),id,p));}
    @GetMapping("/tools") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')")
    public AjaxResult tools() {return success(registry.registry());}
    @PostMapping("/sessions/{id}/tools") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')") @Log(title="图片工具执行",businessType=BusinessType.UPDATE)
    public AjaxResult tool(@PathVariable String id,@RequestBody Map<String,Object> p) {ToolPermissionGuard.check(Json.string(p.get("tool")));var run=runs.submit(getUserId(),id,Json.string(p.get("tool")),Json.map(p.get("params")),p);return success(Json.of("run",run,"session",sessions.detail(getUserId(),id)));}
    @PostMapping("/sessions/{id}/undo") @PreAuthorize("@ss.hasPermi('aipicture:session:edit')") @Log(title="撤销图片编辑",businessType=BusinessType.UPDATE)
    public AjaxResult undo(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(sessions.historyMove(getUserId(),id,p,true));}
    @PostMapping("/sessions/{id}/redo") @PreAuthorize("@ss.hasPermi('aipicture:session:edit')") @Log(title="重做图片编辑",businessType=BusinessType.UPDATE)
    public AjaxResult redo(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(sessions.historyMove(getUserId(),id,p,false));}
    @GetMapping("/sessions/{id}/history") @PreAuthorize("@ss.hasPermi('aipicture:session:query')")
    public AjaxResult history(@PathVariable String id) {return success(sessions.history(getUserId(),id));}
    @PostMapping("/sessions/{id}/selection/prepare") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')")
    public AjaxResult prepare(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(runs.submit(getUserId(),id,"prepare_selection",Map.of(),p));}
    @PostMapping("/sessions/{id}/selection") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')")
    public AjaxResult selection(@PathVariable String id,@RequestBody Map<String,Object> p) {
        var params=BatchExportEvaluationService.parameters(p);var s=sessions.detail(getUserId(),id);
        SelectionInputValidator.validate(params);
        if(!(params.get("revision") instanceof Number)||Json.number(params.get("revision"))!=Json.number(s.get("revision"))) throw AiPictureException.conflict(Json.number(s.get("row_version")));
        if(Json.list(params.get("points")).isEmpty()&&Json.list(params.get("strokes")).isEmpty()) throw AiPictureException.invalid("请点选或涂抹选区");
        return success(runs.submit(getUserId(),id,"selection",params,p));
    }
    @GetMapping("/sessions/{id}/selection") @PreAuthorize("@ss.hasPermi('aipicture:session:query')")
    public AjaxResult selection(@PathVariable String id) {return success(sessions.selection(getUserId(),id));}
    @DeleteMapping("/sessions/{id}/selection") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')")
    public AjaxResult clearSelection(@PathVariable String id) {sessions.clearSelection(getUserId(),id);return success();}
    @GetMapping("/sessions/{id}/messages") @PreAuthorize("@ss.hasPermi('aipicture:session:query')")
    public AjaxResult messages(@PathVariable String id) {return success(plans.list(getUserId(),id));}
    @PostMapping("/sessions/{id}/messages") @PreAuthorize("@ss.hasPermi('aipicture:tool:execute')") @Log(title="图片自然语言计划",businessType=BusinessType.INSERT)
    public AjaxResult message(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(plans.send(getUserId(),id,p));}
    @PostMapping("/sessions/{id}/messages/{turn}/confirm") @PreAuthorize("@ss.hasPermi('aipicture:plan:confirm')") @Log(title="确认图片计划",businessType=BusinessType.UPDATE)
    public AjaxResult confirm(@PathVariable String id,@PathVariable String turn,@RequestBody Map<String,Object> p) {return success(plans.action(getUserId(),id,turn,"confirm",p));}
    @PostMapping("/sessions/{id}/messages/{turn}/retry") @PreAuthorize("@ss.hasPermi('aipicture:plan:retry')") @Log(title="重试图片计划",businessType=BusinessType.UPDATE)
    public AjaxResult retry(@PathVariable String id,@PathVariable String turn,@RequestBody Map<String,Object> p) {return success(plans.action(getUserId(),id,turn,"retry",p));}
    @PostMapping("/sessions/{id}/messages/{turn}/cancel") @PreAuthorize("@ss.hasPermi('aipicture:plan:cancel')") @Log(title="取消图片计划",businessType=BusinessType.UPDATE)
    public AjaxResult cancel(@PathVariable String id,@PathVariable String turn,@RequestBody Map<String,Object> p) {return success(plans.action(getUserId(),id,turn,"cancel",p));}
    @PostMapping("/batches") @PreAuthorize("@ss.hasPermi('aipicture:batch:execute')") @Log(title="批量图片处理",businessType=BusinessType.INSERT)
    public AjaxResult batch(@RequestBody Map<String,Object> p) {return success(business.createBatch(getUserId(),p));}
    @GetMapping("/batches") @PreAuthorize("@ss.hasPermi('aipicture:batch:list')")
    public TableDataInfo batches(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {return table(business.batches(getUserId(),offset(pageNum,limit(pageSize)),pageSize),business.batchCount(getUserId()));}
    @GetMapping("/batches/{id}") @PreAuthorize("@ss.hasPermi('aipicture:batch:list')")
    public AjaxResult batch(@PathVariable String id) {return success(business.batch(getUserId(),id));}
    @PostMapping("/batches/{id}/cancel") @PreAuthorize("@ss.hasPermi('aipicture:batch:cancel')") @Log(title="取消批量处理",businessType=BusinessType.UPDATE)
    public AjaxResult cancelBatch(@PathVariable String id) {return success(business.cancelBatch(getUserId(),id));}
    @PostMapping("/batches/{id}/items/{item}/retry") @PreAuthorize("@ss.hasPermi('aipicture:batch:retry')") @Log(title="重试批量失败项",businessType=BusinessType.UPDATE)
    public AjaxResult retryItem(@PathVariable String id,@PathVariable String item,@RequestBody Map<String,Object> p) {return success(business.retryItem(getUserId(),id,item,p));}
    @PostMapping("/batches/{id}/export") @PreAuthorize("@ss.hasPermi('aipicture:export:download')")
    public AjaxResult exportBatch(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(business.exportBatch(getUserId(),id,p));}
    @PostMapping("/sessions/{id}/exports") @PreAuthorize("@ss.hasPermi('aipicture:export:download')") @Log(title="图片物料导出",businessType=BusinessType.EXPORT)
    public AjaxResult export(@PathVariable String id,@RequestBody Map<String,Object> p) {return success(business.exportSession(getUserId(),id,p));}
    @GetMapping("/exports/{id}/download") @PreAuthorize("@ss.hasPermi('aipicture:export:download')")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String id) throws IOException {var e=business.exportRecord(getUserId(),id);String format=Json.string(e.get("format"));return ResponseEntity.ok().contentType(format.equals("zip")?MediaType.parseMediaType("application/zip"):MediaType.IMAGE_PNG).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"aipicture-"+Json.uuid(id)+"."+format+"\"").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(business.exportDownload(getUserId(),id));}
    @PostMapping("/evaluations") @PreAuthorize("@ss.hasPermi('aipicture:evaluation:execute')") @Log(title="图片评测",businessType=BusinessType.INSERT)
    public AjaxResult evaluation(@RequestBody Map<String,Object> p) {return success(business.createEvaluation(getUserId(),p));}
    @GetMapping("/evaluations") @PreAuthorize("@ss.hasPermi('aipicture:evaluation:list')")
    public TableDataInfo evaluations(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {return table(business.evaluations(getUserId(),offset(pageNum,limit(pageSize)),pageSize),business.evaluationCount(getUserId()));}
    @GetMapping({"/evaluations/{id}","/evaluations/{id}/report"}) @PreAuthorize("@ss.hasPermi('aipicture:evaluation:list')")
    public AjaxResult evaluation(@PathVariable String id) {return success(business.evaluation(getUserId(),id));}
    @GetMapping({"/capabilities","/health"}) @PreAuthorize("@ss.hasPermi('aipicture:capability:query')")
    public AjaxResult capabilities() {return success(worker.call("capabilities",Map.of()));}
}
