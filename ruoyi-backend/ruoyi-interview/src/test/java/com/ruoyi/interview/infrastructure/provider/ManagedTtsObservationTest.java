package com.ruoyi.interview.infrastructure.provider;

import com.ruoyi.system.secret.ManagedSecretService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ManagedTtsObservationTest {
    @Test void unavailableCredentialsRetainFailureInsteadOfReturningNotChecked() {
        var secrets = mock(ManagedSecretService.class);
        when(secrets.resolve(anyString(), anyString())).thenThrow(new IllegalStateException("test-only"));
        var http = mock(HttpClient.class);
        var adapter = new ManagedInterviewProviderAdapters.Tts(secrets, http, new ObjectMapper(),
                "doubao-tts-2.0", "seed-tts-2.0", "test-voice", VolcengineTextToSpeechAdapter.DEFAULT_ENDPOINT, 24000);
        adapter.synthesize(null, (sequence, bytes, end) -> fail("不应产生音频"), null);
        assertEquals("FAIL", adapter.lastObservation().get("status"));
        assertEquals("TTS_NOT_CONFIGURED", adapter.lastObservation().get("reasonCode"));
        assertDoesNotThrow(() -> Instant.parse(adapter.lastObservation().get("observedAt")));
        verifyNoInteractions(http);
    }
}
