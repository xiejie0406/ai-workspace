package com.ruoyi.paicli.controller;

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
public class PcAuditController extends PcControllerSupport {
    private final PcAccess access;private final PcConnectionService connections;
    public PcAuditController(PcStore s,PcAccess a,PcConnectionService c){super(s);access=a;connections=c;}
    @GetMapping("/audit") @PreAuthorize("@ss.hasPermi('paicli:audit:read')") public TableDataInfo list(@RequestParam long directoryContextId,@RequestParam(required=false) String toolName,@RequestParam(required=false) String decision,@RequestParam(required=false) Long taskId){access.directory(directoryContextId,actor(),false);String tool=filter(toolName,"toolName"),choice=filter(decision,"decision");startPage();return auditPage(store.mapper.audits(directoryContextId,actor(),tool,choice,taskId));}
    @GetMapping("/audit/{id}") @PreAuthorize("@ss.hasPermi('paicli:audit:raw')") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(store.view(access.resource(PcTable.SECURITY_AUDIT,id,actor(),false,false)));}
    @GetMapping(value="/audit/export",produces="application/json") @PreAuthorize("@ss.hasPermi('paicli:audit:export')") public List<Map<String,Object>> export(@RequestParam long directoryContextId,@RequestParam(required=false) String toolName,@RequestParam(required=false) String decision,@RequestParam(required=false) Long taskId){access.directory(directoryContextId,actor(),false);return store.mapper.audits(directoryContextId,actor(),filter(toolName,"toolName"),filter(decision,"decision"),taskId).stream().map(this::metadata).toList();}
    private String filter(String value,String field){if(value==null||value.isBlank())return null;if(!value.matches("[A-Za-z0-9._:-]{1,128}"))throw PcException.invalid("审计筛选值非法");return "%\""+field+"\":\""+value.replace("_","!_")+"\"%";}
    private Map<String,Object> metadata(PcRecord row){Map<String,Object> view=store.view(row);Map<String,Object> details=PcJson.object(store.data(row),"details");for(String field:List.of("toolName","toolCallId","argsHash","resourceHash","approvalId","receiptId"))if(details.containsKey(field))view.put(field,details.get(field));view.remove("details");view.put("taskId",row.getParentId()==null?null:String.valueOf(row.getParentId()));view.put("userName",String.valueOf(row.getOwnerId()));view.put("contentHash",row.getContentHash());return view;}
    private TableDataInfo auditPage(List<PcRecord> rows){com.github.pagehelper.PageInfo<PcRecord> info=new com.github.pagehelper.PageInfo<>(rows);TableDataInfo page=new TableDataInfo();page.setCode(200);page.setMsg("查询成功");page.setRows(rows.stream().map(this::metadata).toList());page.setTotal(info.getTotal());return page;}
    @GetMapping("/evaluations") @PreAuthorize("@ss.hasPermi('paicli:evaluation:read')") public AjaxResult evals(@RequestParam long directoryContextId){return AjaxResult.success(connections.list("evaluations",actor(),directoryContextId));}
    @PostMapping("/evaluations") @PreAuthorize("@ss.hasPermi('paicli:evaluation:run')") public AjaxResult eval(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(connections.evaluation(actor(),key,input));}
    @GetMapping("/harness") @PreAuthorize("@ss.hasPermi('paicli:evaluation:read')") public AjaxResult harness(@RequestParam long directoryContextId){return AjaxResult.success(connections.list("harness",actor(),directoryContextId));}
    @PostMapping("/harness") @PreAuthorize("@ss.hasPermi('paicli:evaluation:run')") public AjaxResult harness(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(connections.harness(actor(),key,input));}
}
