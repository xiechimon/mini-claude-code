package com.xmon.nanoagent.hook;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code PreToolUse} hook 对一次工具调用的判定
 *
 * <p>取值取自 Claude Code 契约的 {@code HookPermissionDecision}，四个值全部录入。
 *
 * <p>本课只实现 {@link #DENY}：hook 拒绝时工具不执行，原因回填给模型。另外两个未实现的值各有
 * 明确的前置条件：
 *
 * <ul>
 *   <li>{@link #ALLOW} 的契约语义不是「放行」而是「跳过权限**提示**，但 deny/ask 规则仍要求值」。
 *       要实现它，{@code PermissionGate.check} 得多接一个「已预批准」入参——那是 s15 信任边界的内容。
 *       当前实现下 hook 返回 {@code ALLOW} 与不返回判定同效，两者都继续走权限管线。
 *   <li>{@link #DEFER} 要求非交互模式（{@code -p}）加会话恢复，本项目只有交互式 REPL，没有可挂起的地方。
 * </ul>
 *
 * <p>{@link #ASK} 的契约语义是「强制弹出权限提示」，即使会话模式本来会自动批准。本项目的会话模式
 * 恒为 {@code default}，权限管线已经会对命中 ask 规则的调用弹提示，因此这个值目前无法产生可观察差异。
 *
 * <p>多个 hook 判定不同时的归并优先级见 {@link #precedence()}。
 */
public enum HookPermissionDecision {

    /** 拒绝工具调用，原因给模型看。 */
    DENY("deny", 0),

    /** 优雅退出以便后续恢复该工具调用。契约有，本课未实现。 */
    DEFER("defer", 1),

    /** 强制弹出权限提示。契约有，本课无可观察差异。 */
    ASK("ask", 2),

    /** 跳过权限提示，但 deny/ask 规则仍会求值。契约有，本课未实现。 */
    ALLOW("allow", 3);

    private final String contractValue;
    private final int precedence;

    HookPermissionDecision(String contractValue, int precedence) {
        this.contractValue = contractValue;
        this.precedence = precedence;
    }

    /**
     * 返回契约中的原始取值
     *
     * @return 与 {@code HookPermissionDecision} 逐字相同的字符串
     */
    public String contractValue() {
        return contractValue;
    }

    /**
     * 返回归并优先级，数值越小越优先
     *
     * <p>契约规定多个 {@code PreToolUse} hook 判定不同时按 {@code deny > defer > ask > allow} 归并。
     * 优先级作为枚举自带的数据而不是外部比较器：它是契约事实，跟着取值走。
     *
     * @return 优先级序号
     */
    int precedence() {
        return precedence;
    }

    /**
     * 按契约取值查找判定
     *
     * @param contractValue 契约取值，大小写敏感
     * @return 匹配的判定，无匹配时为空
     */
    static Optional<HookPermissionDecision> fromContractValue(String contractValue) {
        return Stream.of(values())
                .filter(decision -> decision.contractValue.equals(contractValue))
                .findFirst();
    }
}
