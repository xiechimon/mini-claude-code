package com.miniclaudecode.llm;

import org.slf4j.Logger;

/**
 * 记录只在终端流式展示的模型推理文本
 * 不记录可能包含大段 base64 图片的请求体
 */
public final class LlmTraceLogger {
    private LlmTraceLogger() {
    }

    public static void logReasoning(Logger log, String scope, LlmClient llmClient, String reasoningContent) {
        if (log == null || reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        String normalized = reasoningContent.replace("\r\n", "\n").replace('\r', '\n').trim();
        log.info("LLM reasoning [{}] provider={} model={} chars={}\n{}",
                scope == null || scope.isBlank() ? "unknown" : scope,
                llmClient == null ? "unknown" : llmClient.getProviderName(),
                llmClient == null ? "unknown" : llmClient.getModelName(),
                normalized.length(),
                normalized);
    }
}
