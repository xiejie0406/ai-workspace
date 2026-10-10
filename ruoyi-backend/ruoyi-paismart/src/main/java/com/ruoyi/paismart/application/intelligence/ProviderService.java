package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import com.ruoyi.system.secret.ManagedSecretService;
import com.ruoyi.paismart.application.jobs.JobService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class ProviderService {
    public record Provider(String id,String scope,String name,String endpoint,String model,int dimension,String secretRef,boolean enabled,boolean active,long revision,Map<String,Object> config) {public Provider{endpoint=baseUrl(endpoint);}}
    private static String baseUrl(String value){String url=value==null?"":value.trim().replaceAll("/+$","");for(String suffix:List.of("/chat/completions","/embeddings","/rerank","/responses"))if(url.endsWith(suffix))return url.substring(0,url.length()-suffix.length()).replaceAll("/+$","");return url;}
    private final org.springframework.beans.factory.ObjectProvider<ModelGateway> modelGateway;private final JdbcTemplate db;private final TransactionTemplate tx;private final ControlledHttp http;private final IntelligenceSettings settings;
    public ProviderService(@Qualifier("paismartJdbc")JdbcTemplate db,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx,ControlledHttp http,IntelligenceSettings settings,org.springframework.beans.factory.ObjectProvider<ModelGateway> modelGateway){this.modelGateway=modelGateway;this.db=db;this.tx=tx;this.http=http;this.settings=settings;}
    private String scope(String s){String v=s.toUpperCase(Locale.ROOT);if(!Set.of("LLM","EMBEDDING","RERANK").contains(v))throw new IllegalArgumentException("供应商范围无效");return v;}
    public List<Map<String,Object>> list(String scope){List<Map<String,Object>> rows=db.queryForList("SELECT id,scope,name,endpoint,model,dimension,secret_ref AS secretRef,enabled,active,revision AS rowVersion,config_json AS configJson,updated_at AS updatedAt FROM ps_provider WHERE scope=? ORDER BY name",scope(scope));for(var row:rows){row.put("endpoint",baseUrl(Values.text(row.get("endpoint"))));row.put("provider",row.get("name"));row.put("address",row.get("endpoint"));row.put("config",Jsons.map(Values.text(row.remove("configJson"))));Provider p=get(Values.text(row.get("id")));try{settings.resolvedModelSecret(p.secretRef(),p.scope(),Values.text(p.config().get("providerCode")));row.put("configured",true);}catch(RuntimeException missing){row.put("configured",false);}annotateCompatibility(row,p);}return rows;}
    public List<Map<String,Object>> listAll(){List<Map<String,Object>> rows=new ArrayList<>();for(String scope:List.of("LLM","EMBEDDING","RERANK"))rows.addAll(list(scope));return rows;}
    public static boolean isSub2Api(Provider p){return "SUB2API".equalsIgnoreCase(p.name())||"sub2api".equalsIgnoreCase(Values.text(p.config().get("providerCode")));}
    public String secret(Provider p){return resolvedSecret(p).value();}
    public ManagedSecretService.Resolved resolvedSecret(Provider p){
        Provider current=get(p.id());if(!current.enabled())throw new IllegalStateException("PROVIDER_UNAVAILABLE:供应商已停用");
        String code=Values.text(p.config().get("providerCode"));if(!code.matches("[a-z][a-z0-9_-]{1,63}"))throw new IllegalArgumentException("需配置托管用途对应的 providerCode");
        var resolved=settings.resolvedModelSecret(p.secretRef(),p.scope(),code);
        if(isSub2Api(p)){
            validateSub2(p.scope(),p.config());
            if(current.revision()!=p.revision())throw new IllegalStateException("VERSION_CONFLICT:文字模型冻结版本已变化，请重新发起");
            Map<String,Object> binding=Values.map(p.config().get("_secretBinding"));
            if(binding.isEmpty()||!sameData(binding,secretBinding(resolved.metadata())))throw new IllegalStateException("VERSION_CONFLICT:文字模型密钥版本已变化或任务没有冻结密钥");
            if(Values.bool(p.config().get("_compatibilityProbe")))requireProbeAttempt(p,current);
            else if(!compatibilityValid(current,resolved.metadata()))throw new IllegalStateException("PROVIDER_UNAVAILABLE:文字模型兼容验证无效，请重新验证");
        }
        return resolved;
    }
    private static Map<String,Object> secretBinding(ManagedSecretService.Metadata m){return Map.of("activeVersion",m.activeVersion(),"rowVersion",m.rowVersion(),"projectCode",m.projectCode(),"providerCode",m.providerCode(),"capabilityCode",m.capabilityCode(),"authMode",m.authMode());}
    private static Object canonicalData(Object value){if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((key,item)->sorted.put(String.valueOf(key),canonicalData(item)));return sorted;}if(value instanceof List<?> list)return list.stream().map(ProviderService::canonicalData).toList();return value;}
    private static boolean sameData(Object left,Object right){return Jsons.write(canonicalData(left)).equals(Jsons.write(canonicalData(right)));}
    private static Map<String,Object> outboundConfig(Map<String,Object> source){Map<String,Object> config=new LinkedHashMap<>(source);config.remove("compatibility");config.remove("_compatibilityProbe");config.remove("_compatibilityAttempt");return config;}
    private static boolean attemptExpired(Map<String,Object> evidence){try{String value=Values.text(evidence.get("attemptExpiresAt"));java.time.Instant expires=value.isBlank()?java.time.Instant.parse(Values.text(evidence.get("checkedAt"))).plusSeconds(360):java.time.Instant.parse(value);return !java.time.Instant.now().isBefore(expires);}catch(java.time.format.DateTimeParseException invalid){return false;}}
    public void requireProbeAttempt(Provider p){if(Values.bool(p.config().get("_compatibilityProbe")))requireProbeAttempt(p,get(p.id()));}
    private void requireProbeAttempt(Provider p,Provider current){Map<String,Object> evidence=Values.map(current.config().get("compatibility"));String attempt=Values.text(p.config().get("_compatibilityAttempt"));if(attempt.isBlank()||!attempt.equals(evidence.get("attemptId"))||!"PENDING_RECONCILIATION".equals(evidence.get("state"))||attemptExpired(evidence))throw new IllegalStateException("VERSION_CONFLICT:原兼容验证执行权已到期或被恢复，请读取当前验证结果");}
    private Provider copy(Provider p,Map<String,Object> config){return new Provider(p.id(),p.scope(),p.name(),p.endpoint(),p.model(),p.dimension(),p.secretRef(),p.enabled(),p.active(),p.revision(),config);}
    public Provider freeze(Provider p){if(!isSub2Api(p))return p;if(p.config().containsKey("_secretBinding")){resolvedSecret(p);return p;}var resolved=settings.resolvedModelSecret(p.secretRef(),p.scope(),Values.text(p.config().get("providerCode")));Map<String,Object> config=outboundConfig(p.config());config.put("_secretBinding",secretBinding(resolved.metadata()));Provider frozen=copy(p,config);resolvedSecret(frozen);return frozen;}
    public Provider freezeActiveText(){return freeze(active("LLM"));}
    public Provider queuedText(Map<String,Object> stored){if(!stored.isEmpty()){Provider p=fromSnapshot(Jsons.write(stored));if(isSub2Api(p))resolvedSecret(p);return p;}Provider p=active("LLM");if(isSub2Api(p))throw new IllegalStateException("VERSION_CONFLICT:旧任务未冻结文字模型，不能自动迁移到Sub2API，请重新发起");return p;}
    /** 首次选定的文字配置只写入原作业checkpoint，续行读取原版本。 */
    public Provider jobText(JobService.Job job,JobService jobs){
        jobs.requireLease(job.id(),job.leaseToken());
        var rows=db.queryForList("SELECT payload_json FROM ps_job_checkpoint WHERE job_id=? AND step='TEXT_PROVIDER'",job.id());
        if(!rows.isEmpty())return queuedText(Jsons.map(Values.text(rows.get(0).get("payload_json"))));
        if(Values.number(job.payload().get("_textProviderPolicy"),0)!=1)return queuedText(Map.of());
        Provider p=freezeActiveText();jobs.checkpoint(job.id(),job.leaseToken(),"TEXT_PROVIDER",snapshot(p));return p;
    }
    private static void validateSub2(String scope,Map<String,Object> config){
        if(!"LLM".equals(scope))throw new IllegalArgumentException("Sub2API仅允许对话文字用途");
        if(!"sub2api".equals(config.get("providerCode")))throw new IllegalArgumentException("Sub2API平台供应商编码必须为sub2api");
        if(!Set.of("responses","chat_completions").contains(Values.text(config.get("apiProtocol"))))throw new IllegalArgumentException("Sub2API必须明确调用API协议");
        Values.range(config.get("maxOutputTokens"),0,1,16000);
        Sub2ApiTextAdapter.validateReasoning(config);
        if("chat_completions".equals(config.get("apiProtocol"))&&!Set.of("max_tokens","max_completion_tokens").contains(Values.text(config.get("outputTokenParameter"))))throw new IllegalArgumentException("Chat输出参数必须显式选择");
        if("responses".equals(config.get("apiProtocol"))&&config.containsKey("outputTokenParameter"))throw new IllegalArgumentException("Responses不能配置Chat输出参数");
    }
    private String configFingerprint(Provider p,ManagedSecretService.Metadata metadata){Map<String,Object> config=outboundConfig(p.config());config.remove("_secretBinding");Map<String,Object> material=new TreeMap<>();material.put("id",p.id());material.put("endpoint",p.endpoint());material.put("model",p.model());material.put("scope",p.scope());material.put("secretRef",p.secretRef());material.put("config",new TreeMap<>(config));material.put("secret",new TreeMap<>(secretBinding(metadata)));try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Jsons.write(material).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private boolean compatibilityValid(Provider p,ManagedSecretService.Metadata metadata){Map<String,Object> evidence=Values.map(p.config().get("compatibility")),checks=Values.map(evidence.get("checks"));return p.enabled()&&"PASSED".equals(evidence.get("state"))&&List.of("text","stream","tool").stream().allMatch(k->Boolean.TRUE.equals(checks.get(k)))&&configFingerprint(p,metadata).equals(evidence.get("configFingerprint"))&&Values.number(evidence.get("secretActiveVersion"),-1)==metadata.activeVersion()&&Values.number(evidence.get("secretRowVersion"),-1)==metadata.rowVersion();}
    private void annotateCompatibility(Map<String,Object> row,Provider p){if(!isSub2Api(p))return;boolean valid=false;String reason="请完成文字、流式和工具兼容验证";try{var resolved=settings.resolvedModelSecret(p.secretRef(),p.scope(),"sub2api");valid=compatibilityValid(p,resolved.metadata());if(!p.enabled())reason="供应商尚未启用";else if(!valid)reason="兼容验证未通过或配置、密钥已变化";}catch(RuntimeException unavailable){reason=Values.safeError(unavailable);}row.put("compatibilityValid",valid);row.put("activationBlockedReason",valid?"":reason);Map<String,Object> evidence=Values.map(p.config().get("compatibility"));row.put("canStartNewVerification",p.enabled()&&"PENDING_RECONCILIATION".equals(evidence.get("state"))&&reconciledValidation(p.id(),evidence));}
    public Map<String,Object> scopeConfig(String scope){List<Map<String,Object>> rows=list(scope);if(rows.isEmpty()){Map<String,Object> empty=new LinkedHashMap<>();empty.put("scope",scope(scope));empty.put("provider","");empty.put("model","");empty.put("address","");empty.put("enabled",false);empty.put("configured",false);empty.put("rowVersion",0);return empty;}return rows.stream().filter(r->Values.bool(r.get("active"))).findFirst().orElse(rows.get(0));}
    public Map<String,Object> adaptInput(String scope,Map<String,Object> input){Map<String,Object> out=new LinkedHashMap<>(input);String code=Values.required(input,"provider",100);out.put("name",code);out.put("endpoint",input.getOrDefault("address",input.get("endpoint")));Map<String,Object> config=new LinkedHashMap<>(Values.map(input.get("config")));String platformCode=Values.text(config.get("providerCode"));if(platformCode.isBlank())platformCode=code.toLowerCase(Locale.ROOT);if(!platformCode.matches("[a-z][a-z0-9_-]{1,63}"))throw new IllegalArgumentException("平台供应商编码格式无效");config.put("providerCode",platformCode);out.put("config",config);boolean existingIndex="EMBEDDING".equalsIgnoreCase(scope)&&db.queryForObject("SELECT COUNT(*) FROM ps_index_revision WHERE state='ACTIVE'",Long.class)>0;out.put("active",input.containsKey("active")?Values.bool(input.get("active")):Values.bool(input.get("enabled"))&&!existingIndex);return out;}
    public Provider get(String id){List<Provider> rows=db.query("SELECT * FROM ps_provider WHERE id=?",(r,n)->new Provider(r.getString("id"),r.getString("scope"),r.getString("name"),r.getString("endpoint"),r.getString("model"),r.getInt("dimension"),r.getString("secret_ref"),r.getBoolean("enabled"),r.getBoolean("active"),r.getLong("revision"),Jsons.map(r.getString("config_json"))),id);if(rows.isEmpty())throw new IllegalArgumentException("供应商不存在");return rows.get(0);}
    public Provider active(String scope){List<String> ids=db.queryForList("SELECT id FROM ps_provider WHERE scope=? AND active=TRUE AND enabled=TRUE",String.class,scope(scope));if(ids.size()!=1)throw new IllegalStateException("PROVIDER_UNAVAILABLE:该范围未配置唯一激活供应商");return get(ids.get(0));}
    public Map<String,Object> snapshot(Provider p){Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.id());m.put("scope",p.scope());m.put("name",p.name());m.put("endpoint",p.endpoint());m.put("model",p.model());m.put("dimension",p.dimension());m.put("secretRef",p.secretRef());m.put("revision",p.revision());m.put("config",isSub2Api(p)?outboundConfig(p.config()):p.config());return m;}
    public Provider fromSnapshot(String json){Map<String,Object> m=Jsons.map(json);return new Provider(Values.text(m.get("id")),Values.text(m.get("scope")),Values.text(m.get("name")),Values.text(m.get("endpoint")),Values.text(m.get("model")),(int)Values.number(m.get("dimension"),0),Values.text(m.get("secretRef")),true,true,Values.number(m.get("revision"),1),Values.map(m.get("config")));}
    private void rejectInlineSecrets(Object value,int depth){if(depth>8)throw new IllegalArgumentException("供应商配置嵌套过深");if(value instanceof Map<?,?> map){for(var entry:map.entrySet()){String field=String.valueOf(entry.getKey());if(field.matches("(?i)(api[_-]?key|access[_-]?key(?:id|secret)?|secret|password|token|credential|authorization|private[_-]?key)")&&!Values.text(entry.getValue()).isBlank())throw new IllegalArgumentException("模型密钥仅允许通过平台托管用途引用，不能保存内联密钥");rejectInlineSecrets(entry.getValue(),depth+1);}}else if(value instanceof List<?> list)for(Object item:list)rejectInlineSecrets(item,depth+1);}
    public Map<String,Object> save(String scope,Map<String,Object> request){rejectInlineSecrets(request,0);if(Jsons.write(request).length()>32000)throw new IllegalArgumentException("供应商配置超出长度预算");String s=scope(scope);String id=Values.text(request.get("id"));boolean fresh=id.isBlank();if(fresh)id=Values.id();final String key=id;final boolean isNew=fresh;
        if(request.keySet().stream().anyMatch(field->Set.of("compatibilityValid","activationBlockedReason","canStartNewVerification").contains(field)))throw new IllegalArgumentException("供应商验证门禁只读，不能由客户端写入");
        String endpoint=baseUrl(Values.required(request,"endpoint",1000));http.validated(endpoint);String name=Values.required(request,"name",100),model=Values.required(request,"model",200);int dimension="EMBEDDING".equals(s)?Values.range(request.get("dimension"),0,1,8192):0;
        Map<String,Object> inputConfig=new LinkedHashMap<>(Values.map(request.get("config")));if(inputConfig.containsKey("compatibility")||inputConfig.keySet().stream().anyMatch(k->k.startsWith("_")))throw new IllegalArgumentException("兼容验证和服务端冻结资料不能由客户端写入");
        boolean sub2="SUB2API".equalsIgnoreCase(name)||"sub2api".equalsIgnoreCase(Values.text(inputConfig.get("providerCode")));if(sub2){if(!Set.of("providerCode","apiProtocol","maxOutputTokens","outputTokenParameter","reasoningEffort").containsAll(inputConfig.keySet()))throw new IllegalArgumentException("Sub2API存在未批准的配置参数");validateSub2(s,inputConfig);}
        String secret=Values.text(request.get("secretRef"));boolean enabled=Values.bool(request.get("enabled")),active=Values.bool(request.get("active"));long revision=Values.number(request.getOrDefault("rowVersion",request.get("revision")),0);
        if(sub2&&fresh&&(enabled||active))throw new IllegalArgumentException("Sub2API初次保存必须未启用、未激活");
        return tx.execute(status->{db.queryForList("SELECT id FROM ps_provider WHERE scope=? ORDER BY id FOR UPDATE",s);Provider previous=isNew?null:get(key);if(previous!=null&&previous.revision()!=revision)throw new IllegalStateException("VERSION_CONFLICT:供应商配置已变更");if(previous!=null&&!previous.scope().equals(s))throw new IllegalArgumentException("供应商范围不可变更");String ref=secret.isBlank()&&previous!=null?previous.secretRef():secret;if(!ref.matches("ai\\.paismart\\.[a-z0-9._-]{2,100}"))throw new IllegalArgumentException("请在平台密钥页建立 Paismart 用途并填写引用");
            Map<String,Object> config=new LinkedHashMap<>(inputConfig);if(sub2&&previous!=null&&previous.config().containsKey("compatibility"))config.put("compatibility",previous.config().get("compatibility"));
            Provider candidate=new Provider(key,s,sub2?"SUB2API":name,endpoint,model,dimension,ref,enabled,active,isNew?1:revision,config);
            if(active&&sub2){var resolved=settings.resolvedModelSecret(ref,s,"sub2api");if(!compatibilityValid(candidate,resolved.metadata()))throw new IllegalStateException("PROVIDER_UNAVAILABLE:兼容验证无效，不能激活");}
            if(active&&!enabled)throw new IllegalArgumentException("停用供应商不能激活");
            if(active&&s.equals("EMBEDDING")&&db.queryForObject("SELECT COUNT(*) FROM ps_index_revision WHERE state='ACTIVE'",Long.class)>0)throw new IllegalStateException("INDEX_MODEL_MISMATCH:已有索引时请从索引重建完成模型切换");
            if(active){if("LLM".equals(s))db.update("UPDATE ps_provider SET active=FALSE,revision=CASE WHEN UPPER(name)='SUB2API' THEN revision+1 ELSE revision END WHERE scope=? AND id<>? AND active=TRUE",s,key);else db.update("UPDATE ps_provider SET active=FALSE WHERE scope=?",s);}
            if(isNew)db.update("INSERT INTO ps_provider(id,scope,name,endpoint,model,dimension,secret_ref,enabled,active,revision,config_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)",key,s,candidate.name(),endpoint,model,dimension,ref,enabled,active,Jsons.write(config),LocalDateTime.now());
            else if(db.update("UPDATE ps_provider SET name=?,endpoint=?,model=?,dimension=?,secret_ref=?,enabled=?,active=?,config_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",candidate.name(),endpoint,model,dimension,ref,enabled,active,Jsons.write(config),LocalDateTime.now(),key,revision)!=1)throw new IllegalStateException("VERSION_CONFLICT:供应商配置已变更");
            return Map.of("id",key,"scope",s,"rowVersion",isNew?1:revision+1);
        });
    }
    public Map<String,Object> test(long user,String id,String requestKey) {
        Values.required(Map.of("key",requestKey),"key",100);
        Provider p=get(id);if(isSub2Api(p))throw new IllegalArgumentException("Sub2API请使用文字兼容验证并提供当前版本");long start=System.nanoTime();ModelGateway gateway=modelGateway.getObject();String operation="provider-test:"+id+":"+requestKey;
        Map<String,Object> result;
        if(p.scope().equals("EMBEDDING")){var response=gateway.embed(user,operation,List.of("连通测试"),p);result=Map.of("tokens",response.tokens(),"dimension",response.vectors().get(0).size());}
        else if(p.scope().equals("RERANK"))result=gateway.rerank(user,operation,"测试",List.of("测试"),p);
        else result=gateway.complete(user,operation,List.of(Map.of("role","user","content","请回答OK")),List.of(),4,p);
        return Map.of("state","READY","reachable",true,"latencyMs",(System.nanoTime()-start)/1_000_000,"responsePresent",!result.isEmpty());
    }
    public Map<String,Object> testForScope(long user,String scope,String id,String requestKey){String capability=scope(scope);Provider provider=id==null||id.isBlank()?active(capability):get(id);if(!provider.scope().equals(capability))throw new IllegalArgumentException("供应商不属于当前能力范围");return test(user,provider.id(),requestKey);}
    public Map<String,Object> testForScope(long user,String scope,Map<String,Object> request,String requestKey){
        if(!Set.of("id","mode","expectedVersion").containsAll(request.keySet()))throw new IllegalArgumentException("供应商测试参数未批准");
        String id=Values.text(request.get("id"));Provider p=id.isBlank()?active(scope):get(id);if(!p.scope().equals(scope(scope)))throw new IllegalArgumentException("供应商不属于当前能力范围");
        if(!isSub2Api(p)){if(request.containsKey("mode")||request.containsKey("expectedVersion"))throw new IllegalArgumentException("此供应商仅支持原连接测试");return test(user,p.id(),requestKey);}
        if(!"compatibility".equals(request.get("mode")))throw new IllegalArgumentException("Sub2API必须使用文字兼容验证");
        Values.required(Map.of("key",requestKey),"key",100);return compatibilityTest(user,p.id(),Values.number(request.get("expectedVersion"),-1),requestKey);
    }
    private Map<String,Object> compatibilityTest(long user,String id,long expected,String key){
        long start=System.nanoTime();Provider p=get(id);validateSub2(p.scope(),p.config());settings.requireExternal();http.validated(p.endpoint());
        var resolved=settings.resolvedModelSecret(p.secretRef(),p.scope(),"sub2api");if(!p.enabled())throw new IllegalStateException("PROVIDER_UNAVAILABLE:请先启用候选供应商");
        String operationKey=user+":"+key;String fingerprint=configFingerprint(p,resolved.metadata());
        Map<String,Object> prior=Values.map(p.config().get("compatibility"));
        if(key.equals(prior.get("operationKey"))&&Values.number(prior.get("initiatorUserId"),-1)!=user)throw new IllegalArgumentException("原验证幂等键属于另一操作人员，只能查看摘要；新增收费验证须明确确认并使用新幂等键");
        boolean restore=false;
        if(key.equals(prior.get("operationKey"))&&Values.number(prior.get("initiatorUserId"),-1)==user){
            if(!fingerprint.equals(prior.get("configFingerprint")))throw new IllegalStateException("VERSION_CONFLICT:原验证配置或密钥已变更，不能复用此幂等键");
            if(!"PENDING_RECONCILIATION".equals(prior.get("state"))||!attemptExpired(prior)||!canResumeValidation(p.id(),prior))return testResult(prior);
            if(Values.number(prior.get("providerVersion"),-1)!=p.revision())throw new IllegalStateException("VERSION_CONFLICT:原验证Provider版本已变化，不能恢复原收费请求");
            restore=true;
        }
        if(p.revision()!=expected)throw new IllegalStateException("VERSION_CONFLICT:供应商配置已变化，请刷新后验证");
        if(!restore&&"PENDING_RECONCILIATION".equals(prior.get("state"))&&!reconciledValidation(p.id(),prior))throw new IllegalStateException("MODEL_CALL_UNCERTAIN:原验证正在执行或结果待核对，禁止新增验证");
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("state","PENDING_RECONCILIATION");evidence.put("providerVersion",expected);evidence.put("configFingerprint",fingerprint);evidence.put("secretActiveVersion",resolved.metadata().activeVersion());evidence.put("secretRowVersion",resolved.metadata().rowVersion());evidence.put("operationKey",key);evidence.put("initiatorUserId",user);evidence.put("checkedAt",java.time.Instant.now().toString());evidence.put("checks",Map.of("text",false,"stream",false,"tool",false));evidence.put("usageSource","UNKNOWN");evidence.put("reason","验证正在执行；重复请求只读取原操作，不重新发送");
        evidence.put("attemptId",UUID.randomUUID().toString());evidence.put("attemptExpiresAt",java.time.Instant.now().plusSeconds(360).toString());if(restore)evidence.put("reason","已恢复原验证执行权：仅重放已完成子请求并继续未发子请求");
        persistEvidence(p,evidence,prior);
        Map<String,Object> claimedEvidence=new LinkedHashMap<>(evidence);
        Map<String,Object> config=outboundConfig(p.config());config.put("_secretBinding",secretBinding(resolved.metadata()));config.put("_compatibilityProbe",true);config.put("_compatibilityAttempt",evidence.get("attemptId"));Provider probe=copy(p,config);
        String prefix="provider-compat:"+id+":"+com.ruoyi.paismart.application.documents.DocumentService.sha256(operationKey.getBytes(StandardCharsets.UTF_8)).substring(0,32)+":";
        Map<String,Object> checks=new LinkedHashMap<>(Map.of("text",false,"stream",false,"tool",false));
        try{
            ModelGateway gateway=modelGateway.getObject();
            List<Map<String,Object>> textMessages=List.of(Map.of("role","user","content","请只回答兼容验证成功。"));
            Map<String,Object> text=gateway.complete(user,prefix+"text",textMessages,List.of(),128,probe);if(Values.text(text.get("content")).isBlank()||!Values.list(text.get("tool_calls")).isEmpty())throw new IllegalStateException("兼容文字结果不完整");checks.put("text",true);
            var tools=List.<Map<String,Object>>of(Map.of("type","function","function",Map.of("name","compatibility_echo","description","文字兼容合成验证，不读取任何业务资料","parameters",Map.of("type","object","properties",Map.of("value",Map.of("type","string","enum",List.of("synthetic"))),"required",List.of("value"),"additionalProperties",false))));
            List<Map<String,Object>> messages=new ArrayList<>(List.of(Map.of("role","user","content","必须调用compatibility_echo一次，参数value为synthetic；函数返回前不要直接回答，返回后只输出result字段的原文字。")));
            Map<String,Object> plan=gateway.streamTurn(user,prefix+"tool",messages,tools,part->{},()->{requireProbeAttempt(probe);return false;},probe,128);List<Object> calls=Values.list(plan.get("tool_calls"));if(calls.size()!=1)throw new IllegalStateException("合成工具规划必须恰好调用一次");Map<String,Object> call=Values.map(calls.get(0)),function=Values.map(call.get("function"));String callId=Values.text(call.get("id"));if(callId.isBlank()||!"compatibility_echo".equals(function.get("name"))||!Map.of("value","synthetic").equals(Jsons.map(Values.text(function.get("arguments")))))throw new IllegalStateException("合成工具名称、参数或关联ID无效");checks.put("stream",true);
            messages.add(new LinkedHashMap<>(plan));messages.add(Map.of("role","tool","tool_call_id",callId,"content","{\"value\":\"synthetic\",\"result\":\"兼容回灌成功\"}"));
            Map<String,Object> answer=gateway.streamTurn(user,prefix+"feedback",messages,List.of(),part->{},()->{requireProbeAttempt(probe);return false;},probe,128);if(!Values.text(answer.get("content")).contains("兼容回灌成功")||!Values.list(answer.get("tool_calls")).isEmpty())throw new IllegalStateException("合成工具回灌未返回固定结果文字");checks.put("tool",true);
            var rows=db.queryForList("SELECT status,estimated FROM ps_model_call WHERE user_id=? AND operation_id IN (?,?,?)",user,prefix+"text",prefix+"tool",prefix+"feedback");if(rows.size()!=3||rows.stream().anyMatch(row->!"COMPLETED".equals(row.get("status"))))throw new IllegalStateException("MODEL_CALL_UNCERTAIN:验证用量未完整结算");evidence.put("usageSource",rows.stream().anyMatch(row->Values.bool(row.get("estimated")))?"ESTIMATED":"SUPPLIER");evidence.put("state","PASSED");evidence.put("reason","");
        }catch(RuntimeException failure){long unknown=db.queryForObject("SELECT COUNT(*) FROM ps_model_call WHERE user_id=? AND operation_id IN (?,?,?) AND status IN ('STARTED','UNCERTAIN')",Long.class,user,prefix+"text",prefix+"tool",prefix+"feedback");evidence.put("state",unknown>0?"PENDING_RECONCILIATION":"FAILED");evidence.put("reason",Values.safeError(failure));}
        evidence.put("checks",checks);evidence.put("checkedAt",java.time.Instant.now().toString());evidence.put("latencyMs",(System.nanoTime()-start)/1_000_000);
        // 成功也要重新核对同一次密钥版本；变化后不写入过时的通过证据。
        var currentSecret=settings.resolvedModelSecret(p.secretRef(),p.scope(),"sub2api");if(!sameData(secretBinding(currentSecret.metadata()),secretBinding(resolved.metadata())))throw new IllegalStateException("VERSION_CONFLICT:验证期间密钥元数据已变化");
        persistEvidence(p,evidence,claimedEvidence);return testResult(evidence);
    }
    private Map<String,Object> testResult(Map<String,Object> evidence){return Map.of("state",evidence.get("state"),"latencyMs",evidence.getOrDefault("latencyMs",0L),"reason",evidence.getOrDefault("reason",""),"compatibility",new LinkedHashMap<>(evidence));}
    /** 超时恢复只沿原 operation 重放COMPLETED；未知或已人工核账的子请求绝不重送。 */
    private boolean canResumeValidation(String id,Map<String,Object> evidence){long user=Values.number(evidence.get("initiatorUserId"),-1);String key=Values.text(evidence.get("operationKey"));if(user<=0||key.isBlank())return false;String prefix=validationPrefix(id,user,key);var rows=db.queryForList("SELECT status FROM ps_model_call WHERE user_id=? AND operation_id IN (?,?,?)",user,prefix+"text",prefix+"tool",prefix+"feedback");return rows.stream().allMatch(row->"COMPLETED".equals(row.get("status")));}
    private static String validationPrefix(String id,long user,String key){return "provider-compat:"+id+":"+com.ruoyi.paismart.application.documents.DocumentService.sha256((user+":"+key).getBytes(StandardCharsets.UTF_8)).substring(0,32)+":";}
    /** 旧验证只读恢复；正式核账完成后，管理员可显式开始一个新幂等键的验证。 */
    private boolean reconciledValidation(String id,Map<String,Object> evidence){
        long user=Values.number(evidence.get("initiatorUserId"),-1);String key=Values.text(evidence.get("operationKey"));if(user<=0||key.isBlank())return false;
        String prefix="provider-compat:"+id+":"+com.ruoyi.paismart.application.documents.DocumentService.sha256((user+":"+key).getBytes(StandardCharsets.UTF_8)).substring(0,32)+":";
        var rows=db.queryForList("SELECT status FROM ps_model_call WHERE user_id=? AND operation_id IN (?,?,?)",user,prefix+"text",prefix+"tool",prefix+"feedback");
        if(rows.isEmpty()||rows.stream().anyMatch(row->!Set.of("RECONCILED","COMPLETED","STOPPED").contains(Values.text(row.get("status")))))return false;
        return rows.stream().anyMatch(row->"RECONCILED".equals(row.get("status")))||rows.size()==3;
    }
    private void persistEvidence(Provider p,Map<String,Object> evidence,Map<String,Object> expectedEvidence){tx.executeWithoutResult(status->{db.queryForList("SELECT id FROM ps_provider WHERE id=? FOR UPDATE",p.id());Provider current=get(p.id());if(current.revision()!=p.revision()||!sameData(Values.map(current.config().get("compatibility")),expectedEvidence))throw new IllegalStateException("VERSION_CONFLICT:验证期间供应商配置或验证操作已变化");Map<String,Object> config=new LinkedHashMap<>(current.config());config.put("compatibility",evidence);if(db.update("UPDATE ps_provider SET config_json=?,updated_at=? WHERE id=? AND revision=?",Jsons.write(config),LocalDateTime.now(),p.id(),p.revision())!=1)throw new IllegalStateException("VERSION_CONFLICT:验证版本写入失败");});}
}

