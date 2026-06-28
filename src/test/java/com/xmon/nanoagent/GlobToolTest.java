package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试查找文件工具的匹配范围与包含性过滤
 */
final class GlobToolTest {

    @TempDir
    Path workingDirectory;

    private GlobTool tool;

    @BeforeEach
    void createTool() throws Exception {
        tool = new GlobTool(new Workspace(workingDirectory));
        Files.writeString(workingDirectory.resolve("top.py"), "");
        Files.writeString(workingDirectory.resolve("notes.txt"), "");
        Files.writeString(workingDirectory.resolve(".hidden.py"), "");
        Files.createDirectories(workingDirectory.resolve("sub/deep"));
        Files.writeString(workingDirectory.resolve("sub/b.py"), "");
        Files.writeString(workingDirectory.resolve("sub/deep/d.py"), "");
    }

    @Test
    void matchesAreReturnedAsPathsRelativeToTheWorkspace() {
        assertEquals(List.of("top.py"), sorted(glob("*.py")));
        assertEquals(List.of("sub/b.py"), sorted(glob("sub/*.py")));
    }

    @Test
    void doubleStarDoesNotRecurse() {
        assertEquals(sorted(glob("*/*.py")), sorted(glob("**/*.py")));
        assertEquals(List.of("sub/b.py"), sorted(glob("**/*.py")));
    }

    @Test
    void starDoesNotMatchHiddenFilesButAnExplicitDotDoes() {
        assertEquals(List.of("top.py"), sorted(glob("*.py")));
        assertEquals(List.of(".hidden.py"), sorted(glob(".*.py")));
    }

    @Test
    void absolutePatternIsDroppedByTheContainmentFilter() {
        assertEquals("(no matches)", glob("/etc/host*"));
    }

    @Test
    void patternLeavingTheWorkspaceThroughASymbolicLinkIsDropped() throws Exception {
        Files.createSymbolicLink(workingDirectory.resolve("etclink"), Path.of("/etc"));

        assertEquals("(no matches)", glob("etclink/host*"));
    }

    @Test
    void parentPatternKeepsOnlyTheWorkspaceItself() throws Exception {
        // 模式本身不受工作区限制，但界外结果被逐条丢弃，只剩下以 ../ 开头指向工作区自身的那一条。
        assertEquals(
                List.of("../" + new Workspace(workingDirectory).root().getFileName()),
                sorted(glob("../*")));
    }

    @Test
    void noMatchAndInvalidPatternBothReturnTheNoMatchesLiteral() {
        assertEquals("(no matches)", glob("*.rs"));
        assertEquals("(no matches)", glob("["));
        assertEquals("(no matches)", glob("missing/*.py"));
    }

    private String glob(String pattern) {
        return tool.execute(JsonValue.from(Map.of("pattern", pattern)));
    }

    private static List<String> sorted(String output) {
        return Arrays.stream(output.split("\n")).sorted().toList();
    }
}
