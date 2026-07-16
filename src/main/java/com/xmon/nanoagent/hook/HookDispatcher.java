package com.xmon.nanoagent.hook;

import com.anthropic.core.JsonValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * hook 注册表与逐事件的触发、归并
 *
 * <p>这是「挂在循环上，不写进循环里」的落点：{@code AgentLoop} 只在四个位置调用本类的四个方法，
 * 具体跑什么由注册表决定。
 *
 * <p><b>全跑而不短路。</b>匹配上的 handler 全部执行，收齐判定再归并。课程参考解法用的是「首个非空返回
 * 即短路」，那会让注册在业务 hook 之后的审计 hook 在最需要它的时候不执行——权限一拒绝，日志就不打了。
 * 契约的语义是全跑加优先级归并，本实现照契约走。
 *
 * <p><b>顺序执行而不并行。</b>契约说匹配的 hook 并行跑，本实现按注册顺序串行跑。差异只在耗时，不在
 * 语义：「全跑」这个可观察性质保住了，而并行需要一个执行器与结果收集，对理解 hook 机制没有增量。
 * 由此带来的一个后果：契约里未定义的「多个 hook 都改写同一样东西」在本实现里有确定答案（后注册的胜出），
 * 逐处已在方法文档里标出。
 *
 * <p><b>{@code continue: false} 优先于一切。</b>契约规定它压过任何事件专属判定，本实现在归并时先看它。
 */
public final class HookDispatcher {

    private final Map<HookEvent, List<HookMatcher>> registry = new EnumMap<>(HookEvent.class);
    private final CommandHookRunner commandRunner;
    private final HookInput.Base base;

