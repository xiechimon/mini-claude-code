package com.xmon.nanoagent.core;

/**
 * Markdown 元素到 ANSI 转义序列的样式映射
 *
 * <p>每个方法接收纯文本，返回带 ANSI 转义序列的着色文本。
 * 消费端（{@link MarkdownRenderer}）按 AST 节点类型分发到对应方法。
 */
public interface MarkdownTheme {

    /**
     * 标题
     *
     * @param text  标题文本
     * @param level 标题级别 1-6
     * @return 着色后的标题文本
     */
    String heading(String text, int level);

    /**
     * 粗体
     *
     * @param text 文本
     * @return 粗体文本
     */
    String bold(String text);

    /**
     * 斜体
     *
     * @param text 文本
     * @return 斜体文本
     */
    String italic(String text);

    /**
     * 行内代码
     *
     * @param text 代码文本
     * @return 以暗色背景标记的代码文本
     */
    String code(String text);

    /**
     * 代码块内容行
     *
     * @param text 一行代码
     * @return 着色后的代码行
     */
    String codeBlock(String text);

    /**
     * 代码块边框（``` 围栏）
     *
     * @param text 围栏文本，如 {@code ```java}
     * @return 着色后的围栏文本
     */
    String codeBlockBorder(String text);

    /**
     * 引用块内容行
     *
     * @param text 一行引用文本
     * @return 带引用前缀和暗色样式的文本
     */
    String blockquote(String text);

    /**
     * 列表项目符号
     *
     * @param marker 符号文本，如 {@code •}
     * @return 着色后的符号
     */
    String listBullet(String marker);

    /**
     * 链接
     *
     * @param text 链接文本
     * @param url  链接 URL
     * @return 着色后的链接，含 URL 提示
     */
    String link(String text, String url);

    /**
     * 水平分隔线
     *
     * @return 分隔线字符
     */
    String hr();

    /**
     * 删除线
     *
     * @param text 文本
     * @return 带删除线标记的文本
     */
    String strikethrough(String text);
}