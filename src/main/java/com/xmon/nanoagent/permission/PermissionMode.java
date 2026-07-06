package com.xmon.nanoagent.permission;

/**
 * 决定整个会话默认如何处理权限请求的模式
 *
 * <p>取值取自 Claude Code 契约的 {@code PermissionMode}，六个值全部录入。常量名按 Java 惯例书写，
 * 契约原值保存在 {@link #contractValue()} 里。本课只实现 {@link #DEFAULT}，其余模式在
 * {@link PermissionGate} 中显式抛出而不是退化到默认分支。
 */
public enum PermissionMode {

    /** 按规则表判定，命中询问规则时暂停等待用户确认。 */
    DEFAULT("default"),

    /** 自动批准编辑类操作。 */
    ACCEPT_EDITS("acceptEdits"),

    /** 跳过全部权限判定。 */
    BYPASS_PERMISSIONS("bypassPermissions"),

    /** 只读模式，不执行产生副作用的操作。 */
    PLAN("plan"),

    /** 不询问，命中询问规则时直接拒绝。 */
    DONT_ASK("dontAsk"),

    /** 由宿主自行裁决。 */
    AUTO("auto");

    private final String contractValue;

    PermissionMode(String contractValue) {
        this.contractValue = contractValue;
    }

    /**
     * 返回契约中的原始取值
     *
     * @return 与 {@code PermissionMode} 逐字相同的字符串
     */
    String contractValue() {
        return contractValue;
    }
}
