package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 仅保全原SQL实际prompt/type种子；本包不存在正式工作流模板实体，不公共化用户画布。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class AgentTemplateCatalog {
    private final JSONObject source;
    public AgentTemplateCatalog() {
        try(var input=getClass().getResourceAsStream("/paiflow/agent-source-catalog.json")) {
            if(input==null)throw new IOException("missing source catalog");source=JSON.parseObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            if(source.getJSONArray("promptTemplates")==null || source.getJSONArray("types")==null)throw new IOException("invalid source catalog");
        }catch(IOException error) { throw new ServiceException("原智能体模板资产缺失",503); }
    }
    public List<JSONObject> templates(String language,Integer id) {
        String selected=language(language);
        var rows=source.getJSONArray("promptTemplates").toJavaList(JSONObject.class).stream()
            .filter(value->selected.equals(value.getString("language")) && (id==null || id.equals(value.getInteger("id"))))
            .sorted(Comparator.comparing(value->value.getInteger("id"))).map(AgentTemplateCatalog::template).toList();
        if(id!=null && rows.isEmpty())throw new ServiceException("原指令模板不存在",404);return rows;
    }
    public List<JSONObject> types(String language) {
        return source.getJSONArray("types").toJavaList(JSONObject.class).stream().filter(value->value.getIntValue("isAct")==1)
            .sorted(Comparator.comparing(value->value.getInteger("orderNum"))).map(value->{var copy=JSON.parseObject(value.toJSONString());if("en".equals(language(language)) && copy.getString("typeNameEn")!=null)copy.put("typeName",copy.getString("typeNameEn"));return copy;}).toList();
    }
    private static String language(String header) { if(header==null)return "zh";try { var ranges=java.util.Locale.LanguageRange.parse(header);return !ranges.isEmpty() && java.util.Locale.forLanguageTag(ranges.get(0).getRange()).getLanguage().equals("en")?"en":"zh"; }catch(IllegalArgumentException error) { return "zh"; } }
    private static JSONObject template(JSONObject value) {
        var copy=JSON.parseObject(value.toJSONString());copy.put("inputExampleList",JSON.parseArray(copy.getString("inputExample")));
        copy.put("promptStructList",JSON.parseArray(copy.getString("promptStructList")));return copy;
    }
}
