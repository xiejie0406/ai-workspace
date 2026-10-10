package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/terminal-client/v1")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalClientController {
    private final PcTerminalClientGateway gateway;
    public PcTerminalClientController(PcTerminalClientGateway g){gateway=g;}
    @PostMapping("/gateway")
    public AjaxResult invoke(HttpServletRequest request,@RequestBody Map<String,Object> input){var identity=(PcTerminalService.CapabilityIdentity)request.getAttribute("paicliTerminalCapability");if(identity==null)throw com.ruoyi.paicli.domain.PcException.deny("缺少有限终端身份");return AjaxResult.success(gateway.execute(identity,PcJson.text(input,"op"),PcJson.object(input,"params")));}
}
