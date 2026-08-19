package com.miniclaudecode.agent;

import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSummariesTest {

    private static ToolExecutionResult result(String name, String argsJson, String text) {
        return new ToolExecutionResult("id-1", name, argsJson, text, 1L, false, null);
    }

    private static String emitted(ToolExecutionResult result) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolResultSummaries.emit(new PrintStream(sink, true, StandardCharsets.UTF_8), result);
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    void webSearchSummaryCountsNumberedResults() {
        String out = emitted(result("web_search", "{\"query\":\"java agent\"}",
                "1. 结果一\n2. 结果二\n3. 结果三"));
        assertTrue(out.contains("搜索 \"java agent\" 返回 3 条结果"), out);
    }

    @Test
    void webSearchWithoutQueryFallsBackToGenericLabel() {
        String out = emitted(result("web_search", "{}", "1. 唯一结果"));
        assertTrue(out.contains("搜索结果 返回 1 条结果"), out);
    }

    @Test
    void webSearchFailureIsCompactedToOneLine() {
        String out = emitted(result("web_search", "{\"query\":\"x\"}", "搜索失败：网络不可达"));
        assertTrue(out.contains("搜索失败：网络不可达"), out);
        assertEquals(1, out.trim().lines().count(), out);
    }

    @Test
    void webFetchSummaryReportsTitleAndLength() {
        String text = "📄 标题: Example Domain\n📏 正文 1200 字符";
        String out = emitted(result("web_fetch", "{\"url\":\"https://example.com/page\"}", text));
        assertTrue(out.contains("抓取 example.com/page 完成: Example Domain · 正文 1200 字符"), out);
    }

    @Test
    void webFetchFailureCarriesReason() {
        String out = emitted(result("web_fetch", "{\"url\":\"https://example.com\"}", "抓取失败: 超时"));
        assertTrue(out.contains("抓取 example.com 失败: 抓取失败: 超时"), out);
    }

    @Test
    void stepSearchResultIsLabelled() {
        String out = emitted(result("web_search", "{\"query\":\"q\"}",
                "🔍 [StepSearch] 代理结果\n1. 结果"));
        assertTrue(out.contains("StepSearch · 搜索 \"q\""), out);
    }

    @Test
    void otherToolsAndNullsAreSilent() {
        assertEquals("", emitted(result("read_file", "{\"path\":\"a\"}", "content")));
        assertEquals("", emitted(null));
        assertEquals("", emitted(result(null, "{}", "text")));
        ToolResultSummaries.emit(null, result("web_search", "{}", "1. x"));
    }

    @Test
    void forStreamCallbackEmitsToGivenStream() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolResultSummaries.forStream(new PrintStream(sink, true, StandardCharsets.UTF_8))
                .accept(result("web_search", "{\"query\":\"k\"}", "1. r"));

        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("搜索 \"k\" 返回 1 条结果"));
    }

    @Test
    void malformedArgumentsJsonDoesNotBreakSummary() {
        String out = emitted(result("web_search", "not json", "1. 结果"));
        assertTrue(out.contains("搜索结果 返回 1 条结果"), out);
    }

    @Test
    void multiLineFailureCompactsToFirstNonEmptyLine() {
        String out = emitted(result("web_fetch", "{\"url\":\"https://a.dev\"}", "抓取失败\n第二行细节"));
        // ANSI 前缀不参与断言，只验证压缩成首行
        assertTrue(out.contains("失败: 抓取失败") && !out.contains("第二行细节"), out);
    }
}
