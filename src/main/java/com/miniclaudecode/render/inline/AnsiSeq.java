package com.miniclaudecode.render.inline;

/**
 * 原生 ANSI 控制序列常量与工具方法
 *
 * <p>区别于 {@link com.miniclaudecode.util.AnsiStyle}（只做颜色与文字样式），
 * 这里覆盖光标控制、行清理、滚动区域等结构化操作，inline 流式 TUI 的所有
 * 局部重绘 / 底部状态栏都依赖这些原语
 *
 * <p>所有常量保持 ESC（）开头，方便直接 {@code System.out.print}
 */
public final class AnsiSeq {

    public static final String ESC = "";

    // === 光标 ===
    public static final String SAVE_CURSOR = ESC + "7";
    public static final String RESTORE_CURSOR = ESC + "8";
    public static final String HIDE_CURSOR = ESC + "[?25l";
    public static final String SHOW_CURSOR = ESC + "[?25h";

    // === 清除 ===
    public static final String CLEAR_LINE = ESC + "[2K";
    public static final String CLEAR_TO_EOL = ESC + "[K";
    public static final String CLEAR_TO_EOS = ESC + "[J";

    // === 滚动区域（DECSTBM） ===
    public static final String RESET_SCROLL_REGION = ESC + "[r";

    // === 文本样式 ===
    public static final String REVERSE_ON = ESC + "[7m";
    public static final String REVERSE_OFF = ESC + "[27m";
    public static final String RESET = ESC + "[0m";
    public static final String BOLD = ESC + "[1m";
    public static final String DIM = ESC + "[2m";

    private AnsiSeq() {
    }

    public static String setScrollRegion(int top, int bottom) {
        return ESC + "[" + top + ";" + bottom + "r";
    }

    public static String moveCursor(int row, int col) {
        return ESC + "[" + row + ";" + col + "H";
    }

    public static String moveUp(int n) {
        return ESC + "[" + n + "A";
    }

    public static String moveDown(int n) {
        return ESC + "[" + n + "B";
    }
}
