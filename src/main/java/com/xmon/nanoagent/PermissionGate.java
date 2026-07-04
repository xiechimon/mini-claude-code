package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在工具执行之前裁决一次工具调用
 *
 * <p>对应 Claude Code 契约里可注入的 {@code CanUseTool}：宿主自行完成询问，交回去的必须是已决的允许或拒绝。
 * 因此本类自带审批交互，{@link PermissionBehavior#ASK} 不会泄漏到返回值上。
 *
 * <p>不持有终端：拒绝原因随 {@link PermissionDecision.Deny} 返回，由调用方决定怎么呈现。这样端到端
 * 场景只需注入假审批器，无需伪造终端。
 */
final class PermissionGate {

    private final List<PermissionRule> rules;
    private final ApprovalPrompt approvalPrompt;

    /**
     * 创建权限闸门
     *
     * @param rules 按优先级排列的规则表
     * @param approvalPrompt 命中询问规则时使用的审批器
     */
    PermissionGate(List<PermissionRule> rules, ApprovalPrompt approvalPrompt) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.approvalPrompt = Objects.requireNonNull(approvalPrompt, "approvalPrompt");
    }

    /**
     * 裁决一次工具调用
     *
     * <p>规则表按序匹配，首个命中即决定结果；全部未命中时放行。未注册的工具名同样走完整个规则表。
     *
     * <p>判定自身失败时一律拒绝（fail-closed），并把底层异常写进拒绝原因。让它逃逸会杀死整个会话：
     * 此时对话历史里已有一个没有配对 Tool Result 的 Tool Call，下一次请求必被 API 拒绝，连在 REPL 层
     * 兜住都没有意义。这不是掩盖错误——原因同时回填给模型并打印给用户。
     *
     * @param mode 会话的权限模式
     * @param toolName 模型给出的工具名
     * @param input 模型给出的工具输入
     * @return 允许或拒绝
     * @throws UnsupportedOperationException 权限模式尚未实现
     */
    PermissionDecision check(PermissionMode mode, String toolName, JsonValue input) {
        requireImplemented(mode);
        for (PermissionRule rule : rules) {
            if (!rule.tools().contains(toolName)) {
                continue;
            }
            Optional<String> reason;
            try {
                reason = rule.check().test(input);
            } catch (IOException failure) {
                return new PermissionDecision.Deny("Permission check failed: " + failure);
            }
            if (reason.isEmpty()) {
                continue;
            }
            return decide(rule.behavior(), toolName, input, reason.get());
        }
        return new PermissionDecision.Allow();
    }

    /**
     * 把命中规则的行为转成最终判定
     *
     * @param behavior 命中规则的行为
     * @param toolName 模型给出的工具名
     * @param input 模型给出的工具输入
     * @param reason 命中原因
     * @return 允许或拒绝
     */
    private PermissionDecision decide(
            PermissionBehavior behavior, String toolName, JsonValue input, String reason) {
        return switch (behavior) {
            case ALLOW -> new PermissionDecision.Allow();
            case DENY -> new PermissionDecision.Deny(reason);
            // 人否决必须和规则拒绝区分开：只回填命中原因的话，模型读到的是一句像建议的描述，
            // 看不出有人拒绝过，最自然的下一步就是重发同一次调用，于是再弹一次审批。
            case ASK -> approvalPrompt.approve(toolName, input, reason)
                    ? new PermissionDecision.Allow()
                    : new PermissionDecision.Deny("Denied by user: " + reason);
        };
    }

    /**
     * 拒绝尚未实现的权限模式
     *
     * <p>显式抛出而不是退化到 {@link PermissionMode#DEFAULT}：用默认行为顶替未实现的模式，会让一个
     * 本该更严格的模式静默地变宽松。
     *
     * @param mode 会话的权限模式
     * @throws UnsupportedOperationException 权限模式尚未实现
     */
    private static void requireImplemented(PermissionMode mode) {
        if (Objects.requireNonNull(mode, "mode") != PermissionMode.DEFAULT) {
            throw new UnsupportedOperationException(
                    "PermissionMode not implemented: " + mode.contractValue());
        }
    }
}
