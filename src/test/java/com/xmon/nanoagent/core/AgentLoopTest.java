package com.xmon.nanoagent.core;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.xmon.nanoagent.host.Workspace;
import com.xmon.nanoagent.permission.PermissionGate;
import com.xmon.nanoagent.tool.BashTool;
import com.xmon.nanoagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试模型对话和工具调用流程
 */
final class AgentLoopTest {

    private static final JsonValue STRING_TYPE = JsonValue.from(Map.of("type", "string"));
    private static final JsonValue INTEGER_TYPE = JsonValue.from(Map.of("type", "integer"));

    @TempDir
    Path workingDirectory;

    @Test
    void finalResponseReturnsTextAndSendsTheExactRequestInvariants() throws Exception {
        FakeModelClient model = new FakeModelClient(message(StopReason.MAX_TOKENS, text("first"), text("second")));
        StringWriter terminal = new StringWriter();
        AgentLoop loop = loop(model, terminal);

        List<String> response = loop.respond("  keep my spaces  ");

        assertEquals(List.of("first", "second"), response);
        MessageCreateParams request = model.requests.getFirst();
        assertEquals("test-model", request.model().asString());
        assertEquals(8_000L, request.maxTokens());
        assertEquals(
                "You are a coding agent at " + workingDirectory
                        + ". Use tools to solve tasks. Act, don't explain.",
                request.system().orElseThrow().asString());
        assertEquals("  keep my spaces  ", request.messages().getFirst().content().asString());
        assertEquals(MessageParam.Role.USER, request.messages().getFirst().role());
        assertEquals("", terminal.toString());
    }

    @Test
    void everyRequestDeclaresTheFiveCourseToolsInOrder() throws Exception {
        FakeModelClient model = new FakeModelClient(message(StopReason.END_TURN, text("done")));

        loop(model, new StringWriter()).respond("declare tools");

        List<Tool> tools = model.requests.getFirst().tools().orElseThrow().stream()
                .map(union -> union.tool().orElseThrow())
                .toList();
        assertEquals(
                List.of("bash", "read_file", "write_file", "edit_file", "glob"),
                tools.stream().map(Tool::name).toList());
        assertEquals(
                List.of(
                        "Run a shell command.",
                        "Read file contents.",
                        "Write content to a file.",
                        "Replace exact text in a file once.",
                        "Find files matching a glob pattern."),
                tools.stream().map(tool -> tool.description().orElseThrow()).toList());
        assertEquals(
                List.of(
                        List.of("command"),
                        List.of("path"),
                        List.of("path", "content"),
                        List.of("path", "old_text", "new_text"),
                        List.of("pattern")),
                tools.stream().map(tool -> tool.inputSchema().required().orElseThrow()).toList());
        assertEquals(Map.of("command", STRING_TYPE), properties(tools.get(0)));
        assertEquals(Map.of("path", STRING_TYPE, "limit", INTEGER_TYPE), properties(tools.get(1)));
        assertEquals(Map.of("path", STRING_TYPE, "content", STRING_TYPE), properties(tools.get(2)));
        assertEquals(
                Map.of("path", STRING_TYPE, "old_text", STRING_TYPE, "new_text", STRING_TYPE),
                properties(tools.get(3)));
        assertEquals(Map.of("pattern", STRING_TYPE), properties(tools.get(4)));
    }

    @Test
    void everyToolCallRunsInContentOrderAndReturnsOneUserMessage() throws Exception {
        Files.writeString(workingDirectory.resolve("note.txt"), "from the file");
        FakeModelClient model = new FakeModelClient(
                message(
                        StopReason.TOOL_USE,
                        text("working"),
                        toolUse("call-1", "bash", Map.of("command", "printf first")),
                        toolUse("call-2", "read_file", Map.of("path", "note.txt"))),
                message(StopReason.END_TURN, text("done")));
        StringWriter terminal = new StringWriter();
        AgentLoop loop = loop(model, terminal);

        assertEquals(List.of("done"), loop.respond("run both"));

        assertEquals(2, model.requests.size());
        List<MessageParam> secondHistory = model.requests.get(1).messages();
        assertEquals(3, secondHistory.size());
        assertEquals("assistant", secondHistory.get(1)._role().asString().orElseThrow());
        assertEquals(MessageParam.Role.USER, secondHistory.get(2).role());

        List<ContentBlockParam> results = secondHistory.get(2).content().asBlockParams();
        assertEquals(2, results.size());
        assertToolResult(results.get(0), "call-1", "first");
        assertToolResult(results.get(1), "call-2", "from the file");

        String progress = terminal.toString();
        assertTrue(progress.indexOf("> bash") < progress.indexOf("first\n"));
        assertTrue(progress.indexOf("first\n") < progress.indexOf("> read_file"));
        assertTrue(progress.indexOf("> read_file") < progress.indexOf("from the file"));
    }

