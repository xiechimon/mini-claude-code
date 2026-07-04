package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试编辑文件工具的替换语义
 */
final class EditFileToolTest {

    @TempDir
    Path workingDirectory;

    private EditFileTool tool;

    @BeforeEach
    void createTool() throws Exception {
        tool = new EditFileTool(new Workspace(workingDirectory));
    }

    @Test
    void onlyTheFirstOccurrenceIsReplaced() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "x x x");

        assertEquals("Edited a.txt", edit("a.txt", "x", "Y"));
        assertEquals("Y x x", Files.readString(workingDirectory.resolve("a.txt")));
    }

    @Test
    void missingTextLeavesTheFileUnchangedAndEchoesTheRawPath() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "abc");

        assertEquals("Error: text not found in a.txt", edit("a.txt", "zzz", "Y"));
        assertEquals("abc", Files.readString(workingDirectory.resolve("a.txt")));
    }

    @Test
    void matchingIsExactSubstringAndCaseSensitive() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "Hello  World");

        assertEquals("Error: text not found in a.txt", edit("a.txt", "hello", "Y"));
        assertEquals("Error: text not found in a.txt", edit("a.txt", "Hello World", "Y"));
        assertEquals("Hello  World", Files.readString(workingDirectory.resolve("a.txt")));
    }

    @Test
    void emptyOldTextInsertsAtTheStartOfTheFile() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "abc");

        assertEquals("Edited a.txt", edit("a.txt", "", "PRE"));
        assertEquals("PREabc", Files.readString(workingDirectory.resolve("a.txt")));
    }

    @Test
    void missingFileReturnsErrorTextButEscapingPathIsEditedForReal() throws Exception {
        assertTrue(edit("missing.txt", "a", "b").startsWith("Error: "));

        Path outside = workingDirectory.getParent().resolve("outside-edit.txt");
        Files.writeString(outside, "a");
        try {
            // 边界由 PermissionGate 裁决，工具自身不再拒绝越界路径。
            assertEquals("Edited ../outside-edit.txt", edit("../outside-edit.txt", "a", "b"));
            assertEquals("b", Files.readString(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private String edit(String path, String oldText, String newText) {
        return tool.execute(JsonValue.from(
                Map.of("path", path, "old_text", oldText, "new_text", newText)));
    }
}
