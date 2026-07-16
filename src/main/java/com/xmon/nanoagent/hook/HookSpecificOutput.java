package com.xmon.nanoagent.hook;

import java.util.Objects;
import java.util.Optional;

/**
 * hook 针对特定事件返回的富判定
 *
 * <p>对应 Claude Code 契约的 {@code hookSpecificOutput}——一个按 {@code hookEventName} 判别的联合。
 * 契约有 20 个分支（有输出控制的那 20 个事件），本课只建已实现的 4 个。
 *
 * <p>逐事件建类型而不是共用一个大 record：契约本身就是这个形状，且类型系统能钉死错配——
 * {@code PostToolUse} 的 hook 返回 {@code permissionDecision} 是配置错误，不该编译通过。
 */
public sealed interface HookSpecificOutput {

    /**
     * 返回该判定所属的事件
     *
     * @return 事件
     */
    HookEvent event();

    /**
     * 工具执行前的判定
     *
     * @param decision 权限判定，本课只有 {@link HookPermissionDecision#DENY} 有可观察行为
     * @param reason 判定原因。{@code DENY} 时给模型看，其余取值时给用户看
     * @param additionalContext 追加到工具结果旁的上下文
     */
    record PreToolUse(
            Optional<HookPermissionDecision> decision,
            Optional<String> reason,
            Optional<String> additionalContext)
            implements HookSpecificOutput {

        /**
         * 校验工具执行前判定
         *
         * @param decision 权限判定
         * @param reason 判定原因
         * @param additionalContext 追加上下文
         */
        public PreToolUse {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(additionalContext, "additionalContext");
        }

        /**
         * 返回改写后的工具输入
         *
         * <p>恒为空。契约的 {@code updatedInput} 整体替换工具入参，要实现它得让权限管线对「模型给的
         * 输入」和「hook 改过的输入」分别判定——否则 hook 可以先通过权限判定再把参数换掉。
         * 那是 s15 信任边界的内容。
         *
         * @return 恒为空
         */
        public Optional<String> updatedInput() {
            return Optional.empty();
        }

        @Override
        public HookEvent event() {
            return HookEvent.PRE_TOOL_USE;
        }
    }

    /**
     * 工具执行后的判定
     *
     * @param additionalContext 追加到工具结果旁的上下文
     * @param updatedToolOutput 替换送给模型的工具输出。契约要求它匹配工具的输出形状，本项目的工具
     *     输出恒为字符串
     */
    record PostToolUse(Optional<String> additionalContext, Optional<String> updatedToolOutput)
            implements HookSpecificOutput {

        /**
         * 校验工具执行后判定
         *
         * @param additionalContext 追加上下文
         * @param updatedToolOutput 替换后的工具输出
         */
        public PostToolUse {
            Objects.requireNonNull(additionalContext, "additionalContext");
            Objects.requireNonNull(updatedToolOutput, "updatedToolOutput");
        }

        @Override
        public HookEvent event() {
            return HookEvent.POST_TOOL_USE;
        }
    }

    /**
     * 提示词提交后的判定
     *
     * @param additionalContext 与提示词并列送入模型的上下文
     * @param suppressOriginalPrompt 拦截提示词时，是否从给用户的拦截信息里省略原提示词
     */
    record UserPromptSubmit(Optional<String> additionalContext, boolean suppressOriginalPrompt)
            implements HookSpecificOutput {

        /**
         * 校验提示词提交判定
         *
         * @param additionalContext 追加上下文
         * @param suppressOriginalPrompt 是否省略原提示词
         */
        public UserPromptSubmit {
            Objects.requireNonNull(additionalContext, "additionalContext");
        }

        /**
         * 返回会话标题
         *
         * <p>恒为空：本项目没有会话标题的概念，没有可以设置的地方。
         *
         * @return 恒为空
         */
        public Optional<String> sessionTitle() {
            return Optional.empty();
        }

        @Override
        public HookEvent event() {
            return HookEvent.USER_PROMPT_SUBMIT;
        }
    }

    /**
     * 停止前的判定
     *
     * @param additionalContext 送给模型的非错误反馈。对话继续，但在终端标为 hook 反馈而非 hook 错误
     */
    record Stop(Optional<String> additionalContext) implements HookSpecificOutput {

        /**
         * 校验停止判定
         *
         * @param additionalContext 追加上下文
         */
        public Stop {
            Objects.requireNonNull(additionalContext, "additionalContext");
        }

        @Override
        public HookEvent event() {
            return HookEvent.STOP;
        }
    }
}
