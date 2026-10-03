package com.ruoyi.interview.controller.rest.voice;

import com.ruoyi.interview.application.agent.port.InvocationContext;
import com.ruoyi.interview.application.agent.port.TextToSpeechPort;
import com.ruoyi.interview.application.interview.RecoverInterview;
import com.ruoyi.interview.application.voice.CheckVoicePreflight;
import com.ruoyi.interview.configuration.InterviewEnabled;
import com.ruoyi.interview.configuration.properties.ProviderProperties;
import com.ruoyi.interview.controller.rest.common.RequestContextFactory;
import com.ruoyi.interview.domain.interview.InterviewMode;
import com.ruoyi.interview.domain.interview.SessionState;
import com.ruoyi.interview.domain.interview.TurnState;
import com.ruoyi.interview.domain.platform.CostBudget;
import com.ruoyi.interview.domain.platform.Money;
import com.ruoyi.interview.domain.platform.ProviderConfigRef;
import com.ruoyi.interview.domain.platform.ResourceId;
import com.ruoyi.interview.domain.platform.TimeBudget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 朗读权威快照中的当前问题。用户主动请求；不申请麦克风，也不持久化音频正文。 */
@InterviewEnabled
@RestController
@ConditionalOnProperty(prefix = "interview.foundation-safety", name = "business-rest-endpoints-enabled",
        havingValue = "true")
@RequestMapping("/api/v1")
public class QuestionSpeechController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(QuestionSpeechController.class);
    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;

    private final RecoverInterview recoverInterview;
    private final CheckVoicePreflight preflight;
    private final TextToSpeechPort textToSpeech;
    private final RequestContextFactory contexts;
    private final ProviderProperties providers;

    public QuestionSpeechController(RecoverInterview recoverInterview, CheckVoicePreflight preflight,
                                    TextToSpeechPort textToSpeech, RequestContextFactory contexts,
                                    ProviderProperties providers) {
        this.recoverInterview = recoverInterview;
        this.preflight = preflight;
        this.textToSpeech = textToSpeech;
        this.contexts = contexts;
        this.providers = providers;
    }

    @PostMapping(path = "/interviews/{interviewId}/turns/{turnId}/question-speech")
    @PreAuthorize("@ss.hasPermi('interview:session:recover') && @ss.hasPermi('interview:voice:upload')")
    public ResponseEntity<byte[]> speak(@PathVariable UUID interviewId, @PathVariable UUID turnId,
                                        HttpServletRequest request) {
        var query = contexts.query(request);
        var session = recoverInterview.handle(new RecoverInterview.Query(ResourceId.of(interviewId), query));
        if (session.mode() != InterviewMode.VOICE || session.state() != SessionState.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "语音面试当前不能朗读");
        }
        var current = session.turns().stream()
                .filter(turn -> turn.state() == TurnState.QUESTION_COMMITTED)
                .reduce((left, right) -> right)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "当前没有待回答的问题"));
        if (!current.turnId().equals(ResourceId.of(turnId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "问题已经更新，请刷新后再朗读");
        }
        var readiness = preflight.handle(new CheckVoicePreflight.Query(ResourceId.of(interviewId),
                ResourceId.of(turnId), List.of("audio/wav"), query));
        if (!readiness.enabled() || readiness.consentRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "语音授权或服务尚未就绪");
        }
        String question = current.questionText().orElseThrow();
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "题目过长，暂时无法朗读");
        }
        var tts = providers.getVolcengineSpeech().getTts();
        String codec = fallback(tts.getAudioFormat(), "ogg_opus");
        var bytes = new ByteArrayOutputStream();
        var result = textToSpeech.synthesize(new TextToSpeechPort.Request(question,
                        fallback(tts.getLanguage(), "zh-CN"), tts.getVoice(), codec,
                        new ProviderConfigRef("TTS", "VOLCENGINE", tts.getModel(), 1)),
                (sequence, chunk, end) -> {
                    if (chunk.length > MAX_AUDIO_BYTES - bytes.size()) {
                        throw new IllegalStateException("TTS 音频超过大小限制");
                    }
                    bytes.writeBytes(chunk);
                }, new InvocationContext(query.principal().tenantId(), ResourceId.of(UUID.randomUUID()),
                        query.correlationId(), new TimeBudget(Duration.ofSeconds(45)),
                        new CostBudget(new Money(BigDecimal.ONE, "CNY"))));
        if (!(result instanceof TextToSpeechPort.Success) || bytes.size() == 0) {
            LOG.warn("Question speech failed correlationId={} codec={} bytes={} reasonCode={}", query.correlationId(),
                    codec, bytes.size(), result instanceof TextToSpeechPort.Failure failure
                            ? failure.failure().errorClass() : "EMPTY_AUDIO");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "朗读暂不可用，请阅读题目后作答");
        }
        String mediaType = codec.toLowerCase().contains("ogg") || codec.toLowerCase().contains("opus")
                ? "audio/ogg" : codec.toLowerCase().contains("wav") ? "audio/wav" : "audio/mpeg";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(mediaType)).body(bytes.toByteArray());
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
