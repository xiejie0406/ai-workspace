package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 原三个调试请求的薄桥；只有Service能写现有运行/问答事实。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyDebugController {
    private final LegacyDebugBridgeService bridge;private final LegacyDebugStreamService streams;
    public LegacyDebugController(LegacyDebugBridgeService bridge,LegacyDebugStreamService streams){this.bridge=bridge;this.streams=streams;}
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermi('paiflow:run:create') && @ss.hasPermi('paiflow:run:query')")
    @Log(title="PaiFlow原页调试",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public SseEmitter chat(@RequestBody JSONObject body,@RequestHeader("Authorization") String authorization,
            @RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="Last-Event-ID",required=false) String cursor,HttpServletResponse response) {
        var session=streams.session(authorization);
        try(var capacity=streams.reserve(session)) {
            var attachment=bridge.chat(body,space,cursor);headers(response,attachment);return capacity.open(attachment);
        }
    }
    @PostMapping("/resume")
    @PreAuthorize("@ss.hasPermi('paiflow:run:resume') && @ss.hasPermi('paiflow:run:query')")
    @Log(title="PaiFlow原页问答回复",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public Object resume(@RequestBody JSONObject body,@RequestHeader("Authorization") String authorization,
            @RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="Last-Event-ID",required=false) String cursor,
            @RequestHeader(value="X-PaiFlow-Reply-Only",required=false) String replyOnly,HttpServletResponse response) {
        var session=streams.session(authorization);
        if(replyOnly!=null && !"true".equals(replyOnly))throw new ServiceException("问答回执模式无效",400);
        if(replyOnly!=null) {
            var result=bridge.resume(body,space,cursor);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            return LegacyResult.success(new JSONObject(result.checkpoint()).fluentPut("runId",result.attachment().runId()));
        }
        try(var capacity=streams.reserve(session)) {
            var result=bridge.resume(body,space,cursor);headers(response,result.attachment());return capacity.open(result.attachment());
        }
    }
    @PostMapping("/cancel")
    @PreAuthorize("@ss.hasPermi('paiflow:run:cancel')")
    @Log(title="PaiFlow原页停止请求",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<com.ruoyi.paiflow.service.RunService.View> cancel(@RequestBody JSONObject body,
            @RequestHeader("Authorization") String authorization,@RequestHeader(value="space-id",required=false) String space) {
        streams.session(authorization);return LegacyResult.success(bridge.cancel(body,space));
    }
    private static void headers(HttpServletResponse response,LegacyDebugBridgeService.Attachment attachment) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
        response.setHeader("X-PaiFlow-Run-Id",attachment.runId());
        response.setHeader("X-PaiFlow-Workflow-Version-Id",Long.toString(attachment.workflowVersionId()));
        response.setHeader("Access-Control-Expose-Headers","X-PaiFlow-Run-Id,X-PaiFlow-Workflow-Version-Id");
    }
}
