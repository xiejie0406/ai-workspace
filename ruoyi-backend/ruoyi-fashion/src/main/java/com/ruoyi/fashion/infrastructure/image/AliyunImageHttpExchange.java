package com.ruoyi.fashion.infrastructure.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 一次HTTP交换，不重试、不跟随重定向；响应体和整个请求均有上限。 */
@FunctionalInterface
interface AliyunImageHttpExchange {
    record Response(int status, byte[] body) {}
    Response send(String method, URI uri, Map<String,String> headers, byte[] body, int maximumBytes, Duration timeout);

    static AliyunImageHttpExchange jdk() {
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (method,uri,headers,body,maximum,timeout)->{
            var builder=HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(builder::header);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
            var pending=client.sendAsync(builder.build(),info->new LimitedBody(maximum));
            try {
                var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(),response.body());
            } catch (Exception failure) {
                pending.cancel(true);
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                // 不传播URL、签名或Authorization；提交是否受理由业务保留unknown。
                throw new com.ruoyi.fashion.application.agent.run.FashionRuntimeException("PROVIDER_TRANSPORT_UNKNOWN","试衣服务通信未确认，请按已保存的任务状态查询或人工对账",false);
            }
        };
    }

    final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int maximum){this.maximum=maximum;}
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){int remaining=buffer.remaining();if(remaining>maximum-output.size()){subscription.cancel();result.completeExceptionally(new IOException("响应超过大小限制"));return;}
                byte[] bytes=new byte[remaining];buffer.get(bytes);output.writeBytes(bytes);}
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(output.toByteArray());}
    }
}
