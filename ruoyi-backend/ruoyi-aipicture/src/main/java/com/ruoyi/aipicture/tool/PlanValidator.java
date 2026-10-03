package com.ruoyi.aipicture.tool;

import java.util.*;
import com.ruoyi.aipicture.domain.*;

public final class PlanValidator {
    private final ToolRegistry registry;
    public PlanValidator(ToolRegistry registry) { this.registry=registry; }
    public List<Object> validate(List<Object> steps) {
        if(steps.size()>8) throw AiPictureException.invalid("计划最多 8 步");
        var ids=new HashSet<String>(); var seen=new HashSet<String>(); var result=new ArrayList<Object>();
        for(Object object:steps) {
            var step=Json.copy(Json.map(object)); String id=Json.string(step.get("id"));
            if(id.isBlank()||id.length()>64||!ids.add(id)) throw AiPictureException.invalid("计划步骤ID为空或重复");
        }
        // 源计划按拓扑顺序执行，依赖必须在前，直接拒绝环/缺失依赖。
        for(Object object:steps) {
            var step=Json.copy(Json.map(object)); String id=Json.string(step.get("id")),tool=Json.string(step.get("tool"));
            var params=Json.map(step.get("params"));
            for(Object hidden:Json.list(registry.tool(tool).get("agentHidden"))) if(params.get(Json.string(hidden))!=null&&!Objects.equals(params.get(Json.string(hidden)),List.of())) throw AiPictureException.invalid("模型不能设置隐藏字段 "+hidden);
            for(String hidden:List.of("owner","user_id","mask_asset_id","revision","object_key","current_asset_id","selection_markers","selection_strokes")) if(params.containsKey(hidden)) throw AiPictureException.invalid("模型不能设置服务字段 "+hidden);
            var dependencies=Json.list(step.getOrDefault("depends_on",step.getOrDefault("dependencies",List.of())));
            for(Object dependency:dependencies) if(!seen.contains(Json.string(dependency))) throw AiPictureException.invalid("计划存在环或依赖顺序/ID不合法");
            step.put("params",registry.validate(tool,params));step.put("depends_on",dependencies);step.put("status","pending");result.add(step);seen.add(id);
        }
        return result;
    }
}