    @Test
    void toolPreviewShowsTheToolNameWithoutItsArguments() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("call", "bash", Map.of("command", "printf secret-argument"))),
                message(StopReason.END_TURN, text("done")));
        StringWriter terminal = new StringWriter();

        loop(model, terminal).respond("hide arguments");

        String progress = terminal.toString();
        assertTrue(progress.contains("\033[33m> bash\033[0m"));
        assertFalse(progress.contains("printf"));
    }

    @Test
    void unregisteredToolNameIsFilledBackWithoutStoppingTheLoop() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("call", "grep", Map.of("pattern", "todo"))),
                message(StopReason.END_TURN, text("done")));
        StringWriter terminal = new StringWriter();

        assertEquals(List.of("done"), loop(model, terminal).respond("call an unknown tool"));

        List<ContentBlockParam> results = model.requests.get(1).messages().get(2).content().asBlockParams();
        assertToolResult(results.getFirst(), "call", "Unknown: grep");
        assertTrue(terminal.toString().contains("> grep"));
    }

    @Test
    void toolPreviewIsTwoHundredCodePointsButModelReceivesTheFullResult() throws Exception {
        String command = "printf '" + "😀".repeat(250) + "'";
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("long-call", "bash", Map.of("command", command))),
                message(StopReason.END_TURN, text("done")));
        StringWriter terminal = new StringWriter();
        AgentLoop loop = loop(model, terminal);

        loop.respond("long output");

        ToolResultBlockParam result = model.requests.get(1)
                .messages().get(2)
                .content().asBlockParams().getFirst()
                .toolResult().orElseThrow();
        String fullResult = result.content().orElseThrow().asString();
        assertEquals(250, fullResult.codePointCount(0, fullResult.length()));

        String preview = terminal.toString().substring(terminal.toString().indexOf('\n') + 1).strip();
        assertEquals(200, preview.codePointCount(0, preview.length()));
        assertTrue(preview.endsWith("😀"));
    }

    @Test
    void conversationHistoryPersistsAcrossUserTurns() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.END_TURN, text("answer one")),
                message(StopReason.END_TURN, text("answer two")));
        AgentLoop loop = loop(model, new StringWriter());

        loop.respond("question one");
        loop.respond("question two");

        List<MessageParam> history = model.requests.get(1).messages();
        assertEquals(3, history.size());
        assertEquals("question one", history.get(0).content().asString());
        assertEquals("assistant", history.get(1)._role().asString().orElseThrow());
        assertEquals("question two", history.get(2).content().asString());
    }

    @Test
    void missingRequiredToolInputIsExposedAfterAssistantHistoryWasAppended() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("bad-call", "bash", Map.of())),
                message(StopReason.END_TURN, text("recovered next turn")));
        AgentLoop loop = loop(model, new StringWriter());

        assertThrows(RuntimeException.class, () -> loop.respond("bad input"));
        assertEquals(List.of("recovered next turn"), loop.respond("next input"));

        List<MessageParam> history = model.requests.get(1).messages();
        assertEquals(3, history.size());
        assertEquals("assistant", history.get(1)._role().asString().orElseThrow());
        assertEquals("next input", history.get(2).content().asString());
    }

    @Test
    void unexpectedToolInputFieldIsExposedRatherThanIgnored() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(
                        StopReason.TOOL_USE,
                        toolUse("bad-call", "bash", Map.of("command", "printf ok", "extra", "unexpected"))));
        AgentLoop loop = loop(model, new StringWriter());

        // 多传一个字段不能被静默忽略，也不能退化成一条 Error: 文本。
        RuntimeException failure = assertThrows(RuntimeException.class, () -> loop.respond("extra field"));
        assertTrue(failure.getMessage().contains("extra"));
        assertEquals(1, model.requests.size());
    }

    @Test
    void writerFailureIsExposed() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("call", "bash", Map.of("command", "printf result"))));
        PrintWriter failingWriter = new PrintWriter(new java.io.Writer() {
            @Override
            public void write(char[] characters, int offset, int length) throws java.io.IOException {
                throw new java.io.IOException("terminal closed");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        AgentLoop loop = new AgentLoop(
                model,
                toolRegistry(),
                permitAll(),
                "test-model",
                workingDirectory,
                failingWriter);

        assertThrows(IllegalStateException.class, () -> loop.respond("write progress"));
    }

    @Test
    void repeatedToolUseHasNoLocalTurnLimit() throws Exception {
        Message[] responses = new Message[26];
        Arrays.fill(responses, 0, 25, message(StopReason.TOOL_USE));
        responses[25] = message(StopReason.END_TURN, text("finished"));
        FakeModelClient model = new FakeModelClient(responses);
        AgentLoop loop = loop(model, new StringWriter());

        assertEquals(List.of("finished"), loop.respond("keep going"));
        assertEquals(26, model.requests.size());
    }

    @Test
    void modelFailureIsNotConvertedToAFallbackResult() throws Exception {
        IllegalStateException modelFailure = new IllegalStateException("model unavailable");
        ModelClient failingModel = request -> {
            throw modelFailure;
        };
        AgentLoop loop = new AgentLoop(
                failingModel,
                toolRegistry(),
                permitAll(),
                "test-model",
                workingDirectory,
                new PrintWriter(new StringWriter()));

        assertEquals(modelFailure, assertThrows(IllegalStateException.class, () -> loop.respond("fail")));
    }

    /**
     * 构造不设任何规则的权限闸门
     *
     * <p>本类验证的是循环机制而非权限策略，因此规则表留空；审批器一旦被调用即断言失败。
     * 规则表本身的行为由 {@link AgentLoopEndToEndTest} 覆盖。
     *
     * @return 放行一切的权限闸门
     */
    private static PermissionGate permitAll() {
        return new PermissionGate(List.of(), (toolName, input, reason) -> {
            throw new AssertionError("unexpected approval request: " + toolName);
        });
    }

    private AgentLoop loop(FakeModelClient model, StringWriter terminal) throws IOException {
        return new AgentLoop(
                model,
                toolRegistry(),
                permitAll(),
                "test-model",
                workingDirectory,
                new PrintWriter(terminal));
    }

    private ToolRegistry toolRegistry() throws IOException {
        return new ToolRegistry(
                new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                new Workspace(workingDirectory));
    }

    private static Map<String, JsonValue> properties(Tool tool) {
        return tool.inputSchema().properties().orElseThrow()._additionalProperties();
    }

    private static void assertToolResult(ContentBlockParam block, String expectedId, String expectedContent) {
        ToolResultBlockParam result = block.toolResult().orElseThrow();
        assertEquals(expectedId, result.toolUseId());
        assertEquals(expectedContent, result.content().orElseThrow().asString());
        assertFalse(result.isError().isPresent());
    }

    private static ContentBlock text(String value) {
        return ContentBlock.ofText(TextBlock.builder().citations(Optional.empty()).text(value).build());
    }

    private static ContentBlock toolUse(String id, String name, Map<String, String> input) {
        return ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id(id)
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(input))
                .name(name)
                .build());
    }

    private static Message message(StopReason stopReason, ContentBlock... content) {
        return Message.builder()
                .id("message-" + stopReason.asString())
                .container(Optional.empty())
                .content(Arrays.asList(content))
                .model("test-model")
                .stopDetails(Optional.empty())
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .usage(Usage.builder()
                        .cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty())
                        .cacheReadInputTokens(Optional.empty())
                        .inferenceGeo(Optional.empty())
                        .inputTokens(1)
                        .outputTokens(1)
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build())
                .build();
    }

    private static final class FakeModelClient implements ModelClient {

        private final ArrayDeque<Message> responses;
        private final List<MessageCreateParams> requests = new ArrayList<>();

        private FakeModelClient(Message... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Message create(MessageCreateParams request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
