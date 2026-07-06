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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试读取文件工具的输出与边界
 */
final class ReadFileToolTest {

    @TempDir
    Path workingDirectory;

    private ReadFileTool tool;

    @BeforeEach
    void createTool() throws Exception {
        tool = new ReadFileTool(new Workspace(workingDirectory));
    }

    @Test
    void contentIsRejoinedByLineFeedWithoutLineNumbers() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "l1\r\nl2\rl3\n");

        assertEquals("l1\nl2\nl3", read(Map.of("path", "a.txt")));
    }

    @Test
    void limitBelowLineCountTruncatesAndAppendsTheRemainderMarker() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        assertEquals("l1\nl2\n... (2 more lines)", read(Map.of("path", "a.txt", "limit", 2)));
    }

    @Test
    void limitEqualToOrAboveLineCountDoesNotTruncate() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        assertEquals("l1\nl2\nl3\nl4", read(Map.of("path", "a.txt", "limit", 4)));
        assertEquals("l1\nl2\nl3\nl4", read(Map.of("path", "a.txt", "limit", 9)));
    }

    @Test
    void zeroAndAbsentLimitDoNotTruncate() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        assertEquals("l1\nl2\nl3\nl4", read(Map.of("path", "a.txt", "limit", 0)));
        assertEquals("l1\nl2\nl3\nl4", read(Map.of("path", "a.txt")));
    }

    @Test
    void negativeLimitDropsTrailingLinesAndCountsTheMarkerAccordingly() throws Exception {
        Files.writeString(workingDirectory.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        assertEquals("l1\nl2\nl3\n... (5 more lines)", read(Map.of("path", "a.txt", "limit", -1)));
    }

    @Test
    void emptyFileReturnsAnEmptyStringWithoutPlaceholder() throws Exception {
        Files.writeString(workingDirectory.resolve("empty.txt"), "");

        assertEquals("", read(Map.of("path", "empty.txt")));
    }

    @Test
    void missingFileDirectoryAndUndecodableBytesAllReturnErrorText() throws Exception {
        Files.createDirectory(workingDirectory.resolve("dir"));
        Files.write(workingDirectory.resolve("binary.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 'a'});

        assertTrue(read(Map.of("path", "missing.txt")).startsWith("Error: "));
        assertTrue(read(Map.of("path", "dir")).startsWith("Error: "));
        assertTrue(read(Map.of("path", "binary.bin")).startsWith("Error: "));
    }

    @Test
    void escapingPathIsReadForRealBecausePermissionOwnsTheBoundary() throws Exception {
        Path outside = workingDirectory.getParent().resolve("outside-read.txt");
        Files.writeString(outside, "leaked");

        try {
            assertEquals("leaked", read(Map.of("path", "../outside-read.txt")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void utf8IsUsedRegardlessOfPlatformDefaults() throws Exception {
        Files.write(workingDirectory.resolve("u.txt"), "你好".getBytes(StandardCharsets.UTF_8));

        assertEquals("你好", read(Map.of("path", "u.txt")));
    }

    private String read(Map<String, Object> input) {
        return tool.execute(JsonValue.from(input));
    }
}
