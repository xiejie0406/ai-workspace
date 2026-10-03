package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.SpaceService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/space")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class LegacySpaceController {
    private final SpaceService spaces;
    public LegacySpaceController(SpaceService spaces) { this.spaces = spaces; }
    public record Create(String name, String description, String avatarUrl) {}
    public record Update(long id, String name, String description, String avatarUrl, Long revision) {}
    @PostMapping("/create-personal-space")
    @Log(title = "PaiFlow个人空间", businessType = BusinessType.INSERT)
    public LegacyResult<Long> create(@RequestBody Create body) {
        return LegacyResult.success(spaces.createPersonal(body.name(),body.description(),body.avatarUrl()));
    }
    @PostMapping("/update-personal-space")
    @Log(title = "PaiFlow个人空间", businessType = BusinessType.UPDATE)
    public LegacyResult<String> update(@RequestBody Update body) {
        spaces.updatePersonal(body.id(),body.name(),body.description(),body.avatarUrl(),body.revision());
        return LegacyResult.success("更新成功");
    }
    @GetMapping("/personal-list")
    public LegacyResult<List<SpaceService.Summary>> list(@RequestParam(required=false) String name) { return LegacyResult.success(spaces.listPersonal(name,false)); }
    @GetMapping("/personal-self-list")
    public LegacyResult<List<SpaceService.Summary>> owned(@RequestParam(required=false) String name) { return LegacyResult.success(spaces.listPersonal(name,true)); }
    @GetMapping("/check-name")
    public LegacyResult<Boolean> checkName(@RequestParam String name,@RequestParam(required=false) Long id) { return LegacyResult.success(spaces.checkName(name,id)); }
    @GetMapping("/detail")
    public LegacyResult<SpaceService.Summary> detail(@RequestHeader("space-id") long id) { return LegacyResult.success(spaces.detail(id)); }
    @GetMapping("/visit-space")
    public LegacyResult<SpaceService.Summary> visit(@RequestParam(required=false) Long spaceId) { return LegacyResult.success(spaces.visit(spaceId)); }
    @GetMapping("/recent-visit-list")
    public LegacyResult<List<SpaceService.Summary>> recent() { return LegacyResult.success(spaces.recent()); }
    @GetMapping("/get-last-visit-space")
    public LegacyResult<SpaceService.Summary> last() { return LegacyResult.success(spaces.last()); }
}
