package com.ruoyi.interview.controller.rest.voice;

import com.ruoyi.interview.configuration.InterviewEnabled;

import com.ruoyi.interview.controller.rest.common.HttpVersionPreconditions;
import com.ruoyi.interview.controller.rest.common.RequestContextFactory;
import com.ruoyi.interview.application.voice.CheckVoicePreflight;
import com.ruoyi.interview.application.voice.ConfirmTranscript;
import com.ruoyi.interview.application.voice.DeleteAudioArtifact;
import com.ruoyi.interview.application.voice.GetAudioArtifactStatus;
import com.ruoyi.interview.application.voice.GetTranscript;
import com.ruoyi.interview.application.voice.OpenVoiceSession;
import com.ruoyi.interview.application.interview.ProgressInterview;
import com.ruoyi.interview.application.interview.RecoverInterview;
import com.ruoyi.interview.domain.platform.AggregateVersion;
import com.ruoyi.interview.domain.platform.ResourceId;
import com.ruoyi.interview.controller.websocket.VoiceWebSocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Voice OpenAPI 的 REST 入站层；录音与后续题 TTS 通过受控 WebSocket 传输，首题朗读另走本人鉴权的 QuestionSpeechController。 */
@Validated
@InterviewEnabled
@RestController
@ConditionalOnProperty(prefix = "interview.foundation-safety", name = "business-rest-endpoints-enabled",
        havingValue = "true")
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class VoiceController {
    private final CheckVoicePreflight preflight;
    private final OpenVoiceSession openSession;
    private final GetTranscript getTranscript;
    private final ConfirmTranscript confirmTranscript;
    private final GetAudioArtifactStatus getArtifact;
    private final DeleteAudioArtifact deleteArtifact;
    private final RecoverInterview recoverInterview;
    private final ProgressInterview progressInterview;
    private final RequestContextFactory contexts;
    private final VoiceWebSocketHandler sockets;

    public VoiceController(CheckVoicePreflight preflight, OpenVoiceSession openSession,
                           GetTranscript getTranscript, ConfirmTranscript confirmTranscript,
                           GetAudioArtifactStatus getArtifact, DeleteAudioArtifact deleteArtifact,
                           RecoverInterview recoverInterview, ProgressInterview progressInterview,
                           RequestContextFactory contexts, VoiceWebSocketHandler sockets) {
        this.preflight = preflight;
        this.openSession = openSession;
        this.getTranscript = getTranscript;
        this.confirmTranscript = confirmTranscript;
        this.getArtifact = getArtifact;
        this.deleteArtifact = deleteArtifact;
        this.recoverInterview = recoverInterview;
        this.progressInterview = progressInterview;
        this.contexts = contexts;
        this.sockets = sockets;
    }

    @PostMapping(path = "/interviews/{interviewId}/voice-preflight",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@ss.hasPermi('interview:session:start') && @ss.hasPermi('interview:voice:upload')")
    public PreflightResponse preflight(@PathVariable UUID interviewId,
                                       @Valid @RequestBody PreflightRequest body,
                                       HttpServletRequest request) {
        var result = preflight.handle(new CheckVoicePreflight.Query(ResourceId.of(interviewId),
                ResourceId.of(body.turnId()), body.codecCandidates(), contexts.query(request)));
        return new PreflightResponse(result.enabled(), result.consentRequired(),
                result.supportedCodecs(), result.maximumDurationSeconds(), result.maximumBytes(),
                result.unavailableReasonCode().orElse(null));
    }

    @PostMapping(path = "/interviews/{interviewId}/voice-sessions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@ss.hasPermi('interview:session:start') && @ss.hasPermi('interview:voice:upload')")
    public ResponseEntity<VoiceSessionResponse> open(@PathVariable UUID interviewId,
                                                     @Valid @RequestBody OpenSessionRequest body,
                                                     HttpServletRequest request) {
        var result = openSession.handle(new OpenVoiceSession.Command(ResourceId.of(interviewId),
                ResourceId.of(body.turnId()), body.codec(),
                new AggregateVersion(body.expectedSessionVersion()), contexts.operation(request)));
        var handle = result.handle();
        var flow = result.flowControl();
        return ResponseEntity.status(201).body(new VoiceSessionResponse(
                handle.voiceSessionId().value(), result.executionId().value(),
                result.inputArtifactId().value(), handle.websocketPath(), handle.socketTicket(),
                handle.expiresAt().toString(), handle.codec(), flow.protocolVersion(),
                result.socketGeneration(), result.initialServerSequence(),
                flow.maximumInFlightChunks(), flow.maximumChunkBytes(),
                flow.maximumBufferedDurationMillis(), flow.maximumDurationSeconds(), flow.maximumBytes()));
    }

    @GetMapping("/transcripts/{transcriptId}")
    @PreAuthorize("@ss.hasPermi('interview:session:recover')")
    public ResponseEntity<TranscriptResponse> transcript(@PathVariable UUID transcriptId,
                                                         HttpServletRequest request) {
        var view = getTranscript.handle(new GetTranscript.Query(ResourceId.of(transcriptId),
                contexts.query(request)));
        return ResponseEntity.ok().eTag(HttpVersionPreconditions.etag(view.version()))
                .body(TranscriptResponse.from(view));
    }

    @PostMapping(path = "/transcripts/{transcriptId}/commands/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@ss.hasPermi('interview:voice:confirm') && @ss.hasPermi('interview:session:submit')")
    public ResponseEntity<ConfirmedTranscriptResponse> confirm(
            @PathVariable UUID transcriptId,
            @RequestHeader("If-Match") @Pattern(regexp = "^\"v[0-9]+\"$") String ifMatch,
            @Valid @RequestBody ConfirmTranscriptRequest body,
            HttpServletRequest request) {
        var transcriptBeforeConfirm = getTranscript.handle(new GetTranscript.Query(ResourceId.of(transcriptId),
                contexts.query(request)));
        var result = confirmTranscript.handle(new ConfirmTranscript.Command(ResourceId.of(transcriptId),
                ResourceId.of(body.transcriptVersionId()), Optional.ofNullable(body.correctedText()),
                body.lowConfidenceAcknowledged(), HttpVersionPreconditions.requireIfMatch(ifMatch),
                contexts.operation(request)));
        // confirm 的幂等回放只返回原有结果；只有新增转写聚合版本时才推进面试和 TTS，防止同一 Idempotency-Key 重复触发副作用。
        boolean newlyConfirmed = result.version().value() > transcriptBeforeConfirm.version().value();
        if (newlyConfirmed) {
            var recovered = recoverInterview.handle(new RecoverInterview.Query(
                    transcriptBeforeConfirm.sessionId(), contexts.query(request)));
            var progressed = progressInterview.handle(new ProgressInterview.Command(
                    transcriptBeforeConfirm.sessionId(), recovered.version(), contexts.operation(request)));
            var nextQuestion = progressed.turns().stream().reduce((left, right) -> right)
                    .filter(turn -> !turn.turnId().equals(transcriptBeforeConfirm.turnId()))
                    .flatMap(turn -> turn.questionText().map(text -> new NextQuestion(turn.turnId(), text)));
            if (nextQuestion.isPresent()) {
                sockets.synthesizeNextQuestion(contexts.requiredPrincipal().tenantId(),
                        transcriptBeforeConfirm.sessionId(), transcriptBeforeConfirm.turnId(),
                        nextQuestion.orElseThrow().text());
            } else {
                sockets.finishWithoutTts(contexts.requiredPrincipal().tenantId(),
                        transcriptBeforeConfirm.sessionId(), transcriptBeforeConfirm.turnId());
            }
        }
        var operation = result.nextStepOperation();
        return ResponseEntity.status(201).eTag(HttpVersionPreconditions.etag(result.version()))
                .body(new ConfirmedTranscriptResponse(result.transcriptId().value(),
                        result.confirmedTranscriptVersionId().value(), result.answerVersionId().value(),
                        new OperationResponse(operation.operationId().value(),
                                operation.jobId().map(ResourceId::value).orElse(null),
                                operation.statusPath()), result.version().value()));
    }

    @GetMapping("/audio-artifacts/{artifactId}")
    @PreAuthorize("@ss.hasPermi('interview:session:recover')")
    public ResponseEntity<ArtifactResponse> artifact(@PathVariable UUID artifactId,
                                                     HttpServletRequest request) {
        var view = getArtifact.handle(new GetAudioArtifactStatus.Query(ResourceId.of(artifactId),
                contexts.query(request)));
        return ResponseEntity.ok().eTag(HttpVersionPreconditions.etag(view.version()))
                .body(ArtifactResponse.from(view));
    }

    @PostMapping("/audio-artifacts/{artifactId}/commands/delete")
    @PreAuthorize("@ss.hasPermi('interview:voice:upload')")
    public ResponseEntity<ArtifactResponse> delete(
            @PathVariable UUID artifactId,
            @RequestHeader("If-Match") @Pattern(regexp = "^\"v[0-9]+\"$") String ifMatch,
            HttpServletRequest request) {
        var context = contexts.operation(request);
        var principal = context.requirePrincipal();
        // 删除前复用状态查询的 session owner 校验，不能仅凭 tenant 与 artifact ID 删除。
        getArtifact.handle(new GetAudioArtifactStatus.Query(ResourceId.of(artifactId), contexts.query(request)));
        deleteArtifact.handle(new DeleteAudioArtifact.Command(principal.tenantId(),
                ResourceId.of(artifactId), HttpVersionPreconditions.requireIfMatch(ifMatch),
                context.correlationId(), context.requestedAt()));
        var current = getArtifact.handle(new GetAudioArtifactStatus.Query(ResourceId.of(artifactId),
                contexts.query(request)));
        return ResponseEntity.accepted().eTag(HttpVersionPreconditions.etag(current.version()))
                .body(ArtifactResponse.from(current));
    }

    public record PreflightRequest(@NotNull UUID turnId,
                                   @NotEmpty List<@NotBlank @Size(max = 128) String> codecCandidates) { }
    public record OpenSessionRequest(@NotNull UUID turnId, @NotBlank @Size(max = 128) String codec,
                                     @PositiveOrZero long expectedSessionVersion) { }
    public record ConfirmTranscriptRequest(@NotNull UUID transcriptVersionId,
                                           @Size(max = 30_000) String correctedText,
                                           boolean lowConfidenceAcknowledged) { }
    public record PreflightResponse(boolean enabled, boolean consentRequired,
                                    List<String> supportedCodecs, int maxDurationSeconds,
                                    long maxBytes, String unavailableReasonCode) { }
    public record VoiceSessionResponse(String voiceSessionId, String voiceExecutionId,
                                       String inputArtifactId, String websocketPath,
                                       String socketTicket, String expiresAt, String codec,
                                       int protocolVersion, long socketGeneration,
                                       long initialServerSequence, int maxInFlightChunks,
                                       long maxChunkBytes, long maxBufferedDurationMs,
                                       int maxDurationSeconds, long maxBytes) { }
    public record ConfidenceResponse(int startInclusive, int endExclusive,
                                     java.math.BigDecimal confidence) { }
    public record TranscriptVersionResponse(String id, int versionNo, String source, String text,
                                            String language, String offsetUnit,
                                            List<ConfidenceResponse> lowConfidenceSpans,
                                            String createdAt) { }
    public record TranscriptResponse(String id, String state, String sessionId, String turnId,
                                     String audioArtifactId, TranscriptVersionResponse latestVersion,
                                     String confirmedVersionId, long version) {
        static TranscriptResponse from(GetTranscript.View view) {
            var latest = view.latestVersion().map(item -> new TranscriptVersionResponse(item.id().value(),
                    item.versionNo(), item.source().name(), item.text(), item.language(), item.offsetUnit(),
                    item.lowConfidenceSpans().stream().map(span -> new ConfidenceResponse(
                            span.startInclusive(), span.endExclusive(), span.confidence())).toList(),
                    item.createdAt().toString())).orElse(null);
            return new TranscriptResponse(view.id().value(), view.state().name(), view.sessionId().value(),
                    view.turnId().value(), view.audioArtifactId().value(), latest,
                    view.confirmedVersionId().map(ResourceId::value).orElse(null), view.version().value());
        }
    }
    public record OperationResponse(String operationId, String jobId, String statusUrl) { }
    public record ConfirmedTranscriptResponse(String transcriptId,
                                              String confirmedTranscriptVersionId,
                                              String answerVersionId,
                                              OperationResponse nextStepOperation, long version) { }
    public record ArtifactResponse(String id, String state, String purpose, String expiresAt,
                                   String codec, Long bytes, Long durationMillis, String failureCode,
                                   String deleteStatus, long version) {
        static ArtifactResponse from(GetAudioArtifactStatus.View view) {
            return new ArtifactResponse(view.id().value(), view.state().name(), view.purpose().name(),
                    view.expiresAt().toString(), view.codec().orElse(null), view.bytes().orElse(null),
                    view.durationMillis().orElse(null), view.failureCode().orElse(null),
                    view.deleteStatus().orElse(null), view.version().value());
        }
    }
    private record NextQuestion(ResourceId turnId, String text) { }
}

