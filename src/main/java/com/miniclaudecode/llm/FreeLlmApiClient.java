package com.miniclaudecode.llm;

/**
 * FreeLlmAPI 的 OpenAI-compatible 客户端
 */
public class FreeLlmApiClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:5173/v1";
    private static final String DEFAULT_MODEL = "auto";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public FreeLlmApiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "freellmapi";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }
}
