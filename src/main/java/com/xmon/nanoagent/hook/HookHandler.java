package com.xmon.nanoagent.hook;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 一个 hook 的执行体
 *
 * <p>对应 Claude Code 契约的 hook handler，五型全部录入：{@link Callback} 是 Agent SDK 里挂在
 * {@code options.hooks} 上的进程内回调；{@link Command} 是 {@code settings.json} 里
 * {@code type: "command"} 的外部进程；{@link Http}、{@link McpTool}、{@link Prompt}、{@link Agent}
 * 录名与字段但不执行。
 *
 * <p>未实现的四型建成携带真实字段的 record 而不是省略：{@link SettingsHooks} 读到
 * {@code type: "http"} 时会把它构造出来，{@link HookDispatcher} 再带着类型名拒绝。这样配置错了
 * 用户拿到的是一条指名道姓的错误，而不是一个从不触发的 hook。
 */
public sealed interface HookHandler {

    /** command / http / mcp_tool 型的契约默认超时。 */
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    /** {@code UserPromptSubmit} 事件上 command / http / mcp_tool 型的契约默认超时。 */
    Duration USER_PROMPT_SUBMIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 返回契约中的 {@code type} 取值
     *
     * @return 类型名
     */
    String contractType();

    /**
     * 进程内回调
     *
     * <p>对应 Agent SDK 的 {@code HookCallback}。契约签名还带 {@code toolUseID} 与
     * {@code AbortSignal}：前者已在 {@link HookInput.PreToolUse#toolUseId()} 里，
     * 后者需要可取消的执行上下文，本项目的 hook 同步执行、无可取消点。
     *
     * @param callback 收事件数据、返回判定的函数
     */
    record Callback(Function<HookInput, HookOutput> callback) implements HookHandler {

        /**
         * 校验进程内回调
         *
         * @param callback 回调函数
         */
        public Callback {
            Objects.requireNonNull(callback, "callback");
        }

        @Override
        public String contractType() {
            return "callback";
        }
    }

    /**
     * 外部进程
     *
     * <p>契约的传输面：事件数据 JSON 走 stdin，判定走 exit code 加 stdout。完整语义见
     * {@link CommandHookRunner}。
     *
     * @param command 交给 {@code sh -c} 的命令行。契约的 exec form（带 {@code args} 免 shell）未实现：
     *     它解决的是路径含空格与特殊字符的引用问题，与理解 hook 机制无关
     * @param timeout 超时。契约按型与事件给默认值，见 {@link #DEFAULT_TIMEOUT}
     */
    record Command(String command, Duration timeout) implements HookHandler {

        /**
         * 校验外部进程 hook
         *
         * @param command 命令行
         * @param timeout 超时
         */
        public Command {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive: " + timeout);
            }
        }

        @Override
        public String contractType() {
            return "command";
        }
    }

    /**
     * HTTP 端点。契约有，本课未实现
     *
     * @param url POST 目标
     * @param headers 附加请求头
     * @param allowedEnvVars 允许插值进请求头的环境变量名
     */
    record Http(String url, java.util.Map<String, String> headers, List<String> allowedEnvVars)
            implements HookHandler {

        @Override
        public String contractType() {
            return "http";
        }
    }

    /**
     * 已连接 MCP 服务器上的工具。契约有，本课未实现（MCP 是 s14）
     *
     * @param server 服务器名
     * @param tool 工具名
     * @param input 传给工具的参数
     */
    record McpTool(String server, String tool, java.util.Map<String, String> input)
            implements HookHandler {

        @Override
        public String contractType() {
            return "mcp_tool";
        }
    }

    /**
     * 交给模型单轮评估的提示词。契约有，本课未实现
     *
     * @param prompt 提示词，{@code $ARGUMENTS} 是事件数据 JSON 的占位符
     * @param model 评估用模型
     */
    record Prompt(String prompt, Optional<String> model) implements HookHandler {

        @Override
        public String contractType() {
            return "prompt";
        }
    }

    /**
     * 可用工具核查条件的子 Agent。契约有且标注实验性，本课未实现（子 Agent 是 s06）
     *
     * @param prompt 提示词
     * @param model 评估用模型
     */
    record Agent(String prompt, Optional<String> model) implements HookHandler {

        @Override
        public String contractType() {
            return "agent";
        }
    }
}
