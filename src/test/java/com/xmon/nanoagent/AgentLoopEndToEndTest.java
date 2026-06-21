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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentLoopEndToEndTest {

    @TempDir
    Path workingDirectory;

    @Test
    void modelCanCreateHelloPythonAndContinueWithTheToolResult() throws Exception {
        FakeModelClient model = new FakeModelClient(
                toolRequest("printf 'print(\"Hello, world!\")\\n' > hello.py"),
                finalAnswer("created"));

        List<String> answer = loop(model).respond("创建 hello.py");

        assertEquals(List.of("created"), answer);
        assertEquals("print(\"Hello, world!\")\n", Files.readString(workingDirectory.resolve("hello.py")));
        assertEquals("(no output)", toolResultSentToSecondRequest(model));
    }

    @Test
    void modelCanListPythonFilesAndContinueWithTheToolResult() throws Exception {
        Files.writeString(workingDirectory.resolve("a.py"), "");
        Files.writeString(workingDirectory.resolve("b.py"), "");
        Files.writeString(workingDirectory.resolve("notes.txt"), "");
        FakeModelClient model = new FakeModelClient(
                toolRequest("find . -name '*.py' -print | sort"),
                finalAnswer("listed"));

        loop(model).respond("列出 Python 文件");

        assertEquals("./a.py\n./b.py", toolResultSentToSecondRequest(model));
    }

    @Test
    void modelCanReadTheCurrentGitBranchAndContinueWithTheToolResult() throws Exception {
        Files.createDirectories(workingDirectory.resolve(".git/objects"));
        Files.createDirectories(workingDirectory.resolve(".git/refs/heads"));
        Files.writeString(workingDirectory.resolve(".git/HEAD"), "ref: refs/heads/course-branch\n");
        Files.writeString(workingDirectory.resolve(".git/config"), """
                [core]
                    repositoryformatversion = 0
                    bare = false
                """);
        FakeModelClient model = new FakeModelClient(
                toolRequest("git branch --show-current"),
                finalAnswer("course-branch"));

        loop(model).respond("当前 Git 分支是什么？");

        assertEquals("course-branch", toolResultSentToSecondRequest(model));
    }

    private AgentLoop loop(FakeModelClient model) {
        return new AgentLoop(
                model,
                BashTool.production(workingDirectory, System.getenv()),
                "test-model",
                workingDirectory,
                new PrintWriter(new StringWriter()));
    }

    private static String toolResultSentToSecondRequest(FakeModelClient model) {
        return model.requests.get(1).messages().get(2).content().asBlockParams().getFirst()
                .toolResult().orElseThrow().content().orElseThrow().asString();
    }

    private static Message toolRequest(String command) {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("tool-call")
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(Map.of("command", command)))
                .name("bash")
                .build();
        return message(StopReason.TOOL_USE, ContentBlock.ofToolUse(toolUse));
    }

    private static Message finalAnswer(String answer) {
        return message(
                StopReason.END_TURN,
                ContentBlock.ofText(TextBlock.builder()
                        .citations(Optional.empty())
                        .text(answer)
                        .build()));
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
        private final java.util.ArrayList<MessageCreateParams> requests = new java.util.ArrayList<>();

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
