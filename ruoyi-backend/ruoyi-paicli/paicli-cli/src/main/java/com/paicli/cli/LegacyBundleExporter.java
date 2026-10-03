package com.paicli.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 离线来源只读导出。SQLite仅打开稳定字节副本，不加载原Store，不恢复任何任务。 */
public final class LegacyBundleExporter {
    public static final String SCHEMA = "paicli-legacy-v1";
    private static final long FILE_LIMIT = 50L * 1024 * 1024, BUNDLE_LIMIT = 16L * 1024 * 1024, SOURCE_LIMIT = 512L * 1024 * 1024;
    private static final int RECORD_LIMIT = 10_000, FILE_COUNT_LIMIT = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SECRET_KEYS = Set.of("apikey","apisecret","accesskey","secretkey","authkey","clientkey","passphrase","signingkey","token","password","passwd","authorization","proxyauthorization","accesstoken","refreshtoken","idtoken","bottoken","devicetoken","sessiontoken","bridgetoken","clientsecret","secret","privatekey","env","environment","headers","cookies","cookie","setcookie","credentials","credential","permittoken","permitcipher","secretreference","secretref");
    private static final String AUTH_OPTION = "(?:(?:api|access|auth|client|secret|private)[-_]?(?:key|secret|token)|(?:refresh|bearer)[-_]?token|token|secret|password|passwd|credentials?)";
    private static final Pattern AUTH_ARGUMENT = Pattern.compile("(?i)^--?("+AUTH_OPTION+")(?:(=|\\s+)(.*))?$");
    private static final Pattern AUTH_QUERY = Pattern.compile("[?&]([^&=#\\s]+)=([^&#\\s]*)");
    private static final Set<String> KINDS = Set.of("MEMORY","POLICY","PROMPT","SESSION","MESSAGE","LEDGER_EVENT","EVENT","TASK","PROVIDER","MODEL","MCP","SKILL","BROWSER","WECHAT","INDEX","SNAPSHOT","EVALUATION","HARNESS","AUDIT");
    public record ExportSummary(Path output, int records, String sourceFingerprint, List<String> warnings) { }
    private record SourceFile(String name, long size, String sha256, long modified) { }
    private final Path source, temporary;
    private final Clock clock;
    private final String promptScope;
    private final List<Map<String,Object>> records = new ArrayList<>();
    private final Set<String> identifiers = new HashSet<>(), secrets = new HashSet<>();
    private final LinkedHashSet<String> warnings = new LinkedHashSet<>();

