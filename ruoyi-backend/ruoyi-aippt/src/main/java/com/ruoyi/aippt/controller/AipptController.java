package com.ruoyi.aippt.controller;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.persistence.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/aippt") @AipptEnabled public class AipptController  {
    private final AipptService service;
    private final AssetService assets;
    private final CatalogService catalog;
    private final AipptRepository db;
    private final AipptProperties props;
    private final AipptHealthService health;
    public AipptController(AipptService s,AssetService a,CatalogService c,AipptRepository d,AipptProperties p,AipptHealthService h)  {
        service=s;
        assets=a;
        catalog=c;
        db=d;
        props=p;
        health=h;
    }
    private long owner()  {
        return SecurityUtils.getUserId();
    }
    private JSONObject body(Map<String,Object> input)  {
        return JSON.parseObject(JSON.toJSONString(input,JSONWriter.Feature.WriteMapNullValue));
    }
    private AjaxResult ok(Object value)  {
        return AjaxResult.success(value);
    }
    @GetMapping("/projects") @PreAuthorize("@ss.hasPermi('aippt:project:list')") public Map<String,Object> projects(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="")String title)  {
        JSONObject result=service.list(owner(),status,q.isBlank()?title:q,pageNum,pageSize);
        result.put("code",200);
        result.put("msg","查询成功");
        return result;
    }
    @PostMapping("/projects") @PreAuthorize("@ss.hasPermi('aippt:project:add')") public AjaxResult create(@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.create(owner(),SecurityUtils.getDeptId()==null?0:SecurityUtils.getDeptId(),key,body(input)));
    }
    @GetMapping("/projects/{p}") @PreAuthorize("@ss.hasPermi('aippt:project:query')") public AjaxResult project(@PathVariable String p)  {
        return ok(service.getProject(p,owner()));
    }
    @PatchMapping("/projects/{p}") @PreAuthorize("@ss.hasPermi('aippt:project:edit')") public AjaxResult update(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.update(p,owner(),key,body(input)));
    }
    @DeleteMapping("/projects/{p}") @PreAuthorize("@ss.hasPermi('aippt:project:remove')") public AjaxResult delete(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.deleteProject(p,owner(),key,body(input)));
    }
    @PatchMapping("/projects/{p}/theme") @PreAuthorize("@ss.hasPermi('aippt:theme:edit')") public AjaxResult theme(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.theme(p,owner(),key,body(input)));
    }
    @GetMapping("/projects/{p}/sources") @PreAuthorize("@ss.hasPermi('aippt:source:query')") public AjaxResult sources(@PathVariable String p)  {
        return ok(service.sources(p,owner()));
    }
    @PostMapping("/projects/{p}/sources") @PreAuthorize("@ss.hasPermi('aippt:source:add')") public AjaxResult source(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.addSource(p,owner(),key,body(input)));
    }
    @PostMapping(value="/projects/{p}/sources/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("@ss.hasPermi('aippt:source:add')") public AjaxResult uploadSource(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestParam MultipartFile file,@RequestParam long input_revision)  {
        JSONObject asset=assets.uploadCommand(p,owner(),file,"source",key,p+":source",JSONObject.of("input_revision",input_revision));
        try  {
            return ok(service.addSource(p,owner(),key,JSONObject.of("kind","document","filename",file.getOriginalFilename(),"asset_id",asset.getString("id"),"input_revision",input_revision)));
        }
        catch(RuntimeException failure)  {
            assets.discard(asset);
            throw failure;
        }
    }
    @DeleteMapping("/projects/{p}/sources/{s}") @PreAuthorize("@ss.hasPermi('aippt:source:remove')") public AjaxResult removeSource(@PathVariable String p,@PathVariable String s,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.removeSource(p,s,owner(),key,body(input)));
    }
    @GetMapping("/projects/{p}/sources/{s}/sections") @PreAuthorize("@ss.hasPermi('aippt:source:query')") public AjaxResult sections(@PathVariable String p,@PathVariable String s)  {
        return ok(service.sourceSections(p,s,owner()));
    }
    @GetMapping("/projects/{p}/outline") @PreAuthorize("@ss.hasPermi('aippt:outline:query')") public AjaxResult outline(@PathVariable String p)  {
        return ok(service.outline(p,owner()));
    }
    @PostMapping("/projects/{p}/outline/generate") @PreAuthorize("@ss.hasPermi('aippt:outline:generate')") public AjaxResult generateOutline(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.generateOutline(p,owner(),key,body(input)));
    }
    @PatchMapping("/projects/{p}/outline") @PreAuthorize("@ss.hasPermi('aippt:outline:edit')") public AjaxResult saveOutline(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.saveOutline(p,owner(),key,body(input)));
    }
    @PostMapping("/projects/{p}/outline/confirm") @PreAuthorize("@ss.hasPermi('aippt:outline:confirm')") public AjaxResult confirm(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.confirmOutline(p,owner(),key,body(input),false));
    }
    @PostMapping("/projects/{p}/outline/confirm-and-generate") @PreAuthorize("@ss.hasPermi('aippt:outline:confirm') and @ss.hasPermi('aippt:deck:generate')") public AjaxResult confirmGenerate(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.confirmOutline(p,owner(),key,body(input),true));
    }
    @PostMapping("/projects/{p}/outline/unconfirm") @PreAuthorize("@ss.hasPermi('aippt:outline:confirm')") public AjaxResult unconfirm(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.unconfirm(p,owner(),key,body(input)));
    }
    @GetMapping("/projects/{p}/deck") @PreAuthorize("@ss.hasPermi('aippt:deck:query')") public AjaxResult deck(@PathVariable String p)  {
        return ok(service.deck(p,owner()));
    }
    @PostMapping("/projects/{p}/deck/generate") @PreAuthorize("@ss.hasPermi('aippt:deck:generate')") public AjaxResult generateDeck(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.generateDeck(p,owner(),key,body(input)));
    }
    @PostMapping("/projects/{p}/deck/cancel") @PreAuthorize("@ss.hasPermi('aippt:deck:cancel')") public AjaxResult cancelDeck(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.cancel(p,owner(),key,body(input)));
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/retry") @PreAuthorize("@ss.hasPermi('aippt:deck:generate')") public AjaxResult retry(@PathVariable String p,@PathVariable String sid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.retrySlide(p,sid,owner(),key,body(input)));
    }
    @PutMapping("/projects/{p}/deck/slides/order") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult order(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.pages(p,null,"order",owner(),key,body(input)));
    }
    @PostMapping("/projects/{p}/deck/slides") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult addPage(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.pages(p,null,"add",owner(),key,body(input)));
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/duplicate") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult duplicate(@PathVariable String p,@PathVariable String sid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.pages(p,sid,"duplicate",owner(),key,body(input)));
    }
    @DeleteMapping("/projects/{p}/deck/slides/{sid}") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult removePage(@PathVariable String p,@PathVariable String sid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.pages(p,sid,"remove",owner(),key,body(input)));
    }
    @PatchMapping("/projects/{p}/deck/slides/{sid}/blocks/{bid}") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult content(@PathVariable String p,@PathVariable String sid,@PathVariable String bid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,"content",bid,body(input)));
    }
    @PatchMapping("/projects/{p}/deck/slides/{sid}/blocks/{bid}/style") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult style(@PathVariable String p,@PathVariable String sid,@PathVariable String bid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,"style",bid,body(input)));
    }
    @PutMapping(value="/projects/{p}/deck/slides/{sid}/blocks/{bid}/image",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("@ss.hasPermi('aippt:slide:edit') and @ss.hasPermi('aippt:image:upload')") public AjaxResult image(@PathVariable String p,@PathVariable String sid,@PathVariable String bid,@RequestHeader("Idempotency-Key")String key,@RequestParam MultipartFile file,@RequestParam long revision,@RequestParam(defaultValue="")String alt)  {
        JSONObject asset=assets.uploadCommand(p,owner(),file,"image",key,p+":"+sid+":"+bid,JSONObject.of("revision",revision,"alt",alt,"slide_id",sid));
        try  {
            return ok(service.edit(p,sid,owner(),key,"image",bid,JSONObject.of("revision",revision,"asset_id",asset.getString("id"),"alt",alt)));
        }
        catch(RuntimeException failure)  {
            assets.discard(asset);
            throw failure;
        }
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/blocks") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult addBlock(@PathVariable String p,@PathVariable String sid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,"add-block",null,body(input)));
    }
    @DeleteMapping("/projects/{p}/deck/slides/{sid}/blocks/{bid}") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult removeBlock(@PathVariable String p,@PathVariable String sid,@PathVariable String bid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,"remove-block",bid,body(input)));
    }
    @PutMapping("/projects/{p}/deck/slides/{sid}/{action:flex-layout|flex-state|layout}") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult layoutCommand(@PathVariable String p,@PathVariable String sid,@PathVariable String action,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,action,null,body(input)));
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/unlock-flex") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult unlock(@PathVariable String p,@PathVariable String sid,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.edit(p,sid,owner(),key,"unlock-flex",null,body(input)));
    }
    @GetMapping("/projects/{p}/deck/slides/{sid}/layouts") @PreAuthorize("@ss.hasPermi('aippt:deck:query')") public AjaxResult layouts(@PathVariable String p,@PathVariable String sid)  {
        return ok(service.layouts(p,sid,owner()));
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/{kind:ai-edit|relayout}") @PreAuthorize("@ss.hasPermi('aippt:ai:edit')") public AjaxResult propose(@PathVariable String p,@PathVariable String sid,@PathVariable String kind,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.propose(p,sid,owner(),key,kind.replace('-','_'),body(input)));
    }
    @PostMapping("/projects/{p}/deck/slides/{sid}/{kind:ai-edit|relayout}/apply") @PreAuthorize("@ss.hasPermi('aippt:slide:edit')") public AjaxResult apply(@PathVariable String p,@PathVariable String sid,@PathVariable String kind,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.apply(p,sid,owner(),key,kind.replace('-','_'),body(input)));
    }
    @GetMapping("/projects/{p}/proposals/{id}") @PreAuthorize("@ss.hasPermi('aippt:deck:query')") public AjaxResult proposal(@PathVariable String p,@PathVariable String id)  {
        service.getProject(p,owner());
        JSONObject row=service.child("proposal",p,id);
        JSONObject value=AipptRules.copy(row.getJSONObject("output"));
        value.put("id",id);
        value.put("proposal_id",id);
        value.put("revision",row.getLongValue("expected_slide_revision"));
        return ok(value);
    }
    @GetMapping("/projects/{p}/deck/quality") @PreAuthorize("@ss.hasPermi('aippt:export:check')") public AjaxResult quality(@PathVariable String p)  {
        return ok(service.quality(p,owner()));
    }
    @PostMapping("/projects/{p}/deck/exports") @PreAuthorize("@ss.hasPermi('aippt:export:create')") public AjaxResult export(@PathVariable String p,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.export(p,owner(),key,body(input)));
    }
    @GetMapping("/exports/{id}") @PreAuthorize("@ss.hasPermi('aippt:export:download')") public AjaxResult getExport(@PathVariable String id)  {
        return ok(service.getExport(id,owner()));
    }
    @GetMapping("/exports/{id}/download") @PreAuthorize("@ss.hasPermi('aippt:export:download')") public ResponseEntity<byte[]> download(@PathVariable String id)  {
        JSONObject e=service.getExport(id,owner());
        AipptRules.require("ready".equals(e.getString("status")),"导出未完成或回读失败");
        return binary(e.getString("asset_id"),e.getString("filename"),true);
    }
    @GetMapping("/assets/{id}") @PreAuthorize("@ss.hasPermi('aippt:project:query')") public ResponseEntity<byte[]> asset(@PathVariable String id,@RequestParam(required=false)String ticket)  {
        assets.validateTicket(id,owner(),ticket);
        JSONObject a=assets.metadata(id,owner());
        assetPermission(a);
        return binary(id,a.getString("filename"),false);
    }
    private ResponseEntity<byte[]> binary(String id,String filename,boolean attachment)  {
        JSONObject a=assets.metadata(id,owner());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,(attachment?ContentDisposition.attachment():ContentDisposition.inline()).filename(filename==null?"文件":filename,java.nio.charset.StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(a.getString("mime"))).body(assets.read(id,owner()));
    }
    @PostMapping("/assets/{id}/download-ticket") @PreAuthorize("@ss.hasPermi('aippt:project:query')") public AjaxResult ticket(@PathVariable String id)  {
        assetPermission(assets.metadata(id,owner()));
        return ok(assets.ticket(id,owner()));
    }
    private void assetPermission(JSONObject asset) {
        if("source".equals(asset.getString("role"))&&!SecurityUtils.hasPermi("aippt:source:query"))throw new AipptException(403,"SOURCE_FORBIDDEN","无原材料读取权限");
        if("export".equals(asset.getString("role"))&&!SecurityUtils.hasPermi("aippt:export:download"))throw new AipptException(403,"EXPORT_FORBIDDEN","无导出文件下载权限");
    }
    @GetMapping("/tasks/{id}") @PreAuthorize("@ss.hasAnyPermi('"+AipptService.TASK_READ_PERMISSIONS+"')") public AjaxResult task(@PathVariable String id)  {
        return ok(service.task(id,owner(),SecurityUtils::hasPermi));
    }
    @GetMapping("/design/{kind:themes|layouts}") @PreAuthorize("@ss.hasPermi('aippt:design:query')") public AjaxResult design(@PathVariable String kind)  {
        return ok(JSONObject.of("items",catalog.directory(kind),"catalog_version",catalog.version()));
    }
    @GetMapping("/design/sample-deck") @PreAuthorize("@ss.hasPermi('aippt:design:query')") public AjaxResult sample(@RequestParam(name="theme_id",defaultValue="ivory")String themeId)  {
        catalog.theme(themeId);
        JSONObject sample=catalog.sample();
        sample.put("theme_id",themeId);
        sample.put("catalog_version",catalog.version());
        return ok(sample);
    }
    @GetMapping("/design/sample-deck/issues") @PreAuthorize("@ss.hasPermi('aippt:design:query')") public AjaxResult sampleIssues(@RequestParam(name="theme_id",defaultValue="ivory")String themeId)  {
        return ok(service.sampleIssues(owner(),themeId));
    }
    @PostMapping("/design/sample-deck/exports") @PreAuthorize("@ss.hasPermi('aippt:export:create')") public AjaxResult sampleExport(@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> input)  {
        return ok(service.sampleExport(owner(),key,body(input)));
    }
    @GetMapping("/operations/health") @PreAuthorize("@ss.hasPermi('aippt:operations:query')") public AjaxResult health()  {
        return ok(health.snapshot());
    }
}
