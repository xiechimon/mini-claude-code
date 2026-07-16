package com.xmon.nanoagent.core;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xmon.nanoagent.hook.HookDispatcher;
import com.xmon.nanoagent.permission.PermissionDecision;
import com.xmon.nanoagent.permission.PermissionGate;
import com.xmon.nanoagent.permission.PermissionMode;
import com.xmon.nanoagent.tool.ToolHandler;
import com.xmon.nanoagent.tool.ToolRegistry;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 处理模型对话和工具调用
 *
 * <p>循环本身不含任何扩展逻辑：四个 hook 触发点（提示词提交、工具执行前、工具执行后、即将停止）
 * 只调用 {@link HookDispatcher}，跑什么由注册表决定。
 */
public final class AgentLoop {

    private static final long MAX_TOKENS = 8_000L;
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String DARK = "\033[2m";
    private static final String RESET = "\033[0m";

    /** 会话的权限模式。本课没有改写它的入口，恒为契约的默认模式。 */
    private static final PermissionMode SESSION_MODE = PermissionMode.DEFAULT;

    /**
     * 一个回合内 Stop hook 阻止停止的次数上限，取自契约的 8 次。
     *
     * <p>计数在回合内只增不减，<b>期间出现工具调用也不归零</b>。归零的读法会让上限失效：一个说
     * 「先跑测试」的 Stop hook 每次都能拿到一轮工具调用，计数永远回到 0，循环无界。
     * 契约只说「8 次连续续跑」，没定义工具调用是否打断连续性，这里取能真正兜住的那个读法。
     */
    private static final int MAX_STOP_CONTINUATIONS = 8;

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final PermissionGate permissionGate;
    private final HookDispatcher hooks;
    private final String modelId;
    private final String systemPrompt;
    private final PrintWriter terminal;
    private final MarkdownRenderer markdown;
    private final List<MessageParam> history = new ArrayList<>();

    /** 中断标记：信号线程设 true 后主循环在消费事件流时检查并抛出 InterruptedException */
    private volatile boolean interrupted;

    /** 契约的 {@code continue: false}：某个 hook 要求整个会话停止处理，压过一切事件专属判定。 */
    private boolean halted;

