package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 每次MCP实际RPC均核对消费许可与冻结用途；明文只在内存，过期关闭连接或进程。 */
public final class ManagedMcpGrant implements AutoCloseable {
    public record Grant(Map<String,String> env, Map<String,String> headers, Instant expiresAt) {
        public Grant { env=Map.copyOf(env);headers=Map.copyOf(headers); }
        @Override public String toString(){return "Grant[env=<redacted>,headers=<redacted>,expiresAt="+expiresAt+"]";}
    }
    private final CenterHttpClient center;
    private final JsonNode config;
    private final Runnable stop;
    private final ScheduledExecutorService expiryTimer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"paicli-mcp-grant-expiry");t.setDaemon(true);return t;});
    private RunnerLease lease;
    private Supplier<JsonNode> executionBinding;
    private JsonNode frozenVersions;
    private volatile Grant latest;
    private volatile ScheduledFuture<?> expiryTask;
    private final Set<String> redactions=new HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();

    public ManagedMcpGrant(CenterHttpClient center,RunnerLease lease,CenterToolRegistry registry,JsonNode config,Runnable stop){this(center,lease,registry::currentExecutionBinding,config,stop);}
    public ManagedMcpGrant(CenterHttpClient center,RunnerLease lease,Supplier<JsonNode> binding,JsonNode config,Runnable stop){this.center=Objects.requireNonNull(center);this.lease=Objects.requireNonNull(lease);this.executionBinding=Objects.requireNonNull(binding);this.config=Objects.requireNonNull(config).deepCopy();this.stop=Objects.requireNonNull(stop);}
    /** 已停止的进程不可被重新绑定复活；新租约仍须通过一次实际消费许可。 */
    public synchronized void bind(RunnerLease next,CenterToolRegistry registry)throws IOException{bind(next,registry::currentExecutionBinding);}
    public synchronized void bind(RunnerLease next,Supplier<JsonNode> binding)throws IOException{if(closed.get())throw new IOException("MCP短期能力已经关闭");next.ensureActive();lease=Objects.requireNonNull(next);executionBinding=Objects.requireNonNull(binding);}

    public synchronized Grant current()throws IOException{
        try{
            if(closed.get())throw new IOException("MCP短期能力已经关闭");lease.ensureActive();validateConfig();JsonNode consumed=executionBinding.get();
            if(consumed==null||!consumed.isObject()||consumed.path("toolCallId").asText().isBlank()||consumed.path("permitToken").asText().isBlank())throw new IOException("MCP调用没有已消费的中心许可");
            ObjectNode request=lease.identity();for(String field:List.of("taskId","leaseId","fencingToken","version"))if(!request.path(field).asText().equals(consumed.path(field).asText()))throw new IOException("MCP消费许可不属于当前租约");
            request.put("toolCallId",consumed.path("toolCallId").asText());request.put("permitToken",consumed.path("permitToken").asText());request.set("configId",config.get("id"));request.set("configVersion",config.get("version"));request.set("configHash",config.get("configHash"));
            JsonNode response;try{response=center.post("/paicli/runner/v1/mcp-context",request,UUID.randomUUID().toString());}catch(IOException failure){throw new IOException("MCP中心短期能力刷新失败");}lease.ensureActive();if(closed.get())throw new IOException("MCP能力已在刷新期间过期并关闭");validateResponse(request,response);
            Map<String,String> env=values(response.path("env")),headers=values(response.path("headers"));Instant expiry=RunnerLease.expiry(response.path("expiresAt"));
            if(!expiry.isAfter(Instant.now())||expiry.isAfter(Instant.now().plusSeconds(61))||expiry.isAfter(lease.expiresAt()))throw new IOException("MCP中心短期能力期限非法或超过当前租约");
            if(latest!=null&&(!latest.env().equals(env)||!latest.headers().equals(headers)))throw new IOException("MCP固定版本凭证值发生变化");
            latest=new Grant(env,headers,expiry);redactions.addAll(env.values());var secretNames=refs(config,"headerSecretRefs").fieldNames();while(secretNames.hasNext()){String value=headers.get(secretNames.next());redactions.add(value);if(value.regionMatches(true,0,"Bearer ",0,7))redactions.add(value.substring(7));}
            if(expiryTask!=null)expiryTask.cancel(false);long delay=Math.max(1,java.time.Duration.between(Instant.now(),expiry).toMillis());expiryTask=expiryTimer.schedule(this::close,delay,TimeUnit.MILLISECONDS);return latest;
        }catch(IOException failure){lease.invalidate();close();throw failure;}catch(RuntimeException failure){lease.invalidate();close();throw new IOException("MCP中心短期能力校验失败");}
    }
    private void validateConfig()throws IOException{
        if(!config.isObject()||!config.path("id").asText().matches("[1-9][0-9]{0,18}")||config.path("version").asLong()<1||!config.path("configHash").asText().matches("[a-f0-9]{64}")||!config.path("configHash").asText().equals(config.path("trustedHash").asText()))throw new IOException("MCP固定配置缺少当前信任证明");
        String workspace=config.path("workspaceId").asText();if(!workspace.matches("[1-9][0-9]*")||lease.claim().has("workspaceId")&&!workspace.equals(lease.claim().path("workspaceId").asText()))throw new IOException("MCP固定配置不属于租约工作区");
        JsonNode env=refs(config,"envSecretRefs"),headers=refs(config,"headerSecretRefs");if(env.size()>32||headers.size()>32)throw new IOException("MCP固定引用映射超过上限");Set<String> names=new HashSet<>();
        for(String field:List.of("envSecretRefs","headerSecretRefs")){JsonNode group=refs(config,field);names.clear();var entries=group.fields();while(entries.hasNext()){var e=entries.next();String name=e.getKey();if(!e.getValue().isTextual()||!e.getValue().asText().matches("ai\\.paicli\\."+workspace+"\\.[A-Za-z0-9._-]+")||!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("MCP引用映射非法");if(field.equals("envSecretRefs")&&(!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")||name.toUpperCase(Locale.ROOT).startsWith("PAICLI_")||name.toUpperCase(Locale.ROOT).startsWith("DYLD_")||Set.of("RUNTIME_API_KEY","RUNNER_TOKEN","USER_TOKEN","PATH","PATHEXT","COMSPEC","SHELL","SYSTEMROOT","WINDIR","HOME","USERPROFILE","APPDATA","LOCALAPPDATA","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","LD_PRELOAD","LD_LIBRARY_PATH","HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","NO_PROXY").contains(name.toUpperCase(Locale.ROOT))))throw new IOException("MCP环境引用覆盖受保护的执行身份");if(field.equals("headerSecretRefs")&&(!name.matches("[!#$%&'*+.^_`|~A-Za-z0-9-]{1,128}")||Set.of("host","content-length","connection","transfer-encoding","proxy-authorization","upgrade","trailer").contains(name.toLowerCase(Locale.ROOT))))throw new IOException("MCP请求头引用覆盖受保护字段");}}
        if(config.path("env").isObject()&&!config.path("env").isEmpty())throw new IOException("MCP不能包含明文环境值");if(!env.isEmpty()&&!config.path("transport").asText().equals("STDIO")||!headers.isEmpty()&&!config.path("transport").asText().equals("HTTP"))throw new IOException("MCP凭证用途与传输不符");
    }
    private void validateResponse(ObjectNode request,JsonNode response)throws IOException{
        if(!response.isObject())throw new IOException("MCP中心能力响应缺失");for(String field:List.of("configId","configVersion","configHash","taskId","leaseId","fencingToken","version"))if(!request.path(field).asText().equals(response.path(field).asText()))throw new IOException("MCP响应不属于当前冻结配置或租约");
        for(String field:List.of("envSecretRefs","headerSecretRefs"))if(!CanonicalJson.sorted(refs(config,field)).equals(CanonicalJson.sorted(refs(response,field))))throw new IOException("MCP托管凭证引用已变化");
        JsonNode versions=response.path("secretVersions");if(!versions.isObject())throw new IOException("MCP凭证版本证明缺失");Set<String> expected=new HashSet<>();refs(config,"envSecretRefs").fieldNames().forEachRemaining(k->expected.add("env."+k));refs(config,"headerSecretRefs").fieldNames().forEachRemaining(k->expected.add("header."+k));Set<String> actual=new HashSet<>();versions.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw new IOException("MCP凭证版本映射与引用不符");for(JsonNode version:versions)if(!version.isIntegralNumber()||version.asLong()<1)throw new IOException("MCP凭证版本非法");
        if(frozenVersions!=null&&!CanonicalJson.sorted(frozenVersions).equals(CanonicalJson.sorted(versions)))throw new IOException("MCP固定用途密钥版本已变化");
        ObjectNode binding=CanonicalJson.MAPPER.createObjectNode();for(String field:List.of("configId","configVersion","configHash","envSecretRefs","headerSecretRefs","secretVersions","taskId","leaseId","fencingToken","version"))binding.set(field,response.get(field));if(!CanonicalJson.hash(binding).equals(response.path("grantHash").asText()))throw new IOException("MCP能力绑定哈希不匹配");
        Map<String,String> env=values(response.path("env")),headers=values(response.path("headers"));Set<String> envNames=new HashSet<>();refs(config,"envSecretRefs").fieldNames().forEachRemaining(envNames::add);if(!env.keySet().equals(envNames))throw new IOException("MCP下发环境不是批准的引用映射");Set<String> headerNames=new HashSet<>();refs(config,"headerSecretRefs").fieldNames().forEachRemaining(headerNames::add);JsonNode publicHeaders=config.path("headers");if(publicHeaders.isObject())publicHeaders.fieldNames().forEachRemaining(headerNames::add);if(!headers.keySet().equals(headerNames))throw new IOException("MCP下发请求头不是批准的引用映射");
        if(publicHeaders.isObject()){var entries=publicHeaders.fields();while(entries.hasNext()){var e=entries.next();if(!Set.of("accept","content-type","mcp-protocol-version").contains(e.getKey().toLowerCase(Locale.ROOT))||!e.getValue().isTextual()||!e.getValue().asText().equals(headers.get(e.getKey())))throw new IOException("MCP普通请求头与批准配置不一致");}}
        for(var entry:headers.entrySet())if(entry.getValue().indexOf('\r')>=0||entry.getValue().indexOf('\n')>=0)throw new IOException("MCP下发请求头包含非法控制字符");frozenVersions=versions.deepCopy();
    }
    private static JsonNode refs(JsonNode value,String field)throws IOException{JsonNode result=value.path(field);if(result.isMissingNode())return CanonicalJson.MAPPER.createObjectNode();if(!result.isObject())throw new IOException("MCP凭证引用必须为对象");return result;}
    private static Map<String,String> values(JsonNode values)throws IOException{if(!values.isObject())throw new IOException("MCP凭证值映射缺失");Map<String,String> result=new LinkedHashMap<>();var fields=values.fields();while(fields.hasNext()){var e=fields.next();if(!e.getValue().isTextual()||e.getValue().asText().isBlank()||e.getValue().asText().length()>16400||e.getValue().asText().indexOf('\0')>=0)throw new IOException("MCP下发凭证格式非法");result.put(e.getKey(),e.getValue().asText());}return result;}
    /** 一次匹配原文，替换不放大UTF-8正文，也不会再次替换刚生成的掩码。 */
    public synchronized String redact(String text){
        if(text==null||redactions.isEmpty())return text;
        var values=redactions.stream().filter(value->!value.isEmpty()).sorted(Comparator.comparingInt(String::length).reversed()).toList();
        if(values.isEmpty())return text;
        String mask="*";if(redactions.contains(mask)){mask="";for(char candidate=33;candidate<127;candidate++)if(candidate!='"'&&candidate!='\\'&&!redactions.contains(String.valueOf(candidate))){mask=String.valueOf(candidate);break;}}
        String expression=values.stream().map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return java.util.regex.Pattern.compile(expression).matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(mask));
    }
    /** 没有完整RPC响应的IO/解析失败不能变成可安全重试的工具失败。 */
    public synchronized void uncertainExecution(){lease.invalidate();close();}
    public boolean isClosed(){return closed.get();}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;if(expiryTask!=null)expiryTask.cancel(false);expiryTimer.shutdownNow();latest=null;try{stop.run();}catch(RuntimeException ignored){/* 关闭故障不能恢复已撤销能力，也不回显客户端异常中的秘密。 */}}
    @Override public String toString(){return "ManagedMcpGrant[configuration=<redacted>,credentials=<redacted>]";}
}
