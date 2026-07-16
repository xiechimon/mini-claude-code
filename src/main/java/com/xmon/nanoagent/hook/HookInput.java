package com.xmon.nanoagent.hook;

import com.anthropic.core.JsonValue;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次 hook 触发携带的事件数据
 *
 * <p>对应 Claude Code 契约的 {@code HookInput}——一个按 {@code hook_event_name} 判别的联合。
 * 契约有 31 个分支，本课只建已接上触发点的 4 个；其余 27 个的事件名由 {@link HookEvent} 全量录入，
 * 不建空壳 record。record 的字段是行为承诺，不是名字：建一个所有字段都没人填的 record，读者无法
 * 从类型上区分「没实现」和「实现了但没数据」。
 *
 * <p>{@link Base} 是契约 {@code BaseHookInput} 的公共字段。缺 {@code transcript_path}，
 * 理由见 {@link Base#transcriptPath()}。
 *
 * <p>本接口刻意不 import {@code core} 包的类型：{@link Stop#lastAssistantMessage()} 收
 * {@code String} 而不是 {@code Message}，让依赖方向保持 {@code core → hook} 单向。
 */
public sealed interface HookInput {

    /**
     * 返回该次触发的事件
     *
     * @return 事件
     */
    HookEvent event();

    /**
     * 返回公共字段
     *
     * @return 公共字段
     */
    Base base();

    /**
     * 契约 {@code BaseHookInput} 的公共字段
     *
     * <p>缺三个契约字段，各有理由：
     *
     * <ul>
     *   <li>{@code transcript_path}——契约标必填，本项目不把对话历史落盘（那是 s08 压缩与 s09 记忆
     *       的地基）。**省略而不是发一个假路径**：hook 脚本会去读它然后拿到 FileNotFound，
     *       把「未实现」伪装成「实现了但坏了」。
     *   <li>{@code prompt_id}——需要按提示词划分回合并生成 UUID，本项目没有回合标识。
     *   <li>{@code agent_id} / {@code agent_type} / {@code effort}——分别属于 s06 子 Agent 与
     *       推理强度参数，本项目都没有。
     * </ul>
     *
     * @param sessionId 会话标识。一次进程等于一次会话，启动时生成一个 UUID
     * @param cwd 触发时的工作目录
     * @param permissionMode 会话的权限模式，取契约原值
     */
    record Base(String sessionId, String cwd, String permissionMode) {

        /**
         * 校验公共字段
         *
         * @param sessionId 会话标识
         * @param cwd 工作目录
         * @param permissionMode 权限模式
         */
        public Base {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(permissionMode, "permissionMode");
        }

        /**
         * 返回对话历史文件路径
         *
         * <p>恒为空：本项目不落盘 transcript。见本类文档。
         *
         * @return 恒为空
         */
        public Optional<String> transcriptPath() {
            return Optional.empty();
        }
    }

    /**
     * 工具执行前
     *
     * @param base 公共字段
     * @param toolName 模型给出的工具名，也是 matcher 求值的目标字段
     * @param toolInput 模型给出的工具输入
     * @param toolUseId 工具调用标识，用于回填 Tool Result
     */
    record PreToolUse(Base base, String toolName, JsonValue toolInput, String toolUseId)
            implements HookInput {

        /**
         * 校验工具执行前事件
         *
         * @param base 公共字段
         * @param toolName 工具名
         * @param toolInput 工具输入
         * @param toolUseId 工具调用标识
         */
        public PreToolUse {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(toolInput, "toolInput");
            Objects.requireNonNull(toolUseId, "toolUseId");
        }

        @Override
        public HookEvent event() {
            return HookEvent.PRE_TOOL_USE;
        }
    }

    /**
     * 工具执行后
     *
     * <p>权限被拒绝时不触发：工具没跑，契约把「跑过」作为本事件的前提。
     *
     * @param base 公共字段
     * @param toolName 模型给出的工具名，也是 matcher 求值的目标字段
     * @param toolInput 模型给出的工具输入
     * @param toolResponse 工具返回值
     * @param toolUseId 工具调用标识
     */
    record PostToolUse(
            Base base, String toolName, JsonValue toolInput, String toolResponse, String toolUseId)
            implements HookInput {

        /**
         * 校验工具执行后事件
         *
         * @param base 公共字段
         * @param toolName 工具名
         * @param toolInput 工具输入
         * @param toolResponse 工具返回值
         * @param toolUseId 工具调用标识
         */
        public PostToolUse {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(toolInput, "toolInput");
            Objects.requireNonNull(toolResponse, "toolResponse");
            Objects.requireNonNull(toolUseId, "toolUseId");
        }

        @Override
        public HookEvent event() {
            return HookEvent.POST_TOOL_USE;
        }
    }

    /**
     * 用户提示词提交后、进入模型之前
     *
     * @param base 公共字段
     * @param prompt 用户原始输入
     */
    record UserPromptSubmit(Base base, String prompt) implements HookInput {

        /**
         * 校验提示词提交事件
         *
         * @param base 公共字段
         * @param prompt 用户原始输入
         */
        public UserPromptSubmit {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(prompt, "prompt");
        }

        @Override
        public HookEvent event() {
            return HookEvent.USER_PROMPT_SUBMIT;
        }
    }

    /**
     * 模型即将停止时
     *
     * @param base 公共字段
     * @param stopHookActive 本轮是否由上一次 Stop hook 阻止停止而续跑。hook 据此避免无限续跑
     * @param lastAssistantMessage 停止前最后一条 assistant 消息的文本，空文本表示该轮没有文本块
     */
    record Stop(Base base, boolean stopHookActive, String lastAssistantMessage)
            implements HookInput {

        /**
         * 校验停止事件
         *
         * @param base 公共字段
         * @param stopHookActive 是否处于续跑中
         * @param lastAssistantMessage 最后一条 assistant 文本
         */
        public Stop {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(lastAssistantMessage, "lastAssistantMessage");
        }

        @Override
        public HookEvent event() {
            return HookEvent.STOP;
        }
    }
}
