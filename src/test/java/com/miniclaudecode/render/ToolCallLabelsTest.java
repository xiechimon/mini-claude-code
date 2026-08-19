package com.miniclaudecode.render;

import com.miniclaudecode.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLabelsTest {

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void toolLabelRendersBuiltinTools() {
        assertEquals("📖 读取 1 个文件", ToolCallLabels.toolLabel("read_file", 1));
        assertEquals("✏️ 写入 2 个文件", ToolCallLabels.toolLabel("write_file", 2));
        assertEquals("⚡ 执行 1 条命令", ToolCallLabels.toolLabel("execute_command", 1));
        assertEquals("💾 保存长期记忆 3 条", ToolCallLabels.toolLabel("save_memory", 3));
    }

    @Test
    void toolLabelRendersMcpToolWithServerAndToolName() {
        assertEquals("🔌 调用 MCP 工具 chrome-devtools.click",
                ToolCallLabels.toolLabel("mcp__chrome-devtools__click", 1));
        assertEquals("🔌 调用 MCP 工具 chrome-devtools.click × 2",
                ToolCallLabels.toolLabel("mcp__chrome-devtools__click", 2));
    }

    @Test
    void toolLabelFallsBackForUnknownTool() {
        assertEquals("🔧 revert_turn × 1", ToolCallLabels.toolLabel("revert_turn", 1));
    }

    @Test
    void extractKeyParamPullsOutMappedKey() {
        assertEquals("README.md", ToolCallLabels.extractKeyParam("read_file", "{\"path\":\"README.md\"}"));
        assertEquals("mvn test", ToolCallLabels.extractKeyParam("execute_command", "{\"command\":\"mvn test\"}"));
        assertEquals("https://a.dev", ToolCallLabels.extractKeyParam("web_fetch", "{\"url\":\"https://a.dev\"}"));
    }

    @Test
    void extractKeyParamTruncatesLongValue() {
        String longPath = "a".repeat(100);
        String detail = ToolCallLabels.extractKeyParam("read_file", "{\"path\":\"" + longPath + "\"}");
        assertEquals(80, detail.length());
        assertTrue(detail.endsWith("..."), detail);
    }

    @Test
    void extractKeyParamFallsBackToRawArgsForUnmappedTool() {
        assertEquals("{\"turns\":1}", ToolCallLabels.extractKeyParam("revert_turn", "{\"turns\":1}"));
    }

    @Test
    void extractKeyParamFallsBackToRawArgsOnMalformedJson() {
        assertEquals("not json", ToolCallLabels.extractKeyParam("read_file", "not json"));
    }

    @Test
    void extractKeyParamReturnsEmptyForNullArgs() {
        assertEquals("", ToolCallLabels.extractKeyParam("read_file", null));
    }

    @Test
    void groupMergesSameToolAndKeepsFirstSeenOrder() {
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallLabels.group(List.of(
                tc("read_file", "{\"path\":\"a.md\"}"),
                tc("write_file", "{\"path\":\"b.md\"}"),
                tc("read_file", "{\"path\":\"c.md\"}")));

        assertEquals(List.of("read_file", "write_file"), List.copyOf(grouped.keySet()));
        assertEquals(2, grouped.get("read_file").size());
    }

    @Test
    void expandedLinesEmitLabelThenOneDetailPerCall() {
        List<String> lines = ToolCallLabels.expandedLines(ToolCallLabels.group(List.of(
                tc("read_file", "{\"path\":\"a.md\"}"),
                tc("read_file", "{\"path\":\"b.md\"}"))));

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("📖 读取 2 个文件"), lines.toString());
        assertTrue(lines.get(1).contains("a.md"), lines.toString());
        assertTrue(lines.get(2).contains("b.md"), lines.toString());
    }

    @Test
    void expandedLinesSkipEmptyDetail() {
        List<String> lines = ToolCallLabels.expandedLines(ToolCallLabels.group(List.of(
                tc("read_file", "{}"))));

        assertEquals(1, lines.size());
    }

    @Test
    void printExpandedWritesLabelAndDetailToStream() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolCallLabels.printExpanded(new PrintStream(sink, true, StandardCharsets.UTF_8),
                List.of(tc("read_file", "{\"path\":\"README.md\"}")));

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("📖 读取 1 个文件"), emitted);
        assertTrue(emitted.contains("README.md"), emitted);
    }

    @Test
    void printExpandedIgnoresEmptyToolCalls() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);

        ToolCallLabels.printExpanded(out, List.of());
        ToolCallLabels.printExpanded(out, null);

        assertEquals("", sink.toString(StandardCharsets.UTF_8));
    }
}
