package com.xmon.nanoagent.permission;

import com.anthropic.core.JsonValue;

/**
 * 向用户征询一次工具调用的批准
 *
 * <p>做成可注入的接缝而非直读标准输入，是为了让端到端场景能够自动化：审批一旦写死成阻塞式终端读取，
 * 涉及询问的路径就再也跑不进测试。
 *
 * <p>契约在 {@code CanUseTool} 的入参里还提供 {@code blockedPath}、{@code title}、{@code displayName}
 * 等富 UI 字段，本课只认领相当于 {@code decisionReason} 的 {@code reason}。
 */
@FunctionalInterface
public interface ApprovalPrompt {

    /**
     * 询问用户是否批准该次工具调用
     *
     * @param toolName 模型给出的工具名
     * @param input 模型给出的工具输入
     * @param reason 命中规则给出的询问原因
     * @return 用户批准时为 {@code true}
     */
    boolean approve(String toolName, JsonValue input, String reason);
}
