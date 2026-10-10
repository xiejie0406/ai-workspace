package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.ByteString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 正式中心生产端。私有材料只在本进程内；原帧逐次落库ACK后才读取下一帧。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ManagedModelProducer implements ProducerBridge {
    private final ManagedModelRuntime runtime;private final ModelPrivateTransport transport;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"paiflow-model-producer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String,Handle> active=new ConcurrentHashMap<>();
    private static final class Handle {final PrivateGrant grant;volatile Call call;volatile WebSocket socket;final AtomicBoolean cancelled=new AtomicBoolean();Handle(PrivateGrant g){grant=g;}}
    public ManagedModelProducer(ManagedModelRuntime runtime,ModelPrivateTransport transport){this.runtime=runtime;this.transport=transport;}
    @Override public boolean available(ModelRuntimeCatalog.FixedModel m){try{return runtime.profile(m).isInvocationAllowed() && Set.of("openai","xinghuo").contains(m.provider());}catch(RuntimeException e){return false;}}
    @Override public DispatchAck submit(PrivateGrant grant,ProducerCallbacks callbacks){
        var handle=new Handle(grant);if(active.putIfAbsent(grant.operationId(),handle)!=null)throw error("操作已存在私有派发",409);
        try{executor.execute(()->produce(handle,callbacks));}catch(RejectedExecutionException e){active.remove(grant.operationId(),handle);throw error("模型生产队列已满",503);}
        return new DispatchAck(grant.operationId(),grant.grantId(),true);
    }
    private void produce(Handle h,ProducerCallbacks cb){
        boolean sent=false;OkHttpClient client=null;
        try{
            var p=JSON.parseObject(h.grant.parametersJson());var profile=runtime.profileByGrant(h.grant);
            Duration remaining=Duration.between(Instant.now(),h.grant.deadline());if(remaining.isNegative() || remaining.isZero())throw error("模型调用期限已过",403);
            int seconds=(int)Math.min(profile.getTimeoutSeconds(),Math.max(1,(remaining.toMillis()+999)/1000));
            String provider=p.getString("provider");var packet=ModelProviderWire.request(p,h.grant.privateMaterial(),h.grant.providerUid(),true,h.grant.engineProfile());
            String wire="xinghuo".equals(provider)?canonicalSparkWire(packet,profile.getMaxRequestBytes()):canonical(packet,profile.getMaxRequestBytes());
            URI target=URI.create("openai".equals(provider)?h.grant.privateMaterial().baseUrl()+"/chat/completions":h.grant.privateMaterial().baseUrl());
            client=transport.client(target,seconds,profile.getAllowedInferenceAddresses()).newBuilder().callTimeout(remaining).readTimeout(remaining).build();assertActive(h,cb);
            // 从这里起发送状态未知也不能自动重新调用，异常只交脱敏事实。
            sent=true;
            if("openai".equals(provider))openai(h,cb,client,target,wire);
            else spark(h,cb,client,wire);
        }catch(Exception e){try{String kind=e instanceof com.ruoyi.common.exception.ServiceException se && Objects.equals(se.getCode(),403)?"authorization":"transport";
            cb.recordFailure(h.grant,new FailureFacts(h.cancelled.get()?"cancelled":kind,"PRIVATE_PRODUCER_FAILED",sent,true,false));}catch(RuntimeException ignored){/* 原已持久事实保留；不能伪造失败确认。 */}}
        finally{if(h.call!=null)h.call.cancel();if(h.socket!=null)h.socket.cancel();ModelPrivateTransport.close(client);active.remove(h.grant.operationId(),h);}
    }
    private static void assertActive(Handle h,ProducerCallbacks cb){if(h.cancelled.get() || !h.grant.deadline().isAfter(Instant.now()))throw error("模型私有调用已停止或到期",403);var ack=cb.assertActive(h.grant);checkAck(h.grant,ack,null);if(!Boolean.TRUE.equals(ack.get("active")))throw error("模型活动确认无效",409);}
    static void checkAck(PrivateGrant grant,JSONObject ack,Long seq){
        if(ack==null)throw error("模型中心ACK缺失",409);for(var e:grant.binding().entrySet())if(!Objects.equals(e.getValue(),ack.get(e.getKey())))throw error("模型中心ACK绑定不一致",409);
        if(seq!=null && (!Boolean.TRUE.equals(ack.get("accepted")) || ack.getLongValue("seq")!=seq))throw error("模型中心帧ACK序号不一致",409);
    }
    static void checkFrameAck(PrivateGrant grant,JSONObject ack,long seq){checkAck(grant,ack,seq);if(ack.getLongValue("lastSeq")!=seq || !Boolean.FALSE.equals(ack.get("done")) || !"RUNNING".equals(ack.getString("state")))throw error("模型中心帧ACK状态不一致",409);}
    static void checkFinishAck(PrivateGrant grant,JSONObject ack,long last,long finish,String reason){
        checkAck(grant,ack,last);String state="stop".equals(reason)?"COMPLETED":"FAILED";
        if(ack.getLongValue("lastSeq")!=last || ack.getLongValue("finishSeq")!=finish || !Boolean.TRUE.equals(ack.get("done")) || !state.equals(ack.getString("state")) || !Objects.equals("stop".equals(reason)?null:"provider",ack.get("failureKind")))throw error("模型中心结束ACK未确认真实终态",409);
    }
    private void openai(Handle h,ProducerCallbacks cb,OkHttpClient client,URI target,String body) throws IOException {
        h.call=client.newCall(new Request.Builder().url(target.toString()).header("Authorization","Bearer "+h.grant.privateMaterial().apiKey()).header("Accept","text/event-stream")
            .post(RequestBody.create(body,MediaType.get("application/json; charset=utf-8"))).build());assertActive(h,cb);
        try(var response=h.call.execute()){
            if(response.code()!=200 || response.body()==null || response.header("Content-Type","").toLowerCase(Locale.ROOT).indexOf("text/event-stream")<0)throw error("Provider流协议未确认",502);
            var input=response.body().byteStream();long seq=0,finishSeq=0;String finish=null;int total=0;var data=new StringBuilder();
            for(;;){assertActive(h,cb);String line=line(input,FRAME_BYTES);if(line==null)break;total+=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;if(total>STREAM_BYTES)throw error("Provider wire超出预算",413);
                if(line.isEmpty()){
                    if(data.isEmpty())continue;String value=data.toString();data.setLength(0);
                    if("[DONE]".equals(value)){if(finishSeq<1)throw error("Provider未返回真实结束帧",502);checkFinishAck(h.grant,cb.finishStream(h.grant,seq,finishSeq,finish),seq,finishSeq,finish);return;}
                    JSONObject raw=JSON.parseObject(value);var facts=ModelProviderWire.facts("openai",raw,h.grant.engineProfile(),JSON.parseObject(h.grant.optionsJson()));checkFrameAck(h.grant,cb.commitFrame(h.grant,++seq,raw),seq);
                    if(facts.finish()!=null){finishSeq=seq;finish=facts.finish();}
                }else if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());if(data.length()>FRAME_BYTES)throw error("Provider帧超出预算",413);}
            }
            if(!data.isEmpty() || finishSeq<1)throw error("Provider流提前结束",502);
            checkFinishAck(h.grant,cb.finishStream(h.grant,seq,finishSeq,finish),seq,finishSeq,finish);
        }
    }
    static String line(InputStream input,int max) throws IOException {
        var bytes=new ByteArrayOutputStream();for(;;){int b=input.read();if(b<0)return bytes.size()==0?null:bytes.toString(java.nio.charset.StandardCharsets.UTF_8);if(b=='\n')break;bytes.write(b);if(bytes.size()>max)throw error("SSE行超出预算",413);}
        String result=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);return result.endsWith("\r")?result.substring(0,result.length()-1):result;
    }
    private void spark(Handle h,ProducerCallbacks cb,OkHttpClient client,String body) throws Exception {
        var result=new CompletableFuture<Void>();var seq=new AtomicLong();var bytes=new AtomicLong();
        h.socket=client.newWebSocket(new Request.Builder().url(ModelProviderWire.signedSpark(h.grant.privateMaterial(),Instant.now()).toString()).build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){try{assertActive(h,cb);if(!ws.send(body))throw error("Spark未接受发送",502);}catch(RuntimeException e){result.completeExceptionally(e);ws.cancel();}}
            @Override public void onMessage(WebSocket ws,String value){receive(ws,value);}
            @Override public void onMessage(WebSocket ws,ByteString value){if(value.size()>FRAME_BYTES){result.completeExceptionally(error("Spark帧超出预算",413));ws.cancel();}else receive(ws,value.utf8());}
            private void receive(WebSocket ws,String value){try{assertActive(h,cb);long size=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(size>FRAME_BYTES || bytes.addAndGet(size)>STREAM_BYTES)throw error("Spark流超出预算",413);
                    JSONObject raw=JSON.parseObject(value);var facts=ModelProviderWire.facts("xinghuo",raw);long current=seq.incrementAndGet();checkFrameAck(h.grant,cb.commitFrame(h.grant,current,raw),current);
                    if(facts.finish()!=null){checkFinishAck(h.grant,cb.finishStream(h.grant,current,current,facts.finish()),current,current,facts.finish());result.complete(null);ws.close(1000,"completed");}
                }catch(RuntimeException e){result.completeExceptionally(e);ws.cancel();}}
            @Override public void onFailure(WebSocket ws,Throwable t,Response response){result.completeExceptionally(error("Spark生产连接失败",502));}
            @Override public void onClosed(WebSocket ws,int code,String reason){if(!result.isDone())result.completeExceptionally(error("Spark流提前结束",502));}
        });
        long nanos=Duration.between(Instant.now(),h.grant.deadline()).toNanos();if(nanos<=0)throw error("Spark调用期限已过",403);result.get(nanos,TimeUnit.NANOSECONDS);
    }
    @Override public StopEvidence stop(String operation,String grant,String reason){
        var h=active.get(operation);if(h==null || !Objects.equals(h.grant.grantId(),grant))return new StopEvidence(operation,grant,false,null);
        h.cancelled.set(true);if(h.call!=null)h.call.cancel();if(h.socket!=null)h.socket.cancel();
        // 原供应商没有准确操作停止查询协议；本地关闭不能证明供应商已停止计费。
        return new StopEvidence(operation,grant,false,null);
    }
    @PreDestroy public void close(){active.values().forEach(h->stop(h.grant.operationId(),h.grant.grantId(),"shutdown"));executor.shutdownNow();}
}
