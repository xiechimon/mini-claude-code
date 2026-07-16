/**
 * 挂在 Agent 循环上的扩展点
 *
 * <p>对外承诺一个入口：{@link com.xmon.nanoagent.hook.HookDispatcher} 的四个触发方法，分别对应契约的
 * {@code PreToolUse}、{@code PostToolUse}、{@code UserPromptSubmit}、{@code Stop}。循环只调用它们，
 * 具体跑什么由注册表决定——这是本包存在的全部理由：新增一条扩展逻辑不该需要改循环。
 *
 * <p>{@code HookEvent} 的 31 个事件名、{@code HookPermissionDecision} 的 4 个取值、
 * {@code HookHandler} 的 5 个 handler 型都照契约全录，未实现的显式抛出而不是静默忽略。
 * 一条永不触发的 hook 与一条不存在的 hook 在运行时无从分辨，而 hook 的典型用途是当闸门。
 *
 * <p>本包不 import 同级的任何能力包：事件数据的载荷用 {@code String} 与 SDK 的 {@code JsonValue} 表达，
 * 因此依赖方向是 {@code core → hook → host} 单向。
 *
 * <p><b>信任边界</b>：{@code type: "command"} 的 hook 是用户在配置文件里指定的外部进程，它的 stdout
 * 不可信。解析失败一律降级为「无判定」并留下用户可见警告，与契约的「非阻塞错误，动作继续」一致；
 * 但降级绝不静默——见 {@code CommandHookRunner}。
 *
 * <p>hook 不是权限。契约把 {@code PreToolUse} 放在权限判定<b>之前</b>，且 hook 返回
 * {@code allow} 只跳过权限提示、不跳过拒绝规则。因此本包与 {@code permission} 包是两层，
 * 不合并——合并会丢掉「hook 的 allow ≠ 权限的 allow」这个区分。
 */
package com.xmon.nanoagent.hook;
