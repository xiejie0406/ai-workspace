package com.ruoyi.interview.infrastructure.provider;

import com.ruoyi.interview.application.agent.port.ChatModelPort;
import com.ruoyi.interview.application.agent.port.ChatModelRequest;
import com.ruoyi.interview.application.agent.port.ChatModelResult;
import com.ruoyi.interview.application.agent.port.InvocationContext;
import com.ruoyi.interview.application.agent.port.SpeechToTextPort;
import com.ruoyi.interview.application.agent.port.TextToSpeechPort;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 面试 Provider 的运行时唯一密钥来源；数据库失败时只返回不可用，不回退环境变量。 */
public final class ManagedInterviewProviderAdapters {
    public static final String DEEPSEEK = "ai.interview.deepseek";
    public static final String VOLCENGINE_SPEECH = "ai.interview.volcengine.speech";

    private ManagedInterviewProviderAdapters() { }

    public static final class DeepSeek implements ChatModelPort, ProviderAdapter {
        private final ManagedSecretService secrets;
        private final HttpClient http;
        private final ObjectMapper json;
        private final String baseUrl, interviewModel, evaluationModel, thinkingMode;

        public DeepSeek(ManagedSecretService secrets, HttpClient http, ObjectMapper json,
                        String baseUrl, String interviewModel, String evaluationModel, String thinkingMode) {
            this.secrets = secrets;
            this.http = http;
            this.json = json;
            this.baseUrl = baseUrl;
            this.interviewModel = interviewModel;
            this.evaluationModel = evaluationModel;
            this.thinkingMode = thinkingMode;
        }

        private DeepSeekChatModelAdapter current() {
            return new DeepSeekChatModelAdapter(http, json, secrets.require("AI", DEEPSEEK),
                    baseUrl, interviewModel, evaluationModel, thinkingMode);
        }

        @Override public ChatModelResult execute(ChatModelRequest request, InvocationContext context) {
            DeepSeekChatModelAdapter adapter;
            try { adapter = current(); }
            catch (RuntimeException unavailable) { return new DisabledChatModelAdapter().execute(request, context); }
            return adapter.execute(request, context);
        }
        @Override public String adapterId() { return DeepSeekChatModelAdapter.ADAPTER_ID; }
        @Override public boolean available() { try { return current().available(); } catch (RuntimeException unavailable) { return false; } }
        @Override public String reasonCode() { return available() ? "AVAILABLE" : DeepSeekChatModelAdapter.NOT_CONFIGURED; }
    }

    private static String[] speech(ManagedSecretService secrets, ObjectMapper json) {
        ManagedSecretService.Resolved resolved = secrets.resolve("AI", VOLCENGINE_SPEECH);
        ManagedSecretService.Metadata metadata = resolved.metadata();
        String value = resolved.value();
        if ("api-key".equals(metadata.authMode())) return new String[] {"api-key", value, null, null};
        if (!"access-token".equals(metadata.authMode())) throw new IllegalStateException("火山鉴权模式无效");
        try {
            JsonNode root = json.readTree(value);
            String appId = root.path("appId").asText();
            String token = root.path("accessToken").asText();
            if (appId.isBlank() || token.isBlank()) throw new IllegalStateException("火山凭据组不完整");
            return new String[] {"access-token", null, appId, token};
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("火山凭据组格式无效");
        }
    }

    public static final class Asr implements SpeechToTextPort, ProviderAdapter {
        private final ManagedSecretService secrets;
        private final HttpClient http;
        private final ObjectMapper json;
        private final AudioArtifactSource audio;
        private final String model, resourceId;
        private final URI endpoint;

        public Asr(ManagedSecretService secrets, HttpClient http, ObjectMapper json,
                   AudioArtifactSource audio, String model, String resourceId, URI endpoint) {
            this.secrets = secrets; this.http = http; this.json = json; this.audio = audio;
            this.model = model; this.resourceId = resourceId; this.endpoint = endpoint;
        }

        private VolcengineSpeechToTextAdapter current() {
            String[] c = speech(secrets, json);
            return new VolcengineSpeechToTextAdapter(http, json, audio, c[0], c[1], c[2], c[3],
                    model, resourceId, endpoint);
        }

        @Override public Result transcribe(Request request, InvocationContext context) {
            VolcengineSpeechToTextAdapter adapter;
            try { adapter = current(); }
            catch (RuntimeException unavailable) { return new DisabledSpeechToTextAdapter().transcribe(request, context); }
            return adapter.transcribe(request, context);
        }
        @Override public String adapterId() { return VolcengineSpeechToTextAdapter.ADAPTER_ID; }
        @Override public boolean available() { try { return current().available(); } catch (RuntimeException unavailable) { return false; } }
        @Override public String reasonCode() { return available() ? "AVAILABLE" : VolcengineSpeechToTextAdapter.NOT_CONFIGURED; }
    }

    public static final class Tts implements TextToSpeechPort, ProviderAdapter {
        private volatile java.util.Map<String, String> observation = java.util.Map.of("status", "NOT_CHECKED");
        private final ManagedSecretService secrets;
        private final HttpClient http;
        private final ObjectMapper json;
        private final String model, resourceId, voice;
        private final URI endpoint;
        private final int sampleRate;

        public Tts(ManagedSecretService secrets, HttpClient http, ObjectMapper json,
                   String model, String resourceId, String voice, URI endpoint, int sampleRate) {
            this.secrets = secrets; this.http = http; this.json = json;
            this.model = model; this.resourceId = resourceId; this.voice = voice;
            this.endpoint = endpoint; this.sampleRate = sampleRate;
        }

        private VolcengineTextToSpeechAdapter current() {
            String[] c = speech(secrets, json);
            return new VolcengineTextToSpeechAdapter(http, json, c[0], c[1], c[2], c[3],
                    model, resourceId, voice, endpoint, sampleRate);
        }

        @Override public Result synthesize(Request request, AudioSink sink, InvocationContext context) {
            TextToSpeechPort adapter;
            try { adapter = current(); }
            catch (RuntimeException unavailable) { adapter = new DisabledTextToSpeechAdapter(); }
            Result result = adapter.synthesize(request, sink, context);
            observation = java.util.Map.of("status", result instanceof Success ? "PASS" : "FAIL",
                    "observedAt", java.time.Instant.now().toString(), "reasonCode",
                    result instanceof Failure failure ? failure.failure().errorClass() : "AVAILABLE");
            return result;
        }
        @Override public java.util.Map<String, String> lastObservation() { return observation; }
        @Override public String adapterId() { return VolcengineTextToSpeechAdapter.ADAPTER_ID; }
        @Override public boolean available() { try { return current().available(); } catch (RuntimeException unavailable) { return false; } }
        @Override public String reasonCode() { return available() ? "AVAILABLE" : VolcengineTextToSpeechAdapter.NOT_CONFIGURED; }
    }
}
