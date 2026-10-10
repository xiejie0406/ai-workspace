package com.ruoyi.paicli.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paicli.service.PcTerminalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** 必须原平台JWT登录且当前窗口主动确认；CLI能力本身无跨目录扩权。 */
@RestController @RequestMapping("/paicli/v1/terminals")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalAuthorizationController {
    private final PcTerminalService terminals;
    public PcTerminalAuthorizationController(PcTerminalService t){terminals=t;}
    @PostMapping("/{id}/authorizations") @PreAuthorize("@ss.hasPermi('paicli:terminal:use')")
    public AjaxResult decision(@PathVariable long id,@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.authorizeOperation(id,SecurityUtils.getLoginUser(),input));}
}
