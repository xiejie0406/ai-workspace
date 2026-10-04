package com.paicli.llm;

/** 模型适配器只在请求时获取当前有效的中心托管凭据。 */
public interface ManagedKeyAware {
    void setApiKeyProvider(AbstractOpenAiCompatibleClient.ApiKeyProvider provider);
}
