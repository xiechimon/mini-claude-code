package com.miniclaudecode.agent;

import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.tool.ToolRegistry;
import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRunnerTest {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRunnerTest.class);

    private static LlmClient.ToolCall tc(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }

    private static ToolExecutionResult textResult(String name) {
        return new ToolExecutionResult("id-" + name, name, "{}", "ok", 1L, false, null);
    }

    private static ToolExecutionResult imageResult(String name) {
        return new ToolExecutionResult("id-" + name, name, "{}", "ok", 1L, false,
                List.of(LlmClient.ContentPart.imageBase64("QUJD", "image/png")));
    }

    @Test
    void executeKeepsToolCallOrderAndReportsEachResult() {
        List<String> reported = new ArrayList<>();

        List<ToolExecutionResult> results = ToolCallRunner.execute(LOG, "iteration=1", new ToolRegistry(),
                List.of(tc("call-1", "no_such_tool_a", "{}"), tc("call-2", "no_such_tool_b", "{}")),
                result -> reported.add(result.id()));

        assertEquals(List.of("call-1", "call-2"), results.stream().map(ToolExecutionResult::id).toList());
        assertEquals(List.of("call-1", "call-2"), reported);
    }

    @Test
    void executeAcceptsNullResultConsumer() {
        List<ToolExecutionResult> results = ToolCallRunner.execute(LOG, "worker-1", new ToolRegistry(),
                List.of(tc("call-1", "no_such_tool", "{}")), null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).result().contains("未知工具"), results.get(0).result());
    }

    @Test
    void appendImageMessagesAddsOneUserMessagePerImageResult() {
        List<LlmClient.Message> history = new ArrayList<>();

        ToolCallRunner.appendImageMessages(history, List.of(
                textResult("read_file"), imageResult("take_screenshot"), imageResult("read_file")));

        assertEquals(2, history.size());
        LlmClient.Message first = history.get(0);
        assertEquals("user", first.role());
        assertEquals(2, first.contentParts().size());
        assertTrue(first.contentParts().get(0).text().contains("take_screenshot"),
                first.contentParts().get(0).text());
        assertEquals("image_base64", first.contentParts().get(1).type());
    }

    @Test
    void appendImageMessagesIgnoresResultsWithoutImages() {
        List<LlmClient.Message> history = new ArrayList<>();

        ToolCallRunner.appendImageMessages(history, List.of(textResult("read_file")));
        ToolCallRunner.appendImageMessages(history, List.of());
        ToolCallRunner.appendImageMessages(history, null);

        assertTrue(history.isEmpty());
    }
}
