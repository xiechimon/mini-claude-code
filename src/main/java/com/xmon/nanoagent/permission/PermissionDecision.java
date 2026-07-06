package com.xmon.nanoagent.permission;

import java.util.Objects;

/**
 * 一次权限判定的最终结果
 *
 * <p>与 Claude Code 契约的 {@code PermissionResult} 同形：只有允许和拒绝两态，「询问用户」在
 * {@link PermissionGate} 内部就已消解，不会出现在这里。{@link Deny} 强制携带原因，因为契约把
 * {@code message} 定为必填——它会作为 Tool Result 回填给模型，模型据此改换策略而不是重试同一次调用。
 *
 * <p>契约的允许分支还带 {@code updatedInput}（改写工具输入）与 {@code updatedPermissions}
 * （记住本次决定），本课均未实现。
 */
public sealed interface PermissionDecision {

    /**
     * 放行该次工具调用
     */
    record Allow() implements PermissionDecision {
    }

    /**
     * 拒绝该次工具调用
     *
     * @param message 拒绝原因，回填给模型
     */
    record Deny(String message) implements PermissionDecision {

        /**
         * 校验拒绝原因
         *
         * @param message 拒绝原因
         */
        public Deny {
            Objects.requireNonNull(message, "message");
        }
    }
}
