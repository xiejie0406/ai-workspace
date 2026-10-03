package com.ruoyi.paicli.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.paicli.service.*;
import com.ruoyi.paicli.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/paicli/v1")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcKnowledgeController extends PcControllerSupport {
    private final PcKnowledgeService knowledge;private final PcAccess access;
    public PcKnowledgeController(PcStore s,PcKnowledgeService k,PcAccess a){super(s);knowledge=k;access=a;}
    @GetMapping("/memories") @PreAuthorize("@ss.hasPermi('paicli:knowledge:read')") public TableDataInfo list(@RequestParam(required=false) Long workspaceId,@RequestParam(defaultValue="PROJECT") String scope,@RequestParam(required=false) String search){if(!Set.of("PERSONAL","PROJECT").contains(scope)||scope.equals("PROJECT")&&workspaceId==null)throw PcException.invalid("记忆范围或工作区非法");if(workspaceId!=null)access.workspace(workspaceId,actor(),false);String term=like(search);startPage();return page(store.mapper.searchMemories(workspaceId,actor(),scope.equals("PERSONAL"),term));}
    @PostMapping("/memories") @PreAuthorize("@ss.hasPermi('paicli:knowledge:manage')") @Log(title="PaiCLI记忆保存",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult save(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){Map<String,Object> response=knowledge.save(actor(),key,input);return AjaxResult.success(response.getOrDefault("memory",response));}
    @PostMapping("/memories/{id}/{action:verify|replace|coexist}") @PreAuthorize("@ss.hasPermi('paicli:knowledge:manage')") @Log(title="PaiCLI记忆核对",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public AjaxResult action(@PathVariable long id,@PathVariable String action,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(knowledge.action(actor(),id,version(v),key,action,input));}
    @DeleteMapping("/memories/{id}") @PreAuthorize("@ss.hasPermi('paicli:knowledge:manage')") @Log(title="PaiCLI记忆删除",businessType=BusinessType.DELETE)
    public AjaxResult delete(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(knowledge.action(actor(),id,version(v),key,"delete",Map.of("id",id,"version",version(v))));}
    @GetMapping("/indexes") @PreAuthorize("@ss.hasPermi('paicli:knowledge:read')") public AjaxResult index(@RequestParam long workspaceId){return AjaxResult.success(knowledge.index(workspaceId,actor()));}
    @PostMapping("/indexes") @PreAuthorize("@ss.hasPermi('paicli:knowledge:manage')") @Log(title="PaiCLI代码索引",businessType=BusinessType.INSERT)
    public AjaxResult rebuild(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(knowledge.rebuild(actor(),key,PcJson.number(input,"workspaceId")));}
    @GetMapping("/search") @PreAuthorize("@ss.hasPermi('paicli:knowledge:read')") public AjaxResult search(@RequestParam long workspaceId,@RequestParam String query,@RequestParam(defaultValue="SEMANTIC") String mode){return AjaxResult.success(knowledge.search(workspaceId,actor(),query,mode));}
    @GetMapping("/graph") @PreAuthorize("@ss.hasPermi('paicli:knowledge:read')") public AjaxResult graph(@RequestParam long workspaceId,@RequestParam(defaultValue="") String query){return AjaxResult.success(knowledge.graph(workspaceId,actor(),query));}
    @GetMapping("/snapshots") @PreAuthorize("@ss.hasPermi('paicli:snapshot:read')") public AjaxResult snapshots(@RequestParam long sessionId){return AjaxResult.success(knowledge.snapshots(sessionId,actor()));}
    @PostMapping("/snapshots/clean") @PreAuthorize("@ss.hasPermi('paicli:snapshot:restore')") @Log(title="PaiCLI全部工作区快照清理派发",businessType=BusinessType.OTHER)
    public AjaxResult cleanAll(@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(knowledge.cleanAll(actor(),key));}
    @GetMapping("/snapshots/{id}/restore-preview") @PreAuthorize("@ss.hasPermi('paicli:snapshot:read')") public AjaxResult preview(@PathVariable long id){return AjaxResult.success(knowledge.preview(actor(),id));}
    @PostMapping("/snapshots/{id}/restore-preview") @PreAuthorize("@ss.hasPermi('paicli:snapshot:read')") @Log(title="PaiCLI快照预览",businessType=BusinessType.OTHER)
    public AjaxResult requestPreview(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(knowledge.requestPreview(actor(),id,version(v),key));}
    @PostMapping("/snapshots/{id}/restore") @PreAuthorize("@ss.hasPermi('paicli:snapshot:restore')") @Log(title="PaiCLI快照恢复",businessType=BusinessType.UPDATE)
    public AjaxResult restore(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(knowledge.restore(actor(),id,version(v),key,input));}
}
