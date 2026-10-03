package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.SpaceService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/enterprise")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyEnterpriseController {
    private final SpaceService spaces;
    public LegacyEnterpriseController(SpaceService spaces) { this.spaces=spaces; }
    public record Create(String name,String avatarUrl) {}
    @PostMapping("/create")
    @Log(title="PaiFlow团队",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Long> create(@RequestBody Create body) { return LegacyResult.success(spaces.createTeam(body.name(),body.avatarUrl())); }
    @GetMapping("/visit-enterprise")
    public LegacyResult<Boolean> visit(@RequestParam(required=false) Long enterpriseId) { return LegacyResult.success(spaces.visitTeam(enterpriseId)); }
    @GetMapping("/check-name")
    public LegacyResult<Boolean> checkName(@RequestParam String name,@RequestParam(required=false) Long id) { return LegacyResult.success(spaces.checkTeamName(name,id)); }
    @GetMapping("/check-need-create-team")
    public LegacyResult<Integer> checkCreate() { return LegacyResult.success(spaces.checkNeedCreateTeam()); }
    @GetMapping("/check-certification")
    public LegacyResult<Boolean> certification() { throw new ServiceException("原商业认证与真实套餐依据尚未接通",503); }
    @GetMapping("/detail")
    public LegacyResult<SpaceService.TeamSummary> detail(@RequestHeader("enterprise-id") long id) { return LegacyResult.success(spaces.teamDetail(id)); }
    @GetMapping("/join-list")
    public LegacyResult<List<SpaceService.TeamSummary>> list() { return LegacyResult.success(spaces.joinedTeams()); }
    @PostMapping("/update-name")
    @Log(title="PaiFlow团队名称",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> name(@RequestHeader("enterprise-id") long id,@RequestParam String name) { spaces.updateTeam(id,"name",name);return LegacyResult.success("更新成功"); }
    @PostMapping("/update-avatar")
    @Log(title="PaiFlow团队头像",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> avatar(@RequestHeader("enterprise-id") long id,@RequestParam String avatarUrl) { spaces.updateTeam(id,"avatarUrl",avatarUrl);return LegacyResult.success("更新成功"); }
    @PostMapping("/update-logo")
    @Log(title="PaiFlow团队标志",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> logo(@RequestHeader("enterprise-id") long id,@RequestParam String logoUrl) { spaces.updateTeam(id,"logoUrl",logoUrl);return LegacyResult.success("更新成功"); }
}
