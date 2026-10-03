package com.iflytek.astron.workflow.engine.util;

import com.ruoyi.paiflow.worker.domain.WorkerProtocol.ArtifactGrant;
import com.ruoyi.paiflow.worker.service.ExecutionScope;
import com.ruoyi.paiflow.worker.service.EffectReceipt;
import com.ruoyi.paiflow.worker.service.WorkerException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Component
public class S3ClientUtil {
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public String uploadObject(String objectKey, String contentType, byte[] data) {
        if (data == null || data.length > MAX_ARTIFACT_BYTES || objectKey == null || objectKey.isBlank()
                || objectKey.contains("..") || objectKey.startsWith("/") || contentType == null || contentType.isBlank()) {
            throw new WorkerException("ARTIFACT_INVALID", "文件大小、名称或类型超出执行范围");
        }
        ExecutionScope scope = ExecutionScope.current();
        ArtifactGrant grant = scope.center().prepareArtifact(scope.lease(), objectKey, contentType, data.length);
        if (grant == null || grant.artifactId() == null || grant.artifactId().isBlank() || grant.readUrl() == null) {
            throw new WorkerException("RESOURCE_UNAVAILABLE", "中心未分配临时文件写入授权");
        }
        try {
            URI upload = URI.create(grant.uploadUrl());
            String grantId = scope.authorize("object.upload", upload.toString());
            HttpRequest.Builder request = HttpRequest.newBuilder(upload).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(data));
            if (grant.headers() != null) grant.headers().forEach(request::header);
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WorkerException("ARTIFACT_UPLOAD_FAILED", "临时文件上传未被对象服务确认");
            }
            scope.check();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            scope.center().commitArtifact(scope.lease(), grant.artifactId(), hash, data.length);
            scope.confirmedEffect(grantId, Map.of("artifactId", grant.artifactId(), "sha256", hash, "byteLength", data.length));
            return grant.readUrl();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException("EFFECT_UNKNOWN", "上传被中断，须中心核对对象", e);
        } catch (WorkerException e) { throw e; }
        catch (Exception e) { throw new WorkerException("ARTIFACT_UPLOAD_FAILED", "临时文件写入失败", e); }
    }

    public String uploadObject(String objectKey, String contentType, InputStream stream) {
        try {
            byte[] data = stream.readNBytes(MAX_ARTIFACT_BYTES + 1);
            return uploadObject(objectKey, contentType, data);
        } catch (Exception e) { throw new WorkerException("ARTIFACT_UPLOAD_FAILED", "无法读取临时文件", e); }
    }

    public String uploadObject(String bucket, String objectKey, String contentType, byte[] data) {
        throw new WorkerException("TOOL_POLICY_DENIED", "Worker不能自行指定正式存储桶");
    }
}
