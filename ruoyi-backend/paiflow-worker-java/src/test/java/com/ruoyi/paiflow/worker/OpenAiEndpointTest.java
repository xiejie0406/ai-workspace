package com.ruoyi.paiflow.worker;

import com.ruoyi.paiflow.worker.service.OpenAiEndpoint;
import com.ruoyi.paiflow.worker.service.WorkerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiEndpointTest {
    @Test
    void originalProviderCompatibilityAuthorizesTheActualFinalUrl() {
        assertEquals("https://provider.invalid/v1/chat/completions", OpenAiEndpoint.resolve("https://provider.invalid").requestUrl());
        assertEquals("https://provider.invalid/custom/chat", OpenAiEndpoint.resolve("https://provider.invalid/custom/chat").requestUrl());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                OpenAiEndpoint.resolve("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions").requestUrl());
        assertEquals("https://provider.invalid/dashscope.aliyuncs.com/chat",
                OpenAiEndpoint.resolve("https://provider.invalid/dashscope.aliyuncs.com/chat").requestUrl());
        assertThrows(WorkerException.class, () -> OpenAiEndpoint.resolve("https://key@provider.invalid/chat"));
        assertThrows(WorkerException.class, () -> OpenAiEndpoint.resolve("https://provider.invalid/chat?secret=value"));
    }
}
