package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.MembershipService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/invite-record")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyInvitationController {
    private final MembershipService members;
    public LegacyInvitationController(MembershipService members) { this.members=members; }
    @PostMapping("/space-invite")
    @Log(title="PaiFlow空间邀请",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> spaceInvite(@RequestHeader("space-id") long id,@RequestBody List<MembershipService.InviteAdd> body) { members.invite(1,id,body);return LegacyResult.success("邀请及站内通知已保存"); }
    @PostMapping("/enterprise-invite")
    @Log(title="PaiFlow团队邀请",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> teamInvite(@RequestHeader("enterprise-id") long id,@RequestBody List<MembershipService.InviteAdd> body) { members.invite(2,id,body);return LegacyResult.success("邀请及站内通知已保存"); }
    @PostMapping("/space-invite-list")
    public LegacyResult<MembershipService.Page<JSONObject>> spaceList(@RequestHeader("space-id") long id,@RequestBody MembershipService.PageQuery body) { return LegacyResult.success(members.invitations(1,id,body)); }
    @PostMapping("/enterprise-invite-list")
    public LegacyResult<MembershipService.Page<JSONObject>> teamList(@RequestHeader("enterprise-id") long id,@RequestBody MembershipService.PageQuery body) { return LegacyResult.success(members.invitations(2,id,body)); }
    @GetMapping("/space-search-username")
    public LegacyResult<List<JSONObject>> spaceUsername(@RequestHeader("space-id") long id,@RequestParam String username) { return LegacyResult.success(members.searchUsers(1,id,username,false)); }
    @GetMapping("/space-search-user")
    public LegacyResult<List<JSONObject>> spaceMobile(@RequestHeader("space-id") long id,@RequestParam String mobile) { return LegacyResult.success(members.searchUsers(1,id,mobile,true)); }
    @GetMapping("/enterprise-search-username")
    public LegacyResult<List<JSONObject>> teamUsername(@RequestHeader("enterprise-id") long id,@RequestParam String username) { return LegacyResult.success(members.searchUsers(2,id,username,false)); }
    @GetMapping("/enterprise-search-user")
    public LegacyResult<List<JSONObject>> teamMobile(@RequestHeader("enterprise-id") long id,@RequestParam String mobile) { return LegacyResult.success(members.searchUsers(2,id,mobile,true)); }
    @PostMapping("/accept-invite")
    @Log(title="PaiFlow接受邀请",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> accept(@RequestParam long inviteId,@RequestParam(required=false) Long revision) { members.respondInvitation(inviteId,true,revision);return LegacyResult.success("已加入"); }
    @PostMapping("/refuse-invite")
    @Log(title="PaiFlow拒绝邀请",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> refuse(@RequestParam long inviteId,@RequestParam(required=false) Long revision) { members.respondInvitation(inviteId,false,revision);return LegacyResult.success("已拒绝"); }
    @PostMapping("/revoke-space-invite")
    @Log(title="PaiFlow撤销空间邀请",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> revokeSpace(@RequestHeader("space-id") long id,@RequestParam long inviteId,@RequestParam(required=false) Long revision) { members.revokeInvitation(1,id,inviteId,revision);return LegacyResult.success("已撤销"); }
    @PostMapping("/revoke-enterprise-invite")
    @Log(title="PaiFlow撤销团队邀请",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> revokeTeam(@RequestHeader("enterprise-id") long id,@RequestParam long inviteId,@RequestParam(required=false) Long revision) { members.revokeInvitation(2,id,inviteId,revision);return LegacyResult.success("已撤销"); }
    @GetMapping("/get-invite-by-param")
    public LegacyResult<JSONObject> oldLink(@RequestParam String param) { throw new ServiceException("原固定AES邀请链接未接通；需要经授权的新受控链接与通知",503); }
}
