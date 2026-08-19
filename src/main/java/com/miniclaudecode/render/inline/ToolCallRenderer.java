package com.miniclaudecode.render.inline;

import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.render.ToolCallLabels;
import com.miniclaudecode.util.AnsiStyle;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * 把一组工具调用渲染成 {@link FoldableBlock}
 *
 * <p>折叠态：{@code ⏵ 读取 3 个文件 (ctrl+o to expand)}<br>
 * 展开态：原 PlainRenderer 风格的工具标签 + 缩进的关键参数 + 折叠提示
 *
 * <p>每次调用产生一个 block 并立即渲染折叠态，注册到 {@link BlockRegistry}
 */
public final class ToolCallRenderer {

    private final PrintStream out;
    private final BlockRegistry registry;

    public ToolCallRenderer(PrintStream out, BlockRegistry registry) {
        this.out = out;
        this.registry = registry;
    }

    static String collapsedHeader(Map<String, List<LlmClient.ToolCall>> grouped) {
        if (grouped.size() == 1) {
            var entry = grouped.entrySet().iterator().next();
            String label = toolCollapsedLabel(entry.getKey(), entry.getValue());
            return AnsiStyle.subtle("⏵ " + stripPrefixIcon(label) + " (ctrl+o to expand)");
        }
        int totalCalls = grouped.values().stream().mapToInt(List::size).sum();
        return AnsiStyle.subtle("⏵ " + grouped.size() + " 组工具调用 / "
                + totalCalls + " 次 (ctrl+o to expand)");
    }

    /**
     * 移除 emoji 前缀（折叠态视觉更紧凑），如 "📖 读取 3 个文件" → "读取 3 个文件"
     */
    private static String stripPrefixIcon(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        int firstSpace = label.indexOf(' ');
        if (firstSpace < 0) {
            return label;
        }
        // 仅当第一个 token 是 emoji（高 Unicode）时才剥离
        int cp = label.codePointAt(0);
        if (cp >= 0x2600 && cp <= 0x1FAFF) {
            return label.substring(firstSpace + 1);
        }
        return label;
    }

    static String toolCollapsedLabel(String toolName, List<LlmClient.ToolCall> calls) {
        int count = calls == null ? 0 : calls.size();
        String label = ToolCallLabels.toolLabel(toolName, count);
        if (count != 1 || calls == null || calls.isEmpty()) {
            return label;
        }
        String detail = ToolCallLabels.extractKeyParam(toolName, calls.get(0).function().arguments());
        if (detail.isBlank()) {
            return label;
        }
        return switch (toolName) {
            case "web_search" -> "🌐 WebSearch(\"" + detail + "\")";
            case "web_fetch" -> "📰 WebFetch(" + compactUrl(detail) + ")";
            case "search_code" -> "🔍 SearchCode(\"" + detail + "\")";
            case "read_file" -> "📖 ReadFile(" + detail + ")";
            case "list_dir" -> "📂 ListDir(" + detail + ")";
            case "execute_command" -> "⚡ Shell(" + detail + ")";
            default -> label + " · " + detail;
        };
    }

    private static String compactUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
        return value.length() > 80 ? value.substring(0, 77) + "..." : value;
    }

    public void render(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallLabels.group(toolCalls);
        String header = collapsedHeader(grouped);
        List<String> expanded = ToolCallLabels.expandedLines(grouped);

        FoldableBlock block = new FoldableBlock(out, header, expanded);
        registry.register(block);
        block.renderInitial();
    }
}
