package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.xmon.nanoagent.core.AgentLoop;
import com.xmon.nanoagent.core.ModelClient;
import com.xmon.nanoagent.core.ModelEvent;
import com.xmon.nanoagent.host.Workspace;
import com.xmon.nanoagent.permission.PermissionGate;
import com.xmon.nanoagent.permission.PermissionRule;
import com.xmon.nanoagent.permission.TerminalApprovalPrompt;
import com.xmon.nanoagent.tool.BashTool;
import com.xmon.nanoagent.tool.ToolRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试命令行交互
 */
final class ReplTest {

    @TempDir
    Path workingDirectory;

    @Test
    void ordinaryInputIsPreservedAndFinalTextBlocksArePrintedInOrder() throws Exception {
        FakeModelClient model = new FakeModelClient(message(text("first"), text("second")));
        StubLineReader input = new StubLineReader("  keep spaces  ", " q ");
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        repl.run();

        assertEquals("  keep spaces  ", model.requests.getFirst().messages().getFirst().content().asString());
        assertEquals(List.of("\033[36mnano-agent >> \033[0m", "\033[36mnano-agent >> \033[0m"), input.prompts);
        assertEquals("""
                nano-agent — 手写 coding agent harness
                输入问题，回车发送。输入 q 退出。

                firstsecond
                """, output.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "q", " Q ", "exit", " ExIt "})
    void blankAndExitWordsStopWithoutCallingTheModel(String query) throws Exception {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(query);
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        repl.run();

        assertEquals(0, model.requests.size());
    }

    @Test
    void endOfFileStopsWithoutEscaping() throws Exception {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(new EndOfFileException());
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        assertDoesNotThrow(repl::run);
        assertEquals(0, model.requests.size());
    }

    @Test
    void userInterruptStopsWithoutEscaping() throws Exception {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(new UserInterruptException("partial"));
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        assertDoesNotThrow(repl::run);
        assertEquals(0, model.requests.size());
    }

    /**
     * 构造不设任何规则的权限闸门
     *
     * <p>本类验证的是 REPL 交互而非权限策略，因此规则表留空；审批器一旦被调用即断言失败。
     *
     * @return 放行一切的权限闸门
     */
    private static PermissionGate permitAll() {
        return new PermissionGate(List.of(), (toolName, input, reason) -> {
            throw new AssertionError("unexpected approval request: " + toolName);
        });
    }

    private AgentLoop agentLoop(FakeModelClient model, StringWriter output) throws IOException {
        return new AgentLoop(
                model,
                new ToolRegistry(
                        new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                        new Workspace(workingDirectory)),
                permitAll(),
                "test-model",
                workingDirectory,
                new PrintWriter(output));
    }

    @Test
    void interruptAtTheApprovalPromptExitsGracefullyInsteadOfEscaping() throws Exception {
        FakeModelClient model = new FakeModelClient(
                toolUseMessage("bash", Map.of("command", "rm test.txt")),
                message(text("unreachable")));
        // 第一次 readLine 是主提示符，第二次是权限审批提示——中断发生在审批上。
        StubLineReader input = new StubLineReader("delete test.txt", new UserInterruptException("at approval"));
        StringWriter output = new StringWriter();
        LineReader reader = input.reader();
        Workspace workspace = new Workspace(workingDirectory);
        AgentLoop agentLoop = new AgentLoop(
                model,
                new ToolRegistry(new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)), workspace),
                new PermissionGate(
                        PermissionRule.defaults(workspace),
                        new TerminalApprovalPrompt(reader, new PrintWriter(output))),
                "test-model",
                workingDirectory,
                new PrintWriter(output));

        assertDoesNotThrow(() -> new Repl(reader, new PrintWriter(output), agentLoop).run());
        assertEquals(1, model.requests.size());
    }

    private static ContentBlock text(String value) {
        return ContentBlock.ofText(TextBlock.builder().citations(Optional.empty()).text(value).build());
    }

    private static Message toolUseMessage(String name, Map<String, String> input) {
        return message(
                StopReason.TOOL_USE,
                ContentBlock.ofToolUse(ToolUseBlock.builder()
                        .id("tool-call")
                        .caller(DirectCaller.builder().build())
                        .input(JsonValue.from(input))
                        .name(name)
                        .build()));
    }

    private static Message message(ContentBlock... content) {
        return message(StopReason.END_TURN, content);
    }

    private static Message message(StopReason stopReason, ContentBlock... content) {
        return Message.builder()
                .id("message")
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
        public Stream<ModelEvent> events(MessageCreateParams request) {
            requests.add(request);
            Message message = responses.removeFirst();
            List<ModelEvent> events = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                block.text().map(TextBlock::text).ifPresent(text -> events.add(new ModelEvent.TextDelta(text)));
            }
            events.add(new ModelEvent.MessageComplete(message));
            return events.stream();
        }
    }

    private static final class StubLineReader {

        private final ArrayDeque<Object> events;
        private final List<String> prompts = new ArrayList<>();

        private StubLineReader(Object... events) {
            this.events = new ArrayDeque<>(List.of(events));
        }

        private LineReader reader() {
            return (LineReader) Proxy.newProxyInstance(
                    LineReader.class.getClassLoader(),
                    new Class<?>[]{LineReader.class},
                    (proxy, method, arguments) -> {
                        if (!method.getName().equals("readLine")) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        prompts.add((String) arguments[0]);
                        Object event = events.removeFirst();
                        if (event instanceof RuntimeException exception) {
                            throw exception;
                        }
                        return event;
                    });
        }
    }
}
