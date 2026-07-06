package com.xmon.nanoagent.host;

import com.xmon.nanoagent.permission.PermissionGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试工作区的路径解析与包含性判定
 *
 * <p>越界不再是异常而是一个布尔判定：是否放行由 {@link PermissionGate} 裁决，本类只负责把话说准。
 * 按 ADR-0004，符号链接逃逸用例必须继续被识别为越界。
 */
final class WorkspaceTest {

    @TempDir
    Path workingDirectory;

    private Workspace workspace;

    @BeforeEach
    void createWorkspace() throws Exception {
        workspace = new Workspace(workingDirectory);
    }

    @Test
    void relativePathInsideTheWorkspaceResolvesToAnAbsolutePath() throws Exception {
        assertEquals(workspace.root().resolve("a.txt"), workspace.resolve("a.txt"));
        assertTrue(workspace.contains("a.txt"));
    }

    @Test
    void theWorkspaceItselfIsInside() throws Exception {
        assertEquals(workspace.root(), workspace.resolve("."));
        assertTrue(workspace.contains("."));
    }

    @Test
    void emptyPathDegradesToTheWorkspaceRootAndCountsAsInside() throws Exception {
        assertEquals(workspace.root(), workspace.resolve(""));
        assertTrue(workspace.contains(""));
    }

    @Test
    void parentSegmentsThatFallBackInsideAreAccepted() throws Exception {
        Files.createDirectory(workingDirectory.resolve("sub"));

        assertEquals(workspace.root().resolve("a.txt"), workspace.resolve("sub/../a.txt"));
        assertTrue(workspace.contains("sub/../a.txt"));
    }

    @Test
    void pathThatDoesNotExistYetIsStillResolvable() throws Exception {
        assertEquals(workspace.root().resolve("new/deep/c.txt"), workspace.resolve("new/deep/c.txt"));
        assertTrue(workspace.contains("new/deep/c.txt"));
    }

    @Test
    void escapingRelativePathResolvesButIsReportedAsOutside() throws Exception {
        assertEquals(workspace.root().getParent().resolve("x"), workspace.resolve("../x"));
        assertFalse(workspace.contains("../x"));
    }

    @Test
    void absolutePathIsNotJoinedToTheRootAndIsReportedAsOutside() throws Exception {
        Path resolved = workspace.resolve("/etc/passwd");

        // 不断言字面路径：macOS 的 /etc 是指向 /private/etc 的符号链接，解析后的前缀随平台而变。
        assertFalse(resolved.startsWith(workspace.root()));
        assertTrue(resolved.endsWith("passwd"));
        assertFalse(workspace.contains("/etc/passwd"));
    }

    @Test
    void symbolicLinkLeavingTheWorkspaceIsReportedAsOutside() throws Exception {
        Files.createSymbolicLink(workingDirectory.resolve("etclink"), Path.of("/etc"));

        assertFalse(workspace.contains("etclink/passwd"));
    }

    @Test
    void danglingSymbolicLinkLeavingTheWorkspaceIsReportedAsOutside() throws Exception {
        // 写入断链符号链接会在链接目标处创建文件，因此它同样是一条逃逸路径。
        Files.createSymbolicLink(
                workingDirectory.resolve("dangling"),
                workingDirectory.getParent().resolve("outside.txt"));

        assertFalse(workspace.contains("dangling"));
    }
}
