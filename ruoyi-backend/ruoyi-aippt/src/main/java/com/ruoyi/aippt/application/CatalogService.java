package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
@Service @AipptEnabled public class CatalogService  {
    private final AipptProperties props;
    public CatalogService(AipptProperties p)  {
        props=p;
    }
    public String version()  {
        return props.getCatalogVersion();
    }
    /** 构建制品和 Worker 以同一 manifest 校验字节，版本名不能掩盖目录漂移。 */
    @PostConstruct public void verifyManifest()  {
        try  {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            JSONObject manifest;
            boolean classpath = props.getCatalogPath().startsWith("classpath:");
            if (classpath) manifest = readResource(resolver.getResource("classpath:aippt/catalog-manifest.json"));
            else manifest = read(Path.of(props.getCatalogPath()).toAbsolutePath().normalize().resolveSibling("catalog-manifest.json"));
            AipptRules.require(props.getCatalogVersion().equals(manifest.getString("catalogVersion")), "目录版本与 manifest 不同");
            JSONArray files = manifest.getJSONArray("files");
            AipptRules.require(files != null && !files.isEmpty(), "目录 manifest 为空");
            Set<String> expected = new HashSet<>();
            for (Object raw : files)  {
                JSONObject file = (JSONObject) raw;
                String relative = file.getString("path");
                AipptRules.require(relative != null && !relative.startsWith("/") && !relative.contains("..") && expected.add(relative), "目录 manifest 路径无效");
                byte[] bytes;
                if (classpath)  {
                    try (var stream = resolver.getResource(props.getCatalogPath() + "/" + relative).getInputStream())  {
                        bytes = stream.readAllBytes();
                    }
                }
                else  {
                    Path root = Path.of(props.getCatalogPath()).toRealPath();
                    Path item = root.resolve(relative).toRealPath();
                    AipptRules.require(item.startsWith(root), "目录文件越界");
                    bytes = Files.readAllBytes(item);
                }
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                AipptRules.require(bytes.length == file.getLongValue("size") && digest.equals(file.getString("sha256")), "目录文件校验失败：" + relative);
            }
            int actual;
            if (classpath) actual = resolver.getResources(props.getCatalogPath().replace("classpath:", "classpath*:") + "/**/*.json").length;
            else try (var stream = Files.walk(Path.of(props.getCatalogPath())))  {
                actual = (int) stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).count();
            }
            AipptRules.require(actual == expected.size(), "目录 manifest 文件数量不同");
        }
        catch (AipptException e)  {
            throw e;
        }
        catch (Exception e)  {
            throw new AipptException(503, "CATALOG_INVALID", "目录 manifest 无法核验");
        }
    }
    public List<JSONObject> directory(String dir)  {
        AipptRules.require(Set.of("themes","layouts","flex-presets").contains(dir),"非法目录");
        if(props.getCatalogPath().startsWith("classpath:"))  {
            try  {
                Resource[] resources=new PathMatchingResourcePatternResolver().getResources(props.getCatalogPath().replace("classpath:","classpath*:")+"/"+dir+"/*.json");
                return Arrays.stream(resources).sorted(Comparator.comparing(Resource::getFilename)).map(this::readResource).map(value->"themes".equals(dir)?themeDefaults(value):value).toList();
            }
            catch(Exception e)  {
                throw new AipptException(503,"CATALOG_UNAVAILABLE","设计目录未配置或不可读");
            }
        }
        try(var stream=Files.list(Path.of(props.getCatalogPath(),dir)))  {
            return stream.filter(f->f.getFileName().toString().endsWith(".json")).sorted().map(this::read).map(value->"themes".equals(dir)?themeDefaults(value):value).toList();
        }
        catch(Exception e)  {
            throw new AipptException(503,"CATALOG_UNAVAILABLE","设计目录未配置或不可读");
        }
    }
    private JSONObject readResource(Resource resource)  {
        try(var stream=resource.getInputStream())  {
            return JSON.parseObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        catch(Exception e)  {
            throw new AipptException(503,"CATALOG_INVALID","目录文件不可读");
        }
    }
    public JSONObject read(Path path)  {
        try  {
            return JSON.parseObject(Files.readString(path));
        }
        catch(Exception e)  {
            throw new AipptException(503,"CATALOG_INVALID","目录文件不可读");
        }
    }
    public JSONObject sample()  {
        if(props.getCatalogPath().startsWith("classpath:"))return readResource(new PathMatchingResourcePatternResolver().getResource(props.getCatalogPath()+"/sample-deck.json"));
        return read(Path.of(props.getCatalogPath(),"sample-deck.json"));
    }
    public JSONObject theme(String id)  {
        return directory("themes").stream().filter(x->id.equals(x.getString("id"))).findFirst().orElseThrow(()->AipptException.invalid("主题不存在"));
    }
    /** Theme/Fonts/TextStyle/Ambient模型明确声明的默认值，仅补缺字段，不改变目录字节。 */
    static JSONObject themeDefaults(JSONObject raw) {
        JSONObject theme=AipptRules.copy(raw);
        theme.put("fonts",fontDefaults(AipptRules.object(theme.get("fonts"),"主题字体")));
        if(!theme.containsKey("ambient"))theme.put("ambient",new JSONArray());
        JSONObject styles=theme.getJSONObject("text_styles");
        for(String key:styles.keySet())fill(styles.getJSONObject(key),JSONObject.of("italic",false));
        for(Object rawMotif:theme.getJSONArray("ambient")) {
            JSONObject motif=(JSONObject)rawMotif;
            fill(motif,JSONObject.of("scope",null,"color","accent","strength",.2,"avoid_content",false));
            JSONObject defaults=switch(motif.getString("motif")) {
                case "edge_band"->JSONObject.of("edge","left","thickness_pt",6.0,"start",0.0,"end",1.0);
                case "corner_bracket"->JSONObject.of("corner","top_right","size_pt",88.0,"thickness_pt",1.5,"inset_pt",22.0);
                case "hairline_grid"->JSONObject.of("columns",0,"rows",0,"thickness_pt",.75,"area",null);
                case "glow"->JSONObject.of("cx",.85,"cy",.16,"radius_pt",320.0,"layers",4);
                case "watermark"->JSONObject.of("text",null,"size_pt",180.0,"font","display","weight",700,"letter_spacing_pt",0.0,"align","left");
                default->throw AipptException.invalid("未知氛围母题");
            };
            fill(motif,defaults);
        }
        return theme;
    }
    static JSONObject fontDefaults(JSONObject raw) {
        JSONObject fonts=AipptRules.copy(raw);
        AipptRules.require(fonts.keySet().stream().allMatch(Set.of("display","body","emoji")::contains),"未知字体字段");
        if(!fonts.containsKey("emoji"))fonts.put("emoji",JSONObject.of("web","\"Apple Color Emoji\", \"Segoe UI Emoji\", \"Noto Color Emoji\", sans-serif","pptx_latin","Segoe UI Emoji","pptx_east_asian","Segoe UI Emoji"));
        for(String field:List.of("display","body","emoji")) {
            JSONObject family=AipptRules.object(fonts.get(field),"字体族");
            AipptRules.require(family.keySet().equals(Set.of("web","pptx_latin","pptx_east_asian")),"字体族字段不完整或未知");
            for(String key:family.keySet())AipptRules.text(family.get(key),1,1000,"字体名称");
        }
        return fonts;
    }
    private static void fill(JSONObject object,JSONObject defaults) {
        defaults.forEach((key,value)->{if(!object.containsKey(key))object.put(key,value);});
    }
    public JSONObject layout(String id)  {
        return directory("layouts").stream().filter(x->id.equals(x.getString("id"))).findFirst().orElseThrow(()->AipptException.invalid("版式不存在"));
    }
    public JSONObject compatible(JSONObject slide,JSONObject layout)  {
        JSONArray slots=layout.getJSONArray("slots"),blocks=slide.getJSONArray("blocks");
        JSONArray assignments=new JSONArray();
        Set<String> used=new HashSet<>();
        if(blocks.size()>slots.size()||!assign(0,blocks,slots,used,assignments))return JSONObject.of("id",layout.getString("id"),"compatible",false,"reason","内容无法同时满足兼容槽位和必填槽位");
        return JSONObject.of("id",layout.getString("id"),"compatible",true,"assignments",assignments);
    }
    private boolean assign(int index,JSONArray blocks,JSONArray slots,Set<String> used,JSONArray assignments)  {
        if(index==blocks.size())return slots.stream().map(JSONObject.class::cast).allMatch(slot->!slot.getBooleanValue("required")||used.contains(slot.getString("id")));
        JSONObject block=blocks.getJSONObject(index);
        for(Object raw:slots)  {
            JSONObject slot=(JSONObject)raw;
            String id=slot.getString("id");
            if(used.contains(id)||!slot.getJSONArray("accepts").contains(block.getString("type")))continue;
            used.add(id);
            assignments.add(JSONObject.of("block_id",block.getString("id"),"slot_id",id));
            if(assign(index+1,blocks,slots,used,assignments))return true;
            assignments.remove(assignments.size()-1);
            used.remove(id);
        }
        return false;
    }
}
