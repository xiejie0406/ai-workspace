package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.MembershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/apply-record")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyApplicationController {
    private final MembershipService members;
    public LegacyApplicationController(MembershipService members) { this.members=members; }
    @PostMapping("/join-enterprise-space")
    @Log(title="PaiFlow申请加入空间",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> apply(@RequestHeader("enterprise-id") long teamId,@RequestParam long spaceId) { members.apply(teamId,spaceId);return LegacyResult.success("处理成功"); }
    @PostMapping("/agree-enterprise-space")
    @Log(title="PaiFlow同意加入空间",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> agree(@RequestHeader("space-id") long id,@RequestParam long applyId,@RequestParam(required=false) Long revision) { members.auditApplication(id,applyId,true,revision);return LegacyResult.success("已同意"); }
    @PostMapping("/refuse-enterprise-space")
    @Log(title="PaiFlow拒绝加入空间",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> refuse(@RequestHeader("space-id") long id,@RequestParam long applyId,@RequestParam(required=false) Long revision) { members.auditApplication(id,applyId,false,revision);return LegacyResult.success("已拒绝"); }
    @PostMapping("/page")
    public LegacyResult<MembershipService.Page<JSONObject>> page(@RequestHeader("space-id") long id,@RequestBody MembershipService.PageQuery body) { return LegacyResult.success(members.applications(id,body)); }
}
