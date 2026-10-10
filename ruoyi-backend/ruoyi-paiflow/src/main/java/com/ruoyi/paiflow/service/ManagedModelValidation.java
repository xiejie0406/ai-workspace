package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.ruoyi.paiflow.mapper.ModelMapper;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 真实Hello与原modserv部署协议；脱敏回执绑定准确候选、授权、凭据版本。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ManagedModelValidation implements ModelService.ProviderValidationPort,ModelService.LocalDeploymentPort {
    private final ManagedModelRuntime runtime;private final ModelPrivateTransport transport;private final ModelMapper models;
    public ManagedModelValidation(ManagedModelRuntime runtime,ModelPrivateTransport transport,ModelMapper models){this.runtime=runtime;this.transport=transport;this.models=models;}
    private ModelRuntimeProperties.Profile profile(ModelService.Candidate c,ModelService.Authorization a){
        if(!runtime.current(a) || !Objects.equals(c.candidateHash(),a.candidateHash()) || !Objects.equals(c.endpoint(),a.endpoint()) || !Objects.equals(c.provider(),a.provider()) || !Objects.equals(c.credentialRef(),a.credentialRef()))throw error("模型校验许可已失效",403);
        var p=runtime.profile(c.provider(),JSON.parseObject(c.configurationJson()).getString("domain"),c.endpoint(),c.credentialRef());ManagedModelRuntime.scope(p,c.ownerUserId(),c.spaceId());return p;
    }
    @Override public ModelService.ProbeReceipt hello(ModelService.Candidate c,ModelService.Authorization a,JSONObject body){
        var p=profile(c,a);if(!p.isValidationAllowed() || c.type()!=1)throw error("Provider校验用途未批准",403);
        var config=JSON.parseObject(c.configurationJson());
        var expected=JSONObject.of("model",config.getString("domain"),"messages",List.of(JSONObject.of("role","user","content","Hello!")),"stream",false);
        if(!canonical(expected,REQUEST_BYTES).equals(canonical(body,REQUEST_BYTES)))throw error("Hello请求未绑定固定候选",409);
        var material=runtime.material(p,c.credentialRef(),a.credentialVersion());OkHttpClient client=null;
        try{
            if("openai".equals(p.getRuntimeProvider())){
                URI target=URI.create(c.endpoint());client=transport.client(target,p.getTimeoutSeconds(),List.of());body=copy(body);body.put("max_tokens",Math.min(128,p.getMaxTokens()));
                if(!runtime.current(a))throw error("Provider发送前许可失效",403);
                try(var response=transport.request(client,target,"POST",material.apiKey(),canonical(body,p.getMaxRequestBytes()))){
                    String raw=transport.bounded(response,FRAME_BYTES);var value=JSON.parseObject(raw);
                    if(response.code()!=200 || value==null || !(value.get("choices") instanceof JSONArray choices) || choices.isEmpty() || !(value.get("usage") instanceof JSONObject usage))throw error("Provider Hello未返回兼容响应",422);
                    checkUsage(usage);var evidence=JSONObject.of("protocol","openai","httpStatus",response.code(),"choicesCount",choices.size(),"usage",usage);
                    return receipt(c,a,raw,"READY",evidence);
                }
            }
            if(!"xinghuo".equals(p.getRuntimeProvider()))throw error("当前Provider校验协议不支持",503);
            URI target=ManagedModelRuntime.sparkEndpoint(c.endpoint());client=transport.client(target,p.getTimeoutSeconds(),List.of());
            var options=JSONObject.of("temperature",0.5,"maxTokens",Math.min(128,p.getMaxTokens()),"topK",1,"patchId",new JSONArray(),"respFormat",0,"searchDisable",true,"extraParams",new JSONObject());
            var params=JSONObject.of("provider","xinghuo","modelName",config.getString("domain"),"mode","chat","messages",body.get("messages"),"options",options);
            String wire=canonical(ModelProviderWire.request(params,material,Long.toString(c.ownerUserId()),false),p.getMaxRequestBytes());
            JSONObject frame=sparkHello(client,c,a,material,wire,p.getTimeoutSeconds());var facts=ModelProviderWire.facts("xinghuo",frame);
            if(!"stop".equals(facts.finish()) || facts.usage()==null)throw error("Spark Hello未返回真实完成及用量",422);checkUsage(facts.usage());
            return receipt(c,a,canonical(frame,FRAME_BYTES),"READY",JSONObject.of("protocol","xinghuo","providerCode",0,"providerStatus",2,"usage",facts.usage()));
        }catch(com.ruoyi.common.exception.ServiceException e){throw e;}catch(Exception e){throw error("Provider Hello未获得可确认回执",502);}finally{ModelPrivateTransport.close(client);}
    }
    private JSONObject sparkHello(OkHttpClient client,ModelService.Candidate c,ModelService.Authorization a,PrivateMaterial material,String wire,int seconds) throws Exception {
        var result=new CompletableFuture<JSONObject>();var total=new java.util.concurrent.atomic.AtomicLong();
        var socket=client.newWebSocket(new Request.Builder().url(ModelProviderWire.signedSpark(material,Instant.now()).toString()).build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){try{profile(c,a);if(!ws.send(wire))throw error("Spark Hello发送未接受",502);}catch(RuntimeException e){result.completeExceptionally(e);ws.cancel();}}
            @Override public void onMessage(WebSocket ws,String value){try{if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>FRAME_BYTES || total.addAndGet(value.length())>FRAME_BYTES)throw error("Spark Hello超出预算",413);profile(c,a);
                var frame=JSON.parseObject(value);var facts=ModelProviderWire.facts("xinghuo",frame);if(facts.finish()!=null){if(!"stop".equals(facts.finish()))throw error("Spark Hello供应商拒绝",422);result.complete(frame);ws.close(1000,"completed");}}
                catch(RuntimeException e){result.completeExceptionally(e);ws.cancel();}}
            @Override public void onMessage(WebSocket ws,ByteString bytes){if(bytes.size()>FRAME_BYTES){result.completeExceptionally(error("Spark Hello帧超预算",413));ws.cancel();}else onMessage(ws,bytes.utf8());}
            @Override public void onFailure(WebSocket ws,Throwable e,Response response){result.completeExceptionally(error("Spark Hello连接失败",502));}
            @Override public void onClosed(WebSocket ws,int code,String reason){if(!result.isDone())result.completeExceptionally(error("Spark Hello缺少结束帧",502));}
        });try{return result.get(seconds,TimeUnit.SECONDS);}finally{socket.cancel();}
    }
    @Override public ModelService.ProbeReceipt deploy(ModelService.Candidate c,ModelService.Authorization a){
        var p=profile(c,a);if(c.type()!=2 || !p.isDeploymentAllowed())throw error("本地模型部署用途未批准",403);
        var config=JSON.parseObject(c.configurationJson());var body=JSONObject.of("modelName",config.getString("domain"),"replicaCount",config.get("replicaCount"),"resourceRequirements",JSONObject.of("acceleratorCount",config.get("acceleratorCount")));
        Integer context=contextLength(config.getJSONObject("modelCategoryReq"));if(context!=null)body.put("contextLength",context);
        String service=null;if(c.modelId()!=null){var row=models.find(c.modelId());if(row==null || !Objects.equals(row.getSpaceId(),c.spaceId()) || !Objects.equals(row.getOwnerUserId(),c.ownerUserId()) || !Objects.equals(row.getRevision(),c.expectedRevision()))throw error("待部署模型版本已经变化",409);
            service=deploymentId(row.getReceiptJson());}
        String path=service==null?"/api/v1/modserv/deploy":"/api/v1/modserv/"+safeServiceId(service);String method=service==null?"POST":"PUT";
        var reply=modserv(c,a,p,method,path,canonical(body,p.getMaxRequestBytes()));var data=reply.value().getJSONObject("data");if(data==null)throw error("本地部署缺少实际data",502);
        String actual=safeServiceId(data.getString("serviceId"));return receipt(c,a,reply.raw(),"PENDING",JSONObject.of("protocol","modserv","deploymentId",actual,"httpStatus",reply.status(),"method",method));
    }
    record Reply(String raw,JSONObject value,int status) {}
    @Override public JSONArray list(long space,long user){
        var profiles=runtime.deploymentProfiles(space,user);if(profiles.isEmpty())throw error("本地模型目录用途未批准",503);var result=new JSONArray();var used=new HashSet<String>();
        for(var p:profiles){if(!used.add(p.getDeploymentEndpoint()))continue;
            var config=JSONObject.of("domain",p.getDomain());var c=new ModelService.Candidate(space,user,null,0,2,canonical(config,REQUEST_BYTES),sha("model-directory:"+space+":"+user+":"+p.getEndpoint()),p.getEndpoint(),p.getSourceProvider(),p.getCredentialRef());var a=runtime.authorize(c);
            var reply=modserv(c,a,p,"GET","/api/v1/modserv/list",null);if(!(reply.value().get("data") instanceof JSONArray data))throw error("本地模型目录缺少真实列表",502);
            for(Object raw:data){if(!(raw instanceof JSONObject row) || !(row.get("modelName") instanceof String) || !(row.get("modelPath") instanceof String))throw error("本地模型目录字段无效",502);result.add(JSONObject.of("modelName",row.get("modelName"),"modelPath",row.get("modelPath")));}
        }return result;
    }
    @Override public ModelService.ProbeReceipt status(ModelService.Candidate c,ModelService.Authorization a,String serviceId){
        var p=profile(c,a);if(c.type()!=2 || !p.isDeploymentAllowed())throw error("本地模型状态用途未批准",403);
        var reply=modserv(c,a,p,"GET","/api/v1/modserv/"+safeServiceId(serviceId),null);var data=reply.value().getJSONObject("data");
        if(data==null || !Objects.equals(serviceId,data.getString("serviceId")))throw error("本地模型状态不属于准确部署",409);
        String status=data.getString("status");if(status==null || !Set.of("running","pending","failed","initializing","notExsit","terminating").contains(status))throw error("本地模型状态协议未知",502);
        var evidence=JSONObject.of("protocol","modserv","deploymentId",serviceId,"deploymentStatus",status,"httpStatus",reply.status());
        if(!"running".equals(status))return receipt(c,a,reply.raw(),Set.of("failed","notExsit").contains(status)?"FAILED":"PENDING",evidence);
        if(p.getInferenceEndpoint()==null || !Objects.equals(p.getInferenceEndpoint(),ModelService.endpoint(data.getString("endpoint"))))throw error("部署实际推理目标与批准固定出口不一致",409);
        OkHttpClient client=null;try{URI target=URI.create(p.getInferenceEndpoint());client=transport.client(target,p.getTimeoutSeconds(),p.getAllowedInferenceAddresses());var material=runtime.material(p,c.credentialRef(),a.credentialVersion());profile(c,a);
            var body=JSONObject.of("model",JSON.parseObject(c.configurationJson()).getString("domain"),"messages",List.of(JSONObject.of("role","user","content","Hello!")),"stream",false,"max_tokens",Math.min(128,p.getMaxTokens()));
            try(var response=transport.request(client,target,"POST",material.apiKey(),canonical(body,p.getMaxRequestBytes()))){String raw=transport.bounded(response,FRAME_BYTES);var value=JSON.parseObject(raw);
                if(response.code()!=200 || value==null || !(value.get("choices") instanceof JSONArray choices) || choices.isEmpty() || !(value.get("usage") instanceof JSONObject usage))throw error("本地推理Hello未返回兼容回执",422);checkUsage(usage);
                evidence.put("inferenceEndpoint",p.getInferenceEndpoint());evidence.put("helloHttpStatus",response.code());evidence.put("usage",usage);return receipt(c,a,reply.raw()+"\n"+raw,"READY",evidence);}
        }catch(com.ruoyi.common.exception.ServiceException e){throw e;}catch(Exception e){throw error("本地推理状态未确认",502);}finally{ModelPrivateTransport.close(client);}
    }
    private Reply modserv(ModelService.Candidate c,ModelService.Authorization a,ModelRuntimeProperties.Profile p,String method,String path,String body){
        String base=p.getDeploymentEndpoint();if(base==null || base.isBlank())throw error("modserv固定服务未配置",503);URI target=URI.create(base.replaceAll("/+$","")+path);OkHttpClient client=null;
        try{var material=runtime.material(p,c.credentialRef(),a.credentialVersion());client=transport.client(target,p.getTimeoutSeconds(),p.getAllowedDeploymentAddresses());profile(c,a);
            try(var response=transport.request(client,target,method,material.apiKey(),body)){String raw=transport.bounded(response,FRAME_BYTES);var value=JSON.parseObject(raw);
                if(response.code()!=200 || value==null || integer(value.get("code"),0,Integer.MAX_VALUE,"modserv code")!=0)throw error("modserv未确认操作成功",502);return new Reply(raw,value,response.code());}
        }catch(com.ruoyi.common.exception.ServiceException e){throw e;}catch(Exception e){throw error("modserv操作结果未确认；不会自动重发",502);}finally{ModelPrivateTransport.close(client);}
    }
    static String deploymentId(String receipt){if(receipt==null)return null;var json=JSON.parseObject(receipt);var r=json==null?null:json.getJSONObject("receipt");if(r==null)return null;var e=JSON.parseObject(r.getString("evidenceJson"));return e==null?null:e.getString("deploymentId");}
    static String safeServiceId(String id){if(id==null || !id.matches("[A-Za-z0-9_-]{1,200}"))throw error("modserv服务标识无效",502);return id;}
    static Integer contextLength(JSONObject categories){
        if(categories==null || categories.get("contextLengthSystemId")==null)return null;
        try(var input=ManagedModelValidation.class.getResourceAsStream("/paiflow/model-category-source.json")){var data=JSON.parseObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            for(Object raw:data.getJSONArray("rows")){var row=(JSONObject)raw;if(Objects.equals(row.getLong("id"),categories.getLong("contextLengthSystemId"))){String name=row.getString("name").trim().toLowerCase(Locale.ROOT);return Integer.valueOf(name.endsWith("k")?name.substring(0,name.length()-1):name);}}
        }catch(Exception ignored){return null;}return null;
    }
    static void checkUsage(JSONObject usage){for(String key:List.of("prompt_tokens","completion_tokens","total_tokens"))integer(usage.get(key),0,Long.MAX_VALUE,"usage."+key);}
    private static ModelService.ProbeReceipt receipt(ModelService.Candidate c,ModelService.Authorization a,String raw,String state,JSONObject evidence){return new ModelService.ProbeReceipt(c.candidateHash(),c.endpoint(),c.provider(),a.credentialVersion(),a.authorizationId(),sha(raw),Instant.now(),state,canonical(evidence,FRAME_BYTES));}
}
