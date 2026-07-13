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
 */
public final class AgentLoop {

    private static final long MAX_TOKENS = 8_000L;
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String DARK = "\033[2m";
    private static final String RESET = "\033[0m";

    /** 会话的权限模式。本课没有改写它的入口，恒为契约的默认模式。 */
    private static final PermissionMode SESSION_MODE = PermissionMode.DEFAULT;

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final PermissionGate permissionGate;
    private final String modelId;
    private final String systemPrompt;
    private final PrintWriter terminal;
    private final MarkdownRenderer markdown;
    private final List<MessageParam> history = new ArrayList<>();

    /** 中断标记：信号线程设 true 后主循环在消费事件流时检查并抛出 InterruptedException */
    private volatile boolean interrupted;

    /**
     * 创建智能体循环
     *
     * @param modelClient 模型客户端
     * @param toolRegistry 工具注册表
     * @param permissionGate 工具执行前的权限闸门
     * @param modelId 模型标识
     * @param workingDirectory 工作目录
     * @param terminal 终端输出
     * @param markdown  markdown 渲染器
     */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            PermissionGate permissionGate,
            String modelId,
            Path workingDirectory,
            PrintWriter terminal,
            MarkdownRenderer markdown) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.permissionGate = Objects.requireNonNull(permissionGate);
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
        // 将用户信息添加到 history
        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(rawInput)
                .build());

        // ReAct 循环
        while (true) {
            Message response = receive();
            history.add(response.toParam());

            // 如果模型没有调用工具，文本已逐字打印，直接结束。
            if (response.stopReason().filter(StopReason.TOOL_USE::equals).isEmpty()) {
                return;
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

            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(results))
                    .build());
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
     * 先裁决权限，通过后才执行工具
     *
     * <p>被拒绝的调用不执行、不打印输出预览，但仍要产出一条 Tool Result 让循环状态保持一致——静默跳过
     * 会让模型重复发起同一次不安全调用。回填的是拒绝原因本身而非固定文案，模型据此改换策略。
     *
     * @param toolUse 模型发起的工具调用
     * @return 工具返回值，被拒绝时为拒绝原因
     * @throws InterruptedException 工具执行被中断
     */
    private String permit(ToolUseBlock toolUse) throws InterruptedException {
        PermissionDecision decision =
                permissionGate.check(SESSION_MODE, toolUse.name(), toolUse._input());
        if (decision instanceof PermissionDecision.Deny denied) {
            writeLine(RED + "[blocked] " + denied.message() + RESET);
            return denied.message();
        }
        String output = execute(toolUse);
        writeLine(output);
        return output;
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
}
