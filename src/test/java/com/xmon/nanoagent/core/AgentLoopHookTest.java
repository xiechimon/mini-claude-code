package com.xmon.nanoagent.core;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.xmon.nanoagent.hook.HookDispatcher;
import com.xmon.nanoagent.hook.HookEvent;
import com.xmon.nanoagent.hook.HookHandler;
import com.xmon.nanoagent.hook.HookInput;
import com.xmon.nanoagent.hook.HookMatcher;
import com.xmon.nanoagent.hook.HookOutput;
import com.xmon.nanoagent.hook.HookPermissionDecision;
import com.xmon.nanoagent.hook.HookSpecificOutput;
import com.xmon.nanoagent.host.Workspace;
import com.xmon.nanoagent.permission.PermissionBehavior;
import com.xmon.nanoagent.permission.PermissionGate;
import com.xmon.nanoagent.permission.PermissionRule;
import com.xmon.nanoagent.tool.BashTool;
import com.xmon.nanoagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试四个 hook 触发点接进循环之后的端到端行为
 *
 * <p>断言落在「模型实际收到了什么」上而不是终端输出：hook 的价值在于改变模型看到的东西，
 * 终端呈现只是副产品。
 */
final class AgentLoopHookTest {

    @TempDir
    Path workingDirectory;

