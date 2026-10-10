package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.ModelOperationJson;
import com.ruoyi.paiflow.service.ModelOperationService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import com.ruoyi.paiflow.service.DataWorkerOperationService;
import com.ruoyi.paiflow.service.ToolOperationService;
import com.ruoyi.common.exception.ServiceException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原Python节点通过服务身份访问公开绑定/操作事实；私有SDK许可不在HTTP路由中暴露。 */
@RestController @Anonymous @RequestMapping("/internal/paiflow/v1/runs")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerModelController {
    private final WorkerIdentityService identities;private final ModelOperationService models;
    private org.springframework.beans.factory.ObjectProvider<DataWorkerOperationService> dataOperations;
    private org.springframework.beans.factory.ObjectProvider<ToolOperationService> toolOperations;
    @org.springframework.beans.factory.annotation.Autowired public void useToolOperations(org.springframework.beans.factory.ObjectProvider<ToolOperationService> provider){toolOperations=provider;}
    private ToolOperationService tools(){var service=toolOperations==null?null:toolOperations.getIfAvailable();if(service==null)throw new ServiceException("工具中心尚未启用，请完成030及用途配置",503);return service;}
    @org.springframework.beans.factory.annotation.Autowired public void useDataOperations(org.springframework.beans.factory.ObjectProvider<DataWorkerOperationService> provider){dataOperations=provider;}
    private DataWorkerOperationService data(){var service=dataOperations==null?null:dataOperations.getIfAvailable();if(service==null)throw new ServiceException("中心数据操作尚未启用，请先完成026迁移及配置",503);return service;}
    public WorkerModelController(WorkerIdentityService identities,ModelOperationService models){this.identities=identities;this.models=models;}
    private static JSONObject json(Map<String,Object> value){return JSON.parseObject(ModelOperationJson.canonical(value,ModelOperationJson.REQUEST_BYTES));}
    @PostMapping("/{runId}/resources/{resourceId}")
    public AjaxResult resource(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String resourceId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);var request=json(body);return AjaxResult.success(resourceId.startsWith("data:")?data().resource(runId,worker,resourceId,request):resourceId.startsWith("tool:")?tools().resource(runId,worker,resourceId,request):models.resource(runId,worker,resourceId,request));}
    @PostMapping("/{runId}/operations")
    public AjaxResult start(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);var request=json(body);boolean isData=request.get("parameters") instanceof JSONObject parameters && ("database".equals(parameters.get("kind")) || (parameters.get("resourceId") instanceof String id && id.startsWith("data:")));boolean isTool=request.getString("operation")!=null&&request.getString("operation").startsWith("tools.")||request.get("parameters") instanceof JSONObject p&&("tool".equals(p.get("kind"))||p.get("resourceId") instanceof String id&&id.startsWith("tool:"));return AjaxResult.success(isTool?tools().start(runId,worker,request):isData?data().start(runId,worker,request):models.start(runId,worker,request));}
    @PostMapping("/{runId}/operations/{operationId}/status")
    public AjaxResult status(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String operationId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(tools().status(runId,worker,operationId,json(body)));}
    @PostMapping("/{runId}/operations/{operationId}/frames")
    public AjaxResult frames(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String operationId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(models.frames(runId,worker,operationId,json(body)));}
    @PostMapping("/{runId}/operations/{operationId}/cancel")
    public AjaxResult cancel(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String operationId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(operationId.startsWith("tool-op:")?tools().cancel(runId,worker,operationId,json(body)):models.cancel(runId,worker,operationId,json(body)));}
}
