package com.xmon.nanoagent;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试命令行交互
 */
final class ReplTest {

    @TempDir
    Path workingDirectory;

    @Test
    void ordinaryInputIsPreservedAndFinalTextBlocksArePrintedInOrder() throws InterruptedException {
        FakeModelClient model = new FakeModelClient(message(text("first"), text("second")));
        StubLineReader input = new StubLineReader("  keep spaces  ", " q ");
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        repl.run();

        assertEquals("  keep spaces  ", model.requests.getFirst().messages().getFirst().content().asString());
        assertEquals(List.of("\033[36ms01 >> \033[0m", "\033[36ms01 >> \033[0m"), input.prompts);
        assertEquals("""
                s01: Agent Loop
                输入问题，回车发送。输入 q 退出。

                first
                second

                """, output.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "q", " Q ", "exit", " ExIt "})
    void blankAndExitWordsStopWithoutCallingTheModel(String query) throws InterruptedException {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(query);
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        repl.run();

        assertEquals(0, model.requests.size());
    }

    @Test
    void endOfFileStopsWithoutEscaping() {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(new EndOfFileException());
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        assertDoesNotThrow(repl::run);
        assertEquals(0, model.requests.size());
    }

    @Test
    void userInterruptStopsWithoutEscaping() {
        FakeModelClient model = new FakeModelClient();
        StubLineReader input = new StubLineReader(new UserInterruptException("partial"));
        StringWriter output = new StringWriter();
        Repl repl = new Repl(input.reader(), new PrintWriter(output), agentLoop(model, output));

        assertDoesNotThrow(repl::run);
        assertEquals(0, model.requests.size());
    }

    private AgentLoop agentLoop(FakeModelClient model, StringWriter output) {
        return new AgentLoop(
                model,
                new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2)),
                "test-model",
                workingDirectory,
                new PrintWriter(output));
    }

    private static ContentBlock text(String value) {
        return ContentBlock.ofText(TextBlock.builder().citations(Optional.empty()).text(value).build());
    }

    private static Message message(ContentBlock... content) {
        return Message.builder()
                .id("message")
                .container(Optional.empty())
                .content(Arrays.asList(content))
                .model("test-model")
                .stopDetails(Optional.empty())
                .stopReason(StopReason.END_TURN)
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
