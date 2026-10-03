package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.MembershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/enterprise-user")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyEnterpriseMemberController {
    private final MembershipService members;
    public LegacyEnterpriseMemberController(MembershipService members) { this.members=members; }
    @PostMapping("/page")
    public LegacyResult<MembershipService.Page<JSONObject>> page(@RequestHeader("enterprise-id") long id,@RequestBody MembershipService.PageQuery body) { return LegacyResult.success(members.teamMemberPage(id,body)); }
    @PostMapping("/update-role")
    @Log(title="PaiFlow团队成员角色",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> role(@RequestHeader("enterprise-id") long id,@RequestParam String uid,@RequestParam int role) { members.updateTeamRole(id,uid,role);return LegacyResult.success("更新成功"); }
    @DeleteMapping("/remove")
    @Log(title="PaiFlow团队成员移除",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> remove(@RequestHeader("enterprise-id") long id,@RequestParam String uid) { members.removeTeam(id,uid,false);return LegacyResult.success("移除成功"); }
    @PostMapping("/quit-enterprise")
    @Log(title="PaiFlow退出团队",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> leave(@RequestHeader("enterprise-id") long id) { members.removeTeam(id,null,true);return LegacyResult.success("退出成功"); }
    @GetMapping("/get-user-limit")
    public LegacyResult<MembershipService.UserLimit> limit(@RequestHeader("enterprise-id") long id) { return LegacyResult.success(members.userLimit(2,id)); }
}
