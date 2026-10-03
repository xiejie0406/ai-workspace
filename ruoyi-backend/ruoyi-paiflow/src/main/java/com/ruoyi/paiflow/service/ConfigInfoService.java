package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.PublicConfigRecord;
import com.ruoyi.paiflow.mapper.PublicConfigMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** 公开种子仅是来源；当前结果由中心公开表只读Mapper查询，不读取秘密配置或初始化DB。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ConfigInfoService {
    public record View(Long id,String category,String code,String name,String value,Integer isValid,String remarks,
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",timezone="GMT+8") LocalDateTime createTime,
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",timezone="GMT+8") LocalDateTime updateTime) {}
    private static final int MAX_ROWS=1000,MAX_RESPONSE_BYTES=1024*1024;
    private static final Set<String> PRIVATE_KEYS=Set.of("password","passwd","secret","apikey","apisecret","appkey","appsecret","token","accesstoken","refreshtoken","authorization","privatekey","clientsecret","credential","credentialref","secretref");
    private static final Pattern URL=Pattern.compile("https?://[^\\s\"'<>\\\\]+",Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_V4=Pattern.compile("(?:0|10|127)\\..*|169\\.254\\..*|192\\.168\\..*|172\\.(?:1[6-9]|2[0-9]|3[01])\\..*|100\\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\..*");
    private static final Pattern NUMERIC_V4=Pattern.compile("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*");
    private static final Pattern CANONICAL_V4=Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
    private final PublicConfigMapper mapper;
    private final IdentityMappingService identities;
    private final Map<String,Set<String>> whitelist;
    private final boolean remapToolV2;
    public ConfigInfoService(PublicConfigMapper mapper,IdentityMappingService identities,Environment environment) {
        this.mapper=mapper;this.identities=identities;
        // 原逻辑判断完整profile字符串，dev,test组合不等于dev或test。
        String profile=environment.getProperty("spring.profiles.active","");remapToolV2="dev".equals(profile)||"test".equals(profile);
        try(var stream=new ClassPathResource("paiflow/public-config-source.json").getInputStream()) {
            JSONObject source=JSON.parseObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            if(!"paiflow.public-config-source.v1".equals(source.getString("schemaVersion")))throw new IOException("配置来源版本无效");
            JSONObject approved=source.getJSONObject("publicWhitelist");if(approved==null || approved.isEmpty())throw new IOException("公开白名单未配置");
            Map<String,Set<String>> copied=new LinkedHashMap<>();
            for(String category:approved.keySet()) {
                var codes=approved.getJSONArray(category);copied.put(category,codes==null?null:Set.copyOf(codes.toJavaList(String.class)));
            }
            whitelist=Collections.unmodifiableMap(copied);
        } catch(IOException|RuntimeException ex) { throw new IllegalStateException("公开配置来源无法加载，请核对本地catalog",ex); }
    }
    private long actor(){return identities.current().getUserId();}
    private void sameActor(long owner){if(actor()!=owner)throw new ServiceException("当前身份已变化，请重新读取配置",401);}
    private static String text(String value,int max) {
        if(value==null || value.length()>max || value.codePoints().anyMatch(c->c<32 || c==127))throw new ServiceException("配置查询参数无效",400);
        return value;
    }
    private String category(String requested) {
        text(requested,64);
        for(String known:whitelist.keySet())if(known.equalsIgnoreCase(requested))return known;
        throw unavailable();
    }
    private String code(String category,String requested) {
        text(requested,128);Set<String> codes=whitelist.get(category);
        if(codes==null)return requested;
        for(String known:codes)if(known.equalsIgnoreCase(requested))return known;
        throw unavailable();
    }
    private static ServiceException unavailable(){return new ServiceException("该公开配置尚未配置或不在已审查公开目录中",503);}
    public List<View> list(String requested) {
        long owner=actor();String category=category(requested);var result=views(mapper.byCategory(category),false);
        if(result.isEmpty())throw unavailable();sameActor(owner);return result;
    }
    public List<View> list(String requested,String requestedCode) {
        long owner=actor();String category=category(requested),code=code(category,requestedCode);var result=views(mapper.byCategoryAndCode(category,code),false);
        if(result.isEmpty())throw unavailable();sameActor(owner);return result;
    }
    public View first(String requested,String requestedCode) {
        long owner=actor();String category=category(requested),code=code(category,requestedCode);var row=mapper.first(category,code);
        if(row==null)throw unavailable();var result=view(row,false);sameActor(owner);return result;
    }
    public List<View> tags(String flag) {
        long owner=actor();String code=switch(flag==null?"":flag){case "tool"->"TOOL_TAGS";case "bot"->"BOT_TAGS";case "tool_v2"->"TOOL_TAGS_V2";default->null;};
        // 原未知flag确实是空列表；不称供应商/模型或未配置目录已可用。
        if(code==null){sameActor(owner);return List.of();}
        var result=views(mapper.tags(code),"tool_v2".equals(flag));if(result.isEmpty())throw unavailable();sameActor(owner);return result;
    }
    public List<String> workflowCategories() {
        long owner=actor();var rows=views(mapper.byCategory("WORKFLOW_CATEGORY"),false);
        if(rows.size()!=1 || rows.get(0).value()==null)throw unavailable();sameActor(owner);
        return Arrays.asList(rows.get(0).value().split(","));
    }
    private List<View> views(List<PublicConfigRecord> rows,boolean tagV2) {
        if(rows==null)throw unavailable();if(rows.size()>MAX_ROWS)throw new ServiceException("公开配置结果超出允许大小",413);
        List<View> result=new ArrayList<>();for(var row:rows)result.add(view(row,tagV2));
        if(JSON.toJSONString(result).getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE_BYTES)throw new ServiceException("公开配置结果超出允许大小",413);
        return List.copyOf(result);
    }
    private View view(PublicConfigRecord row,boolean tagV2) {
        if(row==null || row.getId()==null || row.getId()<1 || !Integer.valueOf(1).equals(row.getIsValid()))throw unavailable();
        String category=category(row.getCategory());code(category,row.getCode());publicContent(row.getName());publicContent(row.getCode());publicContent(row.getValue());publicContent(row.getRemarks());
        Long id=row.getId();
        if(tagV2 && remapToolV2 && row.getRemarks()!=null && !row.getRemarks().isBlank()) {
            try{id=Long.valueOf(row.getRemarks());}catch(NumberFormatException ex){throw new ServiceException("原tool_v2公开ID映射未配置有效整数",503);}
        }
        var result=new View(id,row.getCategory(),row.getCode(),row.getName(),row.getValue(),row.getIsValid(),row.getRemarks(),row.getCreateTime(),row.getUpdateTime());
        if(JSON.toJSONString(result).getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE_BYTES)throw new ServiceException("公开配置结果超出允许大小",413);
        return result;
    }
    private static void publicContent(String text) {
        if(text==null)return;if(text.getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE_BYTES)throw new ServiceException("公开配置内容超出允许大小",413);
        String stripped=text.stripLeading();
        if(JSON.isValid(text)) {
            try {Object parsed=JSON.parse(text);RunJsonBoundary.check(parsed,MAX_RESPONSE_BYTES);privateFields(parsed,0);}
            catch(RuntimeException ex){throw new ServiceException("公开配置内容未通过公开字段核对",503);}
        } else if(stripped.startsWith("{") || stripped.startsWith("["))throw new ServiceException("公开配置JSON内容未配置有效结构",503);
        publicUrls(text);
    }
    private static void publicUrls(String text) {
        var urls=URL.matcher(text);
        while(urls.find()) {
            try {
                URI uri=URI.create(urls.group());String host=uri.getHost();if(host==null)throw unavailable();host=host.toLowerCase(Locale.ROOT);
                // 浏览器可把整数/八进制/十六进制及短IPv4写法归一成内网；只接受四段标准十进制。
                if(host.endsWith("."))host=host.substring(0,host.length()-1);
                if(NUMERIC_V4.matcher(host).matches()) {
                    if(!CANONICAL_V4.matcher(host).matches())throw unavailable();
                    for(String part:host.split("\\.",-1))if(Integer.parseInt(part)>255)throw unavailable();
                }
                if(uri.getRawUserInfo()!=null || host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal") || PRIVATE_V4.matcher(host).matches() || host.equals("[::1]") || host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe8") || host.startsWith("[fe9") || host.startsWith("[fea") || host.startsWith("[feb"))throw unavailable();
                String query=uri.getRawQuery();
                if(query!=null)for(String item:query.split("&",-1)) {
                    String key=URLDecoder.decode(item.split("=",2)[0],StandardCharsets.UTF_8);
                    if(key.indexOf('\ufffd')>=0 || key.codePoints().anyMatch(c->c<32 || c==127))throw unavailable();
                    key=key.toLowerCase(Locale.ROOT).replace("_","").replace("-","");
                    if(key.contains("token") || key.contains("secret") || key.contains("signature") || key.contains("credential") || key.equals("apikey") || key.equals("authorization") || key.equals("awsaccesskeyid"))throw unavailable();
                }
            } catch(RuntimeException ex){throw new ServiceException("公开配置包含未核对的环境或凭据目标",503);}
        }
    }
    private static void privateFields(Object value,int depth) {
        if(depth>48)throw unavailable();
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()) {
            String key=entry.getKey().toString().toLowerCase(Locale.ROOT).replace("_","").replace("-","");
            if(PRIVATE_KEYS.contains(key))throw unavailable();privateFields(entry.getValue(),depth+1);
        }
        else if(value instanceof List<?> list)for(var child:list)privateFields(child,depth+1);
        else if(value instanceof String text) {
            publicUrls(text);String first=text.stripLeading();
            // 公开目录中的嵌套JSON字符串仍核对；不让转义URL或编码的凭据对象绕过公开边界。
            if((first.startsWith("{") || first.startsWith("[") || first.startsWith("\"")) && JSON.isValid(text))privateFields(JSON.parse(text),depth+1);
        }
    }
}
