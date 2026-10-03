package com.ruoyi.interview.controller.rest.voice;

import com.ruoyi.interview.application.agent.port.TextToSpeechPort;
import com.ruoyi.interview.application.interview.InterviewSessionSnapshot;
import com.ruoyi.interview.application.interview.InterviewTurnView;
import com.ruoyi.interview.application.interview.RecoverInterview;
import com.ruoyi.interview.application.shared.QueryContext;
import com.ruoyi.interview.application.voice.CheckVoicePreflight;
import com.ruoyi.interview.configuration.properties.ProviderProperties;
import com.ruoyi.interview.controller.rest.common.RequestContextFactory;
import com.ruoyi.interview.domain.interview.InterviewMode;
import com.ruoyi.interview.domain.interview.SessionState;
import com.ruoyi.interview.domain.interview.TurnState;
import com.ruoyi.interview.domain.platform.ResourceId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionSpeechControllerTest {
    private final RecoverInterview recover = mock(RecoverInterview.class);
    private final CheckVoicePreflight preflight = mock(CheckVoicePreflight.class);
    private final TextToSpeechPort tts = mock(TextToSpeechPort.class);
    private final RequestContextFactory contexts = mock(RequestContextFactory.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final QuestionSpeechController controller = new QuestionSpeechController(
            recover, preflight, tts, contexts, new ProviderProperties());

    @Test
    void staleTurnCannotChargeTheSpeechProvider() {
        UUID interviewId = UUID.randomUUID();
        UUID currentTurnId = UUID.randomUUID();
        arrangeCurrentQuestion(interviewId, currentTurnId);

        var error = assertThrows(ResponseStatusException.class,
                () -> controller.speak(interviewId, UUID.randomUUID(), request));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(preflight, tts);
    }

    @Test
    void missingConsentCannotChargeTheSpeechProvider() {
        UUID interviewId = UUID.randomUUID();
        UUID currentTurnId = UUID.randomUUID();
        arrangeCurrentQuestion(interviewId, currentTurnId);
        when(preflight.handle(any())).thenReturn(new CheckVoicePreflight.Result(
                false, true, List.of("audio/wav"), 120, 12_000_000, Optional.empty()));

        var error = assertThrows(ResponseStatusException.class,
                () -> controller.speak(interviewId, currentTurnId, request));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(tts);
    }

    private void arrangeCurrentQuestion(UUID interviewId, UUID turnId) {
        var context = mock(QueryContext.class);
        when(contexts.query(request)).thenReturn(context);
        var turn = mock(InterviewTurnView.class);
        when(turn.state()).thenReturn(TurnState.QUESTION_COMMITTED);
        when(turn.turnId()).thenReturn(ResourceId.of(turnId));
        var session = mock(InterviewSessionSnapshot.class);
        when(session.mode()).thenReturn(InterviewMode.VOICE);
        when(session.state()).thenReturn(SessionState.IN_PROGRESS);
        when(session.turns()).thenReturn(List.of(turn));
        when(recover.handle(any())).thenReturn(session);
    }
}
