package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
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

final class AgentLoopTest {

    @TempDir
    Path workingDirectory;

    @Test
    void finalResponseReturnsTextAndSendsTheExactRequestInvariants() throws InterruptedException {
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
                        + ". Use bash to solve tasks. Act, don't explain.",
                request.system().orElseThrow().asString());
        assertEquals(1, request.tools().orElseThrow().size());
        Tool tool = request.tools().orElseThrow().getFirst().tool().orElseThrow();
        assertEquals("bash", tool.name());
        assertEquals("Run a shell command.", tool.description().orElseThrow());
        assertEquals(List.of("command"), tool.inputSchema().required().orElseThrow());
        JsonValue commandSchema = tool.inputSchema().properties().orElseThrow()
                ._additionalProperties().get("command");
        assertEquals(JsonValue.from(Map.of("type", "string")), commandSchema);
        assertEquals("  keep my spaces  ", request.messages().getFirst().content().asString());
        assertEquals(MessageParam.Role.USER, request.messages().getFirst().role());
        assertEquals("", terminal.toString());
    }

    @Test
    void everyToolCallRunsInContentOrderAndReturnsOneUserMessage() throws InterruptedException {
        FakeModelClient model = new FakeModelClient(
                message(
                        StopReason.TOOL_USE,
                        text("working"),
                        toolUse("call-1", "bash", "printf first"),
                        toolUse("call-2", "unexpected-name", "printf second")),
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
        assertToolResult(results.get(1), "call-2", "second");

        String progress = terminal.toString();
        assertTrue(progress.indexOf("$ printf first") < progress.indexOf("first\n"));
        assertTrue(progress.indexOf("first\n") < progress.indexOf("$ printf second"));
        assertTrue(progress.indexOf("$ printf second") < progress.lastIndexOf("second\n"));
    }

    @Test
    void toolPreviewIsTwoHundredCodePointsButModelReceivesTheFullResult() throws InterruptedException {
        String command = "printf '" + "😀".repeat(250) + "'";
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("long-call", "bash", command)),
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
    void conversationHistoryPersistsAcrossUserTurns() throws InterruptedException {
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
    void malformedToolInputIsExposedAfterAssistantHistoryWasAppended() throws InterruptedException {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("bad-call", "bash", Map.of("extra", "ignored"))),
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
    void writerFailureIsExposed() {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("call", "bash", "printf result")));
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
                new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                "test-model",
                workingDirectory,
                failingWriter);

        assertThrows(IllegalStateException.class, () -> loop.respond("write progress"));
    }

    @Test
    void repeatedToolUseHasNoLocalTurnLimit() throws InterruptedException {
        Message[] responses = new Message[26];
        Arrays.fill(responses, 0, 25, message(StopReason.TOOL_USE));
        responses[25] = message(StopReason.END_TURN, text("finished"));
        FakeModelClient model = new FakeModelClient(responses);
        AgentLoop loop = loop(model, new StringWriter());

        assertEquals(List.of("finished"), loop.respond("keep going"));
        assertEquals(26, model.requests.size());
    }

    @Test
    void modelFailureIsNotConvertedToAFallbackResult() {
        IllegalStateException modelFailure = new IllegalStateException("model unavailable");
        ModelClient failingModel = request -> {
            throw modelFailure;
        };
        AgentLoop loop = new AgentLoop(
                failingModel,
                new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                "test-model",
                workingDirectory,
                new PrintWriter(new StringWriter()));

        assertEquals(modelFailure, assertThrows(IllegalStateException.class, () -> loop.respond("fail")));
    }

    private AgentLoop loop(FakeModelClient model, StringWriter terminal) {
        return new AgentLoop(
                model,
                new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                "test-model",
                workingDirectory,
                new PrintWriter(terminal));
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

    private static ContentBlock toolUse(String id, String name, String command) {
        return toolUse(id, name, Map.of("command", command, "extra", "ignored"));
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
