package com.ruoyi.aden.application.collection;

import java.util.*;

import static com.ruoyi.aden.application.collection.AdenCollectionService.*;

/** 只校验本次确认的分区身份与版本；网页数据始终是资料，不形成代码或网络访问许可。 */
public final class AdenCollectionExtraction {
    public static final Set<String> SECTION_IDS = Set.of("basic", "params", "intro", "detail", "main", "gallery", "detailImages");
    private AdenCollectionExtraction() { }

    public static Map<String,Object> validate(Map<String,Object> body) {
        if (!body.containsKey("extraction")) return map("legacy", true);
        Map<String,Object> input = object(body.get("extraction"));
        keys(input, Set.of("protocolVersion", "documentId", "pageFingerprint", "selectionRevision",
                "confirmationRevision", "selectedSections", "sections", "parameterRows", "ranges"));
        if (!(input.get("protocolVersion") instanceof Integer n) || n != 1) throw invalid("分区协议版本无效");
        String document = text(input, "documentId", 128, true);
        String page = text(input, "pageFingerprint", 64, true);
        if (!page.matches("[0-9a-f]{64}")) throw invalid("页面指纹无效");
        if (!document.equals(text(body, "documentId", 128, true))) throw invalid("页面实例不一致");
        String selection = revision(input, "selectionRevision", false);
        revision(input, "confirmationRevision", true);
        List<Object> selected = listValue(input.get("selectedSections"));
        if (selected.isEmpty() || selected.size() > 7) throw invalid("至少选择一个有效分区");
        Set<String> selectedIds = new LinkedHashSet<>();
        for (Object id : selected) if (!(id instanceof String s) || !SECTION_IDS.contains(s) || !selectedIds.add(s)) throw invalid("所选分区重复或无效");
        List<Map<String,Object>> sections = objects(input.get("sections"));
        if (sections.size() != selectedIds.size()) throw invalid("分区结果与所选范围不一致");
        Map<String,Set<String>> sources = new LinkedHashMap<>();
        int sourceCount = 0;
        for (Map<String,Object> section : sections) {
            keys(section, Set.of("sectionId", "documentId", "pageFingerprint", "selectionRevision", "rangeRevision",
                    "extractedRangeRevision", "resultRevision", "sourceIds", "missingReason", "missingConfirmed"));
            String id = text(section, "sectionId", 20, true);
            if (!selectedIds.contains(id) || sources.containsKey(id)) throw invalid("分区结果重复或不在本次范围");
            if (!document.equals(text(section, "documentId", 128, true)) || !page.equals(text(section, "pageFingerprint", 64, true))
                    || !selection.equals(revision(section, "selectionRevision", false))) throw invalid("分区来自过期页面或选择");
            if (!revision(section, "rangeRevision", false).equals(revision(section, "extractedRangeRevision", false))) throw invalid("分区范围已变化，请重新提取");
            revision(section, "resultRevision", true);
            List<Object> values = listValue(section.get("sourceIds"));
            if (values.size() > 1000) throw invalid("分区来源数量超限");
            Set<String> ids = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String s) || s.isBlank() || s.length() > 128 || s.chars().anyMatch(Character::isISOControl) || !ids.add(s)) throw invalid("来源标识重复或无效");
            }
            String reason = text(section, "missingReason", 500, false);
            if (ids.isEmpty() && reason.isBlank()) throw invalid("空分区必须说明缺失原因");
            if (section.containsKey("missingReason") && reason.isBlank()) throw invalid("缺失原因不能为空");
            if (section.containsKey("missingConfirmed") && !(section.get("missingConfirmed") instanceof Boolean)) throw invalid("缺失确认类型无效");
            if (!reason.isBlank() != Boolean.TRUE.equals(section.get("missingConfirmed"))) throw invalid("每个缺失分区必须明确确认，完整分区不能标为缺失");
            sourceCount += ids.size();
            sources.put(id, ids);
        }
        if (sourceCount > 3000) throw invalid("本次来源数量超限");
        List<Map<String,Object>> parameters = objects(input.get("parameterRows"));
        if (parameters.size() > 1000) throw invalid("参数行数量超限");
        for (Map<String,Object> row : parameters) {
            keys(row, Set.of("sectionId", "sourceId", "name", "value", "unit", "rawText", "unknownReason", "excluded"));
            if(!row.containsKey("name") || !row.containsKey("value"))throw invalid("参数行必须保留名称与值字段");
            if (!"params".equals(text(row, "sectionId", 20, true)) || !sources.getOrDefault("params", Set.of()).contains(text(row, "sourceId", 128, true))) throw invalid("参数行来源不属于参数分区");
            String name = text(row, "name", 200, false), value = text(row, "value", 2000, false);
            String unknown = text(row, "unknownReason", 500, false);
            if ((name.isBlank() || value.isBlank()) && unknown.isBlank()) throw invalid("未知参数必须保留原因");
            text(row, "unit", 80, false); text(row, "rawText", 4000, false);
            if (row.containsKey("excluded") && !(row.get("excluded") instanceof Boolean)) throw invalid("参数排除状态无效");
        }
        if (input.containsKey("parameterRows")) validateParameterDisplay(body, parameters);
        List<Map<String,Object>> ranges = objects(input.get("ranges"));
        if (ranges.size() > 100) throw invalid("自选范围数量超限");
        for (Map<String,Object> range : ranges) {
            keys(range, Set.of("sectionId", "sourceId", "name", "mode"));
            if (!selectedIds.contains(text(range, "sectionId", 20, true)) || !Set.of("include", "exclude").contains(text(range, "mode", 10, true))) throw invalid("范围操作无效");
            text(range, "sourceId", 128, true); text(range, "name", 80, true);
        }
        // 不截断、不按参数名合并；顺序与原文一并进入快照和幂等指纹。
        Map<String,Object> result = new LinkedHashMap<>(input);
        result.put("sections", sections); result.put("parameterRows", parameters); result.put("ranges", ranges);
        result.put("legacy", false);
        validateSelectedContent(body, selectedIds);
        return result;
    }

    private static void validateSelectedContent(Map<String,Object> body, Set<String> selected) {
        Map<String,Object> fields=object(body.get("fields"));
        if (!selected.contains("params") && fields.containsKey("parameters") && !str(fields.get("parameters")).isBlank()) throw invalid("未选参数分区包含参数内容");
        if (!selected.contains("basic")) for(String key:List.of("price","priceText","shop","stockText","deliveryText","promotionText")) {
            if(fields.containsKey(key) && !str(fields.get(key)).isBlank())throw invalid("未选商品信息分区包含可选内容");
        }
        for(Map<String,Object> block:objects(body.get("blocks"))) {
            String type=str(block.get("type"));
            String section=switch(type){case "parameters"->"params";case "introduction"->"intro";case "description"->"detail";default->"";};
            if(!section.isEmpty() && !selected.contains(section))throw invalid("内容区块不属于所选分区");
        }
        for(Map<String,Object> image:objects(body.get("images"))) {
            String section=switch(str(image.get("group"))){case "MAIN"->"main";case "GALLERY"->"gallery";case "DETAIL"->"detailImages";default->throw invalid("图片分组无效");};
            if(!selected.contains(section))throw invalid("图片不属于所选分区");
        }
    }

    public static boolean hasMissing(Map<String,Object> extraction) {
        return objects(extraction.get("sections")).stream().anyMatch(section ->
                !str(section.get("missingReason")).isBlank() && Boolean.TRUE.equals(section.get("missingConfirmed")));
    }

    private static void validateParameterDisplay(Map<String,Object> body, List<Map<String,Object>> parameters) {
        // 新版网页解析按未排除行的原文顺序使用全角分号连接，展示和导出必须使用同一内容。
        // 无原文的兼容结构行由名称、值及单位派生；原始结构与排除行仍原样保留在 extraction 中。
        String expected = parameters.stream().filter(row -> !Boolean.TRUE.equals(row.get("excluded")))
                .map(row -> {
                    String raw = str(row.get("rawText"));
                    if (!raw.isBlank()) return raw;
                    String name = str(row.get("name")), value = str(row.get("value")), unit = str(row.get("unit"));
                    return name + "：" + value + (unit.isBlank() || value.endsWith(unit) ? "" : " " + unit);
                }).collect(java.util.stream.Collectors.joining("；"));
        Map<String,Object> fields = object(body.get("fields"));
        if (!expected.equals(str(fields.get("parameters")))) throw invalid("参数展示与有效参数行不一致，请重新提取");
        for (Map<String,Object> block : objects(body.get("blocks"))) {
            if ("parameters".equals(str(block.get("type"))) && !expected.equals(str(block.get("text"))))
                throw invalid("参数区块含有未确认或已排除的内容，请重新提取");
        }
    }

    public static List<Map<String,Object>> normalizeBlocks(List<Map<String,Object>> blocks, Map<String,Object> extraction) {
        if(Boolean.TRUE.equals(extraction.get("legacy")))return blocks;
        Map<String,Set<String>> sources=new HashMap<>();
        for(var section:objects(extraction.get("sections")))sources.put(str(section.get("sectionId")),new HashSet<>(listValue(section.get("sourceIds")).stream().map(AdenCollectionService::str).toList()));
        List<Map<String,Object>> result=new ArrayList<>();Map<String,Map<String,Object>> bySource=new LinkedHashMap<>();
        for(var block:blocks) {
            if(!block.containsKey("sourceId") && !block.containsKey("sectionIds")){result.add(block);continue;}
            String source=text(block,"sourceId",128,true);List<Object> claimed=listValue(block.get("sectionIds"));
            if(claimed.isEmpty() || claimed.size()>7 || new HashSet<>(claimed).size()!=claimed.size())throw invalid("内容区块来源分区无效");
            for(Object section:claimed)if(!(section instanceof String id) || !sources.getOrDefault(id,Set.of()).contains(source))throw invalid("内容区块来源不属于所选分区");
            var previous=bySource.get(source);
            if(previous==null){block.put("sectionIds",new ArrayList<>(claimed));bySource.put(source,block);result.add(block);}
            else {
                if(!Objects.equals(previous.get("text"),block.get("text")) || !Objects.equals(previous.get("imageId"),block.get("imageId")))throw invalid("同一来源内容冲突，请重新提取");
                var combined=new LinkedHashSet<>(listValue(previous.get("sectionIds")));combined.addAll(claimed);previous.put("sectionIds",new ArrayList<>(combined));
            }
        }
        return result;
    }

    public static List<Map<String,Object>> validateImageSources(List<Map<String,Object>> images, Map<String,Object> extraction) {
        if(Boolean.TRUE.equals(extraction.get("legacy"))) {
            for(var image:images){image.remove("sourceId");image.remove("sectionIds");}
            return images;
        }
        Map<String,Set<String>> sources=new HashMap<>();
        for(var section:objects(extraction.get("sections")))sources.put(str(section.get("sectionId")),new HashSet<>(listValue(section.get("sourceIds")).stream().map(AdenCollectionService::str).toList()));
        List<Map<String,Object>> result=new ArrayList<>();Map<String,Map<String,Object>> bySource=new LinkedHashMap<>();Map<String,String> imageSources=new HashMap<>();
        for(var image:images) {
            if(!image.containsKey("sourceId") && !image.containsKey("sectionIds")){result.add(image);continue;}
            String source=text(image,"sourceId",128,true);List<Object> claimed=listValue(image.get("sectionIds"));
            if(claimed.isEmpty() || claimed.size()>7 || new HashSet<>(claimed).size()!=claimed.size())throw invalid("图片来源分区无效");
            for(Object section:claimed)if(!(section instanceof String id) || !Set.of("main","gallery","detailImages").contains(id) || !sources.getOrDefault(id,Set.of()).contains(source))throw invalid("图片来源不属于所选图片区");
            String group=switch(str(image.get("group"))){case "MAIN"->"main";case "GALLERY"->"gallery";case "DETAIL"->"detailImages";default->throw invalid("图片分组无效");};
            if(!claimed.contains(group))throw invalid("图片分组与来源不一致");
            String imageId=text(image,"imageId",128,true);
            if(!imageId.matches("[A-Za-z0-9_-]+"))throw invalid("图片标识无效");
            String previousSource=imageSources.putIfAbsent(imageId,source);
            if(previousSource!=null && !previousSource.equals(source))throw invalid("同一图片标识不能指向不同来源");
            var previous=bySource.get(source);
            if(previous==null){image.put("sectionIds",new ArrayList<>(claimed));bySource.put(source,image);result.add(image);}
            else {
                if(!Objects.equals(previous.get("url"),image.get("url")) || !Objects.equals(previous.get("width"),image.get("width")) || !Objects.equals(previous.get("height"),image.get("height")))throw invalid("同一图片来源内容冲突，请重新提取");
                var combined=new LinkedHashSet<>(listValue(previous.get("sectionIds")));combined.addAll(claimed);previous.put("sectionIds",new ArrayList<>(combined));
            }
        }
        return result;
    }

    private static String revision(Map<String,Object> input, String key, boolean positive) {
        String value = text(input, key, 19, true);
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid("分区版本必须是规范十进制字符串");
        try { long n = Long.parseLong(value); if (positive && n == 0) throw invalid("分区结果或确认版本必须为正数"); }
        catch (NumberFormatException overflow) { throw invalid("分区版本超限"); }
        return value;
    }

    private static String text(Map<String,Object> input, String key, int max, boolean required) {
        Object value = input.get(key);
        if (value == null && !required) return "";
        if (!(value instanceof String s) || s.length() > max || required && s.isBlank()) throw invalid(key + "缺失或超限");
        return s;
    }
    private static void keys(Map<String,Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw invalid("分区包含未支持字段");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
