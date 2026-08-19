package com.miniclaudecode.agent;

import com.miniclaudecode.llm.GLMClient;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.memory.LongTermMemory;
import com.miniclaudecode.memory.MemoryManager;
import com.miniclaudecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /team 终端噪音回归：reviewer 的协议 JSON 不应渲染给用户，重试轮不应重放 worker 全量输出
 */
class TeamOutputNoiseTest {

    private static final String FENCED_REVIEW_JSON = """
            ```json
            {
              "approved": true,
              "summary": "结果完整",
              "issues": [],
              "suggestions": []
            }
            ```
            """;

    private static final String SINGLE_STEP_PLAN = """
            {
              "summary": "单步任务",
              "steps": [
                {"id": "s1", "description": "执行任务", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """;

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static String runTeam(Queue<LlmClient.ChatResponse> responses,
                                  ByteArrayOutputStream sink, File storageDir) {
        StubQueueLlm llmClient = new StubQueueLlm(responses);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolRegistry(),
                new NoOpMemoryManager(storageDir),
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        return orchestrator.run("测试终端噪音");
    }

    @Test
    void reviewerProtocolJsonIsNotRenderedToTerminal(@TempDir Path tempDir) {
        Queue<LlmClient.ChatResponse> responses = new ArrayDeque<>(List.of(
                response(SINGLE_STEP_PLAN),
                response("执行结果正文"),
                response(FENCED_REVIEW_JSON)));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        String finalResult = runTeam(responses, sink, tempDir.toFile());
        String terminal = sink.toString(StandardCharsets.UTF_8);

        assertTrue(finalResult.contains("执行结果正文"), finalResult);
        assertTrue(terminal.contains("✅ 步骤 [step_1] 审查通过"), terminal);
        // reviewer 的 JSON 是给 parseReviewApproval 的协议数据，不允许进终端
        assertFalse(terminal.contains("\"approved\""), terminal);
        assertFalse(terminal.contains("code: json"), terminal);
        assertFalse(terminal.contains("🤖 审查结果"), terminal);
        // planner 的计划 JSON 同理：结构化计划由 summarizeSteps 展示，原始 JSON 不重放
        assertFalse(terminal.contains("🤖 规划结果"), terminal);
        assertFalse(terminal.contains("\"steps\""), terminal);
        assertTrue(terminal.contains("📋 执行计划"), terminal);
    }

    @Test
    void rejectedStepRetriesDoNotReplayWorkerOutput(@TempDir Path tempDir) {
        Queue<LlmClient.ChatResponse> responses = new ArrayDeque<>(List.of(
                response(SINGLE_STEP_PLAN),
                response("第一版结果"),
                response("{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺细节\"]}"),
                response("第二版结果"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        String finalResult = runTeam(responses, sink, tempDir.toFile());
        String terminal = sink.toString(StandardCharsets.UTF_8);

        assertTrue(finalResult.contains("第二版结果"), finalResult);
        assertTrue(terminal.contains("⚠️ 步骤 [step_1] 审查未通过"), terminal);
        assertTrue(terminal.contains("缺细节"), terminal);
        assertTrue(terminal.contains("✅ 步骤 [step_1] 重试后审查通过"), terminal);
        // 首轮 worker 输出已经流式打印过一次；重试轮不允许再重放
        assertTrue(countOccurrences(terminal, "第一版结果") <= 1,
                "worker 输出在终端出现超过一次:\n" + terminal);
        assertFalse(terminal.contains("第二版结果"),
                "重试轮的 worker 输出不应当流式重放:\n" + terminal);
        assertFalse(terminal.contains("\"approved\""), terminal);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }
    }

    private static final class StubQueueLlm extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubQueueLlm(Queue<ChatResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws java.io.IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws java.io.IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new java.io.IOException("缺少预设响应");
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
