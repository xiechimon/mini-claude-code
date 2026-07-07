package com.xmon.nanoagent.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.xmon.nanoagent.host.EffectiveEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 冒烟测试：真调 SDK 验证两个 {@link ModelClient} 实现的事件产出
 *
 * <p>单测用假事件流，覆盖不到「SDK 的流事件与累积器如何拼成完整消息」这条集成链路；本类补上它。
 * 没有模型配置（MODEL_ID 与 API key）时跳过，保证 {@code mvn test} 在无 key 环境仍然通过。
 */
final class ModelClientSmokeTest {

    @Test
    void streamingModelClientEmitsTextAndCompleteMessage() throws Exception {
        AnthropicClient client = clientOrSkip();
        try {
            ModelClient modelClient = new StreamingModelClient(client.messages());
            List<ModelEvent> events = modelClient.events(request(modelId())).toList();

            assertFalse(events.isEmpty());
            assertInstanceOf(ModelEvent.MessageComplete.class, events.getLast());
        } finally {
            client.close();
        }
    }

    @Test
    void blockingModelClientEmitsTextAndCompleteMessage() throws Exception {
        AnthropicClient client = clientOrSkip();
        try {
            ModelClient modelClient = new BlockingModelClient(client.messages());
            List<ModelEvent> events = modelClient.events(request(modelId())).toList();

            assertFalse(events.isEmpty());
            assertInstanceOf(ModelEvent.MessageComplete.class, events.getLast());
        } finally {
            client.close();
        }
    }

    private static MessageCreateParams request(String modelId) {
        return MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(64L)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content("Reply with exactly one word.")
                        .build()))
                .build();
    }

    private static AnthropicClient clientOrSkip() {
        EffectiveEnvironment environment =
                EffectiveEnvironment.load(Path.of("").toAbsolutePath(), System.getenv());
        String apiKey = environment.get("ANTHROPIC_API_KEY");
        String authToken = environment.get("ANTHROPIC_AUTH_TOKEN");
        Assumptions.assumeTrue(
                apiKey != null || authToken != null,
                "跳过：未配置 ANTHROPIC_API_KEY 或 ANTHROPIC_AUTH_TOKEN");

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder();
        String baseUrl = environment.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        if (authToken != null) {
            builder.authToken(authToken);
        }
        return builder.build();
    }

    private static String modelId() {
        EffectiveEnvironment environment =
                EffectiveEnvironment.load(Path.of("").toAbsolutePath(), System.getenv());
        String modelId = environment.get("MODEL_ID");
        Assumptions.assumeTrue(modelId != null, "跳过：未配置 MODEL_ID");
        return modelId;
    }
}
