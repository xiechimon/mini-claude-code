package com.miniclaudecode.llm;

import com.miniclaudecode.config.MiniClaudeCodeConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientFactoryTest {

    @Test
    void createsGlm5vTurboClientWithMultimodalEndpoint() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.getProviders().put("glm",
                new MiniClaudeCodeConfig.ProviderConfig("test-glm-key", null, "glm-5v-turbo"));

        LlmClient client = LlmClientFactory.create("glm", config);

        GLMClient glmClient = assertInstanceOf(GLMClient.class, client);
        assertEquals("glm", glmClient.getProviderName());
        assertEquals("glm-5v-turbo", glmClient.getModelName());
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glmClient.getApiUrl());
    }

    @Test
    void createsKimiClientFromMoonshotAliasAndCustomBaseUrl() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("kimi",
                new MiniClaudeCodeConfig.ProviderConfig(
                        "test-kimi-key",
                        "https://api.moonshot.ai/v1",
                        "kimi-k2.6"));

        LlmClient client = LlmClientFactory.create("moonshot", config);

        KimiClient kimiClient = assertInstanceOf(KimiClient.class, client);
        assertEquals("kimi", kimiClient.getProviderName());
        assertEquals("kimi-k2.6", kimiClient.getModelName());
        assertEquals(256_000, kimiClient.maxContextWindow());
    }

    @Test
    void createsFreeLlmApiClientFromConfiguredProvider() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("freellmapi",
                new MiniClaudeCodeConfig.ProviderConfig(
                        "test-free-key",
                        "http://localhost:5173/v1",
                        "auto"));

        LlmClient client = LlmClientFactory.create("free-llm-api", config);

        FreeLlmApiClient freeClient = assertInstanceOf(FreeLlmApiClient.class, client);
        assertEquals("freellmapi", freeClient.getProviderName());
        assertEquals("auto", freeClient.getModelName());
        assertEquals("http://localhost:5173/v1/chat/completions", freeClient.getApiUrl());
        assertEquals(128_000, freeClient.maxContextWindow());
    }

    @Test
    void createsDeepSeekClientFromConfiguredProvider() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("deepseek",
                new MiniClaudeCodeConfig.ProviderConfig("test-deepseek-key", null, "deepseek-v4-flash"));

        LlmClient client = LlmClientFactory.create("deepseek", config);

        DeepSeekClient deepSeekClient = assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek", deepSeekClient.getProviderName());
        assertEquals("deepseek-v4-flash", deepSeekClient.getModelName());
        assertEquals(1_000_000, deepSeekClient.maxContextWindow());
        assertFalse(deepSeekClient.supportsImageInput());
    }

    @Test
    void returnsNullForRemovedProvider() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("step",
                new MiniClaudeCodeConfig.ProviderConfig("test-step-key", null, "step-3.5-flash"));
        config.getProviders().put("xfyun",
                new MiniClaudeCodeConfig.ProviderConfig("test-xfyun-key", null, "Qwen3.6-35B-A3B"));
        config.getProviders().put("agnes",
                new MiniClaudeCodeConfig.ProviderConfig("test-agnes-key", null, "agnes-2.0-flash"));

        assertNull(LlmClientFactory.create("step", config));
        assertNull(LlmClientFactory.create("stepfun", config));
        assertNull(LlmClientFactory.create("maas", config));
        assertNull(LlmClientFactory.create("agnes-ai", config));
    }

    @Test
    void returnsNullForUnknownProvider() {
        MiniClaudeCodeConfig config = new MiniClaudeCodeConfig();
        config.getProviders().put("unknown", new MiniClaudeCodeConfig.ProviderConfig("test-key", null, "unknown-model"));

        assertNull(LlmClientFactory.create("unknown", config));
    }
}
