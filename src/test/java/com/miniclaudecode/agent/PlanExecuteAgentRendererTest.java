package com.miniclaudecode.agent;

import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.render.PlainRenderer;
import com.miniclaudecode.render.Renderer;
import com.miniclaudecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentRendererTest {

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    /** 记录 appendToolCalls 调用的假 renderer，stream 委托给内置 PlainRenderer */
    private static final class RecordingRenderer implements Renderer {
        private final PlainRenderer plain = new PlainRenderer();
        int appendToolCallsCalls;
        List<LlmClient.ToolCall> lastCalls;

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public PrintStream stream() {
            return plain.stream();
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
            appendToolCallsCalls++;
            lastCalls = toolCalls;
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(com.miniclaudecode.render.StatusInfo status) {
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public com.miniclaudecode.hitl.ApprovalResult promptApproval(
                com.miniclaudecode.hitl.ApprovalRequest request) {
            return com.miniclaudecode.hitl.ApprovalResult.reject("test");
        }
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void rendererDefaultsToNull() throws Exception {
        PlanExecuteAgent plan = new PlanExecuteAgent(
                new FakeLlmClient(),
                (goal, plan1) -> PlanExecuteAgent.PlanReviewDecision.cancel());

        assertNull(readField(plan, "renderer"));
    }

    @Test
    void setRendererStoresReference() throws Exception {
        PlanExecuteAgent plan = new PlanExecuteAgent(
                new FakeLlmClient(),
                (goal, plan1) -> PlanExecuteAgent.PlanReviewDecision.cancel());
        RecordingRenderer renderer = new RecordingRenderer();

        plan.setRenderer(renderer);

        assertSame(renderer, readField(plan, "renderer"));
    }

    @Test
    void renderToolCallsDelegatesToRendererWhenSet() {
        PlanExecuteAgent plan = new PlanExecuteAgent(
                new FakeLlmClient(),
                (goal, plan1) -> PlanExecuteAgent.PlanReviewDecision.cancel());
        RecordingRenderer renderer = new RecordingRenderer();
        plan.setRenderer(renderer);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        plan.renderToolCalls(new PrintStream(sink, true, StandardCharsets.UTF_8),
                List.of(tc("read_file", "{\"path\":\"a.md\"}")));

        assertEquals(1, renderer.appendToolCallsCalls);
        assertEquals(1, renderer.lastCalls.size());
        // 未直写 out：展示责任已移交 renderer
        assertEquals("", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    void renderToolCallsFallsBackToDirectWriteWithoutRenderer() {
        PlanExecuteAgent plan = new PlanExecuteAgent(
                new FakeLlmClient(),
                (goal, plan1) -> PlanExecuteAgent.PlanReviewDecision.cancel());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        plan.renderToolCalls(new PrintStream(sink, true, StandardCharsets.UTF_8),
                List.of(tc("read_file", "{\"path\":\"a.md\"}")));

        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("📖 读取 1 个文件"));
    }

    /** 不发起真实请求的最小 client，只满足构造参数 */
    private static final class FakeLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws java.io.IOException {
            return new ChatResponse("assistant", "ok", null, List.of(), 0, 0, 0);
        }

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws java.io.IOException {
            return chat(messages, tools);
        }
    }
}
