package com.miniclaudecode.pty;

import java.util.List;

/**
 * LLM 交互脚本：描述一个连续对话中每轮 chat/completions 的 SSE 响应
 *
 * <p>每个 Turn 是完整的一次 SSE 流（多条 data: 行），按请求序号依次返回
 */
public record StubScript(
        String modelName,
        List<Turn> turns
) {
    public record Turn(
            String description,
            List<String> sseLines
    ) {
        public Turn(String description, List<String> sseLines) {
            this.description = description;
            this.sseLines = List.copyOf(sseLines);
        }

        /**
         * 返回完整 SSE 文本（含空行分隔和 [DONE]）
         */
        public String toSseText() {
            StringBuilder sb = new StringBuilder();
            for (String line : sseLines) {
                sb.append("data: ").append(line).append("\n");
            }
            sb.append("\n");
            sb.append("data: [DONE]\n");
            sb.append("\n");
            return sb.toString();
        }
    }

    private static final Turn EXHAUSTED_FALLBACK = new Turn("兜底", List.of(
            sseChunk("assistant", null, null, 0, null),
            sseChunk(null, "已收到", "stop", 10, 4)
    ));

    public Turn turn(int index) {
        if (turns.isEmpty()) {
            throw new IllegalStateException("脚本无 turn");
        }
        if (index >= turns.size()) {
            return EXHAUSTED_FALLBACK;
        }
        return turns.get(index);
    }

    // ---- 预置脚本 ----

    /** 纯文本回复 */
    public static StubScript textReply(String text) {
        return new StubScript("glm-5.1", List.of(
                new Turn("文本回复", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseChunk(null, text, null, 0, null),
                        sseChunk(null, null, "stop", 55, 12)
                ))
        ));
    }

    /** 单工具调用：read_file */
    public static StubScript readFile(String filePath) {
        return new StubScript("glm-5.1", List.of(
                new Turn("read_file", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseToolCallStart("call_1", "read_file"),
                        sseToolCallArgs("{\"path\":\"" + filePath + "\",\"offset\":1,\"limit\":50}"),
                        sseFinish("tool_calls", 55, 12)
                ))
        ));
    }

    /** 多个同轮工具调用 */
    public static StubScript readMultipleFiles(String... paths) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(sseChunk("assistant", null, null, 0, null));
        for (int i = 0; i < paths.length; i++) {
            lines.add(sseToolCallStart("call_" + (i + 1), "read_file"));
            lines.add(sseToolCallArgs("{\"path\":\"" + paths[i] + "\",\"offset\":1,\"limit\":50}"));
        }
        lines.add(sseFinish("tool_calls", 55, 12));
        return new StubScript("glm-5.1", List.of(new Turn("read " + paths.length + " files", lines)));
    }

    /** write_file 工具调用 */
    public static StubScript writeFile(String filePath, String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return new StubScript("glm-5.1", List.of(
                new Turn("write_file", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseToolCallStart("call_1", "write_file"),
                        sseToolCallArgs("{\"path\":\"" + filePath + "\",\"content\":\"" + escaped + "\"}"),
                        sseFinish("tool_calls", 55, 12)
                ))
        ));
    }

    /** 带 reasoning 的思考回复 */
    public static StubScript reasoning(String reasoningText, String replyText) {
        return new StubScript("glm-5.1", List.of(
                new Turn("reasoning", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseReasoningChunk(reasoningText),
                        sseChunk(null, replyText, "stop", 55, 12)
                ))
        ));
    }

    /** 多轮脚本：工具调用 + 工具结果后回复 */
    public static StubScript toolThenReply(String toolName, String toolArgs, String reply) {
        return new StubScript("glm-5.1", List.of(
                new Turn("工具调用", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseToolCallStart("call_1", toolName),
                        sseToolCallArgs(toolArgs),
                        sseFinish("tool_calls", 55, 12)
                )),
                new Turn("文本回复", List.of(
                        sseChunk("assistant", null, null, 0, null),
                        sseChunk(null, reply, "stop", 55, 12)
                ))
        ));
    }

    // ---- SSE 行构造器 ----

    static String sseChunk(String role, String content, String finishReason, Integer promptTokens, Integer completionTokens) {
        StringBuilder sb = new StringBuilder("{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{");
        boolean hasDelta = false;
        if (role != null) {
            sb.append("\"role\":\"").append(role).append("\"");
            hasDelta = true;
        }
        if (content != null) {
            if (hasDelta) sb.append(",");
            sb.append("\"content\":\"").append(escapeJson(content)).append("\"");
            hasDelta = true;
        }
        sb.append("}");
        if (finishReason != null) {
            sb.append(",\"finish_reason\":\"").append(finishReason).append("\"");
        } else {
            sb.append(",\"finish_reason\":null");
        }
        sb.append("}]");
        if (promptTokens != null || completionTokens != null) {
            int p = promptTokens != null ? promptTokens : 0;
            int c = completionTokens != null ? completionTokens : 0;
            sb.append(",\"usage\":{\"prompt_tokens\":").append(p)
                    .append(",\"completion_tokens\":").append(c)
                    .append(",\"total_tokens\":").append(p + c).append("}");
        }
        sb.append(",\"model\":\"glm-5.1\"}");
        return sb.toString();
    }

    static String sseReasoningChunk(String reasoningText) {
        return "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\""
                + escapeJson(reasoningText) + "\"},\"finish_reason\":null}],\"model\":\"glm-5.1\"}";
    }

    static String sseToolCallStart(String callId, String functionName) {
        return "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\""
                + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"" + functionName + "\",\"arguments\":\"\"}}]},\"finish_reason\":null}],\"model\":\"glm-5.1\"}";
    }

    static String sseToolCallArgs(String argsJson) {
        return "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\""
                + escapeJson(argsJson) + "\"}}]},\"finish_reason\":null}],\"model\":\"glm-5.1\"}";
    }

    static String sseFinish(String finishReason, Integer promptTokens, Integer completionTokens) {
        int p = promptTokens != null ? promptTokens : 0;
        int c = completionTokens != null ? completionTokens : 0;
        return "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"usage\":{\"prompt_tokens\":" + p
                + ",\"completion_tokens\":" + c + ",\"total_tokens\":" + (p + c)
                + "},\"choices\":[{\"index\":0,\"finish_reason\":\"" + finishReason + "\"}],\"model\":\"glm-5.1\"}";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}