package com.iflytek.astron.workflow.engine.integration.model;

import com.ruoyi.paiflow.worker.service.ExecutionScope;
import com.ruoyi.paiflow.worker.service.EffectReceipt;
import com.ruoyi.paiflow.worker.service.OpenAiEndpoint;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.retry.support.RetryTemplate;

import com.iflytek.astron.workflow.engine.constants.MsgTypeEnum;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmCallback;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmResVo;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 基于OpenAI接口风格的LLM 交互集成
 * - 如讯飞：https://spark-api-open.xf-yun.com/v1/chat/completions
 * - 如智谱：https://open.bigmodel.cn/api/paas/v4/
 *
 * @author YiHui
 * @date 2025/12/1
 */
@Slf4j
@Component
public class OpenAiStyleLlmIntegration {
    private OpenAiApi initClient(String key, String apiUrl) {
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(apiUrl);
        var transport = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var blocking = new JdkClientHttpRequestFactory(transport);
        blocking.setReadTimeout(java.time.Duration.ofSeconds(300));
        var reactive = new JdkClientHttpConnector(transport);
        reactive.setReadTimeout(java.time.Duration.ofSeconds(300));
        return OpenAiApi.builder().apiKey(key).baseUrl(endpoint.baseUrl()).completionsPath(endpoint.completionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(blocking))
                .webClientBuilder(WebClient.builder().clientConnector(reactive)).build();
    }

