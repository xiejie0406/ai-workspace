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
public class PcWorkspaceController extends PcControllerSupport {
    private final PcWorkspaceService workspaces;private final PcDeviceService devices;
    public PcWorkspaceController(PcStore s,PcWorkspaceService w,PcDeviceService d){super(s);workspaces=w;devices=d;}
    @GetMapping("/workspaces") @PreAuthorize("@ss.hasPermi('paicli:workspace:list')") public TableDataInfo list(){startPage();return page(workspaces.list(actor()));}
    @PostMapping("/workspaces") @PreAuthorize("@ss.hasPermi('paicli:workspace:manage')") @Log(title="PaiCLI工作区",businessType=BusinessType.INSERT)
    public AjaxResult save(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(workspaces.save(actor(),key,input));}
    @GetMapping("/workspaces/{id}/members") @PreAuthorize("@ss.hasPermi('paicli:workspace:manage')") public AjaxResult members(@PathVariable long id){return AjaxResult.success(workspaces.members(id,actor()));}
    @PutMapping("/workspaces/{id}/members") @PreAuthorize("@ss.hasPermi('paicli:workspace:manage')") @Log(title="PaiCLI成员维护",businessType=BusinessType.UPDATE)
    public AjaxResult member(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(workspaces.member(id,actor(),key,version(v),input,false));}
    @DeleteMapping("/workspaces/{id}/members/{userId}") @PreAuthorize("@ss.hasPermi('paicli:workspace:manage')") @Log(title="PaiCLI成员移除",businessType=BusinessType.DELETE)
    public AjaxResult remove(@PathVariable long id,@PathVariable long userId,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(workspaces.member(id,actor(),key,version(v),Map.of("userId",userId),true));}
    @GetMapping("/runners") @PreAuthorize("@ss.hasPermi('paicli:runner:list')") public AjaxResult runners(@RequestParam long workspaceId){return AjaxResult.success(devices.runners(workspaceId,actor()));}
    @PostMapping({"/runners/enroll","/runner-enrollment"}) @PreAuthorize("@ss.hasPermi('paicli:runner:manage')") @Log(title="PaiCLI设备注册",businessType=BusinessType.INSERT,isSaveResponseData=false)
    public AjaxResult enroll(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(devices.enroll(actor(),key,input));}
    @PostMapping("/workspaces/{id}/runners") @PreAuthorize("@ss.hasPermi('paicli:runner:manage')") @Log(title="PaiCLI设备注册",businessType=BusinessType.INSERT,isSaveResponseData=false)
    public AjaxResult enroll(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){Map<String,Object> p=new LinkedHashMap<>(input);p.put("workspaceId",id);return AjaxResult.success(devices.enroll(actor(),key,p));}
    @PostMapping("/runners/{id}/sandbox") @PreAuthorize("@ss.hasPermi('paicli:runner:manage')") @Log(title="PaiCLI隔离核验",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public AjaxResult sandbox(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(devices.sandbox(actor(),id,version(v),key,input));}
    @PostMapping("/runners/{id}/revoke") @PreAuthorize("@ss.hasPermi('paicli:runner:manage')") @Log(title="PaiCLI设备撤销",businessType=BusinessType.UPDATE)
    public AjaxResult revoke(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(devices.revoke(actor(),id,version(v),key));}
    @GetMapping("/policies") @PreAuthorize("@ss.hasPermi('paicli:connection:read')") public AjaxResult policy(@RequestParam long workspaceId){return AjaxResult.success(workspaces.policy(workspaceId,actor()));}
    @PutMapping("/policies") @PreAuthorize("@ss.hasPermi('paicli:connection:manage')") @Log(title="PaiCLI策略维护",businessType=BusinessType.UPDATE)
    public AjaxResult policy(@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(workspaces.policy(actor(),key,PcJson.number(input,"workspaceId"),version(v),input));}
}
