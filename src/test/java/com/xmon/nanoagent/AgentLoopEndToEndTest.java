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

import java.io.IOException;
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

/**
 * 测试真实模型的工具调用流程
 */
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

    @Test
    void modelCanReadTheReadmeAndContinueWithTheToolResult() throws Exception {
        Files.writeString(workingDirectory.resolve("README.md"), "# Nano Agent\n一个学习项目。\n");
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("read", "read_file", Map.of("path", "README.md"))),
                finalAnswer("这是一个学习项目"));

        assertEquals(List.of("这是一个学习项目"), loop(model).respond(
                "Read the file README.md and tell me what this project is about"));

        assertEquals("# Nano Agent\n一个学习项目。", toolResultSentToSecondRequest(model));
    }

    @Test
    void modelCanWriteTestPythonAndReadItBack() throws Exception {
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse(
                        "write", "write_file", Map.of("path", "test.py", "content", "print(\"hello\")\n"))),
                message(StopReason.TOOL_USE, toolUse("read", "read_file", Map.of("path", "test.py"))),
                finalAnswer("done"));

        loop(model).respond("Create a file called test.py that prints \"hello\", then read it back");

        assertEquals("print(\"hello\")\n", Files.readString(workingDirectory.resolve("test.py")));
        assertEquals(List.of("Wrote 15 bytes to test.py"), toolResultsAt(model, 1, 2));
        assertEquals(List.of("print(\"hello\")"), toolResultsAt(model, 2, 4));
    }

    @Test
    void modelCanFindPythonFilesWithGlob() throws Exception {
        Files.writeString(workingDirectory.resolve("a.py"), "");
        Files.writeString(workingDirectory.resolve("b.py"), "");
        Files.writeString(workingDirectory.resolve("notes.txt"), "");
        FakeModelClient model = new FakeModelClient(
                message(StopReason.TOOL_USE, toolUse("find", "glob", Map.of("pattern", "*.py"))),
                finalAnswer("found two"));

        loop(model).respond("Find all Python files in this directory");

        assertEquals(
                List.of("a.py", "b.py"),
                Arrays.stream(toolResultSentToSecondRequest(model).split("\n")).sorted().toList());
    }

    @Test
    void modelCanReadTwoFilesInOneTurnAndThenWriteASummary() throws Exception {
        Files.writeString(workingDirectory.resolve("README.md"), "readme body");
        Files.writeString(workingDirectory.resolve("requirements.txt"), "anthropic");
        FakeModelClient model = new FakeModelClient(
                message(
                        StopReason.TOOL_USE,
                        toolUse("read-1", "read_file", Map.of("path", "README.md")),
                        toolUse("read-2", "read_file", Map.of("path", "requirements.txt"))),
                message(StopReason.TOOL_USE, toolUse(
                        "write", "write_file", Map.of("path", "summary.md", "content", "readme body + anthropic"))),
                finalAnswer("summarised"));

        loop(model).respond("Read both README.md and requirements.txt, then create a summary file");

        // 同一个 assistant 轮的两个 Tool Call 按响应顺序执行，结果放进紧随其后的同一条 user 消息。
        assertEquals(List.of("readme body", "anthropic"), toolResultsAt(model, 1, 2));
        assertEquals("readme body + anthropic", Files.readString(workingDirectory.resolve("summary.md")));
    }

    private AgentLoop loop(FakeModelClient model) throws IOException {
        return new AgentLoop(
                model,
                new ToolRegistry(
                        BashTool.production(workingDirectory, System.getenv()),
                        new Workspace(workingDirectory)),
                "test-model",
                workingDirectory,
                new PrintWriter(new StringWriter()));
    }

    private static String toolResultSentToSecondRequest(FakeModelClient model) {
        return toolResultsAt(model, 1, 2).getFirst();
    }

    private static List<String> toolResultsAt(FakeModelClient model, int requestIndex, int messageIndex) {
        return model.requests.get(requestIndex).messages().get(messageIndex).content().asBlockParams().stream()
                .map(block -> block.toolResult().orElseThrow().content().orElseThrow().asString())
                .toList();
    }

    private static Message toolRequest(String command) {
        return message(StopReason.TOOL_USE, toolUse("tool-call", "bash", Map.of("command", command)));
    }

    private static ContentBlock toolUse(String id, String name, Map<String, String> input) {
        return ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id(id)
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(input))
                .name(name)
                .build());
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
