package com.ruoyi.aipicture.worker;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;

@Component
@AiPictureEnabled
public class AiPictureWorkerClient {
    private final AiPictureProperties p;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AiPictureWorkerClient(AiPictureProperties p) { this.p=p; }
    public Map<String,Object> call(String operation,Map<String,Object> input) {
        if(!Set.of("capabilities","plan","prepare-selection","compute").contains(operation)) throw new IllegalArgumentException("未知 Worker 方法");
        if(p.getWorkerUrl().isBlank()||p.getServiceSecret().length()<32) throw new AiPictureException(503,"WORKER_NOT_CONFIGURED","图片 Worker 或服务凭据未配置");
        try {
            URI uri=URI.create(p.getWorkerUrl().replaceAll("/$","")+"/internal/v1/"+operation);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null) throw new IllegalStateException("非法 Worker 地址");
            String method=operation.equals("capabilities")?"GET":"POST";
            byte[] body=method.equals("GET")?new byte[0]:Json.write(input).getBytes(StandardCharsets.UTF_8);String timestamp=String.valueOf(System.currentTimeMillis()/1000),nonce=Json.id();
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(p.getWorkerTimeoutSeconds()))
                .header("Content-Type","application/json").header("X-Aipicture-Service",p.getServiceId())
                .header("X-Aipicture-Timestamp",timestamp).header("X-Aipicture-Nonce",nonce)
                .header("X-Aipicture-Signature",WorkerSignatures.sign(p.getServiceSecret(),method,uri.getRawPath(),timestamp,nonce,body))
                .method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var payload=Json.map(response.body());
            if(response.statusCode()>=400) throw new AiPictureException(response.statusCode()>=500?503:422,Json.string(payload.getOrDefault("error_code","WORKER_ERROR")),Json.string(payload.getOrDefault("message","图片计算服务拒绝请求")));
            return payload;
        } catch(AiPictureException e) { throw e; }
        catch(HttpTimeoutException e) { throw new AiPictureException(504,"WORKER_TIMEOUT","图片 Worker 响应超时",true,null); }
        catch(Exception e) { throw new AiPictureException(503,"WORKER_UNAVAILABLE","图片 Worker 暂不可用",true,null); }
    }
}
