package com.ruoyi.paiflow.controller;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.paiflow.service.ToolService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/** 标准中心维护入口；公开返回不含私有出口/凭据。 */
@RestController @RequestMapping("/paiflow/tools") @ConditionalOnProperty(prefix="paiflow",name={"enabled","tools.enabled"},havingValue="true")
public class ToolController {
 private final ToolService tools;public ToolController(ToolService t){tools=t;}
 @PostMapping public AjaxResult create(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject body){return AjaxResult.success(tools.create(s,body,false));}
 @GetMapping public TableDataInfo list(@RequestHeader(value="space-id",required=false)String s,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="10")int pageSize,@RequestParam(required=false)String search){var page=tools.list(tools.scope(s,null,"paiflow:tool:query",false),pageNum,pageSize,search);var result=new TableDataInfo(page.records(),page.total());result.setCode(200);result.setMsg("查询成功");return result;}
 @GetMapping("/{id}")public AjaxResult detail(@RequestHeader(value="space-id",required=false)String s,@PathVariable long id){return AjaxResult.success(tools.detail(id,tools.scope(s,null,"paiflow:tool:query",false)));}
 @PutMapping("/{id}")public AjaxResult update(@RequestHeader(value="space-id",required=false)String s,@PathVariable long id,@RequestBody JSONObject body){return AjaxResult.success(tools.update(id,tools.scope(s,body.get("spaceId"),"paiflow:tool:edit",false),body.getLong("revision"),body));}
 @DeleteMapping("/{id}")public AjaxResult delete(@RequestHeader(value="space-id",required=false)String s,@PathVariable long id,@RequestParam Long revision){return AjaxResult.success(tools.change(id,tools.scope(s,null,"paiflow:tool:remove",false),revision,true,false));}
}
