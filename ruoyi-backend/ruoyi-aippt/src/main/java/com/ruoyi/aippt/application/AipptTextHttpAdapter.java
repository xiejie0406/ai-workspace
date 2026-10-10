package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.domain.AipptException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Service;

/** 一次有界文本请求。DNS结果在实际建连处校验并使用；不重试、重定向或继承环境代理。 */
@Service @AipptEnabled
public class AipptTextHttpAdapter {
    private static final ObjectMapper STRICT_JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final class RejectedResponse extends AipptException {
        private final JSONObject reportedUsage;
        private RejectedResponse(JSONObject usage,AipptException failure) {
            super(failure.status(),failure.errorCode(),failure.getMessage());
            reportedUsage=usage;
        }
        public JSONObject reportedUsage() { return reportedUsage; }
    }
    private final ExecutorService io=new ThreadPoolExecutor(0,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{
        Thread t=new Thread(r,"aippt-text-http");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());

    public JSONObject call(JSONObject route,String key,String system,String user,BooleanSupplier active) {
        String wire=route.getString("wireApi");
        URI base=AipptTextRouteService.baseUri(route.getString("baseUrl"));
        JSONObject body=JSONObject.of("model",route.getString("model"),"stream",false);
        if("responses".equals(wire)) {
            body.put("store",false);
            body.put("instructions",system);
            body.put("input",new JSONArray(java.util.List.of(JSONObject.of("role","user","content",user))));
            body.put("max_output_tokens",route.getIntValue("maxOutputTokens"));
            body.put("text",JSONObject.of("format",JSONObject.of("type","json_object")));
        } else {
            body.put("messages",new JSONArray(java.util.List.of(JSONObject.of("role","system","content",system),JSONObject.of("role","user","content",user))));
            body.put("response_format",JSONObject.of("type","json_object"));
            body.put(route.getString("outputTokenParameter"),route.getIntValue("maxOutputTokens"));
        }
        HttpPost request=new HttpPost(base+("responses".equals(wire)?"/responses":"/chat/completions"));
        request.setHeader("Authorization","Bearer "+key);
        request.setHeader("Accept","application/json");
        request.setEntity(new StringEntity(body.toJSONString(),ContentType.APPLICATION_JSON));
        if(!active.getAsBoolean())throw new AipptException(409,"TEXT_CALL_CANCELLED","任务已取消或租约失效，禁止文本调用");
        Future<byte[]> future;
        try { future=io.submit(()->send(base,route,request,active)); }
        catch(RejectedExecutionException busy) { throw new AipptException(503,"TEXT_OUTBOUND_BUSY","文本出口暂忙，已预占调用不会自动重送"); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(route.getIntValue("timeoutSeconds"));
        try {
            while(true) {
                if(!active.getAsBoolean())throw new AipptException(409,"TEXT_CALL_CANCELLED","任务已取消或租约失效，文本远端结果待核对");
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw transportFailure();
                try {
                    byte[] bytes=future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(500)),TimeUnit.NANOSECONDS);
                    return decode(route,bytes);
                } catch(TimeoutException waiting) { /* 等待期间持续核对租约与停用，不持有业务事务。 */ }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw transportFailure(); }
                catch(ExecutionException failure) { throw transportFailure(); }
            }
        } finally {
            // 关闭所属连接只代表本机停止等待，不能证明上游未生成或未计费。
            request.abort();future.cancel(true);
        }
    }

    private static byte[] send(URI base,JSONObject route,HttpPost request,BooleanSupplier active)throws Exception {
        int timeout=route.getIntValue("timeoutSeconds")*1000;
        RequestConfig config=RequestConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout)
            .setConnectionRequestTimeout(timeout).build();
        try(CloseableHttpClient client=HttpClients.custom().setDefaultRequestConfig(config)
            .disableAutomaticRetries().disableRedirectHandling().disableContentCompression().disableCookieManagement()
            .setDnsResolver(host->{
                if(!base.getHost().equals(host)||!route.getJSONArray("allowedHosts").contains(host))throw new java.net.UnknownHostException("出口不在白名单");
                InetAddress[] addresses=InetAddress.getAllByName(host);
                if(addresses.length==0)throw new java.net.UnknownHostException("出口解析为空");
                for(InetAddress address:addresses)if(!publicAddress(address))throw new java.net.UnknownHostException("出口解析到非公网地址");
                if(!active.getAsBoolean())throw new java.net.UnknownHostException("任务已失效");
                return addresses;
            }).build();CloseableHttpResponse response=client.execute(request)) {
            if(response.getStatusLine().getStatusCode()!=200||response.getEntity()==null)throw new java.io.IOException("模型HTTP结果无效");
            var encoding=response.getFirstHeader("Content-Encoding");
            if(encoding!=null&&!"identity".equalsIgnoreCase(encoding.getValue()))throw new java.io.IOException("文本响应编码不支持");
            var type=response.getFirstHeader("Content-Type");
            if(type==null||!type.getValue().toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))throw new java.io.IOException("文本响应类型无效");
            int limit=route.getIntValue("maxResponseBytes");
            if(response.getEntity().getContentLength()>limit)throw new java.io.IOException("文本响应超限");
            try(InputStream input=response.getEntity().getContent();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>limit)throw new java.io.IOException("文本响应超限");
                    output.write(buffer,0,count);
                }
                return output.toByteArray();
            }
        }
    }

    private static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] bytes=address.getAddress();
        if(bytes.length==4) {
            int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255;
            return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&b>=64&&b<=127)
                &&!(a==169&&b==254)&&!(a==172&&b>=16&&b<=31)&&!(a==192&&(b==0||b==168||(b==88&&c==99)))
                &&!(a==198&&(b==18||b==19||(b==51&&c==100)))&&!(a==203&&b==0&&c==113);
        }
        // IPv6仅接受全球单播2000::/3，拒绝ULA、映射、文档、过渡与特殊用途。
        if(bytes.length!=16||(bytes[0]&0xe0)!=0x20)return false;
        int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255,d=bytes[3]&255;
        if(a==0x20&&b==0x02)return false;
        if(a==0x20&&b==0x01&&((c==0x0d&&d==0xb8)||(c==0&&(d==0||d==2||(d>=0x10&&d<=0x2f)))))return false;
        return !(a==0x3f&&b==0xff&&(c&0xf0)==0);
    }

    static JSONObject decode(JSONObject route,byte[] bytes) {
        JSONObject reportedUsage=new JSONObject();
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(!STRICT_JSON.readTree(text).isObject())throw protocolFailure();
            Object raw=JSON.parse(text);
            if(!(raw instanceof JSONObject root))throw protocolFailure();
            if(root.get("usage")!=null&&!(root.get("usage") instanceof JSONObject))throw protocolFailure();
            reportedUsage=usage(root.getJSONObject("usage"),"chat_completions".equals(route.getString("wireApi")));
            if(root.get("error")!=null)throw protocolFailure();
            String content;
            JSONObject usage;
            if("responses".equals(route.getString("wireApi"))) {
                if(!"completed".equals(root.getString("status"))||root.get("incomplete_details")!=null)throw protocolFailure();
                if(!(root.get("output") instanceof JSONArray))throw protocolFailure();
                JSONArray output=root.getJSONArray("output");
                if(output==null||output.isEmpty())throw protocolFailure();
                StringBuilder result=new StringBuilder();int messages=0;
                for(Object item:output) {
                    if(!(item instanceof JSONObject part))throw protocolFailure();
                    if("reasoning".equals(part.getString("type")))continue;
                    if(!"message".equals(part.getString("type"))||!"assistant".equals(part.getString("role"))||!"completed".equals(part.getString("status"))||++messages>1)throw protocolFailure();
                    if(!(part.get("content") instanceof JSONArray))throw protocolFailure();
                    JSONArray pieces=part.getJSONArray("content");
                    if(pieces==null||pieces.isEmpty())throw protocolFailure();
                    for(Object chunk:pieces) {
                        if(!(chunk instanceof JSONObject p)||!"output_text".equals(p.getString("type"))||!(p.get("text") instanceof String value))throw protocolFailure();
                        result.append(value);
                    }
                }
                if(messages!=1)throw protocolFailure();
                content=result.toString();usage=reportedUsage;
            } else {
                if(!(root.get("choices") instanceof JSONArray))throw protocolFailure();
                JSONArray choices=root.getJSONArray("choices");
                if(choices==null||choices.size()!=1)throw protocolFailure();
                if(!(choices.get(0) instanceof JSONObject)||!(choices.getJSONObject(0).get("message") instanceof JSONObject))throw protocolFailure();
                JSONObject choice=choices.getJSONObject(0),message=choice.getJSONObject("message");
                if(!"stop".equals(choice.getString("finish_reason"))||message==null||!"assistant".equals(message.getString("role"))
                    ||message.get("refusal")!=null||message.get("function_call")!=null||(message.getJSONArray("tool_calls")!=null&&!message.getJSONArray("tool_calls").isEmpty())
                    ||!(message.get("content") instanceof String))throw protocolFailure();
                content=message.getString("content");usage=reportedUsage;
            }
            // 中心只接受完整JSON对象，详细业务结构仍由原Worker校验；不剥代码块或猜修补内容。
            if(content.isBlank()||!STRICT_JSON.readTree(content).isObject())throw protocolFailure();
            if(root.get("model")!=null&&(!(root.get("model") instanceof String)||!root.getString("model").equals(route.getString("model"))))throw protocolFailure();
            JSONObject response=JSONObject.of("content",content,"model",route.getString("model"),"wireApi",route.getString("wireApi"),
                "routeVersion",route.getString("routeVersion"),"usage_status",usage.size()==3?"available":usage.isEmpty()?"unavailable":"partial","usage",usage);
            if(content.codePointCount(0,content.length())>2*1024*1024
                ||response.toJSONString().getBytes(StandardCharsets.UTF_8).length>2*1024*1024-512)
                throw new AipptException(413,"TEXT_RESULT_BUDGET_EXCEEDED","文本结果超过Worker响应预算，已收到用量保留并须核对");
            return response;
        } catch(Exception failure) { throw new RejectedResponse(reportedUsage,failure instanceof AipptException safe?safe:protocolFailure()); }
    }

    private static JSONObject usage(JSONObject input,boolean chat) {
        JSONObject result=new JSONObject();if(input==null)return result;
        String[] source=chat?new String[]{"prompt_tokens","completion_tokens","total_tokens"}:new String[]{"input_tokens","output_tokens","total_tokens"};
        String[] target={"input_tokens","output_tokens","total_tokens"};
        for(int i=0;i<source.length;i++) {
            Object value=input.get(source[i]);if(value==null)continue;
            if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger))throw protocolFailure();
            long n;try { n=new java.math.BigInteger(value.toString()).longValueExact(); }catch(ArithmeticException invalid) { throw protocolFailure(); }
            if(n<0||n>100_000_000)throw protocolFailure();result.put(target[i],n);
        }
        if(result.size()==3&&result.getLongValue("total_tokens")!=result.getLongValue("input_tokens")+result.getLongValue("output_tokens"))throw protocolFailure();
        return result;
    }

    private static AipptException protocolFailure() { return new AipptException(502,"TEXT_PROTOCOL_INVALID","文本模型结果不是完整受支持JSON响应，远端用量须核对"); }
    private static AipptException transportFailure() { return new AipptException(502,"TEXT_RESULT_UNKNOWN","文本请求未取得可信终态，禁止自动重送并须核对远端结果"); }
    @PreDestroy public void close() { io.shutdownNow(); }
}
