package com.xmon.nanoagent.core;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.BulletListItem;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.OrderedListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Markdown 增量渲染器
 *
 * <p>每次收到文本增量时全量 re-parse 缓冲区，已完成的行落定输出，未完成的末行
 * 保留在缓冲区中等待后续增量补全。块切换时（工具/思考/消息完成）先 flush 缓冲区。
 *
 * <p>依赖 flexmark 做 AST 解析，本类只负责 AST → ANSI 行的转换和增量输出策略。
 */
public final class MarkdownRenderer {

    private final PrintWriter terminal;
    private final MarkdownTheme theme;
    private final Parser parser;
    private final StringBuilder buffer = new StringBuilder();
    private int lastLineCount;

    /**
     * 创建渲染器
     *
     * @param terminal 终端输出
     * @param theme    ANSI 样式主题
     */
    public MarkdownRenderer(PrintWriter terminal, MarkdownTheme theme) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.theme = Objects.requireNonNull(theme, "theme");
        this.parser = Parser.builder(new MutableDataSet())
                .extensions(List.of(StrikethroughExtension.create()))
                .build();
    }

    /**
     * 追加文本增量并渲染
     *
     * @param text 一段 markdown 文本
     */
    public void append(String text) {
        buffer.append(text);
        render();
    }

    /**
     * 强制输出缓冲区中未完成的末行
     *
     * <p>块切换时调用，确保 markdown 流的末行不再被后续增量补全。
     */
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        // 模拟末尾换行使末行变为完成行
        String flushed = buffer.toString() + "\n";
        List<String> lines = parseAndRender(flushed);
        for (int i = lastLineCount; i < lines.size(); i++) {
            terminal.println(lines.get(i));
        }
        terminal.flush();
        buffer.setLength(0);
        lastLineCount = 0;
    }

    /**
     * 解析并增量输出——只输出已完成的行，末行保留在缓冲区
     */
    private void render() {
        String source = buffer.toString();
        boolean endsWithNewline = source.endsWith("\n");
        List<String> newLines = parseAndRender(source);

        // 完成行：换行结尾时全部完成，否则最后一行是未完成行
        int completeCount = endsWithNewline ? newLines.size() : Math.max(0, newLines.size() - 1);

        // 输出新增的完成行
        for (int i = lastLineCount; i < completeCount; i++) {
            terminal.println(newLines.get(i));
        }
        terminal.flush();
        lastLineCount = completeCount;
    }

    /**
     * 解析 markdown 并转为 ANSI 行列表
     */
    private List<String> parseAndRender(String source) {
        Node document = parser.parse(source);
        List<String> lines = new ArrayList<>();
        renderBlockChildren(document, lines);
        return lines;
    }

    /**
     * 递归渲染块级子节点
     */
    private void renderBlockChildren(Node parent, List<String> lines) {
        for (Node child : parent.getChildren()) {
            if (child instanceof Heading h) {
                lines.add(theme.heading(renderInlineChildren(h), h.getLevel()));
            } else if (child instanceof Paragraph p) {
                lines.add(renderInlineChildren(p));
            } else if (child instanceof FencedCodeBlock code) {
                String info = code.getInfo() != null ? code.getInfo().toString() : "";
                lines.add(theme.codeBlockBorder("```" + info));
                // flexmark 的代码内容自带结尾换行，去掉它避免多渲染一行空代码行
                String content = code.getContentChars().toString();
                if (content.endsWith("\n")) {
                    content = content.substring(0, content.length() - 1);
                }
                for (String codeLine : content.split("\n", -1)) {
                    lines.add(theme.codeBlock("  " + codeLine));
                }
                lines.add(theme.codeBlockBorder("```"));
            } else if (child instanceof BulletList list) {
                for (Node item : list.getChildren()) {
                    if (item instanceof BulletListItem li) {
                        lines.add(theme.listBullet("• ") + renderInlineChildren(li));
                    }
                }
            } else if (child instanceof OrderedList list) {
                int num = list.getStartNumber();
                for (Node item : list.getChildren()) {
                    if (item instanceof OrderedListItem li) {
                        lines.add(theme.listBullet(num + ". ") + renderInlineChildren(li));
                        num++;
                    }
                }
            } else if (child instanceof BlockQuote bq) {
                for (Node bqChild : bq.getChildren()) {
                    lines.add(theme.blockquote("│ " + renderInlineChildren(bqChild)));
                }
            } else if (child instanceof ThematicBreak) {
                lines.add(theme.hr());
            } else {
                // 未知块类型：递归渲染子节点
                renderBlockChildren(child, lines);
            }
        }
    }

    /**
     * 渲染行内子节点为纯文本
     */
    private String renderInlineChildren(Node parent) {
        StringBuilder sb = new StringBuilder();
        for (Node child : parent.getChildren()) {
            if (child instanceof Text t) {
                sb.append(t.getChars());
            } else if (child instanceof StrongEmphasis se) {
                sb.append(theme.bold(renderInlineChildren(se)));
            } else if (child instanceof Emphasis e) {
                sb.append(theme.italic(renderInlineChildren(e)));
            } else if (child instanceof Code c) {
                sb.append(theme.code(c.getText().toString()));
            } else if (child instanceof Strikethrough s) {
                sb.append(theme.strikethrough(renderInlineChildren(s)));
            } else if (child instanceof Link l) {
                String text = renderInlineChildren(l);
                String url = l.getUrl().toString();
                sb.append(theme.link(text, url));
            } else if (child instanceof com.vladsch.flexmark.ast.SoftLineBreak) {
                sb.append(' ');
            } else if (child instanceof com.vladsch.flexmark.ast.HardLineBreak) {
                sb.append('\n');
            } else {
                sb.append(renderInlineChildren(child));
            }
        }
        return sb.toString();
    }
}