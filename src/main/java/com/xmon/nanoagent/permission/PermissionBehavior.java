package com.xmon.nanoagent.permission;

/**
 * 一条权限规则命中后要采取的行为
 *
 * <p>取值取自 Claude Code 契约的 {@code PermissionBehavior}。注意这是**规则**的行为而非判定的结果：
 * 判定结果只有允许和拒绝两态（见 {@link PermissionDecision}），{@link #ASK} 会在 {@link PermissionGate}
 * 内部经用户确认后消解成其中之一。
 */
public enum PermissionBehavior {

    /** 直接放行，不再匹配后续规则。 */
    ALLOW("allow"),

    /** 直接拒绝，不询问用户。 */
    DENY("deny"),

    /** 暂停并询问用户，由用户裁决。 */
    ASK("ask");

    private final String contractValue;

    PermissionBehavior(String contractValue) {
        this.contractValue = contractValue;
    }

    /**
     * 返回契约中的原始取值
     *
     * @return 与 {@code PermissionBehavior} 逐字相同的字符串
     */
    String contractValue() {
        return contractValue;
    }
}