    private LegacyBundleExporter(Path source,Path temporary,Clock clock,String promptScope) { this.source=source;this.temporary=temporary;this.clock=clock;this.promptScope=promptScope; }
    public static ExportSummary export(Path source,Path output) throws IOException { return export(source,output,Clock.systemUTC()); }
    static ExportSummary export(Path source,Path output,Clock clock) throws IOException {
        return export(source,output,clock,null);
    }
    static ExportSummary export(Path source,Path output,Clock clock,String promptScope) throws IOException {
        if(promptScope!=null&&!Set.of("PERSONAL","PROJECT").contains(promptScope))throw new IOException("提示词scope必须明确为PERSONAL或PROJECT");
        Path input=source.toAbsolutePath().normalize(), destination=output.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(input)||!Files.isDirectory(input))throw new IOException("来源必须是已存在的普通目录");
        input=input.toRealPath();
        if(destination.startsWith(input))throw new IOException("导出目标必须位于来源目录之外，不能修改原资料");
        if(destination.getParent()==null||!Files.isDirectory(destination.getParent())||Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("目标父目录须已存在，且不能覆盖已有文件");
        for(Path ancestor=destination.getParent();ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))throw new IOException("导出路径不能经过链接目录");
        Path scratch=Files.createTempDirectory("paicli-legacy-snapshot-");
        try {
            LegacyBundleExporter exporter=new LegacyBundleExporter(input,scratch,clock,promptScope);
            List<SourceFile> files=exporter.inventory(); if(files.isEmpty())throw new IOException("来源没有受支持的PaiCLI历史资料");
            exporter.copyStable(files);
            for(SourceFile file:files)if(file.name().endsWith(".json"))exporter.collectSecrets(exporter.readJson(file));
            for(SourceFile file:files)exporter.convert(file);
            for(Map<String,Object> record:exporter.records)record.put("payload",exporter.sanitizeMap(object(record.get("payload"))));
            // 转换期间再次读取源摘要，活跃写入或WAL切换不能被冒充一致来源。
            if(!files.equals(exporter.inventory()))throw new IOException("来源在导出期间变化，请停止原PaiCLI写入后重试");
            var manifest=files.stream().map(file->{Map<String,Object> item=new LinkedHashMap<>();item.put("name",file.name());item.put("sha256",file.sha256());item.put("size",file.size());return item;}).toList();
            String fingerprint=digest(JSON.writeValueAsBytes(manifest));
            exporter.warnings.add("原本机资料所有者为 local；必须在中心显式映射为当前工作区成员，未映射资料不能导入。");
            exporter.warnings.add("旧批准不导入；旧执行任务待核对、旧排队任务暂停，导入不创建派发任务。");
            Map<String,Object> bundle=new LinkedHashMap<>();bundle.put("schemaVersion",SCHEMA);bundle.put("sourceFingerprint",fingerprint);bundle.put("exportedAt",clock.instant().toString());bundle.put("records",exporter.records);bundle.put("warnings",List.copyOf(exporter.warnings));bundle.put("sourceFiles",manifest);
            byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);if(bytes.length>BUNDLE_LIMIT)throw new IOException("脱敏资料包超过中心16MB上限，请按来源子目录拆分导出");
            Path pending=Files.createTempFile(destination.getParent(),".paicli-export-",".tmp");
            try { Files.write(pending,bytes,StandardOpenOption.TRUNCATE_EXISTING);Files.move(pending,destination); }
            finally { Files.deleteIfExists(pending); }
            return new ExportSummary(destination,exporter.records.size(),fingerprint,List.copyOf(exporter.warnings));
        } catch(UncheckedIOException invalid){throw invalid.getCause();}
        finally { removeTemporary(scratch); }
    }
    private List<SourceFile> inventory() throws IOException {
        List<SourceFile> files=new ArrayList<>();long[] bytes={0};
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs)throws IOException{
                if(Files.isSymbolicLink(directory)||!directory.toRealPath().startsWith(source))throw new IOException("来源目录包含越界链接");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                if(Files.isSymbolicLink(file))throw new IOException("来源包含链接文件，未读取链接目标");
                String name=source.relativize(file).toString().replace('\\','/');if(!recognized(name))return FileVisitResult.CONTINUE;
                if(!attrs.isRegularFile()||attrs.size()>FILE_LIMIT)throw new IOException("来源文件不是受支持的普通文件或超过50MB："+name);
                bytes[0]+=attrs.size();if(bytes[0]>SOURCE_LIMIT||files.size()>=FILE_COUNT_LIMIT)throw new IOException("来源总量超过512MB或一万个文件，请拆分导出");
                files.add(new SourceFile(name,attrs.size(),digest(file),attrs.lastModifiedTime().toMillis()));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure)throws IOException{throw failure;}
        });files.sort(Comparator.comparing(SourceFile::name));return files;
    }
    private static boolean recognized(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        // SHM只是可重建的锁与WAL索引；Windows活跃事务会锁定其字节，不能读取或复制。
        return lower.startsWith("prompts/")||lower.startsWith(".paicli/prompts/")||lower.endsWith(".json")||lower.endsWith(".jsonl")||lower.endsWith(".db")||lower.endsWith(".sqlite")||lower.endsWith(".sqlite3")||lower.endsWith(".db-wal")||lower.endsWith(".sqlite-wal")||lower.endsWith(".sqlite3-wal")||lower.endsWith("/skill.md")||lower.equals("pai.md")||lower.endsWith("/pai.md")||lower.equals("pai.local.md")||lower.endsWith("/pai.local.md");
    }
    private void copyStable(List<SourceFile> files)throws IOException {
        for(SourceFile file:files){Path destination=temporary.resolve(file.name()).normalize();if(!destination.startsWith(temporary))throw new IOException("来源文件名越界");Files.createDirectories(destination.getParent());Files.copy(source.resolve(file.name()),destination);if(!file.sha256().equals(digest(destination)))throw new IOException("来源复制时变化，请停止原进程写入后重试");}
        if(!files.equals(inventory()))throw new IOException("来源或WAL复制时变化，请停止原进程写入后重试");
    }
    private JsonNode readJson(SourceFile file)throws IOException{return parseJson(Files.readString(temporary.resolve(file.name())),file.name());}
    private static JsonNode parseJson(String data,String location)throws IOException{try{return JSON.readTree(data);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IOException("来源JSON格式无效："+location);}}
    private void convert(SourceFile file)throws IOException {
        String name=file.name().toLowerCase(Locale.ROOT);
        if(name.startsWith("prompts/")||name.startsWith(".paicli/prompts/")) {
            if(promptScope==null)throw new IOException("来源包含提示词覆盖，须用--prompt-scope PERSONAL或PROJECT明确其正式归属");
            String relative=file.name().substring(file.name().indexOf("prompts/")+8);
            if(relative.isBlank()||relative.contains("..")||relative.startsWith("/"))throw new IOException("提示词相对路径无效");
            String content=redact(Files.readString(temporary.resolve(file.name())));
            if(content.isBlank()||content.getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("提示词正文为空或超过中心64KiB，请分开核对");
            add("PROMPT",legacy(file,relative),fileTime(file),Map.of("relativePath",relative,"scope",promptScope,"content",content,"sha256",digest(content.getBytes(StandardCharsets.UTF_8)),"sourceSha256",file.sha256(),"enabled",false,"status","IMPORTED_UNVERIFIED"));return;
        }
        if(name.endsWith("-wal")||name.endsWith("-shm"))return;
        if(name.endsWith(".db")||name.endsWith(".sqlite")||name.endsWith(".sqlite3")){database(file);return;}
        if(name.endsWith(".jsonl")){ledger(file);return;}
        if(name.endsWith("skill.md")){String body=redact(Files.readString(temporary.resolve(file.name())));add("SKILL",legacy(file,"skill"),fileTime(file),Map.of("name",Path.of(file.name()).getParent().getFileName().toString(),"scope","USER","content",body,"sha256",digest(body.getBytes(StandardCharsets.UTF_8)),"enabled",false,"effective",false,"status","UNTRUSTED_IMPORTED"));return;}
        if(name.endsWith("pai.md")||name.endsWith("pai.local.md")) {
            boolean local=name.endsWith("pai.local.md");
            add("POLICY",legacy(file,"pai"),fileTime(file),Map.of("name",local?"PAI.local":"PAI","relativePath",file.name(),"variant",local?"LOCAL_OVERRIDE":"PROJECT","scope","PROJECT","sourceSha256",file.sha256(),"content",redact(Files.readString(temporary.resolve(file.name()))),"status","IMPORTED_UNVERIFIED"));return;
        }
        JsonNode json=readJson(file);if(json==null)throw new IOException("空JSON来源："+file.name());
        if(name.endsWith("long_term_memory.json")){memory(file,json);return;}
        if(json.has("providers")){providers(file,json);return;}
        if(json.has("mcpServers")){if(!json.path("mcpServers").isObject())throw new IOException("MCP来源schema错误");json.path("mcpServers").fields().forEachRemaining(entry->{Map<String,Object> p=object(sanitize(entry.getValue()));p.put("name",entry.getKey());p.put("status","UNTRUSTED_IMPORTED");p.remove("trustedHash");addUnchecked("MCP",legacy(file,entry.getKey()),fileTime(file),p);});return;}
        String kind=name.contains("snapshot")?"SNAPSHOT":name.contains("harness")?"HARNESS":name.contains("eval")||name.contains("benchmark")?"EVALUATION":name.contains("browser")?"BROWSER":name.contains("wechat")?"WECHAT":name.contains("index")?"INDEX":name.contains("audit")?"AUDIT":null;
        if(kind==null){warnings.add("未识别JSON资料仅保留来源摘要，未猜测业务记录："+file.name());return;}
        Iterable<JsonNode> values=json.isArray()?json:List.of(json);int number=0;for(JsonNode value:values){Map<String,Object> p=object(sanitize(value));p.put("status","IMPORTED_UNVERIFIED");if(kind.equals("EVALUATION")){p.put("formalScores",null);p.put("publishable",false);p.put("paused",true);}add(kind,legacy(file,String.valueOf(++number)),fileTime(file),p);}
    }
    private void memory(SourceFile file,JsonNode json)throws IOException {
        JsonNode entries=json.isArray()?json:json.path("entries");if(!entries.isArray())throw new IOException("长期记忆来源schema不受支持");int row=0;
        for(JsonNode entry:entries){Map<String,Object> p=object(sanitize(entry));String id=entry.path("id").asText(String.valueOf(++row));JsonNode metadata=entry.path("metadata");boolean project="project".equalsIgnoreCase(metadata.path("scope").asText());
            p.put("scope",project?"PROJECT":"PERSONAL");String state=entry.path("status").asText();if(!Set.of("VERIFIED","PENDING","CONFLICT","STALE").contains(state))state=metadata.path("verification_pending").asBoolean(false)?"PENDING":metadata.has("conflictWith")||metadata.has("conflict_with")?"CONFLICT":"VERIFIED";
            p.put("status",state);p.put("source","LEGACY_MEMORY");if(entry.has("lastVerifiedAt"))p.put("verifiedAt",entry.path("lastVerifiedAt").asText());add("MEMORY",legacy(file,id),time(entry.get("timestamp"),fileTime(file)),p);}
    }
    private void providers(SourceFile file,JsonNode json)throws IOException {
        if(!json.path("providers").isObject())throw new IOException("供应商配置schema错误");
        Iterator<Map.Entry<String,JsonNode>> suppliers=json.path("providers").fields();while(suppliers.hasNext()){var supplier=suppliers.next();String providerId=legacy(file,supplier.getKey());Map<String,Object> p=object(sanitize(supplier.getValue()));p.remove("models");p.put("name",supplier.getKey());p.put("provider",supplier.getKey());p.put("protocol","NATIVE");p.put("secretConfigured",false);p.put("status","IMPORTED_UNCONFIGURED");add("PROVIDER",providerId,fileTime(file),p);
            JsonNode models=supplier.getValue().path("models");if(models.isObject()){Iterator<Map.Entry<String,JsonNode>> entries=models.fields();while(entries.hasNext()){var model=entries.next();Map<String,Object> m=object(sanitize(model.getValue()));m.put("modelId",model.getKey());m.put("legacyProviderId",providerId);m.put("enabled",false);add("MODEL",legacy(file,supplier.getKey()+":"+model.getKey()),fileTime(file),m);}}
            String selected=supplier.getValue().path("model").asText();if(!selected.isBlank()&&!models.has(selected))add("MODEL",legacy(file,supplier.getKey()+":"+selected),fileTime(file),Map.of("legacyProviderId",providerId,"modelId",selected,"contextWindow",128000,"supportsImages",false,"supportsThinking",false,"supportsTools",false,"capabilityKnown",false,"enabled",false));}
    }
    private void ledger(SourceFile file)throws IOException {
        try(BufferedReader input=Files.newBufferedReader(temporary.resolve(file.name()),StandardCharsets.UTF_8)){String line;int number=0;while((line=input.readLine())!=null){number++;if(line.isBlank())continue;JsonNode row=parseJson(line,file.name()+":"+number);if(row==null||!row.isObject()||row.path("schemaVersion").asInt()!=1||!row.path("sessionId").asText().matches("[A-Za-z0-9._:-]{1,128}"))throw new IOException("账本schema错误："+file.name()+":"+number);
            collectSecrets(row);String session="ledger:"+row.path("sessionId").asText();String occurred=time(row.get("timestamp"),fileTime(file));
            if(identifiers.add("SESSION:"+session))addDirect("SESSION",session,occurred,Map.of("title",row.path("sessionId").asText(),"mode",row.path("mode").asText("REACT").toUpperCase(Locale.ROOT),"status","HISTORICAL","approvalMode","ASK"));
            Map<String,Object> ledger=object(sanitize(row));ledger.remove("message");
            JsonNode message=row.get("message");if(message!=null&&!message.isNull()){Map<String,Object> payload=object(sanitize(message));payload.put("legacySessionId",session);payload.put("sequence",row.path("sequence").asLong());payload.put("_ledger",ledger);add("MESSAGE",legacy(file,"message:"+row.path("sequence").asText(String.valueOf(number))),occurred,payload);}
            else {Map<String,Object> payload=object(sanitize(row));payload.put("legacySessionId",session);payload.put("evidenceComplete",true);payload.put("_ledger",ledger);add("LEDGER_EVENT",legacy(file,"event:"+number),occurred,payload);}
        }}
    }
    private void database(SourceFile file)throws IOException {
        Path database=temporary.resolve(file.name());Files.deleteIfExists(database.resolveSibling(database.getFileName()+"-shm"));
        try(InputStream input=Files.newInputStream(database)){if(!Arrays.equals("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII),input.readNBytes(16)))throw new IOException("来源不是SQLite格式，拒绝创建或初始化："+file.name());}
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=connection.createStatement()){
            statement.execute("PRAGMA query_only=ON");statement.execute("PRAGMA trusted_schema=OFF");connection.setAutoCommit(false);
            try(ResultSet check=statement.executeQuery("PRAGMA integrity_check")){while(check.next())if(!"ok".equalsIgnoreCase(check.getString(1)))throw new IOException("SQLite/WAL一致性检查失败："+file.name());}
            Set<String> tables=new HashSet<>();try(ResultSet schema=statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")){while(schema.next())tables.add(schema.getString(1));}
            boolean supported=false;
            if(tables.contains("runtime_tasks")){supported=true;try(ResultSet result=statement.executeQuery("SELECT * FROM runtime_tasks ORDER BY id")){while(result.next()){Map<String,Object> p=row(result);collectSecrets(JSON.valueToTree(p));String id=Objects.toString(p.get("id"),"");String status=Objects.toString(p.get("status"),"").toUpperCase(Locale.ROOT);p.put("legacyStatus",status);p.put("status",taskStatus(status));p.put("input",p.getOrDefault("prompt",""));for(String[] names:List.of(new String[]{"started_at","startedAt"},new String[]{"finished_at","finishedAt"},new String[]{"duration_ms","durationMs"})){if(p.containsKey(names[0]))p.put(names[1],p.get(names[0]));}p.put("source","LEGACY_TASK");add("TASK",legacy(file,id),time(p.get("created_at"),fileTime(file)),sanitizeMap(p));}}}
            if(tables.contains("runtime_threads")){supported=true;try(ResultSet result=statement.executeQuery("SELECT * FROM runtime_threads ORDER BY id")){while(result.next()){Map<String,Object> p=row(result);collectSecrets(JSON.valueToTree(p));String id=Objects.toString(p.get("id"),"");p.put("title",id);p.put("status","HISTORICAL");p.put("mode","REACT");p.put("approvalMode","ASK");add("SESSION",legacy(file,"thread:"+id),time(p.get("created_at"),fileTime(file)),sanitizeMap(p));}}}
            if(tables.contains("runtime_events")){supported=true;try(ResultSet result=statement.executeQuery("SELECT * FROM runtime_events ORDER BY id")){while(result.next()){Map<String,Object> raw=row(result);collectSecrets(JSON.valueToTree(raw));JsonNode data=parseJson(Objects.toString(raw.get("data"),"{}"),file.name()+":event");collectSecrets(data);String type=Objects.toString(raw.get("type"),"");if(type.toUpperCase(Locale.ROOT).contains("APPROVAL")){warnings.add("原Runtime批准事件已省略，不能成为当前批准。");continue;}Map<String,Object> p=new LinkedHashMap<>();p.put("data",sanitize(data));p.put("type",type);p.put("sequence",raw.get("id"));p.put("legacySessionId",legacy(file,"thread:"+raw.get("thread_id")));p.put("evidenceComplete",false);add("EVENT",legacy(file,"event:"+raw.get("id")),time(raw.get("created_at"),fileTime(file)),p);}}}
            if(!supported){add("INDEX",legacy(file,"sqlite-metadata"),fileTime(file),Map.of("sourceFile",file.name(),"manifestHash",file.sha256(),"tables",tables.stream().filter(table->!table.startsWith("sqlite_")).sorted().toList(),"status","STALE_IMPORTED","rebuildRequired",true));warnings.add("未将本地SQLite索引视为正式任务或当前代码真相，须在Runner重建："+file.name());}
            connection.rollback();
        }catch(SQLException failure){throw new IOException("无法只读解析SQLite/WAL副本："+file.name(),failure);}
    }
    private static Map<String,Object> row(ResultSet row)throws SQLException {Map<String,Object> value=new LinkedHashMap<>();ResultSetMetaData metadata=row.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)value.put(metadata.getColumnLabel(i),row.getObject(i));return value;}
    static String taskStatus(String status){return Set.of("COMPLETED","FAILED","CANCELLED").contains(status)?status:Set.of("ENQUEUED","QUEUED","PENDING").contains(status)?"PAUSED_IMPORTED":"RECONCILE_REQUIRED";}
    private void collectSecrets(JsonNode node){
        if(node==null)return;
        if(node.isObject())node.fields().forEachRemaining(entry->{if(secretKey(entry.getKey()))collectValues(entry.getValue());else collectSecrets(entry.getValue());});
        else if(node.isArray())for(int i=0;i<node.size();i++){Matcher option=authArgument(node.get(i));if(option!=null){if(option.group(2)==null){if(i+1<node.size())collectValues(node.get(++i));}else collectSecretText(option.group(3));}else collectSecrets(node.get(i));}
        else if(node.isTextual()){JsonNode nested=nestedJson(node.asText());if(nested!=null)collectSecrets(nested);Matcher option=authArgument(node);if(option!=null&&option.group(2)!=null)collectSecretText(option.group(3));Matcher query=AUTH_QUERY.matcher(node.asText());while(query.find())if(secretKey(decode(query.group(1)))){collectSecretText(query.group(2));collectSecretText(decode(query.group(2)));}}
    }
    private void collectSecretText(String value){if(value!=null&&value.length()>=4)secrets.add(value);}
    private void collectValues(JsonNode node){if(node.isTextual()&&node.asText().length()>=4)secrets.add(node.asText());else if(node.isContainerNode())node.forEach(this::collectValues);}
    private static boolean secretKey(String key){return SECRET_KEYS.contains(key.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT));}
    private Object sanitize(JsonNode node){
        if(node==null||node.isNull())return null;
        if(node.isObject()){
            Map<String,Object> image=attachmentReference(node);if(image!=null)return image;
            Map<String,Object> result=new LinkedHashMap<>();node.fields().forEachRemaining(entry->{String key=entry.getKey();if(secretKey(key)){warnings.add("敏感凭证字段已从导出资料中移除。");return;}if(Set.of("base64Data","imageData","imageBase64","storageKey","permitHash","tokenHash").contains(key)){warnings.add("本机二进制内容或授权内部字段仅保留来源文件摘要，未嵌入资料包。");return;}result.put(key,sanitize(entry.getValue()));});return result;}
        if(node.isArray()){List<Object> values=new ArrayList<>();for(int i=0;i<node.size();i++){Matcher option=authArgument(node.get(i));if(option!=null){if(option.group(2)==null&&i+1<node.size())i++;warnings.add("命令参数中的凭证项已移除，导入配置必须重新核对。");}else values.add(sanitize(node.get(i)));}return values;}
        if(node.isTextual()){String value=node.asText();if(value.startsWith("data:image/")||value.startsWith("data:application/")){warnings.add("内嵌媒体仅保留内容摘要，目标附件须另行明确上传和映射。");return "[本机媒体已省略，SHA-256："+digest(value.getBytes(StandardCharsets.UTF_8))+"]";}JsonNode nested=nestedJson(value);if(nested!=null)return redact(JSON.valueToTree(sanitize(nested)).toString());return redact(value);}
        return JSON.convertValue(node,Object.class);
    }
    /** 原图片只记录解码字节摘要；导入必须显式映射到已上传的同用户中心产物。 */
    private Map<String,Object> attachmentReference(JsonNode node) {
        String encoded=null,mime=node.path("mimeType").asText("");
        for(String key:List.of("imageBase64","base64Data","imageData"))if(node.path(key).isTextual()&&!node.path(key).asText().isBlank()) {encoded=node.path(key).asText();break;}
        String url=node.path("imageUrl").asText("");
        if(encoded==null&&url.startsWith("data:image/"))encoded=url;
        if(encoded==null)return null;
        if(encoded.startsWith("data:")) {
            int split=encoded.indexOf(',');String descriptor=split<0?"":encoded.substring(5,split);
            if(split<0||!descriptor.endsWith(";base64"))throw new UncheckedIOException(new IOException("来源图片data URL格式无效，未发布部分资料包"));
            mime=descriptor.substring(0,descriptor.length()-7);encoded=encoded.substring(split+1);
        }
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new UncheckedIOException(new IOException("来源图片不是有效Base64，未发布部分资料包"));}
        if(bytes.length==0||bytes.length>FILE_LIMIT)throw new UncheckedIOException(new IOException("来源图片内容为空或超过50MB，未发布部分资料包"));
        Map<String,Object> result=new LinkedHashMap<>();
        Set<String> replaced=Set.of("type","imageBase64","base64Data","imageData","imageUrl","artifactId","storageKey","path","sha256","size","mimeType");
        node.fields().forEachRemaining(entry->{if(!replaced.contains(entry.getKey())&&!secretKey(entry.getKey()))result.put(entry.getKey(),sanitize(entry.getValue()));});
        String sha=digest(bytes);result.put("type","image_omitted");result.put("legacyAttachmentSha256",sha);result.put("sha256",sha);result.put("size",bytes.length);
        if(!mime.isBlank())result.put("mimeType",redact(mime));
        warnings.add("历史图片保留实际字节SHA-256引用，须另行上传同用户附件并按attachmentMappings显式映射；未映射不能导入。");
        return result;
    }
    private static Matcher authArgument(JsonNode value){if(value==null||!value.isTextual())return null;Matcher option=AUTH_ARGUMENT.matcher(value.asText());return option.matches()?option:null;}
    /** 旧SQLite结果列常把对象再次存成JSON文本；保留文本类型，同时移除其中凭证字段。 */
    private static JsonNode nestedJson(String text){String value=text.stripLeading();if(!value.startsWith("{")&&!value.startsWith("["))return null;try{JsonNode node=JSON.readTree(value);return node!=null&&node.isContainerNode()?node:null;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return null;}}
    private static String decode(String value){String result=value;for(int i=0;i<2;i++)try{String decoded=java.net.URLDecoder.decode(result,StandardCharsets.UTF_8);if(decoded.equals(result))break;result=decoded;}catch(IllegalArgumentException invalid){break;}return result;}
    private String redact(String value){String result=value;for(String secret:secrets)result=result.replace(secret,"[已移除敏感内容]");result=result.replaceAll("(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----","[已移除敏感内容]").replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{6,}","[已移除敏感内容]").replaceAll("\\b(?:sk-[A-Za-z0-9_-]{8,}|pc[dsw]_[A-Za-z0-9_-]{30,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)","[已移除敏感内容]").replaceAll("(?i)(?:--?"+AUTH_OPTION+"(?:\\s*=\\s*|\\s+)|"+AUTH_OPTION+"\\s*[:=]\\s*)[\"']?[^\\s,\"'}]+","[已移除敏感内容]");Matcher query=AUTH_QUERY.matcher(result);StringBuffer safe=new StringBuffer();while(query.find()){if(secretKey(decode(query.group(1)))){query.appendReplacement(safe,Matcher.quoteReplacement("[已移除敏感内容]"));warnings.add("URL中的认证参数已移除，导入配置必须重新核对。");}else query.appendReplacement(safe,Matcher.quoteReplacement(query.group()));}query.appendTail(safe);return safe.toString();}
    private Map<String,Object> sanitizeMap(Map<String,Object> value){return object(sanitize(JSON.valueToTree(value)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(value instanceof Map<?,?> map)return new LinkedHashMap<>((Map<String,Object>)map);return new LinkedHashMap<>(Map.of("legacyValue",value==null?"":value));}
    private void add(String kind,String id,String occurred,Map<String,Object> payload)throws IOException {if(!KINDS.contains(kind)||id.isBlank())throw new IOException("导出记录类型或ID无效");if(!identifiers.add(kind+":"+id))throw new IOException("来源记录ID重复，不能猜测覆盖："+kind+":"+id);addDirect(kind,id,occurred,payload);}
    private void addUnchecked(String kind,String id,String occurred,Map<String,Object> payload){try{add(kind,id,occurred,payload);}catch(IOException failure){throw new UncheckedIOException(failure);}}
    private void addDirect(String kind,String id,String occurred,Map<String,Object> payload)throws IOException {if(records.size()>=RECORD_LIMIT)throw new IOException("导出记录超过中心一万条上限，请按来源拆分");Map<String,Object> record=new LinkedHashMap<>();record.put("kind",kind);record.put("legacyId",id);record.put("legacyOwnerId","local");record.put("occurredAt",occurred);record.put("payload",payload);records.add(record);}
    private static String legacy(SourceFile file,String id){String prefix=digest(file.name().getBytes(StandardCharsets.UTF_8)).substring(0,16);return prefix+":"+(id.length()>200?digest(id.getBytes(StandardCharsets.UTF_8)):id);}
    private static String fileTime(SourceFile file){return Instant.ofEpochMilli(file.modified()).toString();}
    private static String time(Object value,String fallback)throws IOException {if(value==null)return fallback;try{if(value instanceof JsonNode node)value=node.isNumber()?node.asLong():node.asText();if(value instanceof Number number)return Instant.ofEpochMilli(number.longValue()).toString();String text=String.valueOf(value);return text.isBlank()?fallback:Instant.parse(text).toString();}catch(RuntimeException failure){throw new IOException("来源时间戳无效，不能编造历史时间",failure);}}
    private static String digest(Path file)throws IOException {try{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}return HexFormat.of().formatHex(hash.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void removeTemporary(Path directory)throws IOException {Path root=directory.toAbsolutePath().normalize();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(!path.toAbsolutePath().normalize().startsWith(root))throw new IOException("临时清理目标越界");Files.deleteIfExists(path);}}}
    /** 只负责离线导出，不读取中心URL、JWT或Provider环境变量。 */
    public static int run(String[] args){Path source=null,output=null;String promptScope=null;try{for(int i=args.length>0&&"export-legacy".equals(args[0])?1:0;i<args.length;i++){String option=args[i];if(!Set.of("--source","--output","--prompt-scope").contains(option)||++i>=args.length)throw new IOException("用法：paicli export-legacy --source <目录> --output <新JSON文件> [--prompt-scope PERSONAL|PROJECT]");if(option.equals("--source")){if(source!=null)throw new IOException("source不能重复");source=Path.of(args[i]);}else if(option.equals("--prompt-scope")){if(promptScope!=null)throw new IOException("prompt-scope不能重复");promptScope=args[i].toUpperCase(Locale.ROOT);}else{if(output!=null)throw new IOException("output不能重复");output=Path.of(args[i]);}}if(source==null||output==null)throw new IOException("须明确source目录和output新文件");ExportSummary result=export(source,output,Clock.systemUTC(),promptScope);System.out.println("已离线脱敏导出 "+result.records()+" 条历史记录；来源指纹 "+result.sourceFingerprint()+"；目标 "+result.output());for(String warning:result.warnings())System.out.println("提示："+warning);return 0;}catch(Exception failure){System.err.println("历史资料导出失败："+failure.getMessage());return 2;}}
}
