package com.ruoyi.paiflow.worker.service;

import java.net.URI;
import java.util.Set;

/** 沿用原供应商路径兼容规则，并核对最终实际地址。 */
public record OpenAiEndpoint(String baseUrl, String completionsPath, String requestUrl) {
    public static OpenAiEndpoint resolve(String configured) {
        URI uri;
        try { uri = URI.create(configured); }
        catch (RuntimeException error) { throw new WorkerException("RESOURCE_UNAVAILABLE", "模型地址无效"); }
        if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.normalize().equals(uri)) {
            throw new WorkerException("RESOURCE_UNAVAILABLE", "模型地址必须是中心批准的规范HTTP地址");
        }
        if ("dashscope.aliyuncs.com".equalsIgnoreCase(uri.getHost())) {
            String base = "https://dashscope.aliyuncs.com/compatible-mode";
            String path = "/v1/chat/completions";
            return new OpenAiEndpoint(base, path, base + path);
        }
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/v1/chat/completions";
        return new OpenAiEndpoint(base, path, base + path);
    }
}
