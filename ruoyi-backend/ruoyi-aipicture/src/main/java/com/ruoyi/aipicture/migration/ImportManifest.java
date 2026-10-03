package com.ruoyi.aipicture.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import com.ruoyi.aipicture.domain.Json;
import com.ruoyi.aipicture.worker.WorkerSignatures;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

/** 人工导出的原数据包；不读取源数据库、账号秘密或远程对象。 */
public record ImportManifest(Path root, String dataset, String batchId, long exportedAt,
        Map<String,Long> owners, Map<String,List<Map<String,Object>>> entities, String sha256) {
    public static final Set<String> ENTITIES=Set.of("assets","edit_sessions","session_assets","edit_history","tool_runs","agent_runs","asset_documents","selections");
    private static final Set<String> PRIVATE_FIELDS=Set.of("password","password_hash","hashed_password","jwt","jwt_secret","access_token","refresh_token","api_key","authorization","database_url");
    public static ImportManifest read(Path path) throws IOException {
        Path actual=path.toRealPath();if(!Files.isRegularFile(actual)||Files.size(actual)>32L*1024*1024) throw invalid("manifest 必须是至多32MB的普通JSON文件");
        return parse(actual.getParent(),Json.map(Files.readString(actual,StandardCharsets.UTF_8)));
    }
    public static ImportManifest parse(Path root,Map<String,Object> input) throws IOException {
        var allowed=new HashSet<>(ENTITIES);allowed.addAll(Set.of("version","source_dataset","import_batch_id","exported_at","owners"));
        for(String key:input.keySet()) if(!allowed.contains(key)) throw invalid("manifest 未声明字段："+key);
        rejectSecrets(input);if(!"1".equals(input.get("version"))) throw invalid("仅支持 manifest version=1");
        String dataset=Json.string(input.get("source_dataset"));if(!dataset.matches("[A-Za-z0-9._-]{1,128}")) throw invalid("source_dataset 需要稳定的1–128字符标识");
        String batch=uuid(input.get("import_batch_id"));long exported=time(input.get("exported_at"));var owners=new LinkedHashMap<String,Long>();
        if(!(input.get("owners") instanceof List<?>)) throw invalid("必须显式提供原用户到既有若依用户的映射");
        for(Object value:Json.list(input.get("owners"))) {
            var owner=object(value);only(owner,Set.of("source_user_id","ruoyi_user_id"));String source=uuid(owner.get("source_user_id"));long target=integer(owner.get("ruoyi_user_id"),1,Long.MAX_VALUE);
            if(owners.putIfAbsent(source,target)!=null) throw invalid("源用户映射重复");
        }
        var entities=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String name:List.of("assets","edit_sessions","session_assets","edit_history","tool_runs","agent_runs","asset_documents","selections")) {
            Object raw=input.getOrDefault(name,List.of());if(!(raw instanceof List<?> list)||list.size()>100000) throw invalid(name+" 必须为有界数组");
            entities.put(name,Json.list(raw).stream().map(ImportManifest::object).map(ImportManifest::copy).toList());
        }
        Path actualRoot=root.toRealPath();if(!Files.isDirectory(actualRoot)) throw invalid("manifest 根目录不存在");
        return new ImportManifest(actualRoot,dataset,batch,exported,Collections.unmodifiableMap(owners),Collections.unmodifiableMap(entities),hash(input));
    }
    public List<Map<String,Object>> rows(String entity) {return entities.getOrDefault(entity,List.of());}
    public long owner(Object source) {Long target=owners.get(uuid(source));if(target==null) throw invalid("存在未显式映射的源用户");return target;}
    public static Map<String,Object> object(Object value) {if(!(value instanceof Map<?,?>)) throw invalid("预期JSON对象");return Json.map(value);}
    public static void only(Map<String,Object> row,Set<String> fields) {for(String field:row.keySet()) if(!fields.contains(field)) throw invalid("实体存在未声明字段："+field);}
    public static String uuid(Object value) {String text=Json.string(value);if(!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("源ID需要完整UUID");return UUID.fromString(text).toString();}
    public static long integer(Object value,long minimum,long maximum) {
        if(!(value instanceof Number number)) throw invalid("整数元数据缺失");
        try {long result=new java.math.BigDecimal(number.toString()).longValueExact();if(result<minimum||result>maximum) throw invalid("整数元数据越界");return result;}catch(ArithmeticException e){throw invalid("整数元数据不合法");}
    }
    public static long time(Object value) {if(!(value instanceof String text)) throw invalid("源时间需要带时区的ISO8601字符串");try {long result=OffsetDateTime.parse(text).toInstant().toEpochMilli();if(result<0) throw invalid("源时间早于epoch");return result;}catch(java.time.DateTimeException e){throw invalid("源时间必须显式包含时区");}}
    public static String write(Object value) {return JSON.toJSONString(value,JSONWriter.Feature.WriteNulls);}
    public static Map<String,Object> copy(Map<String,Object> value) {return Json.map(write(value));}
    public static String hash(Object value) {return WorkerSignatures.sha256(write(Json.canonical(value)).getBytes(StandardCharsets.UTF_8));}
    public static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
    private static void rejectSecrets(Object value) {
        if(value instanceof Map<?,?>) for(var entry:Json.map(value).entrySet()) {if(PRIVATE_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))) throw invalid("manifest 禁止包含认证秘密字段");rejectSecrets(entry.getValue());}
        else if(value instanceof List<?>) for(Object child:Json.list(value)) rejectSecrets(child);
    }
}