    /**
     * 创建智能体循环
     *
     * @param modelClient 模型客户端
     * @param toolRegistry 工具注册表
     * @param permissionGate 工具执行前的权限闸门
     * @param hooks hook 分发器
     * @param modelId 模型标识
     * @param workingDirectory 工作目录
     * @param terminal 终端输出
     * @param markdown  markdown 渲染器
     */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            PermissionGate permissionGate,
            HookDispatcher hooks,
            String modelId,
            Path workingDirectory,
            PrintWriter terminal,
            MarkdownRenderer markdown) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.permissionGate = Objects.requireNonNull(permissionGate);
        this.hooks = Objects.requireNonNull(hooks);
        this.modelId = Objects.requireNonNull(modelId);
        this.systemPrompt = "You are a coding agent at " + Objects.requireNonNull(workingDirectory)
                + ". Use tools to solve tasks. Act, don't explain.";
        this.terminal = Objects.requireNonNull(terminal);
        this.markdown = Objects.requireNonNull(markdown);
    }

    /**
     * 处理一轮用户输入，直到模型返回最终文本
     *
     * <p>模型文本增量在 {@link ModelEvent.TextDelta} 到达时逐字打印，
     * 工具名在 {@link ModelEvent.ToolUseStart} 到达时实时显示。
     *
     * @param rawInput 用户原始输入
     * @throws InterruptedException 工具执行被中断或模型流被信号打断
     */
    public void respond(String rawInput) throws InterruptedException {
        halted = false;

        // hook 在提示词进入 history 之前触发：被拦截的提示词不该留在上下文里。
        HookDispatcher.UserPromptSubmitVerdict submit = hooks.userPromptSubmit(rawInput);
        report(submit.notices());
        if (submit.notices().halt()) {
            return;
        }
        if (submit.blockReason().isPresent()) {
            // 拦截原因只给用户看，不进上下文——契约明确 "Not added to context"。
            writeLine(RED + "[blocked] " + submit.blockReason().orElseThrow()
                    + (submit.suppressOriginalPrompt() ? "" : ": " + rawInput) + RESET);
            return;
        }

        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(withHookContext(rawInput, "UserPromptSubmit", submit.additionalContext()))
                .build());

        // 本回合内 Stop hook 已阻止停止的次数，见 MAX_STOP_CONTINUATIONS。
        int stopContinuations = 0;

        // ReAct 循环
        while (true) {
            Message response = receive();
            history.add(response.toParam());

            // 如果模型没有调用工具，文本已逐字打印，交给 Stop hook 决定是否真的停下。
            if (response.stopReason().filter(StopReason.TOOL_USE::equals).isEmpty()) {
                HookDispatcher.StopVerdict stop =
                        hooks.stop(stopContinuations > 0, lastAssistantText(response));
                report(stop.notices());
                if (stop.notices().halt() || !stop.continues()) {
                    return;
                }
                if (stopContinuations >= MAX_STOP_CONTINUATIONS) {
                    writeLine(RED + "[hook] Stop hook 已连续阻止停止 " + MAX_STOP_CONTINUATIONS
                            + " 次，强制结束本回合" + RESET);
                    return;
                }
                stopContinuations++;
                history.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(stop.continuationMessage())
                        .build());
                continue;
            }

            // 如果模型调用了工具，则按工具名查表执行并将结果添加到 history
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.toolUse().isEmpty()) {
                    continue;
                }
                ToolUseBlock toolUse = block.toolUse().orElseThrow();

                // 工具名已在 ToolUseStart 中显示，这里格式化参数并执行。
                displayToolInput(toolUse);
                String output = permit(toolUse);

                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(output)
                        .build()));
            }

            // 即使 halted 也要把结果写回 history：留下没有配对 Tool Result 的 Tool Call，
            // 下一个回合的第一次请求必被 API 拒绝。
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(results))
                    .build());
            if (halted) {
                return;
            }
        }
    }

    /**
     * 消费一次模型响应的事件流，逐字打印文本、实时显示工具调用与思考，并返回完整消息
     *
     * @return 完整模型消息
     * @throws InterruptedException 被信号中断
     * @throws IllegalStateException 事件流未以 {@link ModelEvent.MessageComplete} 收尾
     */
    private Message receive() throws InterruptedException {
        interrupted = false;
        Message response = null;
        String lastBlockType = null;
        try (Stream<ModelEvent> events = modelClient.events(createRequest())) {
            for (var iterator = events.iterator(); iterator.hasNext(); ) {
                if (interrupted) {
                    throw new InterruptedException("interrupted by user");
                }
                ModelEvent event = iterator.next();
                switch (event) {
                    case ModelEvent.TextDelta text -> {
                        if (lastBlockType != null && !"text".equals(lastBlockType)) {
                            writeLine("");
                            markdown.flush();
                        }
                        markdown.append(text.text());
                        lastBlockType = "text";
                    }
                    case ModelEvent.ToolUseStart tool -> {
                        markdown.flush();
                        if (lastBlockType != null) {
                            writeLine("");
                        }
                        writeLine(YELLOW + "> " + tool.name() + RESET);
                        lastBlockType = "tool_use";
                    }
                    case ModelEvent.ToolUseDelta ignored -> { /* 静默累积，参数在 respond() 中格式化显示 */ }
                    case ModelEvent.ThinkingDelta think -> {
                        markdown.flush();
                        if (lastBlockType != null && !"thinking".equals(lastBlockType)) {
                            writeLine("");
                        }
                        writeText(DARK + think.thinking() + RESET);
                        lastBlockType = "thinking";
                    }
                    case ModelEvent.MessageComplete complete -> {
                        markdown.flush();
                        response = complete.message();
                    }
                }
            }
        }
        return Objects.requireNonNull(response, "event stream ended without MessageComplete");
    }

    /**
     * 中断当前正在进行的模型请求
     *
     * <p>信号线程安全：仅设 volatile 标记 + 调用 {@link ModelClient#cancel}，无锁。
     * 在 HashMap 到达的场景下，信号线程可能恰好没有看到 volatile 写入——但最坏情况
     * 只是下一轮 receive 才抛异常，不破坏正确性。
     */
    public void interrupt() {
        interrupted = true;
        modelClient.cancel();
    }

    /**
     * 依次经过 hook 与权限判定，通过后才执行工具，执行后再触发一次 hook
     *
     * <p>三层顺序照契约：{@code PreToolUse} hook 在权限判定<b>之前</b>。hook 与权限是两层而不是一层——
     * 契约里 hook 返回 {@code allow} 只跳过权限提示、不跳过拒绝规则，因此 hook 放行不等于权限放行。
     * 本课未实现「跳过提示」，但分层保住了，权限管线原样跑完。
     *
     * <p>被拒绝的调用不执行、不打印输出预览，但仍要产出一条 Tool Result 让循环状态保持一致——静默跳过
     * 会让模型重复发起同一次不安全调用。回填的是拒绝原因本身而非固定文案，模型据此改换策略。
     *
     * <p>{@code PostToolUse} 只在工具真的跑过之后触发：无论是 hook 拒绝还是权限拒绝，工具都没跑，
     * 契约把「跑过」作为该事件的前提。
     *
     * @param toolUse 模型发起的工具调用
     * @return 工具返回值，被拒绝时为拒绝原因
     * @throws InterruptedException 工具执行被中断
     */
    private String permit(ToolUseBlock toolUse) throws InterruptedException {
        if (halted) {
            return "Session halted by hook.";
        }

        HookDispatcher.PreToolUseVerdict pre =
                hooks.preToolUse(toolUse.name(), toolUse._input(), toolUse.id());
        report(pre.notices());
        if (pre.notices().halt()) {
            halted = true;
            return "Session halted by hook.";
        }
        if (pre.denyReason().isPresent()) {
            String reason = pre.denyReason().orElseThrow();
            writeLine(RED + "[blocked] " + reason + RESET);
            return withHookContext(reason, "PreToolUse", pre.additionalContext());
        }

        PermissionDecision decision =
                permissionGate.check(SESSION_MODE, toolUse.name(), toolUse._input());
        if (decision instanceof PermissionDecision.Deny denied) {
            writeLine(RED + "[blocked] " + denied.message() + RESET);
            return denied.message();
        }

        String output = execute(toolUse);

        HookDispatcher.PostToolUseVerdict post =
                hooks.postToolUse(toolUse.name(), toolUse._input(), output, toolUse.id());
        report(post.notices());
        if (post.notices().halt()) {
            halted = true;
        }
        // updatedToolOutput 先整体替换，再把告警与上下文追加上去——契约把前者定义为「替换」、
        // 后者定义为「放在结果旁边」。
        String finalOutput = post.updatedToolOutput().orElse(output);
        List<String> appended = new ArrayList<>(post.additionalContext());
        post.blockReason().ifPresent(appended::add);
        finalOutput = withHookContext(finalOutput, "PostToolUse", appended);
        finalOutput = withHookContext(finalOutput, "PreToolUse", pre.additionalContext());

        writeLine(finalOutput);
        return finalOutput;
    }

    /**
     * 按工具名分发一次工具调用
     *
     * @param toolUse 模型发起的工具调用
     * @return 工具返回值，工具名未注册时为 {@code Unknown: } 加工具名
     * @throws InterruptedException 工具执行被中断
     */
    private String execute(ToolUseBlock toolUse) throws InterruptedException {
        Optional<ToolHandler> handler = toolRegistry.handler(toolUse.name());
        // 未注册的工具名不是错误：结果照常回填，循环继续。
        if (handler.isEmpty()) {
            return "Unknown: " + toolUse.name();
        }
        return handler.get().execute(toolUse._input());
    }

    /**
     * 创建包含完整对话历史的模型请求
     *
     * @return 模型请求参数
     */
    private MessageCreateParams createRequest() {
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(modelId)
                .system(systemPrompt)
                .messages(history)
                .maxTokens(MAX_TOKENS);
        toolRegistry.definitions().forEach(request::addTool);
        return request.build();
    }

    /**
     * 输出一行终端文本
     *
     * @param value 文本内容
     * @throws IllegalStateException 输出失败
     */
    private void writeLine(String value) {
        terminal.println(value);
        terminal.flush();
        if (terminal.checkError()) {
            throw new IllegalStateException("Unable to write terminal output");
        }
    }

    /**
     * 输出一段不换行的终端文本
     *
     * <p>文本增量逐段打印，不补换行：换行是模型输出内容的一部分，由增量本身携带。
     *
     * @param value 文本内容
     * @throws IllegalStateException 输出失败
     */
    private void writeText(String value) {
        terminal.print(value);
        terminal.flush();
        if (terminal.checkError()) {
            throw new IllegalStateException("Unable to write terminal output");
        }
    }

    /**
     * 格式化显示工具调用参数
     *
     * <p>将 {@link ToolUseBlock#_input()} 解码为 Map，逐字段以暗色样式显示，
     * 让用户看到的是可读的参数名和值，而不是原始 JSON 碎片。
     *
     * @param toolUse 模型发起的工具调用
     */
    private void displayToolInput(ToolUseBlock toolUse) {
        Map<String, Object> input = toolUse._input().convert(new TypeReference<>() {});
        for (var entry : input.entrySet()) {
            writeLine(DARK + "  " + entry.getKey() + ": " + entry.getValue() + RESET);
        }
    }

    /**
     * 把 hook 追加的上下文拼到一段文本后面
     *
     * <p>格式照抄真实 Claude Code 的可观测行为：{@code <事件名> hook additional context: <文本>}。
     * 契约说它被包进 system reminder 插入对话，本项目还没有 system reminder 机制（那是 s08 压缩与
     * s09 记忆的地基），因此退化为同一段文本里的一行前缀。
     *
     * @param text 原始文本
     * @param eventName 契约事件名
     * @param additionalContext hook 追加的上下文，可能来自多个 hook
     * @return 拼接后的文本，无追加上下文时原样返回
     */
    private static String withHookContext(
            String text, String eventName, List<String> additionalContext) {
        if (additionalContext.isEmpty()) {
            return text;
        }
        StringBuilder combined = new StringBuilder(text);
        for (String context : additionalContext) {
            combined.append("\n\n").append(eventName).append(" hook additional context: ")
                    .append(context);
        }
        return combined.toString();
    }

    /**
     * 呈现 hook 产出的用户可见通知
     *
     * <p>{@code systemMessage} 与 {@code stopReason} 都只给用户看，不进模型上下文——契约如此。
     * 不静默丢弃：hook 解析失败、超时、返回未实现的判定都走这个通道，丢掉它们等于让一个没生效的
     * 闸门看起来像生效了。
     *
     * @param notices hook 归并出的通知
     */
    private void report(HookDispatcher.Notices notices) {
        for (String warning : notices.warnings()) {
            writeLine(DARK + "[hook] " + warning + RESET);
        }
        if (notices.halt()) {
            writeLine(RED + "[hook] 会话被 hook 要求停止"
                    + notices.haltReason().map(reason -> "：" + reason).orElse("") + RESET);
        }
    }

    /**
     * 取一条模型消息里的文本内容
     *
     * @param message 模型消息
     * @return 全部文本块拼接后的文本，没有文本块时为空串
     */
    private static String lastAssistantText(Message message) {
        return message.content().stream()
                .map(ContentBlock::text)
                .flatMap(Optional::stream)
                .map(text -> text.text())
                .reduce((first, second) -> first + second)
                .orElse("");
    }
}
