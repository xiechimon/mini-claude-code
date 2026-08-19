package com.miniclaudecode.render.inline;

import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.render.ToolCallLabels;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRendererTest {

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void singleGroupCollapsedHeaderUsesToolLabel() {
        var grouped = ToolCallLabels.group(List.of(
                tc("read_file", "{\"path\":\"a.md\"}"),
                tc("read_file", "{\"path\":\"b.md\"}")));
        String header = ToolCallRenderer.collapsedHeader(grouped);
        assertTrue(header.contains("⏵"), header);
        assertTrue(header.contains("读取 2 个文件"), header);
        assertTrue(header.contains("ctrl+o"), header);
    }

    @Test
    void singleWebSearchCollapsedHeaderShowsQuery() {
        var grouped = ToolCallLabels.group(List.of(
                tc("web_search", "{\"query\":\"沉默王二 程序员 博主\"}")));

        String header = ToolCallRenderer.collapsedHeader(grouped);

        assertTrue(header.contains("WebSearch"), header);
        assertTrue(header.contains("沉默王二 程序员 博主"), header);
    }

    @Test
    void singleWebFetchCollapsedHeaderShowsUrl() {
        var grouped = ToolCallLabels.group(List.of(
                tc("web_fetch", "{\"url\":\"https://www.itwanger.com/about\"}")));

        String header = ToolCallRenderer.collapsedHeader(grouped);

        assertTrue(header.contains("WebFetch"), header);
        assertTrue(header.contains("www.itwanger.com/about"), header);
    }

    @Test
    void multipleGroupsCollapsedShowsTotalCount() {
        var grouped = ToolCallLabels.group(List.of(
                tc("read_file", "{}"),
                tc("write_file", "{}")));
        String header = ToolCallRenderer.collapsedHeader(grouped);
        assertTrue(header.contains("2 组工具调用"), header);
        assertTrue(header.contains("2 次"), header);
    }

    @Test
    void expandedLinesIncludeToolLabelAndPaths() {
        var grouped = ToolCallLabels.group(List.of(
                tc("read_file", "{\"path\":\"README.md\"}")));
        List<String> lines = ToolCallLabels.expandedLines(grouped);
        assertTrue(lines.stream().anyMatch(l -> l.contains("📖 读取 1 个文件")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("README.md")), lines.toString());
    }

    @Test
    void rendererCreatesAndRegistersFoldableBlock() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BlockRegistry registry = new BlockRegistry();
        ToolCallRenderer r = new ToolCallRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), registry);
        r.render(List.of(tc("read_file", "{\"path\":\"a.md\"}")));
        assertEquals(1, registry.size());
        FoldableBlock b = registry.peekLast();
        assertFalse(b.isExpanded());
        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("⏵"), emitted);
    }

    @Test
    void emptyToolCallsListIsNoOp() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BlockRegistry registry = new BlockRegistry();
        ToolCallRenderer r = new ToolCallRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), registry);
        r.render(List.of());
        assertEquals(0, registry.size());
        assertEquals("", sink.toString(StandardCharsets.UTF_8));
    }
}
