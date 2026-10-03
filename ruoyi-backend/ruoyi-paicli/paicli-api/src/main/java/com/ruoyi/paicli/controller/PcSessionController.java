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
public class PcSessionController extends PcControllerSupport {
    private final PcSessionService sessions;private final PcToolService tools;private final PcAccess access;private final PcConnectionService connections;
    public PcSessionController(PcStore s,PcSessionService sess,PcToolService t,PcAccess a,PcConnectionService c){super(s);sessions=sess;tools=t;access=a;connections=c;}
    @GetMapping("/sessions") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public TableDataInfo sessions(@RequestParam long workspaceId){access.workspace(workspaceId,actor(),false);startPage();return page(store.mapper.list(PcTable.SESSION,workspaceId,actor(),null,null));}
    @PostMapping("/sessions") @PreAuthorize("@ss.hasPermi('paicli:session:write')") @Log(title="PaiCLI会话创建",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(sessions.create(actor(),key,input));}
    @GetMapping("/sessions/{id}") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(sessions.detail(id,actor()));}
    @PostMapping("/sessions/{id}/turns") @PreAuthorize("@ss.hasPermi('paicli:session:write')") @Log(title="PaiCLI任务提交",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult turn(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String v,@RequestBody Map<String,Object> input){return AjaxResult.success(sessions.turn(id,actor(),key,version(v),input));}
    @GetMapping("/sessions/{id}/events") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public AjaxResult events(@PathVariable long id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="200") int limit,@RequestParam(defaultValue="0") int waitSeconds){if(waitSeconds<0||waitSeconds>20)throw PcException.invalid("waitSeconds最大20");return AjaxResult.success(sessions.events(id,actor(),after,limit));}
    @GetMapping("/sessions/{id}/context/raw") @PreAuthorize("@ss.hasPermi('paicli:context:raw')") public AjaxResult rawContext(@PathVariable long id){return AjaxResult.success(sessions.context(id,actor(),true));}
    @GetMapping("/sessions/{id}/context") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public AjaxResult context(@PathVariable long id){return AjaxResult.success(sessions.context(id,actor()));}
    @PostMapping("/sessions/{id}/context") @PreAuthorize("@ss.hasPermi('paicli:session:write')") @Log(title="PaiCLI上下文操作",businessType=BusinessType.UPDATE)
    public AjaxResult context(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(sessions.contextAction(id,actor(),version(v),key,PcJson.text(input,"action")));}
    @GetMapping(value="/sessions/{id}/export",produces="application/json") @PreAuthorize("@ss.hasPermi('paicli:session:export')") public Map<String,Object> export(@PathVariable long id){return sessions.exportData(id,actor(),com.ruoyi.common.utils.SecurityUtils.hasPermi("paicli:context:raw"));}
    @PutMapping("/sessions/{id}/model") @PreAuthorize("@ss.hasPermi('paicli:session:write')") @Log(title="PaiCLI模型切换",businessType=BusinessType.UPDATE)
    public AjaxResult model(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(connections.switchModel(id,actor(),key,version(v),PcJson.number(input,"modelId")));}
    @GetMapping("/sessions/{id}/ledger") @PreAuthorize("@ss.hasPermi('paicli:session:export')") public AjaxResult ledger(@PathVariable long id,@RequestParam(defaultValue="0") long afterSequence,@RequestParam(required=false) Long endSequence,@RequestParam(defaultValue="200") int limit){return AjaxResult.success(sessions.ledger(id,actor(),afterSequence,endSequence,limit,com.ruoyi.common.utils.SecurityUtils.hasPermi("paicli:context:raw")));}
    @GetMapping("/sessions/{id}/messages") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public AjaxResult messages(@PathVariable long id,@RequestParam(defaultValue="0") long afterSequence,@RequestParam(required=false) Long endSequence,@RequestParam(defaultValue="200") int limit){return AjaxResult.success(sessions.messages(id,actor(),afterSequence,endSequence,limit));}
    @GetMapping("/sessions/{id}/export-sections/{section}") @PreAuthorize("@ss.hasPermi('paicli:session:export')") public AjaxResult exportSection(@PathVariable long id,@PathVariable String section,@RequestParam(defaultValue="0") long afterId,@RequestParam(required=false) Long endId,@RequestParam(defaultValue="200") int limit){return AjaxResult.success(sessions.exportSection(id,actor(),section,afterId,endId,limit,com.ruoyi.common.utils.SecurityUtils.hasPermi("paicli:context:raw")));}
    @GetMapping("/sessions/{id}/records/{section:toolCalls|approvals|artifacts}") @PreAuthorize("@ss.hasPermi('paicli:session:read')") public AjaxResult records(@PathVariable long id,@PathVariable String section,@RequestParam(defaultValue="0") long afterId,@RequestParam(required=false) Long endId,@RequestParam(defaultValue="200") int limit){return AjaxResult.success(sessions.exportSection(id,actor(),section,afterId,endId,limit,false));}
    @GetMapping("/tasks") @PreAuthorize("@ss.hasPermi('paicli:task:read')") public TableDataInfo tasks(@RequestParam long workspaceId,@RequestParam(required=false) String status,@RequestParam(required=false) String requester,@RequestParam(required=false) String search){access.workspace(workspaceId,actor(),false);String term=like(search),person=like(requester);startPage();return page(store.mapper.searchTasks(workspaceId,actor(),status,term,person));}
    @GetMapping("/tasks/{id}") @PreAuthorize("@ss.hasPermi('paicli:task:read')") public AjaxResult task(@PathVariable long id){return AjaxResult.success(sessions.task(id,actor()));}
    @GetMapping("/tasks/{id}/events") @PreAuthorize("@ss.hasPermi('paicli:task:read')") public AjaxResult taskEvents(@PathVariable long id,@RequestParam(defaultValue="0") long after,@RequestParam(required=false) Long endSequence,@RequestParam(defaultValue="200") int limit){return AjaxResult.success(sessions.taskEvents(id,actor(),after,endSequence,limit));}
    @PostMapping("/tasks") @PreAuthorize("@ss.hasPermi('paicli:task:write')") @Log(title="PaiCLI后台任务",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult task(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(sessions.background(actor(),key,input));}
    @PostMapping("/tasks/{id}/cancel") @PreAuthorize("@ss.hasPermi('paicli:task:cancel')") @Log(title="PaiCLI任务取消",businessType=BusinessType.UPDATE)
    public AjaxResult cancel(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(sessions.cancel(id,actor(),version(v),key));}
    @PostMapping("/tasks/{id}/reconcile") @PreAuthorize("@ss.hasPermi('paicli:task:recover')") @Log(title="PaiCLI任务核对",businessType=BusinessType.UPDATE)
    public AjaxResult reconcile(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(sessions.reconcile(id,actor(),version(v),key,input));}
    @GetMapping("/approvals") @PreAuthorize("@ss.hasPermi('paicli:approval:decide')") public TableDataInfo approvals(@RequestParam long workspaceId){access.approve(workspaceId,actor());startPage();List<PcRecord> rows=store.mapper.list(PcTable.APPROVAL,workspaceId,null,null,"PENDING");TableDataInfo page=getDataTable(rows);page.setRows(rows.stream().map(tools::approvalView).toList());return page;}
    @GetMapping("/approvals/{id}/preview-text") @PreAuthorize("@ss.hasPermi('paicli:approval:decide')") public org.springframework.http.ResponseEntity<AjaxResult> previewText(@PathVariable long id,@RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="1048576") int limit){return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").body(AjaxResult.success(tools.previewText(actor(),id,offset,limit)));}
    @PostMapping("/approvals/{id}/decision") @PreAuthorize("@ss.hasPermi('paicli:approval:decide')") @Log(title="PaiCLI工具审批",businessType=BusinessType.UPDATE)
    public AjaxResult decision(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(tools.decision(actor(),id,version(v),key,input));}
    @PostMapping("/plans/{id}/{action:execute|revise|cancel}") @PreAuthorize("@ss.hasPermi('paicli:session:write')") @Log(title="PaiCLI计划控制",businessType=BusinessType.UPDATE)
    public AjaxResult plan(@PathVariable long id,@PathVariable String action,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){if(action.equals("cancel")&&!com.ruoyi.common.utils.SecurityUtils.hasPermi("paicli:task:cancel"))throw PcException.deny("取消计划须有任务取消权限");return AjaxResult.success(tools.planAction(actor(),id,version(v),key,action,input));}
}
