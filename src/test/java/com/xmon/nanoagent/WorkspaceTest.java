package com.xmon.nanoagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试工作区的路径包含性判定
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
        assertEquals(workspace.root().resolve("a.txt"), workspace.resolveInside("a.txt"));
    }

    @Test
    void theWorkspaceItselfIsInside() throws Exception {
        assertEquals(workspace.root(), workspace.resolveInside("."));
    }

    @Test
    void parentSegmentsThatFallBackInsideAreAccepted() throws Exception {
        Files.createDirectory(workingDirectory.resolve("sub"));

        assertEquals(workspace.root().resolve("a.txt"), workspace.resolveInside("sub/../a.txt"));
    }

    @Test
    void pathThatDoesNotExistYetIsStillResolvable() throws Exception {
        assertEquals(workspace.root().resolve("new/deep/c.txt"), workspace.resolveInside("new/deep/c.txt"));
    }

    @Test
    void escapingRelativePathIsRejectedAndEchoesTheRawInput() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> workspace.resolveInside("../x"));

        assertEquals("Path escapes workspace: ../x", failure.getMessage());
    }

    @Test
    void absolutePathOutsideTheWorkspaceIsRejectedRatherThanJoined() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveInside("/etc/passwd"));
    }

    @Test
    void symbolicLinkLeavingTheWorkspaceIsRejected() throws Exception {
        Files.createSymbolicLink(workingDirectory.resolve("etclink"), Path.of("/etc"));

        assertThrows(IllegalArgumentException.class, () -> workspace.resolveInside("etclink/passwd"));
    }

    @Test
    void danglingSymbolicLinkLeavingTheWorkspaceIsRejected() throws Exception {
        // 写入断链符号链接会在链接目标处创建文件，因此它同样是一条逃逸路径。
        Files.createSymbolicLink(
                workingDirectory.resolve("dangling"),
                workingDirectory.getParent().resolve("outside.txt"));

        assertThrows(IllegalArgumentException.class, () -> workspace.resolveInside("dangling"));
    }

    @Test
    void containsReportsMembershipWithoutThrowing() throws Exception {
        assertTrue(workspace.contains("a.txt"));
        assertFalse(workspace.contains("../x"));
    }
}
