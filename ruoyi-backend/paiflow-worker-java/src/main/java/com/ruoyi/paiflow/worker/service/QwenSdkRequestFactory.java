package com.ruoyi.paiflow.worker.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.common.Function;
import com.alibaba.dashscope.common.OutputMode;
import com.alibaba.dashscope.common.Task;
import com.alibaba.dashscope.common.TaskGroup;
import com.alibaba.dashscope.protocol.ApiServiceOption;
import com.alibaba.dashscope.protocol.HalfDuplexRequest;
import com.alibaba.dashscope.protocol.HttpMethod;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.protocol.StreamingMode;

import java.net.URI;
import java.util.Set;

/** 原 SDK 的参数、请求体和结果转换保留，只把实际请求地址交给中心边界核对。 */
public final class QwenSdkRequestFactory {
    private static final String SERVICE_PATH = "/services/aigc/multimodal-generation/generation";

    private QwenSdkRequestFactory() { }

    public static HalfDuplexRequest request(String endpoint, MultiModalConversationParam param) {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (RuntimeException error) { throw new WorkerException("RESOURCE_UNAVAILABLE", "中心未提供有效的Qwen地址"); }
        if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().endsWith(SERVICE_PATH) || !uri.normalize().equals(uri)) {
            throw new WorkerException("RESOURCE_UNAVAILABLE", "Qwen地址必须是中心批准的完整SDK服务地址");
        }
        String base = endpoint.substring(0, endpoint.length() - SERVICE_PATH.length());
        ApiServiceOption option = ApiServiceOption.builder().protocol(Protocol.HTTP).httpMethod(HttpMethod.POST)
                .streamingMode(StreamingMode.NONE).outputMode(OutputMode.ACCUMULATE)
                .taskGroup(TaskGroup.AIGC.getValue()).task(Task.MULTIMODAL_GENERATION.getValue())
                .function(Function.GENERATION.getValue()).baseHttpUrl(base).build();
        try { param.validate(); }
        catch (com.alibaba.dashscope.exception.InputRequiredException error) {
            throw new WorkerException("DSL_INVALID", "Qwen语音参数缺少必要输入");
        }
        HalfDuplexRequest request = new HalfDuplexRequest(param, option);
        if (!endpoint.equals(request.getHttpUrl())) {
            throw new WorkerException("RESOURCE_UNAVAILABLE", "SDK真实请求地址与中心资源不一致");
        }
        return request;
    }
}
