package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.TextConfigService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 保留原text-handle与flow-manager调用的方法、布尔/整数及列表形状。 */
@RestController
@RequestMapping("/paiflow/legacy/textNode/config")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyTextConfigController {
    private final TextConfigService configs;
    public LegacyTextConfigController(TextConfigService configs) { this.configs=configs; }
    @GetMapping("/list") public LegacyResult<List<TextConfigService.View>> list() { return LegacyResult.success(configs.list()); }
    @PostMapping("/save")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> create(@RequestBody JSONObject body) { configs.create(body);return LegacyResult.success(true); }
    @PostMapping("/update")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> update(@RequestBody JSONObject body) { configs.update(TextConfigService.number(body.get("id"),true),body);return LegacyResult.success(true); }
    @GetMapping("/delete")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Integer> delete(@RequestParam long id,@RequestParam(required=false) Long revision) { configs.delete(id,revision);return LegacyResult.success(1); }
}
