package com.ruoyi.paiflow.adapter.legacy;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.ToolService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/** 原tool服务的维护协议映射；所有资源及版本仍通过同一中心Service。 */
@RestController @RequestMapping("/paiflow/legacy/tool") @ConditionalOnProperty(prefix="paiflow",name={"enabled","tools.enabled"},havingValue="true")
public class LegacyToolController {
 private final ToolService tools;public LegacyToolController(ToolService t){tools=t;}
 @PostMapping("/create-tool")public LegacyResult<JSONObject> create(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject body){return LegacyResult.success(tools.create(s,body,false));}
 @PostMapping("/temporary-tool")public LegacyResult<JSONObject> temporary(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject body){return LegacyResult.success(tools.create(s,body,true));}
 @PutMapping("/update-tool")public LegacyResult<JSONObject> update(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject body){return LegacyResult.success(tools.update(body.getLongValue("id"),tools.scope(s,body.get("spaceId"),"paiflow:tool:edit",false),body.getLong("revision"),body));}
 @GetMapping("/detail")public LegacyResult<JSONObject> detail(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id,@RequestParam(required=false)Boolean temporary){return LegacyResult.success(tools.detail(id,tools.scope(s,null,"paiflow:tool:query",false),Boolean.TRUE.equals(temporary)));}
 @GetMapping("/list-tools")public LegacyResult<ToolService.Page> list(@RequestHeader(value="space-id",required=false)String s,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="10")int pageSize,@RequestParam(defaultValue="")String content,@RequestParam(required=false)Integer status){return LegacyResult.success(tools.list(tools.scope(s,null,"paiflow:tool:query",false),pageNo,pageSize,content,status));}
 @DeleteMapping("/delete-tool")public LegacyResult<JSONObject> delete(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id,@RequestParam Long revision){return LegacyResult.success(tools.change(id,tools.scope(s,null,"paiflow:tool:remove",false),revision,true,false));}
 @GetMapping("/get-tool-version")public LegacyResult<java.util.List<JSONObject>> versions(@RequestHeader(value="space-id",required=false)String s,@RequestParam String toolId){return LegacyResult.success(tools.versions(toolId,tools.scope(s,null,"paiflow:tool:query",false)));}
}
