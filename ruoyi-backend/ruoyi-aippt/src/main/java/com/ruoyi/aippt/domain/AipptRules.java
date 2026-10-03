package com.ruoyi.aippt.domain;
import com.alibaba.fastjson2.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** 中心重校业务边界；几何和模型算法仍由 Worker 执行。 */
public final class AipptRules  {
    private AipptRules()  {
    }
    public static final Set<String> TYPES=Set.of("text","bullets","image","chart","table","kpi","cards","callout");
    public static JSONObject copy(JSONObject x)  {
        return JSON.parseObject(JSON.toJSONString(x,JSONWriter.Feature.WriteMapNullValue));
    }
    public static String hash(String s)  {
        try  {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        }
        catch(Exception e)  {
            throw new IllegalStateException(e);
        }
    }
    public static void require(boolean b,String message)  {
        if(!b)throw AipptException.invalid(message);
    }
    /** 字体身份绑定到不可变任务；估算结果不能冒充真实字体版本。 */
    public static void fontManifest(String expected,JSONObject output) {
        require(expected!=null&&("unavailable".equals(expected)||expected.matches("[0-9a-f]{64}")),"任务字体清单标识无效");
        String mode="unavailable".equals(expected)?"estimate":"verified";
        if(!Objects.equals(expected,output.getString("font_manifest_hash"))||!mode.equals(output.getString("font_mode"))) {
            throw new AipptException(422,"FONT_MANIFEST_MISMATCH","结果字体清单或度量模式与任务快照不一致");
        }
    }
    public static void text(Object value,int min,int max,String field)  {
        require(value instanceof String,field+"须为文字");
        String s=(String)value;
        require(s.length()>=min&&s.length()<=max,field+"长度须为"+min+"–"+max);
    }
    public static JSONArray array(JSONObject object,String field) {
        require(object.get(field) instanceof JSONArray,field+"须为数组");
        return (JSONArray)object.get(field);
    }
    public static JSONObject object(Object value,String field) {
        require(value instanceof JSONObject,field+"须为对象");
        return (JSONObject)value;
    }
    public static long integer(Object value,long min,long max,String field) {
        require(value instanceof Number,field+"须为整数");
        try {
            long n=new java.math.BigDecimal(value.toString()).longValueExact();
            require(n>=min&&n<=max,field+"超出范围");
            return n;
        }
        catch(ArithmeticException|NumberFormatException e) { throw AipptException.invalid(field+"须为整数"); }
    }
    public static void expected(JSONObject current,JSONObject command,String field)  {
        require(command.containsKey(field),"缺少预期版本 "+field);
        long actual=current.getLongValue(field);
        if(integer(command.get(field),0,Long.MAX_VALUE,field)!=actual)throw AipptException.conflict("版本已变化",actual);
    }
    public static void project(JSONObject p,boolean create)  {
        text(p.get("title"),1,200,"名称");
        if(p.get("audience")!=null)text(p.get("audience"),0,100,"受众");
        require(p.get("tone") instanceof String&&Set.of("professional","plain","punchy").contains(p.get("tone")),"非法语气");
        require(p.get("content_density") instanceof String&&Set.of("concise","medium","detailed").contains(p.get("content_density")),"非法文字量");
        require(p.get("layout_mode") instanceof String&&Set.of("fixed","flex").contains(p.get("layout_mode")),"非法布局模式");
        integer(p.get("page_count"),create?5:1,create?20:40,"页数");
    }
    public static String fingerprint(JSONObject p,List<JSONObject> sources)  {
        JSONObject data=new JSONObject();
        for(String k:List.of("title","audience","tone","content_density"))data.put(k,p.get(k));
        JSONArray list=new JSONArray();
        sources.stream().filter(s->"ready".equals(s.getString("parse_status"))).sorted(Comparator.comparing(s->s.getString("id"))).forEach(s->list.add(JSONObject.of("id",s.getString("id"),"parser_version",s.getString("parser_version"),"sections",s.getJSONArray("sections"))));
        data.put("sources",list);
        return hash(JSON.toJSONString(data,JSONWriter.Feature.MapSortField));
    }
    public static void outline(JSONArray pages,Set<String> refs)  {
        outline(pages,refs,5,20);
    }
    /** 正式页轮廓与初期生成共用字段/引用校验，仅由调用上下文指定页数边界。 */
    public static void outline(JSONArray pages,Set<String> refs,int minimum,int maximum)  {
        require(pages!=null&&pages.size()>=minimum&&pages.size()<=maximum,"提纲须为"+minimum+"–"+maximum+"页");
        Set<String> ids=new HashSet<>();
        for(Object item:pages)  {
            JSONObject p=object(item,"提纲页");
            require(p.keySet().stream().allMatch(Set.of("id","title","objective","visual","key_points","page_role","source_refs","layout_id")::contains),"未知提纲页字段");
            text(p.get("title"),1,100,"提纲标题");
            text(p.get("objective"),1,300,"页面目的");
            if(p.get("visual")!=null)text(p.get("visual"),0,120,"配图意图");
            if(!p.containsKey("id"))p.put("id",UUID.randomUUID().toString());
            if(!p.containsKey("page_role"))p.put("page_role","content");
            if(!p.containsKey("source_refs"))p.put("source_refs",new JSONArray());
            Object id=p.get("id");
            require(id instanceof String&&((String)id).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")&&ids.add((String)id),"提纲页ID重复或非UUID");
            text(p.get("layout_id"),1,100,"布局ID");
            JSONArray points=array(p,"key_points");
            require(points!=null&&points.size()>=2&&points.size()<=5,"要点须为2–5条");
            for(Object point:points)text(point,1,500,"要点");
            require(p.get("page_role") instanceof String&&Set.of("cover","toc","section","content","summary").contains(p.get("page_role")),"非法页面角色");
            JSONArray r=array(p,"source_refs");
            require(r!=null&&r.size()<=10,"来源须最多10项");
            for(Object ref:r)require(ref instanceof String&&refs.contains(ref),"来源引用不存在");
        }
    }
    public static void block(JSONObject b)  {
        require(b.get("type") instanceof String&&TYPES.contains(b.get("type")), "不支持内容类型");
        text(b.get("id"), 1, 100, "块ID");
        text(b.get("slot_id"), 1, 100, "槽位ID");
        Set<String> fields=new HashSet<>(Set.of("id","slot_id","locked","style","type"));
        String type=b.getString("type");
        switch(type)  {
            case "text" ->  {
                fields.add("text");
                text(b.get("text"),0,20000,"文字");
            }
            case "bullets" ->  {
                fields.add("items");
                JSONArray items=array(b,"items");
                require(items!=null&&items.size()<=100,"要点无效");
                for(Object item:items)text(item,0,5000,"要点");
            }
            case "table" ->  {
                fields.addAll(Set.of("header","rows"));
                JSONArray header=array(b,"header"), rows=array(b,"rows");
                require(header!=null&&header.size()>0&&header.size()<=100&&rows!=null&&rows.size()<=1000,"表格尺寸无效");
                for(Object cell:header)require(cell instanceof String,"表格表头必须是文字");
                for(Object raw:rows)  {
                    require(raw instanceof JSONArray&&((JSONArray)raw).size()==header.size(),"表格行列不一致");
                    for(Object cell:(JSONArray)raw)require(cell instanceof String,"表格单元格必须是文字");
                }
            }
            case "chart" ->  {
                fields.addAll(Set.of("chart_type","categories","series","unit"));
                require(b.get("chart_type") instanceof String&&Set.of("bar","column","line","pie").contains(b.get("chart_type")),"不支持图表类型");
                JSONArray categories=array(b,"categories"),series=array(b,"series");
                require(categories!=null&&!categories.isEmpty()&&categories.size()<=200&&series!=null&&!series.isEmpty()&&series.size()<=20,"图表尺寸无效");
                for(Object category:categories)require(category instanceof String,"图表类别须为文字");
                for(Object raw:series)  {
                    JSONObject serie=object(raw,"图表系列");
                    require(serie.keySet().stream().allMatch(Set.of("name","values")::contains),"非法图表系列字段");
                    text(serie.get("name"),0,1000,"系列名称");
                    JSONArray values=array(serie,"values");
                    require(values!=null&&values.size()==categories.size(),"图表系列维数不一致");
                    for(Object value:values)require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),"图表值必须有限数值");
                }
                if(b.get("unit")!=null)text(b.get("unit"),0,100,"图表单位");
            }
            case "image" ->  {
                fields.addAll(Set.of("url","alt","source","credit"));
                Object url=b.get("url");
                require(url==null||url instanceof String&&((String)url).matches("/aippt/assets/[0-9a-f-]{36}"),"图片仅允许私有资产");
                text(b.get("alt"),0,5000,"替代文本");
                require(b.get("source") instanceof String&&Set.of("generated","stock","upload","placeholder").contains(b.get("source")),"非法图片来源");
                if(b.get("credit")!=null)text(b.get("credit"),0,5000,"图片署名");
            }
            case "cards" ->  {
                fields.add("items");
                JSONArray items=array(b,"items");
                require(items!=null&&!items.isEmpty()&&items.size()<=50,"卡片数量无效");
                for(Object raw:items)  {
                    JSONObject item=object(raw,"卡片");
                    require(item.keySet().stream().allMatch(Set.of("title","desc","icon")::contains),"非法卡片字段");
                    text(item.get("title"),0,5000,"卡片标题");
                    text(item.get("desc"),0,10000,"卡片描述");
                    if(item.get("icon")!=null)text(item.get("icon"),0,100,"卡片图标");
                }
            }
            case "kpi" ->  {
                fields.addAll(Set.of("value","label","note"));
                text(b.get("value"),0,1000,"KPI值");
                text(b.get("label"),0,5000,"KPI名称");
                if(b.get("note")!=null)text(b.get("note"),0,10000,"KPI说明");
            }
            case "callout" ->  {
                fields.addAll(Set.of("text","icon","variant"));
                text(b.get("text"),0,20000,"提示文字");
                require(b.get("variant") instanceof String&&Set.of("note","source").contains(b.get("variant")),"非法提示类型");
                if(b.get("icon")!=null)text(b.get("icon"),0,100,"提示图标");
            }
        }
        require(b.keySet().stream().allMatch(fields::contains),"内容块含不支持字段");
        if(b.containsKey("locked"))require(b.get("locked") instanceof Boolean,"locked须为布尔值");
        if(b.get("style")!=null)style(object(b.get("style"),"样式"));
    }
    public static void style(JSONObject style)  {
        require(style.keySet().stream().allMatch(Set.of("size_pt","weight","radius_pt","border_width_pt","padding_pt","color","fill","border_color","align","italic")::contains),"未知样式字段");
        for(String key:style.keySet())  {
            Object value=style.get(key);
            if(value==null)continue;
            switch(key)  {
                case "size_pt" -> range(value,8,72,key);
                case "weight" ->  {
                    range(value,300,900,key);
                    require(((Number)value).doubleValue()==((Number)value).intValue(),"weight须为整数");
                }
                case "radius_pt" -> range(value,0,48,key);
                case "border_width_pt" -> range(value,0,6,key);
                case "padding_pt" -> range(value,0,48,key);
                case "color","fill","border_color" -> require(value instanceof String&&(("fill".equals(key)&&"none".equals(value))||((String)value).matches("#[0-9A-Fa-f]{6}|accent|accent_soft|background|surface|ink|ink_soft|ink_muted|line|line_strong")),"非法颜色");
                case "align" -> require(Set.of("left","center","right").contains(value),"非法对齐");
                case "italic" -> require(value instanceof Boolean,"非法样式布尔值");
                default -> throw AipptException.invalid("不支持样式字段 "+key);
            }
        }
    }
    private static void range(Object n,double min,double max,String k)  {
        require(n instanceof Number&&Double.isFinite(((Number)n).doubleValue())&&((Number)n).doubleValue()>=min&&((Number)n).doubleValue()<=max,k+"超出范围");
    }
    public static void slide(JSONObject slide)  {
        JSONArray blocks=array(slide,"blocks");
        require(blocks!=null&&blocks.size()>=1&&blocks.size()<=200,"页面须含1–200块");
        Set<String> ids=new HashSet<>();
        for(Object item:blocks)  {
            JSONObject b=object(item,"内容块");
            block(b);
            require(ids.add(b.getString("id")),"重复块ID");
        }
        if("flex".equals(slide.getString("layout_mode")))  {
            Set<String> leaves=new HashSet<>(),nodes=new HashSet<>();
            JSONObject root=object(slide.get("layout_tree"),"布局树");
            require("row".equals(root.get("type"))||"column".equals(root.get("type")),"布局树根须为容器");
            tree(root,0,leaves,nodes,blocks);
            require(leaves.equals(ids),"布局树与块ID集合不一致");
        }
        else require("fixed".equals(slide.getString("layout_mode")),"非法布局模式");
    }
    private static void tree(JSONObject n,int depth,Set<String> leaves,Set<String> nodes,JSONArray blocks)  {
        require(n!=null&&depth<=12&&nodes.size()<512,"布局树无效或过深");
        text(n.get("id"),1,100,"布局节点ID");
        require(nodes.add(n.getString("id")),"布局节点ID重复");
        String type=n.getString("type");
        if("block".equals(type))  {
            require(n.keySet().stream().allMatch(Set.of("type","id","block_id","grow","text_style","offset_x_pt","offset_y_pt","bleed","rect_override")::contains),"未知布局叶字段");
            text(n.get("block_id"),1,100,"布局块ID");
            if(n.get("text_style")!=null)text(n.get("text_style"),1,100,"文字样式名称");
            if(n.containsKey("bleed"))require(n.get("bleed") instanceof Boolean,"bleed须为布尔值");
            String id=n.getString("block_id");
            require(id!=null&&leaves.add(id),"重复/空布局块");
            if(n.getBooleanValue("bleed"))  {
                JSONObject b=blocks.stream().map(JSONObject.class::cast).filter(x->id.equals(x.getString("id"))).findFirst().orElseThrow(AipptException::missing);
                require(Set.of("image","chart").contains(b.getString("type")),"仅图片/图表允许出血");
            }
            if(n.get("rect_override")!=null)  {
                JSONObject r=object(n.get("rect_override"),"解锁几何");
                require(r.keySet().equals(Set.of("x","y","w","h")),"未知解锁几何字段");
                for(String key:List.of("x","y","w","h"))range(r.get(key),0,1,key);
                require(r.getDoubleValue("w")>0&&r.getDoubleValue("h")>0&&r.getDoubleValue("x")+r.getDoubleValue("w")<=1&&r.getDoubleValue("y")+r.getDoubleValue("h")<=1,"解锁几何越出画布");
            }
            for(String key:List.of("offset_x_pt","offset_y_pt"))if(n.containsKey(key))range(n.get(key),-960,960,key);
        }
        else  {
            require(type!=null&&Set.of("row","column").contains(type),"未知布局节点");
            require(n.keySet().stream().allMatch(Set.of("type","id","children","gap_pt","ratios","preset","grow")::contains),"未知布局容器字段");
            if(n.get("preset")!=null)require(n.get("preset") instanceof String&&Set.of("solid_boxes","outline_boxes","side_line","numbered_steps","timeline").contains(n.get("preset")),"未知布局预设");
            JSONArray children=array(n,"children");
            require(children!=null,"缺少布局子节点");
            if(n.containsKey("gap_pt"))range(n.get("gap_pt"),0,960,"间距");
            if(n.get("ratios")!=null)  {
                JSONArray ratios=array(n,"ratios");
                require(ratios.size()==children.size(),"比例与子节点数量不一致");
                for(Object ratio:ratios)range(ratio,0.000001,100000,"布局比例");
            }
            for(Object c:children)tree(object(c,"布局子节点"),depth+1,leaves,nodes,blocks);
        }
        if(n.containsKey("grow"))range(n.get("grow"),0,100000,"grow");
    }
    public static JSONObject column(JSONArray blocks)  {
        JSONArray children=new JSONArray();
        for(Object item:blocks)  {
            JSONObject b=(JSONObject)item;
            children.add(JSONObject.of("type","block","id",UUID.randomUUID().toString(),"block_id",b.getString("id"),"grow",1));
        }
        return JSONObject.of("type","column","id",UUID.randomUUID().toString(),"children",children,"gap_pt",16,"grow",1);
    }
    public static JSONObject defaultBlock(String type)  {
        require(type!=null&&TYPES.contains(type),"未知块类型");
        String id=UUID.randomUUID().toString();
        JSONObject b=JSONObject.of("id",id,"slot_id",id,"type",type,"locked",true,"style",null);
        switch(type)  {
            case "text"->b.put("text","新文本");
            case "bullets"->b.put("items",JSONArray.of("要点"));
            case "image"->b.putAll(JSONObject.of("url",null,"alt","图片","source","placeholder","credit",null));
            case "chart"->b.putAll(JSONObject.of("chart_type","bar","categories",JSONArray.of("类别A","类别B"),"series",JSONArray.of(JSONObject.of("name","系列1","values",JSONArray.of(3.0,5.0))),"unit",null));
            case "table"->b.putAll(JSONObject.of("header",JSONArray.of("列1","列2"),"rows",JSONArray.of(JSONArray.of("",""))));
            case "kpi"->b.putAll(JSONObject.of("value","—","label","指标","note",null));
            case "cards"->b.put("items",JSONArray.of(JSONObject.of("title","要点一","desc","补充说明","icon",null),JSONObject.of("title","要点二","desc","补充说明","icon",null)));
            case "callout"->b.putAll(JSONObject.of("text","补充说明或数据来源","icon",null,"variant","note"));
        }
        return b;
    }
    public static JSONObject blank()  {
        JSONObject title=defaultBlock("text"),body=defaultBlock("bullets");
        title.put("text","新页面");
        JSONArray blocks=JSONArray.of(title,body);
        JSONObject tree=column(blocks);
        JSONObject titleLeaf=tree.getJSONArray("children").getJSONObject(0),bodyLeaf=tree.getJSONArray("children").getJSONObject(1);
        titleLeaf.put("grow",.45); titleLeaf.put("text_style","title");
        bodyLeaf.put("grow",1.5); bodyLeaf.put("text_style","bullet");
        return JSONObject.of("title","新页面","layout_id","bullets","layout_mode","flex","blocks",blocks,"layout_tree",tree,"speaker_notes",null,"issues",new JSONArray(),"status","ready");
    }
    public static void protect(JSONObject before,JSONObject after,boolean ai)  {
        Map<String,JSONObject> previous=new HashMap<>();
        for(Object b:before.getJSONArray("blocks"))previous.put(((JSONObject)b).getString("id"),(JSONObject)b);
        Map<String,JSONObject> next=new HashMap<>();
        for(Object item:after.getJSONArray("blocks"))  {
            JSONObject b=(JSONObject)item;
            JSONObject old=previous.get(b.getString("id"));
            boolean changed=old==null||!content(old).equals(content(b));
            if(ai&&old!=null&&old.getBooleanValue("locked")&&changed)throw AipptException.invalid("AI不能覆盖手工锁定内容");
            b.put("locked",old!=null&&old.getBooleanValue("locked")||!ai&&changed);
            next.put(b.getString("id"),b);
        }
        if(ai)for(JSONObject old:previous.values())require(!old.getBooleanValue("locked")||next.containsKey(old.getString("id")),"AI不能删除锁定内容");
    }
    private static JSONObject content(JSONObject b)  {
        JSONObject c=new JSONObject(b);
        c.remove("locked");
        c.remove("slot_id");
        c.remove("style");
        c=(JSONObject)semanticNumbers(c);
        // 共享模型的这些可选字段缺省为 null；JSON 往返可能省略它们。
        // 只统一已声明的可选空值，其他字段仍按完整字段集合比较。
        switch(c.getString("type")) {
            case "chart"->removeOptionalNull(c,"unit");
            case "image"-> {
                removeOptionalNull(c,"url");
                removeOptionalNull(c,"credit");
            }
            case "kpi"->removeOptionalNull(c,"note");
            case "callout"->removeOptionalNull(c,"icon");
            case "cards"-> {
                JSONArray items=c.getJSONArray("items");
                if(items!=null)for(Object item:items) {
                    if(item instanceof JSONObject card)removeOptionalNull(card,"icon");
                }
            }
            default-> {
            }
        }
        return c;
    }
    private static void removeOptionalNull(JSONObject object,String key) {
        if(object.containsKey(key)&&object.get(key)==null)object.remove(key);
    }
    /** JSON number 的整数/小数表示不影响内容；用精确十进制值比较，拒绝非有限值。 */
    private static Object semanticNumbers(Object value)  {
        if(value instanceof Map<?,?> object) {
            JSONObject normalized=new JSONObject();
            object.forEach((key,item)-> {
                require(key instanceof String,"JSON对象字段必须是字符串");
                normalized.put((String)key,semanticNumbers(item));
            });
            return normalized;
        }
        if(value instanceof Collection<?> array) {
            JSONArray normalized=new JSONArray();
            for(Object item:array)normalized.add(semanticNumbers(item));
            return normalized;
        }
        if(value!=null&&value.getClass().isArray()) {
            JSONArray normalized=new JSONArray();
            for(int i=0;i<java.lang.reflect.Array.getLength(value);i++) {
                normalized.add(semanticNumbers(java.lang.reflect.Array.get(value,i)));
            }
            return normalized;
        }
        if(value instanceof Number number) {
            require(Double.isFinite(number.doubleValue()),"内容数值必须有限");
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }
}
