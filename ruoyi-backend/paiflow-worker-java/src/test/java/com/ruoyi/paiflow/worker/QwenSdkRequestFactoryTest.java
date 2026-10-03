package com.ruoyi.paiflow.worker;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.protocol.HttpMethod;
import com.ruoyi.paiflow.worker.service.QwenSdkRequestFactory;
import com.ruoyi.paiflow.worker.service.WorkerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QwenSdkRequestFactoryTest {
    @Test
    void exactSdkRequestUsesApprovedEndpointAndOriginalTextVoiceBodyWithoutTransport() throws Exception {
        String endpoint = "https://qwen.invalid/api/v1/services/aigc/multimodal-generation/generation";
        var param = MultiModalConversationParam.builder().apiKey("offline-test-key").model("qwen-tts")
                .text("中文语音😀").voice(AudioParameters.Voice.CHERRY).build();
        var request = QwenSdkRequestFactory.request(endpoint, param);
        assertEquals(endpoint, request.getHttpUrl());
        var http = request.getHttpRequest();
        assertEquals(endpoint, http.getUrl());
        assertEquals(HttpMethod.POST, http.getHttpMethod());
        var body = param.getHttpBody();
        assertEquals("qwen-tts", body.get("model").getAsString());
        assertEquals("中文语音😀", body.getAsJsonObject("input").get("text").getAsString());
        assertEquals("Cherry", body.getAsJsonObject("input").get("voice").getAsString());
        assertFalse(body.toString().contains("offline-test-key"));
    }

    @Test
    void missingCredentialsInUrlAndMalformedServiceAddressAreRejectedBeforeTransport() {
        var param = MultiModalConversationParam.builder().apiKey("offline-test-key").model("qwen-tts")
                .text("测试").voice(AudioParameters.Voice.CHERRY).build();
        assertThrows(WorkerException.class, () -> QwenSdkRequestFactory.request("", param));
        assertThrows(WorkerException.class, () -> QwenSdkRequestFactory.request("https://secret@qwen.invalid/api/v1/services/aigc/multimodal-generation/generation", param));
        assertThrows(WorkerException.class, () -> QwenSdkRequestFactory.request("https://qwen.invalid/api/v1/services/aigc/multimodal-generation/generation?secret=value", param));
        assertThrows(WorkerException.class, () -> QwenSdkRequestFactory.request("https://qwen.invalid/api/v1/../services/aigc/multimodal-generation/generation", param));
        assertThrows(WorkerException.class, () -> QwenSdkRequestFactory.request("https://qwen.invalid/unrelated", param));
    }
}
