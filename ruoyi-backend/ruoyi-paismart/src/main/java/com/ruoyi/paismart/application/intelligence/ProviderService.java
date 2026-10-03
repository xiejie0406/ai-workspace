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

@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class ProviderService {
    public record Provider(String id,String scope,String name,String endpoint,String model,int dimension,String secretRef,boolean enabled,boolean active,long revision,Map<String,Object> config) {public Provider{endpoint=baseUrl(endpoint);}}
    private static String baseUrl(String value){String url=value==null?"":value.trim().replaceAll("/+$","");for(String suffix:List.of("/chat/completions","/embeddings","/rerank"))if(url.endsWith(suffix))return url.substring(0,url.length()-suffix.length()).replaceAll("/+$","");return url;}
    private final org.springframework.beans.factory.ObjectProvider<ModelGateway> modelGateway;private final JdbcTemplate db;private final TransactionTemplate tx;private final ControlledHttp http;private final IntelligenceSettings settings;
    public ProviderService(@Qualifier("paismartJdbc")JdbcTemplate db,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx,ControlledHttp http,IntelligenceSettings settings,org.springframework.beans.factory.ObjectProvider<ModelGateway> modelGateway){this.modelGateway=modelGateway;this.db=db;this.tx=tx;this.http=http;this.settings=settings;}
    private String scope(String s){String v=s.toUpperCase(Locale.ROOT);if(!Set.of("LLM","EMBEDDING","RERANK").contains(v))throw new IllegalArgumentException("供应商范围无效");return v;}
    public List<Map<String,Object>> list(String scope){List<Map<String,Object>> rows=db.queryForList("SELECT id,scope,name,endpoint,model,dimension,secret_ref AS secretRef,enabled,active,revision AS rowVersion,config_json AS configJson,updated_at AS updatedAt FROM ps_provider WHERE scope=? ORDER BY name",scope(scope));for(var row:rows){row.put("endpoint",baseUrl(Values.text(row.get("endpoint"))));row.put("provider",row.get("name"));row.put("address",row.get("endpoint"));row.put("config",Jsons.map(Values.text(row.remove("configJson"))));try{secret(get(Values.text(row.get("id"))));row.put("configured",true);}catch(RuntimeException missing){row.put("configured",false);}}return rows;}
    public List<Map<String,Object>> listAll(){List<Map<String,Object>> rows=new ArrayList<>();for(String scope:List.of("LLM","EMBEDDING","RERANK"))rows.addAll(list(scope));return rows;}
    public String secret(Provider p){if(!get(p.id()).enabled())throw new IllegalStateException("PROVIDER_UNAVAILABLE:供应商已停用");String providerCode=Values.text(p.config().get("providerCode"));if(!providerCode.matches("[a-z][a-z0-9_-]{1,63}"))throw new IllegalArgumentException("需配置托管用途对应的 providerCode");return settings.modelSecret(p.secretRef(),p.scope(),providerCode);}
    public Map<String,Object> scopeConfig(String scope){List<Map<String,Object>> rows=list(scope);if(rows.isEmpty()){Map<String,Object> empty=new LinkedHashMap<>();empty.put("scope",scope(scope));empty.put("provider","");empty.put("model","");empty.put("address","");empty.put("enabled",false);empty.put("configured",false);empty.put("rowVersion",0);return empty;}return rows.stream().filter(r->Values.bool(r.get("active"))).findFirst().orElse(rows.get(0));}
    public Map<String,Object> adaptInput(String scope,Map<String,Object> input){Map<String,Object> out=new LinkedHashMap<>(input);String code=Values.required(input,"provider",100);out.put("name",code);out.put("endpoint",input.getOrDefault("address",input.get("endpoint")));Map<String,Object> config=new LinkedHashMap<>(Values.map(input.get("config")));String platformCode=Values.text(config.get("providerCode"));if(platformCode.isBlank())platformCode=code.toLowerCase(Locale.ROOT);if(!platformCode.matches("[a-z][a-z0-9_-]{1,63}"))throw new IllegalArgumentException("平台供应商编码格式无效");config.put("providerCode",platformCode);out.put("config",config);boolean existingIndex="EMBEDDING".equalsIgnoreCase(scope)&&db.queryForObject("SELECT COUNT(*) FROM ps_index_revision WHERE state='ACTIVE'",Long.class)>0;out.put("active",input.containsKey("active")?Values.bool(input.get("active")):Values.bool(input.get("enabled"))&&!existingIndex);return out;}
    public Provider get(String id){List<Provider> rows=db.query("SELECT * FROM ps_provider WHERE id=?",(r,n)->new Provider(r.getString("id"),r.getString("scope"),r.getString("name"),r.getString("endpoint"),r.getString("model"),r.getInt("dimension"),r.getString("secret_ref"),r.getBoolean("enabled"),r.getBoolean("active"),r.getLong("revision"),Jsons.map(r.getString("config_json"))),id);if(rows.isEmpty())throw new IllegalArgumentException("供应商不存在");return rows.get(0);}
    public Provider active(String scope){List<String> ids=db.queryForList("SELECT id FROM ps_provider WHERE scope=? AND active=TRUE AND enabled=TRUE",String.class,scope(scope));if(ids.size()!=1)throw new IllegalStateException("PROVIDER_UNAVAILABLE:该范围未配置唯一激活供应商");return get(ids.get(0));}
    public Map<String,Object> snapshot(Provider p){Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.id());m.put("scope",p.scope());m.put("name",p.name());m.put("endpoint",p.endpoint());m.put("model",p.model());m.put("dimension",p.dimension());m.put("secretRef",p.secretRef());m.put("revision",p.revision());m.put("config",p.config());return m;}
    public Provider fromSnapshot(String json){Map<String,Object> m=Jsons.map(json);return new Provider(Values.text(m.get("id")),Values.text(m.get("scope")),Values.text(m.get("name")),Values.text(m.get("endpoint")),Values.text(m.get("model")),(int)Values.number(m.get("dimension"),0),Values.text(m.get("secretRef")),true,true,Values.number(m.get("revision"),1),Values.map(m.get("config")));}
    private void rejectInlineSecrets(Object value,int depth){if(depth>8)throw new IllegalArgumentException("供应商配置嵌套过深");if(value instanceof Map<?,?> map){for(var entry:map.entrySet()){String field=String.valueOf(entry.getKey());if(field.matches("(?i)(api[_-]?key|access[_-]?key(?:id|secret)?|secret|password|token|credential|authorization|private[_-]?key)")&&!Values.text(entry.getValue()).isBlank())throw new IllegalArgumentException("模型密钥仅允许通过平台托管用途引用，不能保存内联密钥");rejectInlineSecrets(entry.getValue(),depth+1);}}else if(value instanceof List<?> list)for(Object item:list)rejectInlineSecrets(item,depth+1);}
    public Map<String,Object> save(String scope,Map<String,Object> request){rejectInlineSecrets(request,0);if(Jsons.write(request).length()>32000)throw new IllegalArgumentException("供应商配置超出长度预算");String s=scope(scope);String id=Values.text(request.get("id"));boolean fresh=id.isBlank();if(fresh)id=Values.id();final String key=id;final boolean isNew=fresh;
        String endpoint=baseUrl(Values.required(request,"endpoint",1000));http.validated(endpoint);String name=Values.required(request,"name",100),model=Values.required(request,"model",200);int dimension="EMBEDDING".equals(s)?Values.range(request.get("dimension"),0,1,8192):0;
        String secret=Values.text(request.get("secretRef"));boolean enabled=Values.bool(request.get("enabled")),active=Values.bool(request.get("active"));long revision=Values.number(request.getOrDefault("rowVersion",request.get("revision")),0);
        return tx.execute(status->{Provider previous=isNew?null:get(key);if(previous!=null&&!previous.scope().equals(s))throw new IllegalArgumentException("供应商范围不可变更");String ref=secret.isBlank()&&previous!=null?previous.secretRef():secret;if(!ref.matches("ai\\.paismart\\.[a-z0-9._-]{2,100}"))throw new IllegalArgumentException("请在平台密钥页建立 Paismart 用途并填写引用");
            if(active&&!enabled)throw new IllegalArgumentException("停用供应商不能激活");
            if(active&&s.equals("EMBEDDING")&&db.queryForObject("SELECT COUNT(*) FROM ps_index_revision WHERE state='ACTIVE'",Long.class)>0)throw new IllegalStateException("INDEX_MODEL_MISMATCH:已有索引时请从索引重建完成模型切换");
            if(active)db.update("UPDATE ps_provider SET active=FALSE WHERE scope=?",s);
            if(isNew)db.update("INSERT INTO ps_provider(id,scope,name,endpoint,model,dimension,secret_ref,enabled,active,revision,config_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)",key,s,name,endpoint,model,dimension,ref,enabled,active,Jsons.write(Values.map(request.get("config"))),LocalDateTime.now());
            else if(db.update("UPDATE ps_provider SET name=?,endpoint=?,model=?,dimension=?,secret_ref=?,enabled=?,active=?,config_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",name,endpoint,model,dimension,ref,enabled,active,Jsons.write(Values.map(request.get("config"))),LocalDateTime.now(),key,revision)!=1)throw new IllegalStateException("VERSION_CONFLICT:供应商配置已变更");
            return Map.of("id",key,"scope",s,"rowVersion",isNew?1:revision+1);
        });
    }
    public Map<String,Object> test(long user,String id,String requestKey) {
        Values.required(Map.of("key",requestKey),"key",100);
        Provider p=get(id);long start=System.nanoTime();ModelGateway gateway=modelGateway.getObject();String operation="provider-test:"+id+":"+requestKey;
        Map<String,Object> result;
        if(p.scope().equals("EMBEDDING")){var response=gateway.embed(user,operation,List.of("连通测试"),p);result=Map.of("tokens",response.tokens(),"dimension",response.vectors().get(0).size());}
        else if(p.scope().equals("RERANK"))result=gateway.rerank(user,operation,"测试",List.of("测试"),p);
        else result=gateway.complete(user,operation,List.of(Map.of("role","user","content","请回答OK")),List.of(),4,p);
        return Map.of("state","READY","reachable",true,"latencyMs",(System.nanoTime()-start)/1_000_000,"responsePresent",!result.isEmpty());
    }
    public Map<String,Object> testForScope(long user,String scope,String id,String requestKey){String capability=scope(scope);Provider provider=id==null||id.isBlank()?active(capability):get(id);if(!provider.scope().equals(capability))throw new IllegalArgumentException("供应商不属于当前能力范围");return test(user,provider.id(),requestKey);}
}

