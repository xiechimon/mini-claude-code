package com.xmon.nanoagent.permission;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xmon.nanoagent.host.Workspace;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 权限规则表中的一条规则
 *
 * <p>规则把「命中什么」与「命中后怎么办」分开：{@link #check} 负责判定并给出原因，{@link #behavior}
 * 决定该原因导向拒绝还是询问。课程用三个独立函数表达的硬拒绝、规则匹配、用户审批，在这里塌缩成
 * 一张有序规则表加一个审批器——区分「绝对禁止」和「需要确认」的信息由 {@link #behavior} 承载。
 *
 * <p><b>命令模式表不是安全边界，只是教学示例。</b>两组模式都是朴素子串匹配，两类错误都存在且不打算修：
 * false positive——{@code rm -rf /tmp/build} 命中 {@code rm -rf /}，{@code confirm the change} 含子串
 * {@code rm }；false negative——{@code > /dev/sdb} 与 {@code >/dev/sda} 都不命中，大小写不同即整条漏过。
 * 它要演示的是「判定发生在工具执行之前」这个位置，不是真的挡得住谁。想把它当防线加固之前，先换掉朴素子串匹配。
 *
 * @param tools 本规则覆盖的工具名，其余工具名一律不命中
 * @param check 判定逻辑
 * @param behavior 命中后的行为
 */
public record PermissionRule(Set<String> tools, RuleCheck check, PermissionBehavior behavior) {

    /** 永远禁止的命令片段，按序匹配，首个命中者胜出。教学示例，见类文档关于安全边界的说明。 */
    private static final List<String> DENIED_SUBSTRINGS = List.of(
            "rm -rf /",
            "sudo",
            "shutdown",
            "reboot",
            "mkfs",
            "dd if=",
            "> /dev/sda");

    /** 需要用户确认的命令片段，任一命中即触发询问。 */
    private static final List<String> DESTRUCTIVE_SUBSTRINGS = List.of(
            "rm ",
            "> /etc/",
            "chmod 777");

    private static final Set<String> FILE_TOOLS = Set.of("read_file", "write_file", "edit_file");

    /**
     * 校验并固化规则字段
     *
     * @param tools 本规则覆盖的工具名
     * @param check 判定逻辑
     * @param behavior 命中后的行为
     */
    public PermissionRule {
        tools = Set.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(behavior, "behavior");
    }

    /**
     * 构造本课的规则表
     *
     * <p>顺序即优先级：拒绝规则排在询问规则之前，因此命中拒绝表的命令不会再走到审批。
     *
     * @param workspace 判定路径包含性所用的边界
     * @return 按优先级排列的规则表
     */
    public static List<PermissionRule> defaults(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        return List.of(
                new PermissionRule(Set.of("bash"), PermissionRule::deniedCommand, PermissionBehavior.DENY),
                new PermissionRule(FILE_TOOLS, outsideWorkspace(workspace), PermissionBehavior.ASK),
                new PermissionRule(Set.of("bash"), PermissionRule::destructiveCommand, PermissionBehavior.ASK));
    }

    /**
     * 判定命令是否命中拒绝表
     *
     * @param input 模型给出的工具输入
     * @return 命中时为含具体模式的原因，未命中时为空
     */
    private static Optional<String> deniedCommand(JsonValue input) {
        String command = stringField(input, "command");
        return DENIED_SUBSTRINGS.stream()
                .filter(command::contains)
                .findFirst()
                .map(pattern -> "Blocked: '" + pattern + "' is on the deny list");
    }

    /**
     * 判定命令是否含需要确认的破坏性片段
     *
     * @param input 模型给出的工具输入
     * @return 命中时为固定原因，未命中时为空
     */
    private static Optional<String> destructiveCommand(JsonValue input) {
        String command = stringField(input, "command");
        return DESTRUCTIVE_SUBSTRINGS.stream().anyMatch(command::contains)
                ? Optional.of("Potentially destructive command")
                : Optional.empty();
    }

    /**
     * 构造判定路径是否越出工作区的规则逻辑
     *
     * @param workspace 路径边界
     * @return 越界时给出原因的判定逻辑
     */
    private static RuleCheck outsideWorkspace(Workspace workspace) {
        return input -> workspace.contains(stringField(input, "path"))
                ? Optional.empty()
                : Optional.of("Path outside workspace");
    }

    /**
     * 读取工具输入中的字符串字段
     *
     * <p>缺失或非字符串一律退化成空串：空路径解析后就是工作区根，因而不会命中越界规则，与课程一致。
     *
     * @param input 模型给出的工具输入
     * @param name 字段名
     * @return 字段值，缺失时为空串
     */
    private static String stringField(JsonValue input, String name) {
        Object value = input.convert(new TypeReference<Map<String, Object>>() {
        }).get(name);
        return value instanceof String text ? text : "";
    }
}
