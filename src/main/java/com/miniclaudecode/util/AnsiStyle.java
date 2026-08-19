package com.miniclaudecode.util;

/**
 * 终端 ANSI 样式辅助
 */
public final class AnsiStyle {
    /**
     * 前缀/复位序列公开给同包组装场景（如 TerminalMarkdownRenderer 的正则替换）
     */
    public static final String PREFIX_BOLD = "\u001B[1m";
    public static final String PREFIX_ITALIC = "\u001B[3m";
    public static final String PREFIX_CODE = "\u001B[33m";
    public static final String PREFIX_DIM_URL = "\u001B[2;37m";
    public static final String RESET_SEQ = "\u001B[0m";
    /** 整屏擦除 + 清 scrollback + 光标归位；/clear 重放 banner 用 */
    public static final String CLEAR_SCREEN = "\u001B[2J\u001B[3J\u001B[H";

    private static final String RESET = RESET_SEQ;
    private static final String BOLD = PREFIX_BOLD;
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = PREFIX_ITALIC;
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";
    private static final String PURPLE = "\u001B[38;5;141m";
    private static final boolean ENABLED = determineEnabled();

    private AnsiStyle() {
    }

    public static String heading(String text) {
        return wrap(BOLD + CYAN, text);
    }

    public static String section(String text) {
        return wrap(BOLD + GREEN, text);
    }

    public static String answerMarker() {
        return wrap(BOLD + GREEN, "▪");
    }

    public static String subtle(String text) {
        return wrap(DIM + GRAY, text);
    }

    public static String thinking(String text) {
        return wrap(ITALIC + GRAY, text);
    }

    public static String userMessageBlock(String text, int ignoredColumns) {
        String safe = text == null ? "" : text.strip();
        String[] lines = safe.isEmpty() ? new String[]{""} : safe.split("\\R", -1);
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                rendered.append('\n');
            }
            rendered.append(userMessageBlockLine(lines[i]));
        }
        return rendered.toString();
    }

    private static String userMessageBlockLine(String text) {
        String safe = text == null ? "" : text;
        String prefix = "> ";
        if (!ENABLED) {
            return prefix + safe;
        }
        return PURPLE + prefix + RESET + safe;
    }

    public static String codeLabel(String text) {
        return wrap(BOLD + YELLOW, text);
    }

    public static String error(String text) {
        return wrap(BOLD + RED, text);
    }

    public static String quotePrefix(String text) {
        return wrap(DIM + CYAN, text);
    }

    public static String emphasis(String text) {
        return wrap(BOLD, text);
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static String wrap(String prefix, String text) {
        if (!ENABLED || text == null || text.isEmpty()) {
            return text;
        }
        return prefix + text + RESET;
    }

    private static boolean determineEnabled() {
        String property = System.getProperty("mini-claude-code.render.color");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }

        if (System.getenv("NO_COLOR") != null) {
            return false;
        }

        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }
}