    /**
     * 创建 hook 分发器
     *
     * @param sessionId 会话标识，一次进程等于一次会话
     * @param workingDirectory 工作目录，同时作为 hook 子进程的工作目录
     * @param permissionMode 会话的权限模式，取契约原值
     * @param environment hook 子进程的环境变量
     */
    public HookDispatcher(
            String sessionId,
            Path workingDirectory,
            String permissionMode,
            Map<String, String> environment) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.commandRunner = new CommandHookRunner(workingDirectory, environment);
        this.base = new HookInput.Base(sessionId, workingDirectory.toString(), permissionMode);
    }

    /**
     * 注册一组 hook
     *
     * @param event 挂载的事件
     * @param matcher 触发条件与 handler
     * @throws UnsupportedOperationException 该事件尚未接上触发点
     */
    public void register(HookEvent event, HookMatcher matcher) {
        Objects.requireNonNull(event, "event").requireImplemented();
        registry.computeIfAbsent(event, key -> new ArrayList<>())
                .add(Objects.requireNonNull(matcher, "matcher"));
    }

    /**
     * 批量注册，用于合并配置文件读出的 hook
     *
     * @param hooks 事件到 matcher 组的映射
     * @throws UnsupportedOperationException 其中任一事件尚未接上触发点
     */
    public void registerAll(Map<HookEvent, List<HookMatcher>> hooks) {
        Objects.requireNonNull(hooks, "hooks")
                .forEach((event, matchers) -> matchers.forEach(matcher -> register(event, matcher)));
    }

    /**
     * 触发工具执行前的 hook
     *
     * <p>归并按契约的 {@code deny > defer > ask > allow} 优先级。本课只实现 {@code deny}：
     * 其余三个取值命中时不改变控制流，但会产出一条用户可见警告——静默忽略一个 {@code defer} 会让
     * 「未实现」看起来像「放行」。
     *
     * <p>多个 hook 都拒绝时取最先注册的那条原因。契约未规定此情形。
     *
     * @param toolName 模型给出的工具名，也是 matcher 求值的目标
     * @param toolInput 模型给出的工具输入
     * @param toolUseId 工具调用标识
     * @return 归并后的判定
     * @throws InterruptedException 等待 hook 子进程时被中断
     */
    public PreToolUseVerdict preToolUse(String toolName, JsonValue toolInput, String toolUseId)
            throws InterruptedException {
        HookInput input = new HookInput.PreToolUse(base, toolName, toolInput, toolUseId);
        List<HookOutput.Sync> outputs = triggerAll(input, toolName);

        List<String> warnings = new ArrayList<>();
        List<String> additionalContext = new ArrayList<>();
        HookPermissionDecision merged = null;
        String mergedReason = null;

        for (HookOutput.Sync output : outputs) {
            output.systemMessage().ifPresent(warnings::add);
            output.specificAs(HookSpecificOutput.PreToolUse.class)
                    .flatMap(HookSpecificOutput.PreToolUse::additionalContext)
                    .ifPresent(additionalContext::add);

            Optional<HookPermissionDecision> decision = output.specificAs(
                            HookSpecificOutput.PreToolUse.class)
                    .flatMap(HookSpecificOutput.PreToolUse::decision);
            // exit 2 与顶层 decision: block 的效果等同于 deny，契约明文。
            if (output.blocked()) {
                decision = Optional.of(HookPermissionDecision.DENY);
            }
            if (decision.isEmpty()) {
                continue;
            }
            // 契约的 deny > defer > ask > allow：优先级更高者胜出，同级时先注册者胜出。
            if (merged == null || decision.get().precedence() < merged.precedence()) {
                merged = decision.get();
                mergedReason = reasonOf(output, "denied by hook");
            }
        }

        Optional<String> denyReason = Optional.empty();
        if (merged == HookPermissionDecision.DENY) {
            denyReason = Optional.of(mergedReason);
        } else if (merged != null) {
            // 静默忽略一个未实现的判定会让「未实现」看起来像「放行」，必须留下痕迹。
            warnings.add("hook returned permissionDecision \"" + merged.contractValue()
                    + "\", which is not implemented; the tool call proceeds");
        }
        return new PreToolUseVerdict(notices(outputs, warnings), denyReason, List.copyOf(additionalContext));
    }

    /**
     * 触发工具执行后的 hook
     *
     * <p>权限被拒绝的调用不该走到这里：工具没跑，契约把「跑过」作为本事件的前提。
     *
     * <p>多个 hook 都设置 {@code updatedToolOutput} 时后注册的胜出。契约未规定此情形。
     *
     * @param toolName 模型给出的工具名，也是 matcher 求值的目标
     * @param toolInput 模型给出的工具输入
     * @param toolResponse 工具返回值
     * @param toolUseId 工具调用标识
     * @return 归并后的判定
     * @throws InterruptedException 等待 hook 子进程时被中断
     */
    public PostToolUseVerdict postToolUse(
            String toolName, JsonValue toolInput, String toolResponse, String toolUseId)
            throws InterruptedException {
        HookInput input =
                new HookInput.PostToolUse(base, toolName, toolInput, toolResponse, toolUseId);
        List<HookOutput.Sync> outputs = triggerAll(input, toolName);

        List<String> warnings = new ArrayList<>();
        List<String> additionalContext = new ArrayList<>();
        Optional<String> blockReason = Optional.empty();
        Optional<String> updatedToolOutput = Optional.empty();

        for (HookOutput.Sync output : outputs) {
            output.systemMessage().ifPresent(warnings::add);
            Optional<HookSpecificOutput.PostToolUse> specific =
                    output.specificAs(HookSpecificOutput.PostToolUse.class);
            specific.flatMap(HookSpecificOutput.PostToolUse::additionalContext)
                    .ifPresent(additionalContext::add);
            Optional<String> replacement =
                    specific.flatMap(HookSpecificOutput.PostToolUse::updatedToolOutput);
            if (replacement.isPresent()) {
                updatedToolOutput = replacement;
            }
            if (output.blocked() && blockReason.isEmpty()) {
                blockReason = Optional.of(reasonOf(output, "flagged by hook"));
            }
        }
        return new PostToolUseVerdict(
                notices(outputs, warnings),
                blockReason,
                updatedToolOutput,
                List.copyOf(additionalContext));
    }

    /**
     * 触发提示词提交后的 hook
     *
     * <p>{@code decision: "block"} 时提示词不进对话历史，原因只给用户看不进上下文——契约明确
     * 「Not added to context」。
     *
     * @param prompt 用户原始输入
     * @return 归并后的判定
     * @throws InterruptedException 等待 hook 子进程时被中断
     */
    public UserPromptSubmitVerdict userPromptSubmit(String prompt) throws InterruptedException {
        HookInput input = new HookInput.UserPromptSubmit(base, prompt);
        // 本事件不支持 matcher，传空串即可：HookMatcher 在事件不支持时不读该参数。
        List<HookOutput.Sync> outputs = triggerAll(input, "");

        List<String> warnings = new ArrayList<>();
        List<String> additionalContext = new ArrayList<>();
        Optional<String> blockReason = Optional.empty();
        boolean suppressOriginalPrompt = false;

        for (HookOutput.Sync output : outputs) {
            output.systemMessage().ifPresent(warnings::add);
            Optional<HookSpecificOutput.UserPromptSubmit> specific =
                    output.specificAs(HookSpecificOutput.UserPromptSubmit.class);
            specific.flatMap(HookSpecificOutput.UserPromptSubmit::additionalContext)
                    .ifPresent(additionalContext::add);
            suppressOriginalPrompt |= specific
                    .map(HookSpecificOutput.UserPromptSubmit::suppressOriginalPrompt)
                    .orElse(false);
            if (output.blocked() && blockReason.isEmpty()) {
                blockReason = Optional.of(reasonOf(output, "prompt blocked by hook"));
            }
        }
        return new UserPromptSubmitVerdict(
                notices(outputs, warnings),
                blockReason,
                suppressOriginalPrompt,
                List.copyOf(additionalContext));
    }

    /**
     * 触发模型即将停止时的 hook
     *
     * <p>{@code decision: "block"} 与 {@code additionalContext} 都让对话继续，区别只在呈现：
     * 前者是 hook 错误，后者是 hook 反馈。两者都受 {@code stopHookActive} 与调用方的连续续跑上限约束。
     *
     * @param stopHookActive 本轮是否由上一次 Stop hook 阻止停止而续跑
     * @param lastAssistantMessage 停止前最后一条 assistant 消息的文本
     * @return 归并后的判定
     * @throws InterruptedException 等待 hook 子进程时被中断
     */
    public StopVerdict stop(boolean stopHookActive, String lastAssistantMessage)
            throws InterruptedException {
        HookInput input = new HookInput.Stop(base, stopHookActive, lastAssistantMessage);
        List<HookOutput.Sync> outputs = triggerAll(input, "");

        List<String> warnings = new ArrayList<>();
        List<String> additionalContext = new ArrayList<>();
        Optional<String> blockReason = Optional.empty();

        for (HookOutput.Sync output : outputs) {
            output.systemMessage().ifPresent(warnings::add);
            output.specificAs(HookSpecificOutput.Stop.class)
                    .flatMap(HookSpecificOutput.Stop::additionalContext)
                    .ifPresent(additionalContext::add);
            if (output.blocked() && blockReason.isEmpty()) {
                blockReason = Optional.of(reasonOf(output, "stop blocked by hook"));
            }
        }
        return new StopVerdict(
                notices(outputs, warnings), blockReason, List.copyOf(additionalContext));
    }

    /**
     * 执行该事件下所有命中的 handler
     *
     * @param input 事件数据
     * @param matcherValue matcher 求值的目标取值
     * @return 各 handler 的判定，按注册顺序
     * @throws InterruptedException 等待 hook 子进程时被中断
     */
    private List<HookOutput.Sync> triggerAll(HookInput input, String matcherValue)
            throws InterruptedException {
        List<HookOutput.Sync> outputs = new ArrayList<>();
        for (HookMatcher matcher : registry.getOrDefault(input.event(), List.of())) {
            if (!matcher.matches(input.event(), matcherValue)) {
                continue;
            }
            for (HookHandler handler : matcher.handlers()) {
                outputs.add(run(handler, input, matcher));
            }
        }
        return outputs;
    }

    /**
     * 执行单个 handler
     *
     * @param handler 待执行的 handler
     * @param input 事件数据
     * @param matcher 所属的 matcher 组，用于取组级超时
     * @return 该 handler 的判定
     * @throws InterruptedException 等待 hook 子进程时被中断
     * @throws UnsupportedOperationException handler 类型尚未实现
     */
    private HookOutput.Sync run(HookHandler handler, HookInput input, HookMatcher matcher)
            throws InterruptedException {
        return switch (handler) {
            case HookHandler.Callback callback -> {
                HookOutput output = callback.callback().apply(input);
                if (output instanceof HookOutput.Sync sync) {
                    yield sync;
                }
                // 后台执行需要任务生命周期管理（s11），此处显式拒绝而不是当作无判定。
                throw new UnsupportedOperationException(
                        "async hook output is not implemented: " + output);
            }
            case HookHandler.Command command -> commandRunner.run(
                    matcher.timeout()
                            .map(timeout -> new HookHandler.Command(command.command(), timeout))
                            .orElse(command),
                    input);
            default -> throw new UnsupportedOperationException(
                    "hook handler type not implemented: " + handler.contractType());
        };
    }

    /**
     * 归并各 handler 的 universal 字段
     *
     * @param outputs 各 handler 的判定
     * @param warnings 已收集的用户可见警告
     * @return 归并后的通知
     */
    private static Notices notices(List<HookOutput.Sync> outputs, List<String> warnings) {
        boolean halt = false;
        Optional<String> haltReason = Optional.empty();
        for (HookOutput.Sync output : outputs) {
            if (output.continueLoop().filter(value -> !value).isPresent()) {
                halt = true;
                if (haltReason.isEmpty()) {
                    haltReason = output.stopReason();
                }
            }
        }
        return new Notices(halt, haltReason, List.copyOf(warnings));
    }

    /**
     * 取判定原因，缺失时用兜底文案
     *
     * @param output 一个 handler 的判定
     * @param fallback 缺失时的文案
     * @return 原因文本
     */
    private static String reasonOf(HookOutput.Sync output, String fallback) {
        return output.specificAs(HookSpecificOutput.PreToolUse.class)
                .flatMap(HookSpecificOutput.PreToolUse::reason)
                .or(output::reason)
                .filter(reason -> !reason.isBlank())
                .orElse(fallback);
    }

    /**
     * 每个事件都可能产生的通知
     *
     * @param halt 契约的 {@code continue: false}，为 {@code true} 时整个会话停止，压过事件专属判定
     * @param haltReason {@code halt} 时给用户看的原因，不给模型看
     * @param warnings 契约的 {@code systemMessage}，给用户看的警告，不进模型上下文
     */
    public record Notices(boolean halt, Optional<String> haltReason, List<String> warnings) {

        /**
         * 校验通知
         *
         * @param halt 是否停止会话
         * @param haltReason 停止原因
         * @param warnings 用户可见警告
         */
        public Notices {
            Objects.requireNonNull(haltReason, "haltReason");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    /**
     * 工具执行前的归并判定
     *
     * @param notices 通知
     * @param denyReason 拒绝原因，为空表示 hook 不拒绝
     * @param additionalContext 追加到工具结果旁的上下文，可能来自多个 hook
     */
    public record PreToolUseVerdict(
            Notices notices, Optional<String> denyReason, List<String> additionalContext) {

        /**
         * 校验工具执行前判定
         *
         * @param notices 通知
         * @param denyReason 拒绝原因
         * @param additionalContext 追加上下文
         */
        public PreToolUseVerdict {
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(denyReason, "denyReason");
            additionalContext = List.copyOf(Objects.requireNonNull(additionalContext));
        }
    }

    /**
     * 工具执行后的归并判定
     *
     * @param notices 通知
     * @param blockReason 追加到工具结果旁的告警原因，为空表示无告警
     * @param updatedToolOutput 替换后的工具输出，为空表示不替换
     * @param additionalContext 追加到工具结果旁的上下文，可能来自多个 hook
     */
    public record PostToolUseVerdict(
            Notices notices,
            Optional<String> blockReason,
            Optional<String> updatedToolOutput,
            List<String> additionalContext) {

        /**
         * 校验工具执行后判定
         *
         * @param notices 通知
         * @param blockReason 告警原因
         * @param updatedToolOutput 替换后的工具输出
         * @param additionalContext 追加上下文
         */
        public PostToolUseVerdict {
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(blockReason, "blockReason");
            Objects.requireNonNull(updatedToolOutput, "updatedToolOutput");
            additionalContext = List.copyOf(Objects.requireNonNull(additionalContext));
        }
    }

    /**
     * 提示词提交后的归并判定
     *
     * @param notices 通知
     * @param blockReason 拦截原因，为空表示放行
     * @param suppressOriginalPrompt 拦截时是否从给用户的信息里省略原提示词
     * @param additionalContext 与提示词并列送入模型的上下文，可能来自多个 hook
     */
    public record UserPromptSubmitVerdict(
            Notices notices,
            Optional<String> blockReason,
            boolean suppressOriginalPrompt,
            List<String> additionalContext) {

        /**
         * 校验提示词提交判定
         *
         * @param notices 通知
         * @param blockReason 拦截原因
         * @param suppressOriginalPrompt 是否省略原提示词
         * @param additionalContext 追加上下文
         */
        public UserPromptSubmitVerdict {
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(blockReason, "blockReason");
            additionalContext = List.copyOf(Objects.requireNonNull(additionalContext));
        }
    }

    /**
     * 停止前的归并判定
     *
     * @param notices 通知
     * @param blockReason 阻止停止的原因，为空表示不阻止
     * @param additionalContext 送给模型的非错误反馈，出现时同样让对话继续
     */
    public record StopVerdict(
            Notices notices, Optional<String> blockReason, List<String> additionalContext) {

        /**
         * 校验停止判定
         *
         * @param notices 通知
         * @param blockReason 阻止停止的原因
         * @param additionalContext 非错误反馈
         */
        public StopVerdict {
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(blockReason, "blockReason");
            additionalContext = List.copyOf(Objects.requireNonNull(additionalContext));
        }

        /**
         * 判断是否应当阻止本次停止
         *
         * @return 有阻止原因或有非错误反馈时为 {@code true}
         */
        public boolean continues() {
            return blockReason.isPresent() || !additionalContext.isEmpty();
        }

        /**
         * 返回注入对话以让模型继续的文本
         *
         * @return 阻止原因与全部非错误反馈拼接后的文本
         */
        public String continuationMessage() {
            List<String> parts = new ArrayList<>();
            blockReason.ifPresent(parts::add);
            parts.addAll(additionalContext);
            return String.join("\n", parts);
        }
    }
}
