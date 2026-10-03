package com.ruoyi.interview.controller.rest.common;

import com.ruoyi.interview.controller.rest.voice.QuestionSpeechController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuestionSpeechErrorResponseTest {
    @Test void audioRequestGetsJsonWithRealFailureStatus() throws Exception {
        var controller = mock(QuestionSpeechController.class);
        when(controller.speak(any(), any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "朗读暂不可用，请阅读题目后作答"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorHandler()).build();
        mvc.perform(post("/api/v1/interviews/{id}/turns/{turn}/question-speech", UUID.randomUUID(), UUID.randomUUID())
                        .accept("audio/ogg, audio/mpeg, audio/wav"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.errorCode").value("VOICE_SYNTHESIS_UNAVAILABLE"));
    }
}
