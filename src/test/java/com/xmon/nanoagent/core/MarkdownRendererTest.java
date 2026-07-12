package com.xmon.nanoagent.core;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试 Markdown 增量渲染器的落定策略与 AST → 文本行映射
 *
 * <p>用一个恒等主题替换 ANSI 着色，把样式调用还原成纯文本标记，
 * 使断言聚焦在增量输出与节点分发上，而不是具体的转义序列。
 */
final class MarkdownRendererTest {

    private final StringWriter out = new StringWriter();
    private final MarkdownRenderer renderer =
            new MarkdownRenderer(new PrintWriter(out), new IdentityTheme());

    @Test
    void trailingIncompleteLineIsHeldBackUntilCompleted() {
        renderer.append("hello");

        assertEquals("", out.toString());

        renderer.append(" world\n");

        assertEquals("hello world\n", out.toString());
    }

    @Test
    void flushForcesOutTheHeldBackTrailingLine() {
        renderer.append("incomplete");
        renderer.flush();

        assertEquals("incomplete\n", out.toString());
    }

    @Test
    void flushIsANoOpWhenNothingIsBuffered() {
        renderer.flush();

        assertEquals("", out.toString());
    }

    @Test
    void flushAfterACompletedLineDoesNotDuplicateIt() {
        renderer.append("done\n");
        renderer.flush();

        assertEquals("done\n", out.toString());
    }

    @Test
    void multipleCompletedParagraphsEmitInOrder() {
        renderer.append("first\n\nsecond\n");

        assertEquals("first\nsecond\n", out.toString());
    }

    @Test
    void headingLevelIsPassedToTheTheme() {
        renderer.append("# Title\n## Sub\n### Deep\n");

        assertEquals(
                "heading(Title,1)\nheading(Sub,2)\nheading(Deep,3)\n",
                out.toString());
    }

    @Test
    void fencedCodeBlockKeepsItsLanguageAndContents() {
        renderer.append("```java\nint x = 1;\n```\n");

        assertEquals("border(```java)\n  int x = 1;\nborder(```)\n", out.toString());
    }

    @Test
    void bulletAndOrderedListsRenderTheirMarkers() {
        renderer.append("- alpha\n- beta\n\n1. first\n2. second\n");

        assertEquals(
                "bullet(• )alpha\nbullet(• )beta\n"
                        + "bullet(1. )first\nbullet(2. )second\n",
                out.toString());
    }

    @Test
    void blockquoteAndThematicBreakMapToTheirOwnStyles() {
        renderer.append("> quoted\n\n---\n");

        assertEquals("quote(│ quoted)\n<hr>\n", out.toString());
    }

    @Test
    void inlineEmphasisCodeStrikethroughAndLinksAreDispatched() {
        renderer.append("**bold**, *italic*, `code`, ~~gone~~, [text](https://x)\n");

        assertEquals(
                "bold(bold), italic(italic), code(code), strike(gone), link(text|https://x)\n",
                out.toString());
    }

    @Test
    void softLineBreaksWithinAParagraphJoinWithASpace() {
        renderer.append("first\nsecond\n");

        assertEquals("first second\n", out.toString());
    }

    /** 恒等主题：把样式调用还原成纯文本标记，便于断言分发而非转义序列。 */
    private static final class IdentityTheme implements MarkdownTheme {

        @Override
        public String heading(String text, int level) {
            return "heading(" + text + "," + level + ")";
        }

        @Override
        public String bold(String text) {
            return "bold(" + text + ")";
        }

        @Override
        public String italic(String text) {
            return "italic(" + text + ")";
        }

        @Override
        public String code(String text) {
            return "code(" + text + ")";
        }

        @Override
        public String codeBlock(String text) {
            return text;
        }

        @Override
        public String codeBlockBorder(String text) {
            return "border(" + text + ")";
        }

        @Override
        public String blockquote(String text) {
            return "quote(" + text + ")";
        }

        @Override
        public String listBullet(String marker) {
            return "bullet(" + marker + ")";
        }

        @Override
        public String link(String text, String url) {
            return "link(" + text + "|" + url + ")";
        }

        @Override
        public String hr() {
            return "<hr>";
        }

        @Override
        public String strikethrough(String text) {
            return "strike(" + text + ")";
        }
    }
}
