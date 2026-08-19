package com.miniclaudecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import com.miniclaudecode.util.AnsiStyle;

import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * web_search / web_fetch 工具结果的单行摘要
 *
 * <p>三条执行路径共用：工具刚返回时打一行 {@code → 搜索 "xxx" 返回 N 条结果}，让用户在流式输出里
 * 立刻看到联网动作的结论，而不是等整轮回复结束后翻工具原文
 */
public final class ToolResultSummaries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultSummaries() {
    }

    /**
     * @param out 摘要写入目标。ReAct 传 renderer().stream()，Plan / SubAgent 传各自的
     *            PrintStream（SubAgent 必须传 step 级缓冲流，避免并行步骤摘要交错）
     * @return 可直接挂到 {@link ToolCallRunner#execute} onResult 的回调
     */
    public static Consumer<ToolExecutionResult> forStream(PrintStream out) {
        return result -> emit(out, result);
    }

    /**
     * 只对 web_search / web_fetch 产出摘要，其余工具静默
     */
    public static void emit(PrintStream out, ToolExecutionResult result) {
        if (out == null || result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            out.println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private static String webSearchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private static String webFetchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private static boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private static String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return JSON.readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }
}
