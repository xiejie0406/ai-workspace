package com.ruoyi.interview.infrastructure.provider;

import com.ruoyi.interview.application.agent.port.InvocationContext;
import com.ruoyi.interview.application.agent.port.TextToSpeechPort;
import com.ruoyi.interview.domain.platform.ProviderConfigRef;
import com.ruoyi.interview.domain.platform.TimeBudget;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VolcengineTextToSpeechAdapterTest {
    @Test void completedMp3WithoutSubtitlesIsSuccessful() throws Exception {
        byte[] audio = AudioContainerDurationTest.mp3Frames(10);
        var result = synthesize(audio, true);
        var success = assertInstanceOf(TextToSpeechPort.Success.class, result);
        assertEquals(audio.length, success.bytes());
        assertEquals(240, success.durationMillis());
    }

    @Test void unfinishedProviderStreamIsStillRejected() throws Exception {
        var result = synthesize(AudioContainerDurationTest.mp3Frames(10), false);
        assertEquals("PROVIDER_BAD_RESPONSE", assertInstanceOf(TextToSpeechPort.Failure.class, result).failure().errorClass());
    }

    @SuppressWarnings("unchecked")
    private TextToSpeechPort.Result synthesize(byte[] audio, boolean completed) throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String body = "{\"code\":0,\"data\":\"" + Base64.getEncoder().encodeToString(audio)
                + "\",\"usage\":{\"text_words\":2}}\n" + (completed ? "{\"code\":20000000}\n" : "");
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new VolcengineTextToSpeechAdapter(http, new ObjectMapper(), "api-key", "test-only",
                null, null, "doubao-tts-2.0", "seed-tts-2.0", "test-voice",
                VolcengineTextToSpeechAdapter.DEFAULT_ENDPOINT, 24000);
        var context = mock(InvocationContext.class);
        when(context.timeBudget()).thenReturn(new TimeBudget(Duration.ofSeconds(10)));
        var bytes = new ByteArrayOutputStream();
        var result = adapter.synthesize(new TextToSpeechPort.Request("你好", "zh-CN", "test-voice", "mp3",
                new ProviderConfigRef("TTS", "VOLCENGINE", "doubao-tts-2.0", 1)),
                (sequence, chunk, end) -> bytes.writeBytes(chunk), context);
        if (result instanceof TextToSpeechPort.Success) assertArrayEquals(audio, bytes.toByteArray());
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return result;
    }
}
