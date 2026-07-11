package com.xmon.nanoagent.core;

/**
 * {@link MarkdownTheme} 的默认纯 ANSI 实现
 *
 * <p>颜色方案：标题 bold+bright，bullet 黄，代码/引用暗色，链接下划线，删除线 ANSI 9。
 * 无额外依赖，仅 ANSI 转义序列。
 */
public final class DefaultMarkdownTheme implements MarkdownTheme {

    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ITALIC = "\033[3m";
    private static final String UNDERLINE = "\033[4m";
    private static final String STRIKETHROUGH = "\033[9m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    @Override
    public String heading(String text, int level) {
        if (level <= 2) {
            return BOLD + UNDERLINE + text + RESET;
        }
        return BOLD + text + RESET;
    }

    @Override
    public String bold(String text) {
        return BOLD + text + RESET;
    }

    @Override
    public String italic(String text) {
        return ITALIC + text + RESET;
    }

    @Override
    public String code(String text) {
        return DIM + text + RESET;
    }

    @Override
    public String codeBlock(String text) {
        return DIM + text + RESET;
    }

    @Override
    public String codeBlockBorder(String text) {
        return DIM + text + RESET;
    }

    @Override
    public String blockquote(String text) {
        return DIM + text + RESET;
    }

    @Override
    public String listBullet(String marker) {
        return YELLOW + marker + RESET;
    }

    @Override
    public String link(String text, String url) {
        return UNDERLINE + CYAN + text + RESET + DIM + " (" + url + ")" + RESET;
    }

    @Override
    public String hr() {
        return DIM + "─".repeat(40) + RESET;
    }

    @Override
    public String strikethrough(String text) {
        return STRIKETHROUGH + text + RESET;
    }
}