    public LlmResVo call(LlmReqBo req, LlmCallback callback) {
        ExecutionScope scope = ExecutionScope.current();
        String grantId = scope.authorize("model.infer", OpenAiEndpoint.resolve(req.getUrl()).requestUrl());
        // build prompt
        Prompt prompt = buildPrompt(req);
        // llm stream call
        Flux<ChatResponse> flux = buildChatModel(req).stream(prompt);
        // llm response
        StringBuilder response = new StringBuilder();
        // read response
        StringBuilder reasoningContent = new StringBuilder();
        try {
            // accumulate response
            ChatResponse lastResponse = flux.doOnNext(chatResponse -> {
                if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
                    AssistantMessage message = chatResponse.getResult().getOutput();

                    // Accumulate reasoning if present
                    var reasoningChunk = message.getMetadata().get("reasoningContent");
                    if (reasoningChunk != null) {
                        reasoningContent.append(reasoningChunk);
                    }

                    // Accumulate response data
                    String text = message.getText();
                    if (!StringUtils.isBlank(text)) {
                        response.append(text);
                    }

                    // Call the callback with the response
                    callback.onResponse(chatResponse);
                }
            }).blockLast(); // block to wait for all responses to be processed

            log.info("LLM response received, contentLength={}, reasoningLength={}", response.length(), reasoningContent.length());
            // get token usage info
            Usage tokenUsage = lastResponse != null ? lastResponse.getMetadata().getUsage() : new EmptyUsage();
            scope.confirmedEffect(grantId, EffectReceipt.text(response.toString()));
            return new LlmResVo(tokenUsage, response.toString(), reasoningContent.toString());
        } catch (Exception e) {
            String errorMessage = buildErrorMessage(e);
            log.error("Error calling OpenAI API: {}", errorMessage, e);
            throw new RuntimeException(errorMessage, e);
        }
    }

    private String buildErrorMessage(Exception e) {
        Throwable rootCause = findRootCause(e);
        Throwable actualException = rootCause != null ? rootCause : e;
        if (actualException instanceof WebClientResponseException responseException) {
            StringBuilder builder = new StringBuilder("LLM request failed: HTTP ")
                    .append(responseException.getStatusCode().value())
                    .append(" ")
                    .append(responseException.getStatusText());
            if (responseException.getStatusCode().is4xxClientError()) {
                builder.append(", please check the model API key and endpoint configuration");
            }

            return builder.toString();
        }

        return "LLM request failed";
    }

    private Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String trimDetail(String detail) {
        if (StringUtils.isBlank(detail)) {
            return null;
        }

        String normalized = detail.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300) + "...";
    }

    private Prompt buildPrompt(LlmReqBo req) {
        List<Message> msgList = new ArrayList<>();
        if (StringUtils.isNotBlank(req.getSystemMsg())) {
            msgList.add(new SystemMessage(req.getSystemMsg()));
        }
        if (!CollectionUtils.isEmpty(req.getHistory())) {
            // 访问历史非空，构建对话历史
            for (LlmChatHistory.ChatItem item : req.getHistory()) {
                for (LlmChatHistory.ChatMessage message : item.userInputs()) {
                    if (message.role() == MsgTypeEnum.SYSTEM) {
                        // 说明：构建LLM对话历史时，不保留原有的系统消息，避免和现在使用的系统消息冲突
                        continue;
                    } else {
                        msgList.add(new UserMessage(message.content()));
                    }
                }
                if (!CollectionUtils.isEmpty(item.llmResponses())) {
                    msgList.add(new AssistantMessage(item.llmResponses().get(0).content()));
                }
            }
        }
        msgList.add(new UserMessage(req.getUserMsg()));
        return new Prompt(msgList, buildChatOption(req));
    }

    private ChatModel buildChatModel(LlmReqBo req) {
        OpenAiApi openAiApi = initClient(req.getApiKey(), req.getUrl());
        OpenAiChatOptions.Builder optionBuilder = OpenAiChatOptions.builder().model(req.getModel()).streamUsage(true);
        if (req.getMaxTokens() != null) {
            optionBuilder.maxTokens(req.getMaxTokens());
        }
        // 支持深度思考
        optionBuilder.extraBody(Map.of("enable_thinking", true));

        return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(optionBuilder.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    private OpenAiChatOptions buildChatOption(LlmReqBo req) {
        OpenAiChatOptions.Builder chatOptionsBuilder = OpenAiChatOptions.builder();

        AtomicBoolean hasConfig = new AtomicBoolean(false);
        if (req.getTopK() != null) {
            hasConfig.set(true);
        }
        if (req.getMaxTokens() != null) {
            hasConfig.set(true);
            chatOptionsBuilder.maxTokens(req.getMaxTokens());
        }
        if (req.getExtraParams() != null) {
            // 使用更简洁的方式处理额外参数
            get(req.getExtraParams(), "temperature", Double::parseDouble).ifPresent(t -> {
                hasConfig.set(true);
                chatOptionsBuilder.temperature(t);
            });
            get(req.getExtraParams(), "topP", Double::parseDouble).ifPresent(topP -> {
                hasConfig.set(true);
                chatOptionsBuilder.topP(topP);
            });
            get(req.getExtraParams(), "presencePenalty", Double::parseDouble).ifPresent(presencePenalty -> {
                hasConfig.set(true);
                chatOptionsBuilder.presencePenalty(presencePenalty);
            });
            get(req.getExtraParams(), "frequencyPenalty", Double::parseDouble).ifPresent(frequencyPenalty -> {
                hasConfig.set(true);
                chatOptionsBuilder.frequencyPenalty(frequencyPenalty);
            });
            get(req.getExtraParams(), "maxTokens", Integer::parseInt).ifPresent(maxTokens -> {
                hasConfig.set(true);
                chatOptionsBuilder.maxTokens(maxTokens);
            });
            get(req.getExtraParams(), "n", Integer::parseInt).ifPresent(n -> {
                hasConfig.set(true);
                chatOptionsBuilder.N(n);
            });
        }

        OpenAiChatOptions openAiChatOptions = hasConfig.get() ? chatOptionsBuilder.build() : null;
        return openAiChatOptions;
    }

    private <T> Optional<T> get(Map<String, Object> map, String key, Function<String, T> parse) {
        var value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse.apply(value + ""));
        } catch (Exception e) {
            log.error("Invalid model option: {}", key);
            return Optional.empty();
        }
    }
}
