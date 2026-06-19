package com.xmon.nanoagent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * 冒烟验证：确认 SDK、鉴权、网络三者贯通。
 * 从 s01 开始，本类将被真正的 agent loop 取代。
 * <p>
 * 端点与鉴权全部由外部配置提供，代码不含任何 provider 配置：
 * anthropic.baseUrl / ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN。
 */
public final class Main {

    private static final String MODEL = "deepseek-v4-flash";

    public static void main(String[] args) {
        String baseUrl = System.getProperty("anthropic.baseUrl", System.getenv("ANTHROPIC_BASE_URL"));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_BASE_URL 未配置，拒绝使用 Anthropic 默认端点");
        }

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024L)
                .addUserMessage("用一句中文回答：你在线吗？")
                .build();

        Message response = client.messages().create(params);

        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(text -> System.out.println(text.text()));
    }
}
