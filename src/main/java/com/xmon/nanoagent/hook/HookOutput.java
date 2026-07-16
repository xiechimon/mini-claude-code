package com.xmon.nanoagent.hook;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 一次 hook 执行的返回值
 *
 * <p>对应 Claude Code 契约的 {@code HookJSONOutput = AsyncHookJSONOutput | SyncHookJSONOutput}。
 *
 * <p>{@link Sync} 的字段分两层，照抄契约的分层：universal 字段（每个事件都接受）在 record 上，
 * 事件专属的富判定在 {@link Sync#specific()} 里。
 */
public sealed interface HookOutput {

    /**
     * 顶层判定的取值
     *
     * <p>契约的 {@code decision?: 'approve' | 'block'} 两值全部录入。官方文档只记载 {@code block}
     * （原文 "The only value for decision is block"），{@code approve} 只出现在 {@code .d.ts} 里，
     * 是向后兼容遗留值：本课不实现它，遇到时按「无判定」处理。
     */
    enum Decision {

        /** 阻断该事件所代表的动作。 */
        BLOCK("block"),

        /** 遗留值，契约有、文档无、本课不实现。 */
        APPROVE("approve");

        private final String contractValue;

        Decision(String contractValue) {
            this.contractValue = contractValue;
        }

        /**
         * 返回契约中的原始取值
         *
         * @return 契约取值
         */
        public String contractValue() {
            return contractValue;
        }

        /**
         * 按契约取值查找判定
         *
         * @param contractValue 契约取值，大小写敏感
         * @return 匹配的判定，无匹配时为空
         */
        static Optional<Decision> fromContractValue(String contractValue) {
            return Stream.of(values())
                    .filter(decision -> decision.contractValue.equals(contractValue))
                    .findFirst();
        }
    }

    /**
     * 同步返回的判定
     *
     * @param continueLoop {@code false} 时整个会话在本次 hook 之后停止，优先于任何事件专属判定
     * @param stopReason {@code continueLoop} 为 {@code false} 时给用户看的原因，不给模型看
     * @param decision 顶层判定。{@link Decision#BLOCK} 的效果逐事件不同，见 {@link HookDispatcher}
     * @param reason {@code decision} 为 {@link Decision#BLOCK} 时的原因
     * @param systemMessage 给用户看的警告，不进模型上下文
     * @param specific 事件专属的富判定
     */
    record Sync(
            Optional<Boolean> continueLoop,
            Optional<String> stopReason,
            Optional<Decision> decision,
            Optional<String> reason,
            Optional<String> systemMessage,
            Optional<HookSpecificOutput> specific)
            implements HookOutput {

        /**
         * 校验同步判定
         *
         * @param continueLoop 是否继续
         * @param stopReason 停止原因
         * @param decision 顶层判定
         * @param reason 判定原因
         * @param systemMessage 用户可见警告
         * @param specific 事件专属判定
         */
        public Sync {
            Objects.requireNonNull(continueLoop, "continueLoop");
            Objects.requireNonNull(stopReason, "stopReason");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(systemMessage, "systemMessage");
            Objects.requireNonNull(specific, "specific");
        }

        /**
         * 返回不携带任何判定的同步结果
         *
         * @return 空判定
         */
        public static Sync none() {
            return new Sync(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        /**
         * 返回只携带事件专属判定的同步结果
         *
         * @param specific 事件专属判定
         * @return 同步判定
         */
        public static Sync of(HookSpecificOutput specific) {
            return new Sync(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Objects.requireNonNull(specific, "specific")));
        }

        /**
         * 返回顶层阻断判定
         *
         * @param reason 阻断原因
         * @return 同步判定
         */
        public static Sync block(String reason) {
            return new Sync(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Decision.BLOCK),
                    Optional.of(Objects.requireNonNull(reason, "reason")),
                    Optional.empty(),
                    Optional.empty());
        }

        /**
         * 判断是否携带顶层阻断判定
         *
         * @return 判定为 {@link Decision#BLOCK} 时为 {@code true}
         */
        boolean blocked() {
            return decision.filter(Decision.BLOCK::equals).isPresent();
        }

        /**
         * 返回符合期望类型的事件专属判定
         *
         * <p>类型不匹配时返回空而不是抛异常：{@code hookSpecificOutput} 来自 hook 进程的 stdout，
         * 是不可信输入。契约对 schema 校验失败的规定是「非阻塞错误，动作继续」，不是杀会话。
         *
         * @param type 期望的判定类型
         * @param <T> 判定类型
         * @return 类型匹配的判定，不匹配时为空
         */
        <T extends HookSpecificOutput> Optional<T> specificAs(Class<T> type) {
            return specific.filter(type::isInstance).map(type::cast);
        }
    }

    /**
     * 转入后台执行
     *
     * <p>契约有（{@code AsyncHookJSONOutput}），本课未实现：后台执行需要任务生命周期管理，
     * 那是 s11 Background Tasks 的内容。此处只录名与字段，构造它会被 {@link HookDispatcher} 拒绝。
     *
     * @param asyncTimeout 后台执行的超时秒数
     */
    record Async(int asyncTimeout) implements HookOutput {
    }
}
