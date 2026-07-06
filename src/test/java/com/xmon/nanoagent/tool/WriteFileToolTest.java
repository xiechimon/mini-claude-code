package com.xmon.nanoagent.tool;

import com.anthropic.core.JsonValue;
import com.xmon.nanoagent.host.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试写入文件工具的副作用与文案
 */
final class WriteFileToolTest {

    @TempDir
    Path workingDirectory;

    private WriteFileTool tool;

    @BeforeEach
    void createTool() throws Exception {
        tool = new WriteFileTool(new Workspace(workingDirectory));
    }

    @Test
    void missingParentDirectoriesAreCreated() throws Exception {
        assertEquals("Wrote 2 bytes to new/deep/c.txt", write("new/deep/c.txt", "hi"));
        assertEquals("hi", Files.readString(workingDirectory.resolve("new/deep/c.txt")));
    }

    @Test
    void existingFileIsOverwrittenWholesaleWithoutBackup() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "old content that is longer");

        write("a.txt", "new");

        assertEquals("new", Files.readString(workingDirectory.resolve("a.txt")));
        assertFalse(Files.exists(workingDirectory.resolve("a.txt.bak")));
    }

    @Test
    void reportedByteCountIsTheCharacterCountOfTheContent() throws Exception {
        // 课程文案写的是 bytes，计数口径却是字符数；多字节字符下二者并不相同。
        assertEquals("Wrote 2 bytes to u.txt", write("u.txt", "你好"));
        assertEquals(6, Files.readAllBytes(workingDirectory.resolve("u.txt")).length);

        assertEquals("Wrote 1 bytes to emo.txt", write("emo.txt", "🙂"));
        assertEquals(4, Files.readAllBytes(workingDirectory.resolve("emo.txt")).length);
    }

    @Test
    void contentIsWrittenAsUtf8() throws Exception {
        write("u.txt", "你好");

        assertEquals("你好", new String(
                Files.readAllBytes(workingDirectory.resolve("u.txt")), StandardCharsets.UTF_8));
    }

    @Test
    void escapingPathProducesNoSideEffect() throws Exception {
        Path outside = workingDirectory.getParent().resolve("outside-write.txt");
        Files.deleteIfExists(outside);

        try {
            assertEquals("Wrote 6 bytes to ../outside-write.txt", write("../outside-write.txt", "leaked"));
            assertEquals("leaked", Files.readString(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void writingOntoADirectoryReturnsErrorText() throws Exception {
        Files.createDirectory(workingDirectory.resolve("dir"));

        assertTrue(write("dir", "content").startsWith("Error: "));
    }

    private String write(String path, String content) {
        return tool.execute(JsonValue.from(Map.of("path", path, "content", content)));
    }
}
