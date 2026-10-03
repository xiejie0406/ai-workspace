package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.PaismartIdentity;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart")
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class SpaceController extends BaseController {
    public record SpaceRequest(String parentId,String name,Long deptId,Integer uploadLimit,Long rowVersion,Long expectedVersion,Long uploadMaxSizeBytes,String description) {}
    public record MemberRequest(long userId,boolean uploadAllowed,boolean managerAllowed) {}
    public record PrimaryRequest(String spaceId) {}
    private final SpaceService spaces;
    private final MembershipService memberships;
    public SpaceController(SpaceService spaces,MembershipService memberships) {this.spaces=spaces;this.memberships=memberships;}
    private Scope actor() {var a=PaismartIdentity.current();if(!a.administrator()) memberships.requireEligible(a.userId());return new Scope(a.userId(),a.administrator());}
    @GetMapping("/spaces")
    @PreAuthorize("@ss.hasPermi('paismart:space:list')")
    public TableDataInfo list() {List<SpaceService.Space> rows=spaces.list(actor());TableDataInfo out=PaismartTables.page();out.setCode(200);out.setMsg("查询成功");out.setRows(rows);out.setTotal(rows.size());return out;}
    @PostMapping("/spaces/personal")
    @PreAuthorize("@ss.hasPermi('paismart:space:list')")
    @Log(title="个人知识空间",businessType=BusinessType.INSERT)
    public AjaxResult personal() {return success(spaces.personal(actor()));}
    @PostMapping("/spaces")
    @PreAuthorize("@ss.hasPermi('paismart:space:edit')")
    @Log(title="知识空间",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody SpaceRequest input) {return success(spaces.save(actor(),null,input.parentId(),input.name(),input.deptId(),input.uploadLimit()==null?-1:input.uploadLimit(),null,input.uploadMaxSizeBytes(),input.description()));}
    @PutMapping("/spaces/{id}")
    @PreAuthorize("@ss.hasPermi('paismart:space:edit')")
    @Log(title="知识空间",businessType=BusinessType.UPDATE)
    public AjaxResult update(@PathVariable String id,@RequestBody SpaceRequest input) {return success(spaces.save(actor(),id,input.parentId(),input.name(),input.deptId(),input.uploadLimit()==null?-1:input.uploadLimit(),input.rowVersion()==null?input.expectedVersion():input.rowVersion(),input.uploadMaxSizeBytes(),input.description()));}
    @DeleteMapping("/spaces/{id}")
    @PreAuthorize("@ss.hasPermi('paismart:space:edit')")
    @Log(title="知识空间",businessType=BusinessType.DELETE)
    public AjaxResult delete(@PathVariable String id) {spaces.delete(actor(),id);return success();}
    @GetMapping("/spaces/{id}/members")
    @PreAuthorize("@ss.hasPermi('paismart:space:member')")
    public AjaxResult members(@PathVariable String id) {return success(spaces.members(actor(),id));}
    @PutMapping("/spaces/{id}/members")
    @PreAuthorize("@ss.hasPermi('paismart:space:member')")
    @Log(title="知识空间成员",businessType=BusinessType.UPDATE)
    public AjaxResult member(@PathVariable String id,@RequestBody MemberRequest input) {spaces.setMember(actor(),id,input.userId(),input.uploadAllowed(),input.managerAllowed(),false);return success();}
    @DeleteMapping("/spaces/{id}/members/{userId}")
    @PreAuthorize("@ss.hasPermi('paismart:space:member')")
    @Log(title="知识空间成员",businessType=BusinessType.DELETE)
    public AjaxResult removeMember(@PathVariable String id,@PathVariable long userId) {spaces.setMember(actor(),id,userId,false,true);return success();}
    @RequestMapping(value="/members/me/primary-space",method={RequestMethod.PUT,RequestMethod.POST})
    @PreAuthorize("@ss.hasPermi('paismart:space:list')")
    public AjaxResult primary(@RequestBody PrimaryRequest input) {spaces.primary(actor(),input.spaceId());return success();}
}
