/**
 * 工具执行前的权限裁决
 *
 * <p>对外只承诺一个入口：{@link com.xmon.nanoagent.permission.PermissionGate#check}，它接收工具名与
 * 输入，返回已决的 {@link com.xmon.nanoagent.permission.PermissionDecision}——允许，或带原因的拒绝。
 * 「询问用户」在包内经 {@link com.xmon.nanoagent.permission.ApprovalPrompt} 消解，不会泄漏到返回值上，
 * 这与 Claude Code 契约的 {@code CanUseTool} 同形。
 *
 * <p>{@code PermissionMode}、{@code PermissionBehavior} 的取值照抄契约，不可改写。
 * {@link com.xmon.nanoagent.permission.PermissionRule} 里的命令模式表是教学示例而非安全边界。
 */
package com.xmon.nanoagent.permission;
