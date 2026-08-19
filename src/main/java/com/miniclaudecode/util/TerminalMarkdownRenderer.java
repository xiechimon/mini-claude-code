package com.miniclaudecode.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * 轻量终端 Markdown 渲染器
 * <p>
 * 目标不是完整支持所有 Markdown 语法，而是把常见的标题、列表、表格、引用和代码块
 * 渲染成更适合 CLI 终端阅读的纯文本布局
 */
public final class TerminalMarkdownRenderer {
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^ {0,3}(?:(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})$");
    private static final int COMPACT_TABLE_MAX_CELL_LENGTH = 24;
    private static final int COMPACT_TABLE_MAX_TOTAL_WIDTH = 80;
    private static final int DEFAULT_TERMINAL_COLUMNS = 120;
    private static final int MIN_TABLE_CELL_WIDTH = 4;

    private final PrintStream out;
    private final IntSupplier terminalColumnsSupplier;
    private final StringBuilder pending = new StringBuilder();
    private final List<String> pendingTable = new ArrayList<>();
    private boolean inCodeBlock;
    private boolean needsLineBreakBeforeNextBlock;
    private boolean lastOutputBlank;
    private BlockType lastBlockType = BlockType.NONE;

    public TerminalMarkdownRenderer(PrintStream out) {
        this(out, TerminalMarkdownRenderer::resolveTerminalColumns);
    }

    public TerminalMarkdownRenderer(PrintStream out, int terminalColumns) {
        this(out, () -> terminalColumns);
    }

    public TerminalMarkdownRenderer(PrintStream out, IntSupplier terminalColumnsSupplier) {
        this.out = out;
        this.terminalColumnsSupplier = terminalColumnsSupplier == null
                ? TerminalMarkdownRenderer::resolveTerminalColumns
                : terminalColumnsSupplier;
    }

    public static String render(String markdown) {
        return render(markdown, resolveTerminalColumns());
    }

