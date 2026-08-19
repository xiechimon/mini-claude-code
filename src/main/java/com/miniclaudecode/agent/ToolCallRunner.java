package com.miniclaudecode.agent;

import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.tool.ToolRegistry;
import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import com.miniclaudecode.tool.ToolRegistry.ToolInvocation;
import com.miniclaudecode.util.TextPreview;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 三条执行路径共用的工具调用执行与结果回填
 *
 * <p>ReAct、Plan-and-Execute、Multi-Agent 都必须经由 {@link ToolRegistry#executeTools} 才能拿到
 * 并发调度、审批拦截和结果顺序保证；这里只负责把 tool call 翻译成 invocation 并统一日志口径，
 * 不承担任何单条路径特有的展示
 */
public final class ToolCallRunner {

    private static final int RESULT_PREVIEW_LENGTH = 300;

    private ToolCallRunner() {
    }

    /**
     * @param log      调用方的 logger，保留按调用方类名过滤日志的能力
     * @param scope    日志作用域，如 {@code iteration=3}、{@code task step_1}、子 agent 名
     * @param onResult 每个结果的额外处理，可为 null；按 tool call 原顺序回调
     * @return 与 toolCalls 顺序一致的执行结果
     */
    public static List<ToolExecutionResult> execute(Logger log, String scope, ToolRegistry toolRegistry,
                                                    List<LlmClient.ToolCall> toolCalls,
                                                    Consumer<ToolExecutionResult> onResult) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("[{}] scheduling tool: {}", scope, toolName);
            log.debug("[{}] tool args [{}]: {}", scope, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("[{}] executing {} tool calls in parallel", scope, invocations.size());
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("[{}] tool result preview [{}]: {}", scope, result.name(),
                    TextPreview.of(result.result(), RESULT_PREVIEW_LENGTH));
            if (onResult != null) {
                onResult.accept(result);
            }
        }
        return results;
    }

    /**
     * 把返回图片的工具结果补成一条 user message 追加到目标历史
     *
     * <p>tool 角色消息只能携带文本，图片必须另起一条 user message 才会被多模态接口接受
     *
     * @param target 目标消息列表，ReAct 与 SubAgent 传自身 history，Plan 传单个任务的消息列表
     */
    public static void appendImageMessages(List<LlmClient.Message> target,
                                           List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text(
                    "工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            target.add(LlmClient.Message.user(parts));
        }
    }
}
