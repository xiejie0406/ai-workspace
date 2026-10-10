package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** 固定地址白名单、TLS、无重定向、超时和响应预算；不记录供应商原报文。 */
@Component
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class ControlledHttp implements AutoCloseable {
    public static final class HttpStatusFailure extends IllegalStateException {private final int status;public HttpStatusFailure(int status){super("PROVIDER_UNAVAILABLE:HTTP "+status);this.status=status;}public int status(){return status;}}
    private final IntelligenceSettings settings;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.concurrent.ScheduledExecutorService watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"paismart-model-timeouts");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy public void close(){watchdog.shutdownNow();}
    public ControlledHttp(IntelligenceSettings settings) {this.settings=settings;}
    public URI validated(String url) {
        URI u=URI.create(url);
        if(u.getUserInfo()!=null||u.getFragment()!=null||u.getRawQuery()!=null||u.getHost()==null||!settings.hosts().contains(u.getHost()) || (!"https".equals(u.getScheme())&&!("http".equals(u.getScheme())&&settings.enabled("allow-http")))) throw new IllegalArgumentException("PROVIDER_UNAVAILABLE:地址未在批准白名单内");
        return u;
    }
    public Map<String,Object> json(String method,String url,String credential,Object body) {
        return json(method,url,credential,body,90);
    }
    public Map<String,Object> json(String method,String url,String credential,Object body,int timeoutSeconds) {
        if(timeoutSeconds<1||timeoutSeconds>90)throw new IllegalArgumentException("HTTP时间预算无效");
        settings.requireExternal();
        HttpRequest.Builder b=HttpRequest.newBuilder(validated(url)).timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type","application/json");
        if(credential!=null&&!credential.isBlank())b.header("Authorization",credential);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Jsons.write(body)));
        long deadline=System.nanoTime()+Duration.ofSeconds(timeoutSeconds).toNanos();
        try { HttpResponse<InputStream> response=client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()) {long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("供应商响应超过时间预算");var timeout=watchdog.schedule(()->{try{in.close();}catch(java.io.IOException ignored){}},remaining,java.util.concurrent.TimeUnit.NANOSECONDS);try{byte[] bytes=in.readNBytes(8*1024*1024+1);if(System.nanoTime()>deadline)throw new IllegalStateException("供应商响应超过时间预算");if(bytes.length>8*1024*1024)throw new IllegalStateException("供应商响应超过预算");if(response.statusCode()<200||response.statusCode()>=300)throw new HttpStatusFailure(response.statusCode());return bytes.length==0?Map.of():Jsons.map(new String(bytes,StandardCharsets.UTF_8));}finally{timeout.cancel(false);}}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("供应商调用已中断");}catch(java.io.IOException e){throw new IllegalStateException("PROVIDER_UNAVAILABLE:网络请求失败");}
    }
    public record SseFrame(String event,String data) {}
    /** 原 Chat 消费者保留 [DONE] 策略；Responses 使用独立语义终态。 */
    public void sse(String url,String key,Object body,Consumer<Map<String,Object>> onData,java.util.function.BooleanSupplier cancelled) {
        sseFrames(url,key,body,frame->{if("[DONE]".equals(frame.data().trim()))return true;onData.accept(Jsons.map(frame.data()));return false;},cancelled);
    }
    /** 按空行提交完整、有界 SSE 帧；回调返回 true 仅表示已核对协议终态。 */
    public void sseFrames(String url,String key,Object body,Function<SseFrame,Boolean> onFrame,java.util.function.BooleanSupplier cancelled) {
        settings.requireExternal();
        HttpRequest request=HttpRequest.newBuilder(validated(url)).timeout(Duration.ofSeconds(120)).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(Jsons.write(body))).build();
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        try {HttpResponse<InputStream> r=awaitStreamHeaders(request,cancelled,deadline);
            try(InputStream in=r.body();java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(in,StandardCharsets.UTF_8))) {
                if(r.statusCode()!=200)throw new HttpStatusFailure(r.statusCode());
                var stopped=new java.util.concurrent.atomic.AtomicBoolean();var guardFailure=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
                var timeout=watchdog.scheduleWithFixedDelay(()->{boolean close=false;try{boolean cancel=cancelled.getAsBoolean();stopped.set(cancel);close=cancel||System.nanoTime()>deadline;}catch(RuntimeException invalidated){guardFailure.compareAndSet(null,invalidated);close=true;}if(close)try{in.close();}catch(java.io.IOException ignored){}},500,500,java.util.concurrent.TimeUnit.MILLISECONDS);
                try {String line;int budget=0;String event="";StringBuilder data=new StringBuilder();boolean hasData=false;while((line=limitedLine(reader,524288))!=null){
                    if(guardFailure.get()!=null)throw guardFailure.get();if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("用户停止生成");
                    if(System.nanoTime()>deadline||(budget+=line.length()+1)>2*1024*1024)throw new IllegalStateException("生成时间或输出超过预算");
                    if(line.isEmpty()){
                        if(hasData){String payload=data.substring(0,data.length()-1);if(Boolean.TRUE.equals(onFrame.apply(new SseFrame(event,payload)))){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("用户停止生成");return;}}
                        event="";data.setLength(0);hasData=false;continue;
                    }
                    if(line.charAt(0)==':')continue;
                    int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon),value=colon<0?"":line.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
                    if("data".equals(field)){if((long)data.length()+value.length()+1>524288)throw new IllegalStateException("供应商流事件超过预算");data.append(value).append('\n');hasData=true;}
                    else if("event".equals(field)){if(value.length()>200)throw new IllegalStateException("供应商事件名称超过预算");event=value;}
                }
                    if(guardFailure.get()!=null)throw guardFailure.get();if(stopped.get())throw new java.util.concurrent.CancellationException("用户停止生成");if(System.nanoTime()>deadline)throw new IllegalStateException("生成超时");
                    throw new IllegalStateException("PROVIDER_UNAVAILABLE:流式连接在完成标记前关闭，结果待核对");
                }catch(java.io.IOException failure){if(guardFailure.get()!=null)throw guardFailure.get();if(stopped.get())throw new java.util.concurrent.CancellationException("用户停止生成");throw failure;}finally{timeout.cancel(false);}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("生成已中断");}catch(java.io.IOException e){throw new IllegalStateException("PROVIDER_UNAVAILABLE:流式连接中断");}
    }
    /** 响应头到达前也检查停止/资格/租约，取消真正的 HTTP future 而不等待完整模型响应。 */
    private HttpResponse<InputStream> awaitStreamHeaders(HttpRequest request,java.util.function.BooleanSupplier cancelled,long deadline)throws InterruptedException,java.io.IOException {
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("用户停止生成");
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());boolean accepted=false;
        try{while(true){
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("用户停止生成");if(System.nanoTime()>deadline)throw new IllegalStateException("生成响应超时");
            try{HttpResponse<InputStream> response=pending.get(250,java.util.concurrent.TimeUnit.MILLISECONDS);accepted=true;return response;}
            catch(java.util.concurrent.TimeoutException waiting){/* 有限轮询：停止和失租均无需等供应商先发正文。 */}
            catch(java.util.concurrent.ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof java.io.IOException io)throw io;if(cause instanceof RuntimeException runtime)throw runtime;throw new java.io.IOException("供应商连接失败",cause);}
        }}finally{if(!accepted){pending.cancel(true);pending.whenComplete((response,error)->{if(response!=null)try{response.body().close();}catch(java.io.IOException ignored){}});}}
    }
    private String limitedLine(java.io.Reader reader,int max)throws java.io.IOException{StringBuilder out=new StringBuilder();int ch;while((ch=reader.read())>=0){if(ch=='\n')return out.toString();if(ch!='\r')out.append((char)ch);if(out.length()>max)throw new IllegalStateException("供应商流事件超过预算");}return out.isEmpty()?null:out.toString();}
}
