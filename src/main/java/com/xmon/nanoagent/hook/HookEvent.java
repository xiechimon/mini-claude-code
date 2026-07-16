package com.xmon.nanoagent.hook;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Agent 生命周期上可挂载回调的事件
 *
 * <p>取值取自 Claude Code 契约的 {@code HOOK_EVENTS}，31 个全部录入。常量名按 Java 惯例书写，
 * 契约原值保存在 {@link #contractValue()} 里。
 *
 * <p>{@link #implemented()} 区分「录了名」与「接了行为」：本课只实现 {@link #PRE_TOOL_USE}、
 * {@link #POST_TOOL_USE}、{@link #USER_PROMPT_SUBMIT}、{@link #STOP} 四个触发点，其余 27 个在
 * {@link HookDispatcher#register} 与 {@link SettingsHooks} 里显式拒绝。拒绝而不静默忽略：
 * 静默忽略会让一条配置错的 hook 看起来像装好了，等到需要它拦住什么的时候才发现从没跑过。
 *
 * <p>{@link #matchable()} 记录该事件是否支持 {@code matcher}。契约对不支持的事件规定
 * 「静默忽略 matcher 字段」，本枚举把这个事实变成可查询的数据而不是散落的 if。
 */
public enum HookEvent {

    /** 工具执行前。可判定 allow / deny / ask / defer，可改写工具输入。 */
    PRE_TOOL_USE("PreToolUse", true, true),

    /** 工具执行后。可追加上下文，可改写工具输出。 */
    POST_TOOL_USE("PostToolUse", true, true),

    /** 工具执行失败后。本项目的工具把异常收敛成 {@code Error:} 字符串返回，此事件不可达。 */
    POST_TOOL_USE_FAILURE("PostToolUseFailure", false, true),

    /** 一批并行工具调用全部结束后，下一次模型请求之前，恰好触发一次。 */
    POST_TOOL_BATCH("PostToolBatch", false, false),

    /** 需要用户注意时。 */
    NOTIFICATION("Notification", false, true),

    /** 用户提示词提交后、进入模型之前。可拦截提示词，可注入上下文。 */
    USER_PROMPT_SUBMIT("UserPromptSubmit", true, false),

    /** slash command 或 MCP prompt 展开时。 */
    USER_PROMPT_EXPANSION("UserPromptExpansion", false, true),

    /** 会话开始或恢复时。 */
    SESSION_START("SessionStart", false, true),

    /** 会话结束时。 */
    SESSION_END("SessionEnd", false, true),

    /** 模型即将停止时。可阻止停止让对话继续。 */
    STOP("Stop", true, false),

    /** 模型请求以错误告终时。 */
    STOP_FAILURE("StopFailure", false, true),

    /** 子 Agent 启动时。 */
    SUBAGENT_START("SubagentStart", false, true),

    /** 子 Agent 即将停止时。 */
    SUBAGENT_STOP("SubagentStop", false, true),

    /** 上下文压缩之前。可阻止压缩。 */
    PRE_COMPACT("PreCompact", false, true),

    /** 上下文压缩之后。 */
    POST_COMPACT("PostCompact", false, true),

    /** 权限判定即将询问用户时。可代替用户作答。 */
    PERMISSION_REQUEST("PermissionRequest", false, true),

    /** 权限判定已经拒绝之后。可告知模型允许重试。 */
    PERMISSION_DENIED("PermissionDenied", false, true),

    /** 以 {@code --init-only} / {@code --init} / {@code --maintenance} 启动的一次性准备。 */
    SETUP("Setup", false, true),

    /** 队友进入空闲时。可阻止空闲让它继续工作。 */
    TEAMMATE_IDLE("TeammateIdle", false, false),

    /** 任务创建时。可回滚创建。 */
    TASK_CREATED("TaskCreated", false, false),

    /** 任务完成时。可阻止标记完成。 */
    TASK_COMPLETED("TaskCompleted", false, false),

    /** MCP 服务器请求用户输入时。可代替对话框作答。 */
    ELICITATION("Elicitation", false, true),

    /** 用户已回应 MCP 请求之后，回应送达服务器之前。 */
    ELICITATION_RESULT("ElicitationResult", false, true),

    /** 配置发生变化时。可阻止变更生效。 */
    CONFIG_CHANGE("ConfigChange", false, true),

    /** 创建 git worktree 时。stdout 即 worktree 路径。 */
    WORKTREE_CREATE("WorktreeCreate", false, false),

    /** 移除 git worktree 时。 */
    WORKTREE_REMOVE("WorktreeRemove", false, false),

    /** 指令文件（CLAUDE.md 等）载入时。 */
    INSTRUCTIONS_LOADED("InstructionsLoaded", false, true),

    /** 工作目录切换时。 */
    CWD_CHANGED("CwdChanged", false, false),

    /** 被监视的文件发生变化时。 */
    FILE_CHANGED("FileChanged", false, true),

    /** 目录被加入工作区时。 */
    DIRECTORY_ADDED("DirectoryAdded", false, true),

    /** assistant 消息流式输出时，每批完成行触发一次。仅影响显示。 */
    MESSAGE_DISPLAY("MessageDisplay", false, false);

    private final String contractValue;
    private final boolean implemented;
    private final boolean matchable;

    HookEvent(String contractValue, boolean implemented, boolean matchable) {
        this.contractValue = contractValue;
        this.implemented = implemented;
        this.matchable = matchable;
    }

    /**
     * 返回契约中的原始事件名
     *
     * @return 与 {@code HOOK_EVENTS} 逐字相同的字符串
     */
    public String contractValue() {
        return contractValue;
    }

    /**
     * 判断本课是否已接上该事件的触发点
     *
     * @return 已实现时为 {@code true}
     */
    public boolean implemented() {
        return implemented;
    }

    /**
     * 判断该事件是否支持 {@code matcher} 过滤
     *
     * <p>契约规定在不支持的事件上写 {@code matcher} 时静默忽略，因此本方法返回 {@code false}
     * 不是错误条件，只是让 {@link HookMatcher} 知道该跳过求值。
     *
     * @return 支持 matcher 时为 {@code true}
     */
    public boolean matchable() {
        return matchable;
    }

    /**
     * 按契约事件名查找事件
     *
     * @param contractValue 契约事件名，大小写敏感
     * @return 匹配的事件，无匹配时为空
     */
    public static Optional<HookEvent> fromContractValue(String contractValue) {
        return Stream.of(values())
                .filter(event -> event.contractValue.equals(contractValue))
                .findFirst();
    }

    /**
     * 返回契约事件名，供异常与日志使用
     *
     * @return 契约事件名
     */
    @Override
    public String toString() {
        return contractValue;
    }

    /**
     * 拒绝尚未接上触发点的事件
     *
     * <p>显式抛出而不是静默忽略：一条挂在未实现事件上的 hook 永远不会跑，静默忽略会让它看起来
     * 像装好了，直到需要它拦住什么的时候才发现从来没生效。
     *
     * @throws UnsupportedOperationException 该事件尚未实现
     */
    void requireImplemented() {
        if (!implemented) {
            throw new UnsupportedOperationException(
                    "HookEvent not implemented: " + contractValue
                            + " (implemented: "
                            + Stream.of(values())
                                    .filter(HookEvent::implemented)
                                    .map(HookEvent::contractValue)
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("")
                            + ")");
        }
    }
}