    public static String render(String markdown, int terminalColumns) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream, terminalColumns);
        renderer.append(markdown);
        renderer.finish();
        stream.flush();
        return buffer.toString();
    }

    /**
     * 计算字符串在终端占用的显示列宽（CJK / 全角符号按 2 列，组合符号按 0 列）
     *
     * <p>包级可见：表格排版按显示宽度分配列宽，同包测试需要用同一套算法断言，
     * 避免测试另写一份宽度逻辑与实现跑偏
     *
     * @param value 待测量文本，可为 null
     * @return 显示列宽，null 或空串返回 0
     */
    static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            width += codePointWidth(cp);
            offset += Character.charCount(cp);
        }
        return width;
    }

    private static int codePointWidth(int cp) {
        if (Character.isISOControl(cp)) {
            return 0;
        }
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || (cp >= 0xFE00 && cp <= 0xFE0F)) {
            return 0;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> 2;
            default -> isWideSymbol(cp) ? 2 : 1;
        };
    }

    private static boolean isWideSymbol(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2329 && cp <= 0x232A)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F000 && cp <= 0x1FAFF);
    }

    private static int resolveTerminalColumns() {
        String sysValue = System.getProperty("mini-claude-code.render.columns");
        if (sysValue != null && !sysValue.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(sysValue.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv("COLUMNS");
        if (envValue != null && !envValue.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(envValue.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TERMINAL_COLUMNS;
    }

    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        pending.append(chunk);
        flushCompleteLines();
    }

    public void finish() {
        if (pending.length() > 0) {
            processLine(pending.toString());
            pending.setLength(0);
        }
        flushPendingTable();
    }

    private void flushCompleteLines() {
        int newlineIndex;
        while ((newlineIndex = indexOfNewline(pending)) >= 0) {
            String line = pending.substring(0, newlineIndex);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            processLine(line);
            pending.delete(0, newlineIndex + 1);
        }
    }

    private int indexOfNewline(StringBuilder builder) {
        for (int i = 0; i < builder.length(); i++) {
            if (builder.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void processLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine;

        if (line.trim().startsWith("```")) {
            flushPendingTable();
            toggleCodeBlock(line.trim().substring(3).trim());
            return;
        }

        if (inCodeBlock) {
            writeLine("    " + line, BlockType.CODE_BLOCK);
            return;
        }

        if (looksLikeTableLine(line)) {
            pendingTable.add(line);
            return;
        }

        flushPendingTable();

        if (HORIZONTAL_RULE.matcher(line).matches()) {
            renderHorizontalRule();
            return;
        }

        // 4 空格缩进代码块（不在 fence 内时）；内容是列表项时让给列表分支，
        // 否则 4 空格嵌套列表会被当代码裸输出（inline 标记不渲染）
        if (!inCodeBlock && line.length() >= 4
                && line.charAt(0) == ' ' && line.charAt(1) == ' '
                && line.charAt(2) == ' ' && line.charAt(3) == ' '
                && !line.isBlank()
                && !UNORDERED_LIST.matcher(line).matches()
                && !ORDERED_LIST.matcher(line).matches()) {
            writeLine("    " + line.substring(4), BlockType.CODE_BLOCK);
            return;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            writeBlankLine();
            return;
        }

        var headingMatcher = HEADING.matcher(line);
        if (headingMatcher.matches()) {
            renderHeading(headingMatcher.group(1).length(), sanitizeInline(headingMatcher.group(2).trim()));
            return;
        }

        var orderedMatcher = ORDERED_LIST.matcher(line);
        if (orderedMatcher.matches()) {
            int indentLevel = indentLevel(orderedMatcher.group(1));
            if (indentLevel == 0 && lastBlockType != BlockType.NONE && lastBlockType != BlockType.ORDERED_LIST_ITEM) {
                writeBlankLine();
            }
            writeLine(indent(indentLevel) + orderedMatcher.group(2) + ". " + sanitizeInline(orderedMatcher.group(3).trim()),
                    BlockType.ORDERED_LIST_ITEM);
            return;
        }

        var unorderedMatcher = UNORDERED_LIST.matcher(line);
        if (unorderedMatcher.matches()) {
            int indentLevel = indentLevel(unorderedMatcher.group(1));
            writeLine(indent(indentLevel) + "- " + sanitizeInline(unorderedMatcher.group(2).trim()),
                    BlockType.UNORDERED_LIST_ITEM);
            return;
        }

        if (trimmed.startsWith(">")) {
            int depth = 0;
            while (depth < trimmed.length() && trimmed.charAt(depth) == '>') {
                depth++;
            }
            String prefix = "│".repeat(depth);
            writeLine(AnsiStyle.quotePrefix(prefix) + " " + sanitizeInline(trimmed.substring(depth).trim()),
                    BlockType.QUOTE);
            return;
        }

        writeLine(sanitizeInline(line), BlockType.PARAGRAPH);
    }

    private void toggleCodeBlock(String language) {
        if (!inCodeBlock) {
            ensureBlockSpacing();
            String label = language.isBlank() ? "code" : "code: " + language;
            writeLine(AnsiStyle.codeLabel("┌─ " + label), BlockType.CODE_BLOCK);
            inCodeBlock = true;
        } else {
            writeLine(AnsiStyle.codeLabel("└─ end"), BlockType.CODE_BLOCK);
            inCodeBlock = false;
            writeBlankLine();
        }
    }

    private void renderHeading(int level, String content) {
        ensureBlockSpacing();
        writeLine(AnsiStyle.heading(content), BlockType.HEADING);
        char underline = level == 1 ? '=' : '-';
        writeLine(AnsiStyle.subtle(String.valueOf(underline).repeat(Math.max(content.length(), 4))), BlockType.HEADING);
        writeBlankLine();
    }

    private void renderHorizontalRule() {
        ensureBlockSpacing();
        int width = Math.max(8, Math.min(terminalColumns() - 4, 40));
        writeLine(AnsiStyle.subtle("─".repeat(width)), BlockType.PARAGRAPH);
        writeBlankLine();
    }

    private void flushPendingTable() {
        if (pendingTable.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (String line : pendingTable) {
            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }

            List<String> cells = parseTableRow(line);
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        pendingTable.clear();

        if (rows.isEmpty()) {
            return;
        }

        ensureBlockSpacing();
        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (rows.size() >= 2 && columnCount == 2 && shouldRenderAsKeyValue(rows)) {
            renderKeyValueTable(rows);
            writeBlankLine();
            return;
        }

        int[] naturalWidths = new int[columnCount];
        for (List<String> row : rows) {
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                naturalWidths[i] = Math.max(naturalWidths[i], maxDisplayWidth(cell));
            }
        }
        int[] widths = allocateTableWidths(rows, naturalWidths, columnCount);

        String border = buildTableBorder(widths);
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            List<List<String>> wrappedCells = new ArrayList<>();
            int rowHeight = 1;
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                List<String> wrapped = wrapCell(cell, widths[i]);
                wrappedCells.add(wrapped);
                rowHeight = Math.max(rowHeight, wrapped.size());
            }
            for (int lineIndex = 0; lineIndex < rowHeight; lineIndex++) {
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < columnCount; i++) {
                    List<String> wrapped = wrappedCells.get(i);
                    String cellLine = lineIndex < wrapped.size() ? wrapped.get(lineIndex) : "";
                    line.append(" ").append(padRightDisplay(cellLine, widths[i])).append(" |");
                }
                String renderedLine = rowIndex == 0 ? AnsiStyle.emphasis(line.toString()) : line.toString();
                writeLine(renderedLine, BlockType.TABLE);
            }
            if (rowIndex == 0 && rows.size() > 1) {
                writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
            }
        }
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        writeBlankLine();
    }

    private boolean shouldRenderAsKeyValue(List<List<String>> rows) {
        int maxWidth = 0;
        int totalWidth = 0;

        for (List<String> row : rows) {
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            maxWidth = Math.max(maxWidth, Math.max(displayWidth(left), displayWidth(right)));
            totalWidth = Math.max(totalWidth, displayWidth(left) + displayWidth(right));
        }

        return maxWidth > COMPACT_TABLE_MAX_CELL_LENGTH || totalWidth > COMPACT_TABLE_MAX_TOTAL_WIDTH;
    }

    private void renderKeyValueTable(List<List<String>> rows) {
        List<String> header = rows.get(0);
        String leftHeader = sanitizeInline(header.get(0));
        String rightHeader = sanitizeInline(header.get(1));
        writeLine(AnsiStyle.emphasis(leftHeader + " / " + rightHeader), BlockType.TABLE);
        writeLine(AnsiStyle.subtle("-".repeat(Math.max((leftHeader + " / " + rightHeader).length(), 8))), BlockType.TABLE);

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            writeLine(AnsiStyle.emphasis("- " + left), BlockType.TABLE);
            if (!right.isBlank()) {
                writeLine("  " + right, BlockType.TABLE);
            }
            if (i < rows.size() - 1) {
                writeBlankLine();
            }
        }
    }

    private List<String> parseTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private String buildTableBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append("+");
        }
        return border.toString();
    }

    private int[] allocateTableWidths(List<List<String>> rows, int[] naturalWidths, int columnCount) {
        int available = Math.max(columnCount * MIN_TABLE_CELL_WIDTH, terminalColumns() - (columnCount * 3 + 1));
        int[] widths = new int[columnCount];
        int base = Math.max(MIN_TABLE_CELL_WIDTH, Math.min(12, available / Math.max(1, columnCount)));
        int used = 0;
        for (int i = 0; i < columnCount; i++) {
            int headerWidth = rows.isEmpty() || rows.get(0).size() <= i ? 0 : displayWidth(sanitizeInline(rows.get(0).get(i)));
            int minWidth = Math.max(MIN_TABLE_CELL_WIDTH, Math.min(base, Math.max(headerWidth, MIN_TABLE_CELL_WIDTH)));
            widths[i] = Math.min(Math.max(naturalWidths[i], MIN_TABLE_CELL_WIDTH), minWidth);
            used += widths[i];
        }

        while (used > available) {
            int candidate = widestShrinkableColumn(widths);
            if (candidate < 0) {
                break;
            }
            widths[candidate]--;
            used--;
        }

        int remaining = available - used;
        while (remaining > 0) {
            int candidate = widestUnmetColumn(widths, naturalWidths);
            if (candidate < 0) {
                break;
            }
            widths[candidate]++;
            remaining--;
        }
        return widths;
    }

    private int widestShrinkableColumn(int[] widths) {
        int candidate = -1;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] <= MIN_TABLE_CELL_WIDTH) {
                continue;
            }
            if (candidate < 0 || widths[i] > widths[candidate]) {
                candidate = i;
            }
        }
        return candidate;
    }

    private int widestUnmetColumn(int[] widths, int[] naturalWidths) {
        int candidate = -1;
        int candidateGap = 0;
        for (int i = 0; i < widths.length; i++) {
            int gap = naturalWidths[i] - widths[i];
            if (gap <= 0) {
                continue;
            }
            if (candidate < 0 || gap > candidateGap) {
                candidate = i;
                candidateGap = gap;
            }
        }
        return candidate;
    }

    private boolean looksLikeTableLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (TABLE_SEPARATOR.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.contains("|") && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private void ensureBlockSpacing() {
        if (needsLineBreakBeforeNextBlock && !lastOutputBlank) {
            out.println();
            lastOutputBlank = true;
        }
        needsLineBreakBeforeNextBlock = false;
    }

    private void writeLine(String line) {
        writeLine(line, BlockType.PARAGRAPH);
    }

    private void writeLine(String line, BlockType blockType) {
        out.println(line);
        lastOutputBlank = line.isBlank();
        needsLineBreakBeforeNextBlock = blockType != BlockType.ORDERED_LIST_ITEM
                && blockType != BlockType.UNORDERED_LIST_ITEM
                && blockType != BlockType.QUOTE;
        if (!line.isBlank()) {
            lastBlockType = blockType;
        }
    }

    private void writeBlankLine() {
        if (!lastOutputBlank) {
            out.println();
            lastOutputBlank = true;
        }
        needsLineBreakBeforeNextBlock = false;
    }

    // 转义占位符：\X 的 X 换成私有区字符，跑完 markdown 正则后再还原
    private static final String ESCAPED_MARKERS = "*_`~[]()<>\\#";

    private String sanitizeInline(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && ESCAPED_MARKERS.indexOf(value.charAt(i + 1)) >= 0) {
                escaped.append(placeholderFor(value.charAt(i + 1)));
                i++;
                continue;
            }
            escaped.append(c);
        }
        String s = escaped.toString();

        boolean color = AnsiStyle.isEnabled();
        String bold = color ? AnsiStyle.PREFIX_BOLD : "";
        String italic = color ? AnsiStyle.PREFIX_ITALIC : "";
        String reset = color ? AnsiStyle.RESET_SEQ : "";
        String codeColor = color ? AnsiStyle.PREFIX_CODE : "";
        String dimUrl = color ? AnsiStyle.PREFIX_DIM_URL : "";

        s = s.replaceAll("\\*\\*(.+?)\\*\\*", bold + "$1" + reset);
        s = s.replaceAll("__(.+?)__", bold + "$1" + reset);
        s = s.replaceAll("~~(.+?)~~", "[~$1~]");
        s = s.replaceAll("`([^`]+)`", codeColor + "$1" + reset);
        s = s.replaceAll("(?<![\\w\\u4e00-\\u9fa5])\\*([^*\\n]+?)\\*(?![\\w\\u4e00-\\u9fa5])",
                italic + "$1" + reset);
        // italic _text_：词边界限制，snake_case 不吃
        s = s.replaceAll("\\b_(?!\\s)([^_\\n]+?)_(?!\\s)\\b", italic + "$1" + reset);

        // 链接 [text](url) → text (url)
        s = s.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1 (" + dimUrl + "$2" + reset + ")");

        // 还原转义占位符为字面字符
        StringBuilder restored = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            restored.append(restorePlaceholder(s.charAt(i)));
        }
        return restored.toString().stripTrailing();
    }

    private static char placeholderFor(char marker) {
        return (char) (0xE000 + ESCAPED_MARKERS.indexOf(marker));
    }

    private static char restorePlaceholder(char c) {
        if (c >= 0xE000 && c < 0xE000 + ESCAPED_MARKERS.length()) {
            return ESCAPED_MARKERS.charAt(c - 0xE000);
        }
        return c;
    }


    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private String padRightDisplay(String value, int width) {
        int displayWidth = displayWidth(value);
        if (displayWidth >= width) {
            return value;
        }
        return value + " ".repeat(width - displayWidth);
    }

    private List<String> wrapCell(String value, int width) {
        List<String> lines = new ArrayList<>();
        int targetWidth = Math.max(MIN_TABLE_CELL_WIDTH, width);
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }

        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            int cpWidth = codePointWidth(cp);
            int charCount = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                if (lineWidth > 0 && lineWidth < targetWidth) {
                    line.append(' ');
                    lineWidth++;
                }
                offset += charCount;
                continue;
            }
            if (lineWidth > 0 && lineWidth + cpWidth > targetWidth) {
                lines.add(line.toString().stripTrailing());
                line.setLength(0);
                lineWidth = 0;
            }
            line.appendCodePoint(cp);
            lineWidth += cpWidth;
            offset += charCount;
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    private int maxDisplayWidth(String value) {
        int max = 0;
        String text = value == null ? "" : value;
        for (String part : text.split("\\R", -1)) {
            max = Math.max(max, displayWidth(part));
        }
        return max;
    }

    private int terminalColumns() {
        try {
            return Math.max(40, terminalColumnsSupplier.getAsInt());
        } catch (Exception e) {
            return DEFAULT_TERMINAL_COLUMNS;
        }
    }

    private int indentLevel(String leadingWhitespace) {
        if (leadingWhitespace == null || leadingWhitespace.isEmpty()) {
            return 0;
        }
        int spaces = 0;
        for (char ch : leadingWhitespace.toCharArray()) {
            spaces += ch == '\t' ? 4 : 1;
        }
        return Math.max(0, spaces / 2);
    }

    private String indent(int level) {
        return "  ".repeat(Math.max(level, 0));
    }

    private enum BlockType {
        NONE,
        HEADING,
        PARAGRAPH,
        ORDERED_LIST_ITEM,
        UNORDERED_LIST_ITEM,
        QUOTE,
        TABLE,
        CODE_BLOCK
    }
}
