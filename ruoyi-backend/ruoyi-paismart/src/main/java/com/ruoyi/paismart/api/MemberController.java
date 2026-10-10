package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysUserService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart")
@PaismartEnabled
public class MemberController {
    private final JdbcTemplate jdbc;
    private final MembershipService membership;
    private final SpaceService spaces;
    private final QuotaService quotas;
    private final InvitationService invites;
    private final ISysUserService users;
    public record MemberSpacesRequest(List<SpaceService.Assignment> assignments,String primarySpaceId,Long rowVersion,Long expectedVersion) {}
    public MemberController(@Qualifier("paismartJdbc") JdbcTemplate jdbc,MembershipService membership,SpaceService spaces,QuotaService quotas,InvitationService invites,ISysUserService users) {this.jdbc=jdbc;this.membership=membership;this.spaces=spaces;this.quotas=quotas;this.invites=invites;this.users=users;}
    private SpaceService.Scope manager() {
        var actor=PaismartIdentity.current();if(!actor.administrator())membership.requireEligible(actor.userId());
        return new SpaceService.Scope(actor.userId(),actor.administrator());
    }
    @GetMapping("/members/me")
    public AjaxResult me() {
        long id=PaismartIdentity.userId();
        boolean eligible=membership.isEligible(id);
        Map<String,Object> r=new LinkedHashMap<>();r.put("id",id);r.put("userId",id);r.put("eligible",eligible);r.put("enabled",eligible);r.put("status",eligible?"ACTIVE":"INELIGIBLE");r.put("rowVersion",spaces.membershipVersion(id));memberSpaces(r,spaces.memberships(id));
        // 聊天用户读取本人可见空间名称，不依赖空间管理菜单权限；数据范围仍由原空间服务裁决。
        r.put("visibleSpaces",eligible?spaces.list(new SpaceService.Scope(id,PaismartIdentity.current().administrator())).stream()
            .map(space->Map.of("id",space.id(),"name",space.name())).toList():List.of());
        return AjaxResult.success(r);
    }
    @PostMapping("/members/me/eligibility")
    @Log(title="知识库邀请资格",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult claim(@RequestBody Map<String,Object> body) {invites.redeem(PaismartIdentity.userId(),Objects.toString(body.get("code"),""));return me();}
    @GetMapping("/members")
    @PreAuthorize("@ss.hasPermi('paismart:space:member')")
    public TableDataInfo members(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String userName,@RequestParam(required=false) String keyword,@RequestParam(required=false) String spaceId,@RequestParam(required=false) String status) {
        int size=Math.max(1,Math.min(100,pageSize));
        SpaceService.Scope actor=manager();
        if(!actor.administrator() && spaces.list(actor).stream().noneMatch(space -> "ORGANIZATION".equals(space.type()) && space.canManage())) return table(List.of(),0);
        if(status!=null && !status.isBlank() && !Set.of("0","1").contains(status)) throw new IllegalArgumentException("平台账户状态无效");
        boolean filterSpace=spaceId!=null&&!spaceId.isBlank();
        Set<Long> spaceMembers=Set.of();
        if(filterSpace) {
            if(!"ORGANIZATION".equals(spaces.get(spaceId).type()) || !spaces.canManageSpace(actor,spaceId)) throw new com.ruoyi.common.exception.ServiceException("没有组织成员查询权限",403);
            spaceMembers=new HashSet<>(spaces.members(actor,spaceId).stream().map(SpaceService.Member::userId).toList());
            if(spaceMembers.isEmpty()) return table(List.of(),0);
        }
        SysUser query=new SysUser();query.setUserName(userName.isBlank() && keyword!=null?keyword:userName);
        query.setStatus(status);
        // 复用平台@DataScope和分页设施，只组装业务资格元数据，绝不序列化SysUser密码/令牌。
        // 组织过滤在平台可见用户集合内先裁决，再分页，防止过滤当前页造成总数错误。
        if(!filterSpace) PageHelper.startPage(Math.max(1,pageNum),size);
        List<SysUser> selected;
        long total;
        try {selected=users.selectUserList(query);total=new PageInfo<>(selected).getTotal();}
        finally {PageHelper.clearPage();}
        if(filterSpace) {
            Set<Long> allowed=spaceMembers;
            List<SysUser> filtered=selected.stream().filter(user->allowed.contains(user.getUserId())).toList();
            total=filtered.size();long offset=(long)(Math.max(1,pageNum)-1)*size;
            selected=filtered.subList((int)Math.min(offset,total),(int)Math.min(offset+size,total));
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        for(SysUser user:selected) rows.add(memberView(actor,user));
        return table(rows,total);
    }
    public TableDataInfo members(int pageNum,int pageSize,String userName,String keyword) {return members(pageNum,pageSize,userName,keyword,null,null);}
    private TableDataInfo table(List<?> rows,long total) {var result=PaismartTables.page(rows,total);result.setCode(200);result.setMsg("查询成功");return result;}
    @PutMapping("/members/{id}/spaces")
    @PreAuthorize("@ss.hasPermi('paismart:space:member')")
    @Log(title="知识库成员空间",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    @org.springframework.transaction.annotation.Transactional("paismartTransactionManager")
    public AjaxResult spaces(@PathVariable long id,@RequestBody MemberSpacesRequest input,@RequestHeader(value="If-Match",required=false) String match) {
        SpaceService.Scope actor=manager();users.checkUserDataScope(id);
        Long version=input.rowVersion()==null?input.expectedVersion():input.rowVersion();
        if(match!=null) try {version=Long.valueOf(match.replace("\"",""));} catch(NumberFormatException invalid) {throw new IllegalArgumentException("成员版本标识无效");}
        if(version==null || version<0) throw new IllegalArgumentException("需要当前成员空间版本");
        spaces.setMemberAssignments(actor,id,input.assignments(),input.primarySpaceId(),version);
        return AjaxResult.success(memberView(actor,users.selectUserById(id)));
    }
    private Map<String,Object> memberView(SpaceService.Scope actor,SysUser user) {
        long id=user.getUserId();boolean enabled=membership.isEligible(id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("userId",id);result.put("userName",user.getUserName());result.put("nickName",user.getNickName());result.put("enabled",enabled);result.put("eligible",enabled);result.put("status",enabled?"ACTIVE":"INELIGIBLE");result.put("platformStatus",user.getStatus());result.put("rowVersion",spaces.membershipVersion(id));result.put("usage",quotas.snapshot(id));
        result.put("createdAt",user.getCreateTime());
        memberSpaces(result,spaces.managementMemberships(actor,id));return result;
    }
    @PostMapping("/members/{id}/credits")
    @PreAuthorize("@ss.hasPermi('paismart:usage:adjust')")
    @Log(title="知识库追加额度",businessType=BusinessType.UPDATE)
    @org.springframework.transaction.annotation.Transactional("paismartTransactionManager")
    public AjaxResult credit(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestHeader("Idempotency-Key") String key) {
        manager();users.checkUserDataScope(id);
        String reason=Objects.toString(b.get("reason"),"");QuotaService.text(reason,500);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND del_flag='0'",Integer.class,id)!=1) throw new IllegalArgumentException("平台账户不存在");
        long llm=number(b,"llmTokens",number(b,"chatTokens",0)),emb=number(b,"embeddingTokens",0);
        QuotaService.nonnegative(llm);QuotaService.nonnegative(emb);
        quotas.credit(id,"LLM",llm,"admin:"+key+":"+id+":LLM",reason,PaismartIdentity.userId());
        quotas.credit(id,"EMBEDDING",emb,"admin:"+key+":"+id+":EMBEDDING",reason,PaismartIdentity.userId());
        return AjaxResult.success();
    }
    @PutMapping("/members/{id}/quota-policy")
    @PreAuthorize("@ss.hasPermi('paismart:usage:adjust')")
    @Log(title="知识库额度策略",businessType=BusinessType.UPDATE)
    public AjaxResult quota(@PathVariable long id,@RequestBody Map<String,Object>b,@RequestHeader("If-Match") long version) {manager();users.checkUserDataScope(id);quotas.savePolicy(id,Objects.toString(b.get("mode"),"BALANCE"),number(b,"dailyLlmTokens",0),number(b,"dailyEmbeddingTokens",0),version);return AjaxResult.success(quotas.policy(id));}
    @GetMapping("/members/{id}/quota-policy") @PreAuthorize("@ss.hasPermi('paismart:usage:adjust')")
    public AjaxResult quota(@PathVariable long id){manager();users.checkUserDataScope(id);return AjaxResult.success(quotas.policy(id));}
    private void memberSpaces(Map<String,Object> result,List<SpaceService.Member> list){result.put("spaces",list);result.put("spaceIds",list.stream().map(SpaceService.Member::spaceId).toList());result.put("assignments",list);result.put("primarySpaceId",list.stream().filter(SpaceService.Member::primary).map(SpaceService.Member::spaceId).findFirst().orElse(null));}
    private static long number(Map<String,Object>b,String k,long fallback) {Object v=b.get(k);return v==null?fallback:com.ruoyi.paismart.infrastructure.Numbers.integer(v);}
}
