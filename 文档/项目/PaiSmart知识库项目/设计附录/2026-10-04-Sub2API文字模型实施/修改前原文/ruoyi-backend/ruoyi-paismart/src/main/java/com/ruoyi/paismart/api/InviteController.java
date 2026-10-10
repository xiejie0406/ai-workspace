package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart/invites")
@PreAuthorize("@ss.hasPermi('paismart:invite:manage')")
@PaismartEnabled
public class InviteController {
    private final InvitationService invites;
    public InviteController(InvitationService invites) {this.invites=invites;}
    @GetMapping public TableDataInfo list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)Boolean enabled) {int size=Math.max(1,Math.min(100,pageSize));long uid=PaismartIdentity.userId();long offset=(long)(Math.max(1,pageNum)-1)*size;return PaismartTables.page(invites.listAuthorized(uid,(int)Math.min(Integer.MAX_VALUE,offset),size,enabled),invites.countAuthorized(uid,enabled));}
    @PostMapping @Log(title="知识库邀请码",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody Map<String,Object> b,@RequestHeader("Idempotency-Key")String key) {return AjaxResult.success(invites.createIdempotent(b,PaismartIdentity.userId(),key,1).get(0));}
    @PostMapping("/batch") @Log(title="知识库批量邀请码",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult batch(@RequestBody Map<String,Object> b,@RequestHeader("Idempotency-Key")String key) {int n=com.ruoyi.paismart.infrastructure.Numbers.smallInteger(b.getOrDefault("count",1));return AjaxResult.success(invites.createIdempotent(b,PaismartIdentity.userId(),key,n));}
    @PutMapping("/{id}") @Log(title="知识库邀请码",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult update(@PathVariable String id,@RequestBody Map<String,Object>b,@RequestHeader("If-Match")long version) {return AjaxResult.success(invites.updateAuthorized(id,b,version,PaismartIdentity.userId()));}
    @DeleteMapping("/{id}") @Log(title="知识库邀请码",businessType=BusinessType.DELETE)
    public AjaxResult delete(@PathVariable String id,@RequestHeader("If-Match")long version) {invites.deleteAuthorized(id,version,PaismartIdentity.userId());return AjaxResult.success();}
}