    @Test
    void userPromptSubmitContextTravelsWithTheFirstUserMessage() throws Exception {
        FakeModelClient model = new FakeModelClient(finalAnswer("ok"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.USER_PROMPT_SUBMIT, callback(input -> HookOutput.Sync.of(
                new HookSpecificOutput.UserPromptSubmit(Optional.of("branch is main"), false))));

        loop(model, hooks, permitAll()).respond("what branch");

        String firstUserMessage = userTextAt(model, 0, 0);
        assertTrue(firstUserMessage.contains("what branch"), firstUserMessage);
        // 格式照抄真实 Claude Code 的可观测行为。
        assertTrue(
                firstUserMessage.contains("UserPromptSubmit hook additional context: branch is main"),
                firstUserMessage);
    }

    @Test
    void aBlockedPromptNeverReachesTheModel() throws Exception {
        FakeModelClient model = new FakeModelClient(finalAnswer("unreachable"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.USER_PROMPT_SUBMIT,
                callback(input -> HookOutput.Sync.block("prompts must mention a file")));

        StringWriter terminal = new StringWriter();
        loop(model, hooks, permitAll(), terminal).respond("do something");

        assertEquals(0, model.requests.size(), "the model must not be called at all");
        assertTrue(terminal.toString().contains("prompts must mention a file"), terminal.toString());
    }

    @Test
    void preToolUseDenyKeepsTheToolFromRunningAndFeedsTheReasonBack() throws Exception {
        FakeModelClient model = new FakeModelClient(
                toolRequest("printf ran > marker.txt"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.PRE_TOOL_USE, callback(input -> HookOutput.Sync.of(
                new HookSpecificOutput.PreToolUse(
                        Optional.of(HookPermissionDecision.DENY),
                        Optional.of("writes are frozen"),
                        Optional.empty()))));

        loop(model, hooks, permitAll()).respond("write a marker");

        assertEquals("writes are frozen", toolResultAt(model, 1));
        assertFalse(java.nio.file.Files.exists(workingDirectory.resolve("marker.txt")),
                "the tool must not have run");
    }

    @Test
    void thePreToolUseHookRunsBeforeThePermissionGate() throws Exception {
        // 契约把 hook 放在权限判定之前，两者是两层。这里让两层都会拒绝、但原因不同：
        // 回填的是 hook 的原因，说明 hook 先跑且工具没走到权限判定。
        FakeModelClient model = new FakeModelClient(toolRequest("ls"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.PRE_TOOL_USE,
                callback(input -> HookOutput.Sync.block("denied by hook")));
        PermissionGate gate = new PermissionGate(
                List.of(new PermissionRule(
                        java.util.Set.of("bash"),
                        input -> Optional.of("denied by rule"),
                        PermissionBehavior.DENY)),
                (toolName, input, reason) -> {
                    throw new AssertionError("no approval expected");
                });

        loop(model, hooks, gate).respond("list files");

        assertEquals("denied by hook", toolResultAt(model, 1));
    }

    @Test
    void thePermissionGateStillRunsWhenNoHookDenies() throws Exception {
        // 反方向：hook 不拒绝时权限管线照旧生效，hook 放行不等于权限放行。
        FakeModelClient model = new FakeModelClient(toolRequest("ls"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.PRE_TOOL_USE, callback(input -> HookOutput.Sync.none()));
        PermissionGate gate = new PermissionGate(
                List.of(new PermissionRule(
                        java.util.Set.of("bash"),
                        input -> Optional.of("denied by rule"),
                        PermissionBehavior.DENY)),
                (toolName, input, reason) -> {
                    throw new AssertionError("no approval expected");
                });

        loop(model, hooks, gate).respond("list files");

        assertEquals("denied by rule", toolResultAt(model, 1));
    }

    @Test
    void postToolUseCanReplaceWhatTheModelSees() throws Exception {
        FakeModelClient model = new FakeModelClient(toolRequest("printf secret"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.POST_TOOL_USE, callback(input -> HookOutput.Sync.of(
                new HookSpecificOutput.PostToolUse(Optional.empty(), Optional.of("[redacted]")))));

        loop(model, hooks, permitAll()).respond("print the secret");

        assertEquals("[redacted]", toolResultAt(model, 1));
    }

    @Test
    void postToolUseDoesNotFireWhenTheToolNeverRan() throws Exception {
        List<String> fired = new ArrayList<>();
        FakeModelClient model = new FakeModelClient(toolRequest("ls"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.PRE_TOOL_USE, callback(input -> HookOutput.Sync.block("no")));
        hooks.register(HookEvent.POST_TOOL_USE, callback(input -> {
            fired.add("post");
            return HookOutput.Sync.none();
        }));

        loop(model, hooks, permitAll()).respond("list files");

        assertEquals(List.of(), fired, "PostToolUse requires the tool to have run");
    }

    @Test
    void stopHookCanKeepTheConversationGoing() throws Exception {
        FakeModelClient model = new FakeModelClient(finalAnswer("all done"), finalAnswer("tests pass"));
        HookDispatcher hooks = hooks();
        // 第一次阻止停止，第二次（stop_hook_active 为真）放行——正是契约给 hook 的防循环手段。
        hooks.register(HookEvent.STOP, callback(input -> ((HookInput.Stop) input).stopHookActive()
                ? HookOutput.Sync.none()
                : HookOutput.Sync.block("run the tests first")));

        loop(model, hooks, permitAll()).respond("finish up");

        assertEquals(2, model.requests.size());
        // 续跑注入的消息出现在第二次请求的最后一条 user message 里。
        assertEquals("run the tests first", userTextAt(model, 1, 2));
    }

    @Test
    void stopHookContinuationsAreCappedAtEight() throws Exception {
        // 一个永不放行的 Stop hook 若不设上限就能让循环无界地烧额度。
        FakeModelClient model = new FakeModelClient(finalAnswer("done")).repeatingLast();
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.STOP, callback(input -> HookOutput.Sync.block("keep going")));

        StringWriter terminal = new StringWriter();
        loop(model, hooks, permitAll(), terminal).respond("finish up");

        // 首次请求 + 8 次续跑 = 9 次。
        assertEquals(9, model.requests.size());
        assertTrue(terminal.toString().contains("8"), terminal.toString());
    }

    @Test
    void continueFalseStopsTheSessionAndStillPairsTheToolResult() throws Exception {
        FakeModelClient model = new FakeModelClient(toolRequest("ls"), finalAnswer("unreachable"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.POST_TOOL_USE, callback(input -> new HookOutput.Sync(
                Optional.of(false),
                Optional.of("budget exhausted"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())));

        StringWriter terminal = new StringWriter();
        loop(model, hooks, permitAll(), terminal).respond("list files");

        assertEquals(1, model.requests.size(), "no further model request after continue:false");
        assertTrue(terminal.toString().contains("budget exhausted"), terminal.toString());
    }

    @Test
    void hookAdditionalContextRidesAlongWithTheToolResult() throws Exception {
        FakeModelClient model = new FakeModelClient(toolRequest("ls"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(HookEvent.POST_TOOL_USE, callback(input -> HookOutput.Sync.of(
                new HookSpecificOutput.PostToolUse(Optional.of("repo uses mvn test"), Optional.empty()))));

        loop(model, hooks, permitAll()).respond("list files");

        assertTrue(
                toolResultAt(model, 1).contains("PostToolUse hook additional context: repo uses mvn test"),
                toolResultAt(model, 1));
    }

    @Test
    void aCommandHookConfiguredAsAnExternalProcessBlocksTheToolCall() throws Exception {
        // 唯一走真实子进程的场景：证明 settings.json 里的 command hook 真的能当闸门。
        FakeModelClient model = new FakeModelClient(
                toolRequest("printf ran > marker.txt"), finalAnswer("done"));
        HookDispatcher hooks = hooks();
        hooks.register(
                HookEvent.PRE_TOOL_USE,
                new HookMatcher(
                        "bash",
                        List.of(new HookHandler.Command(
                                "printf 'writes are frozen' >&2; exit 2", Duration.ofSeconds(10))),
                        Optional.empty()));

        loop(model, hooks, permitAll()).respond("write a marker");

        // exit 2 走 stderr 路径，回填带 "<事件>:<工具> hook error: [<命令>]: " 前缀。
        assertTrue(toolResultAt(model, 1).startsWith("PreToolUse:bash hook error: ["),
                toolResultAt(model, 1));
        assertTrue(toolResultAt(model, 1).endsWith("writes are frozen"), toolResultAt(model, 1));
        assertFalse(java.nio.file.Files.exists(workingDirectory.resolve("marker.txt")));
    }

    private HookDispatcher hooks() {
        return new HookDispatcher("session-1", workingDirectory, "default", Map.of());
    }

    private static HookMatcher callback(Function<HookInput, HookOutput> body) {
        return new HookMatcher("*", List.of(new HookHandler.Callback(body)), Optional.empty());
    }

    private static PermissionGate permitAll() {
        return new PermissionGate(List.of(), (toolName, input, reason) -> {
            throw new AssertionError("unexpected approval request: " + toolName);
        });
    }

    private AgentLoop loop(FakeModelClient model, HookDispatcher hooks, PermissionGate gate)
            throws IOException {
        return loop(model, hooks, gate, new StringWriter());
    }

    private AgentLoop loop(
            FakeModelClient model, HookDispatcher hooks, PermissionGate gate, StringWriter terminal)
            throws IOException {
        return new AgentLoop(
                model,
                new ToolRegistry(
                        new BashTool(workingDirectory, Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(5)),
                        new Workspace(workingDirectory)),
                gate,
                hooks,
                "test-model",
                workingDirectory,
                new PrintWriter(terminal),
                new MarkdownRenderer(new PrintWriter(terminal), new DefaultMarkdownTheme()));
    }

    /**
     * 取某次请求里某条消息的纯文本内容
     *
     * @param model 假模型客户端
     * @param requestIndex 请求序号
     * @param messageIndex 消息序号
     * @return 文本内容
     */
    private static String userTextAt(FakeModelClient model, int requestIndex, int messageIndex) {
        return model.requests.get(requestIndex).messages().get(messageIndex).content().asString();
    }

    /**
     * 取某次请求里第一条 Tool Result 的内容
     *
     * @param model 假模型客户端
     * @param requestIndex 请求序号
     * @return Tool Result 内容
     */
    private static String toolResultAt(FakeModelClient model, int requestIndex) {
        return model.requests.get(requestIndex).messages().stream()
                .map(message -> message.content())
                .filter(content -> content.blockParams().isPresent())
                .flatMap(content -> content.asBlockParams().stream())
                .map(block -> block.toolResult())
                .flatMap(Optional::stream)
                .map(ToolResultBlockParam::content)
                .flatMap(Optional::stream)
                .map(content -> content.asString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tool result in request " + requestIndex));
    }

    private static Message toolRequest(String command) {
        return message(StopReason.TOOL_USE, ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id("call-1")
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(Map.of("command", command)))
                .name("bash")
                .build()));
    }

    private static Message finalAnswer(String answer) {
        return message(StopReason.END_TURN, ContentBlock.ofText(TextBlock.builder()
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

    /**
     * 按写死顺序回放模型响应的假客户端
     *
     * <p>{@link #repeatingLast()} 让最后一条无限重复，用于测试循环上限——上限的意义正是
     * 「模型永远不改变行为时也要停下来」。
     */
    private static final class FakeModelClient implements ModelClient {

        private final List<Message> responses;
        private final List<MessageCreateParams> requests = new ArrayList<>();
        private boolean repeatLast;
        private int served;

        private FakeModelClient(Message... responses) {
            this.responses = List.of(responses);
        }

        private FakeModelClient repeatingLast() {
            this.repeatLast = true;
            return this;
        }

        @Override
        public Stream<ModelEvent> events(MessageCreateParams request) {
            requests.add(request);
            int index = served < responses.size()
                    ? served
                    : repeatLast ? responses.size() - 1 : -1;
            if (index < 0) {
                throw new AssertionError("unexpected extra model request #" + served);
            }
            served++;
            Message message = responses.get(index);
            List<ModelEvent> events = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                block.text()
                        .map(TextBlock::text)
                        .ifPresent(text -> events.add(new ModelEvent.TextDelta(text)));
            }
            events.add(new ModelEvent.MessageComplete(message));
            return events.stream();
        }
    }
}
