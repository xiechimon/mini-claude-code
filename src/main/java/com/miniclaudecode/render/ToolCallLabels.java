package com.miniclaudecode.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.util.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用的展示文案：分组、标签、关键参数
 *
 * <p>ReAct、Plan-and-Execute、Multi-Agent 三条执行路径与 plain / inline 渲染器共用同一份文案，
 * 否则同一个工具会在不同路径下显示成不同名字
 */
public final class ToolCallLabels {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DETAIL_LENGTH = 80;

    private ToolCallLabels() {
    }

    /**
     * 按工具名合并同批调用，保留首次出现顺序
     */
    public static Map<String, List<LlmClient.ToolCall>> group(List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        return grouped;
    }

    public static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    /**
     * 提取最能说明本次调用意图的单个参数
     *
     * @param argsJson 原始参数 JSON，允许为 null 或非法 JSON——参数畸形不能让展示层抛异常打断执行
     * @return 未映射关键字的工具回退为截断后的原始 JSON
     */
    public static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return truncate(argsJson);
            }
            return truncate(node.path(key).asText(""));
        } catch (Exception e) {
            return truncate(argsJson);
        }
    }

    /**
     * 展开态文案：每组一行标签，组内每次调用一行关键参数
     */
    public static List<String> expandedLines(Map<String, List<LlmClient.ToolCall>> grouped) {
        List<String> lines = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            lines.add(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    lines.add(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
        return lines;
    }

    /**
     * 直接把展开态写入流，供没有折叠能力的渲染路径使用
     */
    public static void printExpanded(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (String line : expandedLines(group(toolCalls))) {
            out.println(line);
        }
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_DETAIL_LENGTH
                ? value.substring(0, MAX_DETAIL_LENGTH - 3) + "..."
                : value;
    }
}
