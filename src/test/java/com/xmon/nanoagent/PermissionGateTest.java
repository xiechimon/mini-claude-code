package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试权限闸门的规则匹配边界
 *
 * <p>只覆盖朴素子串匹配与缺参退化这类容易写错的判定边界，端到端行为由 {@link AgentLoopEndToEndTest} 覆盖。
 */
final class PermissionGateTest {

    @TempDir
    Path workingDirectory;

    private final List<String> asked = new ArrayList<>();
    private PermissionGate gate;

    @BeforeEach
    void createGate() throws Exception {
        Workspace workspace = new Workspace(workingDirectory);
        gate = new PermissionGate(PermissionRule.defaults(workspace), (toolName, input, reason) -> {
            asked.add(reason);
            return false;
        });
    }

    @Test
    void denyListMatchesInTableOrderRatherThanInputOrder() throws Exception {
        // 输入里 sudo 在前，但 rm -rf / 在拒绝表里排得更靠前。
        assertEquals(
                new PermissionDecision.Deny("Blocked: 'rm -rf /' is on the deny list"),
                bash("sudo rm -rf /"));
        assertTrue(asked.isEmpty());
    }

    @Test
    void denyListShortCircuitsBeforeAnyApproval() throws Exception {
        assertEquals(new PermissionDecision.Deny("Blocked: 'sudo' is on the deny list"), bash("sudo ls"));
        assertTrue(asked.isEmpty());
    }

    @Test
    void denyListIsPlainSubstringMatchingAndBitesInnocentCommands() throws Exception {
        // 清理临时目录被当成清空根目录：朴素子串匹配的已知误伤，刻意保留。
        assertEquals(
                new PermissionDecision.Deny("Blocked: 'rm -rf /' is on the deny list"),
                bash("rm -rf /tmp/build"));
    }

    @Test
    void denyListIsCaseSensitive() throws Exception {
        assertInstanceOf(PermissionDecision.Allow.class, bash("SUDO ls"));
    }

    @Test
    void narrowedDeviceRedirectNoLongerBlocksHarmlessTargets() throws Exception {
        assertInstanceOf(PermissionDecision.Allow.class, bash("echo hi > /dev/null"));
    }

    @Test
    void destructiveKeywordNeedsItsTrailingSpace() throws Exception {
        assertInstanceOf(PermissionDecision.Allow.class, bash("rm"));

        assertEquals(
                new PermissionDecision.Deny("Denied by user: Potentially destructive command"),
                bash("rm foo"));
        assertEquals(List.of("Potentially destructive command"), asked);
    }

    @Test
    void destructiveKeywordAlsoBitesInnocentProse() throws Exception {
        // "confirm the change" 含子串 "rm "：与拒绝表同源的误伤，刻意保留。
        assertEquals(
                new PermissionDecision.Deny("Denied by user: Potentially destructive command"),
                bash("confirm the change"));
    }

    @Test
    void readingOutsideTheWorkspaceAsksTooEvenThoughItIsReadOnly() throws Exception {
        assertEquals(
                new PermissionDecision.Deny("Denied by user: Path outside workspace"),
                check("read_file", Map.of("path", "../outside.txt")));
        assertEquals(List.of("Path outside workspace"), asked);
    }

    @Test
    void missingPathDegradesToTheWorkspaceItselfAndDoesNotMatch() throws Exception {
        assertInstanceOf(PermissionDecision.Allow.class, check("read_file", Map.of()));
        assertTrue(asked.isEmpty());
    }

    @Test
    void globAndUnregisteredToolNamesAreCoveredByNoRule() throws Exception {
        assertInstanceOf(PermissionDecision.Allow.class, check("glob", Map.of("pattern", "../*")));
        assertInstanceOf(PermissionDecision.Allow.class, check("unknown_tool", Map.of("command", "sudo ls")));
        assertTrue(asked.isEmpty());
    }

    @Test
    void unimplementedPermissionModesFailLoudlyInsteadOfFallingBackToDefault() {
        for (PermissionMode mode : PermissionMode.values()) {
            if (mode == PermissionMode.DEFAULT) {
                continue;
            }
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> gate.check(mode, "bash", JsonValue.from(Map.of("command", "ls"))));
            assertTrue(failure.getMessage().contains(mode.contractValue()));
        }
    }

    @Test
    void aCheckThatFailsDeniesInsteadOfKillingTheSession() throws Exception {
        // 自指符号链接：解析耗尽跳数后抛 IOException。放它逃逸会让会话带栈退出，
        // 且此时历史里已有没有配对 Tool Result 的 Tool Call，在 REPL 层兜也没用。
        Files.createSymbolicLink(workingDirectory.resolve("loop"), workingDirectory.resolve("loop"));

        PermissionDecision.Deny denied = assertInstanceOf(
                PermissionDecision.Deny.class, check("read_file", Map.of("path", "loop")));

        assertTrue(denied.message().startsWith("Permission check failed: "));
        assertTrue(asked.isEmpty());
    }

    private PermissionDecision bash(String command) throws Exception {
        return check("bash", Map.of("command", command));
    }

    private PermissionDecision check(String toolName, Map<String, String> input) throws Exception {
        return gate.check(PermissionMode.DEFAULT, toolName, JsonValue.from(input));
    }
}
