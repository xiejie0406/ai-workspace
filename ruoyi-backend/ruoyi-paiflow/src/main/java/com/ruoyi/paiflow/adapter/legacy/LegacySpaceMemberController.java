package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.MembershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/space-user")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacySpaceMemberController {
    private final MembershipService members;
    public LegacySpaceMemberController(MembershipService members) { this.members=members; }
    @PostMapping("/page")
    public LegacyResult<MembershipService.Page<com.alibaba.fastjson2.JSONObject>> page(@RequestHeader("space-id") long id,@RequestBody MembershipService.PageQuery body) { return LegacyResult.success(members.memberPage(id,body)); }
    @GetMapping("/list-space-member")
    public LegacyResult<java.util.List<com.alibaba.fastjson2.JSONObject>> list(@RequestHeader("space-id") long id) { return LegacyResult.success(members.memberList(id)); }
    @GetMapping("/get-user-limit")
    public LegacyResult<MembershipService.UserLimit> limit(@RequestHeader("space-id") long id) { return LegacyResult.success(members.userLimit(1,id)); }
    @PostMapping("/enterprise-add")
    @Log(title="PaiFlow添加团队空间成员",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> add(@RequestHeader("space-id") long id,@RequestParam String uid,@RequestParam int role) { members.enterpriseAdd(id,uid,role);return LegacyResult.success(true); }
    @PostMapping("/update-role")
    @Log(title="PaiFlow成员角色",businessType=BusinessType.UPDATE)
    public LegacyResult<Boolean> role(@RequestHeader("space-id") long id,@RequestParam String uid,@RequestParam int role) {
        members.updateRole(id,uid,role);return LegacyResult.success(true);
    }
    @DeleteMapping("/remove")
    @Log(title="PaiFlow移除成员",businessType=BusinessType.DELETE)
    public LegacyResult<Boolean> remove(@RequestHeader("space-id") long id,@RequestParam String uid) {
        members.remove(id,uid);return LegacyResult.success(true);
    }
    @PostMapping("/quit-space")
    @Log(title="PaiFlow退出空间",businessType=BusinessType.UPDATE)
    public LegacyResult<Boolean> leave(@RequestHeader("space-id") long id) { members.leave(id);return LegacyResult.success(true); }
    @PostMapping("/transfer-space")
    @Log(title="PaiFlow转让空间",businessType=BusinessType.UPDATE)
    public LegacyResult<Boolean> transfer(@RequestHeader("space-id") long id,@RequestParam String uid) {
        members.transfer(id,uid);return LegacyResult.success(true);
    }
}
