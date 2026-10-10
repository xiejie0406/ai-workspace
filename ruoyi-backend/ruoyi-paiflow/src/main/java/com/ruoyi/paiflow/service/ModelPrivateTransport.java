package com.ruoyi.paiflow.service;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 正式私有出口：准确URI、固定DNS结果、TLS主机校验；无代理、跳转和自动重发。 */
@Component
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelPrivateTransport {
    private static final MediaType JSON_TYPE=MediaType.get("application/json; charset=utf-8");
    public OkHttpClient client(URI target,int seconds,List<String> allowedPrivateAddresses){
        if(target.getHost()==null || target.getUserInfo()!=null || target.getFragment()!=null
            || !Set.of("https","wss","http").contains(target.getScheme()) || seconds<1 || seconds>1800)throw error("私有模型出口目标无效",503);
        // HTTP只允许显式固定部署地址；普通模型始终走TLS。
        if("http".equals(target.getScheme()) && allowedPrivateAddresses.isEmpty())throw error("私有部署HTTP出口未批准",403);
        final List<InetAddress> pinned;
        try{pinned=List.of(InetAddress.getAllByName(target.getHost()));}catch(UnknownHostException e){throw error("模型固定出口无法解析",503);}
        if(pinned.isEmpty())throw error("模型固定出口无法解析",503);
        for(var address:pinned){boolean explicit=allowedPrivateAddresses.contains(address.getHostAddress());
            if(!explicit && forbidden(address) || "http".equals(target.getScheme()) && !explicit)throw error("模型DNS地址不在批准出口范围",403);
        }
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).dns(host->{if(!host.equalsIgnoreCase(target.getHost()))throw new UnknownHostException("固定目标不匹配");return pinned;})
            .connectTimeout(Duration.ofSeconds(Math.min(seconds,20))).readTimeout(Duration.ofSeconds(seconds))
            .writeTimeout(Duration.ofSeconds(Math.min(seconds,20))).callTimeout(Duration.ofSeconds(seconds))
            .connectionPool(new ConnectionPool(0,1,java.util.concurrent.TimeUnit.SECONDS)).build();
    }
    static boolean forbidden(InetAddress a){
        byte[] b=a.getAddress();if(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())return true;
        if(b.length==4){int x=b[0]&255,y=b[1]&255;return x==0 || x>=224 || x==100&&y>=64&&y<=127 || x==169&&y==254 || x==198&&(y==18||y==19);}
        return b.length!=16 || (b[0]&254)==252; // IPv6 ULA。映射地址由JDK归一化为IPv4。
    }
    public Response request(OkHttpClient client,URI target,String method,String key,String body) throws IOException {
        var builder=new Request.Builder().url(target.toString()).header("Accept","application/json");
        if(key!=null && !key.isBlank()){if(key.chars().anyMatch(Character::isISOControl))throw error("模型私有鉴权材料无效",503);builder.header("Authorization","Bearer "+key);}
        builder.method(method,Set.of("GET","HEAD").contains(method)?null:RequestBody.create(body==null?"":body,JSON_TYPE));
        return client.newCall(builder.build()).execute();
    }
    public String bounded(Response response,int maxBytes) throws IOException {
        if(response.body()==null)throw error("供应商未返回响应正文",502);
        try(var input=response.body().byteStream()){byte[] bytes=input.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw error("供应商响应超出预算",413);return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }
    static void close(OkHttpClient client){if(client!=null){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}}
}
