package com.ruoyi.aipicture.tool;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;

@Component
@AiPictureEnabled
public class ToolRegistry {
    private final Map<String,Object> registry;
    private final Map<String,Map<String,Object>> tools = new LinkedHashMap<>();
    private final Map<String,Object> documentSchema;
    private final SchemaValidator validator=new SchemaValidator();
    public ToolRegistry(AiPictureProperties p) throws java.io.IOException {
        registry=load(p,"tools.schema.json");
        documentSchema=load(p,"document.schema.json");
        for(Object value:Json.list(registry.get("tools"))) { var tool=Json.map(value); tools.put(Json.string(tool.get("name")),tool); }
        if(tools.size()!=22) throw new IllegalStateException("图片工具契约必须覆盖原全部 22 个工具");
    }
    private Map<String,Object> load(AiPictureProperties p,String file) throws java.io.IOException {
        if(!p.getContractsDirectory().isBlank()) return Json.map(Files.readString(Path.of(p.getContractsDirectory()).toAbsolutePath().resolve(file)));
        try(var input=ToolRegistry.class.getResourceAsStream("/contracts/aipicture/v1/"+file)) {
            if(input==null) throw new IllegalStateException("图片业务制品缺少唯一契约 "+file);
            return Json.map(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public Map<String,Object> registry() { return registry; }
    public Map<String,Object> tool(String name) {
        var tool=tools.get(name); if(tool==null) throw AiPictureException.invalid("未知工具："+name); return tool;
    }
    public Map<String,Object> validate(String name,Map<String,Object> params) {
        var spec=tool(name); var copy=Json.copy(params); var schema=Json.map(spec.get("paramsSchema"));
        for(var e:Json.map(schema.get("properties")).entrySet()) {
            var property=Json.map(e.getValue()); if(!copy.containsKey(e.getKey())&&property.containsKey("default")) copy.put(e.getKey(),property.get("default"));
        }
        validator.validate(copy,schema); return copy;
    }
    public void document(Map<String,Object> document) {
        validator.validate(document,documentSchema);long w=Json.number(document.get("width")),h=Json.number(document.get("height"));
        if(w<1||h<1||w>50_000_000L||h>50_000_000L||w*h>50_000_000L) throw AiPictureException.invalid("文档画幅至少1像素且不超过5000万像素");
        var ids=new HashSet<String>();for(Object object:Json.list(document.get("layers"))) {
            var layer=Json.map(object);if(!ids.add(Json.string(layer.get("id")))) throw AiPictureException.invalid("图层ID不能重复");
            if(Json.number(layer.get("width"))<1||Json.number(layer.get("height"))<1) throw AiPictureException.invalid("图层宽高必须为正数");
            double opacity=Json.decimal(layer.get("opacity"),1);if(!Double.isFinite(opacity)||opacity<0||opacity>1) throw AiPictureException.invalid("图层透明度需要0–1");
            var transform=Json.map(layer.get("transform"));for(String axis:List.of("scale_x","scale_y")) if(Json.decimal(transform.get(axis),1)==0) throw AiPictureException.invalid("图层缩放不能为0");
        }
    }
    public void versions(Map<String,Object> request) {
        String schema=Json.string(request.getOrDefault("schemaVersion",request.get("schema_version")));
        String tools=Json.string(request.getOrDefault("toolRegistryVersion",request.get("tool_registry_version")));
        if(!Json.string(registry.get("schemaVersion")).equals(schema)||!Json.string(registry.get("toolRegistryVersion")).equals(tools))
            throw new AiPictureException(409,"CONTRACT_VERSION_MISMATCH","客户端与服务端工具契约版本不一致，请更新页面");
    }
}
