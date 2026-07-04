package com.xmon.nanoagent;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 处理模型对话和工具调用
 */
final class AgentLoop {

    private static final long MAX_TOKENS = 8_000L;
    private static final int MAX_PREVIEW_CODE_POINTS = 200;
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    /** 会话的权限模式。本课没有改写它的入口，恒为契约的默认模式。 */
    private static final PermissionMode SESSION_MODE = PermissionMode.DEFAULT;

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final PermissionGate permissionGate;
    private final String modelId;
    private final String systemPrompt;
    private final PrintWriter terminal;
    private final List<MessageParam> history = new ArrayList<>();

    /**
     * 创建智能体循环
     *
     * @param modelClient 模型客户端
     * @param toolRegistry 工具注册表
     * @param permissionGate 工具执行前的权限闸门
     * @param modelId 模型标识
     * @param workingDirectory 工作目录
     * @param terminal 终端输出
     */
    AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            PermissionGate permissionGate,
            String modelId,
            Path workingDirectory,
            PrintWriter terminal) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.permissionGate = Objects.requireNonNull(permissionGate);
        this.modelId = Objects.requireNonNull(modelId);
        this.systemPrompt = "You are a coding agent at " + Objects.requireNonNull(workingDirectory)
                + ". Use tools to solve tasks. Act, don't explain.";
        this.terminal = Objects.requireNonNull(terminal);
    }

    /**
     * 处理一轮用户输入，直到模型返回最终文本
     *
     * @param rawInput 用户原始输入
     * @return 模型返回的文本块
     * @throws InterruptedException 工具执行被中断
     */
    List<String> respond(String rawInput) throws InterruptedException {
        // 将用户信息添加到 history
        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(rawInput)
                .build());

        // ReAct 循环
        while (true) {
            Message response = modelClient.create(createRequest());
            history.add(response.toParam());

            // 如果模型没有调用工具，则返回文本内容
            if (response.stopReason().filter(StopReason.TOOL_USE::equals).isEmpty()) {
                return response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(text -> text.text())
                        .toList();
            }

            // 如果模型调用了工具，则按工具名查表执行并将结果添加到 history
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.toolUse().isEmpty()) {
                    continue;
                }
                ToolUseBlock toolUse = block.toolUse().orElseThrow();

                // 先打印工具名再判权限：用户在被问到之前就看得到模型想做什么。
                writeLine(YELLOW + "> " + toolUse.name() + RESET);
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
        writeLine(prefixByCodePoint(output, MAX_PREVIEW_CODE_POINTS));
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
     * 按 Unicode 码点截取文本前缀
     *
     * @param value 原始文本
     * @param maximumCodePoints 最大码点数
     * @return 文本前缀
     */
    private static String prefixByCodePoint(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
