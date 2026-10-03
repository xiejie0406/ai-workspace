package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.CheckpointService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 原缺失resume接口不伪装为已存在；中心标准入口供后续原交互最小适配。 */
@RestController
@RequestMapping("/paiflow/runs/{runId}/checkpoints")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class CheckpointController {
    private final CheckpointService checkpoints;
    public CheckpointController(CheckpointService checkpoints){this.checkpoints=checkpoints;}
    @GetMapping("/{checkpointId}")
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public AjaxResult detail(@PathVariable String runId,@PathVariable String checkpointId){return AjaxResult.success(checkpoints.detail(runId,checkpointId));}
    @PostMapping("/{checkpointId}/reply")
    @PreAuthorize("@ss.hasPermi('paiflow:run:resume')")
    @Log(title="工作流问答回复",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult reply(@PathVariable String runId,@PathVariable String checkpointId,@RequestBody Map<String,Object> body){return AjaxResult.success(checkpoints.reply(runId,checkpointId,JSON.parseObject(JSON.toJSONString(body))));}
}
